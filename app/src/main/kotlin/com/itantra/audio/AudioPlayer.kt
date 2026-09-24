package com.itantra.audio

import android.media.AudioAttributes
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

data class PcmChunk(
    val chunkNumber: Int,
    val samples: FloatArray,
    val isEos: Boolean = false
)


class AudioPlayer {

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var currentUsage: Int = 0
    @Volatile private var rxFrameCount: Long = 0L

    // Progressive TTS streaming state
    @Volatile private var isTtsStreamActive = false
    @Volatile private var ttsTotalFramesWritten = 0
    @Volatile private var ttsInitialHeadPosition = 0
    @Volatile private var ttsPlaybackStartedReported = false
    @Volatile private var isFloatEncoding = true

    private val lock = Any()

    /**
     * Prewarm an AudioTrack for the given sample rate so the first utterance does not
     * pay the native AudioFlinger / AudioServer IPC allocation overhead (~150-300ms).
     */
    fun prewarm(sampleRate: Int = 22050, isAlert: Boolean = false) {
        synchronized(lock) {
            val targetUsage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                currentSampleRate == sampleRate && currentUsage == targetUsage) {
                return
            }
            try {
                initTrackLocked(sampleRate, targetUsage, isAlert, null)
                Log.d(TAG, "[AUDIO] Prewarmed AudioTrack for sampleRate=$sampleRate usage=$targetUsage")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prewarm AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Starts a progressive TTS streaming playback session.
     * Reuses the prewarmed/active AudioTrack if sample rate and usage match.
     */
    fun startTtsStream(sampleRate: Int, isAlert: Boolean = false): Boolean {
        val targetUsage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        synchronized(lock) {
            // ALWAYS recreate AudioTrack. Legacy devices (API 23) severely corrupt their
            // native mixer state if you pause() and flush() a STREAM track between utterances.
            stopInternal()
            initTrackLocked(sampleRate, targetUsage, isAlert, null)

            val track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "INIT: AudioTrack not available for TTS progressive stream")
                return false
            }

            isPlaying = true
            isTtsStreamActive = true
            ttsTotalFramesWritten = 0
            ttsPlaybackStartedReported = false
            ttsInitialHeadPosition = track.playbackHeadPosition
            return true
        }
    }

