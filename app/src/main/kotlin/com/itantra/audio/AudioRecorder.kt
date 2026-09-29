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
import kotlin.math.sqrt

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
        audioSource: Int = MediaRecorder.AudioSource.MIC,
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
        audioSource: Int = MediaRecorder.AudioSource.MIC,
        onChunk: (ShortArray) -> Unit
    ) {
        startRecordingInternal(audioSource) { shortBuf, read ->
            onChunk(if (read == shortBuf.size) shortBuf else shortBuf.copyOf(read))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingInternal(
        audioSource: Int = MediaRecorder.AudioSource.MIC,
        onRawChunk: (ShortArray, Int) -> Unit
    ) {
        if (recording) {
            Log.w(TAG, "START: Already recording, duplicate request ignored")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        val audioSourcesToTry = intArrayOf(
            audioSource,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        ).distinct().toIntArray()

        var recorder: AudioRecord? = null
        var selectedSource = -1

        for (src in audioSourcesToTry) {
            try {
                val rec = AudioRecord(
                    src,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufSize
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = rec
                    selectedSource = src
                    break
                } else {
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source $src: ${e.message}")
            }
        }

        if (recorder == null || selectedSource == -1) {
            Log.e(TAG, "INIT: AudioRecord failed to initialize with all candidate audio sources!")
            return
        }

        audioRecord = recorder
        Log.i(TAG, "START: AudioRecord initialized successfully with source $selectedSource")
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
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = shortBuf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read)

                    // If chunk RMS is faint (e.g. low hardware gain), apply soft auto-gain
                    if (rms in 0.1..300.0) {
                        val gain = 4.0f
                        for (i in 0 until read) {
                            val scaled = (shortBuf[i] * gain).toInt()
                            shortBuf[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                    }

                    Log.v(TAG, "READ: read $read samples (rawRms=%.2f)".format(rms))
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
