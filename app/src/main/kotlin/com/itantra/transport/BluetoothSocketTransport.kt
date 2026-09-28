package com.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.itantra.transport.binary.BinaryDecoder
import com.itantra.transport.binary.BinaryEncoder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val TAG = "ITAN-BLUETOOTH"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class BluetoothSocketTransport(
    private val context: Context,
) : TransportChannel {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

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

    @Volatile private var framedSocket: FramedSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private var connectionJob: Job? = null
    private var receiveJob: Job? = null
    private var acceptJob: Job? = null

    private var broadcastReceiver: BroadcastReceiver? = null

    init {
        registerReceiver()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertisingAndDiscovery() {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled or missing permissions")
            return
        }

        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return
        }

        _connectionState.value = ConnectionState.Discovering
        val devices = mutableListOf<DiscoveredDevice>()

        bluetoothAdapter.bondedDevices?.forEach { device ->
            devices.add(DiscoveredDevice(device.name ?: "Unknown", device.address))
        }
        _discoveredDevices.value = devices.toList()

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        bluetoothAdapter.startDiscovery()
        
        startServer()
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        acceptJob?.cancel()
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket?.close()
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("iTantra", SPP_UUID)
                Log.d(TAG, "Server socket listening")
                
                val socket = withTimeoutOrNull(30_000L) { serverSocket?.accept() }
                if (socket != null) {
                    Log.d(TAG, "Accepted incoming connection")
                    handleConnectedSocket(socket, "Peer", true)
                } else if (_connectionState.value is ConnectionState.Connecting) {
                    Log.w(TAG, "RFCOMM accept timed out")
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                    _connectionState.value = ConnectionState.Error("Bluetooth connection timed out")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Accept failed", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    override suspend fun connectTo(deviceAddress: String) {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null) {
            _connectionState.value = ConnectionState.Error("Missing permissions")
            return
        }
        
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.Connecting
            val myMac = bluetoothAdapter.address ?: "00:00:00:00:00:00"
            
            val isServer = myMac > deviceAddress
            Log.d(TAG, "Connecting to $deviceAddress, myMac=$myMac, isServer=$isServer")
            
            if (isServer) {
                Log.d(TAG, "I have higher MAC, acting as server (waiting for peer to connect)")
                // Server should already be running from startAdvertisingAndDiscovery, we just wait
            } else {
                Log.d(TAG, "I have lower MAC, acting as client")
                try {
                    val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                    var connected = false
                    for (i in 1..3) {
                        var socket: BluetoothSocket? = null
                        try {
                            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                            socket.connect()
                            connected = true
                            handleConnectedSocket(socket, device.name ?: "Unknown", false)
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Connect attempt $i failed", e)
                            runCatching { socket?.close() }
                            delay(1000)
                        }
                    }
                    
                    if (!connected) {
                        throw IOException("Could not connect after 3 attempts")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Connection failed", e)
                    _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
                    performCleanup()
                }
            }
        }
    }

    private fun handleConnectedSocket(socket: BluetoothSocket, peerName: String, isGroupOwner: Boolean) {
        runCatching { framedSocket?.close() }
        framedSocket = FramedSocket(socket)
        
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        
        val address = runCatching { socket.remoteDevice?.address }.getOrNull() ?: ""
        _connectionState.value = ConnectionState.Connected(peerName, isGroupOwner, address)
        Log.d(TAG, "Connected to $peerName (isServer=$isGroupOwner)")
        
        receiveJob?.cancel()
        receiveJob = scope.launch { receiveLoop() }
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val socket = framedSocket ?: return@withContext
        while (isActive && !socket.isClosed) {
            val frame = socket.readFrame()
            if (frame == null) {
                Log.w(TAG, "Remote disconnected")
                break
            }
            when (frame.first) {
                FrameType.BINARY_PAYLOAD -> {
                    val payload = BinaryDecoder.decode(frame.second)?.copy(receivedTimestampMs = System.currentTimeMillis())
                    if (payload != null) {
                        _onPayloadReceived.emit(payload)
                    } else {
                        Log.e(TAG, "Binary decode failed for frame size=${frame.second.size}")
                    }
                }
                FrameType.JSON_PAYLOAD -> {
                    val str = String(frame.second, Charsets.UTF_8)
                    runCatching {
                        val payload = json.decodeFromString<Payload>(str).copy(receivedTimestampMs = System.currentTimeMillis())
                        _onPayloadReceived.emit(payload)
                    }.onFailure { Log.e(TAG, "JSON parse error", it) }
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
        withContext(Dispatchers.Main) { performCleanup() }
    }

    override suspend fun send(payload: Payload) {
        val socket = framedSocket ?: throw IllegalStateException("Not connected")
        val encoded = BinaryEncoder.encode(payload)
        socket.writeFrame(FrameType.BINARY_PAYLOAD, encoded)
    }

    override suspend fun sendPcm(pcm: FloatArray) {
        val socket = framedSocket ?: throw IllegalStateException("Not connected")
        val byteBuffer = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in pcm) byteBuffer.putFloat(f)
        socket.writeFrame(FrameType.RAW_PCM, byteBuffer.array())
    }

    override suspend fun disconnect() {
        performCleanup()
    }

    @SuppressLint("MissingPermission")
    private suspend fun performCleanup() = withContext(Dispatchers.IO) {
        Log.d(TAG, "performCleanup")
        receiveJob?.cancel()
        receiveJob = null
        connectionJob?.cancel()
        connectionJob = null
        acceptJob?.cancel()
        acceptJob = null

        runCatching { framedSocket?.close() }
        framedSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null

        if (hasBluetoothPermissions()) {
            runCatching { bluetoothAdapter?.cancelDiscovery() }
        }

        _connectionState.value = ConnectionState.Idle
    }

    override fun shutdown() {
        scope.launch { performCleanup() }
        unregisterReceiver()
        scope.cancel()
    }

    private fun registerReceiver() {
        if (broadcastReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        if (device != null && hasBluetoothPermissions()) {
                            val name = device.name ?: "Unknown"
                            val address = device.address
                            val currentList = _discoveredDevices.value.toMutableList()
                            if (currentList.none { it.address == address }) {
                                currentList.add(DiscoveredDevice(name, address))
                                _discoveredDevices.value = currentList
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        broadcastReceiver = receiver
    }

    private fun unregisterReceiver() {
        broadcastReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            broadcastReceiver = null
        }
    }
}
