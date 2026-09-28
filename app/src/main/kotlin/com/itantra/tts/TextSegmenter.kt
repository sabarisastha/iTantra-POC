package com.itantra.tts

/**
 * Splits recognized STT text into natural sentence/clause segments
 * for progressive low-latency TTS.
 *
 * Design goals:
 * - Avoid extremely tiny TTS chunks.
 * - Keep the first chunk large enough to reduce playback underruns.
 * - Preserve natural punctuation.
 * - Avoid splitting on commas because comma-based chunks can become too short.
 * - Keep conjunctions attached to the preceding clause when possible.
 */
object TextSegmenter {

    /**
     * Sentence/clause delimiters.
     *
     * NOTE:
     * Comma is intentionally NOT included.
     *
     * Splitting on commas frequently produces tiny chunks such as:
     *
     * "we need help,"
     *
     * which can finish playing before the next clause is synthesized.
     */
    private val PUNCTUATION_SPLIT_REGEX =
        Regex("(?<=[.!?;:|।॥\\n])\\s+")

    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Common conjunctions / connectors across supported languages.
     *
     * These are only used as segmentation hints.
     */
    private val CONJUNCTIONS = setOf(
        // English
        "and",
        "but",
        "or",
        "so",
        "because",
        "then",
        "if",
        "while",
        "also",

        // Latin transliterations
        "aur",
        "lekin",
        "parantu",
        "tatha",
        "ya",
        "kyunki",
        "isliye",
        "ani",
        "ane",
        "pan",
        "parantoo",
        "ebong",
        "kintu",
        "aar",
        "mattrum",
        "anal",
        "aanal",
        "enbathal",
        "mariyu",
        "kaani",
        "kanuka",
        "mattu",
        "aadare",

        // Hindi
        "और",
        "लेकिन",
        "परंतु",
        "तथा",
        "या",
        "क्योंकि",
        "इसलिए",

        // Marathi
        "आणि",
        "पण",

        // Gujarati
        "અને",
        "પણ",
        "પરંતુ",

        // Bengali
        "এবং",
        "কিন্তু",
        "বা",

        // Odia
        "ଓ",
        "କିନ୍ତୁ",
        "ଏବଂ",

        // Tamil
        "மற்றும்",
        "ஆனால்",
        "அதனால்",
        "எனினும்",

        // Telugu
        "మరియు",
        "కానీ",
        "కనుక",

        // Kannada
        "ಮತ್ತು",
        "ಆದರೆ",
        "ಹಾಗೂ",

        // Malayalam
        "എന്നാൽ",
        "അതുകൊണ്ട്",
        "കൂടാതെ"
    )

    private const val SHORT_CHUNK_WORD_LIMIT = 5
    private const val FIRST_CHUNK_TARGET_WORDS = 9
    private const val FIRST_CONJUNCTION_SEARCH_START = 4
    private const val FIRST_CONJUNCTION_SEARCH_END = 9

    private const val REMAINING_TARGET_WORDS = 11
    private const val REMAINING_CONJUNCTION_SEARCH_START = 7
    private const val REMAINING_CONJUNCTION_SEARCH_END = 14

