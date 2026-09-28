package com.itantra.session

/**
 * Languages supported by the STT/TTS pipeline.
 *
 * Confirmed SIH 2026 problem-statement language list (10 languages, in spec order).
 *
 * ⚠️  English IS in the spec.  Punjabi is NOT.  Do not confuse the two.
 */
enum class SupportedLanguage(val code: String, val displayName: String, val emergencyPhrase: String) {
    HINDI    ("hi", "Hindi",     "आपातकाल। मुझे सहायता की आवश्यकता है।"),
    GUJARATI ("gu", "Gujarati",  "કટોકટી. મને મદદની જરૂર છે."),
    MARATHI  ("mr", "Marathi",   "आणीबाणी. मला मदतीची आवश्यकता आहे."),
    KANNADA  ("kn", "Kannada",   "ತುರ್ತು. ನನಗೆ ಸಹಾಯ ಬೇಕು."),
    MALAYALAM("ml", "Malayalam", "അടിയന്തരാവസ്ഥ. എനിക്ക് സഹായം വേണം."),
    TAMIL    ("ta", "Tamil",     "அவசரம். எனக்கு உதவி தேவை."),
    TELUGU   ("te", "Telugu",    "అత్యవసరం. నాకు సహాయం కావాలి."),
    ODIA     ("or", "Odia",      "ଜରୁରୀକାଳୀନ ପରିସ୍ଥିତି। ମୋତେ ସାହାଯ୍ୟ ଦରକାର।"),
    BENGALI  ("bn", "Bengali",   "জরুরী অবস্থা। আমার সাহায্য প্রয়োজন।"),
    ENGLISH  ("en", "English",   "Emergency. I need assistance."),   // ← explicitly in SIH spec; NOT replaced by Punjabi
}
