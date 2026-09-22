package com.itantra.stt

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioRecorder
import com.itantra.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ITAN-STT"
private const val MIN_SEGMENT_SAMPLES = 4800 // ~300ms at 16kHz
private const val MAX_CAPTURE_SAMPLES = AudioRecorder.SAMPLE_RATE * 60

class IndicConformerSttEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : SpeechToTextEngine {

    private val _recognizedSentences = MutableSharedFlow<RecognizedSentence>(extraBufferCapacity = 32)
    override val recognizedSentences: Flow<RecognizedSentence> = _recognizedSentences.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioRecorder: AudioRecorder? = null
    private val sequenceIdCounter = AtomicInteger(0)

    private val chunkLock = Any()
    private val audioChunks = ArrayList<FloatArray>()
    private var capturedSamples = 0

    @Volatile private var isRecording = false
    @Volatile private var isShutdown = false

    private sealed interface Command {
        object Start : Command
        object Stop : Command
        data class SetLang(val langCode: String) : Command
    }

    private val commandChannel = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (cmd in commandChannel) {
                if (isShutdown) break
                try {
                    when (cmd) {
                        is Command.Start -> handleStart()
                        is Command.Stop -> handleStop()
                        is Command.SetLang -> handleSetLanguage(cmd.langCode)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing STT command $cmd", e)
                }
            }
        }
    }

    override fun setLanguage(languageCode: String) {
        Log.d(TAG, "setLanguage($languageCode)")
        commandChannel.trySend(Command.SetLang(languageCode))
    }

    private suspend fun handleSetLanguage(languageCode: String) {
        if (!isRecording) {
            modelManager.loadSttModel(languageCode)
        }
    }

    override fun start() {
        if (isShutdown) return
        commandChannel.trySend(Command.Start)
    }

    private fun handleStart() {
        if (isRecording) {
            Log.w(TAG, "start() called while already recording — ignored")
            return
        }

        Log.d(TAG, "Starting STT Engine capture")
        synchronized(chunkLock) {
            audioChunks.clear()
            capturedSamples = 0
        }
        isRecording = true

        val recorder = AudioRecorder()
        audioRecorder = recorder
        recorder.startRecording { floatChunk ->
            if (!isRecording) return@startRecording
            synchronized(chunkLock) {
                val remaining = MAX_CAPTURE_SAMPLES - capturedSamples
                if (remaining > 0) {
                    val accepted = floatChunk.copyOf(minOf(floatChunk.size, remaining))
                    audioChunks.add(accepted)
                    capturedSamples += accepted.size
                } else {
                    Log.w(TAG, "Maximum STT capture duration reached; dropping additional audio")
                }
            }
        }
    }

    override fun stop() {
        if (isShutdown) return
        commandChannel.trySend(Command.Stop)
    }

    private fun handleStop() {
        if (!isRecording) {
            Log.d(TAG, "stop() called while not recording — ignored")
            return
        }
        Log.d(TAG, "Stopping STT Engine capture")
        isRecording = false

        audioRecorder?.stopRecording()
        audioRecorder = null

        val segment = synchronized(chunkLock) {
            val totalSamples = audioChunks.sumOf { it.size }
            if (totalSamples == 0) {
                FloatArray(0)
            } else {
                val combined = FloatArray(totalSamples)
                var offset = 0
                for (chunk in audioChunks) {
                    System.arraycopy(chunk, 0, combined, offset, chunk.size)
                    offset += chunk.size
                }
                audioChunks.clear()
                capturedSamples = 0
                combined
            }
        }

        if (segment.size >= MIN_SEGMENT_SAMPLES) {
            scope.launch {
                processSpeechSegment(segment)
            }
        } else {
            Log.d(TAG, "Speech segment too short (${segment.size} samples < $MIN_SEGMENT_SAMPLES) — discarded")
        }
    }

    private suspend fun processSpeechSegment(audioData: FloatArray) {
        if (audioData.isEmpty() || isShutdown) return

        val lang = modelManager.currentSttLang ?: "hi"
        var recognizer = modelManager.getSttRecognizer()

        if (recognizer == null) {
            Log.d(TAG, "STT model not in memory — attempting load for $lang")
            recognizer = modelManager.loadSttModel(lang)
        }

        var text = ""
        val langCode = modelManager.currentSttLang ?: lang

        if (recognizer != null) {
            val acquiredRecognizer = modelManager.acquireSttForInference()
            if (acquiredRecognizer != null) {
                try {
                    val startMs = System.currentTimeMillis()
                    Log.d(TAG, "Running Sherpa-ONNX STT inference on ${audioData.size} samples (${audioData.size / 16000f}s)")
                    val stream = acquiredRecognizer.createStream()
                    stream.acceptWaveform(audioData, sampleRate = AudioRecorder.SAMPLE_RATE)
                    acquiredRecognizer.decode(stream)
                    val result = acquiredRecognizer.getResult(stream)
                    stream.release()

                    text = result.text.trim()
                    val latencyMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "STT inference finished in ${latencyMs}ms: '$text'")
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
            Log.d(TAG, "[ITAN-STT] Silence / blank transcription — skipping emission")
            return
        }

        val seqId = sequenceIdCounter.incrementAndGet()
        Log.d(TAG, "[ITAN-STT] Recognized: '$text' lang=$langCode seq=$seqId")
        _recognizedSentences.emit(
            RecognizedSentence(
                text         = text,
                languageCode = langCode,
                isAlert      = false,
                timestampMs  = System.currentTimeMillis(),
                sequenceId   = seqId
            )
        )
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down STT Engine")
        isShutdown = true
        commandChannel.close()
        isRecording = false
        audioRecorder?.stopRecording()
        audioRecorder = null
        synchronized(chunkLock) {
            audioChunks.clear()
            capturedSamples = 0
        }
        scope.cancel()
    }
}
