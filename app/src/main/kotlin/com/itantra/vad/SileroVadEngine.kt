package com.itantra.vad

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "SileroVadEngine"
private const val MODEL_ASSET = "silero_vad.onnx"
private const val SAMPLE_RATE = 16000
const val SILERO_FRAME_SIZE = 512 // 32ms at 16kHz

/**
 * Offline Voice Activity Detection engine powered by Silero VAD running via Sherpa-ONNX native C++ API.
 *
 * Efficient:
 * - Direct C++ inference using bundled silero_vad.onnx.
 * - Thread-safe frame computation.
 * - Graceful fallback to energy VAD if model is absent or runtime fails.
 */
class SileroVadEngine(
    private val context: Context,
    private val threshold: Float = 0.25f,
) : VadEngine {

    private var vad: Vad? = null
    @Volatile var isInitialized = false
        private set

    companion object {
        @Volatile var isOrtUnavailable = false
    }

    private val vadMutex = Mutex()

    init {
        initEngine()
    }

    private fun initEngine() {
        if (isOrtUnavailable) return
        try {
            Log.d(TAG, "Initializing Sherpa-ONNX native Vad with asset $MODEL_ASSET...")
            val sileroConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = 0.25f, // Baseline low threshold for raw neural probability output
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.05f,
                windowSize = SILERO_FRAME_SIZE,
                maxSpeechDuration = 30.0f
            )
            val config = VadModelConfig(
                sileroVadModelConfig = sileroConfig,
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
            val instance = Vad(context.assets, config)
            vad = instance
            isInitialized = true
            isOrtUnavailable = false
            Log.d(TAG, "Sherpa-ONNX Silero VAD loaded successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX Silero VAD. Latching to fallback energy VAD.", t)
            isOrtUnavailable = true
            isInitialized = false
        }
    }

    override suspend fun processFrame(pcmFrame: ShortArray): VadResult = withContext(Dispatchers.Default) {
        if (isOrtUnavailable || !isInitialized) {
            return@withContext fallbackVad(pcmFrame)
        }

        // Silero VAD strictly requires 512 samples at 16kHz
        val frameToProcess = if (pcmFrame.size == SILERO_FRAME_SIZE) {
            pcmFrame
        } else if (pcmFrame.size > SILERO_FRAME_SIZE) {
            pcmFrame.copyOfRange(0, SILERO_FRAME_SIZE)
        } else {
            return@withContext fallbackVad(pcmFrame)
        }

        vadMutex.withLock {
            val instance = vad
            if (!isInitialized || instance == null) {
                return@withContext fallbackVad(pcmFrame)
            }
            try {
                val floatFrame = FloatArray(frameToProcess.size)
                for (i in frameToProcess.indices) {
                    floatFrame[i] = frameToProcess[i] / 32768.0f
                }

                val prob = instance.compute(floatFrame)
                return@withContext VadResult(
                    speechProbability = prob,
                    isSpeech = prob >= threshold
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error processing frame with Sherpa VAD", t)
                return@withContext fallbackVad(pcmFrame)
            }
        }
    }

    /**
     * Fallback RMS/dBFS energy calculation if native neural engine fails or is unavailable.
     */
    fun fallbackVad(pcmFrame: ShortArray): VadResult {
        if (pcmFrame.isEmpty()) return VadResult(0f, false)
        var sumSq = 0.0
        for (sample in pcmFrame) {
            val s = sample.toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / pcmFrame.size)
        val normRms = rms / 32767.0
        val dbfs = if (rms > 0.0) 20.0 * log10(normRms) else -100.0
        val isVoiced = dbfs >= -42.0 || normRms > 0.008
        val prob = if (isVoiced) 0.85f else 0.10f
        return VadResult(speechProbability = prob, isSpeech = isVoiced)
    }

    override fun reset() {
        Log.d(TAG, "Resetting Sherpa VAD internal recurrent states")
        runBlocking {
            vadMutex.withLock {
                runCatching { vad?.reset() }
            }
        }
    }

    override fun close() {
        Log.d(TAG, "Closing Sherpa VAD session and native resources")
        runBlocking {
            vadMutex.withLock {
                isInitialized = false
                runCatching { vad?.release() }
                vad = null
            }
        }
    }
}