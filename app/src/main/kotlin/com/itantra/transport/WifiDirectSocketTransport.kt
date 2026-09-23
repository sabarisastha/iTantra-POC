package com.itantra.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "ITANTRA-WIFI"
private const val TCP_PORT = 8988
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val CLIENT_RETRY_DELAY_MS = 800L
private const val CLIENT_MAX_RETRIES = 6

class WifiDirectSocketTransport(
    private val context: Context,
) : TransportChannel {

    private val json = Json { ignoreUnknownKeys = true }

    // Single long-lived scope with SupervisorJob — never cancelled permanently.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val p2pChannel: WifiP2pManager.Channel? =
        wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
    private var broadcastReceiver: BroadcastReceiver? = null

    // ── Exposed state ─────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _onPayloadReceived = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val onPayloadReceived: Flow<Payload> = _onPayloadReceived.asSharedFlow()

    private val _onPcmReceived = MutableSharedFlow<FloatArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val onPcmReceived: Flow<FloatArray> = _onPcmReceived.asSharedFlow()

    // ── Active resources ──────────────────────────────────────────────────────
    @Volatile private var framedSocket: FramedSocket? = null
    @Volatile private var serverSocket: ServerSocket? = null
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null      // the job owning the current connection attempt/session
    private var receiveJob: Job? = null         // the read-loop job

    init {
        registerReceiver()
        Log.d(TAG, "WifiDirectSocketTransport initialized")
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private fun hasWifiP2pPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    override suspend fun startAdvertisingAndDiscovery() {
        if (!hasWifiP2pPermission()) {
            Log.w(TAG, "startAdvertisingAndDiscovery: missing permissions")
            return
        }
        val ch = p2pChannel ?: return
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            Log.d(TAG, "startAdvertisingAndDiscovery: skipping because already connected/connecting")
            return
        }
        Log.d(TAG, "Starting peer discovery")
        _connectionState.value = ConnectionState.Discovering
        
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            try {
                wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "discoverPeers: started") }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverPeers: failed reason=$reason")
                        if (_connectionState.value !is ConnectionState.Connected && _connectionState.value !is ConnectionState.Connecting) {
                            _connectionState.value = ConnectionState.Idle
                        }
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "discoverPeers failed: ${e.message}")
            }
        }
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    override suspend fun connectTo(deviceAddress: String) {
        if (!hasWifiP2pPermission()) {
            _connectionState.value = ConnectionState.Error("Missing permissions")
            return
        }

        // Cancel any in-flight connection attempt, but do NOT disconnect an already established socket
        connectionJob?.cancel()

        connectionJob = scope.launch {
            Log.d(TAG, "[ITANTRA-WIFI] state=CONNECTING to $deviceAddress")
            _connectionState.value = ConnectionState.Connecting

            var requestAccepted = false
            for (attempt in 1..3) {
                val config = WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
                    wps.setup = WpsInfo.PBC
                }

                Log.d(TAG, "Initiating wifiP2pManager.connect() attempt $attempt/3 to $deviceAddress")
                val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        try {
                            wifiP2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    Log.d(TAG, "wifiP2pManager.connect() request accepted by framework (attempt $attempt)")
                                    if (cont.isActive) cont.resume(0) // 0 = SUCCESS
                                }
                                override fun onFailure(reason: Int) {
                                    Log.e(TAG, "wifiP2pManager.connect() failed reason=$reason (attempt $attempt)")
                                    if (cont.isActive) cont.resume(reason)
                                }
                            })
                        } catch (e: SecurityException) {
                            Log.e(TAG, "connect failed with SecurityException: ${e.message}")
                            if (cont.isActive) cont.resume(-1)
                        }
                    }
                }

                if (result == 0) {
                    requestAccepted = true
                    break
                } else {
                    Log.w(TAG, "Connect attempt $attempt failed with reason $result — retrying after cleanup...")
                    p2pChannel?.let { ch ->
                        runCatching {
                            try {
                                wifiP2pManager?.cancelConnect(ch, null)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "cancelConnect failed: ${e.message}")
                            }
                        }
                    }
                    delay(1200)
                }
            }

            if (!requestAccepted) {
                Log.e(TAG, "Connection request rejected after retries — resetting to IDLE")
                performCleanup()
            }
            // If true: wait for WIFI_P2P_CONNECTION_CHANGED_ACTION broadcast → handleConnectionInfo()
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    override suspend fun send(payload: Payload) {
        val socket = framedSocket
            ?: throw IllegalStateException("[ITANTRA-SOCKET] send() called while not connected")
        val encoded = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        Log.d(TAG, "[ITANTRA-SOCKET] send seq=${payload.sequenceId} type=${payload.type}")
        socket.writeFrame(FrameType.JSON_PAYLOAD, encoded)
    }

    override suspend fun sendPcm(pcm: FloatArray) {
        val socket = framedSocket
            ?: throw IllegalStateException("[ITANTRA-SOCKET] sendPcm() called while not connected")
        val byteBuffer = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in pcm) byteBuffer.putFloat(f)
        socket.writeFrame(FrameType.RAW_PCM, byteBuffer.array())
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    override suspend fun disconnect() {
        Log.d(TAG, "[ITANTRA-WIFI] disconnect() called")
        performCleanup()
    }

    /**
     * Deterministic cleanup: cancel all jobs, close all sockets, remove Wi-Fi P2P group,
     * reset to IDLE. Safe to call multiple times.
     */
    private suspend fun performCleanup() = withContext(NonCancellable + Dispatchers.IO) {
        Log.d(TAG, "performCleanup() start")

        val rJob = receiveJob
        receiveJob = null
        rJob?.cancel()
        
        connectionJob?.cancel()
        connectionJob = null
        
        discoveryJob?.cancel()
        discoveryJob = null

        runCatching { framedSocket?.close() }
        framedSocket = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        val ch = p2pChannel
        if (ch != null && hasWifiP2pPermission()) {
            withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    try {
                        wifiP2pManager?.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                            override fun onFailure(r: Int) { if (cont.isActive) cont.resume(Unit) }
                        })
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
            withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    try {
                        wifiP2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                            override fun onFailure(r: Int) { if (cont.isActive) cont.resume(Unit) }
                        })
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }

        _discoveredDevices.value = emptyList()
        _connectionState.value = ConnectionState.Idle
        Log.d(TAG, "[ITANTRA-WIFI] state=IDLE (cleanup complete)")
    }

    override fun shutdown() {
        runBlocking { performCleanup() }
        unregisterReceiver()
        scope.cancel()
    }

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    private fun registerReceiver() {
        if (broadcastReceiver != null) return
        val ch = p2pChannel ?: return
        val mgr = wifiP2pManager ?: return
        val receiver = WifiP2pBroadcastReceiver(
            manager       = mgr,
            channel       = ch,
            hasPermission = ::hasWifiP2pPermission,
            onPeers = { devices ->
                _discoveredDevices.value = devices.map {
                    DiscoveredDevice(it.deviceName.ifBlank { "Unknown" }, it.deviceAddress)
                }
            },
            onConnectionInfo = ::handleConnectionInfo,
            onP2pEnabled = { enabled ->
                if (!enabled) {
                    Log.w(TAG, "Wi-Fi Direct disabled — cleaning up")
                    _connectionState.value = ConnectionState.Error("Wi-Fi Direct is turned off")
                    scope.launch { performCleanup() }
                }
            }
        )

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        broadcastReceiver = receiver
        Log.d(TAG, "BroadcastReceiver registered")
    }

    private fun unregisterReceiver() {
        broadcastReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            broadcastReceiver = null
            Log.d(TAG, "BroadcastReceiver unregistered")
        }
    }

    // ── Connection info callback (from BroadcastReceiver) ─────────────────────

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        Log.d(TAG, "[ITANTRA-WIFI] connectionInfo received: groupFormed=${info.groupFormed} isOwner=${info.isGroupOwner}")

        if (!info.groupFormed) {
            // Remote disconnect or group dissolved
            if (_connectionState.value !is ConnectionState.Idle) {
                Log.w(TAG, "Group dissolved — triggering cleanup and reconnect to IDLE")
                scope.launch { performCleanup() }
            }
            return
        }

        // Avoid setting up a duplicate socket if already connected
        if ((_connectionState.value is ConnectionState.Connected && framedSocket?.isClosed == false) || connectionJob?.isActive == true) {
            Log.d(TAG, "handleConnectionInfo: already connected or connection in progress — ignoring duplicate broadcast")
            return
        }

        val ownerAddress = info.groupOwnerAddress ?: run {
            Log.e(TAG, "groupOwnerAddress is null — cannot proceed")
            return
        }

        // Cancel previous transport setup job (not full cleanup) and start fresh
        connectionJob?.cancel()
        connectionJob = scope.launch {
            Log.d(TAG, "[ITANTRA-SOCKET] Starting socket setup (isOwner=${info.isGroupOwner})")
            if (info.isGroupOwner) {
                runServer()
            } else {
                runClient(ownerAddress)
            }
        }
    }

    // ── TCP Server ────────────────────────────────────────────────────────────

    private suspend fun runServer() = withContext(Dispatchers.IO) {
        var retryCount = 0
        while (retryCount < 3) {
            try {
                // Always close any stale server socket before creating a new one
                runCatching { serverSocket?.close() }
                val ss = ServerSocket(TCP_PORT).apply {
                    reuseAddress = true
                    soTimeout = 25_000
                }
                serverSocket = ss
                Log.d(TAG, "[ITANTRA-SOCKET] server started on port $TCP_PORT")
                val client = ss.accept()
                runCatching { ss.close() }
                serverSocket = null
                setupSocket(client, "Client", isGroupOwner = true)
                return@withContext
            } catch (e: BindException) {
                retryCount++
                Log.w(TAG, "Server bind failed (attempt $retryCount): ${e.message}")
                if (retryCount >= 3) {
                    _connectionState.value = ConnectionState.Error("Server bind error: ${e.message}")
                    return@withContext
                }
                delay(1000)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Server error: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Server error: ${e.message}")
                return@withContext
            }
        }
    }

    // ── TCP Client ────────────────────────────────────────────────────────────

    private suspend fun runClient(ownerAddress: InetAddress) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (attempt in 1..CLIENT_MAX_RETRIES) {
            try {
                Log.d(TAG, "[ITANTRA-SOCKET] client attempt $attempt/$CLIENT_MAX_RETRIES → $ownerAddress:$TCP_PORT")
                val s = Socket()
                s.connect(InetSocketAddress(ownerAddress, TCP_PORT), 5000)
                setupSocket(s, "GroupOwner", isGroupOwner = false)
                return@withContext // success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Client attempt $attempt failed: ${e.message}")
                delay(CLIENT_RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "All $CLIENT_MAX_RETRIES client attempts failed: ${lastError?.message}")
        _connectionState.value = ConnectionState.Error("Could not reach Group Owner")
    }

    // ── Socket Setup ──────────────────────────────────────────────────────────

    private fun setupSocket(s: Socket, peerName: String, isGroupOwner: Boolean) {
        s.tcpNoDelay = true
        s.soTimeout  = 0  // no read timeout; receive loop handles disconnect via null return

        // Ensure previous framed socket is closed
        runCatching { framedSocket?.close() }
        framedSocket = FramedSocket(s)
        val address = runCatching { s.inetAddress?.hostAddress }.getOrNull() ?: ""
        _connectionState.value = ConnectionState.Connected(peerName, isGroupOwner, address)
        Log.d(TAG, "[ITANTRA-SOCKET] client connected peerName=$peerName isOwner=$isGroupOwner")

        // Cancel old receive loop (shouldn't exist, but safety)
        receiveJob?.cancel()
        receiveJob = scope.launch { receiveLoop() }
    }

    // ── Receive Loop ──────────────────────────────────────────────────────────

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        Log.d(TAG, "[ITANTRA-SOCKET] receive loop started")
        val socket = framedSocket ?: return@withContext
        while (isActive && !socket.isClosed) {
            val frame = socket.readFrame()
            if (frame == null) {
                Log.w(TAG, "[ITANTRA-SOCKET] null frame — remote disconnected")
                break
            }
            when (frame.first) {
                FrameType.JSON_PAYLOAD -> {
                    val str = String(frame.second, Charsets.UTF_8)
                    runCatching {
                        val payload = json.decodeFromString<Payload>(str).copy(receivedTimestampMs = System.currentTimeMillis())
                        _onPayloadReceived.emit(payload)
                    }.onFailure { Log.e(TAG, "JSON parse error: ${it.message}") }
                }
                FrameType.RAW_PCM -> {
                    if (frame.second.size % 4 != 0) {
                        Log.w(TAG, "Dropping malformed PCM frame size=${frame.second.size}")
                        continue
                    }
                    val buf = ByteBuffer.wrap(frame.second).order(ByteOrder.LITTLE_ENDIAN)
                    val floats = FloatArray(frame.second.size / 4) { buf.float }
                    _onPcmReceived.tryEmit(floats)
                }
            }
        }
        Log.w(TAG, "[ITANTRA-SOCKET] receive loop ended — triggering reconnect cleanup")
        // Remote dropped; transition to Idle so UI can attempt reconnect
        withContext(Dispatchers.Main) { performCleanup() }
    }
}

// ── BroadcastReceiver ─────────────────────────────────────────────────────────

private class WifiP2pBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val hasPermission: () -> Boolean,
    private val onPeers: (Collection<WifiP2pDevice>) -> Unit,
    private val onConnectionInfo: (WifiP2pInfo) -> Unit,
    private val onP2pEnabled: (Boolean) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                onP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (hasPermission()) {
                    try {
                        manager.requestPeers(channel) { list ->
                            onPeers(list?.deviceList ?: emptyList())
                        }
                    } catch (e: SecurityException) {
                        Log.w("ITANTRA-WIFI", "requestPeers SecurityException: ${e.message}")
                    }
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                if (hasPermission()) {
                    try {
                        manager.requestConnectionInfo(channel) { info ->
                            info?.let { onConnectionInfo(it) }
                        }
                    } catch (e: SecurityException) {
                        Log.w("ITANTRA-WIFI", "requestConnectionInfo SecurityException: ${e.message}")
                    }
                }
            }
        }
    }
}
