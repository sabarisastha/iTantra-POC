# iTantra — Offline Multilingual Neural Transceiver
### SIH 2026 Scaffold · Mock Build · Version 1.0

A two-phone offline speech relay: Phone A speaks → STT → text sent over Wi-Fi Direct (TCP) → Phone B → TTS → audio out. No cloud, no internet, no Google Play Services dependency.

---

## Project Structure

```
iTantra/
├── app/src/main/kotlin/com/itantra/
│   ├── stt/                 SpeechToTextEngine interface + RecognizedSentence model
│   ├── tts/                 TextToSpeechEngine interface
│   ├── transport/           TransportChannel interface, Payload model,
│   │                        WifiDirectSocketTransport (primary),
│   │                        BluetoothSocketTransport (stub)
│   ├── session/             SessionController, SessionMode, SupportedLanguage,
│   │                        TranscriptEntry, Direction
│   ├── mock/                MockSttEngine (real VAD, placeholder text)
│   │                        MockTtsEngine (Android TTS — TEMPORARY)
│   └── ui/
│       ├── theme/           iTantraTheme + Color palette
│       ├── home/            HomeScreen + HomeViewModel (peer discovery & connect)
│       ├── session/         SessionScreen + SessionViewModel (PTT/Phone session)
│       ├── MainActivity.kt
│       └── MainNavGraph.kt
└── iTantraApplication.kt    Manual DI root — THE ONLY FILE TO TOUCH FOR MODEL SWAP
```

---

## Confirmed SIH 2026 Language List (10 languages)

| Code | Language  | Note |
|------|-----------|------|
| hi   | Hindi     | |
| gu   | Gujarati  | |
| mr   | Marathi   | |
| kn   | Kannada   | |
| ml   | Malayalam | |
| ta   | Tamil     | |
| te   | Telugu    | |
| or   | Odia      | |
| bn   | Bengali   | |
| en   | **English** | ← in spec; **NOT** Punjabi |

---

## Swapping to Real Models

> **The modeling team needs to touch exactly 3 files. No other changes are required.**

### Step 1 — Replace MockSttEngine

Create `app/src/main/kotlin/com/itantra/stt/RealSttEngine.kt`:

```kotlin
class RealSttEngine(context: Context) : SpeechToTextEngine {
    // Keep AudioRecord setup from MockSttEngine (16 kHz, mono, PCM-16)
    // Keep the VAD state machine — or replace with a proper VAD library
    // Replace the tryEmit("[sim-stt …]") call with:
    //   val transcript = yourTfLiteModel.infer(audioBuffer, languageCode)
    //   _recognizedSentences.tryEmit(RecognizedSentence(text = transcript, …))
    override val recognizedSentences: Flow<RecognizedSentence> = _recognizedSentences.asSharedFlow()
    override fun start() { … }
    override fun stop()  { … }
}
```

### Step 2 — Replace MockTtsEngine

Create `app/src/main/kotlin/com/itantra/tts/RealTtsEngine.kt`:

```kotlin
class RealTtsEngine(context: Context) : TextToSpeechEngine {
    // Keep the alert-path AudioManager logic (max volume + AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
    // Replace Android TextToSpeech.speak() with your open-source TTS model inference
    override fun speak(sentence: RecognizedSentence) { … }
    override fun shutdown() { … }
}
```

### Step 3 — Update iTantraApplication.kt (2 lines)

```kotlin
// Line A: change MockSttEngine → RealSttEngine
val sttEngine: RealSttEngine by lazy { RealSttEngine(this) }

// Line B: change MockTtsEngine → RealTtsEngine
val ttsEngine: RealTtsEngine by lazy { RealTtsEngine(this) }
```

**That is all.** SessionController, TransportChannel, all UI, and all ViewModels are interface-bound and require zero changes.

---

## Running on Two Physical Phones

> Wi-Fi Direct (WifiP2pManager) does NOT work on Android emulators. Two physical devices (minSdk 24) are required.

1. `./gradlew assembleDebug` (or Build → Make Project in Android Studio)
2. Install on both phones.
3. Grant all runtime permissions when prompted (RECORD_AUDIO, NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT).
4. Open app on both — both show "Discovering…"
5. Phone B taps **Connect** next to Phone A in the peer list.
6. Both navigate to Session screen.

### PTT test
- Phone A: hold the PTT circle button → speak for 2+ seconds → release
- Both phones: transcript shows `[sim-stt segment #1, lang=hi]`
- Phone B: hears the mock TTS output

### Phone mode test
- Toggle both to **PHONE** mode
- Speak naturally — segments appear automatically when you pause

### Alert test
- Tap **Send Alert** FAB on Phone A
- Phone B: alert plays at **maximum volume**, non-interruptible

### Latency overlay
- Expand the **⏱ Latency Debug** card on Phone B
- Read Δ network, Δ TTS start — all populated from the Payload timestamp chain

---

## Key Architecture Decisions

| Decision | Reason |
|---|---|
| `WifiP2pManager` + TCP Socket, NOT Nearby Connections | Nearby Connections requires closed-source Google Play Services. Problem statement requires open-source only. |
| Newline-delimited JSON (`kotlinx.serialization`) | Simple, self-framing stream protocol. No length prefix needed; `BufferedReader.readLine()` handles it. |
| `INTERNET` permission for local TCP | Android requires this permission for *any* `java.net.Socket` usage, even over a local P2P group with no internet reachability. |
| Bluetooth permissions in manifest | Reserved for future `BluetoothSocketTransport` fallback. Zero active BT logic currently. |
| No Hilt/Dagger | Keeps build fast and the scaffold simple. Manual DI in `iTantraApplication` is sufficient. |
| Single pipeline (PTT + Phone) | Both modes use identical STT → Transport → TTS path. Mode only controls when `SpeechToTextEngine.start()/stop()` is called. |
| Real AudioRecord + VAD in mock | Allows end-to-end latency benchmarking of the pause-detection mechanism before the TFLite models are ready. |

---

## Permissions Explained for Judges

| Permission | Why it is here |
|---|---|
| `INTERNET` | Required by Android for `java.net.Socket`. All traffic stays within the local Wi-Fi Direct group. No internet calls exist anywhere in the code. |
| `NEARBY_WIFI_DEVICES` | Android 13+ replacement for `ACCESS_FINE_LOCATION` specifically for Wi-Fi Direct peer discovery. No location data is collected (`neverForLocation` flag set). |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | Reserved for the future `BluetoothSocketTransport` fallback. Currently no active Bluetooth logic. |

---

## Transport Channel Interface Contract

Higher-level code (SessionController, ViewModels) **must** depend only on `TransportChannel`. Never import `WifiP2pManager`, `Socket`, or `BluetoothSocket` outside the `transport/` package.

```kotlin
interface TransportChannel {
    val onPayloadReceived: Flow<Payload>
    val connectionState: StateFlow<ConnectionState>
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>
    suspend fun startAdvertisingAndDiscovery()
    suspend fun connectTo(deviceAddress: String)
    suspend fun send(payload: Payload)
    fun shutdown()
}
```
