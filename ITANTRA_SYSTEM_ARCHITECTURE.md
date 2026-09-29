# iTantra System Architecture

> Disaster-Resilient Multilingual Mesh Communication  
> Revision: commit `58e89f1`

---

## 1. Philosophy

**Problem:** Cellular towers, power grid, and internet all fail simultaneously in major disasters. PTT radios are monolingual and require dedicated hardware.

**Solution:** Collapse the entire comms stack onto ordinary Android handsets operating fully offline. Devices form a Bluetooth / Wi-Fi Direct P2P mesh with no server or account needed.

**Semantic Compression:** Instead of 32 KB/s raw PCM, iTantra runs on-device neural ASR and sends only the transcribed text (~20–80 bytes). Compression ratio: **1,200:1 – 4,800:1** vs raw PCM. The receiver re-synthesizes speech locally with a neural VITS TTS model.

**4 non-negotiable invariants:**

| Invariant | Mechanism |
|---|---|
| Zero infrastructure dependency | All ML inference on-device (ONNX). No cloud calls. |
| Bilateral language symmetry | `SESSION_INIT` handshake; mismatch → `SESSION_REJECT:LANGUAGE_MISMATCH` + disconnect |
| Half-duplex floor control | Timestamp tie-break + 8-char UUID-prefix lexicographic ordering |
| Audio hardware exclusivity | `Channel<Command>` serialization; explicit `AudioRecord` release before re-create |

---

## 2. Component Map

```
UI  (Jetpack Compose)
  SplashScreen → LanguageSelectionScreen → HomeScreen → SessionScreen
  SessionScreen: LatencyPanel | TranscriptList | GPSAlert | ModeSwitch | FloorControl

SessionController  (SupervisorJob + Dispatchers.Main.immediate)
  MAX_TRANSCRIPT_ENTRIES = 200
  FLOOR_TIMEOUT_MS       = 15_000

  ├── SpeechToTextEngine (IndicConformerSttEngine)
  │     AdaptiveNoiseFloorVadPipeline
  │       SileroVadEngine   (silero_vad.onnx via Sherpa-ONNX)
  │       SpeechSegmenter   FSM
  │     AudioRecorder       (16kHz, VOICE_COMMUNICATION)
  │
  ├── TextToSpeechEngine (AndroidTtsEngine / IndicParlerTtsEngine)
  │     AudioPlayer
  │
  └── TransportManager  (flatMapLatest multiplexer)
        BluetoothSocketTransport   RFCOMM / SPP
        WifiDirectSocketTransport  TCP port 8988
        FramedSocket               ITAN magic header

ModelManager    (sttMutex + ttsMutex, reference counting)
AppDatabase     (Room v3: HistoryEntity)
DutyCycleScheduler  (battery-aware burst sampling)
```

---

## 3. Audio Acquisition

### AudioRecorder

| Param | Value |
|---|---|
| Source | `VOICE_COMMUNICATION` (hardware AEC + noise suppression) |
| Sample rate | 16,000 Hz |
| Channels | `CHANNEL_IN_MONO` |
| Encoding | `ENCODING_PCM_16BIT` → native `ShortArray` |
| Buffer | `max(minBufferSize, 4096)` |

Delivers via `startRecordingShorts { shortChunk: ShortArray → … }` — avoids FloatArray allocation per chunk.

### AudioPlayer

- Coroutine playback queue; per-segment replay history
- `ALERT` → `STREAM_ALARM` + max volume + `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`; previous volume stored in `alertPrevVolumes`, restored in `onDone`
- Normal speech → `STREAM_MUSIC`; auto-boost to 75% max if volume = 0

---

## 4. Voice Activity Detection (4 layers)

### Layer 1 — Frame Accumulator

Location: `IndicConformerSttEngine.handleStart()`

Silero needs exactly 512 samples (32ms @ 16kHz). Ring-buffer `frameAccum: ShortArray(512)` + `framePos: Int` slices variable AudioRecord chunks. `frameAccum.copyOf()` before reset ensures frame stability.

### Layer 2 — SileroVadEngine

