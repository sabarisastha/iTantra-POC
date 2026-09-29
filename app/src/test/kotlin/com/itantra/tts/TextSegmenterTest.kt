package com.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun `empty and blank text returns empty list`() {
        assertEquals(emptyList<String>(), TextSegmenter.segment(""))
        assertEquals(emptyList<String>(), TextSegmenter.segment("   "))
    }

    @Test
    fun `short phrase without punctuation is kept as single chunk`() {
        val result = TextSegmenter.segment("namaste aap kaise hain")
        assertEquals(1, result.size)
        assertEquals("namaste aap kaise hain", result[0])
    }

    @Test
    fun `punctuated sentences are split at punctuation marks`() {
        val text = "Emergency response unit is on standby. I need immediate assistance near the base."
        val result = TextSegmenter.segment(text)
        assertEquals(2, result.size)
        assertEquals("Emergency response unit is on standby.", result[0])
        assertEquals("I need immediate assistance near the base.", result[1])
    }

    @Test
    fun `unpunctuated long English sentence splits at conjunction`() {
        val text = "we are moving to sector four and need immediate backup at the ridge"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected at least 2 segments", result.size >= 2)
        assertEquals("we are moving to sector four and", result[0])
        assertTrue("Second segment should contain the rest", result[1].contains("need immediate backup"))
    }

    @Test
    fun `unpunctuated long sentence without conjunction splits at target limit`() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected multiple segments", result.size >= 2)
        assertEquals("one two three four five six seven eight nine", result[0])
    }

    @Test
    fun `Hindi sentence with conjunction splits appropriately`() {
        val text = "hum yahan pahunch gaye aur ab aage badh rahe hain sabhi ko Soochit kar dein"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected at least 2 segments", result.size >= 2)
    }

    @Test
    fun `Tamil punctuated sentence splits on punctuation`() {
        val text = "அவசர உதவிக்குழு தயாராக உள்ளது நிலைமை மிக மோசமாக உள்ளது. எனக்கு உடனடியாக பாதுகாப்பு உதவி தேவைப்படுகிறது மற்றும் வழிகாட்டல் அவசியம்."
        val result = TextSegmenter.segment(text)
        assertEquals(2, result.size)
    }
}
