package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioPlayer
import com.itantra.mock.MockTtsEngine
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.stt.RecognizedSentence
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ITANTRA-TTS"

class IndicParlerTtsEngine(
    private val context: Context,
    private val modelManager: ModelManager
) : TextToSpeechEngine {

    private val audioPlayer = AudioPlayer()
    private val fallbackSystemTts = MockTtsEngine(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null
    private val jobLock = Mutex()

    @Volatile private var isShutdown = false

    override fun speak(sentence: RecognizedSentence) {
        if (isShutdown) {
            Log.w(TAG, "speak() called after shutdown — ignored")
            return
        }

        scope.launch {
            jobLock.withLock {
                if (sentence.isAlert) {
                    currentJob?.cancel()
                    audioPlayer.stop()
                }
                currentJob = scope.launch {
                    synthesizeAndPlay(sentence)
                }
            }
        }
    }

    private suspend fun synthesizeAndPlay(sentence: RecognizedSentence) {
        Log.d(TAG, "TTS requested: '${sentence.text}' lang=${sentence.languageCode}")

        val expectedLang = sentence.languageCode.ifBlank { "hi" }

        var tts = modelManager.getTtsEngine()
        if (tts == null || modelManager.currentTtsLang != expectedLang) {
            Log.d(TAG, "TTS model not loaded for $expectedLang — attempting load")
            modelManager.unloadStt()
            tts = modelManager.loadTtsModel(expectedLang)
        }

        if (tts != null && modelManager.ttsState.value !is ModelState.Error) {
            val acquiredTts = modelManager.acquireTtsForInference()
            if (acquiredTts != null) {
                try {
                    val startMs = System.currentTimeMillis()
                    Log.d(TAG, "Synthesizing speech via Sherpa-ONNX...")
                    val generatedAudio = acquiredTts.generate(sentence.text)
                    val latencyMs = System.currentTimeMillis() - startMs

                    val pcmData    = generatedAudio.samples
                    val sampleRate = generatedAudio.sampleRate
                    Log.d(TAG, "TTS done in ${latencyMs}ms — samples=${pcmData.size} rate=$sampleRate")

                    if (pcmData.isNotEmpty()) {
                        audioPlayer.playAudio(pcmData, sampleRate, sentence.isAlert)
                        return
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "TTS job cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sherpa-ONNX TTS synthesis failed — falling back to System TTS", e)
                } finally {
                    modelManager.releaseTtsForInference()
                }
            }
        }

        // Fallback to Android System TextToSpeech
        Log.d(TAG, "[ITANTRA-TTS] Using Android System TextToSpeech fallback for '${sentence.text}'")
        fallbackSystemTts.speak(
            RecognizedSentence(
                text         = sentence.text,
                languageCode = expectedLang,
                isAlert      = sentence.isAlert,
                timestampMs  = sentence.timestampMs,
                sequenceId   = sentence.sequenceId
            )
        )
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down TTS Engine")
        isShutdown = true
        currentJob?.cancel()
        audioPlayer.stop()
        fallbackSystemTts.shutdown()
        scope.cancel()
    }
}
