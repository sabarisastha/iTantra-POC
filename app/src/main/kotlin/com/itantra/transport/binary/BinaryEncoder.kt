package com.itantra.transport.binary

import com.itantra.transport.Payload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compact binary encoder for [Payload] messages.
 * Encodes application messages into a small fixed-header packet with variable-length UTF-8 data.
 */
object BinaryEncoder {

    fun encode(payload: Payload): ByteArray {
        val langId = BinaryProtocol.languageCodeToId(payload.languageCode)
        val isCustomLang = (langId == BinaryProtocol.LANG_ID_CUSTOM && payload.languageCode.isNotBlank())

        var flags = 0
        val textBytes = if (payload.text.isNotEmpty()) payload.text.toByteArray(Charsets.UTF_8) else null
        if (textBytes != null && textBytes.isNotEmpty()) {
            flags = flags or BinaryProtocol.FLAG_HAS_TEXT
        }

        val senderBytes = if (payload.senderId.isNotEmpty()) payload.senderId.toByteArray(Charsets.UTF_8) else null
        if (senderBytes != null && senderBytes.isNotEmpty()) {
            flags = flags or BinaryProtocol.FLAG_HAS_SENDER_ID
        }

        val hasLocation = (payload.latitude != null && payload.longitude != null) ||
                payload.accuracy != null ||
                payload.locationTimestampMs != null ||
                !payload.locationStatus.isNullOrEmpty()
        if (hasLocation) {
            flags = flags or BinaryProtocol.FLAG_HAS_LOCATION
        }

        val hasTelemetry = payload.sttLatencyMs > 0L || payload.senderQueueMs > 0L || payload.rtf > 0f
        if (hasTelemetry) {
            flags = flags or BinaryProtocol.FLAG_HAS_TELEMETRY
        }

        if (isCustomLang) {
            flags = flags or BinaryProtocol.FLAG_CUSTOM_LANG
        }

        // Build variable-length body
        val bodyStream = ByteArrayOutputStream()
        val bodyOut = DataOutputStream(bodyStream)

        // 1. Text Section (if present)
        if (textBytes != null && textBytes.isNotEmpty()) {
            require(textBytes.size <= 0xFFFF) { "Text length exceeds 65535 bytes" }
            bodyOut.writeShort(textBytes.size)
            bodyOut.write(textBytes)
        }

        // 2. Sender ID Section (if present)
        if (senderBytes != null && senderBytes.isNotEmpty()) {
            require(senderBytes.size <= 0xFF) { "Sender ID length exceeds 255 bytes" }
            bodyOut.writeByte(senderBytes.size)
            bodyOut.write(senderBytes)
        }

        // 3. Telemetry Section (if present)
        if (hasTelemetry) {
            bodyOut.writeInt(payload.sttLatencyMs.toInt())
            bodyOut.writeInt(payload.senderQueueMs.toInt())
            bodyOut.writeFloat(payload.rtf)
        }

        // 4. Location Section (if present)
        if (hasLocation) {
            var locFlags = 0
            if (payload.latitude != null && payload.longitude != null) {
                locFlags = locFlags or BinaryProtocol.LOC_FLAG_COORDINATES
            }
            if (payload.accuracy != null) {
                locFlags = locFlags or BinaryProtocol.LOC_FLAG_ACCURACY
            }
            if (payload.locationTimestampMs != null) {
                locFlags = locFlags or BinaryProtocol.LOC_FLAG_TIMESTAMP
            }
            val statusBytes = if (!payload.locationStatus.isNullOrEmpty()) {
                payload.locationStatus.toByteArray(Charsets.UTF_8)
            } else null
            if (statusBytes != null && statusBytes.isNotEmpty()) {
                locFlags = locFlags or BinaryProtocol.LOC_FLAG_STATUS
            }

            bodyOut.writeByte(locFlags)
            if (payload.latitude != null && payload.longitude != null) {
                bodyOut.writeDouble(payload.latitude)
                bodyOut.writeDouble(payload.longitude)
            }
            if (payload.accuracy != null) {
                bodyOut.writeFloat(payload.accuracy)
            }
            if (payload.locationTimestampMs != null) {
                bodyOut.writeLong(payload.locationTimestampMs)
            }
            if (statusBytes != null && statusBytes.isNotEmpty()) {
                require(statusBytes.size <= 0xFF) { "Location status length exceeds 255 bytes" }
                bodyOut.writeByte(statusBytes.size)
                bodyOut.write(statusBytes)
            }
        }

        // 5. Custom Language Section (if present)
        if (isCustomLang) {
            val customLangBytes = payload.languageCode.toByteArray(Charsets.UTF_8)
            require(customLangBytes.size <= 0xFF) { "Language code exceeds 255 bytes" }
            bodyOut.writeByte(customLangBytes.size)
            bodyOut.write(customLangBytes)
        }

        bodyOut.flush()
        val bodyBytes = bodyStream.toByteArray()
        require(bodyBytes.size <= 0xFFFF) { "Binary body exceeds maximum size of 65535 bytes" }

        // Construct complete packet: Fixed Header (18 bytes) + Variable Body
        val packet = ByteBuffer.allocate(BinaryProtocol.HEADER_SIZE + bodyBytes.size)
            .order(ByteOrder.BIG_ENDIAN)

        packet.put(BinaryProtocol.PROTOCOL_VERSION)
        packet.put(payload.type.ordinal.toByte())
        packet.put(langId)
        packet.put(flags.toByte())
        packet.putInt(payload.sequenceId)
        packet.putLong(payload.sentTimestampMs)
        packet.putShort(bodyBytes.size.toShort())
        packet.put(bodyBytes)

        return packet.array()
    }
}
