package com.itantra.model

const val HF_STT_BASE = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main"
const val HF_TTS_BASE = "https://huggingface.co/csukuangfj/vits-mms/resolve/main"

data class LanguageConfig(
    val code: String,
    val displayName: String,
    val sttModelUrl: String,
    val sttTokensUrl: String,
    val ttsModelUrl: String? = null,
    val ttsTokensUrl: String? = null,
    val available: Boolean = true
)

val SUPPORTED_LANGUAGES = listOf(
    LanguageConfig(
        code = "hi",
        displayName = "Hindi",
        sttModelUrl = "$HF_STT_BASE/hi/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = "https://huggingface.co/csukuangfj/vits-piper-hi_IN-pratham-medium/resolve/main/hi_IN-pratham-medium.onnx",
        ttsTokensUrl = "https://huggingface.co/csukuangfj/vits-piper-hi_IN-pratham-medium/resolve/main/tokens.txt"
    ),
    LanguageConfig(
        code = "bn",
        displayName = "Bengali",
        sttModelUrl = "$HF_STT_BASE/bn/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "ta",
        displayName = "Tamil",
        sttModelUrl = "$HF_STT_BASE/ta/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "te",
        displayName = "Telugu",
        sttModelUrl = "$HF_STT_BASE/te/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "mr",
        displayName = "Marathi",
        sttModelUrl = "$HF_STT_BASE/mr/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "gu",
        displayName = "Gujarati",
        sttModelUrl = "$HF_STT_BASE/gu/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "kn",
        displayName = "Kannada",
        sttModelUrl = "$HF_STT_BASE/kn/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "ml",
        displayName = "Malayalam",
        sttModelUrl = "$HF_STT_BASE/ml/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "or",
        displayName = "Odia (Coming Soon)",
        sttModelUrl = "",
        sttTokensUrl = "",
        available = false
    ),
    LanguageConfig(
        code = "as",
        displayName = "Assamese",
        sttModelUrl = "$HF_STT_BASE/as/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = null,
        ttsTokensUrl = null
    ),
    LanguageConfig(
        code = "en",
        displayName = "English",
        sttModelUrl = "$HF_STT_BASE/en/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = "https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/model.onnx",
        ttsTokensUrl = "https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/tokens.txt"
    )
)

fun getLanguageConfig(code: String): LanguageConfig? =
    SUPPORTED_LANGUAGES.find { it.code == code }
