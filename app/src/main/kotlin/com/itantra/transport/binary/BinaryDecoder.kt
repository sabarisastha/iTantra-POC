package com.itantra.transport.binary

import android.util.Log
import com.itantra.transport.Payload
import com.itantra.transport.PayloadType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json

private const val TAG = "BinaryDecoder"

private fun logW(tag: String, msg: String) {
    try {
        android.util.Log.w(tag, msg)
    } catch (_: Throwable) {
        System.err.println("[$tag] WARN: $msg")
    }
}

private fun logE(tag: String, msg: String, tr: Throwable? = null) {
    try {
        android.util.Log.e(tag, msg, tr)
    } catch (_: Throwable) {
        System.err.println("[$tag] ERROR: $msg: ${tr?.message}")
    }
}

/**
 * Compact binary decoder for [Payload] messages.
 * Includes validation for protocol version, message type, language ID, packet bounds, and truncation.
 * Also includes safe JSON fallback if legacy or debug JSON frames arrive.
 */
object BinaryDecoder {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Decodes a binary packet into a [Payload].
     * Returns null if the packet is invalid, truncated, or corrupted (safe failure).
     */
    fun decode(bytes: ByteArray): Payload? {
        if (bytes.isEmpty()) {
            logW(TAG, "Empty packet received")
            return null
        }

        // Isolated JSON fallback / debug path: if packet starts with '{', attempt JSON decode
        if (bytes[0] == '{'.code.toByte()) {
            return runCatching {
                val jsonString = String(bytes, Charsets.UTF_8)
                json.decodeFromString<Payload>(jsonString)
            }.onFailure {
                logW(TAG, "JSON fallback parse failed: ${it.message}")
            }.getOrNull()
        }

        // Header truncation check
        if (bytes.size < BinaryProtocol.HEADER_SIZE) {
            logW(TAG, "Truncated packet: size=${bytes.size} < minimum header size ${BinaryProtocol.HEADER_SIZE}")
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // 1. Validate Protocol Version
        val version = buffer.get()
        if (version != BinaryProtocol.PROTOCOL_VERSION) {
            logW(TAG, "Unsupported protocol version: $version (expected ${BinaryProtocol.PROTOCOL_VERSION})")
            return null
        }

        // 2. Validate Message Type
        val typeOrdinal = buffer.get().toInt() and 0xFF
        val payloadTypes = PayloadType.entries
        if (typeOrdinal >= payloadTypes.size) {
            logW(TAG, "Unknown message type ordinal: $typeOrdinal")
            return null
        }
        val type = payloadTypes[typeOrdinal]

        // 3. Language ID
        val langId = buffer.get()

        // 4. Flags
        val flags = buffer.get().toInt() and 0xFF

        // 5. Sequence ID
        val sequenceId = buffer.int

        // 6. Sent Timestamp
        val sentTimestampMs = buffer.long

        // 7. Validate Payload Length
        val payloadLength = buffer.short.toInt() and 0xFFFF
        if (payloadLength > BinaryProtocol.MAX_PAYLOAD_SIZE) {
            logW(TAG, "Invalid payload length: $payloadLength exceeds max ${BinaryProtocol.MAX_PAYLOAD_SIZE}")
            return null
        }

        if (buffer.remaining() < payloadLength) {
            logW(TAG, "Truncated packet: remaining ${buffer.remaining()} < payloadLength $payloadLength")
            return null
        }

        var text = ""
        var senderId = ""
        var sttLatencyMs = 0L
        var senderQueueMs = 0L
        var rtf = 0f
        var latitude: Double? = null
        var longitude: Double? = null
        var accuracy: Float? = null
        var locationTimestampMs: Long? = null
        var locationStatus: String? = null
        var languageCode = BinaryProtocol.languageIdToCode(langId) ?: ""

        try {
            // 1. Text Section
            if ((flags and BinaryProtocol.FLAG_HAS_TEXT) != 0) {
                if (buffer.remaining() < 2) {
                    logW(TAG, "Truncated text length field")
                    return null
                }
                val textLen = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() < textLen) {
                    logW(TAG, "Truncated text body: remaining ${buffer.remaining()} < textLen $textLen")
                    return null
                }
                val tBytes = ByteArray(textLen)
                buffer.get(tBytes)
                text = String(tBytes, Charsets.UTF_8)
            }

            // 2. Sender ID Section
            if ((flags and BinaryProtocol.FLAG_HAS_SENDER_ID) != 0) {
                if (buffer.remaining() < 1) {
                    logW(TAG, "Truncated senderId length field")
                    return null
                }
                val sLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < sLen) {
                    logW(TAG, "Truncated senderId body: remaining ${buffer.remaining()} < sLen $sLen")
                    return null
                }
                val sBytes = ByteArray(sLen)
                buffer.get(sBytes)
                senderId = String(sBytes, Charsets.UTF_8)
            }

            // 3. Telemetry Section
            if ((flags and BinaryProtocol.FLAG_HAS_TELEMETRY) != 0) {
                if (buffer.remaining() < 12) {
                    logW(TAG, "Truncated telemetry block: remaining ${buffer.remaining()} < 12")
                    return null
                }
                sttLatencyMs = buffer.int.toLong().coerceAtLeast(0L)
                senderQueueMs = buffer.int.toLong().coerceAtLeast(0L)
                rtf = buffer.float
            }

            // 4. Location Section
            if ((flags and BinaryProtocol.FLAG_HAS_LOCATION) != 0) {
                if (buffer.remaining() < 1) {
                    logW(TAG, "Truncated location flags")
                    return null
                }
                val locFlags = buffer.get().toInt() and 0xFF

                if ((locFlags and BinaryProtocol.LOC_FLAG_COORDINATES) != 0) {
                    if (buffer.remaining() < 16) {
                        logW(TAG, "Truncated coordinates: remaining ${buffer.remaining()} < 16")
                        return null
                    }
                    latitude = buffer.double
                    longitude = buffer.double
                }

                if ((locFlags and BinaryProtocol.LOC_FLAG_ACCURACY) != 0) {
                    if (buffer.remaining() < 4) {
                        logW(TAG, "Truncated accuracy: remaining ${buffer.remaining()} < 4")
                        return null
                    }
                    accuracy = buffer.float
                }

                if ((locFlags and BinaryProtocol.LOC_FLAG_TIMESTAMP) != 0) {
                    if (buffer.remaining() < 8) {
                        logW(TAG, "Truncated location timestamp: remaining ${buffer.remaining()} < 8")
                        return null
                    }
                    locationTimestampMs = buffer.long
                }

                if ((locFlags and BinaryProtocol.LOC_FLAG_STATUS) != 0) {
                    if (buffer.remaining() < 1) {
                        logW(TAG, "Truncated status length")
                        return null
                    }
                    val statusLen = buffer.get().toInt() and 0xFF
                    if (buffer.remaining() < statusLen) {
                        logW(TAG, "Truncated status body: remaining ${buffer.remaining()} < statusLen $statusLen")
                        return null
                    }
                    val stBytes = ByteArray(statusLen)
                    buffer.get(stBytes)
                    locationStatus = String(stBytes, Charsets.UTF_8)
                }
            }

            // 5. Custom Language Section
            if ((flags and BinaryProtocol.FLAG_CUSTOM_LANG) != 0) {
                if (buffer.remaining() < 1) {
                    logW(TAG, "Truncated custom language length")
                    return null
                }
                val lLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < lLen) {
                    logW(TAG, "Truncated custom language body: remaining ${buffer.remaining()} < lLen $lLen")
                    return null
                }
                val lBytes = ByteArray(lLen)
                buffer.get(lBytes)
                languageCode = String(lBytes, Charsets.UTF_8)
            }

        } catch (e: Exception) {
            logE(TAG, "Unexpected error decoding binary packet", e)
            return null
        }

        return Payload(
            type = type,
            text = text,
            languageCode = languageCode,
            sequenceId = sequenceId,
            sentTimestampMs = sentTimestampMs,
            senderId = senderId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            locationTimestampMs = locationTimestampMs,
            locationStatus = locationStatus,
            sttLatencyMs = sttLatencyMs,
            senderQueueMs = senderQueueMs,
            rtf = rtf,
        )
    }
}
