package com.itantra.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport abstraction used by ALL higher-level code.
 *
 * Higher-level code (SessionController, ViewModels) MUST depend only on this
 * interface — never directly on WifiP2pManager, Socket, or BluetoothSocket.
 * This isolation makes the transport layer fully swappable.
 */
interface TransportChannel {

    /** Emits every [Payload] received from the remote peer. Hot, never completes. */
    val onPayloadReceived: Flow<Payload>

    /** Live connection state — observed by the UI layer. */
    val connectionState: StateFlow<ConnectionState>

    /** Discovered peers available to connect to — observed by HomeScreen. */
    val discoveredDevices: StateFlow<List<DiscoveredDevice>>

    /**
     * Start peer discovery and advertise this device's presence.
     */
    suspend fun startAdvertisingAndDiscovery()

    /** Initiate a connection to [deviceAddress] (MAC address for Wi-Fi Direct). */
    suspend fun connectTo(deviceAddress: String)

    /**
     * Serialize [payload] as JSON and write it to the connected socket.
     * Suspends until the payload is written to the wire.
     * @throws IllegalStateException if called when not [ConnectionState.Connected].
     */
    suspend fun send(payload: Payload)

    /**
     * Explicitly disconnects any active socket connection, clears active P2P groups,
     * cancels active connection jobs, resets state to [ConnectionState.Idle] and clears discovered devices.
     * Idempotent and safe to call multiple times.
     */
    suspend fun disconnect()

    /** Release all resources permanently (e.g. app exit). */
    fun shutdown()
}

/** Lifecycle state of the transport channel. */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object Discovering : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val peerName: String, val isGroupOwner: Boolean) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** A peer device visible during discovery. */
data class DiscoveredDevice(
    val name: String,
    val address: String,
)
