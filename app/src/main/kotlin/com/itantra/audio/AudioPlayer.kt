package com.itantra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ITANTRA-AUDIO"

class AudioPlayer {

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false

    suspend fun playAudio(pcmData: FloatArray, sampleRate: Int, isAlert: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (pcmData.isEmpty()) {
                Log.w(TAG, "playAudio: empty PCM data — skipping")
                return@withContext
            }

            // Stop any previous playback first
            stopInternal()

            try {
                val attributes = AudioAttributes.Builder()
                    .setUsage(if (isAlert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
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
                    Log.e(TAG, "Invalid AudioTrack buffer size for sampleRate=$sampleRate")
                    return@withContext
                }

                val track = AudioTrack(
                    attributes,
                    format,
                    minBuffer,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioTrack failed to initialize (state=${track.state})")
                    track.release()
                    return@withContext
                }

                audioTrack = track
                isPlaying = true
                track.play()

                val chunkSize = minBuffer
                var offset = 0
                while (offset < pcmData.size && isPlaying) {
                    val writeSize = minOf(chunkSize, pcmData.size - offset)
                    val written = track.write(pcmData, offset, writeSize, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write() returned error code $written")
                        break
                    }
                    offset += written
                }

                // Safe teardown
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.flush()
                track.release()

                if (audioTrack == track) {
                    audioTrack = null
                    isPlaying = false
                }
                Log.d(TAG, "playAudio complete: ${pcmData.size} samples at ${sampleRate}Hz")

            } catch (e: Exception) {
                Log.e(TAG, "AudioPlayer.playAudio failed", e)
                isPlaying = false
            }
        }

    fun startStream(sampleRate: Int = 16000) {
        stopInternal()
        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                minBuffer,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
                isPlaying = true
                track.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming AudioTrack", e)
        }
    }

    fun writeStream(pcmData: FloatArray) {
        if (!isPlaying) return
        val track = audioTrack ?: return
        try {
            track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Stream write failed", e)
        }
    }

    fun stop() {
        isPlaying = false
        stopInternal()
    }

    private fun stopInternal() {
        val track = audioTrack ?: return
        audioTrack = null
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
            try { track.release() } catch (e: Exception) { /* ignore */ }
        }
    }
}