`com.k2fsa.sherpa.onnx.Vad` (bidirectional LSTM, Snakers4).

| Param | Value |
|---|---|
| model | `silero_vad.onnx` (asset) |
| threshold | 0.25f (baseline; overridden by Layer 3) |
| minSilenceDuration | 0.25 s |
| minSpeechDuration | 0.05 s |
| windowSize | 512 samples |
| maxSpeechDuration | 30.0 s |
| numThreads | 1 |

**RMS safety fallback** (when `rms > 180.0` AND Silero prob < 0.15):
```
normRms  = rms / 32767.0
dBFS     = 20 × log10(normRms)
isVoiced = dBFS ≥ −42.0 OR normRms > 0.008
prob     = 0.85 if isVoiced else 0.10
```

**ORT latch:** If native `.so` fails, `isOrtUnavailable = true` permanently → energy fallback forever.

### Layer 3 — AdaptiveNoiseFloorVadPipeline

EMA noise-floor adaptation (α = 0.05), updated only on silence frames (prob < 0.15):

```
EMA_n = 0.05 × RMS_n + 0.95 × EMA_{n−1}
BASELINE_RMS = 130 / 32767 ≈ 0.003967  (~−38 dBFS)

δ   = (EMA − BASELINE_RMS) / max(BASELINE_RMS, 1e−6)
adj = clamp(δ × 0.25, −0.15, +0.08)
T   = clamp(BASE_THRESHOLD + adj, 0.35, 0.58)
```

Buffer: `ArrayList<Short>(16000 × 30)` = 30s capacity.  
Auto-flush at 56,000 samples (~3.5s); PTT release flush min 400 samples.

### Layer 4 — SpeechSegmenter FSM

States: `IN_SILENCE` ↔ `IN_SPEECH`

| Param | Value | Duration |
|---|---|---|
| speechStartThreshold | 0.50 | — |
| speechEndThreshold | 0.35 | — |
| attackFrames | 3 | ~96 ms |
| hangoverFrames | 9 | ~288 ms |

```
IN_SILENCE: prob ≥ 0.50 → voiced++; if voiced ≥ 3 → IN_SPEECH + SPEECH_START
IN_SPEECH:  prob < 0.35 → silent++; if silent ≥ 9 → IN_SILENCE + SPEECH_END
```

---

## 5. Speech-to-Text Engine

**Class:** `IndicConformerSttEngine` — Conformer CTC INT8 ONNX from AI4Bharat via Sherpa-ONNX.

**Command serialization:** `Channel<Command>(UNLIMITED)` → sequential drain coroutine. `handleStop()` (AudioRecord release) always finishes before `handleStart()` (AudioRecord create).

**Hot path:**
```kotlin
recorder.startRecordingShorts { shortChunk ->
    for (s in shortChunk) {
        frameAccum[framePos++] = s
        if (framePos == 512) {
            val frame = frameAccum.copyOf(); framePos = 0
            scope.launch { pipeline.processFrame(frame)?.let { processSpeechSegment(it) } }
        }
    }
}
```

**Peak normalization:** if `maxAbs ∈ [0.001, 0.25]` → gain = min(0.7/maxAbs, 10×).

**Inference latency:**
```kotlin
val startMs = System.currentTimeMillis()
stream.acceptWaveform(samples, 16000); recognizer.decode(stream)
val latencyMs = System.currentTimeMillis() - startMs
// → RecognizedSentence.inferenceDurationMs
```

**RTF (per-utterance):**
```kotlin
val audioDurationSec = samplesToProcess.size / 16000.0f
val sttRtf = latencyMs / 1000.0f / audioDurationSec
// → RecognizedSentence.rtf
```

**Model URLs:**
```
Base: https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main
STT:  <lang>/model.int8.onnx
Tok:  tokens.txt  (shared)
Langs: hi bn gu kn ml mr or ta te as en
```

**RecognizedSentence:**
```kotlin
data class RecognizedSentence(
    val text: String, val languageCode: String,
    val isAlert: Boolean, val timestampMs: Long,
    val sequenceId: Int,
    val inferenceDurationMs: Long = 0L,
    val rtf: Float = 0f,
)
```

