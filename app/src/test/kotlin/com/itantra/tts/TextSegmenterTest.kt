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
    fun `short phrase without punctuation is kept as single chunk if under 3 words`() {
        val result = TextSegmenter.segment("namaste aap")
        assertEquals(1, result.size)
        assertEquals("namaste aap", result[0])
    }
    
    @Test
    fun `medium phrase without punctuation splits aggressively into 2-word first chunk`() {
        // "namaste aap kaise hain" (4 words)
        // With minWordsToSplit=3 and firstChunkTargetWords=2:
        // Result: "namaste aap", "kaise hain"
        val result = TextSegmenter.segment("namaste aap kaise hain")
        assertEquals(2, result.size)
        assertEquals("namaste aap", result[0])
        assertEquals("kaise hain", result[1])
    }

    @Test
    fun `punctuated sentences are split at punctuation marks but tiny clauses are merged`() {
        // "Emergency." (1 word) gets merged with "I need assistance near the base."
        val text = "Emergency. I need assistance near the base."
        val result = TextSegmenter.segment(text)
        // It merges the 1-word punctuation chunk, resulting in a single 7-word chunk.
        assertEquals(1, result.size)
        assertEquals("Emergency. I need assistance near the base.", result[0])
    }

    @Test
    fun `long punctuated sentences split properly`() {
        val text = "We have an emergency situation here. I need assistance near the base."
        val result = TextSegmenter.segment(text)
        // 6 words and 7 words. Punctuation split preserves < 12 word chunks.
        // So they might stay intact or get split further if they go to segmentUnpunctuated?
        // Actually punctuationChunks < 12 words do NOT go to segmentUnpunctuated in the new code!
        // "if (words.size > 12) { initialSegments += segmentUnpunctuated(chunk) } else { initialSegments += chunk }"
        // So they should be exactly 2 chunks!
        assertEquals(2, result.size)
        assertEquals("We have an emergency situation here.", result[0])
        assertEquals("I need assistance near the base.", result[1])
    }

    @Test
    fun `unpunctuated long English sentence splits at conjunction or length limit`() {
        val text = "we are moving to sector four and need immediate backup at the ridge"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected at least 2 segments", result.size >= 2)
        // first chunk target is ~2 words
        assertTrue("First segment should be small", result[0].split(" ").size <= 4)
    }

    @Test
    fun `unpunctuated long sentence without conjunction splits into small chunks`() {
        val text = "one two three four five six seven eight nine ten eleven"
        val result = TextSegmenter.segment(text)
        assertTrue("Expected multiple segments", result.size >= 2)
        assertEquals("one two", result[0])
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
        val text = "நமக்கு ஒரு அவசர நிலை உள்ளது. எனக்கு உதவி தேவை." // "We have an emergency situation. I need help."
        val result = TextSegmenter.segment(text)
        assertEquals(2, result.size)
        assertEquals("நமக்கு ஒரு அவசர நிலை உள்ளது.", result[0])
        assertEquals("எனக்கு உதவி தேவை.", result[1])
    }
}
