package com.itantra.stt

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioRecorder
import com.itantra.model.ModelManager
import com.itantra.vad.AdaptiveNoiseFloorVadPipeline
import com.itantra.vad.SILERO_FRAME_SIZE
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ITAN-STT"

/**
 * Speech-to-Text engine backed by Sherpa-ONNX (IndicConformer).
 *
 * Audio pipeline:
 *   AudioRecorder (PCM-16 ShortArray chunks, 16kHz mono)
 *     → frame accumulator (512 samples per Silero frame)
 *     → AdaptiveNoiseFloorVadPipeline (Silero VAD + adaptive threshold + SpeechSegmenter FSM)
 *     → processSpeechSegment (Sherpa-ONNX inference)
 *     → recognizedSentences (shared flow)
 *
 * In PTT mode the [SessionController] calls [start] on FLOOR_GRANTED and [stop] on RELEASE_FLOOR.
 * In PHONE mode it calls [start] immediately after mode transition (continuous, no floor control).
 * Either way the VAD segmenter handles per-sentence boundaries automatically.
 */
class IndicConformerSttEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : SpeechToTextEngine {

    private val _recognizedSentences = MutableSharedFlow<RecognizedSentence>(extraBufferCapacity = 32)
    override val recognizedSentences: Flow<RecognizedSentence> = _recognizedSentences.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioRecorder: AudioRecorder? = null
    private var vadPipeline: AdaptiveNoiseFloorVadPipeline? = null
    private var frameChannel: Channel<ShortArray>? = null
    private var frameWorkerJob: Job? = null
    private val sequenceIdCounter = AtomicInteger(0)

    @Volatile private var isRecording = false
    @Volatile private var isShutdown = false
    @Volatile private var isContinuousMode = false

    private sealed interface Command {
        object Start : Command
        object Stop : Command
        data class SetLang(val langCode: String) : Command
        data class SetContinuous(val enabled: Boolean) : Command
    }