    /**
     * Writes an incremental chunk of PCM samples into the active progressive TTS stream.
     * AudioTrack.play() is initiated immediately on the first written chunk so the user hears
     * speech without waiting for full synthesis.
     */
    fun writeTtsChunk(
        pcmData: FloatArray,
        chunkNumber: Int = 1,
        onFirstAudioPlayed: ((t5: Long) -> Unit)? = null
    ): Int {
        if (pcmData.isEmpty()) return 0
        val track: AudioTrack
        val useFloat: Boolean
        synchronized(lock) {
            track = audioTrack ?: return 0
            if (!isTtsStreamActive || track.state != AudioTrack.STATE_INITIALIZED) return 0
            useFloat = isFloatEncoding
        }

        try {
            val written: Int = if (useFloat) {
                track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            } else {
                val shortData = ShortArray(pcmData.size)
                for (i in pcmData.indices) {
                    val s = pcmData[i]
                    val clamped = if (s > 1.0f) 1.0f else if (s < -1.0f) -1.0f else s
                    shortData[i] = (clamped * 32767.0f).toInt().toShort()
                }
                track.write(shortData, 0, shortData.size, AudioTrack.WRITE_BLOCKING)
            }

            if (written > 0) {
                synchronized(lock) {
                    ttsTotalFramesWritten += written
                    try {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "AudioTrack.play() failed during chunk write", e)
                    }
                    if (!ttsPlaybackStartedReported) {
                        ttsPlaybackStartedReported = true
                        val t5 = System.currentTimeMillis()
                        Log.d(TAG, "[AUDIO] T5_PLAYBACK_STARTED at $t5")
                        onFirstAudioPlayed?.invoke(t5)
                    }
                }
                Log.d(TAG, "[AUDIO] CHUNK $chunkNumber WRITTEN ($written samples)")
            }
            return written
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack.write failed in writeTtsChunk", e)
            return 0
        }
    }

    /**
     * Suspends until the AudioTrack has completely finished playing all written samples.
     * Retains the AudioTrack in a warm state for the next utterance.
     */
    suspend fun awaitTtsCompletion() = withContext(Dispatchers.IO) {
        val track = audioTrack ?: return@withContext
        val targetHead: Long
        val sampleRate: Int
        synchronized(lock) {
            targetHead = (ttsInitialHeadPosition.toLong() and 0xFFFFFFFFL) + ttsTotalFramesWritten.toLong()
            sampleRate = currentSampleRate
        }
        val startTime = System.currentTimeMillis()
        val maxWaitMs = if (sampleRate > 0) (ttsTotalFramesWritten * 1000L / sampleRate) + 2000L else 5000L
        try {
            while (isPlaying && isTtsStreamActive) {
                val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (currentHead >= targetHead) {
                    break
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING && isPlaying && isTtsStreamActive) {
                    try {
                        track.play()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to re-assert play() in awaitTtsCompletion: ${e.message}")
                    }
                }
                if (System.currentTimeMillis() - startTime > maxWaitMs) {
                    Log.w(TAG, "awaitTtsCompletion timeout reached (${maxWaitMs}ms)")
                    break
                }
                delay(20)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "awaitTtsCompletion polling error", e)
        } finally {
            synchronized(lock) {
                isTtsStreamActive = false
                isPlaying = false
            }
            Log.d(TAG, "[AUDIO] STREAM_COMPLETED")
        }
    }

    suspend fun playAudio(
        pcmData: FloatArray,
        sampleRate: Int,
        isAlert: Boolean = false,
        onPlaybackStarted: ((t5: Long, t6: Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty()) {
            Log.w(TAG, "playAudio: empty PCM data — skipping")
            return@withContext
        }

        val targetUsage = if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        var track: AudioTrack?

        synchronized(lock) {
            // ALWAYS recreate AudioTrack for legacy device stability
            stopInternal()
            initTrackLocked(sampleRate, targetUsage, isAlert, pcmData.size)

            track = audioTrack
            if (track == null || track?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "INIT: AudioTrack not available for playback")
                return@withContext
            }

            isPlaying = true
        }

        val activeTrack = track ?: return@withContext
        val t5 = System.currentTimeMillis()
        try {
            activeTrack.play()
            Log.d(TAG, "[AUDIO] T5_PLAYBACK_STARTED at $t5")

            // Bulk write pcmData in native call
            val offset = 0
            val writeSize = pcmData.size
            val written = activeTrack.write(pcmData, offset, writeSize, AudioTrack.WRITE_BLOCKING)
            val t6 = System.currentTimeMillis()
            Log.d(TAG, "[AUDIO] T6_FIRST_BUFFER_WRITTEN at $t6 ($written samples)")
            
            onPlaybackStarted?.invoke(t5, t6)

            // Wait for head position to finish playing before returning, but keep the track warm
            val startHead = activeTrack.playbackHeadPosition
            val targetHead = startHead + written
            while (isPlaying && activeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                if (activeTrack.playbackHeadPosition >= targetHead) {
                    break
                }
                delay(20)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer.playAudio failed", e)
        } finally {
            isPlaying = false
        }
        Log.d(TAG, "playAudio complete: ${pcmData.size} samples at ${sampleRate}Hz")
    }

    private fun initTrackLocked(sampleRate: Int, targetUsage: Int, isAlert: Boolean, expectedSamples: Int?) {
        val attributes = AudioAttributes.Builder()
            .setUsage(targetUsage)
            .setContentType(if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // ALWAYS use 16-bit PCM for 100% cross-device compatibility.
        // Float PCM is notoriously glitchy on many Android OEM ROMs and provides zero benefit for voice.
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val isFloat = false
        var minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        
        if (minBuffer <= 0) {
            minBuffer = sampleRate * 2 // Fallback safe buffer
        }

        if (minBuffer <= 0) {
            Log.e(TAG, "INIT: Invalid AudioTrack buffer size for sampleRate=$sampleRate")
            return
        }

        val bytesPerSample = if (isFloat) 4 else 2
        val bufSize = if (expectedSamples != null) {
            maxOf(minBuffer * 2, expectedSamples * bytesPerSample)
        } else {
            maxOf(minBuffer * 2, sampleRate * bytesPerSample)
        }

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        try {
            val track = AudioTrack(
                attributes,
                format,
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "INIT: AudioTrack failed to initialize (state=${track.state})")
                track.release()
                if (isFloat) {
                    initTrack16BitFallbackLocked(sampleRate, targetUsage, attributes, expectedSamples)
                }
                return
            }

            audioTrack = track
            currentSampleRate = sampleRate
            currentUsage = targetUsage
            isFloatEncoding = isFloat
            Log.i(TAG, "INIT: AudioTrack initialized (sampleRate=$sampleRate isFloat=$isFloat bufSize=$bufSize)")
        } catch (e: Throwable) {
            Log.e(TAG, "INIT: AudioTrack creation failed with float, trying 16-bit fallback", e)
            if (isFloat) {
                initTrack16BitFallbackLocked(sampleRate, targetUsage, attributes, expectedSamples)
            }
        }
    }

    private fun initTrack16BitFallbackLocked(sampleRate: Int, targetUsage: Int, attributes: AudioAttributes, expectedSamples: Int?) {
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            val bufSize = if (expectedSamples != null) maxOf(minBuffer * 2, expectedSamples * 2) else maxOf(minBuffer * 2, sampleRate * 2)
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(attributes, format, bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
                currentSampleRate = sampleRate
                currentUsage = targetUsage
                isFloatEncoding = false
                Log.i(TAG, "INIT: AudioTrack initialized with 16-bit PCM fallback")
            } else {
                track.release()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "16-bit fallback initialization failed", e)
        }
    }

    fun startStream(sampleRate: Int = 16000): Boolean {
        stopInternal()
        rxFrameCount = 0L
        synchronized(lock) {
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e("CALL", "[CALL][RX][ERROR] Invalid buffer size for sampleRate=$sampleRate")
                    return false
                }
                
                // Generous buffer to absorb Wi-Fi Direct / Bluetooth packet jitter without starving
                val bufSize = maxOf(minBuffer * 4, 16000)

                // Try VOICE_COMMUNICATION first, fallback to USAGE_MEDIA
                var track: AudioTrack? = null
                val usages = intArrayOf(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_MEDIA)
                for (usage in usages) {
                    try {
                        val candidate = AudioTrack(
                            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                            AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                            bufSize,
                            AudioTrack.MODE_STREAM,
                            AudioManager.AUDIO_SESSION_ID_GENERATE
                        )
                        if (candidate.state == AudioTrack.STATE_INITIALIZED) {
                            track = candidate
                            currentUsage = usage
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack init failed with usage=$usage: ${e.message}")
                    }
                }

                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack = track
                    currentSampleRate = sampleRate
                    isPlaying = true
                    Log.i("CALL", "[CALL][RX] AudioTrack initialized: sampleRate=$sampleRate bufferSize=$bufSize")
                    track.play()
                    Log.i("CALL", "[CALL][RX] PCM receiver started")
                    return true
                } else {
                    Log.e("CALL", "[CALL][RX][ERROR] Failed to initialize AudioTrack for sampleRate=$sampleRate")
                    return false
                }
            } catch (e: Exception) {
                Log.e("CALL", "[CALL][RX][ERROR] Exception starting AudioTrack: ${e.message}", e)
                return false
            }
        }
    }

    fun writeStream(pcmData: FloatArray) {
        if (!isPlaying) return
        val track = audioTrack ?: return
        try {
            val written = track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_NON_BLOCKING)
            rxFrameCount++
            if (rxFrameCount == 1L) {
                Log.i("CALL", "[CALL][RX] First PCM frame received seq=1 size=${pcmData.size}")
            } else if (rxFrameCount % 100L == 0L) {
                Log.i("CALL", "[CALL][RX] Received $rxFrameCount frames")
            }
            Log.v(TAG, "WRITE: Stream wrote $written samples")
        } catch (e: Exception) {
            Log.e("CALL", "[CALL][RX][ERROR] Stream write failed: ${e.message}", e)
        }
    }

    
    /**
     * Consumes PcmChunks from a bounded/unlimited Channel, writes PCM samples into
     * a SINGLE continuous AudioTrack stream, and stops only after the EOS chunk
     * has been physically played by the hardware DAC.
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
        var totalWrittenSamples = 0
        var totalChunks = 0
        var t4 = 0L
        var t5 = 0L
        var track: AudioTrack? = null

        try {
            val streamStarted = startTtsStream(sampleRate, isAlert)
            if (!streamStarted) {
                Log.e(TAG, "playTtsQueue: startTtsStream failed")
                return@withContext
            }

            synchronized(lock) {
                track = audioTrack
            }
            val activeTrack = track
            if (activeTrack == null || activeTrack.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "playTtsQueue: AudioTrack null or uninitialized")
                return@withContext
            }

            var initialHead = (activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL)

            for (chunk in channel) {
                if (chunk.isEos) {
                    Log.d(TAG, "[AUDIO] EOS chunk received in consumer loop")
                    break
                }
                if (chunk.samples.isEmpty()) continue

                totalChunks++
                val samples = chunk.samples
                if (t4 == 0L) {
                    t4 = System.currentTimeMillis()
                    Log.d(TAG, "[TTS] T4_FIRST_AUDIO available in ${t4 - t0}ms from receive")
                }

                // Detect underrun to clear deadlocked hardware state
                val currentHead = (activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                val targetHead = initialHead + totalWrittenSamples.toLong()
                val isUnderrun = totalWrittenSamples > 0 && currentHead >= targetHead

                if (isUnderrun) {
                    Log.w(TAG, "[AUDIO] Underrun detected (head=$currentHead, target=$targetHead). Kicking track to resume cleanly.")
                    try {
                        activeTrack.pause()
                    } catch (e: Throwable) {
                        Log.e(TAG, "AudioTrack pause failed", e)
                    }
                    // Removed flush() because it destroys buffered audio and causes clicking on slow phones.
                    // A simple pause() followed by play() cleanly kicks the hardware state machine out of underrun.
                }

                // CRITICAL FIX: Always assert PLAYING *before* a blocking write!
                // Otherwise, writing a chunk larger than the buffer will deadlock forever.
                if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        activeTrack.play()
                        if (!ttsPlaybackStartedReported) {
                            ttsPlaybackStartedReported = true
                            t5 = System.currentTimeMillis()
                            Log.d(TAG, "[AUDIO] T5_PLAYBACK_STARTED at $t5 (in ${t5 - t0}ms from receive)")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "AudioTrack.play() failed on start/resume", e)
                    }
                }

                val written = writePcmToTrack(activeTrack, samples)
                if (written > 0) {
                    totalWrittenSamples += written
                    Log.d(TAG, "[AUDIO] CHUNK ${chunk.chunkNumber} WRITTEN ($written samples)")
                } else if (written < 0) {
                    Log.e(TAG, "[AUDIO] writePcmToTrack returned ERROR: $written")
                }

                val newHead = (activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                Log.d(
                    TAG,
                    "[AUDIO] PLAY_STATE state=${activeTrack.state} playState=${activeTrack.playState} PLAYBACK_HEAD current=$newHead target=${initialHead + totalWrittenSamples}"
                )
            }

            // EOS reached: Calculate final target head position and wait for hardware playback to complete
            val targetHead = initialHead + totalWrittenSamples.toLong()
            Log.d(
                TAG,
                "[AUDIO] EOS WAIT START: totalChunks=$totalChunks totalWrittenSamples=$totalWrittenSamples targetHead=$targetHead initialHead=$initialHead"
            )

            val startTime = System.currentTimeMillis()
            val maxWaitMs = if (sampleRate > 0) (totalWrittenSamples * 1000L / sampleRate) + 3000L else 7000L

            while (isPlaying && isTtsStreamActive) {
                val currentHead = activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (currentHead >= targetHead) {
                    break
                }
                if (activeTrack.playState != AudioTrack.PLAYSTATE_PLAYING && isPlaying && isTtsStreamActive) {
                    try { activeTrack.play() } catch (e: Throwable) { Log.w(TAG, "Re-assert play in EOS wait failed: ${e.message}") }
                }
                if (System.currentTimeMillis() - startTime > maxWaitMs) {
                    Log.w(TAG, "[AUDIO] EOS wait timeout reached (${maxWaitMs}ms)")
                    break
                }
                delay(20)
            }

            val t6 = System.currentTimeMillis()
            val t7 = t6
            if (t5 == 0L) t5 = t4.takeIf { it > 0L } ?: t6
            if (t4 == 0L) t4 = t5

            val firstAudibleLatencyMs = t5 - t0
            val firstAudioGenMs = t4 - t0
            val totalSynthesisMs = t6 - t0
            val totalLatencyMs = t7 - t0

            Log.i(
                BENCH_TAG,
                "[TTS_BENCH] T0_RECEIVED=$t0 T1_REQUESTED=$t1 T2_MODEL_READY=$t2 T3_SYNTHESIS_START=$t3 T4_FIRST_AUDIO=$t4 T5_PLAYBACK_START=$t5 T6_SYNTHESIS_END=$t6 T7_PLAYBACK_END=$t7 FIRST_AUDIBLE_LATENCY_MS=$firstAudibleLatencyMs FIRST_AUDIO_GEN_MS=$firstAudioGenMs TOTAL_SYNTHESIS_MS=$totalSynthesisMs TOTAL_LATENCY_MS=$totalLatencyMs"
            )
            Log.d(
                TAG,
                "[AUDIO] STREAM_COMPLETED totalChunks=$totalChunks totalGeneratedSamples=$totalWrittenSamples totalWrittenSamples=$totalWrittenSamples currentPlaybackHead=${activeTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL} AudioTrackState=${activeTrack.state} AudioTrackPlayState=${activeTrack.playState} streamActiveState=$isTtsStreamActive"
            )

        } catch (e: Exception) {
            Log.e(TAG, "playTtsQueue error", e)
        } finally {
            synchronized(lock) {
                isTtsStreamActive = false
                isPlaying = false
            }
        }
    }

    private fun writePcmToTrack(track: AudioTrack, pcmData: FloatArray): Int {
        val useFloat = isFloatEncoding
        return try {
            if (useFloat) {
                track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            } else {
                val shortData = ShortArray(pcmData.size)
                for (i in pcmData.indices) {
                    val s = pcmData[i]
                    val clamped = if (s > 1.0f) 1.0f else if (s < -1.0f) -1.0f else s
                    shortData[i] = (clamped * 32767.0f).toInt().toShort()
                }
                track.write(shortData, 0, shortData.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "writePcmToTrack failed", e)
            0
        }
    }

    fun stop() {
        isPlaying = false
        isTtsStreamActive = false
        stopInternal()
    }

    private fun stopInternal() {
        synchronized(lock) {
            val track = audioTrack ?: return
            audioTrack = null
            currentSampleRate = 0
            currentUsage = 0
            isTtsStreamActive = false
            Log.i(TAG, "STOP: Stopping playback")
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayer.stopInternal: ${e.message}")
            } finally {
                try {
                    track.release()
                    Log.i(TAG, "RELEASE: AudioTrack released")
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }
}