---

## 6. Payload Model

```kotlin
@Serializable
data class Payload(
    val type: PayloadType,
    val text: String = "",
    val languageCode: String = "",
    val sequenceId: Int = 0,
    val sentTimestampMs: Long = 0L,
    val senderId: String = "",          // 8-char UUID prefix; floor tie-break key
    @Transient val receivedTimestampMs: Long = 0L,  // local only; not transmitted
    val latitude: Double? = null, val longitude: Double? = null,
    val accuracy: Float? = null, val locationTimestampMs: Long? = null,
    val locationStatus: String? = null,
    val sttLatencyMs: Long = 0L,
    val senderQueueMs: Long = 0L,
    val rtf: Float = 0f,
)
```

PayloadType: `SPEECH ALERT REQUEST_FLOOR FLOOR_GRANTED FLOOR_DENIED RELEASE_FLOOR MODE_CHANGE CONTROL SESSION_INIT SESSION_ACK HEARTBEAT LOCATION_UPDATE`

---

## 7. Binary Wire Protocol

### 18-byte Fixed Header (Big Endian)

```
Off  Field        Type    Bytes  Value
0    Version      UInt8   1      0x01
1    Type         UInt8   1      PayloadType ordinal
2    Language ID  UInt8   1      see table below
3    Flags        UInt8   1      section bitmask
4–7  Sequence ID  Int32   4
8–15 Sent Time    Int64   8      epoch ms
16–17 Body Len   UInt16   2
```

### Flag Bitmask

| Bit | Constant | Hex |
|---|---|---|
| 0 | FLAG_HAS_TEXT | 0x01 |
| 1 | FLAG_HAS_SENDER_ID | 0x02 |
| 2 | FLAG_HAS_LOCATION | 0x04 |
| 3 | FLAG_HAS_TELEMETRY | 0x08 |
| 4 | FLAG_CUSTOM_LANG | 0x10 |
| 5 | FLAG_TEXT_COMPRESSED | 0x20 |

### Language ID Table

`0x01=hi 0x02=gu 0x03=mr 0x04=kn 0x05=ml 0x06=ta 0x07=te 0x08=or 0x09=bn 0x0A=en 0x0B=as 0xFF=custom`

### Variable Body (fixed section order)

```
TEXT:      [2B len][UTF-8]  OR  [2B compLen][2B origLen][DEFLATE]
           (compressed when compLen+4 < rawLen+2)
SENDER_ID: [1B len][UTF-8]
TELEMETRY: [4B sttLatencyMs Int32][4B senderQueueMs Int32][4B rtf Float32]
LOCATION:  [1B flags] then:
             0x01 → [8B lat Double][8B lon Double]
             0x02 → [4B accuracy Float]
             0x04 → [8B timestamp Long]
             0x08 → [1B len][UTF-8 status]
CUSTOM_LANG: [1B len][UTF-8]
```

### FramedSocket Header (16 bytes)

```
0–3  Magic   Int32  0x4954414E  ('ITAN')
4–7  Version Int32  1
8–11 Type    Int32  1=JSON_PAYLOAD  2=RAW_PCM
12–15 Len    Int32  payload bytes
```

`writeMutex: Mutex` serializes concurrent writes. Invalid magic / version / len>512KB → `close()`.

---

## 8. Transport Layer

### ConnectionState

`Idle → Discovering → Connecting → Connected(peerName, isGroupOwner) → Error(msg) → Idle`

### TransportManager

`flatMapLatest` over `_activeKind: MutableStateFlow<TransportKind>`. Switch:
```kotlin
active().disconnect(); _activeKind.value = kind; active().startAdvertisingAndDiscovery()
```

### Bluetooth RFCOMM (`BluetoothSocketTransport`)

| Param | Value |
|---|---|
| UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| Server accept timeout | 30 s |
| Client retries | 3 × 1 s |
| Discoverability | 300 s |
| Role | Higher MAC = server (`accept`); lower MAC = client (`connect`) |

