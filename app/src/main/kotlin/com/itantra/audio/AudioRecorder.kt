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
        startRecordingInternal(audioSource) { shortBuf, read ->
            val floatChunk = FloatArray(read)
            for (i in 0 until read) {
                floatChunk[i] = shortBuf[i] / 32768.0f
            }
            onChunk(floatChunk)
        }
    }

    /**
     * Raw PCM-16 variant delivering [ShortArray] chunks directly.
     *
     * Preferred by the VAD pipeline to avoid a redundant float→short conversion
     * since Silero VAD requires int16 input anyway.
     */
    @SuppressLint("MissingPermission")
    fun startRecordingShorts(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onChunk: (ShortArray) -> Unit
    ) {
        startRecordingInternal(audioSource) { shortBuf, read ->
            onChunk(if (read == shortBuf.size) shortBuf else shortBuf.copyOf(read))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingInternal(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onRawChunk: (ShortArray, Int) -> Unit
    ) {
        if (recording) {
            Log.w(TAG, "START: Already recording, duplicate request ignored")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        var recordSource = audioSource
        var recorder = AudioRecord(
            recordSource,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "INIT: AudioRecord failed to initialize with source $recordSource")
            recorder.release()
            if (recordSource != MediaRecorder.AudioSource.MIC) {
                Log.w(TAG, "Retrying AudioRecord initialization with MediaRecorder.AudioSource.MIC fallback")
                recordSource = MediaRecorder.AudioSource.MIC
                recorder = AudioRecord(
                    recordSource,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufSize
                )
            }
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "INIT: AudioRecord failed to initialize with fallback source $recordSource")
            recorder.release()
            return
        }

        audioRecord = recorder
        Log.i(TAG, "START: AudioRecord initialized successfully with source $recordSource")
        audioRecord?.startRecording()
        recording = true

        if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "WARNING: AudioRecord state is not RECORDSTATE_RECORDING: ${audioRecord?.recordingState}")
        }

        recordJob = scope.launch {
            val shortBuf = ShortArray(bufSize / 2)
            while (recording) {
                val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: break
                if (read > 0) {
                    Log.v(TAG, "READ: read $read samples")
                    onRawChunk(shortBuf, read)
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
