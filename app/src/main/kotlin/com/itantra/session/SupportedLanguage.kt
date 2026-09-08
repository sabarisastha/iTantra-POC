package com.itantra.session

/**
 * Languages supported by the STT/TTS pipeline.
 *
 * Confirmed SIH 2026 problem-statement language list (10 languages, in spec order).
 *
 * ⚠️  English IS in the spec.  Punjabi is NOT.  Do not confuse the two.
 */
enum class SupportedLanguage(val code: String, val displayName: String) {
    HINDI    ("hi", "Hindi"),
    GUJARATI ("gu", "Gujarati"),
    MARATHI  ("mr", "Marathi"),
    KANNADA  ("kn", "Kannada"),
    MALAYALAM("ml", "Malayalam"),
    TAMIL    ("ta", "Tamil"),
    TELUGU   ("te", "Telugu"),
    ODIA     ("or", "Odia"),
    BENGALI  ("bn", "Bengali"),
    ENGLISH  ("en", "English"),   // ← explicitly in SIH spec; NOT replaced by Punjabi
}
