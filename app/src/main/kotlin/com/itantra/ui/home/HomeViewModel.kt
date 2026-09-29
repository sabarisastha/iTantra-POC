package com.itantra.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.itantra.db.HistoryEntity
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.session.SessionController
import com.itantra.session.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.DiscoveredDevice
import com.itantra.transport.TransportManager
import com.itantra.transport.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "HomeViewModel"

class HomeViewModel(
    private val transport: TransportManager,
    private val sessionController: SessionController,
    private val modelManager: ModelManager,
    permissionsGranted: StateFlow<Boolean>,
    private val historyDao: com.itantra.db.HistoryDao? = null
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> = historyDao?.getAllHistory()
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        ?: MutableStateFlow(emptyList())

    val connectionState: StateFlow<ConnectionState> = transport.connectionState
        .onEach { state ->
            if (state is ConnectionState.Error &&
                state.message.contains("turned off", ignoreCase = true)) {
                _wifiOffDialog.value = true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = transport.discoveredDevices
    val activeTransport: StateFlow<TransportKind> = transport.activeKind
    val selectedLanguage: StateFlow<SupportedLanguage> = sessionController.selectedLanguage

    // Track STT/TTS download states from ModelManager
    val sttState: StateFlow<ModelState> = modelManager.sttState
    val ttsState: StateFlow<ModelState> = modelManager.ttsState

    private val _isModelDownloaded = MutableStateFlow(false)
    val isModelDownloaded: StateFlow<Boolean> = _isModelDownloaded.asStateFlow()

    private val _wifiOffDialog = MutableStateFlow(false)
    val wifiOffDialog: StateFlow<Boolean> = _wifiOffDialog.asStateFlow()

    init {
        // Watch for permission grant and auto-start discovery
        viewModelScope.launch {
            permissionsGranted.collect { granted ->
                if (granted) {
                    Log.d(TAG, "Permissions granted — starting discovery")
                    delay(500)
                    discover()
                }
            }
        }

        // Periodic auto-discovery loop to keep peer list fresh automatically
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                if (permissionsGranted.value) {
                    val state = connectionState.value
                    if (state is ConnectionState.Idle) {
                        Log.d(TAG, "Auto-refreshing peer discovery...")
                        discover()
                    }
                }
            }
        }

        // Whenever language changes, check if it's downloaded
        viewModelScope.launch {
            selectedLanguage.collect { lang ->
                _isModelDownloaded.value = modelManager.isModelDownloaded(lang.code)
            }
        }
    }

    fun downloadModels() {
        val lang = selectedLanguage.value.code
        viewModelScope.launch {
            try {
                modelManager.preDownloadModels(lang)
                _isModelDownloaded.value = modelManager.isModelDownloaded(lang)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading models", e)
            }
        }
    }

    /** Start / restart peer discovery. Safe to call repeatedly. */
    fun discover() {
        viewModelScope.launch {
            Log.d(TAG, "discover() called")
            transport.startAdvertisingAndDiscovery()
        }
    }

    fun connectTo(address: String) {
        viewModelScope.launch {
            Log.d(TAG, "connectTo($address)")
            transport.connectTo(address)
        }
    }

    fun selectTransport(kind: TransportKind) {
        if (sessionController.isSessionActive()) return
        viewModelScope.launch { transport.select(kind) }
    }

    fun setLanguage(lang: SupportedLanguage) {
        sessionController.setLanguage(lang)
    }

    fun dismissWifiDialog() { _wifiOffDialog.value = false }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao?.clearHistory()
        }
    }

    fun updatePeerAlias(peerKey: String, alias: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao?.updateAlias(peerKey, alias?.trim()?.ifBlank { null })
        }
    }

    fun updatePeerAliasById(id: Long, alias: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            historyDao?.updateAliasById(id, alias?.trim()?.ifBlank { null })
        }
    }

    companion object {
        fun factory(
            transport: TransportManager,
            sessionController: SessionController,
            modelManager: ModelManager,
            permissionsGranted: StateFlow<Boolean>,
            historyDao: com.itantra.db.HistoryDao? = null
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(transport, sessionController, modelManager, permissionsGranted, historyDao) as T
        }
    }
}
