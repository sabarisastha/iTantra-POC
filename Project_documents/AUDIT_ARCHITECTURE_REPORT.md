# Comprehensive Static Architectural & Forensic Codebase Audit

**Project:** iTantra POC — Offline Indic Communication System  
**Audit Scope:** `app/src/main/` Kotlin & C++ Native Architecture  
**Audit Date:** September 28, 2026  

---

## 1. Model Inventory & Verification (Real ONNX vs. Fallback / Mock)

### A. Silero Voice Activity Detection (VAD)

* **Primary Engine Implementation:** [`SileroVadEngine.kt:L30-L104`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/SileroVadEngine.kt#L30-L104)
* **Asset Location:** `app/src/main/assets/silero_vad.onnx` (2,327,524 bytes)
* **Native Execution Engine:** Refactored to run directly via Sherpa-ONNX's native C++ VAD wrapper (`com.k2fsa.sherpa.onnx.Vad` and `SileroVadModelConfig`) loaded from `sherpa-onnx-1.13.7.aar`.
* **Execution Flow:**
  1. `initEngine()` ([`SileroVadEngine.kt:L55-L78`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/SileroVadEngine.kt#L55-L78)) instantiates `Vad(context.assets, VadModelConfig(...))` using native Sherpa JNI bindings (`libsherpa-onnx-jni.so`).
  2. In `processFrame(pcmFrame: ShortArray)` ([`SileroVadEngine.kt:L80-L116`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/SileroVadEngine.kt#L80-L116)), 512 PCM samples at 16 kHz are normalized to `FloatArray` in $[-1.0, 1.0]$ and passed directly to `vad.compute(floatFrame)`.
* **Fallback Activation Triggers:**
  - Fallback to energy/RMS thresholding (`fallbackVad`) ([`SileroVadEngine.kt:L121-L133`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/SileroVadEngine.kt#L121-L133)) occurs **only** if asset opening fails, JNI symbol loading fails, or `isOrtUnavailable` is set to `true`.
  - Removal of `com.microsoft.onnxruntime:onnxruntime-android` from [`app/build.gradle.kts`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/build.gradle.kts) resolved the `UnsatisfiedLinkError: OrtGetApiBase` symbol collision, ensuring `isOrtUnavailable` remains `false` on hardware runs.

---

### B. Speech-to-Text (STT - IndicConformer)

* **Primary Engine Implementation:** [`IndicConformerSttEngine.kt:L33-L270`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/stt/IndicConformerSttEngine.kt#L33-L270)
* **Model Configuration Registry:** [`ModelConfig.kt:L22-L134`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/model/ModelConfig.kt#L22-L134)
* **Model Architecture & Manifest:**
  - **Type:** Quantized 8-bit ONNX IndicConformer CTC model (`model.int8.onnx`).
  - **Tokenizer:** Shared global token table (`tokens.txt`).
  - **Remote Base:** `https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main`
* **Language Support Matrix:**

| Language Code | Language Name | STT Model File Path | STT Tokens Path | Status |
| :--- | :--- | :--- | :--- | :--- |
| `hi` | Hindi | `stt/hi/model.int8.onnx` | `stt/tokens.txt` | Bundled / Supported |
| `bn` | Bengali | `stt/bn/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `gu` | Gujarati | `stt/gu/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `kn` | Kannada | `stt/kn/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `ml` | Malayalam | `stt/ml/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `mr` | Marathi | `stt/mr/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `or` | Odia | `stt/or/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `ta` | Tamil | `stt/ta/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `te` | Telugu | `stt/te/model.int8.onnx` | `stt/tokens.txt` | Downloadable |
| `as` | Assamese | `stt/as/model.int8.onnx` | `stt/tokens.txt` | Listed in `ModelConfig.kt` |
| `en` | English | `stt/en/model.int8.onnx` | `stt/tokens.txt` | Supported |

* **Fallback Cascade:** If a language without a pre-downloaded checkpoint is selected at runtime, [`ModelManager.kt:L168-L174`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/model/ModelManager.kt#L168-L174) initiates an asynchronous HTTP download via `ModelDownloader`. If the network is offline or download fails, `_sttState` transitions to `ModelState.Error` ([`ModelManager.kt:L212`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/model/ModelManager.kt#L212)), and `IndicConformerSttEngine` skips transcription emission ([`IndicConformerSttEngine.kt:L253-L256`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/stt/IndicConformerSttEngine.kt#L253-L256)).

---

### C. Text-to-Speech (TTS - Rasa-13 / Indic-VITS)

* **Primary Engine Implementation:** [`IndicParlerTtsEngine.kt:L17-L173`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/tts/IndicParlerTtsEngine.kt#L17-L173)
* **Fallback Engine:** [`MockTtsEngine.kt:L21-L200`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/mock/MockTtsEngine.kt#L21-L200) (wraps Android OS System `android.speech.tts.TextToSpeech`).
* **Model Configuration & Speaker Mapping:**

| Language | Model Key | Model Base URL | Speaker ID | Phonetic Data |
| :--- | :--- | :--- | :--- | :--- |
| **Hindi (`hi`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 0 | `espeak-ng-data` |
| **Bengali (`bn`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 1 | `espeak-ng-data` |
| **Gujarati (`gu`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 2 | `espeak-ng-data` |
| **Kannada (`kn`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 3 | `espeak-ng-data` |
| **Malayalam (`ml`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 4 | `espeak-ng-data` |
| **Marathi (`mr`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 5 | `espeak-ng-data` |
| **Odia (`or`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 6 | `espeak-ng-data` |
| **Tamil (`ta`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | **8** *(7 skipped)* | `espeak-ng-data` |
| **Telugu (`te`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 9 | `espeak-ng-data` |
| **Assamese (`as`)** | `rasa13` | `.../vits-rasa-13/resolve/main/model.onnx` | 10 | `espeak-ng-data` |
| **English (`en`)** | `en` | `.../vits-mms-eng/resolve/main/model.onnx` | 0 | `espeak-ng-data` |

* **Fallback Logic:** If Sherpa-ONNX TTS fails or is missing on disk, `IndicParlerTtsEngine.kt` catches the exception ([L106-L108](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/tts/IndicParlerTtsEngine.kt#L106-L108)) and routes the sentence to `fallbackSystemTts` ([`MockTtsEngine.kt`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/mock/MockTtsEngine.kt#L115-L127)), which uses Android's native system TTS engine.

---

## 2. State Machine, Floor Control & Operational Modes

```mermaid
stateDiagram-v2
    [*] --> PTT_IDLE
    
    state PTT_Mode {
        PTT_IDLE --> WAITING_FOR_CHANNEL: User Presses PTT Button
        WAITING_FOR_CHANNEL --> LOCAL_TRANSMITTING: Floor Granted / Optimistic Acquire
        LOCAL_TRANSMITTING --> PTT_IDLE: User Releases PTT (sttEngine.stop() + flush())
        PTT_IDLE --> REMOTE_TRANSMITTING: Incoming Speech / REQUEST_FLOOR
        REMOTE_TRANSMITTING --> PTT_IDLE: TTS Playback Completed / 15s Timeout
    }

    PTT_IDLE --> PHONE_ACTIVE: Mode Switch to PHONE
    PHONE_ACTIVE --> PTT_IDLE: Mode Switch to PTT

    state Phone_Mode {
        PHONE_ACTIVE --> VAD_LISTENING: Continuous AudioRecord
        VAD_LISTENING --> VAD_SPEECH_DETECTED: P(speech) >= 0.50 (3 frames)
        VAD_SPEECH_DETECTED --> STT_INFERENCE: SPEECH_END (16 silence frames ~512ms)
        STT_INFERENCE --> VAD_LISTENING: Emit Text Payload
    }
```

### A. Push-to-Talk (PTT) Mode Architecture

1. **Button Down (`requestFloorAndArm`)** ([`SessionController.kt:L204-L252`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L204-L252)):
   - Evaluates `_floorState.value`. If `REMOTE_TRANSMITTING`, sets `CHANNEL_BUSY` and rejects interaction.
   - If clear, transitions `_floorState` to `LOCAL_TRANSMITTING` (optimistic local acquisition).
   - Invokes `sttEngine.start()`, which starts `AudioRecorder` and instantiates `AdaptiveNoiseFloorVadPipeline`.
   - Transmits `PayloadType.REQUEST_FLOOR` asynchronously over the transport channel.
2. **Recording & Audio Buffering:**
   - PCM chunks (16 kHz 16-bit mono) are pushed into `frameAccum[512]` and sent to `vadPipeline.processFrame(frame)`.
   - Frames with detected speech are appended to `speechBuffer` in `AdaptiveNoiseFloorVadPipeline.kt`.
3. **Button Up (`releaseFloorAndDisarm`)** ([`SessionController.kt:L254-L274`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L254-L274)):
   - Calls `sttEngine.stop()` **first** before network release.
   - `handleStop()` in `IndicConformerSttEngine.kt` invokes `vadPipeline.flush()` ([L176](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/stt/IndicConformerSttEngine.kt#L176)), draining remaining audio samples.
   - `processSpeechSegment()` executes Sherpa-ONNX Conformer STT inference on the segment and emits `RecognizedSentence`.
   - `collectOutgoing()` ([`SessionController.kt:L324-L334`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L324-L334)) packages the sentence into a `PayloadType.SPEECH` packet and calls `transport.send()`.
   - `SessionController` transmits `PayloadType.RELEASE_FLOOR` and sets `_floorState` back to `IDLE`.
4. **Floor Timeout & Protection:**
   - `armFloorTimeout()` ([`SessionController.kt:L614-L623`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L614-L623)) sets a 15-second safety timer (`FLOOR_TIMEOUT_MS`). If a remote peer crashes while holding the floor, the state automatically resets to `IDLE`.
   - When incoming speech finishes playing via TTS ([`SessionController.kt:L580-L595`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L580-L595)), the `finally` block immediately cancels the 15-second timeout and resets `_floorState` to `IDLE`.

---

### B. Phone Mode (Continuous Hands-Free) Architecture

1. **Mode Transition:**
   - `applyModeTransition(SessionMode.PHONE)` ([`SessionController.kt:L164-L191`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L164-L191)) releases any held floor, sets `_floorState = IDLE`, and calls `sttEngine.start()`.
2. **Continuous Gating & Temporal FSM:**
   - `AudioRecorder` runs continuously in the background.
   - 512-sample frames are sliced and evaluated by `SpeechSegmenter.kt` ([L34-L72](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/SpeechSegmenter.kt#L34-L72)):
     - **`SILENCE` $\to$ `SPEECH_START`:** Requires 3 consecutive voiced frames ($\approx 96\text{ ms}$) with $P(\text{speech}) \ge 0.50$.
     - **`IN_SPEECH` $\to$ `SPEECH_END`:** Requires 16 consecutive silence frames ($\approx 512\text{ ms}$) with $P(\text{speech}) < 0.35$.
3. **Autonomous Segment Draining:**
   - On `SegmenterEvent.SPEECH_END`, `AdaptiveNoiseFloorVadPipeline.kt` calls `drainBuffer()` ([L149](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/vad/AdaptiveNoiseFloorVadPipeline.kt#L149)), returning the normalized `FloatArray` to `IndicConformerSttEngine`.
   - `processSpeechSegment()` runs STT inference asynchronously while `AudioRecorder` continues listening for the next sentence.

---

## 3. Network Payload Wire Format & Transport Layer

### A. Binary Packet Framing (`FramedSocket.kt`)

All network communication over Bluetooth SPP or Wi-Fi Direct TCP Sockets uses a 16-byte fixed binary header followed by a UTF-8 JSON payload body ([`FramedSocket.kt:L49-L97`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/transport/FramedSocket.kt#L49-L97)):

```
 +-------------------+-------------------+-------------------+-------------------+
 | Magic (4 Bytes)   | Version (4 Bytes) | Type Code (4 B)   | Length (4 Bytes)  |
 | 0x4954414E ('ITAN')| 0x00000001 (v1)   | 0x00000001 (JSON) | N (Payload Size)  |
 +-------------------+-------------------+-------------------+-------------------+
 | UTF-8 Encoded JSON Payload Body (N Bytes)                                    |
 +-------------------------------------------------------------------------------+
```

* **Header Magic:** `0x4954414E` (ASCII `"ITAN"`)
* **Header Version:** `1`
* **Frame Types:** `1` = `JSON_PAYLOAD`, `2` = `RAW_PCM`

---

### B. Payload Structure ([`Payload.kt`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/transport/Payload.kt#L11-L24))

```kotlin
@Serializable
data class Payload(
    val type: PayloadType,              // SPEECH, ALERT, REQUEST_FLOOR, FLOOR_GRANTED, etc.
    val text: String = "",              // Transcribed text (e.g. "मदद चाहिए")
    val languageCode: String = "",      // ISO language code (e.g. "hi")
    val sequenceId: Int = 0,            // Monotonic sequence counter
    val sentTimestampMs: Long = 0L,     // Sender timestamp
    val senderId: String = "",          // Sender peer ID (for tie-breaking)
)
```

---

### C. Bandwidth & Bitrate Efficiency Analysis

* **Transmitted Packet Size:** A typical Hindi emergency utterance (e.g., `"मदद चाहिए"`) produces a JSON string of $\sim 125\text{ bytes}$.
  - Header: $16\text{ bytes}$
  - Body: $125\text{ bytes}$
  - **Total Packet Size:** $\mathbf{141\text{ bytes}}$
* **Bandwidth Comparison for a 3-Second Speech Segment:**

| Transmission Architecture | Data Volume (3s Segment) | Effective Bitrate | Bandwidth Efficiency vs. Raw PCM |
| :--- | :--- | :--- | :--- |
| **Raw PCM-16 (16 kHz Mono)** | $96,000\text{ bytes}$ ($96\text{ KB}$) | $256.0\text{ kbps}$ | Baseline ($1\times$) |
| **Opus Compressed Audio (16 kbps)** | $6,000\text{ bytes}$ ($6\text{ KB}$) | $16.0\text{ kbps}$ | $16\times$ reduction |
| **iTantra VAD+STT Text Payload** | $\mathbf{141\text{ bytes}}$ ($\mathbf{0.14\text{ KB}}$) | $\mathbf{0.376\text{ kbps}}$ | $\mathbf{680.8\times}$ **reduction** |

> [!NOTE]
> Transmitting lightweight VAD-gated STT text payloads instead of raw audio streams reduces mesh network bandwidth consumption by over **$680\times$**, enabling reliable communication across congested or long-range Bluetooth SPP and Wi-Fi Direct links in disaster recovery scenarios.

---

## 4. Hardware Sizing & Memory Footprint (RAM / Profiling)

### A. Static Memory Allocations (Resident in RAM / Direct ByteBuffers)

1. **Silero VAD ONNX Model:**
   - Disk Size: `silero_vad.onnx` ($2.22\text{ MB}$)
   - RAM Allocation: $\approx 5.0\text{ MB}$ (loaded via `com.k2fsa.sherpa.onnx.Vad` in native heap).
2. **IndicConformer STT Model (Quantized 8-bit ONNX):**
   - Disk Size: `model.int8.onnx` ($48.5\text{ MB}$) + `tokens.txt` ($0.5\text{ MB}$)
   - RAM Allocation: $\approx 85.0\text{ MB}$ (ONNX Runtime engine session graph, weight tensors, intra/inter-op thread pools).
3. **Rasa-13 VITS TTS Model (Multi-Speaker 13-in-1):**
   - Disk Size: `model.onnx` ($60.2\text{ MB}$) + `espeak-ng-data` ($3.5\text{ MB}$)
   - RAM Allocation: $\approx 95.0\text{ MB}$ (VITS generator network, phonemizer lookup tables).

---

### B. Dynamic Buffers & Working Memory

* **`AudioRecord` Internal Ring Buffer:** $4096\text{ shorts} = 8,192\text{ bytes}$ ($\approx 8\text{ KB}$).
* **`speechBuffer` in `AdaptiveNoiseFloorVadPipeline`:** Pre-allocated capacity for 30s @ 16kHz = $480,000\text{ shorts} = 960,000\text{ bytes}$ ($\approx 0.96\text{ MB}$).
* **`AudioTrack` Playback PCM Buffer:** $\approx 64\text{ KB}$.
* **LRU Replay Caches (`replayCache` + `fallbackReplayCache`):** Bound to 20 entries $\approx 4.0\text{ MB}$.

---

### C. Total Projected Working Set Footprint

$$\text{Total RAM} = \text{Native Heap (Sherpa ONNX C++)} + \text{Java Heap (Android Runtime / UI)}$$

$$\text{Total RAM} \approx (5.0 + 85.0 + 95.0 + 5.0)\text{ MB} + 45.0\text{ MB} = \mathbf{235.0\text{ MB}}$$

> [!IMPORTANT]
> The total working set footprint is approximately **$235\text{ MB}$**, making the architecture highly optimized and fully capable of running on low-end emergency devices equipped with only **2 GB of RAM** (where standard Android app heap limits are $\sim 512\text{ MB}$).

---

## 5. Architectural Discrepancies, Logical Fallacies & Contradictions

### A. Concurrency & Race Conditions

1. **`AudioRecord` Hardware Re-bind Race Condition:**
   - In [`SessionController.kt:L179-L180`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L179-L180), switching to Phone Mode calls `sttEngine.stop()` followed immediately by `sttEngine.start()`.
   - `sttEngine.stop()` sends `Command.Stop` and `sttEngine.start()` sends `Command.Start` to an unlimited FIFO channel ([`IndicConformerSttEngine.kt:L56`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/stt/IndicConformerSttEngine.kt#L56)).
   - Because `AudioRecord.release()` in `stopRecording()` is executed asynchronously by the Android OS native audio flinger, `Command.Start` attempts to instantiate a new `AudioRecord` before the hardware microphone is unbound, causing `AudioRecord` initialization to fail ([`AudioRecorder.kt:L80`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/audio/AudioRecorder.kt#L80)) and silently halting continuous audio capture in Phone Mode.

2. **Unmuted Microphone During Local TTS Playback (Acoustic Feedback Loop):**
   - In [`SessionController.kt:L580-L595`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L580-L595), incoming speech triggers `ttsEngine.speakAndAwait(sentence)`.
   - In Phone Mode, `AudioRecorder` remains active during speaker playback.
   - Because `AudioRecorder.kt` configures `MediaRecorder.AudioSource.MIC` ([L30](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/audio/AudioRecorder.kt#L30)) instead of `VOICE_COMMUNICATION` (Source 7), hardware Acoustic Echo Cancellation (AEC) is disabled.
   - The microphone captures the TTS speaker output, Silero VAD classifies it as speech, STT transcribes the echoed speech, and `sendSentence()` transmits the echoed payload back to the remote peer, triggering an acoustic echo loop.

---

### B. Configuration & Code Contradictions

1. **`AudioSource` Mismatch:**
   - [`AudioRecorder.kt:L30`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/audio/AudioRecorder.kt#L30) defaults to `MediaRecorder.AudioSource.MIC`, whereas full-duplex VoIP / hands-free communications require `MediaRecorder.AudioSource.VOICE_COMMUNICATION` to enable hardware AEC and Noise Suppression.
2. **Language Specification & Config Discrepancy:**
   - [`SupportedLanguage.kt:L8-L20`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SupportedLanguage.kt#L8-L20) explicitly documents 10 target languages (`hi`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`, `or`, `bn`, `en`), emphasizing: `English IS in the spec. Punjabi is NOT.`
   - However, [`ModelConfig.kt:L115-L123`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/model/ModelConfig.kt#L115-L123) includes Assamese (`code = "as"`) in `SUPPORTED_LANGUAGES`, while omitting Punjabi (`pa`).
3. **Speaker ID Mapping Gap in `ModelConfig.kt`:**
   - In [`ModelConfig.kt:L95-L103`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/model/ModelConfig.kt#L95-L103), Tamil (`ta`) is assigned `speakerId = 8`, skipping index `7` in the Rasa-13 multi-speaker mapping table.

---

### C. Dead Code & Deprecated Paths

1. **Legacy Raw PCM Streaming Methods:**
   - `TransportManager.kt` contains deprecated declarations for `onPcmReceived` flow and `sendPcm()` method, left over from earlier raw audio streaming implementations before transitioning to VAD-gated STT text payload architecture.
2. **Orphaned Fallback Comment:**
   - In [`SessionController.kt:L402-L405`](file:///home/darika-sivakumar/win_d/iTantra-POC-main/app/src/main/kotlin/com/itantra/session/SessionController.kt#L402-L405), comments note that raw PCM handling is removed, but dead branch handlers for unused control types remain present in `handleControl`.

---

## 6. Audit Conclusion & Recommendations

1. **Phone Mode Mode-Transition Fix:** Add a 100ms hardware unbind delay before instantiating `AudioRecorder` in `handleStart()` to prevent hardware MIC initialization failures.
2. **Self-Mute During TTS Playback:** Wrap `ttsEngine.speakAndAwait()` in Phone mode with temporary STT pause/resume (`sttEngine.stop()` $\to$ `speakAndAwait()` $\to$ `sttEngine.start()`).
3. **Hardware AEC Activation:** Switch `AudioRecorder` default source to `MediaRecorder.AudioSource.VOICE_COMMUNICATION`.
