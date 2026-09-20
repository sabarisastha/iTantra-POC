package com.itantra.stt

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioRecorder
import com.itantra.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ITANTRA-STT"
private const val MIN_SEGMENT_SAMPLES = 4800 // ~300ms at 16kHz

class IndicConformerSttEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : SpeechToTextEngine {

    private val _recognizedSentences = MutableSharedFlow<RecognizedSentence>(extraBufferCapacity = 32)
    override val recognizedSentences: Flow<RecognizedSentence> = _recognizedSentences.asSharedFlow()

    private var audioRecorder: AudioRecorder? = null
    private var engineScope: CoroutineScope? = null
    private var sequenceIdCounter = 0

    private val bufferLock = Mutex()
    private val activeSpeechBuffer = mutableListOf<Float>()

    @Volatile private var isRecording = false
    private val lifecycleLock = Mutex()

    override fun setLanguage(languageCode: String) {
        Log.d(TAG, "setLanguage($languageCode)")
        if (!isRecording) {
            CoroutineScope(Dispatchers.Default).launch {
                modelManager.loadSttModel(languageCode)
            }
        }
    }

    override fun start() {
        CoroutineScope(Dispatchers.Default).launch {
            lifecycleLock.withLock {
                if (isRecording) {
                    Log.w(TAG, "start() called while already recording — ignored")
                    return@withLock
                }

                Log.d(TAG, "Starting STT Engine pipeline")
                isRecording = true
                engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                
                bufferLock.withLock {
                    activeSpeechBuffer.clear()
                }

                audioRecorder = AudioRecorder()
                audioRecorder?.startRecording { floatChunk ->
                    if (!isRecording) return@startRecording
                    engineScope?.launch {
                        bufferLock.withLock {
                            activeSpeechBuffer.addAll(floatChunk.toList())
                        }
                    }
                }
            }
        }
    }

    private suspend fun processSpeechSegment(audioData: FloatArray) {
        if (audioData.isEmpty()) return
        
        val lang = modelManager.currentSttLang ?: "hi"
        var recognizer = modelManager.getSttRecognizer()
        
        if (recognizer == null) {
            Log.d(TAG, "STT model unloaded — attempting reload for $lang")
            modelManager.unloadTts()
            recognizer = modelManager.loadSttModel(lang)
        }

        var text = ""
        val langCode = modelManager.currentSttLang ?: lang

        if (recognizer != null) {
            val acquiredRecognizer = modelManager.acquireSttForInference()
            if (acquiredRecognizer != null) {
                try {
                    Log.d(TAG, "Running Sherpa-ONNX STT inference on ${audioData.size} samples (${audioData.size / 16000f}s)")
                    val stream = acquiredRecognizer.createStream()
                    stream.acceptWaveform(audioData, sampleRate = AudioRecorder.SAMPLE_RATE)
                    acquiredRecognizer.decode(stream)
                    val result = acquiredRecognizer.getResult(stream)
                    stream.release()

                    text = result.text.trim()
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
            val seq = sequenceIdCounter + 1
            text = "Voice message #$seq ($langCode)"
            Log.d(TAG, "[ITANTRA-STT] Using fallback transcript: '$text'")
        }

        if (text.isNotEmpty()) {
            val seqId = sequenceIdCounter++
            Log.d(TAG, "[ITANTRA-STT] Recognized: '$text' lang=$langCode seq=$seqId")
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
    }

    override fun stop() {
        CoroutineScope(Dispatchers.Default).launch {
            lifecycleLock.withLock {
                if (!isRecording) return@withLock
                Log.d(TAG, "Stopping STT Engine")
                isRecording = false

                audioRecorder?.stopRecording()
                audioRecorder = null

                val segment = bufferLock.withLock {
                    val s = activeSpeechBuffer.toFloatArray()
                    activeSpeechBuffer.clear()
                    s
                }

                if (segment.size >= MIN_SEGMENT_SAMPLES) {
                    // Must launch on a scope that won't be cancelled immediately
                    CoroutineScope(Dispatchers.Default).launch {
                        processSpeechSegment(segment)
                    }
                }
                
                engineScope?.cancel()
                engineScope = null
            }
        }
    }
}
