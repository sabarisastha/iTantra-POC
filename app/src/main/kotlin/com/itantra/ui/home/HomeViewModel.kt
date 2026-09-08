package com.itantra.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.itantra.session.SessionController
import com.itantra.session.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.DiscoveredDevice
import com.itantra.transport.TransportChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val transport: TransportChannel,
    private val sessionController: SessionController,
    permissionsGranted: StateFlow<Boolean>
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = transport.connectionState
        .onEach { state ->
            if (state is ConnectionState.Error &&
                state.message.contains("Wi-Fi is off", ignoreCase = true)
            ) {
                _wifiOffDialog.value = true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Idle)

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = transport.discoveredDevices
    val selectedLanguage: StateFlow<SupportedLanguage> = sessionController.selectedLanguage

    private val _wifiOffDialog = MutableStateFlow(false)
    val wifiOffDialog: StateFlow<Boolean> = _wifiOffDialog.asStateFlow()

    init {
        viewModelScope.launch {
            permissionsGranted.collect { granted ->
                if (granted && connectionState.value is ConnectionState.Idle) {
                    discover()
                }
            }
        }
    }

    fun discover() {
        viewModelScope.launch { transport.startAdvertisingAndDiscovery() }
    }

    fun connectTo(address: String) {
        viewModelScope.launch { transport.connectTo(address) }
    }

    fun setLanguage(lang: SupportedLanguage) {
        sessionController.setLanguage(lang)
    }

    fun dismissWifiDialog() { _wifiOffDialog.value = false }

    companion object {
        fun factory(
            transport: TransportChannel,
            sessionController: SessionController,
            permissionsGranted: StateFlow<Boolean>
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(transport, sessionController, permissionsGranted) as T
        }
    }
}
