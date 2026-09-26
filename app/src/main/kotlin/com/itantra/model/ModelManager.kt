package com.itantra.model

import android.content.Context
import android.util.Log
import com.itantra.download.ModelDownloader
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ModelState tracks what lifecycle state each model is in.
 *
 * UNLOADED    → model is not in memory.
 * CHECKING    → verifying disk / download.
 * DOWNLOADING → downloading from remote.
 * LOADING     → being instantiated from disk into native heap.
 * READY       → loaded and available for inference.
 * RUNNING     → actively executing an inference call; MUST NOT be unloaded.
 * ERROR       → failed to load; needs retry.
 */
sealed class ModelState {
    object Idle                                : ModelState()   // alias for UNLOADED
    object Checking                            : ModelState()
    data class Downloading(val progress: Int) : ModelState()
    object Loading                             : ModelState()
    object Ready                               : ModelState()
    object Running                             : ModelState()   // actively inferencing — do NOT unload
    data class Error(val message: String)      : ModelState()
}

private const val TAG = "ITANTRA-MODEL"

class ModelManager(private val context: Context) {

    var numThreads: Int = 2
    val downloader = ModelDownloader(context)

    // ── Coroutine-safe Mutex locks (replaces Java synchronized) ─────────────
    private val sttMutex = Mutex()
    private val ttsMutex = Mutex()

    // ── Active inference reference counters ──────────────────────────────────
    @Volatile private var sttInferenceCount = 0
    @Volatile private var ttsInferenceCount = 0

    // ── STT Model instances ──────────────────────────────────────────────────
    @Volatile private var currentSttRecognizer: OfflineRecognizer? = null
    @Volatile var currentSttLang: String? = null
        private set

    // ── TTS Model instances ──────────────────────────────────────────────────
    @Volatile private var currentTtsEngine: OfflineTts? = null
    @Volatile var currentTtsLang: String? = null
        private set
    @Volatile var currentTtsModelKey: String? = null
        private set

    // ── Observable state ─────────────────────────────────────────────────────
    private val _sttState = MutableStateFlow<ModelState>(ModelState.Idle)
    val sttState: StateFlow<ModelState> = _sttState

    private val _ttsState = MutableStateFlow<ModelState>(ModelState.Idle)
    val ttsState: StateFlow<ModelState> = _ttsState

    fun init() {
        Log.d(TAG, "ModelManager.init()")
        downloader.cleanupStaleDownloads()
        downloader.initEspeakData()
    }

    // ── Pre-download ─────────────────────────────────────────────────────────

    suspend fun preDownloadModels(lang: String) {
        val config = getLanguageConfig(lang) ?: return
        withContext(Dispatchers.IO) {
            try {
                // Download STT
                val sttFile = downloader.modelFile("stt", lang, "model.int8.onnx")
                val sttTokens = downloader.sharedFile("stt", "tokens.txt")
                if (!sttFile.exists() || sttFile.length() == 0L) {
                    _sttState.value = ModelState.Downloading(0)
                    downloader.downloadSync(config.sttModelUrl, sttFile) { dl, total ->
                        if (total > 0) _sttState.value = ModelState.Downloading(((dl.toFloat() / total) * 100).toInt())
                    }
                }
                if (!sttTokens.exists() || sttTokens.length() == 0L) {
                    downloader.downloadSync(config.sttTokensUrl, sttTokens)
                }
                _sttState.value = ModelState.Idle

                // Download TTS
                if (config.ttsModelUrl != null) {
                    val ttsKey = config.ttsModelKey ?: lang
                    val ttsFile = downloader.modelFile("tts", ttsKey, "model.onnx")
                    val ttsTokens = downloader.modelFile("tts", ttsKey, "tokens.txt")
                    if (!ttsFile.exists() || ttsFile.length() == 0L) {
                        _ttsState.value = ModelState.Downloading(0)
                        downloader.downloadSync(config.ttsModelUrl, ttsFile) { dl, total ->
                            if (total > 0) _ttsState.value = ModelState.Downloading(((dl.toFloat() / total) * 100).toInt())
                        }
                    }
                    if (config.ttsTokensUrl != null && (!ttsTokens.exists() || ttsTokens.length() == 0L)) {
                        downloader.downloadSync(config.ttsTokensUrl, ttsTokens)
                    }
                    _ttsState.value = ModelState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "preDownloadModels failed", e)
                val err = "Download failed: ${e.message}"
                _sttState.value = ModelState.Error(err)
                _ttsState.value = ModelState.Error(err)
            }
        }
    }

