package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "AudioPlayer"
private const val BENCH_TAG = "TTS_BENCH"

/**
 * A single generated TTS PCM chunk.
 *
 * samples are Float32 in [-1, 1].
 */
data class PcmChunk(
    val chunkNumber: Int,
    val samples: FloatArray,
    val isEos: Boolean = false
)

class AudioPlayer(
    private val context: Context? = null
) {

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

    @Volatile
    private var currentSampleRate = 0

    @Volatile
    private var currentUsage = 0

    @Volatile
    private var rxFrameCount = 0L

    // TTS streaming state.
    @Volatile
    private var isTtsStreamActive = false

    @Volatile
    private var ttsTotalFramesWritten = 0L

    @Volatile
    private var ttsInitialHeadPosition = 0L

    @Volatile
    private var ttsPlaybackStartedReported = false

    /**
     * All current TTS output is deliberately PCM16.
     *
     * This avoids OEM-specific PCM_FLOAT problems and also makes
     * Phone Mode/TTS output format handling consistent.
     */
    @Volatile
    private var isFloatEncoding = false

    private val lock = Any()

    /**
     * Ensures audio focus, audible volume check/boost, and phone loudspeaker routing.
     */
    fun configureSpeakerOutput(isAlert: Boolean = false) {
        val ctx = context ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // 1. Audio Focus
            val usage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            val contentType = if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH
            val streamType = if (isAlert) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(
                    if (isAlert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                ).build()
                val focusResult = am.requestAudioFocus(focusReq)
                Log.d(TAG, "[AUDIO] requestAudioFocus result=$focusResult")
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    streamType,
                    if (isAlert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }

            // 2. Volume check & safety boost for testing
            val currentVol = am.getStreamVolume(streamType)
            val maxVol = am.getStreamMaxVolume(streamType)
            val isMute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.isStreamMute(streamType) else (currentVol == 0)

            Log.i(TAG, "[AUDIO] Volume check: stream=$streamType, current=$currentVol/$maxVol, muted=$isMute")
            if (currentVol == 0 || isMute || currentVol < maxVol / 3) {
                val safeVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
                Log.w(TAG, "[AUDIO] Volume was low/muted ($currentVol/$maxVol) — boosting to $safeVol/$maxVol for audible playback")
                am.setStreamVolume(streamType, safeVol, 0)
            }

            // 3. Force loudspeaker output
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    val ok = am.setCommunicationDevice(speaker)
                    Log.i(TAG, "[AUDIO] setCommunicationDevice(TYPE_BUILTIN_SPEAKER): $ok")
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }

            val modeStr = when (am.mode) {
                AudioManager.MODE_NORMAL -> "MODE_NORMAL"
                AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
                AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
                else -> "MODE_${am.mode}"
            }
            Log.i(TAG, "[AUDIO] ROUTE: mode=$modeStr, speakerphone=${am.isSpeakerphoneOn}, streamVolume=${am.getStreamVolume(streamType)}/$maxVol")
        } catch (e: Throwable) {
            Log.w(TAG, "[AUDIO] configureSpeakerOutput error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // PREWARM
    // -------------------------------------------------------------------------

    /**
     * Prepares an AudioTrack so the first incoming TTS chunk does not
     * need to pay full AudioTrack allocation/mixer setup cost.
     */
    fun prewarm(
        sampleRate: Int = 22050,
        isAlert: Boolean = false
    ) {
        synchronized(lock) {

            val targetUsage =
                if (isAlert) {
                    AudioAttributes.USAGE_ALARM
                } else {
                    AudioAttributes.USAGE_MEDIA
                }

            val existing = audioTrack

            if (
                existing != null &&
                existing.state == AudioTrack.STATE_INITIALIZED &&
                currentSampleRate == sampleRate &&
                currentUsage == targetUsage
            ) {
                return
            }

            stopInternal()

            try {
                initTrackLocked(
                    sampleRate = sampleRate,
                    targetUsage = targetUsage,
                    isAlert = isAlert
                )

                Log.d(
                    TAG,
                    "[AUDIO] Prewarmed AudioTrack " +
                            "sampleRate=$sampleRate usage=$targetUsage"
                )
            } catch (e: Throwable) {
                Log.e(
                    TAG,
                    "[AUDIO] AudioTrack prewarm failed",
                    e
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // TTS STREAM
    // -------------------------------------------------------------------------

    /**
     * Starts a progressive TTS stream.
     *
     * A prewarmed AudioTrack is reused when possible.
     * It is NOT recreated for every sentence unless reuse fails.
     */
    fun startTtsStream(
        sampleRate: Int,
        isAlert: Boolean = false
    ): Boolean {

        configureSpeakerOutput(isAlert)

        val targetUsage =
            if (isAlert) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_MEDIA
            }

        synchronized(lock) {

            val existing = audioTrack

            val matchingTrack =
                existing != null &&
                        existing.state == AudioTrack.STATE_INITIALIZED &&
                        currentSampleRate == sampleRate &&
                        currentUsage == targetUsage

            if (matchingTrack) {

                try {
                    /*
                     * The previous utterance has already completed.
                     *
                     * Reuse the AudioTrack but clear any old queued PCM.
                     */
                    if (existing!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        existing.pause()
                    }

                    existing.flush()

                    ttsInitialHeadPosition =
                        existing.playbackHeadPosition
                            .toLong() and 0xFFFFFFFFL

                } catch (e: Throwable) {

                    Log.w(
                        TAG,
                        "[AUDIO] Existing AudioTrack could not be reused. " +
                                "Recreating it.",
                        e
                    )

                    stopInternal()

                    try {
                        initTrackLocked(
                            sampleRate,
                            targetUsage,
                            isAlert
                        )
                    } catch (initError: Throwable) {
                        Log.e(
                            TAG,
                            "[AUDIO] Failed to recreate TTS AudioTrack",
                            initError
                        )
                        return false
                    }
                }

            } else {

                stopInternal()

                try {
                    initTrackLocked(
                        sampleRate,
                        targetUsage,
                        isAlert
                    )
                } catch (e: Throwable) {
                    Log.e(
                        TAG,
                        "[AUDIO] Failed to initialize TTS AudioTrack",
                        e
                    )
                    return false
                }
            }

            val track = audioTrack

            if (
                track == null ||
                track.state != AudioTrack.STATE_INITIALIZED
            ) {
                Log.e(
                    TAG,
                    "[AUDIO] TTS AudioTrack unavailable"
                )
                return false
            }

            isPlaying = true
            isTtsStreamActive = true
            ttsTotalFramesWritten = 0L
            ttsPlaybackStartedReported = false

            ttsInitialHeadPosition =
                track.playbackHeadPosition
                    .toLong() and 0xFFFFFFFFL

            return true
        }
    }

    /**
     * Writes one TTS chunk into the active TTS stream.
     *
     * This method guarantees that a partial AudioTrack write is completed
     * before returning whenever possible.
     */
    fun writeTtsChunk(
        pcmData: FloatArray,
        chunkNumber: Int = 1,
        onFirstAudioPlayed: ((Long) -> Unit)? = null
    ): Int {

        if (pcmData.isEmpty()) {
            return 0
        }

        val track: AudioTrack
        val shouldUseFloat: Boolean

        synchronized(lock) {

            track = audioTrack ?: return 0

            if (
                !isTtsStreamActive ||
                track.state != AudioTrack.STATE_INITIALIZED
            ) {
                return 0
            }

            shouldUseFloat = isFloatEncoding
        }

        return try {

            /*
             * Start the track before writing if it is not currently playing.
             */
            ensureTrackPlaying(track)

            val written =
                if (shouldUseFloat) {
                    writeFloatsFully(track, pcmData)
                } else {
                    val shorts = floatToPcm16(pcmData)
                    writeShortsFully(track, shorts)
                }

            if (written > 0) {

                var firstPlaybackTimestamp = 0L

                synchronized(lock) {
                    ttsTotalFramesWritten += written.toLong()

                    if (!ttsPlaybackStartedReported) {
                        ttsPlaybackStartedReported = true
                        firstPlaybackTimestamp =
                            System.currentTimeMillis()

                        Log.d(
                            TAG,
                            "[AUDIO] T5_PLAYBACK_STARTED " +
                                    "at=$firstPlaybackTimestamp"
                        )
                    }
                }

                if (firstPlaybackTimestamp > 0L) {
                    onFirstAudioPlayed?.invoke(
                        firstPlaybackTimestamp
                    )
                }

                Log.d(
                    TAG,
                    "[AUDIO] CHUNK $chunkNumber WRITTEN " +
                            "($written/${pcmData.size} samples)"
                )
            }

            written

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "[AUDIO] writeTtsChunk failed",
                e
            )

            0
        }
    }

    /**
     * Waits for all currently written TTS samples to physically drain.
     *
     * IMPORTANT:
     * playState is NOT treated as the end condition because an AudioTrack
     * can leave PLAYING state during an underrun while more chunks are still
     * expected.
     */
    suspend fun awaitTtsCompletion() =
        withContext(Dispatchers.IO) {

            val track =
                synchronized(lock) {
                    audioTrack
                } ?: return@withContext

            val targetHead =
                synchronized(lock) {
                    (
                            ttsInitialHeadPosition and
                                    0xFFFFFFFFL
                            ) + ttsTotalFramesWritten
                }

            val sampleRate =
                synchronized(lock) {
                    currentSampleRate
                }

            val startTime =
                System.currentTimeMillis()

            val maxWaitMs =
                if (sampleRate > 0) {
                    (
                            ttsTotalFramesWritten * 1000L /
                                    sampleRate
                            ) + 3000L
                } else {
                    7000L
                }

            while (
                isPlaying &&
                isTtsStreamActive
            ) {

                val currentHead =
                    track.playbackHeadPosition
                        .toLong() and 0xFFFFFFFFL

                if (currentHead >= targetHead) {
                    break
                }

                if (
                    track.playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {
                    ensureTrackPlaying(track)
                }

                if (
                    System.currentTimeMillis() -
                    startTime >
                    maxWaitMs
                ) {
                    Log.w(
                        TAG,
                        "[AUDIO] awaitTtsCompletion timeout " +
                                "head=$currentHead target=$targetHead"
                    )
                    break
                }

                delay(10L)
            }
        }

    // -------------------------------------------------------------------------
    // ONE-SHOT REPLAY
    // -------------------------------------------------------------------------

    /**
     * Plays a complete cached sentence.
     *
     * Replay is intentionally kept separate from progressive TTS streaming.
     */
    suspend fun playAudio(
        pcmData: FloatArray,
        sampleRate: Int,
        isAlert: Boolean = false,
        onPlaybackStarted: ((Long, Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {

        if (pcmData.isEmpty()) {
            Log.w(
                TAG,
                "[AUDIO] playAudio: empty PCM"
            )
            return@withContext
        }

        configureSpeakerOutput(isAlert)

        val targetUsage =
            if (isAlert) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_MEDIA
            }

        val track: AudioTrack

        synchronized(lock) {

            /*
             * Replay is allowed to replace an old stream.
             */
            stopInternal()

            try {
                initTrackLocked(
                    sampleRate = sampleRate,
                    targetUsage = targetUsage,
                    isAlert = isAlert
                )
            } catch (e: Throwable) {
                Log.e(
                    TAG,
                    "[AUDIO] Failed to create replay AudioTrack",
                    e
                )
                return@withContext
            }

            track =
                audioTrack ?: return@withContext

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {
                Log.e(
                    TAG,
                    "[AUDIO] Replay AudioTrack not initialized"
                )
                return@withContext
            }

            isPlaying = true
        }

        try {

            val t5 =
                System.currentTimeMillis()

            ensureTrackPlaying(track)

            val initialHead =
                track.playbackHeadPosition
                    .toLong() and 0xFFFFFFFFL

            val written =
                if (isFloatEncoding) {
                    writeFloatsFully(
                        track,
                        pcmData
                    )
                } else {
                    writeShortsFully(
                        track,
                        floatToPcm16(pcmData)
                    )
                }

            val t6 =
                System.currentTimeMillis()

            Log.d(
                TAG,
                "[AUDIO] Replay wrote " +
                        "$written/${pcmData.size} samples"
            )

            onPlaybackStarted?.invoke(
                t5,
                t6
            )

            val targetHead =
                initialHead + written.toLong()

            val timeout =
                if (sampleRate > 0) {
                    (
                            written * 1000L /
                                    sampleRate
                            ) + 3000L
                } else {
                    7000L
                }

            val waitStart =
                System.currentTimeMillis()

            while (isPlaying) {

                val currentHead =
                    track.playbackHeadPosition
                        .toLong() and 0xFFFFFFFFL

                if (currentHead >= targetHead) {
                    break
                }

                if (
                    track.playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {
                    ensureTrackPlaying(track)
                }

                if (
                    System.currentTimeMillis() -
                    waitStart > timeout
                ) {
                    Log.w(
                        TAG,
                        "[AUDIO] Replay playback timeout"
                    )
                    break
                }

                delay(10L)
            }

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "[AUDIO] playAudio failed",
                e
            )

        } finally {

            synchronized(lock) {
                isPlaying = false
            }

            /*
             * Keep no old replay data queued.
             */
            synchronized(lock) {
                audioTrack?.let {
                    try {
                        if (
                            it.state ==
                            AudioTrack.STATE_INITIALIZED
                        ) {
                            if (
                                it.playState ==
                                AudioTrack.PLAYSTATE_PLAYING
                            ) {
                                it.stop()
                            }

                            it.flush()
                        }
                    } catch (e: Throwable) {
                        Log.w(
                            TAG,
                            "[AUDIO] Replay cleanup failed",
                            e
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // PHONE MODE RX
    // -------------------------------------------------------------------------

    /**
     * Starts Phone Mode receive playback.
     *
     * Phone Mode uses PCM16.
     */
    fun startStream(
        sampleRate: Int = 16000
    ): Boolean {

        configureSpeakerOutput(false)

        stopInternal()

        rxFrameCount = 0L

        synchronized(lock) {

            val minBuffer =
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (
                minBuffer <= 0 ||
                minBuffer ==
                AudioTrack.ERROR ||
                minBuffer ==
                AudioTrack.ERROR_BAD_VALUE
            ) {
                Log.e(
                    "CALL",
                    "[CALL][RX][ERROR] Invalid AudioTrack buffer " +
                            "for sampleRate=$sampleRate"
                )
                return false
            }

            /*
             * ~250ms-class buffering depending on the platform's
             * minimum buffer size.
             *
             * This is intended to absorb wireless jitter.
             */
            val bufferSize =
                maxOf(
                    minBuffer * 4,
                    sampleRate / 4 * 2
                )

            val usages =
                intArrayOf(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioAttributes.USAGE_MEDIA
                )

            var createdTrack: AudioTrack? = null

            for (usage in usages) {

                try {

                    val candidate =
                        AudioTrack(
                            AudioAttributes.Builder()
                                .setUsage(usage)
                                .setContentType(
                                    AudioAttributes
                                        .CONTENT_TYPE_SPEECH
                                )
                                .build(),
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setEncoding(
                                    AudioFormat
                                        .ENCODING_PCM_16BIT
                                )
                                .setChannelMask(
                                    AudioFormat
                                        .CHANNEL_OUT_MONO
                                )
                                .build(),
                            bufferSize,
                            AudioTrack.MODE_STREAM,
                            AudioManager
                                .AUDIO_SESSION_ID_GENERATE
                        )

                    if (
                        candidate.state ==
                        AudioTrack.STATE_INITIALIZED
                    ) {
                        createdTrack = candidate
                        currentUsage = usage
                        break
                    }

                    candidate.release()

                } catch (e: Throwable) {

                    Log.w(
                        "CALL",
                        "[CALL][RX] AudioTrack init failed " +
                                "usage=$usage",
                        e
                    )
                }
            }

            if (createdTrack == null) {

                Log.e(
                    "CALL",
                    "[CALL][RX][ERROR] " +
                            "Unable to initialize AudioTrack"
                )

                return false
            }

            audioTrack = createdTrack
            currentSampleRate = sampleRate
            isPlaying = true

            try {
                createdTrack.play()
            } catch (e: Throwable) {
                Log.e(
                    "CALL",
                    "[CALL][RX][ERROR] " +
                            "AudioTrack.play() failed",
                    e
                )

                try {
                    createdTrack.release()
                } catch (_: Throwable) {
                }

                audioTrack = null
                isPlaying = false

                return false
            }

            Log.i(
                "CALL",
                "[CALL][RX] AudioTrack initialized " +
                        "sampleRate=$sampleRate " +
                        "bufferSize=$bufferSize " +
                        "usage=$currentUsage"
            )

            Log.i(
                "CALL",
                "[CALL][RX] PCM receiver started"
            )

            return true
        }
    }

    /**
     * Receives Float32 PCM from the transport and converts it to PCM16
     * before writing to the Phone Mode AudioTrack.
     *
     * This fixes the previous FloatArray -> PCM16 mismatch.
     */
    fun writeStream(
        pcmData: FloatArray
    ) {

        if (!isPlaying || pcmData.isEmpty()) {
            return
        }

        val track =
            synchronized(lock) {
                audioTrack
            } ?: return

        try {

            /*
             * If Android/HAL has stopped playback because the buffer
             * drained, resume it when new PCM arrives.
             */
            ensureTrackPlaying(track)

            val shortData =
                floatToPcm16(pcmData)

            val written =
                writeShortsFully(
                    track,
                    shortData
                )

            if (written > 0) {

                rxFrameCount++

                if (rxFrameCount == 1L) {

                    Log.i(
                        "CALL",
                        "[CALL][RX] First PCM frame received " +
                                "seq=1 size=$written"
                    )

                } else if (
                    rxFrameCount % 100L == 0L
                ) {

                    Log.i(
                        "CALL",
                        "[CALL][RX] Received " +
                                "$rxFrameCount frames"
                    )
                }
            }

        } catch (e: Throwable) {

            Log.e(
                "CALL",
                "[CALL][RX][ERROR] Stream write failed",
                e
            )
        }
    }

    // -------------------------------------------------------------------------
    // TTS PRODUCER / CONSUMER
    // -------------------------------------------------------------------------

    /**
     * Consumes progressive TTS PCM chunks.
     *
     * Architecture:
     *
     * Producer:
     *   TTS synthesis
     *       ↓
     *   Channel<PcmChunk>
     *
     * Consumer:
     *   Channel
     *       ↓
     *   ONE AudioTrack
     *       ↓
     *   speaker
     *
     * AudioTrack is started once and remains the same stream for the
     * complete utterance.
     */
    suspend fun playTtsQueue(
        channel: Channel<PcmChunk>,
        sampleRate: Int,
        isAlert: Boolean,
        t0: Long,
        t1: Long,
        t2: Long,
        t3: Long,
        sequenceId: Int
    ) = withContext(Dispatchers.IO) {

        var totalChunks = 0
        var totalWrittenSamples = 0L

        var t4FirstAudio = 0L
        var t5PlaybackStart = 0L
        var t6PlaybackEnd = 0L
        var t7StreamEnd = 0L

        var eosReceived = false
        var playbackStarted = false

        /*
         * Very small initial look-ahead.
         *
         * The objective is not a large delay.
         * It is only intended to prevent a tiny first chunk from being
         * immediately exhausted on slow hardware.
         */
        val initialPrebufferMs = 300L

        val pendingChunks =
            ArrayDeque<FloatArray>()

        var pendingSamples = 0L

        try {

            val streamStarted =
                startTtsStream(
                    sampleRate = sampleRate,
                    isAlert = isAlert
                )

            if (!streamStarted) {

                Log.e(
                    TAG,
                    "[AUDIO] playTtsQueue: " +
                            "startTtsStream failed"
                )

                return@withContext
            }

            val track =
                synchronized(lock) {
                    audioTrack
                }

            if (
                track == null ||
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                Log.e(
                    TAG,
                    "[AUDIO] playTtsQueue: invalid AudioTrack"
                )

                return@withContext
            }

            fun bufferedDurationMs(): Long {
                if (sampleRate <= 0) {
                    return 0L
                }

                return pendingSamples * 1000L /
                        sampleRate
            }

            fun startPlaybackIfNeeded() {

                if (playbackStarted) {
                    return
                }

                ensureTrackPlaying(track)

                playbackStarted = true

                t5PlaybackStart =
                    System.currentTimeMillis()

                ttsInitialHeadPosition =
                    track.playbackHeadPosition
                        .toLong() and 0xFFFFFFFFL

                while (pendingChunks.isNotEmpty()) {

                    val samples =
                        pendingChunks.removeFirst()

                    if (samples.isEmpty()) {
                        continue
                    }

                    val written =
                        writePcmToTrack(
                            track,
                            samples
                        )

                    if (written <= 0) {

                        Log.e(
                            TAG,
                            "[AUDIO] Initial TTS chunk " +
                                    "write failed"
                        )

                        return
                    }

                    totalWrittenSamples +=
                        written.toLong()

                    pendingSamples -=
                        samples.size.toLong()

                    totalChunks++

                    Log.d(
                        TAG,
                        "[AUDIO] Initial chunk written " +
                                "($written/${samples.size})"
                    )
                }

                Log.d(
                    TAG,
                    "[AUDIO] T5_PLAYBACK_STARTED " +
                            "at=$t5PlaybackStart " +
                            "(in ${t5PlaybackStart - t0}ms) " +
                            "initialHead=$ttsInitialHeadPosition"
                )
            }

            for (chunk in channel) {

                if (chunk.isEos) {

                    eosReceived = true

                    Log.d(
                        TAG,
                        "[AUDIO] EOS received in consumer"
                    )

                    break
                }

                val samples =
                    chunk.samples

                if (samples.isEmpty()) {
                    continue
                }

                if (t4FirstAudio == 0L) {

                    t4FirstAudio =
                        System.currentTimeMillis()

                    Log.d(
                        TAG,
                        "[TTS] T4_FIRST_AUDIO " +
                                "available in " +
                                "${t4FirstAudio - t0}ms"
                    )
                }

                /*
                 * Start playback immediately on the first available chunk.
                 */
                if (!playbackStarted) {

                    pendingChunks.addLast(samples)

                    pendingSamples +=
                        samples.size.toLong()

                    val bufferedMs =
                        bufferedDurationMs()

                    Log.d(
                        TAG,
                        "[AUDIO] CHUNK " +
                                chunk.chunkNumber +
                                " QUEUED " +
                                "(${
                                    samples.size
                                } samples, " +
                                "buffered=${bufferedMs}ms)"
                    )

                    startPlaybackIfNeeded()

                    continue
                }

                /*
                 * Playback already started.
                 *
                 * Write the next chunk directly into the SAME
                 * AudioTrack. Never stop/flush/recreate between chunks.
                 */
                ensureTrackPlaying(track)

                val written =
                    writePcmToTrack(
                        track,
                        samples
                    )

                if (written > 0) {

                    totalChunks++

                    totalWrittenSamples +=
                        written.toLong()

                    Log.d(
                        TAG,
                        "[AUDIO] CHUNK " +
                                chunk.chunkNumber +
                                " WRITTEN " +
                                "($written/${samples.size})"
                    )

                } else {

                    Log.e(
                        TAG,
                        "[AUDIO][ERROR] Failed to write " +
                                "chunk=${chunk.chunkNumber}"
                    )
                }

                val currentHead =
                    track.playbackHeadPosition
                        .toLong() and 0xFFFFFFFFL

                Log.d(
                    TAG,
                    "[AUDIO] PLAY_STATE " +
                            "state=${track.state} " +
                            "playState=${track.playState} " +
                            "PLAYBACK_HEAD=$currentHead"
                )
            }

            /*
             * If EOS came before playback had enough samples to start,
             * flush the final pending data now.
             */
            if (
                !playbackStarted &&
                pendingChunks.isNotEmpty()
            ) {
                startPlaybackIfNeeded()
            }

            if (!eosReceived) {
                Log.w(
                    TAG,
                    "[AUDIO] Channel closed without EOS"
                )
            }

            if (!playbackStarted) {

                Log.w(
                    TAG,
                    "[AUDIO] No playable TTS audio generated"
                )

                return@withContext
            }

            /*
             * Wait until ALL samples already written to AudioTrack
             * have physically drained through the speaker DAC.
             */
            val waitStart =
                System.currentTimeMillis()

            val maxWaitMs =
                if (sampleRate > 0) {
                    (
                            totalWrittenSamples * 1000L /
                                    sampleRate
                            ) + 4000L
                } else {
                    8000L
                }

            Log.d(
                TAG,
                "[AUDIO] EOS WAIT START " +
                        "totalChunks=$totalChunks " +
                        "totalWrittenSamples=$totalWrittenSamples " +
                        "initialHead=$ttsInitialHeadPosition " +
                        "maxWaitMs=$maxWaitMs"
            )

            while (
                isPlaying &&
                isTtsStreamActive
            ) {

                val head =
                    track.playbackHeadPosition
                        .toLong() and 0xFFFFFFFFL

                val playedSamples =
                    (head - ttsInitialHeadPosition) and 0xFFFFFFFFL

                if (playedSamples >= totalWrittenSamples) {
                    Log.d(
                        TAG,
                        "[AUDIO] EOS WAIT COMPLETE: " +
                                "playedSamples=$playedSamples " +
                                "totalWrittenSamples=$totalWrittenSamples " +
                                "in ${System.currentTimeMillis() - waitStart}ms"
                    )
                    break
                }

                /*
                 * If an underrun causes PLAYSTATE to leave PLAYING,
                 * resume playback while samples still remain.
                 */
                if (
                    track.playState !=
                    AudioTrack.PLAYSTATE_PLAYING
                ) {
                    ensureTrackPlaying(track)
                }

                if (
                    System.currentTimeMillis() -
                    waitStart >
                    maxWaitMs
                ) {

                    Log.e(
                        TAG,
                        "[AUDIO][ERROR] EOS wait timeout " +
                                "playedSamples=$playedSamples " +
                                "totalWrittenSamples=$totalWrittenSamples " +
                                "head=$head " +
                                "initialHead=$ttsInitialHeadPosition"
                    )

                    break
                }

                delay(10L)
            }

            t6PlaybackEnd =
                System.currentTimeMillis()

            t7StreamEnd =
                t6PlaybackEnd

            if (t5PlaybackStart == 0L) {
                t5PlaybackStart =
                    t4FirstAudio
                        .takeIf { it > 0L }
                        ?: t6PlaybackEnd
            }

            if (t4FirstAudio == 0L) {
                t4FirstAudio =
                    t5PlaybackStart
            }

            val firstAudibleLatency =
                t5PlaybackStart - t0

            val firstAudioGeneration =
                t4FirstAudio - t0

            val totalLatency =
                t7StreamEnd - t0

            val finalHead =
                track.playbackHeadPosition
                    .toLong() and 0xFFFFFFFFL

            Log.i(
                BENCH_TAG,
                "[TTS_BENCH] " +
                        "T0_RECEIVED=$t0 " +
                        "T1_REQUESTED=$t1 " +
                        "T2_MODEL_READY=$t2 " +
                        "T3_SYNTHESIS_START=$t3 " +
                        "T4_FIRST_AUDIO=$t4FirstAudio " +
                        "T5_PLAYBACK_START=$t5PlaybackStart " +
                        "T6_PLAYBACK_END=$t6PlaybackEnd " +
                        "T7_STREAM_END=$t7StreamEnd " +
                        "FIRST_AUDIBLE_LATENCY_MS=$firstAudibleLatency " +
                        "FIRST_AUDIO_GEN_MS=$firstAudioGeneration " +
                        "TOTAL_LATENCY_MS=$totalLatency " +
                        "TOTAL_WRITTEN_SAMPLES=$totalWrittenSamples " +
                        "FINAL_PLAYBACK_HEAD=$finalHead"
            )

            Log.i(
                TAG,
                "[AUDIO] STREAM_COMPLETED " +
                        "totalChunks=$totalChunks " +
                        "totalWrittenSamples=$totalWrittenSamples " +
                        "finalPlaybackHead=$finalHead " +
                        "AudioTrackState=${track.state} " +
                        "AudioTrackPlayState=${track.playState}"
            )

        } catch (e: kotlinx.coroutines.CancellationException) {

            Log.d(
                TAG,
                "[AUDIO] TTS stream cancelled"
            )

            throw e

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "[AUDIO] playTtsQueue failed",
                e
            )

        } finally {

            /*
             * Closing the channel is important if the consumer exits
             * unexpectedly. It prevents the producer from hanging forever
             * on send().
             */
            try {
                channel.close()
            } catch (_: Throwable) {
            }

            synchronized(lock) {
                isTtsStreamActive = false
                isPlaying = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // PCM CONVERSION / WRITING
    // -------------------------------------------------------------------------

    /**
     * Float32 [-1,1] -> signed PCM16.
     */
    private fun floatToPcm16(
        pcmData: FloatArray
    ): ShortArray {

        val result =
            ShortArray(pcmData.size)

        for (i in pcmData.indices) {

            val sample =
                pcmData[i]
                    .coerceIn(-1.0f, 1.0f)

            result[i] =
                (
                        sample * 32767.0f
                        ).toInt()
                    .toShort()
        }

        return result
    }

    /**
     * Writes a complete ShortArray.
     *
     * AudioTrack.write() is allowed to return fewer samples than requested.
     * We continue until the complete PCM chunk is written.
     */
    private fun writeShortsFully(
        track: AudioTrack,
        data: ShortArray
    ): Int {

        var offset = 0
        var zeroWrites = 0

        while (
            offset < data.size &&
            isPlaying
        ) {

            val written =
                track.write(
                    data,
                    offset,
                    data.size - offset,
                    AudioTrack.WRITE_BLOCKING
                )

            when {

                written > 0 -> {
                    offset += written
                    zeroWrites = 0
                }

                written < 0 -> {
                    return written
                }

                else -> {

                    zeroWrites++

                    if (zeroWrites >= 20) {

                        Log.e(
                            TAG,
                            "[AUDIO] AudioTrack returned 0 " +
                                    "repeatedly"
                        )

                        break
                    }

                    Thread.yield()
                }
            }
        }

        return offset
    }

    /**
     * Writes FloatArray only if a future AudioTrack configuration uses
     * PCM_FLOAT.
     */
    private fun writeFloatsFully(
        track: AudioTrack,
        data: FloatArray
    ): Int {

        var offset = 0
        var zeroWrites = 0

        while (
            offset < data.size &&
            isPlaying
        ) {

            val written =
                track.write(
                    data,
                    offset,
                    data.size - offset,
                    AudioTrack.WRITE_BLOCKING
                )

            when {

                written > 0 -> {
                    offset += written
                    zeroWrites = 0
                }

                written < 0 -> {
                    return written
                }

                else -> {

                    zeroWrites++

                    if (zeroWrites >= 20) {
                        Log.e(
                            TAG,
                            "[AUDIO] Float AudioTrack " +
                                    "returned 0 repeatedly"
                        )
                        break
                    }

                    Thread.yield()
                }
            }
        }

        return offset
    }

    /**
     * Writes Float32 PCM to the active TTS AudioTrack.
     */
    private fun writePcmToTrack(
        track: AudioTrack,
        pcmData: FloatArray
    ): Int {

        if (pcmData.isEmpty()) {
            return 0
        }

        return try {

            if (isFloatEncoding) {

                writeFloatsFully(
                    track,
                    pcmData
                )

            } else {

                writeShortsFully(
                    track,
                    floatToPcm16(pcmData)
                )
            }

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "[AUDIO] writePcmToTrack failed",
                e
            )

            -1
        }
    }

    /**
     * Ensures the AudioTrack is in PLAYING state.
     *
     * This is used only when audio is expected to continue.
     */
    private fun ensureTrackPlaying(
        track: AudioTrack
    ) {

        try {

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {
                return
            }

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {
                track.play()
                Log.d(
                    TAG,
                    "[AUDIO] AudioTrack.play() called, state=${track.state}, playState=${track.playState}"
                )
            }

        } catch (e: Throwable) {

            Log.w(
                TAG,
                "[AUDIO] ensureTrackPlaying failed: " +
                        e.message
            )
        }
    }

    // -------------------------------------------------------------------------
    // TRACK INITIALIZATION
    // -------------------------------------------------------------------------

    private fun initTrackLocked(
        sampleRate: Int,
        targetUsage: Int,
        isAlert: Boolean,
        expectedSamples: Int? = null
    ) {

        val attributes =
            AudioAttributes.Builder()
                .setUsage(targetUsage)
                .setContentType(
                    if (isAlert) {
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    } else {
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    }
                )
                .build()

        /*
         * PCM16 is deliberately used everywhere.
         *
         * It is better supported across older Android/OEM audio HALs.
         */
        val encoding =
            AudioFormat.ENCODING_PCM_16BIT

        isFloatEncoding = false

        var minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                encoding
            )

        if (minBuffer <= 0) {
            minBuffer = sampleRate * 2
        }

        if (minBuffer <= 0) {
            throw IllegalStateException(
                "Invalid AudioTrack buffer size"
            )
        }

        /*
         * Do not allocate the entire sentence as the native track buffer.
         * A bounded buffer is enough; AudioTrack.write() blocks until space
         * becomes available.
         */
        val bytesPerSample = 2

        val desiredBytes =
            if (expectedSamples != null) {
                expectedSamples
                    .coerceAtLeast(1)
                    .toLong()
                    .coerceAtMost(sampleRate.toLong()) *
                        bytesPerSample
            } else {
                sampleRate.toLong() *
                        bytesPerSample
            }

        val bufferSize =
            maxOf(
                minBuffer * 2,
                desiredBytes
                    .coerceAtMost(
                        sampleRate.toLong() *
                                bytesPerSample
                    )
                    .toInt()
            )

        val format =
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO
                )
                .build()

        val track =
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            try {
                track.release()
            } catch (_: Throwable) {
            }

            throw IllegalStateException(
                "AudioTrack initialization failed"
            )
        }

        audioTrack = track
        currentSampleRate = sampleRate
        currentUsage = targetUsage
        isFloatEncoding = false

        Log.i(
            TAG,
            "[AUDIO] AudioTrack initialized " +
                    "sampleRate=$sampleRate " +
                    "encoding=PCM16 " +
                    "bufferSize=$bufferSize " +
                    "usage=$targetUsage"
        )
    }

    // -------------------------------------------------------------------------
    // STOP / CLEANUP
    // -------------------------------------------------------------------------

    fun stop() {

        synchronized(lock) {

            isPlaying = false
            isTtsStreamActive = false

            stopInternal()
        }
    }

    private fun stopInternal() {

        val track =
            audioTrack ?: return

        audioTrack = null
        currentSampleRate = 0
        currentUsage = 0

        try {

            if (
                track.state ==
                AudioTrack.STATE_INITIALIZED
            ) {

                try {
                    if (
                        track.playState ==
                        AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        track.stop()
                    }
                } catch (_: Throwable) {
                }

                try {
                    track.flush()
                } catch (_: Throwable) {
                }
            }

        } finally {

            try {
                track.release()
            } catch (_: Throwable) {
            }

            Log.d(
                TAG,
                "[AUDIO] AudioTrack released"
            )
        }
    }
}