    /**
     * Main segmentation entry point.
     */
    fun segment(text: String): List<String> {
        val trimmed = text.trim()

        if (trimmed.isEmpty()) {
            return emptyList()
        }

        /*
         * First split on natural sentence/clause punctuation.
         */
        val punctuationChunks = trimmed
            .split(PUNCTUATION_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (punctuationChunks.size <= 1) {
            return segmentUnpunctuated(trimmed)
        }

        val initialSegments = mutableListOf<String>()

        for (chunk in punctuationChunks) {
            val words = wordsOf(chunk)

            if (words.size > 12) {
                initialSegments += segmentUnpunctuated(chunk)
            } else {
                initialSegments += chunk
            }
        }

        /*
         * Merge tiny punctuation-created segments.
         *
         * Example:
         *
         * "Emergency. I need help."
         *
         * We do NOT want "Emergency." to become a tiny independent
         * stream if the next clause is immediately available.
         */
        return mergeShortSegments(initialSegments)
    }

    /**
     * Segments text that doesn't contain a usable sentence delimiter.
     */
    private fun segmentUnpunctuated(text: String): List<String> {
        val words = wordsOf(text)

        if (words.size <= 5) {
            return listOf(text.trim())
        }

        val result = mutableListOf<String>()

        var start = 0

        /*
         * First chunk:
         *
         * Target around 9 words.
         *
         * Search for a conjunction so we can finish the first chunk
         * at a reasonably natural semantic boundary.
         */
        val firstSearchStart =
            minOf(
                FIRST_CONJUNCTION_SEARCH_START,
                words.lastIndex
            )

        val firstSearchEnd =
            minOf(
                FIRST_CONJUNCTION_SEARCH_END,
                words.lastIndex
            )

        var firstEndExclusive = -1

        for (i in firstSearchStart..firstSearchEnd) {
            val normalized = normalizeWord(words[i])

            if (normalized in CONJUNCTIONS) {
                /*
                 * Include the conjunction in the preceding chunk.
                 *
                 * This avoids starting a new TTS chunk with:
                 *
                 * "and ..."
                 */
                firstEndExclusive = i + 1
                break
            }
        }

        if (firstEndExclusive <= 0) {
            firstEndExclusive =
                minOf(
                    FIRST_CHUNK_TARGET_WORDS,
                    words.size
                )
        }

        result += joinWords(
            words,
            start,
            firstEndExclusive
        )

        start = firstEndExclusive

        /*
         * Remaining chunks.
         */
        while (words.size - start > REMAINING_TARGET_WORDS) {

            val remainingCount = words.size - start

            val searchStart =
                minOf(
                    REMAINING_CONJUNCTION_SEARCH_START,
                    remainingCount - 1
                )

            val searchEnd =
                minOf(
                    REMAINING_CONJUNCTION_SEARCH_END,
                    remainingCount - 1
                )

            var nextEndExclusive = -1

            if (searchStart <= searchEnd) {
                for (offset in searchStart..searchEnd) {
                    val index = start + offset
                    val normalized = normalizeWord(words[index])

                    if (normalized in CONJUNCTIONS) {
                        nextEndExclusive = index + 1
                        break
                    }
                }
            }

            if (nextEndExclusive <= start) {
                nextEndExclusive =
                    minOf(
                        start + REMAINING_TARGET_WORDS,
                        words.size
                    )
            }

            result += joinWords(
                words,
                start,
                nextEndExclusive
            )

            start = nextEndExclusive
        }

        if (start < words.size) {
            result += joinWords(
                words,
                start,
                words.size
            )
        }

        return mergeShortSegments(result)
    }

    /**
     * Prevent extremely short chunks from becoming independent
     * playback streams.
     */
    private fun mergeShortSegments(
        segments: List<String>
    ): List<String> {

        if (segments.size <= 1) {
            return segments
        }

        val result = mutableListOf<String>()
        var pending = segments.first().trim()

        for (i in 1 until segments.size) {

            val current = segments[i].trim()

            val pendingWords = wordsOf(pending).size

            if (pendingWords < SHORT_CHUNK_WORD_LIMIT) {
                pending = "$pending $current".trim()
            } else {
                result += pending
                pending = current
            }
        }

        if (pending.isNotEmpty()) {
            if (
                result.isNotEmpty() &&
                wordsOf(pending).size < SHORT_CHUNK_WORD_LIMIT
            ) {
                result[result.lastIndex] =
                    "${result.last()} $pending".trim()
            } else {
                result += pending
            }
        }

        return result.filter { it.isNotBlank() }
    }

    private fun wordsOf(text: String): List<String> {
        return text
            .trim()
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
    }

    private fun normalizeWord(word: String): String {
        /*
         * Remove only punctuation around the token.
         *
         * Do NOT restrict Unicode ranges here, because the project
         * supports multiple Indian writing systems.
         */
        return word
            .trim()
            .trim(
                '.', '!', '?', ',', ';', ':',
                '|', '।', '॥', '(', ')', '[', ']',
                '{', '}', '"', '\'', '“', '”', '‘', '’'
            )
            .lowercase()
    }

    private fun joinWords(
        words: List<String>,
        start: Int,
        endExclusive: Int
    ): String {
        if (start >= endExclusive) {
            return ""
        }

        return words
            .subList(start, endExclusive)
            .joinToString(" ")
            .trim()
    }
}