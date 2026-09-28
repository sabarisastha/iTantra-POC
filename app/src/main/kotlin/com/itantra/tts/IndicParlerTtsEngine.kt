package com.itantra.tts

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioPlayer
import com.itantra.audio.PcmChunk
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.stt.RecognizedSentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

private const val TAG = "IndicParlerTtsEngine"
private const val BENCH_TAG = "TTS_BENCH"

class IndicParlerTtsEngine(
    private val context: Context,
    private val modelManager: ModelManager,
    private val audioPlayer: AudioPlayer = AudioPlayer()
) : TextToSpeechEngine {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.Default
        )

    @Volatile
    private var currentJob: Job? = null

    private val replayLock =
        Any()

    private val fallbackSystemTts: TextToSpeechEngine =
        AndroidTtsEngine(context)

    private val replayCache =
        object :
            LinkedHashMap<Int, CachedAudio>(
                16,
                0.75f,
                true
            ) {

            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, CachedAudio>
            ): Boolean {
                return size > 20
            }
        }

    private val fallbackReplayCache =
        object :
            LinkedHashMap<Int, RecognizedSentence>(
                16,
                0.75f,
                true
            ) {

            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, RecognizedSentence>
            ): Boolean {
                return size > 20
            }
        }

    @Volatile
    private var isShutdown = false

    @Volatile
    private var isPlayingAlert = false

    // -------------------------------------------------------------------------
    // SPEAK
    // -------------------------------------------------------------------------

    override fun speak(
        sentence: RecognizedSentence
    ) {

        if (isShutdown) {

            Log.w(
                TAG,
                "[TTS] speak() called after shutdown"
            )

            return
        }

        val t0 =
            when {
                sentence.receivedTimestampMs > 0L ->
                    sentence.receivedTimestampMs

                sentence.timestampMs > 0L ->
                    sentence.timestampMs

                else ->
                    System.currentTimeMillis()
            }

        val t1 =
            System.currentTimeMillis()

        if (sentence.isAlert) {

            /*
             * Emergency alert has priority.
             */
            currentJob?.cancel()

            audioPlayer.stop()

            isPlayingAlert = true

            currentJob =
                scope.launch(Dispatchers.Default) {

                    try {

                        synthesizeAndPlay(
                            sentence = sentence,
                            t0 = t0,
                            t1 = t1
                        )

                    } finally {

                        isPlayingAlert = false
                    }
                }

            return
        }

        /*
         * Normal PTT speech must not interrupt an emergency alert.
         */
        if (isPlayingAlert) {

            Log.w(
                TAG,
                "[TTS] Normal speech ignored while alert is active"
            )

            return
        }

        /*
         * Queue normal utterances sequentially.
         *
         * This prevents a second received transcript from cancelling
         * the first sentence halfway through.
         */
        val previousJob =
            currentJob

        currentJob =
            scope.launch(Dispatchers.Default) {

                if (
                    previousJob != null &&
                    previousJob.isActive
                ) {

                    Log.d(
                        TAG,
                        "[TTS] Waiting for previous speech job"
                    )

                    try {
                        previousJob.join()
                    } catch (_: Throwable) {
                    }
                }

                synthesizeAndPlay(
                    sentence = sentence,
                    t0 = t0,
                    t1 = t1
                )
            }
    }

    // -------------------------------------------------------------------------
    // SYNTHESIS
    // -------------------------------------------------------------------------

    private suspend fun synthesizeAndPlay(
        sentence: RecognizedSentence,
        t0: Long,
        t1: Long
    ) {

        Log.d(
            TAG,
            "[TTS] REQUEST_STARTED " +
                    "seq=${sentence.sequenceId} " +
                    "lang=${sentence.languageCode} " +
                    "alert=${sentence.isAlert}"
        )

        val expectedLanguage =
            sentence.languageCode
                .ifBlank { "hi" }

        var tts =
            modelManager.getTtsEngine()

        /*
         * Load the correct language model only when necessary.
         */
        if (
            tts == null ||
            modelManager.currentTtsLang !=
            expectedLanguage
        ) {

            Log.d(
                TAG,
                "[TTS] Loading TTS model " +
                        "language=$expectedLanguage"
            )

            tts =
                modelManager.loadTtsModel(
                    expectedLanguage
                )
        }

        val t2 =
            System.currentTimeMillis()

        Log.d(
            TAG,
            "[TTS] MODEL_READY " +
                    "in=${t2 - t1}ms"
        )

        /*
         * If the local engine cannot be obtained, fall back.
         */
        if (
            tts == null ||
            modelManager.ttsState.value
                    is ModelState.Error
        ) {

            Log.w(
                TAG,
                "[TTS] Local TTS unavailable; using fallback"
            )

            playSystemTtsFallback(
                sentence,
                expectedLanguage,
                t0,
                t1,
                t2
            )

            return
        }

        val acquiredTts =
            modelManager
                .acquireTtsForInference()

        if (acquiredTts == null) {

            Log.w(
                TAG,
                "[TTS] acquireTtsForInference() returned null — fresh load or model error"
            )

            playSystemTtsFallback(
                sentence,
                expectedLanguage,
                t0,
                t1,
                t2
            )

            return
        } else {
            Log.d(
                TAG,
                "[TTS] acquireTtsForInference() CACHE HIT — ONNX TTS session warm (lang=$expectedLanguage)"
            )
        }

        try {

            val speakerId =
                modelManager.currentTtsSpeakerId

            val sampleRate =
                acquiredTts.sampleRate()

            val segments =
                TextSegmenter.segment(
                    sentence.text
                )

            if (segments.isEmpty()) {
                return
            }

            val t3 =
                System.currentTimeMillis()

            Log.d(
                TAG,
                "[TTS] SYNTHESIS_STARTED " +
                        "speakerId=$speakerId " +
                        "segments=${segments.size}"
            )

            /*
             * Bounded queue.
             *
             * 4 chunks is enough to provide look-ahead without allowing
             * unlimited PCM to accumulate in RAM.
             *
             * The queue also creates natural backpressure if synthesis
             * gets significantly ahead of playback.
             */
            val pcmChannel =
                Channel<PcmChunk>(
                    capacity = 4
                )

            /*
             * Consumer starts BEFORE producer synthesis.
             *
             * This is important for first-audio latency.
             */
            val playbackJob =
                scope.launch(Dispatchers.IO) {

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

            val accumulatedChunks =
                mutableListOf<FloatArray>()

            var totalGeneratedSamples = 0

            try {

                /*
                 * PRODUCER
                 *
                 * Generate clauses sequentially.
                 *
                 * Because the consumer is already running, the next clause
                 * can be synthesized while the previous clause is playing.
                 */
                for (
                (index, segment)
                in segments.withIndex()
                ) {

                    coroutineContext.ensureActive()

                    val chunkNumber =
                        index + 1

                    Log.d(
                        TAG,
                        "[TTS] CHUNK $chunkNumber START " +
                                "\"$segment\""
                    )

                    val chunkStart =
                        System.currentTimeMillis()

                    val generated = if (chunkNumber == 1) {
                        /*
                         * Watchdog: Chunk 1 must produce audio within 1200ms budget.
                         * If exceeded, withTimeout throws TimeoutCancellationException,
                         * triggering immediate fallback to Android TTS.
                         */
                        withTimeout(1200L) {
                            acquiredTts.generate(
                                segment,
                                speakerId,
                                1.0f
                            )
                        }
                    } else {
                        acquiredTts.generate(
                            segment,
                            speakerId,
                            1.0f
                        )
                    }

                    val chunkTime =
                        System.currentTimeMillis() -
                                chunkStart

                    Log.d(
                        TAG,
                        "[TTS] CHUNK $chunkNumber END " +
                                "took=${chunkTime}ms"
                    )

                    val samples =
                        generated.samples

                    if (samples.isEmpty()) {
                        continue
                    }

                    totalGeneratedSamples +=
                        samples.size

                    /*
                     * Keep a copy specifically for Replay.
                     *
                     * The original generated array is sent directly
                     * to the playback queue to avoid another large copy.
                     */
                    accumulatedChunks +=
                        samples.copyOf()

                    Log.d(
                        TAG,
                        "[AUDIO] CHUNK $chunkNumber QUEUED " +
                                "samples=${samples.size}"
                    )

                    /*
                     * If the consumer fails, do not block forever trying
                     * to send to its dead queue.
                     */
                    if (!playbackJob.isActive) {

                        throw IllegalStateException(
                            "TTS playback consumer stopped unexpectedly"
                        )
                    }

                    pcmChannel.send(
                        PcmChunk(
                            chunkNumber =
                                chunkNumber,
                            samples =
                                samples,
                            isEos =
                                false
                        )
                    )
                }

                /*
                 * Explicit End Of Stream.
                 */
                Log.d(
                    TAG,
                    "[AUDIO] EOS QUEUED " +
                            "segments=${segments.size} " +
                            "generatedSamples=$totalGeneratedSamples"
                )

                if (playbackJob.isActive) {

                    pcmChannel.send(
                        PcmChunk(
                            chunkNumber =
                                segments.size + 1,
                            samples =
                                FloatArray(0),
                            isEos =
                                true
                        )
                    )
                }

                /*
                 * Save COMPLETE synthesized audio for Replay.
                 */
                if (
                    totalGeneratedSamples > 0
                ) {

                    val completePcm =
                        FloatArray(
                            totalGeneratedSamples
                        )

                    var offset = 0

                    for (chunk in accumulatedChunks) {

                        System.arraycopy(
                            chunk,
                            0,
                            completePcm,
                            offset,
                            chunk.size
                        )

                        offset +=
                            chunk.size
                    }

                    synchronized(replayLock) {

                        replayCache[
                            sentence.sequenceId
                        ] =
                            CachedAudio(
                                samples =
                                    completePcm,
                                sampleRate =
                                    sampleRate,
                                isAlert =
                                    sentence.isAlert
                            )
                    }
                }

                /*
                 * Wait until the consumer has completed the COMPLETE
                 * utterance.
                 */
                playbackJob.join()

            } catch (e: CancellationException) {

                if (e is TimeoutCancellationException) {
                    Log.w(
                        TAG,
                        "[TTS] Watchdog cancellation caught; switching to Android TTS fallback"
                    )

                    try {
                        pcmChannel.close()
                    } catch (_: Throwable) {
                    }

                    playbackJob.cancelAndJoin()

                    audioPlayer.stop()

                    playSystemTtsFallback(
                        sentence,
                        expectedLanguage,
                        t0,
                        t1,
                        t2
                    )

                    return
                }

                Log.d(
                    TAG,
                    "[TTS] Streaming synthesis cancelled"
                )

                try {
                    pcmChannel.close()
                } catch (_: Throwable) {
                }

                playbackJob.cancelAndJoin()

                audioPlayer.stop()

                throw e

            } catch (e: Throwable) {

                Log.e(
                    TAG,
                    "[TTS] Streaming synthesis failed",
                    e
                )

                try {
                    pcmChannel.close()
                } catch (_: Throwable) {
                }

                playbackJob.cancelAndJoin()

                audioPlayer.stop()

                /*
                 * Fallback to System TTS only after the local TTS
                 * stream has been safely stopped.
                 */
                playSystemTtsFallback(
                    sentence,
                    expectedLanguage,
                    t0,
                    t1,
                    t2
                )
            }

        } finally {

            modelManager
                .releaseTtsForInference()
        }
    }

    // -------------------------------------------------------------------------
    // FALLBACK
    // -------------------------------------------------------------------------

    private fun playSystemTtsFallback(
        sentence: RecognizedSentence,
        languageCode: String,
        t0: Long,
        t1: Long,
        t2: Long
    ) {

        Log.w(
            TAG,
            "[TTS] Using Android System TTS fallback " +
                    "lang=$languageCode " +
                    "alert=${sentence.isAlert}"
        )

        synchronized(replayLock) {

            fallbackReplayCache[
                sentence.sequenceId
            ] = sentence
        }

        val start =
            System.currentTimeMillis()

        fallbackSystemTts.speak(
            RecognizedSentence(
                text =
                    sentence.text,
                languageCode =
                    languageCode,
                isAlert =
                    sentence.isAlert,
                timestampMs =
                    sentence.timestampMs,
                sequenceId =
                    sentence.sequenceId
            )
        )

        val end =
            System.currentTimeMillis()

        Log.i(
            BENCH_TAG,
            "[TTS_BENCH] " +
                    "T0_RECEIVED=$t0 " +
                    "T1_REQUESTED=$t1 " +
                    "T2_MODEL_READY=$t2 " +
                    "T3_SYNTHESIS_START=$start " +
                    "T4_FIRST_AUDIO=$start " +
                    "T5_PLAYBACK_START=$start " +
                    "T6_SYNTHESIS_END=$end " +
                    "T7_PLAYBACK_END=$end " +
                    "FIRST_AUDIBLE_LATENCY_MS=${start - t0} " +
                    "TOTAL_LATENCY_MS=${end - t0}"
        )
    }

    // -------------------------------------------------------------------------
    // PREWARM
    // -------------------------------------------------------------------------

    override fun prewarm(
        languageCode: String
    ) {

        if (isShutdown) {
            return
        }

        val lang = languageCode.ifBlank { "hi" }

        scope.launch(Dispatchers.Default) {

            try {
                val prewarmStart = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "[TTS] Prewarm started for language=$lang"
                )

                val tts =
                    modelManager.loadTtsModel(lang)

                val sampleRate =
                    tts?.sampleRate() ?: 24000

                /*
                 * Prewarm the audio path with exact model sample rate.
                 */
                audioPlayer.prewarm(
                    sampleRate = sampleRate,
                    isAlert = false
                )

                /*
                 * Run 1 dummy inference to prime ONNX Runtime memory pools,
                 * thread dispatchers, and raise CPU frequency governor.
                 */
                if (tts != null) {
                    val acquired =
                        modelManager.acquireTtsForInference()

                    if (acquired != null) {
                        try {
                            val dummyStart = System.currentTimeMillis()
                            acquired.generate(
                                ".",
                                modelManager.currentTtsSpeakerId,
                                1.0f
                            )
                            Log.d(
                                TAG,
                                "[TTS] Prewarm dummy inference took=" +
                                        "${System.currentTimeMillis() - dummyStart}ms"
                            )
                        } finally {
                            modelManager.releaseTtsForInference()
                        }
                    }
                }

                Log.d(
                    TAG,
                    "[TTS] Prewarm completed for lang=$lang in " +
                            "${System.currentTimeMillis() - prewarmStart}ms " +
                            "(sampleRate=$sampleRate)"
                )

            } catch (e: Throwable) {

                Log.e(
                    TAG,
                    "[TTS] Prewarm failed",
                    e
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // REPLAY
    // -------------------------------------------------------------------------

    override fun replay(
        sequenceId: Int
    ): Boolean {

        /*
         * 1. Prefer locally cached synthesized PCM.
         */
        val cachedAudio =
            synchronized(replayLock) {
                replayCache[sequenceId]
            }

        if (cachedAudio != null) {

            Log.d(
                TAG,
                "[TTS] Replay cached local audio " +
                        "seq=$sequenceId"
            )

            scope.launch(Dispatchers.IO) {

                audioPlayer.playAudio(
                    pcmData =
                        cachedAudio.samples,
                    sampleRate =
                        cachedAudio.sampleRate,
                    isAlert =
                        cachedAudio.isAlert
                )
            }

            return true
        }

        /*
         * 2. Fallback cache.
         */
        val cachedSentence =
            synchronized(replayLock) {
                fallbackReplayCache[sequenceId]
            }

        if (cachedSentence != null) {

            Log.d(
                TAG,
                "[TTS] Replay System TTS " +
                        "seq=$sequenceId"
            )

            fallbackSystemTts.speak(
                cachedSentence
            )

            return true
        }

        Log.w(
            TAG,
            "[TTS] No replay cache for seq=$sequenceId"
        )

        return false
    }

    // -------------------------------------------------------------------------
    // SHUTDOWN
    // -------------------------------------------------------------------------

    override fun shutdown() {

        if (isShutdown) {
            return
        }

        Log.d(
            TAG,
            "[TTS] Shutting down"
        )

        isShutdown = true

        currentJob?.cancel()

        synchronized(replayLock) {
            replayCache.clear()
            fallbackReplayCache.clear()
        }

        audioPlayer.stop()

        fallbackSystemTts.shutdown()
    }

    // -------------------------------------------------------------------------
    // CACHE MODEL
    // -------------------------------------------------------------------------

    private data class CachedAudio(
        val samples: FloatArray,
        val sampleRate: Int,
        val isAlert: Boolean
    )

    // -------------------------------------------------------------------------
    // PHASE 0 — ISOLATED TTS BENCHMARK (debug only)
    // -------------------------------------------------------------------------

    /**
     * Runs a pure synthesis benchmark with no network, STT, floor control,
     * or audio playback involved.
     *
     * Loads the model for [lang], then runs [tts.generate()] [runs] times
     * on the same [text], logging per-run:
     *   TTS_ISO run=N gen=Xms samples=Y RTF=Z
     *
     * Call from an ADB broadcast receiver or debug button.
     */
    fun runIsolatedBenchmark(
        lang: String = "hi",
        text: String = "हम आपात स्थिति में हैं",
        runs: Int = 5,
        threads: Int? = null
    ) {
        scope.launch(Dispatchers.Default) {
            Log.i("TTS_ISO", "=== ISOLATED TTS BENCHMARK START ===")
            if (threads != null && threads > 0 && threads != modelManager.numThreads) {
                Log.i("TTS_ISO", "Switching threads from ${modelManager.numThreads} to $threads")
                modelManager.numThreads = threads
                modelManager.unloadTts()
            }
            Log.i("TTS_ISO", "lang=$lang text=\"$text\" runs=$runs threads=${modelManager.numThreads}")

            // 1. Load model
            val loadStart = System.currentTimeMillis()
            val tts = modelManager.loadTtsModel(lang)
            val loadMs = System.currentTimeMillis() - loadStart
            Log.i("TTS_ISO", "MODEL_LOAD took=${loadMs}ms")

            if (tts == null) {
                Log.e("TTS_ISO", "ABORT — loadTtsModel($lang) returned null")
                return@launch
            }

            val speakerId = modelManager.currentTtsSpeakerId
            val sampleRate = tts.sampleRate()
            Log.i("TTS_ISO", "sampleRate=$sampleRate speakerId=$speakerId")

            // 2. Acquire for inference
            val acquired = modelManager.acquireTtsForInference()
            if (acquired == null) {
                Log.e("TTS_ISO", "ABORT — acquireTtsForInference() returned null")
                return@launch
            }

            try {
                for (run in 1..runs) {
                    val genStart = System.currentTimeMillis()
                    val result = acquired.generate(text, speakerId, 1.0f)
                    val genMs = System.currentTimeMillis() - genStart
                    val samples = result.samples.size
                    val audioDurationMs = if (sampleRate > 0) (samples * 1000L) / sampleRate else 0L
                    val rtf = if (audioDurationMs > 0) genMs.toFloat() / audioDurationMs else 0f
                    Log.i(
                        "TTS_ISO",
                        "run=$run gen=${genMs}ms samples=$samples audio=${audioDurationMs}ms RTF=${"%.3f".format(rtf)}"
                    )
                }
            } finally {
                modelManager.releaseTtsForInference()
            }

            Log.i("TTS_ISO", "=== ISOLATED TTS BENCHMARK END ===")
        }
    }
}