package com.itantra.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WifiDirectTransport"
private const val TCP_PORT = 9876

class WifiDirectSocketTransport(private val context: Context) : TransportChannel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json  = Json { ignoreUnknownKeys = true }

    private val wifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    @Volatile private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var broadcastReceiver: BroadcastReceiver? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _onPayloadReceived = MutableSharedFlow<Payload>(extraBufferCapacity = 64)
    override val onPayloadReceived: Flow<Payload> = _onPayloadReceived.asSharedFlow()

    @Volatile private var activeSocket: Socket?       = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socketWriter: PrintWriter?  = null

    private fun hasWifiP2pPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun startAdvertisingAndDiscovery() {
        if (!isWifiEnabled()) {
            Log.w(TAG, "Wi-Fi is off")
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            _connectionState.value =
                ConnectionState.Error("Wi-Fi is off. Enable Wi-Fi and tap Discover again.")
            return
        }
        initP2pChannel()
        registerReceiver()
        discoverPeers()
    }

    override suspend fun connectTo(deviceAddress: String) {
        val ch = p2pChannel
        if (ch == null) {
            val msg = "Channel not initialized"
            _connectionState.value = ConnectionState.Error(msg)
            throw RuntimeException(msg)
        }
        if (!hasWifiP2pPermission()) {
            val msg = "Missing P2P permission"
            _connectionState.value = ConnectionState.Error(msg)
            throw SecurityException(msg)
        }

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "connectTo: connecting to $deviceAddress")

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            cont.invokeOnCancellation {
                resumed.set(true)
            }
            try {
                wifiP2pManager.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "connect() started for $deviceAddress")
                        if (resumed.compareAndSet(false, true)) {
                            cont.resume(Unit)
                        }
                    }
                    override fun onFailure(reason: Int) {
                        val msg = "connect() failed: reason=$reason"
                        Log.e(TAG, msg)
                        _connectionState.value = ConnectionState.Error(msg)
                        if (resumed.compareAndSet(false, true)) {
                            cont.resumeWithException(RuntimeException(msg))
                        }
                    }
                })
            } catch (e: SecurityException) {
                val msg = "SecurityException in connect: ${e.message}"
                Log.e(TAG, msg, e)
                _connectionState.value = ConnectionState.Error(msg)
                if (resumed.compareAndSet(false, true)) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    override suspend fun send(payload: Payload) {
        val writer = socketWriter
            ?: throw IllegalStateException("send() called while not connected")
        withContext(Dispatchers.IO) {
            val encoded = json.encodeToString(payload)
            Log.d(TAG, "send() [seq=${payload.sequenceId}, type=${payload.type}, text='${payload.text}', lang=${payload.languageCode}] -> wire: $encoded")
            writer.println(encoded)
            writer.flush()
            if (writer.checkError()) {
                Log.e(TAG, "PrintWriter error after send()")
            } else {
                Log.d(TAG, "send() successfully flushed to wire")
            }
        }
    }

    override suspend fun disconnect() {
        Log.d(TAG, "disconnect() called — performing safe session cleanup")
        withContext(Dispatchers.IO) {
            // 1. Close sockets and writer
            runCatching { socketWriter?.close() }
            runCatching { activeSocket?.close() }
            runCatching { serverSocket?.close() }
            socketWriter = null
            activeSocket = null
            serverSocket = null

            // 2. Cancel child connection jobs without cancelling scope permanently
            scope.coroutineContext.cancelChildren()

            // 3. Remove active P2P Group
            val ch = p2pChannel
            if (ch != null && hasWifiP2pPermission()) {
                try {
                    wifiP2pManager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.d(TAG, "wifiP2pManager.removeGroup() onSuccess")
                        }
                        override fun onFailure(reason: Int) {
                            Log.d(TAG, "wifiP2pManager.removeGroup() onFailure (reason=$reason)")
                        }
                    })
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException in removeGroup", e)
                }
            }

            // 4. Reset connection and device state
            _discoveredDevices.value = emptyList()
            _connectionState.value = ConnectionState.Idle
            Log.d(TAG, "disconnect() completed cleanly — state is now Idle")
        }
    }

    override fun shutdown() {
        Log.d(TAG, "shutdown() called — releasing all resources")
        unregisterReceiver()
        runCatching { socketWriter?.close() }
        runCatching { activeSocket?.close() }
        runCatching { serverSocket?.close() }
        socketWriter = null
        activeSocket = null
        serverSocket = null

        val ch = p2pChannel
        if (ch != null && hasWifiP2pPermission()) {
            try {
                wifiP2pManager.removeGroup(ch, null)
            } catch (e: Exception) {
                Log.e(TAG, "Exception removing group on shutdown", e)
            }
        }
        p2pChannel = null
        scope.cancel()
        _connectionState.value = ConnectionState.Idle
        _discoveredDevices.value = emptyList()
    }

    private fun isWifiEnabled(): Boolean =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.isWifiEnabled == true

    private fun initP2pChannel() {
        if (p2pChannel != null) return
        Log.d(TAG, "Initializing Wi-Fi P2P Channel...")
        p2pChannel = wifiP2pManager.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "Wi-Fi Direct channel lost — resetting")
            p2pChannel = null
            _connectionState.value = ConnectionState.Error("Wi-Fi Direct disconnected. Tap Discover to retry.")
        }
    }

    private suspend fun discoverPeers() {
        val ch = p2pChannel ?: return
        if (!hasWifiP2pPermission()) {
            Log.e(TAG, "Missing P2P permission")
            _connectionState.value = ConnectionState.Error("Missing required permissions")
            return
        }
        
        _connectionState.value = ConnectionState.Discovering
        Log.d(TAG, "Starting discoverPeers()...")
        
        suspendCancellableCoroutine { cont ->
            val resumed = AtomicBoolean(false)
            cont.invokeOnCancellation {
                resumed.set(true)
            }
            try {
                wifiP2pManager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "discoverPeers() onSuccess")
                        if (resumed.compareAndSet(false, true)) cont.resume(Unit)
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverPeers() failed: reason=$reason")
                        if (resumed.compareAndSet(false, true)) cont.resume(Unit)
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in discoverPeers", e)
                _connectionState.value = ConnectionState.Error("Permission denied: ${e.message}")
                if (resumed.compareAndSet(false, true)) cont.resume(Unit)
            }
        }
    }

    private fun registerReceiver() {
        if (broadcastReceiver != null) return
        val ch = p2pChannel ?: return
        val receiver = WifiP2pBroadcastReceiver(
            manager  = wifiP2pManager,
            channel  = ch,
            hasPermission = ::hasWifiP2pPermission,
            onPeers  = { devices ->
                Log.d(TAG, "Discovered peers updated: ${devices.size} devices found")
                _discoveredDevices.value = devices.map { DiscoveredDevice(it.deviceName.ifBlank { "Unknown" }, it.deviceAddress) }
            },
            onConnectionInfo = ::handleConnectionInfo,
            onP2pEnabled = { enabled ->
                Log.d(TAG, "Wi-Fi P2P enabled: $enabled")
                if (!enabled) _connectionState.value = ConnectionState.Error("Wi-Fi Direct turned off")
            },
        )
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        broadcastReceiver = receiver
        Log.d(TAG, "Registered WifiP2pBroadcastReceiver")
    }

    private fun unregisterReceiver() {
        broadcastReceiver?.let {
            Log.d(TAG, "Unregistering WifiP2pBroadcastReceiver")
            runCatching { context.unregisterReceiver(it) }
            broadcastReceiver = null
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        Log.d(TAG, "handleConnectionInfo: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, groupOwnerAddress=${info.groupOwnerAddress}")
        if (!info.groupFormed) {
            Log.d(TAG, "Group disbanded — reverting to Idle/Discovering")
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.Idle
            }
            return
        }
        
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            if (activeSocket != null && !activeSocket!!.isClosed) {
                Log.d(TAG, "Active socket already established, ignoring duplicate connection info")
                return
            }
        }

        val ownerAddress = info.groupOwnerAddress ?: return
        if (info.isGroupOwner) {
            Log.d(TAG, "Role: GROUP OWNER — starting ServerSocket on port $TCP_PORT")
            scope.launch { runServer() }
        } else {
            val ip = ownerAddress.hostAddress ?: return
            Log.d(TAG, "Role: CLIENT — connecting to Group Owner at $ip:$TCP_PORT")
            scope.launch { runClient(InetAddress.getByName(ip)) }
        }
    }

    private suspend fun runServer() = withContext(Dispatchers.IO) {
        runCatching {
            val ss = ServerSocket(TCP_PORT)
            serverSocket = ss
            Log.d(TAG, "ServerSocket listening on port $TCP_PORT …")
            val client = ss.accept()
            Log.d(TAG, "Client accepted: ${client.inetAddress.hostAddress}")
            ss.close() // Close listener immediately; 1:1 transceiver
            serverSocket = null
            setupSocket(client, peerName = "Client", isGroupOwner = true)
        }.onFailure { e ->
            Log.e(TAG, "ServerSocket error", e)
            _connectionState.value = ConnectionState.Error("Server socket error: ${e.message}")
        }
    }

    private suspend fun runClient(ownerAddress: InetAddress) = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        repeat(6) { attempt ->
            runCatching {
                Log.d(TAG, "Client connecting attempt ${attempt + 1}/6 to $ownerAddress:$TCP_PORT ...")
                val s = Socket(ownerAddress, TCP_PORT)
                setupSocket(s, peerName = "GroupOwner", isGroupOwner = false)
                return@withContext
            }.onFailure { e ->
                lastException = e as? Exception
                Log.w(TAG, "Client connect attempt ${attempt + 1}/6 failed: ${e.message}")
                delay(600L)
            }
        }
        Log.e(TAG, "Client could not connect after 6 attempts", lastException)
        _connectionState.value = ConnectionState.Error("Could not reach Group Owner after retries")
    }

    private fun setupSocket(s: Socket, peerName: String, isGroupOwner: Boolean) {
        activeSocket = s
        socketWriter = PrintWriter(s.getOutputStream(), /* autoFlush = */ true)
        _connectionState.value = ConnectionState.Connected(peerName, isGroupOwner)
        Log.d(TAG, "Socket connected & ready (peer=$peerName, GO=$isGroupOwner) — starting receive loop")
        scope.launch { receiveLoop(s) }
    }

    private suspend fun receiveLoop(s: Socket) = withContext(Dispatchers.IO) {
        runCatching {
            BufferedReader(InputStreamReader(s.getInputStream())).use { reader ->
                while (!s.isClosed) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "receiveLoop read line from socket: $line")
                    runCatching {
                        val payload = json.decodeFromString<Payload>(line)
                            .copy(receivedTimestampMs = System.currentTimeMillis())
                        Log.d(TAG, "Emitting received payload to onPayloadReceived: seq=${payload.sequenceId}, text='${payload.text}', type=${payload.type}")
                        _onPayloadReceived.tryEmit(payload)
                    }.onFailure { e -> Log.e(TAG, "Deserialization failed for payload line: $line", e) }
                }
            }
        }
        Log.d(TAG, "Receive loop ended — remote socket closed")
        if (_connectionState.value is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Idle
        }
    }
}

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
                        manager.requestPeers(channel) { list: WifiP2pDeviceList? ->
                            onPeers(list?.deviceList ?: emptyList())
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException in requestPeers", e)
                    }
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                if (hasPermission()) {
                    try {
                        manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                            info?.let { onConnectionInfo(it) }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException in requestConnectionInfo", e)
                    }
                }
            }
        }
    }
}