### Wi-Fi Direct TCP (`WifiDirectSocketTransport`)

| Param | Value |
|---|---|
| Port | 8988 |
| Timeout | 20 s |
| Retries | 6 × 800 ms |
| GO | `WifiP2pManager` negotiation; GO runs `ServerSocket:8988` |

---

## 9. Session Control & Floor FSM

### FloorState

```kotlin
enum class FloorState {
    IDLE, WAITING_FOR_CHANNEL,
    LOCAL_TRANSMITTING, REMOTE_TRANSMITTING, CHANNEL_BUSY
}
```

### Transition Table

| From | Event | To | Actions |
|---|---|---|---|
| IDLE | `requestFloorAndArm()` | LOCAL_TRANSMITTING | `sttEngine.start()` + send `REQUEST_FLOOR` |
| IDLE | incoming `REQUEST_FLOOR` | REMOTE_TRANSMITTING | `armFloorTimeout(15s)` + send `FLOOR_GRANTED` |
| LOCAL_TRANSMITTING | `releaseFloorAndDisarm()` | IDLE | `sttEngine.stop()` + send `RELEASE_FLOOR` |
| LOCAL_TRANSMITTING | incoming `FLOOR_DENIED` | CHANNEL_BUSY | `sttEngine.stop()` |
| LOCAL_TRANSMITTING | incoming `REQUEST_FLOOR` | LOCAL_TRANSMITTING | send `FLOOR_DENIED` |
| WAITING_FOR_CHANNEL | incoming `REQUEST_FLOOR` (local wins) | LOCAL_TRANSMITTING | `sttEngine.start()` + send `FLOOR_DENIED` |
| WAITING_FOR_CHANNEL | incoming `REQUEST_FLOOR` (remote wins) | REMOTE_TRANSMITTING | `sttEngine.stop()` + `armFloorTimeout()` + `FLOOR_GRANTED` |
| WAITING_FOR_CHANNEL | incoming `FLOOR_GRANTED` | LOCAL_TRANSMITTING | `sttEngine.start()` |
| REMOTE_TRANSMITTING | incoming `RELEASE_FLOOR` | IDLE | cancel timeout |
| REMOTE_TRANSMITTING | timeout (15 s) | IDLE | log warning |
| REMOTE_TRANSMITTING | TTS complete | IDLE | cancel timeout |
| CHANNEL_BUSY | `releaseFloorAndDisarm()` | REMOTE_TRANSMITTING | — |

### Optimistic Acquisition

Local immediately goes `LOCAL_TRANSMITTING` + starts STT on PTT press. Sends `REQUEST_FLOOR` async. 250ms collision window — if `FLOOR_DENIED` arrives → `CHANNEL_BUSY`.

### Collision Tie-Break

```kotlin
val localWins = when {
    localTime < remoteTime -> true
    localTime > remoteTime -> false
    else -> localPeerId < payload.senderId  // 8-char UUID-prefix lexicographic
}
```

### Session Lifecycle

**Connect:** setLanguage → prewarm → startHeartbeat(10s) → send `SESSION_INIT:<lang>:<mode>`  
**SESSION_INIT:** match → `sessionValidated=true`; mismatch → `SESSION_REJECT` → disconnect  
**Heartbeat:** 3 failures → disconnect  
**Disconnect:** if transmitting → `RELEASE_FLOOR` first; stop STT; reset all state

---

## 10. Text-to-Speech

### AndroidTtsEngine (primary/fallback)

- Utterance ID: `"${seqId}_${UUID.randomUUID()}"` — links `UtteranceProgressListener.onStart()` to `speak()` call
- **TTS latency:** `startMs` captured at `speak()`; `ttsMs = currentMs - startMs` on `onStart(uid)` → fires `onUtteranceStartedListener(seqId, startedMs)`
- **Locale cascade:** BCP-47 → Locale → `Locale.ENGLISH` → `Locale.US` → `Locale.getDefault()`
- Alert: `STREAM_ALARM` + max volume + `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`; volume restored via `alertPrevVolumes`

