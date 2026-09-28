package com.itantra.transport.binary

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Built-in DEFLATE / INFLATE compression utility for UTF-8 text payloads.
 * Operates purely using JVM standard library (java.util.zip) without external dependencies.
 */
object DeflateCompressor {

    /**
     * Compresses the given [input] byte array using DEFLATE.
     * Uses [Deflater.BEST_COMPRESSION] for maximum size reduction over wireless channels.
     */
    fun compress(input: ByteArray, level: Int = Deflater.BEST_COMPRESSION): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val deflater = Deflater(level)
        return try {
            deflater.setInput(input)
            deflater.finish()

            val outStream = ByteArrayOutputStream(input.size)
            val buffer = ByteArray(256)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outStream.write(buffer, 0, count)
            }
            outStream.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Decompresses DEFLATE-compressed [compressed] bytes expecting [expectedLength] uncompressed bytes.
     *
     * Returns null if:
     * - [expectedLength] is invalid (< 0 or exceeds [BinaryProtocol.MAX_TEXT_SIZE]).
     * - The compressed stream is truncated, incomplete, or corrupted.
     * - Decompression encounters a [DataFormatException].
     */
    fun decompress(compressed: ByteArray, expectedLength: Int): ByteArray? {
        if (expectedLength == 0) return ByteArray(0)
        if (expectedLength < 0 || expectedLength > BinaryProtocol.MAX_TEXT_SIZE) {
            return null
        }
        if (compressed.isEmpty()) {
            return null
        }

        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedLength)
            var totalInflated = 0

            while (!inflater.finished() && totalInflated < expectedLength) {
                val count = inflater.inflate(output, totalInflated, expectedLength - totalInflated)
                if (count == 0 && inflater.needsInput()) break
                totalInflated += count
            }

            if (totalInflated != expectedLength || !inflater.finished()) {
                null
            } else {
                output
            }
        } catch (_: DataFormatException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
