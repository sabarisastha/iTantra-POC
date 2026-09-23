package com.itantra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "ITAN-AUDIO-PLAY"

class AudioPlayer {

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var currentUsage: Int = 0
    @Volatile private var rxFrameCount: Long = 0L

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
        var totalFramesToWrite = pcmData.size

        synchronized(lock) {
            val existing = audioTrack
            if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED &&
                currentSampleRate == sampleRate && currentUsage == targetUsage) {
                // Warm path: reuse existing AudioTrack instance
                try {
                    if (existing.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        existing.pause()
                    }
                    existing.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack flush failed, recreating: ${e.message}")
                    stopInternal()
                }
            } else {
                stopInternal()
            }

            if (audioTrack == null) {
                initTrackLocked(sampleRate, targetUsage, isAlert, pcmData.size)
            }

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
            var offset = 0
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
            // Keep track warm and initialized for subsequent utterances
            isPlaying = false
        }
        Log.d(TAG, "playAudio complete: ${pcmData.size} samples at ${sampleRate}Hz")
    }

    private fun initTrackLocked(sampleRate: Int, targetUsage: Int, isAlert: Boolean, expectedSamples: Int?) {
        val attributes = AudioAttributes.Builder()
            .setUsage(targetUsage)
            .setContentType(if (isAlert) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "INIT: Invalid AudioTrack buffer size for sampleRate=$sampleRate")
            return
        }

        val bufSize = if (expectedSamples != null) {
            maxOf(minBuffer * 2, expectedSamples * 4)
        } else {
            maxOf(minBuffer * 2, sampleRate * 2) // default 1-second float buffer
        }

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
            return
        }

        audioTrack = track
        currentSampleRate = sampleRate
        currentUsage = targetUsage
        Log.i(TAG, "INIT: AudioTrack initialized (sampleRate=$sampleRate bufSize=$bufSize)")
    }

    fun startStream(sampleRate: Int = 16000): Boolean {
        stopInternal()
        rxFrameCount = 0L
        synchronized(lock) {
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
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
                            AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
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

    fun stop() {
        isPlaying = false
        stopInternal()
    }

    private fun stopInternal() {
        synchronized(lock) {
            val track = audioTrack ?: return
            audioTrack = null
            currentSampleRate = 0
            currentUsage = 0
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