### Rasa-13 Neural VITS (`IndicParlerTtsEngine`)

Single shared `OfflineTts` model (~300MB) for all Indic languages via speaker ID:

| Lang | ID | | Lang | ID |
|---|---|---|---|---|
| hi | 0 | | or | 6 |
| bn | 1 | | ta | 8 |
| gu | 2 | | te | 9 |
| kn | 3 | | as | 10 |
| ml | 4 | | en | separate |
| mr | 5 | | | |

```
Rasa-13: https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/main/model.onnx
English: https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/model.onnx
```

### TextSegmenter

Splits before TTS: sentence punctuation (`. ! ? ।`) → clause (`, ; :`) → hard char limit.

---

## 11. Model Lifecycle

### ModelState FSM

`Idle → Checking → Downloading(n%) → Loading → Ready ↔ Running → Error`

- `Running`: `sttInferenceCount > 0`; unload blocked until count = 0
- `sttMutex` + `ttsMutex` serialize all lifecycle ops
- `acquireSttForInference()` increments count; `releaseSttForInference()` decrements
- Cache hit: `currentSttLang == lang && recognizer != null` → skip reload
- Rasa-13 key `"rasa13"` shared by all Indic languages → loaded once

### ModelDownloader

- Storage: `filesDir/models/<type>/<lang>/<file>`
- `cleanupStaleDownloads()` on startup removes zero-byte partials
- `initEspeakData()` extracts `assets/espeak-ng-data/` on first run

---

## 12. Battery Duty Cycle Scheduler

| Tier | Battery | Interval |
|---|---|---|
| ABOVE_50 | >50% | 150 ms |
| BETWEEN_20_AND_50 | 20–50% | 600 ms |
| BELOW_20 | <20% | 2,500 ms |

50ms burst: 800 samples @ 16kHz. RMS formula: `sqrt((1/N)×Σ(sᵢ/32768)²)`. If `RMS ≥ 0.015` → `onActivatePipeline()`.

---

## 13. Telemetry & Latency

### TelemetryState

```kotlin
data class TelemetryState(
    val sttLatencyMs: Long = 0L,   // neural inference duration
    val ttsLatencyMs: Long = 0L,   // speak() → onStart() delta
    val rtf: Float = 0f,           // sttLatencyMs / 1000 / audioDurationSec
    val e2eLatencyMs: Long = 0L,   // sttMs + netMs + ttsMs
    val payloadBytes: Int = 0,
    val activeLanguage: String = "",
    val transport: String = "",
)
```

### Measurement Points

**STT latency:** `inferenceDurationMs` from `IndicConformerSttEngine.processSpeechSegment()` (preferred) or `sttCompleteMs - sentence.timestampMs` (fallback).

**RTF:** `latencyMs / 1000.0f / audioDurationSec` — computed per-utterance in `IndicConformerSttEngine`, stored in `RecognizedSentence.rtf`.

**TTS latency:** `onUtteranceStartedListener(seqId, startedMs)` → `handleUtteranceStarted()` in `SessionController` → `startedMs - entry.receivedMs`.

**E2E on sender:** `sttLatency` (approx).  
**E2E on receiver:** `payload.sttLatencyMs + (receivedMs − sentTimestampMs) + ttsLatencyMs`.

### TranscriptEntry Timestamp Chain

```
micPauseDetectedMs → sttCompleteMs → sentMs → receivedMs → ttsStartedMs
  (VAD SPEECH_END)   (STT emit)    (send())  (dequeue)   (TTS.speak())

deltaModelMs   = sttCompleteMs   - micPauseDetectedMs
deltaQueueMs   = sentMs          - sttCompleteMs
deltaNetworkMs = receivedMs      - sentMs
deltaTtsStartMs= ttsStartedMs    - receivedMs
totalLatencyMs = sum of all
```

---

## 14. Room Database