    fun isModelDownloaded(lang: String): Boolean {
        val config = getLanguageConfig(lang) ?: return false
        val sttFile = downloader.modelFile("stt", lang, "model.int8.onnx")
        val sttTokens = downloader.sharedFile("stt", "tokens.txt")
        if (!sttFile.exists() || sttFile.length() == 0L) return false
        if (!sttTokens.exists() || sttTokens.length() == 0L) return false

        if (config.ttsModelUrl != null) {
            val ttsKey = config.ttsModelKey ?: lang
            val ttsFile = downloader.modelFile("tts", ttsKey, "model.onnx")
            val ttsTokens = downloader.modelFile("tts", ttsKey, "tokens.txt")
            if (!ttsFile.exists() || ttsFile.length() == 0L) return false
            if (config.ttsTokensUrl != null && (!ttsTokens.exists() || ttsTokens.length() == 0L)) return false
        }
        return true
    }

    // ── STT Lifecycle ────────────────────────────────────────────────────────

    suspend fun loadSttModel(lang: String): OfflineRecognizer? = sttMutex.withLock {
        // Session-level cache: already loaded and correct lang → skip reload
        if (currentSttLang == lang && currentSttRecognizer != null) {
            Log.d(TAG, "STT model for $lang already loaded — reusing")
            _sttState.value = ModelState.Ready
            return@withLock currentSttRecognizer
        }

        val config = getLanguageConfig(lang) ?: run {
            val msg = "Unknown language: $lang"
            Log.e(TAG, msg)
            _sttState.value = ModelState.Error(msg)
            return@withLock null
        }

        withContext(Dispatchers.IO) {
            try {
                _sttState.value = ModelState.Checking
                val modelFile  = downloader.modelFile("stt", lang, "model.int8.onnx")
                val tokensFile = downloader.sharedFile("stt", "tokens.txt")

                if (!modelFile.exists() || modelFile.length() == 0L) {
                    _sttState.value = ModelState.Downloading(0)
                    Log.d(TAG, "Downloading STT model ($lang) from ${config.sttModelUrl}")
                    downloader.downloadSync(config.sttModelUrl, modelFile) { dl, total ->
                        if (total > 0) _sttState.value = ModelState.Downloading(((dl.toFloat() / total) * 100).toInt())
                    }
                }

                if (!tokensFile.exists() || tokensFile.length() == 0L) {
                    Log.d(TAG, "Downloading shared STT tokens")
                    downloader.downloadSync(config.sttTokensUrl, tokensFile)
                }

                _sttState.value = ModelState.Loading

                try { currentSttRecognizer?.release() } catch (e: Exception) { Log.w(TAG, "release old STT: ${e.message}") }
                currentSttRecognizer = null
                currentSttLang = null

                Log.d(TAG, "Creating OfflineRecognizer for $lang")
                val rec = OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig().apply {
                        modelConfig = OfflineModelConfig().apply {
                            nemo = OfflineNemoEncDecCtcModelConfig().apply {
                                model = modelFile.absolutePath
                            }
                            tokens     = tokensFile.absolutePath
                            numThreads = this@ModelManager.numThreads
                            provider   = "cpu"
                        }
                    }
                )

                currentSttRecognizer = rec
                currentSttLang = lang
                _sttState.value = ModelState.Ready
                Log.d(TAG, "STT model READY for $lang")
                rec

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load STT model for $lang", e)
                _sttState.value = ModelState.Error(e.message ?: "Unknown error")
                null
            }
        }
    }

    suspend fun acquireSttForInference(): OfflineRecognizer? = sttMutex.withLock {
        val r = currentSttRecognizer ?: return@withLock null
        sttInferenceCount++
        _sttState.value = ModelState.Running
        Log.d(TAG, "STT inference acquired (count=$sttInferenceCount)")
        r
    }

    suspend fun releaseSttForInference() = sttMutex.withLock {
        if (sttInferenceCount > 0) sttInferenceCount--
        if (sttInferenceCount == 0 && currentSttRecognizer != null) {
            _sttState.value = ModelState.Ready
        }
        Log.d(TAG, "STT inference released (count=$sttInferenceCount)")
    }

    suspend fun unloadStt() = sttMutex.withLock {
        if (sttInferenceCount > 0) {
            Log.w(TAG, "unloadStt() blocked — inference in progress ($sttInferenceCount)")
            return@withLock
        }
        if (currentSttRecognizer != null) {
            Log.d(TAG, "Unloading STT model (lang=$currentSttLang)")
            try { currentSttRecognizer?.release() } catch (e: Exception) { Log.w(TAG, "unloadStt: ${e.message}") }
            currentSttRecognizer = null
        }
        _sttState.value = ModelState.Idle
    }

    // ── TTS Lifecycle ────────────────────────────────────────────────────────

    suspend fun loadTtsModel(lang: String): OfflineTts? = ttsMutex.withLock {
        val config = getLanguageConfig(lang) ?: run {
            val msg = "Unknown language: $lang"
            Log.e(TAG, msg)
            _ttsState.value = ModelState.Error(msg)
            return@withLock null
        }

        val targetKey = config.ttsModelKey ?: lang

        // Re-use shared model if already loaded (e.g. Rasa-13 shared across hi, gu, mr, etc.)
        if (currentTtsModelKey == targetKey && currentTtsEngine != null) {
            Log.d(TAG, "Shared TTS model ($targetKey) already loaded for $lang — reusing instance")
            currentTtsLang = lang
            _ttsState.value = ModelState.Ready
            return@withLock currentTtsEngine
        }

        if (config.ttsModelUrl == null) {
            val msg = "TTS not available for $lang"
            Log.w(TAG, msg)
            _ttsState.value = ModelState.Error(msg)
            return@withLock null
        }

        withContext(Dispatchers.IO) {
            try {
                _ttsState.value = ModelState.Checking
                val ttsKey = config.ttsModelKey ?: lang
                val modelFile  = downloader.modelFile("tts", ttsKey, "model.onnx")
                val tokensFile = downloader.modelFile("tts", ttsKey, "tokens.txt")

                if (!modelFile.exists() || modelFile.length() == 0L) {
                    _ttsState.value = ModelState.Downloading(0)
                    Log.d(TAG, "Downloading TTS model ($ttsKey) from ${config.ttsModelUrl}")
                    downloader.downloadSync(config.ttsModelUrl, modelFile) { dl, total ->
                        if (total > 0) _ttsState.value = ModelState.Downloading(((dl.toFloat() / total) * 100).toInt())
                    }
                }

                if (config.ttsTokensUrl != null && (!tokensFile.exists() || tokensFile.length() == 0L)) {
                    Log.d(TAG, "Downloading TTS tokens ($ttsKey)")
                    downloader.downloadSync(config.ttsTokensUrl, tokensFile)
                }

                _ttsState.value = ModelState.Loading

                try { currentTtsEngine?.release() } catch (e: Exception) { Log.w(TAG, "release old TTS: ${e.message}") }
                currentTtsEngine = null
                currentTtsLang = null
                currentTtsModelKey = null

                Log.d(TAG, "Creating OfflineTts for $lang (modelKey=$targetKey)")
                val tts = OfflineTts(
                    assetManager = null,
                    config = OfflineTtsConfig().apply {
                        model = OfflineTtsModelConfig().apply {
                            vits = OfflineTtsVitsModelConfig().apply {
                                model   = modelFile.absolutePath
                                tokens  = tokensFile.absolutePath
                                dataDir = downloader.espeakDataDir.absolutePath
                            }
                            numThreads = this@ModelManager.numThreads
                            provider   = "cpu"
                        }
                    }
                )

                currentTtsEngine = tts
                currentTtsLang = lang
                currentTtsModelKey = targetKey
                _ttsState.value = ModelState.Ready
                Log.d(TAG, "TTS model READY for $lang (modelKey=$targetKey)")
                tts

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TTS model for $lang", e)
                _ttsState.value = ModelState.Error(e.message ?: "Unknown error")
                null
            }
        }
    }

    suspend fun acquireTtsForInference(): OfflineTts? = ttsMutex.withLock {
        val t = currentTtsEngine ?: return@withLock null
        ttsInferenceCount++
        _ttsState.value = ModelState.Running
        Log.d(TAG, "TTS inference acquired (count=$ttsInferenceCount)")
        t
    }

    suspend fun releaseTtsForInference() = ttsMutex.withLock {
        if (ttsInferenceCount > 0) ttsInferenceCount--
        if (ttsInferenceCount == 0 && currentTtsEngine != null) {
            _ttsState.value = ModelState.Ready
        }
        Log.d(TAG, "TTS inference released (count=$ttsInferenceCount)")
    }

    suspend fun unloadTts() = ttsMutex.withLock {
        if (ttsInferenceCount > 0) {
            Log.w(TAG, "unloadTts() blocked — inference in progress ($ttsInferenceCount)")
            return@withLock
        }
        if (currentTtsEngine != null) {
            Log.d(TAG, "Unloading TTS model (lang=$currentTtsLang, modelKey=$currentTtsModelKey)")
            try { currentTtsEngine?.release() } catch (e: Exception) { Log.w(TAG, "unloadTts: ${e.message}") }
            currentTtsEngine = null
            currentTtsLang = null
            currentTtsModelKey = null
        }
        _ttsState.value = ModelState.Idle
    }

    // ── Getters (non-locking fast path) ──────────────────────────────────────
    fun getSttRecognizer(): OfflineRecognizer? = currentSttRecognizer
    fun getTtsEngine(): OfflineTts? = currentTtsEngine

    // ── Session cleanup ───────────────────────────────────────────────────────
    suspend fun releaseAll() {
        unloadStt()
        unloadTts()
        Log.d(TAG, "All models released")
    }
}
