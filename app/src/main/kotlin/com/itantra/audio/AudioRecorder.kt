package com.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "AudioRecorder"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false

    @SuppressLint("MissingPermission")
    fun startRecording(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onChunk: (FloatArray) -> Unit
    ) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize
        )

        audioRecord?.startRecording()
        recording = true

        Thread {
            val shortBuf = ShortArray(bufSize / 2)
            while (recording) {
                val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: break
                if (read > 0) {
                    val floatChunk = FloatArray(read) { i -> shortBuf[i] / 32768.0f }
                    onChunk(floatChunk)
                }
            }
        }.start()
    }

    fun stopRecording() {
        recording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording: ${e.message}")
        } finally {
            audioRecord = null
        }
    }
}
