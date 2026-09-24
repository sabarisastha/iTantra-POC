package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioPlayer
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.stt.RecognizedSentence
import kotlinx.coroutines.channels.Channel
import com.itantra.audio.PcmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val TAG = "IndicParlerTtsEngine"
private const val BENCH_TAG = "TTS_BENCH"

/**
 * Text-to-Speech Engine using on-device Sherpa-ONNX VITS model.
 * Falls back to Android System TextToSpeech if Sherpa-ONNX is unavailable or fails.
 */
class IndicParlerTtsEngine(
    private val context: Context,
    private val modelManager: ModelManager,
    private val audioPlayer: AudioPlayer = AudioPlayer()
) : TextToSpeechEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentJob: Job? = null
    private val replayLock = Any()
    private val fallbackSystemTts = com.itantra.mock.MockTtsEngine(context)

    private val replayCache = object : LinkedHashMap<Int, CachedAudio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedAudio>): Boolean = size > 20
    }
    private val fallbackReplayCache = object : LinkedHashMap<Int, RecognizedSentence>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RecognizedSentence>): Boolean = size > 20
    }

    @Volatile private var isShutdown = false
    @Volatile private var isPlayingAlert = false

    override fun speak(sentence: RecognizedSentence) {
        if (isShutdown) {
            Log.w(TAG, "speak() called after shutdown — ignored")
            return
        }

        val t0 = if (sentence.receivedTimestampMs > 0L) sentence.receivedTimestampMs else sentence.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val t1 = System.currentTimeMillis()

        if (sentence.isAlert) {
            // Alert is highest priority: preempt any ongoing normal speech immediately
            currentJob?.cancel()
            audioPlayer.stop()
            isPlayingAlert = true
            currentJob = scope.launch(Dispatchers.Default) {
                try {
                    synthesizeAndPlay(sentence, t0, t1)
                } finally {
                    isPlayingAlert = false
                }
            }
        } else {
            // Normal speech must NOT interrupt an active alert
            if (isPlayingAlert) {
                Log.w(TAG, "[TTS] Normal speech dropped/deferred: emergency alert is playing")
                return
            }
            // For normal PTT messages, launch sequentially or await active job so mid-sentence is not cut off by duplicate events
            val prevJob = currentJob
            currentJob = scope.launch(Dispatchers.Default) {
                if (prevJob != null && prevJob.isActive) {
                    Log.d(TAG, "[TTS] Awaiting previous active speech job completion before starting new sentence")
                    try { prevJob.join() } catch (_: Throwable) {}
                }
                synthesizeAndPlay(sentence, t0, t1)
            }
        }
    }

    private suspend fun synthesizeAndPlay(sentence: RecognizedSentence, t0: Long, t1: Long) {
        Log.d(TAG, "[TTS] REQUEST_STARTED: '${sentence.text}' lang=${sentence.languageCode} alert=${sentence.isAlert}")
        val expectedLang = sentence.languageCode.ifBlank { "hi" }

        var tts = modelManager.getTtsEngine()
        if (tts == null || modelManager.currentTtsLang != expectedLang) {
            Log.d(TAG, "TTS model not loaded for $expectedLang — attempting load")
            tts = modelManager.loadTtsModel(expectedLang)
        }

        val t2 = System.currentTimeMillis()
        Log.d(TAG, "[TTS] MODEL_READY in ${t2 - t1}ms")

        if (tts != null && modelManager.ttsState.value !is ModelState.Error) {
            val acquiredTts = modelManager.acquireTtsForInference()
            if (acquiredTts != null) {
                try {
                    val sid = modelManager.currentTtsSpeakerId
                    val sampleRate = acquiredTts.sampleRate()
                    val segments = TextSegmenter.segment(sentence.text)
                    if (segments.isEmpty()) return

                    val t3 = System.currentTimeMillis()
                    Log.d(TAG, "[TTS] SYNTHESIS_STARTED via Sherpa-ONNX (speakerId=$sid, segments=${segments.size})...")

                    val pcmChannel = Channel<PcmChunk>(Channel.UNLIMITED)
                    
                    // Consumer: Launches audio writer on Dispatchers.IO
                    val playbackJob = scope.launch(Dispatchers.IO) {
                        audioPlayer.playTtsQueue(
                            channel = pcmChannel,
                            sampleRate = sampleRate,
                            isAlert = sentence.isAlert,
                            t0 = t0,
                            t1 = t1,
                            t2 = t2,
                            t3 = t3,
                            sequenceId = sentence.sequenceId
                        )
                    }

                    // Producer: Synthesizes chunks and pushes to Channel
                    var totalGeneratedSamples = 0
                    val accumulatedChunks = mutableListOf<FloatArray>()
                    val totalSegments = segments.size

                    for ((index, segment) in segments.withIndex()) {
                        val chunkNum = index + 1
                        Log.d(TAG, "[TTS] CHUNK $chunkNum START")
                        coroutineContext.ensureActive()

                        val tChunkStart = System.currentTimeMillis()
                        val genAudio = acquiredTts.generate(segment, sid, 1.0f)
                        Log.d(TAG, "[TTS] CHUNK $chunkNum END (took ${System.currentTimeMillis() - tChunkStart}ms)")

                        val samples = genAudio.samples
                        if (samples.isNotEmpty()) {
                            totalGeneratedSamples += samples.size
                            accumulatedChunks.add(samples.copyOf())
                            Log.d(TAG, "[AUDIO] CHUNK $chunkNum QUEUED (${samples.size} samples)")
                            pcmChannel.send(PcmChunk(chunkNum, samples, isEos = false))
                        }
                    }

                    // Send EOS token to Consumer
                    Log.d(TAG, "[AUDIO] CHUNK EOS QUEUED (totalChunks=$totalSegments totalGeneratedSamples=$totalGeneratedSamples)")
                    pcmChannel.send(PcmChunk(totalSegments + 1, FloatArray(0), isEos = true))

                    // Store complete concatenated array for instant replay
                    if (totalGeneratedSamples > 0) {
                        val fullPcm = FloatArray(totalGeneratedSamples)
                        var offset = 0
                        for (c in accumulatedChunks) {
                            System.arraycopy(c, 0, fullPcm, offset, c.size)
                            offset += c.size
                        }
                        synchronized(replayLock) {
                            replayCache[sentence.sequenceId] = CachedAudio(fullPcm, sampleRate, sentence.isAlert)
                        }
                    }

                    // Await Consumer playback completion
                    playbackJob.join()
                    return
                } catch (e: CancellationException) {
                    Log.d(TAG, "TTS job cancelled")
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Sherpa-ONNX TTS synthesis failed — falling back to System TTS", e)
                } finally {
                    modelManager.releaseTtsForInference()
                }
            }
        }

        // Fallback to Android System TextToSpeech (works offline for all installed device languages including Tamil)
        val fallbackStart = System.currentTimeMillis()
        Log.d(TAG, "[ITANTRA-TTS] Using Android System TextToSpeech fallback for '${sentence.text}' (lang=$expectedLang, isAlert=${sentence.isAlert})")
        synchronized(replayLock) {
            fallbackReplayCache[sentence.sequenceId] = sentence
        }
        val pt4 = System.currentTimeMillis()
        val pt5 = System.currentTimeMillis()
        fallbackSystemTts.speak(
            RecognizedSentence(
                text         = sentence.text,
                languageCode = expectedLang,
                isAlert      = sentence.isAlert,
                timestampMs  = sentence.timestampMs,
                sequenceId   = sentence.sequenceId
            )
        )
        val pt6 = System.currentTimeMillis()
        val pt7 = pt6
        val firstAudibleLatencyMs = pt5 - t0
        val firstAudioGenMs = pt4 - t0
        val totalSynthesisMs = pt6 - t0
        val totalLatencyMs = pt7 - t0
        Log.i(
            BENCH_TAG,
            "[TTS_BENCH] T0_RECEIVED=$t0 T1_REQUESTED=$t1 T2_MODEL_READY=$t2 T3_SYNTHESIS_START=$fallbackStart T4_FIRST_AUDIO=$pt4 T5_PLAYBACK_START=$pt5 T6_SYNTHESIS_END=$pt6 T7_PLAYBACK_END=$pt7 FIRST_AUDIBLE_LATENCY_MS=$firstAudibleLatencyMs FIRST_AUDIO_GEN_MS=$firstAudioGenMs TOTAL_SYNTHESIS_MS=$totalSynthesisMs TOTAL_LATENCY_MS=$totalLatencyMs"
        )
    }

    override fun prewarm(languageCode: String) {
        if (isShutdown) return
        scope.launch(Dispatchers.Default) {
            if (modelManager.currentTtsLang != languageCode || modelManager.getTtsEngine() == null) {
                Log.d(TAG, "Prewarming local TTS model for $languageCode")
                modelManager.loadTtsModel(languageCode)
            }
            audioPlayer.prewarm(22050, false)
        }
    }

    override fun replay(sequenceId: Int): Boolean {
        // 1. Check Sherpa-ONNX audio cache
        val cachedAudio = synchronized(replayLock) { replayCache[sequenceId] }
        if (cachedAudio != null) {
            Log.d(TAG, "Replaying cached Sherpa-ONNX audio for seq=$sequenceId")
            scope.launch(Dispatchers.Default) { audioPlayer.playAudio(cachedAudio.samples, cachedAudio.sampleRate, cachedAudio.isAlert) }
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
    }

    private data class CachedAudio(val samples: FloatArray, val sampleRate: Int, val isAlert: Boolean)
}
