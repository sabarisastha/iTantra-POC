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
    @Volatile private var txFrameCount: Long = 0L
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startRecording(
        preferredAudioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onChunk: (FloatArray) -> Unit
    ): Boolean {
        if (recording) {
            Log.w(TAG, "START: Already recording, duplicate request ignored")
            return true
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        val sourcesToTry = when (preferredAudioSource) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            MediaRecorder.AudioSource.MIC -> intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
            else -> intArrayOf(
                preferredAudioSource,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )
        }

        var initializedRecord: AudioRecord? = null
        var chosenSource = preferredAudioSource

        // Try sources with up to 3 retries (allows mic to be released if STT just stopped)
        retryLoop@ for (attempt in 1..3) {
            for (source in sourcesToTry) {
                try {
                    val record = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL,
                        ENCODING,
                        bufSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = record
                        chosenSource = source
                        break@retryLoop
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt $attempt: AudioRecord init failed for source=$source: ${e.message}")
                }
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(60) // Pause for hardware mic release
                } catch (ignored: InterruptedException) {}
            }
        }

        if (initializedRecord == null || initializedRecord.state != AudioRecord.STATE_INITIALIZED) {
            val sourceName = getSourceName(preferredAudioSource)
            Log.e("CALL", "[CALL][TX][ERROR] Failed to initialize AudioRecord after 3 retries (preferred source=$sourceName)")
            return false
        }

        val chosenSourceName = getSourceName(chosenSource)
        Log.i("CALL", "[CALL][TX] AudioRecord initialized: source=$chosenSourceName sampleRate=$SAMPLE_RATE")

        audioRecord = initializedRecord
        recording = true
        txFrameCount = 0L

        try {
            initializedRecord.startRecording()
            Log.i("CALL", "[CALL][TX] PCM sender started")
        } catch (e: Exception) {
            Log.e("CALL", "[CALL][TX][ERROR] Failed to start AudioRecord: ${e.message}", e)
            initializedRecord.release()
            audioRecord = null
            recording = false
            return false
        }

        recordJob = scope.launch {
            val shortBuf = ShortArray(bufSize / 2)
            val floatChunk = FloatArray(shortBuf.size)
            while (recording) {
                val record = audioRecord ?: break
                val read = record.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    for (i in 0 until read) {
                        floatChunk[i] = shortBuf[i] / 32768.0f
                    }
                    val outChunk = if (read == floatChunk.size) floatChunk else floatChunk.copyOf(read)
                    txFrameCount++
                    if (txFrameCount == 1L) {
                        Log.i("CALL", "[CALL][TX] First PCM frame sent seq=1 size=$read")
                    } else if (txFrameCount % 100L == 0L) {
                        Log.i("CALL", "[CALL][TX] Sent $txFrameCount frames")
                    }
                    Log.v(TAG, "READ: read $read samples")
                    onChunk(outChunk)
                } else if (read < 0) {
                    Log.w("CALL", "[CALL][TX][ERROR] AudioRecord read error code: $read")
                }
            }
        }
        return true
    }

    private fun getSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> "SOURCE_$source"
    }

    fun stopRecording() {
        Log.i(TAG, "STOP: Stopping recording")
        recording = false
        try {
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
