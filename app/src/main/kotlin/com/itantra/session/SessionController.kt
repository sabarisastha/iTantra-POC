package com.itantra.session

import android.util.Log
import com.itantra.stt.RecognizedSentence
import com.itantra.stt.SpeechToTextEngine
import com.itantra.transport.ConnectionState
import com.itantra.transport.Payload
import com.itantra.transport.PayloadType
import com.itantra.transport.TransportChannel
import com.itantra.tts.TextToSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "ITANTRA-SESSION"
private const val MAX_TRANSCRIPT_ENTRIES = 200
private const val FLOOR_TIMEOUT_MS = 15_000L

/**
 * Orchestrates the Walkie-Talkie communication pipeline and Half-Duplex Floor Control / Full-Duplex Phone mode.
 */
class SessionController(
    private val sttEngine: SpeechToTextEngine,
    private val ttsEngine: TextToSpeechEngine,
    val transport: TransportChannel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val localPeerId: String = UUID.randomUUID().toString().take(8)

    // ── Exposed State ─────────────────────────────────────────────────────────

    private val _mode = MutableStateFlow(SessionMode.PTT)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(SupportedLanguage.HINDI)
    val selectedLanguage: StateFlow<SupportedLanguage> = _selectedLanguage.asStateFlow()

    /** True when a session handshake has succeeded with a remote peer. */
    private val _sessionValidated = MutableStateFlow(false)
    val sessionValidated: StateFlow<Boolean> = _sessionValidated.asStateFlow()

    @Volatile private var localFloorRequestTimestamp: Long = 0L
    @Volatile private var localModeChangeTimestamp: Long = 0L
    private var floorTimeoutJob: Job? = null
    private var heartbeatJob: Job? = null
    
    // No raw PCM fields — Phone Mode uses VAD-gated STT (same as PTT, but no floor control)

    init {
        Log.d(TAG, "SessionController initialized localPeerId=$localPeerId")
        collectOutgoing()
        collectIncoming()
        monitorConnectionState()
    }

    // ── Connection monitoring ─────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var failedCount = 0
            while (true) {
                delay(10_000L)
                val result = runCatching { 
                    transport.send(Payload(
                        type = PayloadType.CONTROL,
                        text = "HEARTBEAT",
                        senderId = localPeerId,
                        sentTimestampMs = System.currentTimeMillis()
                    ))
                }
                if (result.isFailure) {
                    failedCount++
                    if (failedCount >= 3) {
                        Log.e(TAG, "[ITANTRA-SESSION] Heartbeat failed 3 times, disconnecting")
                        disconnect()
                        break
                    }
                } else {
                    failedCount = 0
                }
            }
        }
    }

    private fun monitorConnectionState() {
        scope.launch {
            transport.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        // Preload before the first PTT release / first incoming speech, avoiding
                        // a model-load pause on the active communication path.
                        sttEngine.setLanguage(_selectedLanguage.value.code)
                        ttsEngine.prewarm(_selectedLanguage.value.code)
                        startHeartbeat()
                        Log.d(TAG, "[ITANTRA-SESSION] state=CONNECTED — sending SESSION_INIT")
                        sendControlPayload(
                            PayloadType.CONTROL,
                            text = "SESSION_INIT:${_selectedLanguage.value.code}:${_mode.value.name}"
                        )
                        if (_mode.value == SessionMode.PHONE) {
                            Log.d(TAG, "[ITANTRA-SESSION] Connected in PHONE mode — auto-starting continuous STT")
                            sttEngine.start()
                        }
                    }
                    is ConnectionState.Idle, is ConnectionState.Error -> {
                        Log.d(TAG, "[ITANTRA-SESSION] state=DISCONNECTED — resetting session")
                        onSessionLost()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun onSessionLost() {
        heartbeatJob?.cancel()
        _sessionValidated.value = false
        floorTimeoutJob?.cancel()
        sttEngine.stop()
        _floorState.value = FloorState.IDLE
        _mode.value = SessionMode.PTT
        Log.d(TAG, "[ITANTRA-SESSION] session reset complete")
    }

    // ── Public Control API ────────────────────────────────────────────────────

    fun isSessionActive(): Boolean =
        transport.connectionState.value is ConnectionState.Connected

    fun setMode(newMode: SessionMode) {
        requestModeChange(newMode)
    }

    fun requestModeChange(newMode: SessionMode) {
        if (_mode.value == newMode) return
        val reqTime = System.currentTimeMillis()
        localModeChangeTimestamp = reqTime
        Log.d(TAG, "requestModeChange: $newMode at $reqTime")
        applyModeTransition(newMode)
        scope.launch {
            sendControlPayload(PayloadType.MODE_CHANGE, text = newMode.name, timestamp = reqTime)
        }
    }

    private fun applyModeTransition(newMode: SessionMode) {
        val old = _mode.value
        if (old == newMode) return
        _mode.value = newMode
        Log.d(TAG, "Mode: $old -> $newMode")
        when {
            old == SessionMode.PTT && newMode == SessionMode.PHONE -> {
                // PTT -> PHONE: release floor, then start continuous VAD-gated STT.
                // Phone Mode is full-duplex text — no floor control, no raw PCM.
                if (_floorState.value == FloorState.LOCAL_TRANSMITTING ||
                    _floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                    scope.launch { sendControlPayload(PayloadType.RELEASE_FLOOR) }
                }
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.IDLE
                sttEngine.stop()  // ensure clean state before restart
                sttEngine.start() // continuous VAD-gated STT in phone mode
                Log.d(TAG, "[ITANTRA-PHONE] Continuous VAD-gated STT started")
            }
            old == SessionMode.PHONE && newMode == SessionMode.PTT -> {
                // PHONE -> PTT: stop continuous STT; floor control takes over.
                sttEngine.stop()
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.IDLE
                Log.d(TAG, "[ITANTRA-PHONE] Continuous STT stopped; reverting to PTT floor control")
            }
        }
    }


    fun setLanguage(language: SupportedLanguage) {
        if (isSessionActive()) {
            Log.w(TAG, "setLanguage rejected: session active — language is LOCKED to ${_selectedLanguage.value.displayName}")
            return
        }
        _selectedLanguage.value = language
        sttEngine.setLanguage(language.code)
        Log.d(TAG, "[ITANTRA-SESSION] Language set to ${language.displayName} (${language.code})")
    }

    fun requestFloorAndArm() {
        if (_mode.value == SessionMode.PHONE) return
        if (!isSessionActive()) {
            Log.w(TAG, "requestFloorAndArm rejected: not connected")
            return
        }

        val currentFloor = _floorState.value
        Log.d(TAG, "[ITANTRA-PTT] requestFloorAndArm() floorState=$currentFloor")

        if (currentFloor == FloorState.REMOTE_TRANSMITTING) {
            Log.w(TAG, "[ITANTRA-PTT] PTT denied: remote owns floor")
            _floorState.value = FloorState.CHANNEL_BUSY
            return
        }

        if (currentFloor == FloorState.LOCAL_TRANSMITTING ||
            currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            Log.d(TAG, "[ITANTRA-PTT] PTT already active — ignored")
            return
        }

        val reqTime = System.currentTimeMillis()
        localFloorRequestTimestamp = reqTime

        // ── Optimistic local acquisition ─────────────────────────────────────
        // In a direct P2P walkie-talkie there may be no peer to send FLOOR_GRANTED.
        // We immediately acquire the floor locally so STT starts without any
        // round-trip delay, then notify the peer asynchronously.
        // If the peer simultaneously requested the floor, the collision is resolved
        // by the timestamp tie-breaker in handleIncomingFloorRequest.
        _floorState.value = FloorState.LOCAL_TRANSMITTING
        sttEngine.start()
        Log.d(TAG, "[ITANTRA-PTT] Floor acquired locally → sttEngine.start() called (reqTime=$reqTime)")

        scope.launch {
            // Notify peer that we are now transmitting
            sendControlPayload(PayloadType.REQUEST_FLOOR, timestamp = reqTime)

            // ── Collision guard ──────────────────────────────────────────────
            // If the peer sends FLOOR_DENIED within 250ms (they were also transmitting),
            // handleIncomingFloorDenied() will stop us and set CHANNEL_BUSY.
            // Nothing extra needed here — the handler covers that path.
            delay(250L)
            if (_floorState.value == FloorState.LOCAL_TRANSMITTING) {
                Log.d(TAG, "[ITANTRA-PTT] Floor held after 250ms — no collision detected, continuing STT")
            }
        }
    }

    fun releaseFloorAndDisarm() {
        if (_mode.value == SessionMode.PHONE) return

        val currentFloor = _floorState.value
        Log.d(TAG, "[ITANTRA-PTT] releaseFloorAndDisarm() floorState=$currentFloor")

        if (currentFloor == FloorState.LOCAL_TRANSMITTING ||
            currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            // Stop STT FIRST so the last VAD segment is flushed before we send RELEASE_FLOOR.
            // The remote peer should not receive a new SPEECH payload after RELEASE_FLOOR.
            sttEngine.stop()
            Log.d(TAG, "[ITANTRA-PTT] sttEngine.stop() called on PTT release")
            _floorState.value = FloorState.IDLE
            Log.d(TAG, "[ITANTRA-PTT] RELEASE_FLOOR sent — floor returned to IDLE")
            scope.launch {
                sendControlPayload(PayloadType.RELEASE_FLOOR, timestamp = System.currentTimeMillis())
            }
        } else if (currentFloor == FloorState.CHANNEL_BUSY) {
            _floorState.value = FloorState.REMOTE_TRANSMITTING
        }
    }

    fun triggerAlert() {
        Log.d(TAG, "triggerAlert()")
        scope.launch {
            sendSentence(
                RecognizedSentence(
                    text         = "[ALERT] Manual alert triggered by operator",
                    languageCode = _selectedLanguage.value.code,
                    isAlert      = true,
                    timestampMs  = System.currentTimeMillis(),
                    sequenceId   = -1,
                )
            )
        }
    }

    fun replayReceivedAudio(sequenceId: Int): Boolean = ttsEngine.replay(sequenceId)

    fun disconnect() {
        Log.d(TAG, "disconnect() from SessionController")
        floorTimeoutJob?.cancel()

        val wasTransmitting = _floorState.value == FloorState.LOCAL_TRANSMITTING ||
                              _floorState.value == FloorState.WAITING_FOR_CHANNEL

        sttEngine.stop()

        _floorState.value = FloorState.IDLE
        _mode.value = SessionMode.PTT
        _transcript.value = emptyList()
        _sessionValidated.value = false

        scope.launch {
            if (wasTransmitting) {
                runCatching { sendControlPayload(PayloadType.RELEASE_FLOOR) }
            }
            transport.disconnect()
        }
    }

    fun shutdown() {
        Log.d(TAG, "shutdown() from SessionController")
        disconnect()
        ttsEngine.shutdown()
        scope.cancel()
    }

    // ── Outgoing pipeline: STT → Transport ───────────────────────────────────

    private fun collectOutgoing() {
        scope.launch {
            sttEngine.recognizedSentences
                .catch { e -> Log.e(TAG, "STT flow error", e) }
                .collect { sentence ->
                    // Both PTT and PHONE modes transmit recognized text payloads.
                    // Phone Mode uses continuous VAD-gated STT (no floor control).
                    sendSentence(sentence)
                }
        }
    }

    private suspend fun sendSentence(sentence: RecognizedSentence) {
        val sttCompleteMs = System.currentTimeMillis()
        val payload = Payload(
            type            = if (sentence.isAlert) PayloadType.ALERT else PayloadType.SPEECH,
            text            = sentence.text,
            languageCode    = sentence.languageCode,
            sequenceId      = sentence.sequenceId,
            sentTimestampMs = sttCompleteMs,
            senderId        = localPeerId,
        )
        runCatching { transport.send(payload) }
            .onFailure { e -> Log.e(TAG, "transport.send() failed", e) }

        appendTranscript(
            TranscriptEntry(
                direction          = Direction.SENT,
                text               = sentence.text,
                languageCode       = sentence.languageCode,
                isAlert            = sentence.isAlert,
                sequenceId         = sentence.sequenceId,
                sessionMode        = _mode.value,
                micPauseDetectedMs = sentence.timestampMs,
                sttCompleteMs      = sttCompleteMs,
                sentMs             = System.currentTimeMillis(),
                receivedMs         = 0L,
                ttsStartedMs       = 0L,
            )
        )
    }

    private suspend fun sendControlPayload(
        type: PayloadType,
        text: String = "",
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val payload = Payload(
            type            = type,
            text            = text,
            sentTimestampMs = timestamp,
            senderId        = localPeerId,
        )
        runCatching { transport.send(payload) }
            .onFailure { e -> Log.e(TAG, "sendControlPayload($type) failed", e) }
    }

    // ── Incoming pipeline: Transport → TTS + Floor Control ───────────────────

    private fun collectIncoming() {
        scope.launch {
            transport.onPayloadReceived
                .catch { e -> Log.e(TAG, "Transport receive flow error", e) }
                .collect { payload ->
                    when (payload.type) {
                        PayloadType.CONTROL,
                        PayloadType.SESSION_INIT,
                        PayloadType.SESSION_ACK   -> handleControl(payload)
                        PayloadType.HEARTBEAT     -> Log.d(TAG, "Incoming HEARTBEAT from ${payload.senderId}")
                        PayloadType.MODE_CHANGE   -> handleIncomingModeChange(payload)
                        PayloadType.REQUEST_FLOOR -> handleIncomingFloorRequest(payload)
                        PayloadType.FLOOR_GRANTED -> handleIncomingFloorGranted(payload)
                        PayloadType.FLOOR_DENIED  -> handleIncomingFloorDenied(payload)
                        PayloadType.RELEASE_FLOOR -> handleIncomingReleaseFloor(payload)
                        PayloadType.SPEECH, PayloadType.ALERT -> handleIncomingSpeech(payload)
                    }
                }
        }
        
        // Raw PCM reception removed — Phone Mode now receives SPEECH text payloads
        // decoded by the remote peer's VAD+STT pipeline and plays them via TTS.
    }

    private fun handleControl(payload: Payload) {
        val text = payload.text
        if (text.startsWith("SESSION_INIT:")) {
            val parts = text.split(":")
            if (parts.size < 3) {
                Log.w(TAG, "Malformed SESSION_INIT payload: $text")
                return
            }
            val remoteLang = parts[1]
            val remoteMode = parts[2]
            val localLang  = _selectedLanguage.value.code

            if (remoteLang != localLang) {
                Log.w(TAG, "[ITANTRA-SESSION] Rejecting language mismatch: local=$localLang remote=$remoteLang")
                _sessionValidated.value = false
                scope.launch {
                    sendControlPayload(PayloadType.CONTROL, "SESSION_REJECT:LANGUAGE_MISMATCH")
                    disconnect()
                }
                return
            }

            _sessionValidated.value = true
            Log.d(TAG, "[ITANTRA-SESSION] Session VALIDATED — localLang=$localLang remoteLang=$remoteLang mode=$remoteMode")

            val parsedMode = runCatching { SessionMode.valueOf(remoteMode) }.getOrNull()
            if (parsedMode != null && parsedMode != _mode.value) {
                applyModeTransition(parsedMode)
            } else if (_mode.value == SessionMode.PHONE) {
                // Ensure continuous STT is running if we re-validated an existing PHONE session
                sttEngine.start()
            }

        } else if (text.startsWith("SESSION_REJECT:")) {
            Log.e(TAG, "[ITANTRA-SESSION] Remote rejected session: $text")
            _sessionValidated.value = false
            scope.launch { disconnect() }
        }
    }

    private fun handleIncomingModeChange(payload: Payload) {
        val requestedMode = runCatching { SessionMode.valueOf(payload.text) }.getOrNull() ?: return
        Log.d(TAG, "Incoming MODE_CHANGE to $requestedMode from ${payload.senderId}")
        if (_mode.value == requestedMode) return

        val localTime  = localModeChangeTimestamp
        val remoteTime = payload.sentTimestampMs

        if (localTime > 0L && Math.abs(System.currentTimeMillis() - localTime) < 3000L) {
            val localWins = when {
                localTime < remoteTime -> true
                localTime > remoteTime -> false
                else -> localPeerId < payload.senderId
            }
            if (localWins) {
                scope.launch {
                    sendControlPayload(PayloadType.MODE_CHANGE, text = _mode.value.name, timestamp = localTime)
                }
                return
            }
        }
        applyModeTransition(requestedMode)
    }

    private fun handleIncomingFloorRequest(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        val currentFloor = _floorState.value
        Log.d(TAG, "[ITANTRA-PTT] REQUEST_FLOOR from ${payload.senderId} floorState=$currentFloor")

        when (currentFloor) {
            FloorState.IDLE -> {
                // Remote wants the floor and we're idle — grant it immediately.
                _floorState.value = FloorState.REMOTE_TRANSMITTING
                armFloorTimeout()
                Log.d(TAG, "[ITANTRA-PTT] Remote granted floor to ${payload.senderId} — we are now REMOTE_TRANSMITTING")
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED, timestamp = System.currentTimeMillis()) }
            }
            FloorState.LOCAL_TRANSMITTING -> {
                // We own the floor — deny the remote request.
                Log.d(TAG, "[ITANTRA-PTT] FLOOR_DENIED to ${payload.senderId} — we are LOCAL_TRANSMITTING")
                scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED, timestamp = System.currentTimeMillis()) }
            }
            FloorState.WAITING_FOR_CHANNEL -> {
                // Both sides requested simultaneously — timestamp tie-break.
                val localTime  = localFloorRequestTimestamp
                val remoteTime = payload.sentTimestampMs
                val localWins = when {
                    localTime < remoteTime -> true
                    localTime > remoteTime -> false
                    else                  -> localPeerId < payload.senderId
                }
                if (localWins) {
                    _floorState.value = FloorState.LOCAL_TRANSMITTING
                    sttEngine.start()
                    Log.d(TAG, "[ITANTRA-PTT] Collision: local wins — LOCAL_TRANSMITTING, sttEngine.start() called")
                    scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED, timestamp = System.currentTimeMillis()) }
                } else {
                    sttEngine.stop()
                    _floorState.value = FloorState.REMOTE_TRANSMITTING
                    armFloorTimeout()
                    Log.d(TAG, "[ITANTRA-PTT] Collision: remote wins — REMOTE_TRANSMITTING, sttEngine.stop() called")
                    scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED, timestamp = System.currentTimeMillis()) }
                }
            }
            FloorState.REMOTE_TRANSMITTING, FloorState.CHANNEL_BUSY -> {
                // Already aware remote is transmitting — re-grant and refresh timeout.
                armFloorTimeout()
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED, timestamp = System.currentTimeMillis()) }
            }
        }
    }

    private fun handleIncomingFloorGranted(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        val rtt = System.currentTimeMillis() - localFloorRequestTimestamp
        Log.d(TAG, "[ITANTRA-PTT] FLOOR_GRANTED from ${payload.senderId} roundTripMs=$rtt floorState=${_floorState.value}")
        // With optimistic acquisition, we are already LOCAL_TRANSMITTING.
        // FLOOR_GRANTED from the peer simply confirms no collision — nothing to do.
        if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
            // Fallback: in case we somehow ended up in WAITING_FOR_CHANNEL, promote now.
            _floorState.value = FloorState.LOCAL_TRANSMITTING
            sttEngine.start()
            Log.d(TAG, "[ITANTRA-PTT] FLOOR_GRANTED promoted WAITING_FOR_CHANNEL → LOCAL_TRANSMITTING, sttEngine.start()")
        } else {
            Log.d(TAG, "[ITANTRA-PTT] FLOOR_GRANTED acknowledged — already LOCAL_TRANSMITTING, STT running")
        }
    }

    private fun handleIncomingFloorDenied(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "[ITANTRA-PTT] FLOOR_DENIED from ${payload.senderId}")
        if (_floorState.value == FloorState.WAITING_FOR_CHANNEL ||
            _floorState.value == FloorState.LOCAL_TRANSMITTING) {
            sttEngine.stop()
            _floorState.value = FloorState.CHANNEL_BUSY
        }
    }

    private fun handleIncomingReleaseFloor(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "[ITANTRA-PTT] RELEASE_FLOOR from ${payload.senderId} — resetting to IDLE")
        floorTimeoutJob?.cancel()
        if (_floorState.value == FloorState.REMOTE_TRANSMITTING ||
            _floorState.value == FloorState.CHANNEL_BUSY) {
            _floorState.value = FloorState.IDLE
            Log.d(TAG, "[ITANTRA-PTT] Floor returned to IDLE after remote release")
        }
    }

    private fun handleIncomingSpeech(payload: Payload) {
        Log.d(TAG, "[ITANTRA-SESSION] incoming speech seq=${payload.sequenceId} text='${payload.text}'")

        val localLang = _selectedLanguage.value.code
        if (payload.languageCode.isNotBlank() && payload.languageCode != localLang) {
            Log.w(TAG, "Incoming speech lang mismatch: payload=${payload.languageCode} local=$localLang — TTS will attempt anyway")
        }

        if (_mode.value == SessionMode.PTT && _floorState.value == FloorState.IDLE) {
            _floorState.value = FloorState.REMOTE_TRANSMITTING
        }
        armFloorTimeout()

        val receivedMs = payload.receivedTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val sentence = RecognizedSentence(
            text         = payload.text,
            languageCode = payload.languageCode,
            isAlert      = payload.type == PayloadType.ALERT,
            timestampMs  = payload.sentTimestampMs,
            sequenceId   = payload.sequenceId,
        )

        val ttsStartedMs = System.currentTimeMillis()
        ttsEngine.speak(sentence)

        appendTranscript(
            TranscriptEntry(
                direction          = Direction.RECEIVED,
                text               = payload.text,
                languageCode       = payload.languageCode,
                isAlert            = sentence.isAlert,
                sequenceId         = payload.sequenceId,
                sessionMode        = _mode.value,
                micPauseDetectedMs = payload.sentTimestampMs,
                sttCompleteMs      = payload.sentTimestampMs,
                sentMs             = payload.sentTimestampMs,
                receivedMs         = receivedMs,
                ttsStartedMs       = ttsStartedMs,
            )
        )
    }

    private fun armFloorTimeout() {
        floorTimeoutJob?.cancel()
        floorTimeoutJob = scope.launch {
            delay(FLOOR_TIMEOUT_MS)
            if (_floorState.value == FloorState.REMOTE_TRANSMITTING) {
                Log.w(TAG, "[ITANTRA-SESSION] Floor timeout — resetting REMOTE_TRANSMITTING → IDLE")
                _floorState.value = FloorState.IDLE
            }
        }
    }

    private fun appendTranscript(entry: TranscriptEntry) {
        _transcript.update { (it + entry).takeLast(MAX_TRANSCRIPT_ENTRIES) }
    }
}
