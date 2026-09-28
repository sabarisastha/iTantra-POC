package com.itantra.transport.binary

import com.itantra.transport.PayloadType

/**
 * Specification and constants for the iTantra compact binary protocol.
 *
 * Fixed Header Layout (18 bytes, Big Endian):
 * ┌────────┬─────────────┬────────────┬────────┬──────────────┬──────────────────┬───────────────┐
 * │ Offset │ Field       │ Type       │ Size   │ Description                                      │
 * ├────────┼─────────────┼────────────┼────────┼──────────────┼──────────────────┼───────────────┤
 * │ 0      │ Version     │ UInt8      │ 1 byte │ Protocol version (0x01)                          │
 * │ 1      │ Type        │ UInt8      │ 1 byte │ Ordinal of [PayloadType] (0..11)                 │
 * │ 2      │ Language ID │ UInt8      │ 1 byte │ Compact language identifier                      │
 * │ 3      │ Flags       │ UInt8      │ 1 byte │ Bitmask for present variable-length sections     │
 * │ 4..7   │ Sequence ID │ Int32      │ 4 byte │ Transaction sequence number                      │
 * │ 8..15  │ Sent Time   │ Int64      │ 8 byte │ Sender epoch timestamp in ms                     │
 * │ 16..17 │ Body Length │ UInt16     │ 2 byte │ Length of variable payload following header      │
 * └────────┴─────────────┴────────────┴────────┴──────────────┴──────────────────┴───────────────┘
 */
object BinaryProtocol {
    const val PROTOCOL_VERSION: Byte = 0x01
    const val HEADER_SIZE: Int = 18
    const val MAX_PAYLOAD_SIZE: Int = 512 * 1024 // 512 KB socket frame limit
    const val MAX_TEXT_SIZE: Int = 65535         // 64 KB text length limit

    // Header Flags (Bitmask)
    const val FLAG_HAS_TEXT: Int        = 1 shl 0 // 0x01: Text section present
    const val FLAG_HAS_SENDER_ID: Int   = 1 shl 1 // 0x02: Sender ID present
    const val FLAG_HAS_LOCATION: Int    = 1 shl 2 // 0x04: Location section present
    const val FLAG_HAS_TELEMETRY: Int   = 1 shl 3 // 0x08: Telemetry section present
    const val FLAG_CUSTOM_LANG: Int     = 1 shl 4 // 0x10: Custom language string present
    const val FLAG_TEXT_COMPRESSED: Int = 1 shl 5 // 0x20: Text section is DEFLATE-compressed

    // Location Sub-Flags (Bitmask)
    const val LOC_FLAG_COORDINATES: Int = 1 shl 0 // 0x01: Latitude & Longitude (16 bytes)
    const val LOC_FLAG_ACCURACY: Int    = 1 shl 1 // 0x02: Accuracy float (4 bytes)
    const val LOC_FLAG_TIMESTAMP: Int   = 1 shl 2 // 0x04: Location timestamp ms (8 bytes)
    const val LOC_FLAG_STATUS: Int      = 1 shl 3 // 0x08: Location status string

    // Language ID Mappings
    const val LANG_ID_NONE: Byte   = 0x00
    const val LANG_ID_HI: Byte     = 0x01
    const val LANG_ID_GU: Byte     = 0x02
    const val LANG_ID_MR: Byte     = 0x03
    const val LANG_ID_KN: Byte     = 0x04
    const val LANG_ID_ML: Byte     = 0x05
    const val LANG_ID_TA: Byte     = 0x06
    const val LANG_ID_TE: Byte     = 0x07
    const val LANG_ID_OR: Byte     = 0x08
    const val LANG_ID_BN: Byte     = 0x09
    const val LANG_ID_EN: Byte     = 0x0A
    const val LANG_ID_AS: Byte     = 0x0B
    const val LANG_ID_CUSTOM: Byte = 0xFF.toByte()

    fun languageCodeToId(code: String): Byte = when (code.trim().lowercase()) {
        "" -> LANG_ID_NONE
        "hi" -> LANG_ID_HI
        "gu" -> LANG_ID_GU
        "mr" -> LANG_ID_MR
        "kn" -> LANG_ID_KN
        "ml" -> LANG_ID_ML
        "ta" -> LANG_ID_TA
        "te" -> LANG_ID_TE
        "or" -> LANG_ID_OR
        "bn" -> LANG_ID_BN
        "en" -> LANG_ID_EN
        "as" -> LANG_ID_AS
        else -> LANG_ID_CUSTOM
    }

    fun languageIdToCode(id: Byte): String? = when (id) {
        LANG_ID_NONE -> ""
        LANG_ID_HI -> "hi"
        LANG_ID_GU -> "gu"
        LANG_ID_MR -> "mr"
        LANG_ID_KN -> "kn"
        LANG_ID_ML -> "ml"
        LANG_ID_TA -> "ta"
        LANG_ID_TE -> "te"
        LANG_ID_OR -> "or"
        LANG_ID_BN -> "bn"
        LANG_ID_EN -> "en"
        LANG_ID_AS -> "as"
        LANG_ID_CUSTOM -> null // Custom string follows in payload body
        else -> null
    }
}
