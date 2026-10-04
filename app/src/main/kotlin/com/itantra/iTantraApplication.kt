package com.itantra

import android.app.Application
import com.itantra.session.SessionController
import com.itantra.transport.BluetoothSocketTransport
import com.itantra.transport.TransportManager
import com.itantra.transport.WifiDirectSocketTransport

/**
 * Application class — manual DI root.
 */
class iTantraApplication : Application() {

    private val wifiDirectTransport: WifiDirectSocketTransport by lazy {
        WifiDirectSocketTransport(this)
    }

    private val bluetoothTransport: BluetoothSocketTransport by lazy {
        BluetoothSocketTransport(this)
    }

    val database: com.itantra.db.AppDatabase by lazy {
        com.itantra.db.AppDatabase.getDatabase(this)
    }

    val historyDao: com.itantra.db.HistoryDao by lazy {
        database.historyDao()
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
     * Real Neural TTS Engine (Indic Parler / Sherpa-ONNX with automatic fallback).
     */
    val ttsEngine: com.itantra.tts.TextToSpeechEngine by lazy {
        com.itantra.tts.IndicParlerTtsEngine(this, modelManager)
    }

    /**
     * Single pipeline orchestrator — PTT and Phone modes both route through here.
     */
    val sessionController: SessionController by lazy {
        SessionController(sttEngine, ttsEngine, transport, historyDao, this)
    }

    override fun onTerminate() {
        super.onTerminate()
        sessionController.shutdown()
        transport.shutdown()
    }
}
