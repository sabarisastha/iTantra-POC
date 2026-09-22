package com.itantra

import android.app.Application
import com.itantra.session.SessionController
import com.itantra.transport.BluetoothSocketTransport
import com.itantra.transport.TransportManager
import com.itantra.transport.WifiDirectSocketTransport

/**
 * Application class — manual DI root.
 *
 * All singletons are created here by lazy delegation so they are only
 * instantiated on first access (typically when MainActivity starts).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * MODEL SWAP GUIDE — exactly 2 lines to change for real model integration:
 *
 *   Line A:  val sttEngine = MockSttEngine(this)
 *            → Replace with: val sttEngine = RealSttEngine(this)
 *
 *   Line B:  val ttsEngine = MockTtsEngine(this)
 *            → Replace with: val ttsEngine = RealTtsEngine(this)
 *
 *   NO other file needs to change.  All higher-level code (SessionController,
 *   ViewModels, UI) depends exclusively on the SpeechToTextEngine and
 *   TextToSpeechEngine interfaces, never on the concrete mock classes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class iTantraApplication : Application() {

    /**
     * Primary Wi-Fi Direct transport channel.
     * Injected into [SessionController] as a [com.itantra.transport.TransportChannel] —
     * SessionController never references WifiDirectSocketTransport directly.
     */
    private val wifiDirectTransport: WifiDirectSocketTransport by lazy {
        WifiDirectSocketTransport(this)
    }

    private val bluetoothTransport: BluetoothSocketTransport by lazy {
        BluetoothSocketTransport(this)
    }

    /** One stable reference for UI and SessionController; Bluetooth is selected by default. */
    val transport: TransportManager by lazy {
        TransportManager(bluetoothTransport, wifiDirectTransport)
    }

    val modelManager: com.itantra.model.ModelManager by lazy {
        com.itantra.model.ModelManager(this).apply { init() }
    }

    /**
     * Real STT Engine.
     */
    val sttEngine: com.itantra.stt.SpeechToTextEngine by lazy {
        com.itantra.stt.IndicConformerSttEngine(this, modelManager)
    }

    /**
     * Real TTS Engine.
     */
    val ttsEngine: com.itantra.tts.TextToSpeechEngine by lazy {
        com.itantra.tts.IndicParlerTtsEngine(this, modelManager)
    }

    /**
     * Single pipeline orchestrator — PTT and Phone modes both route through here.
     */
    val sessionController: SessionController by lazy {
        SessionController(sttEngine, ttsEngine, transport)
    }

    override fun onTerminate() {
        super.onTerminate()
        sessionController.shutdown()
        transport.shutdown()
    }
}
