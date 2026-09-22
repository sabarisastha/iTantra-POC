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
    private data class CachedAudio(val samples: FloatArray, val sampleRate: Int, val isAlert: Boolean)
    private val replayLock = Any()
    private val replayCache = object : LinkedHashMap<Int, CachedAudio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedAudio>): Boolean = size > 20
    }
    private val fallbackReplayCache = object : LinkedHashMap<Int, RecognizedSentence>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RecognizedSentence>): Boolean = size > 20
    }

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
                        synchronized(replayLock) {
                            replayCache[sentence.sequenceId] = CachedAudio(pcmData.copyOf(), sampleRate, sentence.isAlert)
                        }
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

        // Fallback to Android System TextToSpeech (works offline for all installed device languages including Tamil)
        Log.d(TAG, "[ITANTRA-TTS] Using Android System TextToSpeech fallback for '${sentence.text}' (lang=$expectedLang)")
        synchronized(replayLock) {
            fallbackReplayCache[sentence.sequenceId] = sentence
        }
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

    override fun prewarm(languageCode: String) {
        if (isShutdown) return
        scope.launch {
            if (modelManager.currentTtsLang != languageCode || modelManager.getTtsEngine() == null) {
                Log.d(TAG, "Prewarming local TTS model for $languageCode")
                modelManager.loadTtsModel(languageCode)
            }
        }
    }

    override fun replay(sequenceId: Int): Boolean {
        // 1. Check Sherpa-ONNX audio cache
        val cachedAudio = synchronized(replayLock) { replayCache[sequenceId] }
        if (cachedAudio != null) {
            Log.d(TAG, "Replaying cached Sherpa-ONNX audio for seq=$sequenceId")
            scope.launch { audioPlayer.playAudio(cachedAudio.samples, cachedAudio.sampleRate, cachedAudio.isAlert) }
            return true
        }

        // 2. Check System TTS fallback cache
        val cachedSentence = synchronized(replayLock) { fallbackReplayCache[sequenceId] }
        if (cachedSentence != null) {
            Log.d(TAG, "Replaying cached System TTS utterance for seq=$sequenceId: '${cachedSentence.text}'")
            fallbackSystemTts.speak(cachedSentence)
            return true
        }

        Log.w(TAG, "No replay cache found for seq=$sequenceId")
        return false
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down TTS Engine")
        isShutdown = true
        currentJob?.cancel()
        synchronized(replayLock) { 
            replayCache.clear() 
            fallbackReplayCache.clear()
        }
        audioPlayer.stop()
        fallbackSystemTts.shutdown()
        scope.cancel()
    }
}
