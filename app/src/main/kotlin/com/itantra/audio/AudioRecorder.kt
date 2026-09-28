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
        private const val RETRY_DELAY_MS = 80L
        private const val MAX_RETRIES_PER_SOURCE = 3
    }

    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var recordJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startRecording(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onChunk: (FloatArray) -> Unit
    ): Boolean {
        return startRecordingInternal(audioSource) { shortBuf, read ->
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
    ): Boolean {
        return startRecordingInternal(audioSource) { shortBuf, read ->
            onChunk(if (read == shortBuf.size) shortBuf else shortBuf.copyOf(read))
        }
    }

    /**
     * Attempts to open an [AudioRecord] on a single [source], retrying up to
     * [MAX_RETRIES_PER_SOURCE] times with a short pause between attempts.
     *
     * This pause is what actually absorbs the Android HAL's mic-release race
     * condition (~50-100ms after a previous AudioRecord/STT session releases
     * the microphone). A single immediate attempt is not sufficient — that
     * was the root cause of the previous intermittent "device B not audible"
     * bug, where two back-to-back sources were tried with zero delay.
     *
     * NOTE: this runs on Dispatchers.IO (see startRecordingInternal call site),
     * so a blocking Thread.sleep here is safe and does not block the UI thread.
     */
    @SuppressLint("MissingPermission")
    private fun tryOpenAudioRecord(
        source: Int,
        bufSize: Int
    ): AudioRecord? {
        repeat(MAX_RETRIES_PER_SOURCE) { attempt ->
            val recorder = AudioRecord(
                source,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufSize
            )

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                if (attempt > 0) {
                    Log.i(TAG, "INIT: source=$source succeeded on retry #$attempt")
                }
                return recorder
            }

            Log.w(TAG, "INIT: source=$source failed (attempt ${attempt + 1}/$MAX_RETRIES_PER_SOURCE)")
            runCatching { recorder.release() }

            if (attempt < MAX_RETRIES_PER_SOURCE - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingInternal(
        audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        onRawChunk: (ShortArray, Int) -> Unit
    ): Boolean {
        if (recording) {
            Log.w(TAG, "START: Already recording, duplicate request ignored")
            return true
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, 4096)

        // Full three-way fallback chain: requested source -> MIC -> DEFAULT.
        // Each source gets its own retry+delay loop via tryOpenAudioRecord.
        // linkedSetOf preserves order and de-duplicates if audioSource is
        // already MIC or DEFAULT.
        val sourcesToTry = linkedSetOf(
            audioSource,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )

        var recorder: AudioRecord? = null
        var usedSource = -1

        for (source in sourcesToTry) {
            recorder = tryOpenAudioRecord(source, bufSize)
            if (recorder != null) {
                usedSource = source
                break
            }
        }

        if (recorder == null) {
            Log.e(TAG, "INIT: AudioRecord failed to initialize on ALL sources: $sourcesToTry")
            return false
        }

        audioRecord = recorder
        Log.i(TAG, "START: AudioRecord initialized successfully with source $usedSource")
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
        return true
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