package com.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "ITAN-AUDIO-PLAY"

class AudioPlayer(private val context: Context? = null) {

    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private val lock = Any()

    private val audioManager: AudioManager? by lazy {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    @Volatile private var alertFocusRequest: AudioFocusRequest? = null
    @Volatile private var prevAlarmVolume: Int? = null

    suspend fun playAudio(pcmData: FloatArray, sampleRate: Int, isAlert: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (pcmData.isEmpty()) {
                Log.w(TAG, "playAudio: empty PCM data — skipping")
                return@withContext
            }

            // Stop any previous playback first
            stopInternal()

            if (isAlert) {
                requestAlertAudioFocusAndVolume()
            }

            var trackToMonitor: AudioTrack? = null
            var totalFramesToWrite = 0

            synchronized(lock) {
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
                        Log.e(TAG, "INIT: Invalid AudioTrack buffer size for sampleRate=\$sampleRate")
                        if (isAlert) restoreAlertAudioFocusAndVolume()
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
                        Log.e(TAG, "INIT: AudioTrack failed to initialize (state=\${track.state})")
                        track.release()
                        if (isAlert) restoreAlertAudioFocusAndVolume()
                        return@withContext
                    }

                    Log.i(TAG, "INIT: AudioTrack initialized (sampleRate=\$sampleRate, alert=\$isAlert)")

                    audioTrack = track
                    isPlaying = true
                    Log.i(TAG, "START: AudioTrack playing")
                    track.play()

                    val chunkSize = minBuffer
                    var offset = 0
                    
                    while (offset < pcmData.size && isPlaying) {
                        val writeSize = minOf(chunkSize, pcmData.size - offset)
                        val written = track.write(pcmData, offset, writeSize, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) {
                            Log.e(TAG, "WRITE: AudioTrack.write() returned error code \$written")
                            break
                        }
                        Log.v(TAG, "WRITE: Wrote \$written samples")
                        offset += written
                    }
                    totalFramesToWrite = offset
                    trackToMonitor = track
                } catch (e: Exception) {
                    Log.e(TAG, "AudioPlayer.playAudio failed", e)
                    isPlaying = false
                }
            }

            trackToMonitor?.let { track ->
                try {
                    // Polling for playback completion
                    while (isPlaying && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        if (track.playbackHeadPosition >= totalFramesToWrite) {
                            break
                        }
                        delay(20)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling failed", e)
                }

                synchronized(lock) {
                    if (audioTrack == track) {
                        stopInternal()
                    }
                }
            }

            if (isAlert) {
                restoreAlertAudioFocusAndVolume()
            }

            Log.d(TAG, "playAudio complete: \${pcmData.size} samples at \${sampleRate}Hz")
        }

    fun startStream(sampleRate: Int = 16000) {
        stopInternal()
        synchronized(lock) {
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e(TAG, "INIT: Invalid buffer size in startStream")
                    return
                }
                
                val track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    minBuffer,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    Log.i(TAG, "INIT: Stream AudioTrack initialized")
                    audioTrack = track
                    isPlaying = true
                    Log.i(TAG, "START: Stream AudioTrack playing")
                    track.play()
                } else {
                    Log.e(TAG, "INIT: Stream AudioTrack failed to initialize")
                    track.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming AudioTrack", e)
            }
        }
    }

    fun writeStream(pcmData: FloatArray) {
        if (!isPlaying) return
        synchronized(lock) {
            val track = audioTrack
            if (!isPlaying || track == null) return
            try {
                val written = track.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
                Log.v(TAG, "WRITE: Stream wrote \$written samples")
            } catch (e: Exception) {
                Log.e(TAG, "Stream write failed", e)
            }
        }
    }

    fun stop() {
        isPlaying = false
        stopInternal()
        restoreAlertAudioFocusAndVolume()
    }

    private fun stopInternal() {
        synchronized(lock) {
            val track = audioTrack ?: return
            audioTrack = null
            Log.i(TAG, "STOP: Stopping playback")
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioPlayer.stopInternal: \${e.message}")
            } finally {
                try {
                    track.release()
                    Log.i(TAG, "RELEASE: AudioTrack released")
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun requestAlertAudioFocusAndVolume() {
        val am = audioManager ?: return
        try {
            val stream = AudioManager.STREAM_ALARM
            prevAlarmVolume = am.getStreamVolume(stream)
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { /* hold focus */ }
                    .build()
                am.requestAudioFocus(req)
                alertFocusRequest = req
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
            Log.d(TAG, "Alert audio focus requested & max alarm volume set")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request alert audio focus/volume", e)
        }
    }

    private fun restoreAlertAudioFocusAndVolume() {
        val am = audioManager ?: return
        try {
            prevAlarmVolume?.let { vol ->
                am.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
                Log.d(TAG, "Restored STREAM_ALARM volume to \$vol")
                prevAlarmVolume = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alertFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                alertFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore alert audio focus/volume", e)
        }
    }
}
