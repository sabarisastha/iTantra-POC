package com.itantra.model

const val HF_STT_BASE = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main"
const val HF_TTS_BASE = "https://huggingface.co/csukuangfj/vits-mms/resolve/main"
const val RASA13_TTS_BASE = "https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/main"

data class LanguageConfig(
    val code: String,
    val displayName: String,
    val sttModelUrl: String,
    val sttTokensUrl: String,
    val ttsModelUrl: String? = null,
    val ttsTokensUrl: String? = null,
    val ttsModelKey: String? = null,
    val speakerId: Int = 0,
    val available: Boolean = true
)

val RASA13_MODEL_URL = "$RASA13_TTS_BASE/model.onnx"
val RASA13_TOKENS_URL = "$RASA13_TTS_BASE/tokens.txt"

val SUPPORTED_LANGUAGES = listOf(
    LanguageConfig(
        code = "hi",
        displayName = "Hindi",
        sttModelUrl = "$HF_STT_BASE/hi/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 0
    ),
    LanguageConfig(
        code = "bn",
        displayName = "Bengali",
        sttModelUrl = "$HF_STT_BASE/bn/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 1
    ),
    LanguageConfig(
        code = "gu",
        displayName = "Gujarati",
        sttModelUrl = "$HF_STT_BASE/gu/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 2
    ),
    LanguageConfig(
        code = "kn",
        displayName = "Kannada",
        sttModelUrl = "$HF_STT_BASE/kn/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 3
    ),
    LanguageConfig(
        code = "ml",
        displayName = "Malayalam",
        sttModelUrl = "$HF_STT_BASE/ml/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 4
    ),
    LanguageConfig(
        code = "mr",
        displayName = "Marathi",
        sttModelUrl = "$HF_STT_BASE/mr/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 5
    ),
    LanguageConfig(
        code = "or",
        displayName = "Odia",
        sttModelUrl = "$HF_STT_BASE/or/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 6,
        available = true
    ),
    LanguageConfig(
        code = "ta",
        displayName = "Tamil",
        sttModelUrl = "$HF_STT_BASE/ta/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 8
    ),
    LanguageConfig(
        code = "te",
        displayName = "Telugu",
        sttModelUrl = "$HF_STT_BASE/te/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 9
    ),
    LanguageConfig(
        code = "as",
        displayName = "Assamese",
        sttModelUrl = "$HF_STT_BASE/as/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = RASA13_MODEL_URL,
        ttsTokensUrl = RASA13_TOKENS_URL,
        ttsModelKey = "rasa13",
        speakerId = 10
    ),
    LanguageConfig(
        code = "en",
        displayName = "English",
        sttModelUrl = "$HF_STT_BASE/en/model.int8.onnx",
        sttTokensUrl = "$HF_STT_BASE/tokens.txt",
        ttsModelUrl = "https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/model.onnx",
        ttsTokensUrl = "https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/tokens.txt",
        ttsModelKey = "en",
        speakerId = 0
    )
)

fun getLanguageConfig(code: String): LanguageConfig? =
    SUPPORTED_LANGUAGES.find { it.code == code }