    private val commandChannel = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (cmd in commandChannel) {
                if (isShutdown) break
                try {
                    when (cmd) {
                        is Command.Start         -> handleStart()
                        is Command.Stop          -> handleStop()
                        is Command.SetLang       -> handleSetLanguage(cmd.langCode)
                        is Command.SetContinuous -> isContinuousMode = cmd.enabled
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing STT command $cmd", e)
                }
            }
        }
    }

    // ── SpeechToTextEngine API ────────────────────────────────────────────────

    override fun setLanguage(languageCode: String) {
        Log.d(TAG, "setLanguage($languageCode)")
        commandChannel.trySend(Command.SetLang(languageCode))
    }

    override fun setContinuousMode(enabled: Boolean) {
        Log.d(TAG, "setContinuousMode($enabled)")
        isContinuousMode = enabled
        commandChannel.trySend(Command.SetContinuous(enabled))
    }

    override fun start() {
        if (isShutdown) return
        Log.d(TAG, "IndicConformerSttEngine.start() invoked (continuous=$isContinuousMode), queueing START command")
        commandChannel.trySend(Command.Start)
    }

    override fun stop() {
        if (isShutdown) return
        commandChannel.trySend(Command.Stop)
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down STT Engine")
        isShutdown = true
        commandChannel.close()
        isRecording = false
        frameChannel?.close()
        frameWorkerJob?.cancel()
        frameWorkerJob = null
        frameChannel = null
        audioRecorder?.stopRecording()
        audioRecorder = null
        vadPipeline?.close()
        vadPipeline = null
        scope.cancel()
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private suspend fun handleSetLanguage(languageCode: String) {
        if (!isRecording) {
            modelManager.loadSttModel(languageCode)
        }
    }

    private fun handleStart() {
        if (isRecording) {
            Log.w(TAG, "start() called while already recording — ignored")
            return
        }

        Log.d(TAG, "IndicConformerSttEngine.start() invoked, starting AudioRecorder (continuous=$isContinuousMode)")
        isRecording = true

        // Create a fresh VAD pipeline for this recording session
        val pipeline = AdaptiveNoiseFloorVadPipeline(context)
        vadPipeline = pipeline

        // Sequential frame processing channel to guarantee in-order execution without thread race conditions
        val channel = Channel<ShortArray>(capacity = 128)
        frameChannel = channel

        frameWorkerJob?.cancel()
        frameWorkerJob = scope.launch(Dispatchers.Default) {
            for (frame in channel) {
                if (!isRecording) break
                val segment = pipeline.processFrame(frame, isContinuousMode = isContinuousMode)
                if (segment != null) {
                    Log.d(TAG, "[ITAN-STT] VAD speech segment complete: ${segment.size} samples (${segment.size / 16000f}s) — dispatching to STT")
                    processSpeechSegment(segment)
                }
            }
        }

        // Accumulator for partial Silero frames (512 samples each)
        val frameAccum = ShortArray(SILERO_FRAME_SIZE)
        var framePos = 0
        var totalFramesDispatched = 0

        val recorder = AudioRecorder()
        audioRecorder = recorder
        Log.d(TAG, "[ITAN-STT] AudioRecorder created, calling startRecordingShorts()")

        // Use the ShortArray overload to avoid float→short conversion in the VAD hot path
        recorder.startRecordingShorts { shortChunk ->
            if (!isRecording) return@startRecordingShorts

            for (s in shortChunk) {
                frameAccum[framePos++] = s
                if (framePos == SILERO_FRAME_SIZE) {
                    // Copy frame before reset so the pipeline gets stable data
                    val frame = frameAccum.copyOf()
                    framePos = 0
                    totalFramesDispatched++
                    if (totalFramesDispatched == 1) {
                        Log.d(TAG, "[ITAN-STT] First 512-sample frame queued to sequential VAD worker")
                    } else if (totalFramesDispatched % 50 == 0) {
                        Log.v(TAG, "[ITAN-STT] VAD pipeline tick: $totalFramesDispatched frames processed")
                    }
                    channel.trySend(frame)
                }
            }
        }
    }

    private fun handleStop() {
        if (!isRecording) {
            Log.d(TAG, "stop() called while not recording — ignored")
            return
        }
        Log.d(TAG, "Stopping VAD-gated STT capture")
        isRecording = false

        audioRecorder?.stopRecording()
        audioRecorder = null

        frameChannel?.close()
        frameChannel = null
        frameWorkerJob?.cancel()
        frameWorkerJob = null

        val remainingSegment = vadPipeline?.flush()
        vadPipeline?.close()
        vadPipeline = null

        if (remainingSegment != null && remainingSegment.isNotEmpty()) {
            Log.d(TAG, "[ITAN-STT] PTT release flush — processing ${remainingSegment.size} samples (${remainingSegment.size / 16000f}s)")
            scope.launch(Dispatchers.Default) {
                processSpeechSegment(remainingSegment)
            }
        } else {
            Log.w(TAG, "[ITAN-STT] PTT release flush returned NULL or empty segment")
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Run Sherpa-ONNX inference on a completed VAD speech segment.
     *
     * @param audioData Normalised float PCM [-1.0, 1.0] at 16kHz mono.
     */
    private suspend fun processSpeechSegment(audioData: FloatArray) {
        if (audioData.isEmpty() || isShutdown) return

        val lang = modelManager.currentSttLang ?: "hi"
        var recognizer = modelManager.getSttRecognizer()

        if (recognizer == null) {
            Log.d(TAG, "STT model not in memory — attempting load for $lang")
            recognizer = modelManager.loadSttModel(lang)
        }

        val langCode = modelManager.currentSttLang ?: lang
        var text = ""
        var sttLatency = 0L
        var rtfVal = 0f

        if (recognizer != null) {
            val acquiredRecognizer = modelManager.acquireSttForInference()
            if (acquiredRecognizer != null) {
                try {
                    val startMs = System.currentTimeMillis()
                    Log.d(TAG, "Running Sherpa-ONNX STT on ${audioData.size} samples (${audioData.size / 16000f}s)")
                    val stream = acquiredRecognizer.createStream()
                    stream.acceptWaveform(audioData, sampleRate = AudioRecorder.SAMPLE_RATE)
                    acquiredRecognizer.decode(stream)
                    val result = acquiredRecognizer.getResult(stream)
                    stream.release()

                    text = result.text.trim()
                    val latencyMs = System.currentTimeMillis() - startMs
                    sttLatency = latencyMs
                    val audioDurationMs = (audioData.size.toFloat() / AudioRecorder.SAMPLE_RATE) * 1000f
                    rtfVal = if (audioDurationMs > 0f) latencyMs.toFloat() / audioDurationMs else 0f
                    Log.d(TAG, "STT inference done in ${latencyMs}ms (RTF: $rtfVal): '$text'")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sherpa-ONNX STT inference failed", e)
                } finally {
                    modelManager.releaseSttForInference()
                }
            }
        }

        if (text.isBlank()) {
            Log.d(TAG, "[ITAN-STT] Blank transcription — skipping emission")
            return
        }

        val seqId = sequenceIdCounter.incrementAndGet()
        Log.d(TAG, "[ITAN-STT] Recognized: '$text' lang=$langCode seq=$seqId rtf=$rtfVal")
        _recognizedSentences.emit(
            RecognizedSentence(
                text         = text,
                languageCode = langCode,
                isAlert      = false,
                timestampMs  = System.currentTimeMillis() - sttLatency,
                sequenceId   = seqId,
                sttLatencyMs = sttLatency,
                rtf          = rtfVal,
            )
        )
    }
}