```kotlin
@Database(entities = [HistoryEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase()
// Singleton: @Volatile INSTANCE + synchronized(this) DCL
// fallbackToDestructiveMigration()

@Entity(tableName = "session_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerName: String,       // "SENT" or "RECEIVED"
    val peerAddress: String,
    val timestampMs: Long,
    val durationMs: Long,
    val transportType: String,  // SessionMode.name
    val languageUsed: String,   // BCP-47
    val status: String,         // "SUCCESS"
)
```

Written in `appendTranscript()` on `Dispatchers.IO` inside `runCatching`.

---

## 15. UI & Navigation

```
startDestination = "splash"
"splash"               → SplashAnimationScreen → auto-navigate
"language_selection"   → LanguageSelectionScreen → on lang selected
"home/{languageCode}"  → HomeScreen → on device connected
"session"              → SessionScreen
```

SessionScreen components: `FloorControlButton | ModeSwitch | TranscriptList | LatencyPanel | GPSAlertSection | DutyCycleIndicator`. All state via `collectAsState()`.

---

## 16. End-to-End Data Flow

### Sender (PTT press → bytes on wire)

```
PTT press → requestFloorAndArm()
  FloorState: IDLE → LOCAL_TRANSMITTING
  sttEngine.start() [Command.Start queued]
  AudioRecord (16kHz, VOICE_COMMUNICATION) created
  AdaptiveNoiseFloorVadPipeline created

AudioRecord → shortChunk → frameAccum → 512-sample frame
  → SileroVad (prob=0.72) → adaptedThreshold=0.50 → gated=true
  → speechBuffer += frame
  → SpeechSegmenter: 3 voiced frames → SPEECH_START
                     9 silent frames → SPEECH_END → drainBuffer()

processSpeechSegment(floatSegment):
  peakCheck → OfflineRecognizer.decode → text="ஹலோ ஹலோ"
  latencyMs=187ms  audioDurationSec=1.2s  rtf=0.156
  emit RecognizedSentence(text, inferenceDurationMs=187, rtf=0.156, seq=6)

sendSentence(sentence):
  sttLatency=187, rtfVal=0.156
  Payload{SPEECH, "ஹலோ ஹலோ", lang=ta, seq=6, sentTimestampMs=T, senderId=3dc68681,
          sttLatencyMs=187, rtf=0.156}
  BinaryEncoder.encode(payload) → 37 bytes (payloadBytes)
  transport.send() → FramedSocket.writeFrame(JSON_PAYLOAD, bytes)
  _telemetry: {sttMs=187, rtf=0.156, bytes=37, transport="Bluetooth"}
  appendTranscript(SENT) → historyDao.insertHistory()

PTT release → releaseFloorAndDisarm()
  sttEngine.stop() → vadPipeline.flush()
  FloorState: LOCAL_TRANSMITTING → IDLE
  send RELEASE_FLOOR
```

### Receiver (bytes received → TTS audio)

```
FramedSocket.readFrame() → JSON_PAYLOAD
  Payload{SPEECH, "ஹலோ ஹலோ", seq=6, sentTimestampMs=T, sttLatencyMs=187, rtf=0.156}
  _onPayloadReceived.emit(payload)

handleIncomingSpeech(payload):
  FloorState: IDLE → REMOTE_TRANSMITTING
  armFloorTimeout(15s)
  receivedMs = currentMs
  initialNetMs = receivedMs - T
  _telemetry: {sttMs=187, rtf=0.156, netMs=initialNetMs, e2e=187+netMs+prevTts}

  ttsEngine.speak(sentence)
  → AndroidTtsEngine: uid="6_<uuid>", startMs=currentMs
  → tts.speak("ஹலோ ஹலோ")
  → onStart(uid): onUtteranceStartedListener(seq=6, startedMs)

handleUtteranceStarted(seq=6, startedMs):
  ttsLatency = startedMs - entry.receivedMs  [e.g. 42ms]
  netMs = entry.receivedMs - entry.sentMs
  e2e   = sttMs + netMs + ttsLatency
  _telemetry updated; entry.ttsStartedMs = startedMs

appendTranscript(RECEIVED) → historyDao.insertHistory()
FloorState → IDLE (on RELEASE_FLOOR or TTS done)
```

