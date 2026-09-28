package com.itantra.transport.binary

import com.itantra.transport.Payload
import com.itantra.transport.PayloadType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Helper to compute what the binary packet size was before compression (Feature 1 baseline).
     */
    private fun encodeUncompressedBinary(payload: Payload): ByteArray {
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

        val bodyStream = ByteArrayOutputStream()
        val bodyOut = DataOutputStream(bodyStream)

        if (textBytes != null && textBytes.isNotEmpty()) {
            bodyOut.writeShort(textBytes.size)
            bodyOut.write(textBytes)
        }

        if (senderBytes != null && senderBytes.isNotEmpty()) {
            bodyOut.writeByte(senderBytes.size)
            bodyOut.write(senderBytes)
        }

        if (hasTelemetry) {
            bodyOut.writeInt(payload.sttLatencyMs.toInt())
            bodyOut.writeInt(payload.senderQueueMs.toInt())
            bodyOut.writeFloat(payload.rtf)
        }

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
                bodyOut.writeByte(statusBytes.size)
                bodyOut.write(statusBytes)
            }
        }

        if (isCustomLang) {
            val customLangBytes = payload.languageCode.toByteArray(Charsets.UTF_8)
            bodyOut.writeByte(customLangBytes.size)
            bodyOut.write(customLangBytes)
        }

        bodyOut.flush()
        val bodyBytes = bodyStream.toByteArray()

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

    // ── 1. Short English (should remain uncompressed) ──────────────────────────

    @Test
    fun `short English sentence encodes and decodes with exact fidelity without compression`() {
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
        val flags = encoded[3].toInt() and 0xFF
        assertEquals("Compression flag must NOT be set for short text", 0, flags and BinaryProtocol.FLAG_TEXT_COMPRESSED)

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

    // ── 2. Long English (should be adaptively compressed) ─────────────────────

    @Test
    fun `long English sentence adaptively compresses and decodes with exact fidelity`() {
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
        val flags = encoded[3].toInt() and 0xFF
        assertTrue("Compression flag MUST be set for long English text", (flags and BinaryProtocol.FLAG_TEXT_COMPRESSED) != 0)

        val decoded = BinaryDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original.text, decoded!!.text)
        assertEquals(original.languageCode, decoded.languageCode)
        assertArrayEquals(original.text.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 3. Short Tamil (should remain uncompressed) ───────────────────────────

    @Test
    fun `short Tamil sentence remains uncompressed and preserves Unicode byte-for-byte`() {
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
        val flags = encoded[3].toInt() and 0xFF
        assertEquals("Compression flag must NOT be set for short Tamil", 0, flags and BinaryProtocol.FLAG_TEXT_COMPRESSED)

        val decoded = BinaryDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals("வணக்கம்", decoded!!.text)
        assertEquals("ta", decoded.languageCode)
        assertEquals(original.sequenceId, decoded.sequenceId)
        assertArrayEquals(original.text.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 4. Long Tamil (should be adaptively compressed) ───────────────────────

    @Test
    fun `long Tamil sentence adaptively compresses and preserves Unicode byte-for-byte`() {
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
        val flags = encoded[3].toInt() and 0xFF
        assertTrue("Compression flag MUST be set for long Tamil text", (flags and BinaryProtocol.FLAG_TEXT_COMPRESSED) != 0)

        val decoded = BinaryDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(longTamilText, decoded!!.text)
        assertArrayEquals(longTamilText.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 5. Short Hindi (should remain uncompressed) ───────────────────────────

    @Test
    fun `short Hindi sentence remains uncompressed and preserves Unicode byte-for-byte`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "नमस्ते",
            languageCode = "hi",
            sequenceId = 301,
            sentTimestampMs = 1774880001200L,
            senderId = "peer-hi1",
            sttLatencyMs = 110L,
            senderQueueMs = 2L,
            rtf = 0.20f,
        )

        val encoded = BinaryEncoder.encode(original)
        val flags = encoded[3].toInt() and 0xFF
        assertEquals("Compression flag must NOT be set for short Hindi", 0, flags and BinaryProtocol.FLAG_TEXT_COMPRESSED)

        val decoded = BinaryDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals("नमस्ते", decoded!!.text)
        assertEquals("hi", decoded.languageCode)
        assertArrayEquals(original.text.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 6. Long Hindi (should be adaptively compressed) ───────────────────────

    @Test
    fun `long Hindi sentence adaptively compresses and preserves Unicode byte-for-byte`() {
        val longHindiText = "खोज और बचाव दल सेक्टर सात से रिपोर्ट कर रहा है। हमने बेस स्टेशन के साथ रेडियो संपर्क स्थापित कर लिया है और निर्दिष्ट निर्देशांक की ओर बढ़ रहे हैं।"
        val original = Payload(
            type = PayloadType.SPEECH,
            text = longHindiText,
            languageCode = "hi",
            sequenceId = 302,
            sentTimestampMs = 1774880001500L,
            senderId = "peer-hi1",
            sttLatencyMs = 380L,
            senderQueueMs = 5L,
            rtf = 0.21f,
        )

        val encoded = BinaryEncoder.encode(original)
        val flags = encoded[3].toInt() and 0xFF
        assertTrue("Compression flag MUST be set for long Hindi text", (flags and BinaryProtocol.FLAG_TEXT_COMPRESSED) != 0)

        val decoded = BinaryDecoder.decode(encoded)
        assertNotNull(decoded)
        assertEquals(longHindiText, decoded!!.text)
        assertArrayEquals(longHindiText.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 7. Mixed Unicode Text ────────────────────────────────────────────────

    @Test
    fun `mixed Unicode text with Tamil Hindi English numbers and symbols preserves byte-for-byte`() {
        val mixedText = "Team Alpha: அவசரம்! Urgent report - 45 people evacuated. सब सुरक्षित हैं. GPS: 13.0827° N."
        val original = Payload(
            type = PayloadType.SPEECH,
            text = mixedText,
            languageCode = "en",
            sequenceId = 401,
            sentTimestampMs = 1774880002000L,
            senderId = "mixed-node",
        )

        val encoded = BinaryEncoder.encode(original)
        val decoded = BinaryDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(mixedText, decoded!!.text)
        assertArrayEquals(mixedText.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 8. Emergency Alert with GPS ──────────────────────────────────────────

    @Test
    fun `emergency alert message with location metadata preserves exact coordinates and text`() {
        val original = Payload(
            type = PayloadType.ALERT,
            text = "அவசரம். எனக்கு உடனடி உதவி தேவை. நாங்கள் இரண்டாவது தளத்தில் சிக்கியுள்ளோம்.",
            languageCode = "ta",
            sequenceId = -1,
            sentTimestampMs = 1774880002500L,
            senderId = "rescuer-9",
            latitude = 12.9715987,
            longitude = 77.5945627,
            accuracy = 3.2f,
            locationTimestampMs = 1774880002400L,
            locationStatus = "Location available (GPS fix, 8 satellites)",
            sttLatencyMs = 0L,
            senderQueueMs = 1L,
            rtf = 0.0f,
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
        assertEquals(original.latitude!!, decoded.latitude!!, 0.000001)
        assertEquals(original.longitude!!, decoded.longitude!!, 0.000001)
        assertEquals(original.accuracy!!, decoded.accuracy!!, 0.001f)
        assertEquals(original.locationTimestampMs, decoded.locationTimestampMs)
        assertEquals(original.locationStatus, decoded.locationStatus)
        assertArrayEquals(original.text.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
    }

    // ── 9. Adaptive Decision: Uncompressed when compression increases size ───

    @Test
    fun `payload that becomes larger after compression remains uncompressed`() {
        val shortTexts = listOf("OK", "Roger", "No", "வணக்கம்", "नमस्ते", "ஆம்")
        shortTexts.forEach { text ->
            val payload = Payload(
                type = PayloadType.SPEECH,
                text = text,
                sequenceId = 501,
                senderId = "peer",
            )
            val encoded = BinaryEncoder.encode(payload)
            val flags = encoded[3].toInt() and 0xFF
            val isCompressed = (flags and BinaryProtocol.FLAG_TEXT_COMPRESSED) != 0
            assertFalse("Short text '$text' should NOT be compressed", isCompressed)

            val decoded = BinaryDecoder.decode(encoded)
            assertNotNull(decoded)
            assertEquals(text, decoded!!.text)
        }
    }

    // ── 10. Malformed Compressed Payload Handling ────────────────────────────

    @Test
    fun `malformed compressed payload is safely rejected without crashing`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "Search and rescue team Bravo reporting in from sector seven. We have established radio contact with the base station and are proceeding along the western ridge toward the designated coordinates.",
            sequenceId = 601,
            senderId = "peer",
        )
        val validEncoded = BinaryEncoder.encode(original)
        val flags = validEncoded[3].toInt() and 0xFF
        assertTrue("Pre-condition: must be compressed", (flags and BinaryProtocol.FLAG_TEXT_COMPRESSED) != 0)

        // Corrupt compressed payload bytes in the body (after 18B header + 2B compLen + 2B uncompLen = offset 22)
        val corrupted = validEncoded.clone()
        for (i in 22 until 22 + 10.coerceAtMost(corrupted.size - 22)) {
            corrupted[i] = (corrupted[i].toInt() xor 0xFF).toByte()
        }

        val decoded = BinaryDecoder.decode(corrupted)
        assertNull("Corrupted compressed payload must be safely rejected returning null", decoded)
    }

    // ── 11. Truncated Compressed Payload Handling ────────────────────────────

    @Test
    fun `truncated compressed payload is safely rejected without crashing`() {
        val original = Payload(
            type = PayloadType.SPEECH,
            text = "Search and rescue team Bravo reporting in from sector seven. We have established radio contact with the base station and are proceeding along the western ridge toward the designated coordinates.",
            sequenceId = 701,
            senderId = "peer",
        )
        val validEncoded = BinaryEncoder.encode(original)

        // Truncate compressed stream by 15 bytes
        val truncated = validEncoded.copyOf(validEncoded.size - 15)
        val decoded = BinaryDecoder.decode(truncated)
        assertNull("Truncated compressed payload must be safely rejected returning null", decoded)
    }

    // ── 12. Multilingual Byte-for-Byte Preservation across 10 Languages ──────

    @Test
    fun `all 10 Indic and English languages preserve exact byte-for-byte Unicode fidelity`() {
        val languages = mapOf(
            "en" to "The quick brown fox jumps over the lazy dog. Search and rescue operations are fully active across sectors.",
            "ta" to "அவசர உதவி தேவைப்படுகிறது உடனடியாக மருத்துவ குழுவை அனுப்புங்கள் நாங்கள் இரண்டாவது தளத்தில் இருக்கிறோம்.",
            "hi" to "खोज और बचाव दल सेक्टर सात से रिपोर्ट कर रहा है। हमने बेस स्टेशन के साथ रेडियो संपर्क स्थापित कर लिया है।",
            "te" to "అత్యవసర సహాయం అవసరం వెంటనే వైద్య బృందాన్ని పంపండి మేము రెండవ అంతస్తులో ఉన్నాము.",
            "kn" to "ತುರ್ತು ಸಹಾಯ ಬೇಕಾಗಿದೆ ತಕ್ಷಣ ವೈದ್ಯಕೀಯ ತಂಡವನ್ನು ಕಳುಹಿಸಿ ನಾವು ಎರಡನೇ ಮಹಡಿಯಲ್ಲಿದ್ದೇವೆ.",
            "ml" to "അടിയന്തര സഹായം ആവശ്യമാണ് ഉടൻ മെഡിക്കൽ സംഘത്തെ അയക്കുക ഞങ്ങൾ രണ്ടാമത്തെ നിലയിലാണ്.",
            "mr" to "तातडीच्या मदतीची गरज आहे लगेच वैद्यकीय पथक पाठवा आम्ही दुसऱ्या मजल्यावर अडकलो आहोत.",
            "gu" to "તાત્કાલિક મદદની જરૂર છે તરત જ તબીબી ટીમને મોકલો અમે બીજા માળે અટવાયેલા છીએ.",
            "bn" to "জরুরী সাহায্য প্রয়োজন অবিলম্বে চিকিৎসা দল পাঠান আমরা দ্বিতীয় তলায় আটকা পড়েছি।",
            "or" to "ଜରୁରୀ ସାହାଯ୍ୟ ଆବଶ୍ୟକ ତୁରନ୍ତ ଡାକ୍ତରୀ ଦଳ ପଠାନ୍ତୁ ଆମେ ଦ୍ୱିତୀୟ ମହଲାରେ ଫସି ରହିଛୁ।"
        )

        languages.forEach { (lang, text) ->
            val payload = Payload(
                type = PayloadType.SPEECH,
                text = text,
                languageCode = lang,
                sequenceId = 800,
                senderId = "lang-tester",
            )

            val encoded = BinaryEncoder.encode(payload)
            val decoded = BinaryDecoder.decode(encoded)

            assertNotNull("Failed to decode language $lang", decoded)
            assertEquals("Language code mismatch for $lang", lang, decoded!!.languageCode)
            assertEquals("Text mismatch for $lang", text, decoded.text)
            assertArrayEquals("UTF-8 byte mismatch for $lang", text.toByteArray(Charsets.UTF_8), decoded.text.toByteArray(Charsets.UTF_8))
        }
    }

    // ── 13. All 12 PayloadTypes Functional Check ─────────────────────────────

    @Test
    fun `all 12 PayloadTypes continue to encode and decode correctly`() {
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

    // ── 14. Validation Error Branches (Under header size, unknown type) ──────

    @Test
    fun `corrupted header fields and under-header sizes reject safely`() {
        val underHeader = ByteArray(10) { 0 }
        assertNull(BinaryDecoder.decode(underHeader))

        val payload = Payload(type = PayloadType.REQUEST_FLOOR, senderId = "peer1")
        val bytes = BinaryEncoder.encode(payload)
        bytes[0] = 0x99.toByte() // Invalid version
        assertNull(BinaryDecoder.decode(bytes))

        val bytes2 = BinaryEncoder.encode(payload)
        bytes2[1] = 0xFE.toByte() // Unknown message type
        assertNull(BinaryDecoder.decode(bytes2))
    }

    // ── 15. Real Measured Benchmark Table (No invented numbers!) ──────────────

    @Test
    fun `measure and verify Binary vs Binary+Adaptive Compression bandwidth reduction`() {
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
            "Short Hindi" to Payload(
                type = PayloadType.SPEECH,
                text = "नमस्ते",
                languageCode = "hi",
                sequenceId = 5,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 110L,
                senderQueueMs = 2L,
                rtf = 0.20f,
            ),
            "Long Hindi" to Payload(
                type = PayloadType.SPEECH,
                text = "खोज और बचाव दल सेक्टर सात से रिपोर्ट कर रहा है। हमने बेस स्टेशन के साथ रेडियो संपर्क स्थापित कर लिया है।",
                languageCode = "hi",
                sequenceId = 6,
                sentTimestampMs = 1774880000000L,
                senderId = "a1b2c3d4",
                sttLatencyMs = 380L,
                senderQueueMs = 5L,
                rtf = 0.21f,
            ),
            "Emergency" to Payload(
                type = PayloadType.ALERT,
                text = "அவசரம். எனக்கு உடனடி உதவி தேவை. நாங்கள் இரண்டாவது தளத்தில் சிக்கியுள்ளோம்.",
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
            )
        )

        println("\n==========================================================================")
        println(String.format("%-25s | %-12s | %-17s | %-12s", "Message", "Binary", "Binary+Adaptive", "Reduction %"))
        println("--------------------------------------------------------------------------")

        testCases.forEach { (name, payload) ->
            val uncompressedBinaryBytes = encodeUncompressedBinary(payload).size
            val adaptiveBinaryBytes = BinaryEncoder.encode(payload).size
            val diff = uncompressedBinaryBytes - adaptiveBinaryBytes
            val reductionPercent = if (diff > 0) {
                (diff.toDouble() / uncompressedBinaryBytes.toDouble()) * 100.0
            } else {
                0.0
            }

            println(String.format("%-25s | %4d B        | %4d B             | %5.1f%%",
                name, uncompressedBinaryBytes, adaptiveBinaryBytes, reductionPercent))

            assertTrue("Adaptive binary must never be larger than uncompressed binary",
                adaptiveBinaryBytes <= uncompressedBinaryBytes)
        }
        println("==========================================================================\n")
    }
}
