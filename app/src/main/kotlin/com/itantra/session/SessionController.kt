package com.itantra.session

import android.util.Log
import com.itantra.mock.MockSttEngine
import com.itantra.mock.MockTtsEngine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "SessionController"
private const val MAX_TRANSCRIPT_ENTRIES = 200
private const val FLOOR_TIMEOUT_MS = 15_000L

/**
 * Orchestrates the Walkie-Talkie communication pipeline and Half-Duplex Floor Control.
 *
 * Floor Control Rules:
 *  - Only ONE peer may transmit voice at a time.
 *  - PTT press sends REQUEST_FLOOR; microphone captures only when floor is granted.
 *  - If remote peer owns floor, local attempt shows CHANNEL_BUSY and locks out microphone.
 *  - Simultaneous PTT requests are deterministically resolved via (timestamp, peerId) tie-breaking.
 *  - PTT release immediately sends RELEASE_FLOOR and stops microphone capture.
 *  - Disconnect or remote inactivity timeout safely resets floor state to IDLE.
 */
class SessionController(
    private val sttEngine: SpeechToTextEngine,
    private val ttsEngine: TextToSpeechEngine,
    val transport: TransportChannel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Unique local peer ID used for deterministic collision tie-breaking
    val localPeerId: String = UUID.randomUUID().toString().take(8)

    // ── Exposed State ────────────────────────────────────────────────────────
    private val _mode = MutableStateFlow(SessionMode.PTT)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(SupportedLanguage.HINDI)
    val selectedLanguage: StateFlow<SupportedLanguage> = _selectedLanguage.asStateFlow()

    @Volatile private var localFloorRequestTimestamp: Long = 0L
    @Volatile private var localModeChangeTimestamp: Long = 0L
    private var floorTimeoutJob: Job? = null

    init {
        Log.d(TAG, "SessionController initialized with localPeerId=$localPeerId")
        collectOutgoing()
        collectIncoming()
        syncModeOnConnect()
    }

    private fun syncModeOnConnect() {
        scope.launch {
            transport.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    Log.d(TAG, "Transport connected -> syncing initial mode (${_mode.value}) with peer")
                    sendControlPayload(PayloadType.MODE_CHANGE, text = _mode.value.name)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public Control API
    // ─────────────────────────────────────────────────────────────────────────

    fun isSessionActive(): Boolean =
        transport.connectionState.value is ConnectionState.Connected

    fun setMode(newMode: SessionMode) {
        requestModeChange(newMode)
    }

    /**
     * Broadcasts mode change to remote peer and transitions local mode.
     * Simultaneous conflicting requests are resolved deterministically via timestamp / peerId tie-break.
     */
    fun requestModeChange(newMode: SessionMode) {
        if (_mode.value == newMode) return
        val reqTime = System.currentTimeMillis()
        localModeChangeTimestamp = reqTime
        Log.d(TAG, "requestModeChange: requested $newMode at $reqTime")
        applyModeTransition(newMode)
        scope.launch {
            sendControlPayload(PayloadType.MODE_CHANGE, text = newMode.name, timestamp = reqTime)
        }
    }

    private fun applyModeTransition(newMode: SessionMode) {
        val old = _mode.value
        if (old == newMode) return
        _mode.value = newMode
        Log.d(TAG, "Mode transitioned: $old → $newMode")
        when {
            old == SessionMode.PTT && newMode == SessionMode.PHONE -> {
                if (_floorState.value == FloorState.LOCAL_TRANSMITTING || _floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                    scope.launch { sendControlPayload(PayloadType.RELEASE_FLOOR) }
                }
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.LOCAL_TRANSMITTING
                sttEngine.start()
            }
            old == SessionMode.PHONE && newMode == SessionMode.PTT -> {
                sttEngine.stop()
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.IDLE
            }
        }
    }

    fun setLanguage(language: SupportedLanguage) {
        if (isSessionActive()) {
            Log.w(TAG, "setLanguage rejected: session is currently active (language is locked)")
            return
        }
        _selectedLanguage.value = language
        (sttEngine as? MockSttEngine)?.setLanguage(language.code)
        Log.d(TAG, "Language set to ${language.displayName} (${language.code})")
    }

    /**
     * Called when user presses and holds PTT button.
     * Enforces half-duplex floor control before starting microphone capture.
     */
    fun requestFloorAndArm() {
        if (_mode.value == SessionMode.PHONE) {
            // PHONE mode captures continuously
            return
        }

        val currentFloor = _floorState.value
        Log.d(TAG, "requestFloorAndArm() invoked. Current floorState=$currentFloor")

        if (currentFloor == FloorState.REMOTE_TRANSMITTING) {
            Log.w(TAG, "Floor request denied: Remote peer already owns the floor (Channel Busy)")
            _floorState.value = FloorState.CHANNEL_BUSY
            return
        }

        if (currentFloor == FloorState.LOCAL_TRANSMITTING || currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            Log.d(TAG, "Already transmitting or waiting for channel")
            return
        }

        // Send REQUEST_FLOOR to remote peer
        val reqTime = System.currentTimeMillis()
        localFloorRequestTimestamp = reqTime
        _floorState.value = FloorState.WAITING_FOR_CHANNEL
        Log.d(TAG, "Floor requested at $reqTime. Sending REQUEST_FLOOR to remote peer...")

        scope.launch {
            sendControlPayload(
                PayloadType.REQUEST_FLOOR,
                timestamp = reqTime
            )
            // Optimistic grant: transition to LOCAL_TRANSMITTING and start mic capture
            if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                _floorState.value = FloorState.LOCAL_TRANSMITTING
                Log.d(TAG, "Floor granted locally. Starting microphone & Silero VAD capture...")
                sttEngine.start()
            }
        }
    }

    /**
     * Called when user releases PTT button.
     * Stops microphone capture, finalizes in-flight speech, and releases floor.
     */
    fun releaseFloorAndDisarm() {
        if (_mode.value == SessionMode.PHONE) return

        val currentFloor = _floorState.value
        Log.d(TAG, "releaseFloorAndDisarm() invoked. Current floorState=$currentFloor")

        if (currentFloor == FloorState.LOCAL_TRANSMITTING || currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            _floorState.value = FloorState.IDLE
            Log.d(TAG, "Stopping microphone & Silero VAD capture...")
            sttEngine.stop()

            val releaseTime = System.currentTimeMillis()
            Log.d(TAG, "Broadcasting RELEASE_FLOOR to remote peer at $releaseTime...")
            scope.launch {
                sendControlPayload(PayloadType.RELEASE_FLOOR, timestamp = releaseTime)
            }
        } else if (currentFloor == FloorState.CHANNEL_BUSY) {
            // Revert back to REMOTE_TRANSMITTING
            _floorState.value = FloorState.REMOTE_TRANSMITTING
            Log.d(TAG, "Releasing busy state — returning to REMOTE_TRANSMITTING")
        }
    }

    /**
     * Trigger high-priority manual alert.
     */
    fun triggerAlert() {
        Log.d(TAG, "triggerAlert() called")
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

    /**
     * Safely disconnect active session and reset all floor/STT/TTS state.
     */
    fun disconnect() {
        Log.d(TAG, "disconnect() requested on SessionController")
        floorTimeoutJob?.cancel()

        // 1. If currently transmitting, release floor
        if (_floorState.value == FloorState.LOCAL_TRANSMITTING || _floorState.value == FloorState.WAITING_FOR_CHANNEL) {
            scope.launch {
                sendControlPayload(PayloadType.RELEASE_FLOOR)
            }
        }

        // 2. Stop audio capture and playback
        sttEngine.stop()
        (sttEngine as? MockSttEngine)?.resetSequence()
        (ttsEngine as? MockTtsEngine)?.stop()

        // 3. Reset floor & mode state
        _floorState.value = FloorState.IDLE
        _mode.value = SessionMode.PTT

        // 4. Clear session transcript
        _transcript.value = emptyList()

        // 5. Disconnect transport & remove Wi-Fi Direct group
        scope.launch {
            transport.disconnect()
        }
    }

    fun shutdown() {
        Log.d(TAG, "shutdown() called on SessionController")
        disconnect()
        (sttEngine as? MockSttEngine)?.shutdown()
        ttsEngine.shutdown()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline — Outgoing (STT → TransportChannel)
    // ─────────────────────────────────────────────────────────────────────────

    private fun collectOutgoing() {
        scope.launch {
            sttEngine.recognizedSentences
                .catch { e -> Log.e(TAG, "STT flow error", e) }
                .collect { sentence ->
                    // In PTT mode, only send if local device owns the floor
                    if (_mode.value == SessionMode.PHONE || _floorState.value == FloorState.LOCAL_TRANSMITTING) {
                        Log.d(TAG, "collectOutgoing: sending sentence: text='${sentence.text}', seq=${sentence.sequenceId}")
                        sendSentence(sentence)
                    } else {
                        Log.w(TAG, "collectOutgoing: dropped sentence because local device does not own floor (${_floorState.value})")
                    }
                }
        }
    }

    private suspend fun sendSentence(sentence: RecognizedSentence) {
        val sttCompleteMs = System.currentTimeMillis()
        val payload = Payload(
            type             = if (sentence.isAlert) PayloadType.ALERT else PayloadType.SPEECH,
            text             = sentence.text,
            languageCode     = sentence.languageCode,
            sequenceId       = sentence.sequenceId,
            sentTimestampMs  = sttCompleteMs,
            senderId         = localPeerId,
        )
        val sentMs = System.currentTimeMillis()
        Log.d(TAG, "sendSentence: sending payload [seq=${payload.sequenceId}, type=${payload.type}]")
        runCatching { transport.send(payload) }
            .onFailure { e ->
                Log.e(TAG, "transport.send() failed", e)
                return
            }

        appendTranscript(
            TranscriptEntry(
                direction           = Direction.SENT,
                text                = sentence.text,
                languageCode        = sentence.languageCode,
                isAlert             = sentence.isAlert,
                sequenceId          = sentence.sequenceId,
                sessionMode         = _mode.value,
                micPauseDetectedMs  = sentence.timestampMs,
                sttCompleteMs       = sttCompleteMs,
                sentMs              = sentMs,
                receivedMs          = 0L,
                ttsStartedMs        = 0L,
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

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline — Incoming (TransportChannel → TTS & Floor Control)
    // ─────────────────────────────────────────────────────────────────────────

    private fun collectIncoming() {
        scope.launch {
            transport.onPayloadReceived
                .catch { e -> Log.e(TAG, "Transport receive flow error", e) }
                .collect { payload ->
                    when (payload.type) {
                        PayloadType.MODE_CHANGE   -> handleIncomingModeChange(payload)
                        PayloadType.REQUEST_FLOOR -> handleIncomingFloorRequest(payload)
                        PayloadType.FLOOR_GRANTED -> handleIncomingFloorGranted(payload)
                        PayloadType.FLOOR_DENIED  -> handleIncomingFloorDenied(payload)
                        PayloadType.RELEASE_FLOOR -> handleIncomingReleaseFloor(payload)
                        PayloadType.SPEECH, PayloadType.ALERT -> handleIncomingSpeech(payload)
                        PayloadType.CONTROL -> { /* Reserved */ }
                    }
                }
        }
    }

    private fun handleIncomingModeChange(payload: Payload) {
        val requestedMode = runCatching { SessionMode.valueOf(payload.text) }.getOrNull() ?: return
        Log.d(TAG, "Incoming MODE_CHANGE to $requestedMode from sender=${payload.senderId}, time=${payload.sentTimestampMs}")

        if (_mode.value == requestedMode) return

        val localTime = localModeChangeTimestamp
        val remoteTime = payload.sentTimestampMs

        // Detect simultaneous mode change conflict within 3-second window
        if (localTime > 0L && Math.abs(System.currentTimeMillis() - localTime) < 3000L) {
            val localWins = when {
                localTime < remoteTime -> true
                localTime > remoteTime -> false
                else -> localPeerId < payload.senderId
            }

            if (localWins) {
                Log.d(TAG, "Simultaneous MODE_CHANGE conflict: Local WON ($localTime / $localPeerId vs $remoteTime / ${payload.senderId}). Enforcing ${_mode.value}")
                scope.launch {
                    sendControlPayload(PayloadType.MODE_CHANGE, text = _mode.value.name, timestamp = localTime)
                }
                return
            } else {
                Log.d(TAG, "Simultaneous MODE_CHANGE conflict: Remote WON ($remoteTime / ${payload.senderId} vs $localTime / $localPeerId). Switching to $requestedMode")
            }
        }

        applyModeTransition(requestedMode)
    }

    private fun handleIncomingFloorRequest(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        val currentFloor = _floorState.value
        Log.d(TAG, "Incoming REQUEST_FLOOR from sender=${payload.senderId}, time=${payload.sentTimestampMs}. Current floorState=$currentFloor")

        when (currentFloor) {
            FloorState.IDLE -> {
                // Grant floor to remote peer
                _floorState.value = FloorState.REMOTE_TRANSMITTING
                armFloorTimeout()
                Log.d(TAG, "Remote peer acquired the floor. Local state: REMOTE_TRANSMITTING")
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
            }
            FloorState.LOCAL_TRANSMITTING -> {
                // Local is already transmitting; deny remote request
                Log.d(TAG, "Local is currently transmitting — sending FLOOR_DENIED to remote")
                scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED) }
            }
            FloorState.WAITING_FOR_CHANNEL -> {
                // Simultaneous PTT Request Collision Resolution (Deterministic Tie-Break)
                val localTime = localFloorRequestTimestamp
                val remoteTime = payload.sentTimestampMs

                val localWins = when {
                    localTime < remoteTime -> true
                    localTime > remoteTime -> false
                    else -> localPeerId < payload.senderId // Deterministic lexicographical tie-break
                }

                if (localWins) {
                    Log.d(TAG, "Simultaneous PTT Collision: Local WON tie-break ($localTime / $localPeerId vs $remoteTime / ${payload.senderId}). Staying LOCAL_TRANSMITTING")
                    _floorState.value = FloorState.LOCAL_TRANSMITTING
                    sttEngine.start()
                    scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED) }
                } else {
                    Log.d(TAG, "Simultaneous PTT Collision: Remote WON tie-break. Local switching to REMOTE_TRANSMITTING")
                    _floorState.value = FloorState.REMOTE_TRANSMITTING
                    sttEngine.stop()
                    armFloorTimeout()
                    scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
                }
            }
            FloorState.REMOTE_TRANSMITTING, FloorState.CHANNEL_BUSY -> {
                // Remote already owns floor; acknowledge
                armFloorTimeout()
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
            }
        }
    }

    private fun handleIncomingFloorGranted(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "Incoming FLOOR_GRANTED from sender=${payload.senderId}")
        if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
            _floorState.value = FloorState.LOCAL_TRANSMITTING
            Log.d(TAG, "Floor confirmed by remote. Local state: LOCAL_TRANSMITTING")
            sttEngine.start()
        }
    }

    private fun handleIncomingFloorDenied(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "Incoming FLOOR_DENIED from sender=${payload.senderId} (Channel is Busy)")
        if (_floorState.value == FloorState.WAITING_FOR_CHANNEL || _floorState.value == FloorState.LOCAL_TRANSMITTING) {
            sttEngine.stop()
            _floorState.value = FloorState.CHANNEL_BUSY
            Log.d(TAG, "Microphone stopped. Local state: CHANNEL_BUSY")
        }
    }

    private fun handleIncomingReleaseFloor(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "Incoming RELEASE_FLOOR from sender=${payload.senderId}")
        floorTimeoutJob?.cancel()
        if (_floorState.value == FloorState.REMOTE_TRANSMITTING || _floorState.value == FloorState.CHANNEL_BUSY) {
            _floorState.value = FloorState.IDLE
            Log.d(TAG, "Floor released. Channel is now IDLE")
        }
    }

    private fun handleIncomingSpeech(payload: Payload) {
        Log.d(TAG, "handleIncomingSpeech: seq=${payload.sequenceId}, text='${payload.text}', type=${payload.type}")
        if (_mode.value == SessionMode.PTT && _floorState.value == FloorState.IDLE) {
            _floorState.value = FloorState.REMOTE_TRANSMITTING
        }
        armFloorTimeout()

        val receivedMs = payload.receivedTimestampMs.takeIf { it > 0L }
            ?: System.currentTimeMillis()

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
                direction           = Direction.RECEIVED,
                text                = payload.text,
                languageCode        = payload.languageCode,
                isAlert             = sentence.isAlert,
                sequenceId          = payload.sequenceId,
                sessionMode         = _mode.value,
                micPauseDetectedMs  = payload.sentTimestampMs,
                sttCompleteMs       = payload.sentTimestampMs,
                sentMs              = payload.sentTimestampMs,
                receivedMs          = receivedMs,
                ttsStartedMs        = ttsStartedMs,
            )
        )
    }

    private fun armFloorTimeout() {
        floorTimeoutJob?.cancel()
        floorTimeoutJob = scope.launch {
            delay(FLOOR_TIMEOUT_MS)
            if (_floorState.value == FloorState.REMOTE_TRANSMITTING) {
                Log.w(TAG, "Remote floor ownership timed out after ${FLOOR_TIMEOUT_MS}ms of inactivity. Resetting floor to IDLE.")
                _floorState.value = FloorState.IDLE
            }
        }
    }

    private fun appendTranscript(entry: TranscriptEntry) {
        _transcript.value = (_transcript.value + entry).takeLast(MAX_TRANSCRIPT_ENTRIES)
    }
}