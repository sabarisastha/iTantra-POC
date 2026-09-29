package com.itantra.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.pow
import kotlin.random.Random

class AdaptiveNoiseFloorMathTest {

    private lateinit var calculator: AdaptiveNoiseFloorCalculator
    private val baseThreshold = 0.50f

    @Before
    fun setUp() {
        calculator = AdaptiveNoiseFloorCalculator(baseThreshold = baseThreshold)
    }

    /**
     * Test 1: Quiet Room Adaptation
     * Feed 50 synthetic frames of low-amplitude noise (-55 dBFS, RMS ≈ 0.0018).
     * Assert that adaptedThreshold decreases below baseThreshold (from 0.50 down towards 0.40).
     */
    @Test
    fun testQuietRoomAdaptation() {
        // -55 dBFS correspond to RMS of ~10^(-55/20) ≈ 0.001778
        val quietDbfsRms = 10.0.pow(-55.0 / 20.0).toFloat() // ~0.001778
        val frameSize = SILERO_FRAME_SIZE

        // Feed 50 frames of quiet noise
        for (i in 0 until 50) {
            val quietFrame = generateSyntheticPcmFrame(quietDbfsRms, frameSize)
            calculator.update(quietFrame, speechProbability = 0.05f)
        }

        val adapted = calculator.adaptedThreshold
        println("Quiet Room Adaptation: Initial base=$baseThreshold, Adapted threshold=$adapted, EMA RMS=${calculator.emaRms}")

        assertTrue(
            "Adapted threshold ($adapted) should decrease below base threshold ($baseThreshold)",
            adapted < baseThreshold
        )
        assertTrue(
            "Adapted threshold ($adapted) should decrease towards ~0.40 range",
            adapted in 0.35f..0.45f
        )
    }

    /**
     * Test 2: Disaster Noise Adaptation
     * Feed 50 synthetic frames of heavy noise (-22 dBFS, RMS ≈ 0.080).
     * Assert that adaptedThreshold increases above baseThreshold (from 0.50 up to 0.65-0.75).
     */
    @Test
    fun testDisasterNoiseAdaptation() {
        // -22 dBFS correspond to RMS of ~10^(-22/20) ≈ 0.07943
        val disasterNoiseRms = 10.0.pow(-22.0 / 20.0).toFloat() // ~0.07943
        val frameSize = SILERO_FRAME_SIZE

        // Feed 50 frames of heavy noise
        for (i in 0 until 50) {
            val heavyFrame = generateSyntheticPcmFrame(disasterNoiseRms, frameSize)
            calculator.update(heavyFrame, speechProbability = 0.10f)
        }

        val adapted = calculator.adaptedThreshold
        println("Disaster Noise Adaptation: Initial base=$baseThreshold, Adapted threshold=$adapted, EMA RMS=${calculator.emaRms}")

        assertTrue(
            "Adapted threshold ($adapted) should increase above base threshold ($baseThreshold)",
            adapted > baseThreshold
        )
        assertTrue(
            "Adapted threshold ($adapted) should rise into 0.65..0.75 range",
            adapted in 0.65f..0.75f
        )
    }

    /**
     * Test 3: Asymmetric Adaptation (Speech Immunity)
     * Feed a sudden, high-energy 300ms burst (representing a distress shout).
     * Verify that because speech frames with P(speech) >= 0.30 are excluded from the noise-floor EMA,
     * the noise floor does NOT corrupt or spike during actual speech.
     */
    @Test
    fun testAsymmetricSpeechImmunity() {
        val initialEma = calculator.emaRms
        val initialThreshold = calculator.adaptedThreshold

        // 300ms at 16kHz is ~4800 samples = ~9 frames of 512 samples
        val highEnergyRms = 0.50f // High energy speech burst
        val frameSize = SILERO_FRAME_SIZE

        for (i in 0 until 10) {
            val speechFrame = generateSyntheticPcmFrame(highEnergyRms, frameSize)
            // Speech frames have P(speech) >= 0.30 (e.g. 0.85)
            calculator.update(speechFrame, speechProbability = 0.85f)
        }

        assertEquals(
            "EMA RMS should remain unaffected during speech frames",
            initialEma,
            calculator.emaRms,
            1e-6f
        )
        assertEquals(
            "Adapted threshold should remain unchanged during speech frames",
            initialThreshold,
            calculator.adaptedThreshold,
            1e-6f
        )
    }

    private fun generateSyntheticPcmFrame(targetRms: Float, size: Int): ShortArray {
        val frame = ShortArray(size)
        val amplitude = targetRms * 32767f
        for (i in 0 until size) {
            // Uniform random noise centered around 0 with target RMS
            val sampleVal = (Random.nextFloat() * 2f - 1f) * amplitude * 1.732f
            frame[i] = sampleVal.toInt().coerceIn(-32768, 32767).toShort()
        }
        return frame
    }
}
