# iTantra — Offline Multilingual Neural Transceiver
### SIH 2026 Production Solution · Low-Latency Neural Speech & Data Relay

A two-phone offline neural speech and emergency data transceiver: **Phone A speaks → VAD & Sherpa-ONNX IndicConformer STT → Compact Binary Relay over Wi-Fi Direct / Bluetooth RFCOMM → Phone B → Android System TTS → Speaker Output**. 

Designed for zero-infrastructure, disaster response, and tactical field environments — **100% offline, zero internet, zero cloud, and zero external service dependencies**.

---

## 🚀 Key Architectural Innovations

- **Sherpa-ONNX IndicConformer STT Engine**: On-device neural speech recognition across 10 official Indian languages using ONNX Runtime.
- **Adaptive Noise-Floor Silero VAD Pipeline**: Dynamic exponential moving average (EMA) noise-floor tracking coupled with a Silero ONNX VAD engine and temporal state machine (`SpeechSegmenter`).
- **Low-Latency Sentence Slicing (Phone Mode)**: Automatic 3.5-second hard ceiling (56,000 samples @ 16kHz mono) and 9-frame (~288ms) hangover hold-off preventing CPU exhaustion and maintaining sub-350ms decoding latency during continuous speech.
- **Dual-Transport Protocol Manager**: Automatic failover between Wi-Fi Direct (TCP socket) and Bluetooth RFCOMM sockets.
- **Compact 18-Byte Binary Protocol**: Custom binary wire format with bitmask header flags, variable payload length, and adaptive DEFLATE text compression.
- **5-Point Evaluation Telemetry**: Real-time instrumentation matching hackathon rubric criteria (STT Duration, STT Real-Time Factor (RTF), Network Latency, TTS Speaker Startup Delay, and Total E2E Delta).
- **Emergency Beacon & Compass Navigation**: Live GPS coordinates, accuracy bounds, peer distance estimation, and real-time sensor-fused compass pointer (`EmergencyCompassView`).

---

## 📁 Project Structure

```
iTantra-POC/
├── app/src/main/kotlin/com/itantra/
│   ├── audio/               AudioRecorder (16kHz PCM-16 short streaming), DutyCycleScheduler
│   ├── db/                  Room Database (HistoryDao, HistoryEntity) for offline session logs
│   ├── model/               ModelManager (Sherpa-ONNX model asset loader & lifecycle)
│   ├── session/             SessionController, SessionMode (PTT / PHONE), FloorState,
│   │                        TranscriptEntry, SupportedLanguage
│   ├── stt/                 SpeechToTextEngine interface, IndicConformerSttEngine,
│   │                        RecognizedSentence
│   ├── telemetry/           TelemetryState (5-point evaluation metrics)
│   ├── transport/           TransportChannel, TransportManager, Payload, PayloadType,
│   │                        WifiDirectSocketTransport, BluetoothSocketTransport,
│   │                        binary/ (BinaryEncoder, BinaryDecoder, BinaryProtocol, DeflateCompressor)
│   ├── tts/                 TextToSpeechEngine, AndroidTtsEngine (System TTS with UtteranceProgressListener)
│   ├── ui/                  HomeScreen, SessionScreen, EmergencyCompassView, LatencyPanel, Theme
│   ├── utils/               LocationHelper, LiveLocationState, MovementState
│   ├── MainActivity.kt
│   └── MainNavGraph.kt
└── iTantraApplication.kt    Application entry point & Manual Dependency Injection root
```

---

## 🌐 Supported SIH 2026 Languages (10 Languages)

| Code | Language   | Script / Locale | Note |
|------|------------|-----------------|------|
| `hi` | Hindi      | `hi_IN`         | Core Supported Language |
| `gu` | Gujarati   | `gu_IN`         | Core Supported Language |
| `mr` | Marathi    | `mr_IN`         | Core Supported Language |
| `kn` | Kannada    | `kn_IN`         | Core Supported Language |
| `ml` | Malayalam  | `ml_IN`         | Core Supported Language |
| `ta` | Tamil      | `ta_IN`         | Core Supported Language |
| `te` | Telugu     | `te_IN`         | Core Supported Language |
| `or` | Odia       | `or_IN`         | Core Supported Language |
| `bn` | Bengali    | `bn_IN`         | Core Supported Language |
| `en` | English    | `en_US`         | Primary Tactical Standard |

---

## 📊 Telemetry & Hackathon Evaluation Metrics (20% Criteria)

The application instrumentates and displays 5 core metrics in the live `LatencyPanel` overlay:

1. **Words Said to STT Completion (`sttDurationMs`)**: Time delta from speech boundary detection to Sherpa-ONNX model output emission.
2. **STT Real-Time Factor (`sttRtf`)**: `(STT Compute Time in Seconds) / (Audio Input Duration in Seconds)`.
3. **Network Transport Latency (`deltaNetworkMs`)**: Socket transport transmission delay between devices.
4. **TTS Speaker Startup Delay (`deltaTtsStartMs`)**: Time delta from socket payload arrival to actual acoustic output from speaker (`UtteranceProgressListener.onStart`).
5. **Total End-to-End (E2E) Delta (`totalLatencyMs`)**: `STT Duration + Network Latency + TTS Startup Delay`.

### UI Latency Chip Display:
```text
[STT: 75ms (0.18x) | Net: 42ms | TTS: 310ms | Total E2E: 427ms]
```

---

## 📱 Running on Physical Android Devices

> **Note**: Wi-Fi Direct and Bluetooth hardware sockets require physical Android devices (Android 7.0 / API 24+).

1. Build and install:
   ```bash
   ./gradlew assembleDebug
   ```
2. Grant runtime permissions when prompted (`RECORD_AUDIO`, `NEARBY_WIFI_DEVICES` / `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).
3. Open iTantra on both devices — discovery starts automatically.
4. Connect Phone B to Phone A from the discovered peer list.

### 🎙 Walkie-Talkie Push-to-Talk (PTT) Mode
- Press and hold **Hold to Talk** button -> speak utterance -> release button.
- Audio is gated by Silero VAD, decoded by Sherpa-ONNX, transmitted via binary protocol, and synthesized instantly on the remote peer.

### 📞 Hands-Free Phone Mode
- Toggle mode switch to **PHONE** mode.
- Speak continuously — audio is auto-sliced at natural 300ms pauses or 3.5s hard ceiling into sub-350ms chunks streaming in real-time.

### 🚨 Emergency Alert & Navigation
- Tap the **Send Alert** floating button.
- Dispatches emergency alert payload with GPS coordinates, accuracy, and status.
- Receiving device triggers maximum volume alarm playback and opens the **Emergency Compass Pointer** pointing directly to sender's location.

---

## 🔒 Permissions & Security Architecture

| Permission | Purpose & Context |
|---|---|
| `INTERNET` | Required by Android runtime for local `java.net.Socket` operations. Zero internet calls exist. |
| `NEARBY_WIFI_DEVICES` | Android 13+ peer discovery for Wi-Fi Direct P2P group creation. |
| `ACCESS_FINE_LOCATION` | Required for legacy Wi-Fi Direct scanning and GPS location emergency beacons. |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | Required for Bluetooth RFCOMM peer discovery and secondary transport failover. |
| `RECORD_AUDIO` | Required for 16kHz PCM-16 microphone audio capture. |

---

## 🛠 Unit Testing & Verification

Run the test suite via Gradle:
```bash
./gradlew test
```
All unit tests for `SpeechSegmenter`, `AdaptiveNoiseFloorVadPipeline`, `TextSegmenter`, and `BinaryProtocol` are included.
