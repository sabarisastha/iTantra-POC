package com.itantra.session

import android.util.Log
import com.itantra.stt.RecognizedSentence
import com.itantra.stt.SpeechToTextEngine
import com.itantra.transport.ConnectionState
import com.itantra.transport.Payload
import com.itantra.transport.PayloadType
import com.itantra.transport.TransportChannel
import com.itantra.transport.TransportManager
import com.itantra.tts.TextToSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
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
    private val historyDao: com.itantra.db.HistoryDao? = null,
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

    // Phone mode audio streams
    private var phoneAudioRecorder: com.itantra.audio.AudioRecorder? = null
    private var phoneAudioPlayer: com.itantra.audio.AudioPlayer? = null
    private var pcmSendChannel: Channel<FloatArray>? = null
    private var phoneTxJob: Job? = null

    // History and Metrics Tracking
    private var sessionStartMs: Long = 0L
    private var connectedPeerName: String = "Connected Peer"
    private var connectedPeerAddress: String = ""
    private var connectingPeerName: String? = null

    init {
        monitorConnectionState()
        collectIncoming()
        collectStt()
    }

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
                    is ConnectionState.Connecting -> {
                        connectingPeerName = transport.discoveredDevices.value.firstOrNull()?.name ?: "Peer"
                    }
                    is ConnectionState.Connected -> {
                        sessionStartMs = System.currentTimeMillis()
                        connectedPeerName = state.peerName.ifBlank { "Connected Peer" }
                        connectedPeerAddress = state.peerAddress
                        connectingPeerName = null

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
                            Log.d(TAG, "[ITANTRA-SESSION] Connected in PHONE mode — auto-starting phone audio")
                            startPhoneModeAudio()
                        }
                    }
                    is ConnectionState.Error -> {
                        val transportType = (transport as? TransportManager)?.activeKind?.value?.label ?: "P2P"
                        if (connectingPeerName != null) {
                            val peer = connectingPeerName ?: "Peer"
                            val savedAlias = historyDao?.getAliasForPeer(peer)
                            val entry = com.itantra.db.HistoryEntity(
                                peerName = peer,
                                peerAddress = "",
                                alias = savedAlias,
                                timestampMs = System.currentTimeMillis(),
                                durationMs = 0L,
                                transportType = transportType,
                                languageUsed = _selectedLanguage.value.displayName,
                                status = "FAILED"
                            )
                            scope.launch(Dispatchers.IO) { historyDao?.insertHistory(entry) }
                            connectingPeerName = null
                        } else if (sessionStartMs > 0L) {
                            val duration = System.currentTimeMillis() - sessionStartMs
                            val peerKey = connectedPeerAddress.ifBlank { connectedPeerName }
                            val savedAlias = historyDao?.getAliasForPeer(peerKey)
                            val entry = com.itantra.db.HistoryEntity(
                                peerName = connectedPeerName,
                                peerAddress = connectedPeerAddress,
                                alias = savedAlias,
                                timestampMs = sessionStartMs,
                                durationMs = duration,
                                transportType = transportType,
                                languageUsed = _selectedLanguage.value.displayName,
                                status = if (duration > 2000L) "SUCCESS" else "ABORTED"
                            )
                            scope.launch(Dispatchers.IO) { historyDao?.insertHistory(entry) }
                            sessionStartMs = 0L
                        }
                        Log.d(TAG, "[ITANTRA-SESSION] state=ERROR — resetting session")
                        onSessionLost()
                    }
                    is ConnectionState.Idle, is ConnectionState.Disconnected -> {
                        if (sessionStartMs > 0L) {
                            val duration = System.currentTimeMillis() - sessionStartMs
                            val transportType = (transport as? TransportManager)?.activeKind?.value?.label ?: "P2P"
                            val peerKey = connectedPeerAddress.ifBlank { connectedPeerName }
                            val savedAlias = historyDao?.getAliasForPeer(peerKey)
                            val entry = com.itantra.db.HistoryEntity(
                                peerName = connectedPeerName,
                                peerAddress = connectedPeerAddress,
                                alias = savedAlias,
                                timestampMs = sessionStartMs,
                                durationMs = duration,
                                transportType = transportType,
                                languageUsed = _selectedLanguage.value.displayName,
                                status = if (duration > 2000L) "SUCCESS" else "ABORTED"
                            )
                            scope.launch(Dispatchers.IO) { historyDao?.insertHistory(entry) }
                            sessionStartMs = 0L
                        }
                        connectingPeerName = null
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
        stopPhoneModeAudio()
        _floorState.value = FloorState.IDLE
        _mode.value = SessionMode.PTT
        Log.d(TAG, "[ITANTRA-SESSION] session reset complete")
    }

    private fun armFloorTimeout() {
        floorTimeoutJob?.cancel()
        floorTimeoutJob = scope.launch {
            delay(FLOOR_TIMEOUT_MS)
            if (_floorState.value == FloorState.REMOTE_TRANSMITTING ||
                _floorState.value == FloorState.CHANNEL_BUSY) {
                Log.w(TAG, "[ITANTRA-PTT] Floor timeout reached — resetting to IDLE")
                _floorState.value = FloorState.IDLE
            }
        }
    }

    // ── Session Controls ──────────────────────────────────────────────────────

    fun switchMode(targetMode: SessionMode) {
        if (!isSessionActive()) {
            _mode.value = targetMode
            return
        }
        val timestamp = System.currentTimeMillis()
        localModeChangeTimestamp = timestamp
        applyModeTransition(targetMode)
        scope.launch {
            sendControlPayload(PayloadType.MODE_CHANGE, text = targetMode.name, timestamp = timestamp)
        }
    }

    fun setMode(targetMode: SessionMode) = switchMode(targetMode)

    private fun applyModeTransition(newMode: SessionMode) {
        val old = _mode.value
        if (old == newMode) return
        Log.d(TAG, "[ITANTRA-SESSION] Mode transitioning: $old -> $newMode")
        _mode.value = newMode
        when {
            old == SessionMode.PTT && newMode == SessionMode.PHONE -> {
                if (_floorState.value == FloorState.LOCAL_TRANSMITTING ||
                    _floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                    scope.launch { sendControlPayload(PayloadType.RELEASE_FLOOR) }
                }
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.IDLE
                sttEngine.stop()
                startPhoneModeAudio()
            }
            old == SessionMode.PHONE && newMode == SessionMode.PTT -> {
                stopPhoneModeAudio()
                sttEngine.stop()
                floorTimeoutJob?.cancel()
                _floorState.value = FloorState.IDLE
            }
        }
    }

    private fun startPhoneModeAudio() {
        Log.d(TAG, "[ITANTRA-PHONE] Starting independent TX & RX for PHONE mode")
        startPhoneRx()
        startPhoneTx()
    }

    private fun startPhoneRx() {
        if (phoneAudioPlayer != null) return
        Log.d(TAG, "[ITANTRA-PHONE] Starting RX stream")
        val player = com.itantra.audio.AudioPlayer()
        val success = player.startStream(16000)
        if (success) {
            phoneAudioPlayer = player
        } else {
            Log.e("CALL", "[CALL][RX][ERROR] Failed to start phone mode audio player stream")
        }
    }

    private fun startPhoneTx() {
        if (phoneAudioRecorder != null) return
        Log.d(TAG, "[ITANTRA-PHONE] Starting TX stream")

        val channel = Channel<FloatArray>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        pcmSendChannel = channel

        phoneTxJob?.cancel()
        phoneTxJob = scope.launch(Dispatchers.IO) {
            // Give Android AudioFlinger / HAL a 100ms window to cleanly release microphone if STT just stopped
            delay(100)
            if (_mode.value != SessionMode.PHONE || !isSessionActive()) return@launch

            val recorder = com.itantra.audio.AudioRecorder()
            phoneAudioRecorder = recorder
            recorder.startRecording { floatChunk ->
                channel.trySend(floatChunk)
            }

            for (chunk in channel) {
                if (_mode.value == SessionMode.PHONE && isSessionActive()) {
                    runCatching { transport.sendPcm(chunk) }
                        .onFailure { e -> Log.e("CALL", "[CALL][TX][ERROR] Failed to send PCM chunk: ${e.message}") }
                }
            }
        }
    }

    private fun stopPhoneModeAudio() {
        Log.d(TAG, "[ITANTRA-PHONE] Stopping TX & RX for PHONE mode")
        stopPhoneTx()
        stopPhoneRx()
    }

    private fun stopPhoneTx() {
        phoneTxJob?.cancel()
        phoneTxJob = null
        pcmSendChannel?.close()
        pcmSendChannel = null
        phoneAudioRecorder?.stopRecording()
        phoneAudioRecorder = null
    }

    private fun stopPhoneRx() {
        phoneAudioPlayer?.stop()
        phoneAudioPlayer = null
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
            Log.w(TAG, "PTT denied: remote owns floor")
            _floorState.value = FloorState.CHANNEL_BUSY
            return
        }

        if (currentFloor == FloorState.LOCAL_TRANSMITTING ||
            currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            Log.d(TAG, "PTT already active — ignored")
            return
        }

        val reqTime = System.currentTimeMillis()
        localFloorRequestTimestamp = reqTime
        _floorState.value = FloorState.WAITING_FOR_CHANNEL
        Log.d(TAG, "[ITANTRA-PTT] REQUEST_FLOOR sent at $reqTime")

        scope.launch {
            sendControlPayload(PayloadType.REQUEST_FLOOR, timestamp = reqTime)
            
            delay(5000L)
            if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                Log.w(TAG, "[ITANTRA-PTT] Floor request timeout, retrying...")
                sendControlPayload(PayloadType.REQUEST_FLOOR, timestamp = System.currentTimeMillis())
                
                delay(5000L)
                if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
                    Log.w(TAG, "[ITANTRA-PTT] Floor request retry failed, resetting to IDLE")
                    _floorState.value = FloorState.IDLE
                }
            }
        }
    }

    fun releaseFloorAndDisarm() {
        if (_mode.value == SessionMode.PHONE) return

        val currentFloor = _floorState.value
        Log.d(TAG, "[ITANTRA-PTT] releaseFloorAndDisarm() floorState=$currentFloor")

        if (currentFloor == FloorState.LOCAL_TRANSMITTING ||
            currentFloor == FloorState.WAITING_FOR_CHANNEL) {
            _floorState.value = FloorState.IDLE
            sttEngine.stop()
            Log.d(TAG, "[ITANTRA-PTT] RELEASE_FLOOR")
            scope.launch {
                sendControlPayload(PayloadType.RELEASE_FLOOR, timestamp = System.currentTimeMillis())
            }
        } else if (currentFloor == FloorState.CHANNEL_BUSY) {
            _floorState.value = FloorState.IDLE
        }
    }

    fun triggerAlert(latitude: Double? = null, longitude: Double? = null) {
        Log.d(TAG, "triggerAlert(lat=$latitude, lon=$longitude)")
        val lang = _selectedLanguage.value
        scope.launch {
            val sttCompleteMs = System.currentTimeMillis()
            val sentence = RecognizedSentence(
                text         = lang.emergencyPhrase,
                languageCode = lang.code,
                isAlert      = true,
                timestampMs  = sttCompleteMs,
                sequenceId   = -1,
            )
            val payload = Payload(
                type            = PayloadType.ALERT,
                text            = sentence.text,
                languageCode    = sentence.languageCode,
                sequenceId      = sentence.sequenceId,
                sentTimestampMs = sttCompleteMs,
                senderId        = localPeerId,
                latitude        = latitude,
                longitude       = longitude
            )
            runCatching { transport.send(payload) }
                .onFailure { e -> Log.e(TAG, "transport.send() alert failed", e) }

            appendTranscript(
                TranscriptEntry(
                    direction          = Direction.SENT,
                    text               = sentence.text,
                    languageCode       = sentence.languageCode,
                    isAlert            = sentence.isAlert,
                    sequenceId         = sentence.sequenceId,
                    sessionMode        = _mode.value,
                    latitude           = latitude,
                    longitude          = longitude,
                    micPauseDetectedMs = sentence.timestampMs,
                    sttCompleteMs      = sttCompleteMs,
                    sentMs             = System.currentTimeMillis(),
                    receivedMs         = 0L,
                    ttsStartedMs       = 0L,
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
        stopPhoneModeAudio()
        
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
        heartbeatJob?.cancel()
        floorTimeoutJob?.cancel()
        sttEngine.shutdown()
        ttsEngine.shutdown()
        stopPhoneModeAudio()
        scope.cancel()
    }

    fun isSessionActive(): Boolean {
        return transport.connectionState.value is ConnectionState.Connected
    }

    // ── Outgoing pipeline: STT → Transport ───────────────────────────────────

    private fun collectStt() {
        scope.launch {
            sttEngine.recognizedSentences
                .catch { e -> Log.e(TAG, "STT flow error", e) }
                .collect { sentence ->
                    if (_mode.value == SessionMode.PHONE) {
                        Log.w(TAG, "STT emission in PHONE mode — discarded (use raw PCM)")
                    } else {
                        sendSentence(sentence)
                    }
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
            latitude        = null,
            longitude       = null
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
                latitude           = null,
                longitude          = null,
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
        
        scope.launch(Dispatchers.IO) {
            transport.onPcmReceived
                .catch { e -> Log.e(TAG, "Transport receive PCM flow error", e) }
                .collect { floatChunk ->
                    if (_mode.value == SessionMode.PHONE) {
                        if (phoneAudioPlayer == null) {
                            startPhoneRx()
                        }
                        phoneAudioPlayer?.writeStream(floatChunk)
                    }
                }
        }
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
                if (phoneAudioRecorder == null) startPhoneModeAudio()
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
                // Grant floor to remote peer and update UI state immediately
                _floorState.value = FloorState.REMOTE_TRANSMITTING
                armFloorTimeout()
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
            }
            FloorState.LOCAL_TRANSMITTING -> {
                scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED) }
            }
            FloorState.WAITING_FOR_CHANNEL -> {
                val localTime  = localFloorRequestTimestamp
                val remoteTime = payload.sentTimestampMs
                val localWins = when {
                    localTime < remoteTime -> true
                    localTime > remoteTime -> false
                    else -> localPeerId < payload.senderId
                }
                if (localWins) {
                    _floorState.value = FloorState.LOCAL_TRANSMITTING
                    sttEngine.start()
                    scope.launch { sendControlPayload(PayloadType.FLOOR_DENIED) }
                } else {
                    _floorState.value = FloorState.REMOTE_TRANSMITTING
                    sttEngine.stop()
                    armFloorTimeout()
                    scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
                }
            }
            FloorState.REMOTE_TRANSMITTING, FloorState.CHANNEL_BUSY -> {
                armFloorTimeout()
                scope.launch { sendControlPayload(PayloadType.FLOOR_GRANTED) }
            }
        }
    }

    private fun handleIncomingFloorGranted(payload: Payload) {
        if (_mode.value == SessionMode.PHONE) return
        Log.d(TAG, "[ITANTRA-PTT] FLOOR_GRANTED from ${payload.senderId} roundTripMs=${System.currentTimeMillis() - localFloorRequestTimestamp}")
        if (_floorState.value == FloorState.WAITING_FOR_CHANNEL) {
            _floorState.value = FloorState.LOCAL_TRANSMITTING
            sttEngine.start()
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
        Log.d(TAG, "[ITANTRA-PTT] RELEASE_FLOOR from ${payload.senderId}")
        floorTimeoutJob?.cancel()
        if (_floorState.value == FloorState.REMOTE_TRANSMITTING ||
            _floorState.value == FloorState.CHANNEL_BUSY) {
            _floorState.value = FloorState.IDLE
        }
    }

    private fun handleIncomingSpeech(payload: Payload) {
        Log.d(TAG, "[ITANTRA-SESSION] incoming speech seq=${payload.sequenceId} text='${payload.text}'")

        val localLang = _selectedLanguage.value.code
        if (payload.languageCode.isNotBlank() && payload.languageCode != localLang) {
            Log.w(TAG, "Incoming speech lang mismatch: payload=${payload.languageCode} local=$localLang — TTS will attempt anyway")
        }

        // CRITICAL SPEC REQUIREMENT:
        // PTT RELEASE_FLOOR cannot be overridden by delayed/old SPEECH packets.
        // We DO NOT set _floorState to REMOTE_TRANSMITTING here. Floor is governed strictly by
        // REQUEST_FLOOR and RELEASE_FLOOR messages.

        val receivedMs = payload.receivedTimestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val sentence = RecognizedSentence(
            text         = payload.text,
            languageCode = payload.languageCode,
            isAlert      = payload.type == PayloadType.ALERT,
            timestampMs  = payload.sentTimestampMs,
            sequenceId   = payload.sequenceId,
            receivedTimestampMs = receivedMs,
        )

        Log.d(TAG, "[PTT] SPEECH_RECEIVED seq=${payload.sequenceId} text='${payload.text}' delay_since_send=${System.currentTimeMillis() - payload.sentTimestampMs}ms")
        
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
                latitude           = payload.latitude,
                longitude          = payload.longitude,
                micPauseDetectedMs = payload.sentTimestampMs,
                sttCompleteMs      = payload.sentTimestampMs,
                sentMs             = payload.sentTimestampMs,
                receivedMs         = receivedMs,
                ttsStartedMs       = ttsStartedMs,
            )
        )
    }

    private fun appendTranscript(entry: TranscriptEntry) {
        _transcript.update { (it + entry).takeLast(MAX_TRANSCRIPT_ENTRIES) }
    }
}
