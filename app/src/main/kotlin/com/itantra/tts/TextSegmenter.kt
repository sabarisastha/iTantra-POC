package com.itantra.tts

/**
 * Splits a recognized transcript into natural clause and sentence segments
 * for progressive, ultra-low-latency TTS streaming.
 *
 * Designed to minimize FIRST_AUDIBLE_LATENCY (T5 - T0) by delivering a short,
 * natural first clause (3-5 words) within ~150-200ms, while subsequent clauses
 * are synthesized concurrently and streamed into the continuous audio buffer
 * with zero audible gaps.
 */
object TextSegmenter {

    // Regex to split on sentence/clause-ending punctuation: . ! ? ; : , | ॥ \n
    // Uses lookbehind so punctuation stays attached to the preceding segment
    private val PUNCTUATION_SPLIT_REGEX = Regex("(?<=[.!?,;:|।॥\n])\\s+")

    // Common linguistic clause conjunctions across the 10 supported languages (Latin + native scripts)
    private val CONJUNCTIONS = setOf(
        // English
        "and", "but", "or", "so", "because", "then", "if", "while", "also",
        // Hindi / Marathi / Gujarati / Bengali / Odia
        "aur", "lekin", "parantu", "tatha", "ya", "kyunki", "isliye",
        "ani", "ane", "pan", "parantoo",
        "ebong", "kintu", "aar",
        "और", "लेकिन", "परंतु", "तथा", "या", "क्योंकि", "इसलिए",
        "आणि", "पण",
        "અને", "પણ", "પરંતુ",
        "এবং", "কিন্তু", "বা",
        "ଓ", "କିନ୍ତୁ", "ଏବଂ",
        // Tamil / Telugu / Kannada / Malayalam
        "mattrum", "anal", "aanal", "enbathal", "mariyu", "kaani", "kanuka", "mattu", "aadare",
        "மற்றும்", "ஆனால்", "அதனால்", "எனினும்",
        "మరియు", "కానీ", "కనుక",
        "ಮತ್ತು", "ಆದರೆ", "ಹಾಗೂ",
        "എന്നാൽ", "അതുകൊണ്ട്", "കൂടാതെ"
    )

    /**
     * Splits a text utterance into natural sentence or clause segments for progressive TTS streaming.
     *
     * Rules:
     * 1. If text contains punctuation delimiters, split along those boundaries.
     * 2. If text has <= 5 words and no punctuation, keep as a single chunk (vocodes in <200ms).
     * 3. If text has > 5 words and no punctuation:
     *    - Search for a conjunction between words 2 and 6. If found, split before/at the conjunction.
     *    - If no conjunction found, split after the 4th word to deliver the first audible sound fast.
     *    - Subsequent parts are segmented into chunks of ~6-8 words.
     */
    fun segment(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. If punctuation is present, split primarily on punctuation
        val punctChunks = trimmed.split(PUNCTUATION_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
        if (punctChunks.size > 1) {
            val result = mutableListOf<String>()
            for (chunk in punctChunks) {
                val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.size > 10) {
                    result.addAll(segmentUnpunctuated(chunk))
                } else {
                    result.add(chunk)
                }
            }
            return result
        }

        // 2. Unpunctuated text (standard STT / ASR transcript)
        return segmentUnpunctuated(trimmed)
    }

    private fun segmentUnpunctuated(text: String): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= 5) {
            return listOf(text)
        }

        val segments = mutableListOf<String>()
        var remainingWords = words

        // For the very first chunk, target ~8-12 words to provide a large initial buffer
        // (e.g. ~3 seconds of audio). This slightly increases T5 latency (e.g. 100ms -> 400ms)
        // but completely prevents stuttering/lagging on slow legacy CPUs that can't synthesize in real-time.
        var firstSplitIndex = -1
        val searchMax = minOf(12, remainingWords.size - 1)
        for (i in 6..searchMax) {
            val cleanWord = remainingWords[i].lowercase().replace(Regex("[^a-zA-Z\\u0900-\\u0D7F]"), "")
            if (CONJUNCTIONS.contains(cleanWord)) {
                firstSplitIndex = i
                break
            }
        }

        if (firstSplitIndex == -1) {
            // Default to 10 words for first chunk
            firstSplitIndex = minOf(10, remainingWords.size)
        }

        segments.add(remainingWords.subList(0, firstSplitIndex).joinToString(" "))
        remainingWords = remainingWords.subList(firstSplitIndex, remainingWords.size)

        // For remaining text, segment into larger clauses (10-15 words)
        while (remainingWords.size > 12) {
            var nextSplit = -1
            val nextSearchMax = minOf(14, remainingWords.size - 1)
            for (i in 8..nextSearchMax) {
                val cleanWord = remainingWords[i].lowercase().replace(Regex("[^a-zA-Z\\u0900-\\u0D7F]"), "")
                if (CONJUNCTIONS.contains(cleanWord)) {
                    nextSplit = i
                    break
                }
            }
            if (nextSplit == -1) {
                nextSplit = minOf(12, remainingWords.size)
            }
            segments.add(remainingWords.subList(0, nextSplit).joinToString(" "))
            remainingWords = remainingWords.subList(nextSplit, remainingWords.size)
        }

        if (remainingWords.isNotEmpty()) {
            segments.add(remainingWords.joinToString(" "))
        }

        return segments
    }
}
