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
        val text = "Emergency. I need assistance near the base."
        val result = TextSegmenter.segment(text)
        assertEquals(2, result.size)
        assertEquals("Emergency.", result[0])
        assertEquals("I need assistance near the base.", result[1])
    }

    @Test
    fun `unpunctuated long English sentence splits at conjunction`() {
        val text = "we are moving to sector four and need immediate backup at the ridge"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected at least 2 segments", result.size >= 2)
        assertEquals("we are moving to sector four", result[0])
        assertTrue("Second segment should contain the rest", result[1].contains("need immediate backup"))
    }

    @Test
    fun `unpunctuated long sentence without conjunction splits at 4th word`() {
        val text = "one two three four five six seven eight nine ten eleven"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected multiple segments", result.size >= 2)
        assertEquals("one two three four", result[0])
    }

    @Test
    fun `Hindi sentence with conjunction splits appropriately`() {
        val text = "hum yahan pahunch gaye aur ab aage badh rahe hain"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected at least 2 segments", result.size >= 2)
        assertTrue("First segment should be concise", result[0].split(" ").size <= 5)
    }

    @Test
    fun `Tamil punctuated sentence splits on punctuation`() {
        val text = "அவசரம். எனக்கு உதவி தேவை."
        val result = TextSegmenter.segment(text)
        assertEquals(2, result.size)
        assertEquals("அவசரம்.", result[0])
        assertEquals("எனக்கு உதவி தேவை.", result[1])
    }
}
