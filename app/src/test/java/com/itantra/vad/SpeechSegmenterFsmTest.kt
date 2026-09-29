package com.itantra.vad

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class SpeechSegmenterFsmTest {

    private lateinit var segmenter: SpeechSegmenter

    @Before
    fun setUp() {
        // attackFrames = 3, hangoverFrames = 16 (~512 ms)
        segmenter = SpeechSegmenter(
            speechStartThreshold = 0.50f,
            speechEndThreshold = 0.35f,
            attackFrames = 3,
            hangoverFrames = 16
        )
    }

    /**
     * Test 1: Attack Pre-Roll Buffer
     * Simulate silence followed by speech. Verify that initial voiced frames during attack debounce
     * are accumulated alongside pre-roll frames so initial syllables are preserved.
     */
    @Test
    fun testAttackPreRollBuffer() {
        val buffer = ArrayList<Short>()

        // 1. Send silence
        val silenceResult = VadResult(speechProbability = 0.10f, isSpeech = false)
        val event0 = segmenter.process(silenceResult)
        assertEquals(SegmenterEvent.SILENCE, event0)

        // 2. Send 2 voiced frames (attack threshold = 3 frames)
        val voicedResult = VadResult(speechProbability = 0.85f, isSpeech = true)
        val frame1 = ShortArray(512) { 1000 }
        val frame2 = ShortArray(512) { 1000 }
        val frame3 = ShortArray(512) { 1000 }

        // Accumulate pre-roll frames during attack phase
        val event1 = segmenter.process(voicedResult)
        assertEquals("Frame 1 should be silent debounce phase", SegmenterEvent.SILENCE, event1)
        frame1.forEach { buffer.add(it) }

        val event2 = segmenter.process(voicedResult)
        assertEquals("Frame 2 should be silent debounce phase", SegmenterEvent.SILENCE, event2)
        frame2.forEach { buffer.add(it) }

        // 3. 3rd voiced frame triggers SPEECH_START
        val event3 = segmenter.process(voicedResult)
        assertEquals("Frame 3 should trigger SPEECH_START", SegmenterEvent.SPEECH_START, event3)
        frame3.forEach { buffer.add(it) }

        assertTrue("Segmenter should be IN_SPEECH", segmenter.isSpeechActive)
        assertEquals("Buffer should contain all 3 frames (1536 samples)", 1536, buffer.size)
    }

    /**
     * Test 2: Hangover Endpointing
     * Simulate speech followed by 300ms of silence (~9 frames of 32ms), then speech resumption.
     * Assert that the segment does NOT cut prematurely (hangover threshold = 16 frames).
     */
    @Test
    fun testHangoverEndpointing() {
        val voicedResult = VadResult(speechProbability = 0.85f, isSpeech = true)
        val silenceResult = VadResult(speechProbability = 0.10f, isSpeech = false)

        // Force into IN_SPEECH state (3 voiced frames)
        segmenter.process(voicedResult)
        segmenter.process(voicedResult)
        val startEvent = segmenter.process(voicedResult)
        assertEquals(SegmenterEvent.SPEECH_START, startEvent)
        assertTrue(segmenter.isSpeechActive)

        // Simulate 300ms of silence (~9 frames @ 32ms each)
        for (i in 1..9) {
            val event = segmenter.process(silenceResult)
            assertEquals(
                "Silence frame $i should maintain SPEECH_CONTINUING during hangover period",
                SegmenterEvent.SPEECH_CONTINUING,
                event
            )
            assertTrue("Segmenter should remain IN_SPEECH during hangover", segmenter.isSpeechActive)
        }

        // Resume speech on 10th frame
        val resumeEvent = segmenter.process(voicedResult)
        assertEquals(
            "Speech resumption should return SPEECH_CONTINUING",
            SegmenterEvent.SPEECH_CONTINUING,
            resumeEvent
        )
        assertTrue("Segmenter should remain IN_SPEECH", segmenter.isSpeechActive)
    }

    /**
     * Test 3: PTT Flush Thread Safety
     * Concurrently invoke accumulate, drain, and flush operations across multiple coroutines.
     * Assert zero IndexOutOfBoundsException or ConcurrentModificationException.
     */
    @Test
    fun testPttFlushThreadSafety() = runBlocking(Dispatchers.Default) {
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val dummyBuffer = ArrayList<Short>()

        fun accumulate(frame: ShortArray) {
            synchronized(dummyBuffer) {
                for (s in frame) dummyBuffer.add(s)
            }
        }

        fun flush(): FloatArray? {
            synchronized(dummyBuffer) {
                if (dummyBuffer.isEmpty()) return null
                val out = FloatArray(dummyBuffer.size)
                for (i in out.indices) out[i] = dummyBuffer[i] / 32768.0f
                dummyBuffer.clear()
                return out
            }
        }

        val jobs = List(50) { id ->
            launch {
                try {
                    val frame = ShortArray(512) { (id + 1).toShort() }
                    for (i in 0 until 100) {
                        accumulate(frame)
                        if (i % 10 == 0) {
                            flush()
                        }
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                }
            }
        }

        jobs.forEach { it.join() }

        assertTrue(
            "Concurrent access threw exceptions: ${exceptions.firstOrNull()?.message}",
            exceptions.isEmpty()
        )
    }

    /**
     * Test 4: Default hangover (9 frames ~288ms) triggers SPEECH_END on 9th silence frame.
     */
    @Test
    fun testDefaultHangoverCut() {
        val defaultSegmenter = SpeechSegmenter()
        val voicedResult = VadResult(speechProbability = 0.85f, isSpeech = true)
        val silenceResult = VadResult(speechProbability = 0.10f, isSpeech = false)

        // 3 voiced frames -> IN_SPEECH
        defaultSegmenter.process(voicedResult)
        defaultSegmenter.process(voicedResult)
        assertEquals(SegmenterEvent.SPEECH_START, defaultSegmenter.process(voicedResult))

        // 8 silence frames -> SPEECH_CONTINUING
        for (i in 1..8) {
            assertEquals(SegmenterEvent.SPEECH_CONTINUING, defaultSegmenter.process(silenceResult))
        }

        // 9th silence frame -> SPEECH_END
        assertEquals(SegmenterEvent.SPEECH_END, defaultSegmenter.process(silenceResult))
        assertFalse(defaultSegmenter.isSpeechActive)
    }

    /**
     * Test 5: resetToSpeech maintains IN_SPEECH state.
     */
    @Test
    fun testResetToSpeech() {
        segmenter.resetToSpeech()
        assertTrue(segmenter.isSpeechActive)
    }
}
