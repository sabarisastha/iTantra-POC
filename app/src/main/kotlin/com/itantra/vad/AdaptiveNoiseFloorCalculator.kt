package com.itantra.vad

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Noise Floor Calculator encapsulating real-time Exponential Moving Average (EMA) tracking
 * and dynamic Silero speech probability threshold adaptation.
 */
class AdaptiveNoiseFloorCalculator(
    val baseThreshold: Float = 0.50f,
    private val emaAlpha: Float = 0.05f,
    private val adaptGain: Float = 0.25f,
    private val maxRaise: Float = 0.25f,
    private val maxLower: Float = 0.15f,
    val baselineRms: Float = 130f / 32767f
) {
    var emaRms: Float = baselineRms
        private set

    var adaptedThreshold: Float = baseThreshold
        private set

    /**
     * Updates EMA noise floor and returns recomputed adapted threshold.
     */
    fun update(pcmFrame: ShortArray, speechProbability: Float): Float {
        if (pcmFrame.isEmpty() || speechProbability >= 0.30f) {
            return adaptedThreshold
        }

        var sumSq = 0.0
        for (sample in pcmFrame) {
            val f = sample / 32768.0
            sumSq += f * f
        }
        val frameRms = sqrt(sumSq / pcmFrame.size).toFloat()
        return updateWithRms(frameRms, speechProbability)
    }

    /**
     * Updates EMA noise floor from normalized RMS float and speech probability.
     */
    fun updateWithRms(frameRms: Float, speechProbability: Float): Float {
        if (speechProbability >= 0.30f) {
            return adaptedThreshold
        }

        emaRms = emaAlpha * frameRms + (1f - emaAlpha) * emaRms
        val delta = (emaRms - baselineRms) / max(baselineRms, 1e-6f)
        val adjustment = (delta * adaptGain).coerceIn(-maxLower, maxRaise)
        adaptedThreshold = (baseThreshold + adjustment).coerceIn(0.20f, 0.90f)

        return adaptedThreshold
    }

    fun reset() {
        emaRms = baselineRms
        adaptedThreshold = baseThreshold
    }
}
