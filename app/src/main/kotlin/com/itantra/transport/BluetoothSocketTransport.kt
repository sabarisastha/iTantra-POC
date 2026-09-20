package com.itantra.transport

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothSocketTransport(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : TransportChannel {

    private val _payloads = MutableSharedFlow<Payload>()
    private val _pcm      = MutableSharedFlow<FloatArray>()
    private val _state    = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _devices  = MutableStateFlow<List<DiscoveredDevice>>(emptyList())

    override val onPayloadReceived: Flow<Payload>               = _payloads.asSharedFlow()
    override val onPcmReceived: Flow<FloatArray>               = _pcm.asSharedFlow()
    override val connectionState:   StateFlow<ConnectionState>  = _state.asStateFlow()
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    override suspend fun startAdvertisingAndDiscovery(): Unit =
        throw UnsupportedOperationException(
            "BluetoothSocketTransport is not yet implemented. Use WifiDirectSocketTransport.",
        )

    override suspend fun connectTo(deviceAddress: String): Unit =
        throw UnsupportedOperationException("BluetoothSocketTransport is not yet implemented.")

    override suspend fun send(payload: Payload): Unit =
        throw UnsupportedOperationException("BluetoothSocketTransport is not yet implemented.")
        
    override suspend fun sendPcm(pcm: FloatArray): Unit =
        throw UnsupportedOperationException("BluetoothSocketTransport is not yet implemented.")

    override suspend fun disconnect() {
        _state.value = ConnectionState.Idle
        _devices.value = emptyList()
    }

    override fun shutdown() { /* no-op - nothing to release */ }
}
