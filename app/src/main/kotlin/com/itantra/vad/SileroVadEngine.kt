package com.itantra.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "SileroVadEngine"
private const val MODEL_ASSET = "silero_vad.onnx"
private const val SAMPLE_RATE = 16000L
const val SILERO_FRAME_SIZE = 512 // 32ms at 16kHz

/**
 * Real offline Voice Activity Detection engine powered by Silero VAD running via ONNX Runtime Mobile.
 *
 * Efficient:
 * - Loads model once on initialization into a single OrtSession.
 * - Reuses float buffers and tensor memory.
 * - Manages recurrent hidden state across sequential frames.
 * - Thread-safe and non-blocking (runs on Dispatchers.Default / background).
 * - Graceful fallback to energy VAD if model is absent or runtime fails.
 */
class SileroVadEngine(
    private val context: Context,
    private val threshold: Float = 0.5f,
) : VadEngine {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    @Volatile private var isInitialized = false

    // Model input metadata
    private var isV5 = false
    private var stateShape = longArrayOf(2, 1, 128) // Default Silero v5 state shape

    private val vadMutex = kotlinx.coroutines.sync.Mutex()

    private fun allocateDirectBuffer(capacity: Int): FloatBuffer {
        return java.nio.ByteBuffer.allocateDirect(capacity * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    // Reusable direct float buffers for recurrent state (zero-copy JNI)
    private var stateBuffer: FloatBuffer = allocateDirectBuffer(2 * 1 * 128)
    private var hBuffer: FloatBuffer = allocateDirectBuffer(2 * 1 * 64) // For Silero v4
    private var cBuffer: FloatBuffer = allocateDirectBuffer(2 * 1 * 64) // For Silero v4

    init {
        initOnnx()
    }

    private fun initOnnx() {
        try {
            Log.d(TAG, "Initializing ONNX Runtime and loading $MODEL_ASSET...")
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val sessionOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val session = env.createSession(modelBytes, sessionOpts)
            ortSession = session

            val inputNames = session.inputNames
            Log.d(TAG, "Silero VAD loaded successfully. Inputs: $inputNames, Outputs: ${session.outputNames}")

            if (inputNames.contains("state")) {
                isV5 = true
                val info = session.inputInfo["state"]
                val tensorInfo = info?.info as? ai.onnxruntime.TensorInfo
                tensorInfo?.shape?.let { shape ->
                    stateShape = shape
                    val total = shape.reduce { acc, l -> acc * l }.toInt()
                    stateBuffer = allocateDirectBuffer(total)
                }
                Log.d(TAG, "Detected Silero VAD v5 architecture. State shape: ${stateShape.contentToString()}")
            } else {
                isV5 = false
                Log.d(TAG, "Detected Silero VAD v4 architecture.")
            }

            reset()
            isInitialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Silero VAD via ONNX Runtime. Will use fallback VAD.", t)
            isInitialized = false
        }
    }

    override suspend fun processFrame(pcmFrame: ShortArray): VadResult = withContext(Dispatchers.Default) {
        if (!isInitialized || ortSession == null || ortEnv == null) {
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
            val env = ortEnv ?: return@withContext fallbackVad(pcmFrame)
            val session = ortSession ?: return@withContext fallbackVad(pcmFrame)

            val createdTensors = mutableListOf<OnnxTensor>()
            var runResult: ai.onnxruntime.OrtSession.Result? = null

            try {
                // Convert PCM-16 shorts to float [-1.0, 1.0]
                val floatFrame = FloatArray(frameToProcess.size)
                for (i in frameToProcess.indices) {
                    floatFrame[i] = frameToProcess[i] / 32768.0f
                }

                val inputTensor = OnnxTensor.createTensor(env, arrayOf(floatFrame))
                createdTensors.add(inputTensor)
                val srTensor = OnnxTensor.createTensor(env, longArrayOf(SAMPLE_RATE))
                createdTensors.add(srTensor)

                val inputs = mutableMapOf<String, OnnxTensor>()
                inputs["input"] = inputTensor
                inputs["sr"] = srTensor

                if (isV5) {
                    stateBuffer.rewind()
                    val stateTensor = OnnxTensor.createTensor(env, stateBuffer, stateShape)
                    createdTensors.add(stateTensor)
                    inputs["state"] = stateTensor

                    val result = session.run(inputs)
                    runResult = result

                    val outputTensor = result.get(0) as OnnxTensor
                    @Suppress("UNCHECKED_CAST") val prob = (outputTensor.value as Array<FloatArray>)[0][0]

                    // Update recurrent state for next frame
                    if (result.size() > 1) {
                        val nextStateTensor = result.get(1) as OnnxTensor
                        val nextStateValues = nextStateTensor.floatBuffer
                        stateBuffer.rewind()
                        stateBuffer.put(nextStateValues)
                    }

                    return@withContext VadResult(
                        speechProbability = prob,
                        isSpeech = prob >= threshold
                    )
                } else {
                    // Silero v4 fallback (h, c)
                    hBuffer.rewind()
                    cBuffer.rewind()
                    val hTensor = OnnxTensor.createTensor(env, hBuffer, longArrayOf(2, 1, 64))
                    createdTensors.add(hTensor)
                    val cTensor = OnnxTensor.createTensor(env, cBuffer, longArrayOf(2, 1, 64))
                    createdTensors.add(cTensor)
                    inputs["h"] = hTensor
                    inputs["c"] = cTensor

                    val result = session.run(inputs)
                    runResult = result

                    val outputTensor = result.get(0) as OnnxTensor
                    @Suppress("UNCHECKED_CAST") val prob = (outputTensor.value as Array<FloatArray>)[0][0]

                    if (result.size() > 2) {
                        val nextH = (result.get(1) as OnnxTensor).floatBuffer
                        val nextC = (result.get(2) as OnnxTensor).floatBuffer
                        hBuffer.rewind()
                        cBuffer.rewind()
                        hBuffer.put(nextH)
                        cBuffer.put(nextC)
                    }

                    return@withContext VadResult(
                        speechProbability = prob,
                        isSpeech = prob >= threshold
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ONNX inference error in SileroVadEngine", t)
                return@withContext fallbackVad(pcmFrame)
            } finally {
                runResult?.close()
                for (tensor in createdTensors) {
                    runCatching { tensor.close() }
                }
            }
        }
    }

    /**
     * Fallback RMS/dBFS energy calculation if ONNX Runtime fails or is unavailable.
     */
    private fun fallbackVad(pcmFrame: ShortArray): VadResult {
        if (pcmFrame.isEmpty()) return VadResult(0f, false)
        var sumSq = 0.0
        for (sample in pcmFrame) {
            val s = sample.toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / pcmFrame.size)
        val dbfs = if (rms > 0.0) 20.0 * log10(rms / 32767.0) else -100.0
        val isVoiced = dbfs >= -38.0
        val prob = if (isVoiced) 0.85f else 0.10f
        return VadResult(speechProbability = prob, isSpeech = isVoiced)
    }

    override fun reset() {
        Log.d(TAG, "Resetting Silero VAD internal recurrent states")
        stateBuffer.rewind()
        for (i in 0 until stateBuffer.capacity()) {
            stateBuffer.put(i, 0.0f)
        }
        hBuffer.rewind()
        for (i in 0 until hBuffer.capacity()) {
            hBuffer.put(i, 0.0f)
        }
        cBuffer.rewind()
        for (i in 0 until cBuffer.capacity()) {
            cBuffer.put(i, 0.0f)
        }
    }

    override fun close() {
        Log.d(TAG, "Closing Silero VAD session and environment")
        runCatching { ortSession?.close() }
        runCatching { ortEnv?.close() }
        ortSession = null
        ortEnv = null
        isInitialized = false
    }
}