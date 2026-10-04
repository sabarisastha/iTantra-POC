package com.itantra.vad

import android.content.Context
import android.util.Log
import com.itantra.audio.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max

private const val TAG = "AdaptiveNoiseFloorVad"

/**
 * Adaptive Noise-Floor Silero VAD Pipeline.
 *
 * Wraps [SileroVadEngine] + [SpeechSegmenter] with a real-time exponential moving average
 * (EMA) of the ambient RMS measured during silence frames. This dynamically adjusts the
 * Silero speech-probability threshold up or down based on the acoustic noise floor —
 * critical for disaster/high-noise environments.
 *
 * ## Algorithm
 * - Every frame classified as SILENCE by Silero contributes its RMS to an EMA estimator.
 * - The effective threshold is nudged by a scaled delta from the baseline RMS:
 *   ```
 *   delta  = (ema_rms - BASELINE_RMS) / BASELINE_RMS
 *   thresh = clamp(BASE_THRESHOLD + delta * ADAPT_GAIN, BASE-0.15, BASE+0.10)
 *   ```
 * - Noisy environments raise the threshold (fewer false triggers);
 *   quiet environments lower it slightly (more sensitivity).
 *
 * ## Usage
 * Feed exactly [SILERO_FRAME_SIZE] (512) PCM-16 samples at 16kHz per call to [processFrame].
 * Returns a complete FloatArray [-1.0, 1.0] segment on SPEECH_END, or null otherwise.
 *
 * @param context Android context for loading the bundled ONNX model asset.
 * @param baseThreshold Base Silero speech-probability threshold before adaptation.
 */
