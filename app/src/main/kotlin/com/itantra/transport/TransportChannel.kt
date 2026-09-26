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

    /**
     * Emits every raw PCM audio chunk received from the remote peer.
     *
     * @deprecated Phone Mode now transmits text via VAD+STT instead of raw PCM.
     * This flow is no longer collected by [SessionController]. Retained for protocol
     * compatibility only; do not add new consumers.
     */
    @Deprecated(
        message = "Phone Mode uses VAD-gated STT → text payloads. Raw PCM streaming is removed.",
        level = DeprecationLevel.WARNING
    )
    val onPcmReceived: Flow<FloatArray>

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
     * Send raw PCM over the transport.
     *
     * @deprecated Phone Mode uses VAD-gated STT → text [Payload] instead of raw PCM.
     * This method is no longer called by [SessionController]. Retained for protocol
     * compatibility only; do not add new call-sites.
     */
    @Deprecated(
        message = "Phone Mode uses VAD-gated STT → text payloads. Raw PCM streaming is removed.",
        level = DeprecationLevel.WARNING
    )
    suspend fun sendPcm(pcm: FloatArray)

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
    data object Idle : ConnectionState()
    data object Discovering : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val peerName: String, val isGroupOwner: Boolean) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
    data class Disconnected(val reason: String) : ConnectionState()
}

/** A peer device visible during discovery. */
data class DiscoveredDevice(
    val name: String,
    val address: String,
)
