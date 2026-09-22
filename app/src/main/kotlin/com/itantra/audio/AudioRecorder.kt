package com.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "ITAN-AUDIO-REC"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startRecording(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onChunk: (FloatArray) -> Unit
    ) {
        if (recording) {
            Log.w(TAG, "START: Already recording, duplicate request ignored")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "INIT: AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        Log.i(TAG, "START: AudioRecord started")
        audioRecord?.startRecording()
        recording = true

        recordJob = scope.launch {
            val shortBuf = ShortArray(bufSize / 2)
            val floatChunk = FloatArray(shortBuf.size)
            while (recording) {
                val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: break
                if (read > 0) {
                    for (i in 0 until read) {
                        floatChunk[i] = shortBuf[i] / 32768.0f
                    }
                    val outChunk = if (read == floatChunk.size) floatChunk else floatChunk.copyOf(read)
                    Log.v(TAG, "READ: read $read samples")
                    onChunk(outChunk)
                }
            }
        }
    }

    fun stopRecording() {
        Log.i(TAG, "STOP: Stopping recording")
        recording = false
        try {
            // stop() unblocks a pending read; cancelling first can leave the native recorder open.
            audioRecord?.stop()
            recordJob?.cancel()
            audioRecord?.release()
            Log.i(TAG, "RELEASE: AudioRecord released")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording: ${e.message}")
        } finally {
            audioRecord = null
            recordJob = null
        }
    }
}