---

## 17. Security

| Aspect | Current State |
|---|---|
| Network | Direct BT RFCOMM or Wi-Fi Direct TCP only. No internet. No server. |
| Session validation | Language match enforced before any speech payload |
| Data at rest | Plain SQLite (no encryption). Prod: SQLCipher or Keystore-backed. |
| Floor tie-break | UUID prefix only — not cryptographic auth. Prod: sign payloads. |

---

## 18. Invariants & Failure Modes

### Critical Invariants

| # | Invariant | Enforced By |
|---|---|---|
| 1 | One AudioRecord at a time | `commandChannel` serialization |
| 2 | STT model not unloaded during inference | `sttInferenceCount` + `sttMutex` |
| 3 | Language locked during session | `setLanguage()` checks `isSessionActive()` |
| 4 | Floor released before disconnect | `disconnect()` checks `wasTransmitting` |
| 5 | TTS stopped on disconnect | `ttsEngine.stop()` in `onSessionLost()` |
| 6 | Transcript max 200 entries | `_transcript.update { it.takeLast(200) }` |
| 7 | Auto-disconnect on 3 heartbeat failures | `failedCount >= 3 → disconnect()` |
| 8 | VAD buffer auto-flushed at 56k samples | `isBufferOverMax` check → force flush |

### Failure Modes

| Failure | Mitigation |
|---|---|
| ONNX runtime unavailable | `isOrtUnavailable=true` → energy dBFS fallback (permanent) |
| STT model not downloaded | Inline `modelManager.loadSttModel(lang)` |
| TTS locale missing | Cascade: locale → English → US → device default |
| BT connection dropped | `onSessionLost()` resets all state |
| Floor stuck REMOTE_TRANSMITTING | 15s `armFloorTimeout()` → IDLE |
| Simultaneous PTT | Timestamp + UUID tie-break |
| Audio too quiet (maxAbs < 0.001) | Skipped (silence) |
| AudioRecord silent (rms=0) | CRITICAL log every 100 frames |

---

## File Index

| File | Role |
|---|---|
| `session/SessionController.kt` | Central orchestrator |
| `session/FloorState.kt` | Floor FSM enum |
| `session/TranscriptEntry.kt` | Timestamp chain + derived latency |
| `vad/AdaptiveNoiseFloorVadPipeline.kt` | EMA adaptive VAD |
| `vad/SileroVadEngine.kt` | Sherpa-ONNX Silero |
| `vad/SpeechSegmenter.kt` | Attack/hangover FSM |
| `stt/IndicConformerSttEngine.kt` | Conformer CTC + RTF |
| `stt/SpeechToTextEngine.kt` | STT interface |
| `tts/AndroidTtsEngine.kt` | System TTS + latency |
| `tts/IndicParlerTtsEngine.kt` | Rasa-13 neural VITS |
| `tts/TextSegmenter.kt` | Prosodic segmentation |
| `transport/binary/BinaryProtocol.kt` | Protocol constants |
| `transport/binary/BinaryEncoder.kt` | Encoder |
| `transport/binary/BinaryDecoder.kt` | Decoder |
| `transport/FramedSocket.kt` | ITAN frame header |
| `transport/TransportManager.kt` | flatMapLatest mux |
| `transport/BluetoothSocketTransport.kt` | BT RFCOMM |
| `transport/WifiDirectSocketTransport.kt` | Wi-Fi Direct TCP |
| `transport/Payload.kt` | Wire data model |
| `model/ModelManager.kt` | ONNX lifecycle |
| `model/ModelConfig.kt` | Language registry |
| `audio/DutyCycleScheduler.kt` | Battery scheduling |
| `audio/AudioRecorder.kt` | Mic capture |
| `audio/AudioPlayer.kt` | PCM playback |
| `db/AppDatabase.kt` | Room singleton |
| `db/HistoryEntity.kt` | Session history schema |
| `ui/MainNavGraph.kt` | Navigation |
| `ui/session/SessionScreen.kt` | Session UI |
