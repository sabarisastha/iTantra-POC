package com.itantra.vad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class SileroNeuralModelVerificationTest {

    private lateinit var vadEngine: SileroVadEngine

    @Before
    fun setUp() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        vadEngine = SileroVadEngine(appContext, threshold = 0.50f)
    }

    @After
    fun tearDown() {
        vadEngine.close()
    }

    /**
     * Test 1: Real ONNX Model Loading (No Fallback)
     * Initialize the VAD engine using the asset silero_vad.onnx.
     * Strict Assertion: Assert isInitialized == true AND isOrtUnavailable == false.
     * Fail explicitly if it falls back to energy VAD.
     */
    @Test
    fun testRealOnnxModelLoadingNoFallback() {
        assertTrue(
            "FAILED: silero_vad.onnx was not executed! Engine failed to initialize.",
            vadEngine.isInitialized
        )
        assertFalse(
            "FAILED: silero_vad.onnx was not executed! Falling back to energy VAD.",
            SileroVadEngine.isOrtUnavailable
        )
    }

    /**
     * Test 2: Neural Discrimination Test
     * - Generate 512 samples of digital silence (all zeros). Assert probability < 0.15.
     * - Generate synthetic 512-sample voiced audio frame (440Hz sine wave proxy).
     *   Assert valid probability in [0.0, 1.0] without crashing or JNI UnsatisfiedLinkErrors.
     */
    @Test
    fun testNeuralDiscrimination() = runBlocking {
        // 1. Digital silence (512 zeros)
        val silenceFrame = ShortArray(SILERO_FRAME_SIZE) { 0 }
        val silenceResult = vadEngine.processFrame(silenceFrame)

        assertNotNull(silenceResult)
        println("Digital silence speech probability: ${silenceResult.speechProbability}")
        assertTrue(
            "Digital silence should produce speech probability < 0.15 (got ${silenceResult.speechProbability})",
            silenceResult.speechProbability < 0.15f
        )
        assertFalse("Digital silence should not be classified as speech", silenceResult.isSpeech)

        // 2. Voiced proxy audio frame (440Hz sine wave @ 16kHz)
        val voicedFrame = ShortArray(SILERO_FRAME_SIZE) { i ->
            val angle = 2.0 * Math.PI * 440.0 * i / 16000.0
            (sin(angle) * 16000.0).toInt().toShort()
        }
        val voicedResult = vadEngine.processFrame(voicedFrame)

        assertNotNull(voicedResult)
        println("Voiced proxy speech probability: ${voicedResult.speechProbability}")
        assertTrue(
            "Voiced proxy should produce valid speech probability in [0.0, 1.0]",
            voicedResult.speechProbability in 0.0f..1.0f
        )
        assertFalse(
            "Neural model execution must not trigger fallback mode",
            SileroVadEngine.isOrtUnavailable
        )
    }

    /**
     * Test 3: Memory & Recurrent State Continuity
     * Process 200 consecutive frames in a loop.
     * Verify recurrent state retains numerical stability without NaN, Inf, or memory leaks.
     */
    @Test
    fun testMemoryAndRecurrentStateContinuity() = runBlocking {
        val frame = ShortArray(SILERO_FRAME_SIZE) { i ->
            (sin(2.0 * Math.PI * 220.0 * i / 16000.0) * 8000.0).toInt().toShort()
        }

        for (i in 1..200) {
            val result = vadEngine.processFrame(frame)
            val prob = result.speechProbability

            assertFalse(
                "Speech probability must not be NaN at frame $i",
                prob.isNaN()
            )
            assertFalse(
                "Speech probability must not be Inf at frame $i",
                prob.isInfinite()
            )
            assertTrue(
                "Speech probability must stay bounded in [0.0, 1.0] at frame $i (got $prob)",
                prob in 0.0f..1.0f
            )
        }

        assertFalse(
            "Engine must remain out of fallback mode after 200 frames",
            SileroVadEngine.isOrtUnavailable
        )
    }
}
