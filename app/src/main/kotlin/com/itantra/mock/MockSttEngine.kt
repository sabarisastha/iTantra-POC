package com.itantra.mock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.itantra.session.SupportedLanguage
import com.itantra.stt.RecognizedSentence
import com.itantra.stt.SpeechToTextEngine
import com.itantra.vad.SILERO_FRAME_SIZE
import com.itantra.vad.SegmenterEvent
import com.itantra.vad.SileroVadEngine
import com.itantra.vad.SpeechSegmenter
import com.itantra.vad.VadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MockSttEngine"

/**
 * Speech-To-Text Engine integrated with real offline Silero VAD (ONNX Runtime Mobile).
 *
 * Pipeline:
 *  1. AudioRecord captures 16 kHz, Mono, PCM-16 frames of 512 samples (32 ms).
 *  2. SileroVadEngine computes neural speech probability per frame.
 *  3. SpeechSegmenter applies temporal attack/hangover state machine to detect speech endpoints.
 *  4. Emits RecognizedSentence on speech boundary or when PTT floor is released.
 */
class MockSttEngine(
    private val context: Context,
    private var languageCode: String = SupportedLanguage.HINDI.code,
    private val sampleRate: Int = 16_000,
) : SpeechToTextEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequenceCounter = AtomicInteger(0)

    private val _recognizedSentences = MutableSharedFlow<RecognizedSentence>(extraBufferCapacity = 32)
    override val recognizedSentences: Flow<RecognizedSentence> = _recognizedSentences.asSharedFlow()

    @Volatile private var captureJob: Job? = null
    @Volatile private var shouldCapture = false

    // Real offline Silero VAD engine & temporal segmenter
    private val vadEngine: VadEngine = SileroVadEngine(context)
    private val speechSegmenter = SpeechSegmenter(
        speechStartThreshold = 0.50f,
        speechEndThreshold = 0.35f,
        attackFrames = 3,       // ~96ms voiced to start
        hangoverFrames = 16,    // ~512ms silence to end
    )

    fun setLanguage(code: String) {
        languageCode = code
        Log.d(TAG, "MockSttEngine language updated to: $code")
    }

    fun resetSequence() {
        sequenceCounter.set(0)
        Log.d(TAG, "MockSttEngine sequence counter reset to 0")
    }

    override fun start() {
        if (shouldCapture) {
            Log.d(TAG, "start() called while already capturing (idempotent)")
            return
        }
        Log.d(TAG, "start() called — starting microphone & Silero VAD pipeline (lang=$languageCode)")
        shouldCapture = true
        speechSegmenter.reset()
        vadEngine.reset()
        captureJob = scope.launch { captureLoop() }
    }

    override fun stop() {
        if (!shouldCapture) {
            Log.d(TAG, "stop() called while not capturing (idempotent)")
            return
        }
        Log.d(TAG, "stop() called — requesting capture loop stop")
        shouldCapture = false
    }

    fun shutdown() {
        stop()
        vadEngine.close()
        Log.d(TAG, "MockSttEngine shutdown complete")
    }

    private suspend fun captureLoop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted — microphone capture aborted")
            shouldCapture = false
            return
        }

        val frameSamples = SILERO_FRAME_SIZE // 512 samples @ 16 kHz = 32 ms
        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBufBytes, frameSamples * Short.SIZE_BYTES * 4)

        val recorder: AudioRecord
        try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing AudioRecord", e)
            shouldCapture = false
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            shouldCapture = false
            return
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording() failed", e)
            recorder.release()
            shouldCapture = false
            return
        }
        Log.d(TAG, "AudioRecord started (16 kHz, mono, PCM-16, frameSize=$frameSamples samples)")

        val shortBuf = ShortArray(frameSamples)
        var segStartMs = 0L
        var totalVoicedFrames = 0

        try {
            while (shouldCapture) {
                val read = recorder.read(shortBuf, 0, frameSamples, AudioRecord.READ_BLOCKING)
                if (read < frameSamples) continue

                // ── Run Silero VAD on 512-sample frame ────────────────────────
                val vadResult = vadEngine.processFrame(shortBuf)
                if (vadResult.isSpeech) totalVoicedFrames++

                // ── Temporal segmentation state machine ───────────────────────
                when (speechSegmenter.process(vadResult)) {
                    SegmenterEvent.SPEECH_START -> {
                        segStartMs = System.currentTimeMillis()
                        Log.d(TAG, "VAD Event: SPEECH_START detected (prob=${vadResult.speechProbability})")
                    }
                    SegmenterEvent.SPEECH_CONTINUING -> {
                        // In-speech audio continuing
                    }
                    SegmenterEvent.SPEECH_END -> {
                        // Sustained silence detected after speech segment
                        val pauseMs = System.currentTimeMillis()
                        val seqId = sequenceCounter.incrementAndGet()
                        val duration = pauseMs - segStartMs
                        Log.d(TAG, "VAD Event: SPEECH_END — emitting segment #$seqId (duration=${duration}ms, lang=$languageCode)")
                        val sentence = RecognizedSentence(
                            text         = "[sim-stt segment #$seqId, lang=$languageCode]",
                            languageCode = languageCode,
                            isAlert      = false,
                            timestampMs  = pauseMs,
                            sequenceId   = seqId,
                        )
                        _recognizedSentences.tryEmit(sentence)
                    }
                    SegmenterEvent.SILENCE -> {
                        // Ambient silence
                    }
                }
            }

            // ── Finalize in-flight segment when capture loop stops (PTT release) ──
            Log.d(TAG, "Capture loop stopped. Checking for in-flight speech to finalize...")
            if (speechSegmenter.isSpeechActive || totalVoicedFrames >= 3) {
                val pauseMs = System.currentTimeMillis()
                val seqId = sequenceCounter.incrementAndGet()
                val duration = if (segStartMs > 0) pauseMs - segStartMs else 0L
                Log.d(TAG, "PTT Release / VAD finalizer: Emitting segment #$seqId (duration=${duration}ms, lang=$languageCode)")
                val sentence = RecognizedSentence(
                    text         = "[sim-stt segment #$seqId, lang=$languageCode]",
                    languageCode = languageCode,
                    isAlert      = false,
                    timestampMs  = pauseMs,
                    sequenceId   = seqId,
                )
                _recognizedSentences.tryEmit(sentence)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in STT captureLoop", e)
        } finally {
            runCatching {
                recorder.stop()
                recorder.release()
            }
            Log.d(TAG, "AudioRecord stopped and released")
        }
    }
}