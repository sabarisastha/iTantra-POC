package com.itantra.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FrameType(val code: Int) {
    JSON_PAYLOAD(1),
    RAW_PCM(2)
}

class FramedSocket(private val socket: Socket) {
    private val inputStream = DataInputStream(socket.getInputStream())
    private val outputStream = DataOutputStream(socket.getOutputStream())

    suspend fun writeFrame(type: FrameType, payload: ByteArray) = withContext(Dispatchers.IO) {
        try {
            synchronized(outputStream) {
                outputStream.writeInt(0x4954414E) // Magic 'ITAN'
                outputStream.writeInt(1) // Version
                outputStream.writeInt(type.code)
                outputStream.writeInt(payload.size)
                outputStream.write(payload)
                outputStream.flush()
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    suspend fun readFrame(): Pair<FrameType, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val magic = inputStream.readInt()
            if (magic != 0x4954414E) return@withContext null
            val version = inputStream.readInt()
            val typeCode = inputStream.readInt()
            val length = inputStream.readInt()
            
            if (length < 0 || length > 10 * 1024 * 1024) return@withContext null // Max 10MB
            
            val payload = ByteArray(length)
            inputStream.readFully(payload)
            
            val type = FrameType.entries.find { it.code == typeCode } ?: return@withContext null
            Pair(type, payload)
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        try { socket.close() } catch (e: Exception) {}
    }
    
    val isClosed: Boolean get() = socket.isClosed
}
