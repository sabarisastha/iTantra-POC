package com.itantra.transport

import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ITAN-SOCKET"

enum class FrameType(val code: Int) {
    JSON_PAYLOAD(1),
    RAW_PCM(2)
}

class FramedSocket(
    inStream: InputStream,
    outStream: OutputStream,
    private val closeBlock: () -> Unit,
    private val isClosedBlock: () -> Boolean,
) {
    constructor(socket: Socket) : this(
        socket.getInputStream(),
        socket.getOutputStream(),
        { socket.close() },
        { socket.isClosed }
    )

    constructor(bluetoothSocket: BluetoothSocket) : this(
        bluetoothSocket.inputStream,
        bluetoothSocket.outputStream,
        { bluetoothSocket.close() },
        { !bluetoothSocket.isConnected }
    )

    private val inputStream = DataInputStream(BufferedInputStream(inStream))
    private val outputStream = DataOutputStream(BufferedOutputStream(outStream))
    private val writeMutex = Mutex()

    suspend fun writeFrame(type: FrameType, payload: ByteArray) = withContext(Dispatchers.IO) {
        try {
            writeMutex.withLock {
                outputStream.writeInt(0x4954414E) // Magic 'ITAN'
                outputStream.writeInt(1) // Version
                outputStream.writeInt(type.code)
                outputStream.writeInt(payload.size)
                outputStream.write(payload)
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeFrame failed", e)
            close()
            throw e
        }
    }

    suspend fun readFrame(): Pair<FrameType, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val magic = inputStream.readInt()
            if (magic != 0x4954414E) {
                Log.e(TAG, "Invalid magic number, closing socket")
                close()
                return@withContext null
            }
            val version = inputStream.readInt()
            if (version != 1) {
                Log.e(TAG, "Unsupported protocol version: $version, closing socket")
                close()
                return@withContext null
            }
            val typeCode = inputStream.readInt()
            val length = inputStream.readInt()
            
            if (length < 0 || length > 512 * 1024) { // Max 512KB
                Log.e(TAG, "Invalid frame length: $length, closing socket")
                close()
                return@withContext null
            }
            
            val payload = ByteArray(length)
            inputStream.readFully(payload)
            
            val type = FrameType.entries.find { it.code == typeCode } ?: run {
                Log.e(TAG, "Invalid frame type: $typeCode, closing socket")
                close()
                return@withContext null
            }
            Pair(type, payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "readFrame failed: ${e.message}")
            null
        }
    }

    fun close() {
        Log.d(TAG, "close() called")
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { closeBlock() }
    }
    
    val isClosed: Boolean get() = isClosedBlock()
}