class AdaptiveNoiseFloorVadPipeline(
    private val context: Context,
    private val baseThreshold: Float = 0.50f,
) {
    // ── Adaptive threshold constants ──────────────────────────────────────────

    /** EMA smoothing factor for noise-floor estimation (lower = slower, more stable). */
    private val EMA_ALPHA = 0.05f

    /** Gain factor scaling RMS delta into threshold adjustment. */
    private val ADAPT_GAIN = 0.25f

    /** Maximum upward adjustment (noisier → higher threshold). */
    private val MAX_RAISE = 0.10f

    /** Maximum downward adjustment (quieter → lower threshold). */
    private val MAX_LOWER = 0.15f

    /**
     * Baseline RMS for a typical indoor quiet environment (~-38 dBFS).
     * 10^(-38/20) * 1.0 ≈ 0.00397 on normalised float scale.
     */
    private val BASELINE_RMS = 130f / 32767f

    // ── State ─────────────────────────────────────────────────────────────────

    /** Running EMA of RMS over silence frames. Initialized to baseline. */
    @Volatile private var emaRms: Float = BASELINE_RMS

    /** Current adapted threshold fed to the Silero engine. */
    @Volatile private var adaptedThreshold: Float = baseThreshold

    /** Silero probability threshold last used to construct the engine. */
    @Volatile private var engineThreshold: Float = baseThreshold

    /** Core Silero VAD engine, recreated when threshold drifts significantly. */
    private var vadEngine: SileroVadEngine = SileroVadEngine(context, baseThreshold)

    /** Temporal FSM that debounces VAD output into speech segments. */
    private val segmenter = SpeechSegmenter()

    /**
     * Accumulation buffer for the current in-progress speech segment (PCM-16 shorts).
     * Pre-allocated at a generous capacity (30 s @ 16kHz = 480 000 samples).
     */
    private val speechBuffer = ArrayList<Short>(AudioRecorder.SAMPLE_RATE * 30)

    /** Maximum number of samples to accumulate before force-flushing a segment (~30 s). */
    private val MAX_SEGMENT_SAMPLES = AudioRecorder.SAMPLE_RATE * 30

    /** Total frames processed in current recording session. */
    private var totalFrames = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process one Silero frame (exactly [SILERO_FRAME_SIZE] = 512 PCM-16 samples at 16kHz).
     *
     * @return A completed speech segment as FloatArray [-1.0, 1.0] when a SPEECH_END event
     *         fires, or null while silence / mid-utterance accumulation is ongoing.
     */
    suspend fun processFrame(frame: ShortArray, isContinuousMode: Boolean = true): FloatArray? = withContext(Dispatchers.Default) {
        totalFrames++
        val vadResult = vadEngine.processFrame(frame)

        var sum = 0.0
        for (s in frame) sum += s * s
        val rawRms = kotlin.math.sqrt(sum / frame.size)

        if (totalFrames % 100L == 0L) {
            Log.d(TAG, "Frame $totalFrames | rawRms=${"%.2f".format(rawRms)} | isSpeech=${vadResult.isSpeech} | prob=${vadResult.speechProbability.fmt()} | bufSize=${speechBuffer.size} | fallback=${SileroVadEngine.isOrtUnavailable}")
        }
        if (rawRms == 0.0 && totalFrames % 100L == 0L) {
            Log.e(TAG, "CRITICAL: AudioRecord delivering completely silent 0-filled buffers!")
        }

        // Buffer frame if speech is detected OR if audio has energy (rawRms > 60.0) OR fallback mode is active
        if (vadResult.isSpeech || rawRms > 60.0 || SileroVadEngine.isOrtUnavailable) {
            accumulateFrame(frame)
        }

        val event = segmenter.process(vadResult)

        when (event) {
            SegmenterEvent.SILENCE -> {
                updateNoiseFloor(frame, vadResult)
                null
            }
            SegmenterEvent.SPEECH_START -> {
                Log.d(TAG, "SPEECH_START — threshold=${adaptedThreshold.fmt()} emaRms=${emaRms.fmt()}")
                if (!SileroVadEngine.isOrtUnavailable && !vadResult.isSpeech && rawRms <= 60.0) {
                    accumulateFrame(frame)
                }
                null
            }
            SegmenterEvent.SPEECH_CONTINUING -> {
                if (!SileroVadEngine.isOrtUnavailable && !vadResult.isSpeech && rawRms <= 60.0) {
                    accumulateFrame(frame)
                }
                // Force-flush if the segment is unreasonably long
                if (speechBuffer.size >= MAX_SEGMENT_SAMPLES) {
                    Log.w(TAG, "Max segment length reached (${speechBuffer.size} samples) — force-flushing")
                    drainBuffer()
                } else {
                    null
                }
            }
            SegmenterEvent.SPEECH_END -> {
                val samples = speechBuffer.size
                Log.d(TAG, "SPEECH_END — segment=$samples samples (${samples / 16000f}s), continuous=$isContinuousMode")
                if (isContinuousMode) {
                    drainBuffer()
                } else {
                    // In PTT mode, keep utterance intact until user releases PTT (handleStop flushes)
                    null
                }
            }
        }
    }

    /**
     * Reset recurrent VAD state, segmenter FSM, speech buffer, and noise-floor estimator.
     * Call between separate recording sessions to avoid state bleed-over.
     */
    fun reset() {
        Log.d(TAG, "reset() — clearing state")
        totalFrames = 0L
        segmenter.reset()
        vadEngine.reset()
        speechBuffer.clear()
        emaRms = BASELINE_RMS
        adaptedThreshold = baseThreshold
        engineThreshold = baseThreshold
    }

    /**
     * Drain and return any accumulated speech segment sitting in [speechBuffer] upon PTT release.
     * Enforces a minimum sample threshold (400 samples ~25ms) to ignore clicks/pops while preserving short utterances.
     */
    fun flush(): FloatArray? {
        Log.d(TAG, "flush() requested. Current speechBuffer.size = ${speechBuffer.size}")
        if (speechBuffer.isEmpty()) {
            Log.w(TAG, "flush() failed: speechBuffer is completely empty")
            return null
        }
        val total = speechBuffer.size
        if (total < 400) {
            Log.w(TAG, "flush() dropped: only $total samples (< 400)")
            speechBuffer.clear()
            return null
        }
        val out = FloatArray(total)
        for (i in 0 until total) {
            out[i] = speechBuffer[i] / 32768.0f
        }
        speechBuffer.clear()
        Log.d(TAG, "flush() successfully emitted ${out.size} float samples")
        return out
    }

    /**
     * Release all native ONNX resources. The pipeline must not be used after this call.
     */
    fun close() {
        Log.d(TAG, "close()")
        vadEngine.close()
        speechBuffer.clear()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Append [frame] samples to [speechBuffer]. */
    private fun accumulateFrame(frame: ShortArray) {
        for (s in frame) speechBuffer.add(s)
    }

    /** Convert the accumulated [speechBuffer] to a normalised FloatArray and clear it. */
    private fun drainBuffer(): FloatArray {
        val out = FloatArray(speechBuffer.size)
        for (i in out.indices) out[i] = speechBuffer[i] / 32768.0f
        speechBuffer.clear()
        return out
    }

    /**
     * Update noise-floor EMA from a silence frame and recompute [adaptedThreshold].
     * Only called when the segmenter reports [SegmenterEvent.SILENCE].
     */
    private fun updateNoiseFloor(frame: ShortArray, vadResult: VadResult) {
        if (frame.isEmpty()) return

        // Only update EMA when Silero is also confident it's silence (prob < 0.30)
        if (vadResult.speechProbability >= 0.30f) return

        // Compute normalised RMS of this frame
        var sumSq = 0.0
        for (s in frame) {
            val f = s / 32768.0
            sumSq += f * f
        }
        val frameRms = sqrt(sumSq / frame.size).toFloat()

        // Update EMA
        emaRms = EMA_ALPHA * frameRms + (1f - EMA_ALPHA) * emaRms

        // Relative deviation from baseline
        val delta = (emaRms - BASELINE_RMS) / max(BASELINE_RMS, 1e-6f)

        // Compute new threshold with asymmetric clamping
        val adjustment = (delta * ADAPT_GAIN).coerceIn(-MAX_LOWER, MAX_RAISE)
        val newThreshold = (baseThreshold + adjustment).coerceIn(0.20f, 0.90f)

        adaptedThreshold = newThreshold

        // Swap engine only when drift is significant (>0.02) to avoid ONNX init overhead.
        // Skip re-creation if ONNX runtime is permanently unavailable.
        if (!SileroVadEngine.isOrtUnavailable && abs(newThreshold - engineThreshold) > 0.02f) {
            Log.d(TAG, "Adapting engine threshold: ${engineThreshold.fmt()} → ${newThreshold.fmt()} " +
                    "(emaRms=${emaRms.fmt()}, delta=${delta.fmt()})")
            vadEngine.close()
            vadEngine = SileroVadEngine(context, newThreshold)
            engineThreshold = newThreshold
        }
    }

    private fun Float.fmt() = "%.3f".format(this)
}
