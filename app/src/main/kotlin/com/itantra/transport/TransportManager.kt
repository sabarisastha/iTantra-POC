package com.itantra.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

private const val TAG = "ITAN-TRANSPORT"

/** The user-visible transport choice. Bluetooth is the primary offline path. */
enum class TransportKind(val label: String) {
    BLUETOOTH("Bluetooth"),
    WIFI_DIRECT("Wi-Fi Direct"),
}

/**
 * The sole transport reference used above the transport layer.  Its flows follow the
 * selected concrete channel, preventing UI/session traffic from being left on an old
 * transport after a switch.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransportManager(
    private val bluetooth: TransportChannel,
    private val wifiDirect: TransportChannel,
) : TransportChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeKind = MutableStateFlow(TransportKind.BLUETOOTH)
    val activeKind: StateFlow<TransportKind> = _activeKind.asStateFlow()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())

    private fun active(): TransportChannel = when (_activeKind.value) {
        TransportKind.BLUETOOTH -> bluetooth
        TransportKind.WIFI_DIRECT -> wifiDirect
    }

    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    init {
        scope.launch {
            _activeKind.flatMapLatest { active().connectionState }.collect { _connectionState.value = it }
        }
        scope.launch {
            _activeKind.flatMapLatest { active().discoveredDevices }.collect { _discoveredDevices.value = it }
        }
    }

    override val onPayloadReceived: Flow<Payload> = _activeKind.flatMapLatest {
        active().onPayloadReceived
    }

    override val onPcmReceived: Flow<FloatArray> = _activeKind.flatMapLatest {
        active().onPcmReceived
    }

    /** Switch only between sessions; always tear down stale discovery/socket state first. */
    suspend fun select(kind: TransportKind) {
        if (_activeKind.value == kind) return
        val previous = active()
        Log.d(TAG, "switch ${_activeKind.value} -> $kind: cleaning previous transport")
        previous.disconnect()
        _activeKind.value = kind
        active().startAdvertisingAndDiscovery()
    }

    override suspend fun startAdvertisingAndDiscovery() = active().startAdvertisingAndDiscovery()
    override suspend fun connectTo(deviceAddress: String) = active().connectTo(deviceAddress)
    override suspend fun send(payload: Payload) = active().send(payload)
    override suspend fun sendPcm(pcm: FloatArray) = active().sendPcm(pcm)
    override suspend fun disconnect() = active().disconnect()
    override fun shutdown() {
        bluetooth.shutdown()
        wifiDirect.shutdown()
        scope.cancel()
    }
}
