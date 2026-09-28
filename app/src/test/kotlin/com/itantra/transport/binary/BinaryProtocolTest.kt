package com.itantra.transport.binary

import com.itantra.transport.Payload
import com.itantra.transport.PayloadType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `short English sentence encodes and decodes with exact fidelity`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "Hello team",
            languageCode = "en",
            sequenceId = 101,
            sentTimestampMs = 1774880000123L,
            senderId = "peer-abc",
            sttLatencyMs = 120L,
            senderQueueMs = 4L,
            rtf = 0.22f,
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull("Decoded payload should not be null", decoded)
        assertEquals(original.type, decoded!!.type)
        assertEquals(original.text, decoded.text)
        assertEquals(original.languageCode, decoded.languageCode)
        assertEquals(original.sequenceId, decoded.sequenceId)
        assertEquals(original.sentTimestampMs, decoded.sentTimestampMs)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.sttLatencyMs, decoded.sttLatencyMs)
        assertEquals(original.senderQueueMs, decoded.senderQueueMs)
        assertEquals(original.rtf, decoded.rtf, 0.001f)
    }

    @Test
    fun `long English sentence encodes and decodes with exact fidelity`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "Search and rescue team Bravo reporting in from sector seven. We have established radio contact with the base station and are proceeding along the western ridge toward the designated coordinates.",
            languageCode = "en",
            sequenceId = 102,
            sentTimestampMs = 1774880000456L,
            senderId = "peer-abc",
            sttLatencyMs = 350L,
            senderQueueMs = 6L,
            rtf = 0.18f,
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.text, decoded!!.text)
        assertEquals(original.languageCode, decoded.languageCode)
    }

    @Test
    fun `short Tamil sentence preserves Unicode byte-for-byte`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "வணக்கம்",
            languageCode = "ta",
            sequenceId = 201,
            sentTimestampMs = 1774880000789L,
            senderId = "peer-ta1",
            sttLatencyMs = 140L,
            senderQueueMs = 3L,
            rtf = 0.25f,
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals("வணக்கம்", decoded!!.text)
        assertEquals("ta", decoded.languageCode)
        assertEquals(original.sequenceId, decoded.sequenceId)
    }

    @Test
    fun `long Tamil sentence preserves Unicode byte-for-byte`() {
        val longTamilText = "அவசர உதவி தேவைப்படுகிறது உடனடியாக மருத்துவ குழுவை அனுப்புங்கள் நாங்கள் இரண்டாவது தளத்தில் இருக்கிறோம்"
        val original = Payload(
            type = PayloadType.SPEECH,
            text = longTamilText,
            languageCode = "ta",
            sequenceId = 202,
            sentTimestampMs = 1774880001000L,
            senderId = "peer-ta1",
            sttLatencyMs = 410L,
            senderQueueMs = 5L,
            rtf = 0.28f,
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(longTamilText, decoded!!.text)
        assertEquals("ta", decoded.languageCode)
    }

    @Test
    fun `emergency alert with GPS location encodes and decodes all fields`() {
        val original = Payload(
            type = PayloadType.ALERT,
            text = "அவசரம். எனக்கு உதவி தேவை.",
            languageCode = "ta",
            sequenceId = -1,
            sentTimestampMs = 1774880002000L,
            senderId = "peer-emerg",
            latitude = 13.0826802,
            longitude = 80.2707184,
            accuracy = 4.2f,
            locationTimestampMs = 1774880001950L,
            locationStatus = "Location available (GPS fix)",
            sttLatencyMs = 0L,
            senderQueueMs = 1L,
            rtf = 0f,
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(PayloadType.ALERT, decoded!!.type)
        assertEquals(original.text, decoded.text)
        assertEquals(original.languageCode, decoded.languageCode)
        assertEquals(original.sequenceId, decoded.sequenceId)
        assertEquals(original.sentTimestampMs, decoded.sentTimestampMs)
        assertEquals(original.senderId, decoded.senderId)
        assertEquals(original.latitude!!, decoded.latitude!!, 0.0000001)
        assertEquals(original.longitude!!, decoded.longitude!!, 0.0000001)
        assertEquals(original.accuracy!!, decoded.accuracy!!, 0.01f)
        assertEquals(original.locationTimestampMs, decoded.locationTimestampMs)
        assertEquals(original.locationStatus, decoded.locationStatus)
    }

    @Test
    fun `all 12 PayloadTypes encode and decode correctly`() {
        PayloadType.entries.forEachIndexed { index, type ->
            val payload = Payload(
                type = type,
                text = if (type == PayloadType.SPEECH || type == PayloadType.ALERT) "Test message for $type" else "",
                languageCode = if (type == PayloadType.SPEECH || type == PayloadType.ALERT) "hi" else "",
                sequenceId = index + 10,
                sentTimestampMs = 1774880000000L + index * 1000L,
                senderId = "node-$index",
            )
            val encoded = BinaryEncoder.encode(payload)
            val decoded = BinaryDecoder.decode(encoded)

            assertNotNull("Failed for type: $type", decoded)
            assertEquals("Type mismatch for $type", type, decoded!!.type)
            assertEquals(payload.sequenceId, decoded.sequenceId)
            assertEquals(payload.senderId, decoded.senderId)
            assertEquals(payload.text, decoded.text)
        }
    }

    @Test
    fun `all supported Indic languages encode and decode correctly`() {
        val sampleMap = mapOf(
            "hi" to "नमस्ते आप कैसे हैं",
            "gu" to "નમસ્તે તમે કેમ છો",
            "mr" to "नमस्कार तुम्ही कसे आहात",
            "kn" to "ನಮಸ್ಕಾರ ನೀವು ಹೇಗಿದ್ದೀರಿ",
            "ml" to "നമസ്കാരം സുഖമാണോ",
            "ta" to "வணக்கம் எப்படி இருக்கிறீர்கள்",
            "te" to "నమస్కారం మీరు ఎలా ఉన్నారు",
            "or" to "ନମସ୍କାର ଆପଣ କେମିତି ଅଛନ୍ତି",
            "bn" to "নমস্কার আপনি কেমন আছেন",
            "en" to "Hello how are you",
            "as" to "নমস্কাৰ আপুনি কেনে আছে",
            "fr" to "Bonjour comment allez-vous", // Custom language fallback
        )

        sampleMap.forEach { (lang, sampleText) ->
            val payload = Payload(
                type = PayloadType.SPEECH,
                text = sampleText,
                languageCode = lang,
                sequenceId = 50,
                sentTimestampMs = 1774880005000L,
                senderId = "sender-1",
            )
            val encoded = BinaryEncoder.encode(payload)
            val decoded = BinaryDecoder.decode(encoded)

            assertNotNull("Failed for language $lang", decoded)
            assertEquals("Language code mismatch", lang, decoded!!.languageCode)
            assertEquals("Text mismatch for $lang", sampleText, decoded.text)
        }
    }

    // ── Validation & Safe Error Handling (Requirement 11) ────────────────────

    @Test
    fun `truncated packet under header size returns null safely`() {
        val tinyBytes = ByteArray(10) { 0 }
        val decoded = BinaryDecoder.decode(tinyBytes)
        assertNull("Truncated packet under 18 bytes must return null safely", decoded)
    }

    @Test
    fun `invalid protocol version returns null safely`() {
        val payload = Payload(type = PayloadType.REQUEST_FLOOR, senderId = "peer1")
        val bytes = BinaryEncoder.encode(payload)
        bytes[0] = 0x99.toByte() // Corrupt version

        val decoded = BinaryDecoder.decode(bytes)
        assertNull("Invalid version must be rejected", decoded)
    }

    @Test
    fun `unknown message type ordinal returns null safely`() {
        val payload = Payload(type = PayloadType.REQUEST_FLOOR, senderId = "peer1")
        val bytes = BinaryEncoder.encode(payload)
        bytes[1] = 0xFE.toByte() // Unknown message type

        val decoded = BinaryDecoder.decode(bytes)
        assertNull("Unknown message type must be rejected", decoded)
    }

    @Test
    fun `truncated body returns null safely`() {
        val payload = Payload(type = PayloadType.SPEECH, text = "Testing body truncation", senderId = "peer1")
        val bytes = BinaryEncoder.encode(payload)
        // Cut the packet in half
        val truncated = bytes.copyOf(bytes.size - 10)

        val decoded = BinaryDecoder.decode(truncated)
        assertNull("Truncated body must be safely rejected without crashing", decoded)
    }

    @Test
    fun `legacy or debug JSON payload is safely decoded via fallback`() {
        val payload = Payload(
            type = PayloadType.SPEECH,
            text = "Fallback JSON text",
            languageCode = "en",
            sequenceId = 88,
            sentTimestampMs = 1774880009999L,
            senderId = "json-peer",
        )
        val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        assertTrue("JSON payload starts with {", jsonBytes[0] == '{'.code.toByte())

        val decoded = BinaryDecoder.decode(jsonBytes)
        assertNotNull("JSON fallback should successfully decode", decoded)
        assertEquals(payload.text, decoded!!.text)
        assertEquals(payload.sequenceId, decoded.sequenceId)
        assertEquals(payload.senderId, decoded.senderId)
    }

    // ── Bandwidth Measurement Comparison ─────────────────────────────────────

    @Test
    fun `measure and verify JSON vs Binary bandwidth reduction`() {
        val testCases = listOf(
            "Short English" to Payload(
                type = PayloadType.SPEECH,
                text = "Hello team",
                languageCode = "en",
                sequenceId = 1,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 120L,
                senderQueueMs = 2L,
                rtf = 0.25f,
            ),
            "Long English" to Payload(
                type = PayloadType.SPEECH,
                text = "Search and rescue team Bravo reporting in from sector seven. We have established radio contact with the base station and are proceeding along the western ridge toward the designated coordinates.",
                languageCode = "en",
                sequenceId = 2,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 350L,
                senderQueueMs = 6L,
                rtf = 0.18f,
            ),
            "Short Tamil" to Payload(
                type = PayloadType.SPEECH,
                text = "வணக்கம்",
                languageCode = "ta",
                sequenceId = 3,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 140L,
                senderQueueMs = 3L,
                rtf = 0.25f,
            ),
            "Long Tamil" to Payload(
                type = PayloadType.SPEECH,
                text = "அவசர உதவி தேவைப்படுகிறது உடனடியாக மருத்துவ குழுவை அனுப்புங்கள் நாங்கள் இரண்டாவது தளத்தில் இருக்கிறோம்",
                languageCode = "ta",
                sequenceId = 4,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 410L,
                senderQueueMs = 5L,
                rtf = 0.28f,
            ),
            "Emergency" to Payload(
                type = PayloadType.ALERT,
                text = "அவசரம். எனக்கு உதவி தேவை.",
                languageCode = "ta",
                sequenceId = -1,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                latitude = 13.0827,
                longitude = 80.2707,
                accuracy = 4.5f,
                locationTimestampMs = 1774880000000L,
                locationStatus = "Location available (GPS fix)",
                sttLatencyMs = 0L,
                senderQueueMs = 1L,
                rtf = 0f,
            ),
            "Floor Control (REQUEST_FLOOR)" to Payload(
                type = PayloadType.REQUEST_FLOOR,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
            )
        )

        println("\n==========================================================================")
        println(String.format("%-30s | %-12s | %-12s | %-10s", "Message", "JSON bytes", "Binary bytes", "Reduction %"))
        println("--------------------------------------------------------------------------")

        testCases.forEach { (name, payload) ->
            val jsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8).size
            val binaryBytes = BinaryEncoder.encode(payload).size
            val reduction = ((jsonBytes - binaryBytes).toDouble() / jsonBytes.toDouble()) * 100.0

            println(String.format("%-30s | %-12d | %-12d | %-9.1f%%", name, jsonBytes, binaryBytes, reduction))

            assertTrue("Binary format must be smaller than JSON for $name", binaryBytes < jsonBytes)
        }
        println("==========================================================================\n")
    }
}
