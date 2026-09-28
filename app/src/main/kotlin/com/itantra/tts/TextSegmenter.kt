package com.itantra.tts

/**
 * Splits recognized STT text into natural sentence/clause segments
 * for progressive low-latency TTS.
 *
 * Design goals:
 * - Force real chunking on typical short/medium PTT phrases (3-10 words),
 *   since STT output has NO punctuation and the previous thresholds
 *   (skip-split under 5 words, ~9-word first chunk target) meant most
 *   real walkie-talkie utterances collapsed into ONE chunk — silently
 *   defeating the progressive-streaming architecture and reproducing
 *   the full monolithic-synthesis delay this system was built to avoid.
 * - Keep the first chunk small (~4 words) so first-audio latency is
 *   driven by a small chunk's inference time, not the whole sentence.
 * - Preserve natural punctuation splitting when present.
 * - Avoid splitting on commas because comma-based chunks can become too short.
 * - Keep conjunctions attached to the preceding clause when possible.
 * - Only re-merge chunks that are genuinely too tiny to stand alone (1-2 words).
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

    // Only sentences shorter than 3 words stay as a single chunk
    var minWordsToSplit = 3

    // First chunk target is 2 words so first-audio latency is
    // driven by synthesizing only ~2 words (~320ms on device).
    var firstChunkTargetWords = 2
    var firstConjunctionSearchStart = 2
    var firstConjunctionSearchEnd = 3

    // Remaining chunks target ~5 words
    var remainingTargetWords = 5
    var remainingConjunctionSearchStart = 3
    var remainingConjunctionSearchEnd = 6

    // Only merge chunks that are truly single orphan words (<= 1 word).
    var shortChunkWordLimit = 1

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
     *
     * This is the path essentially ALL real-time STT output takes, since
     * ASR models emit raw text with no punctuation.
     */
    private fun segmentUnpunctuated(text: String): List<String> {
        val words = wordsOf(text)

        if (words.size < minWordsToSplit) {
            return listOf(text.trim())
        }

        val result = mutableListOf<String>()

        var start = 0

        /*
         * First chunk:
         *
         * Target around 2 words.
         *
         * Search for a conjunction so we can finish the first chunk
         * at a reasonably natural semantic boundary.
         */
        val firstSearchStart =
            minOf(
                firstConjunctionSearchStart,
                words.lastIndex
            )

        val firstSearchEnd =
            minOf(
                firstConjunctionSearchEnd,
                words.lastIndex
            )

        var firstEndExclusive = -1

        for (i in firstSearchStart..firstSearchEnd) {
            val normalized = normalizeWord(words[i])

            if (normalized in CONJUNCTIONS) {
                firstEndExclusive = i + 1
                break
            }
        }

        if (firstEndExclusive <= 0) {
            firstEndExclusive =
                minOf(
                    firstChunkTargetWords,
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
        while (words.size - start > remainingTargetWords) {

            val remainingCount = words.size - start

            val searchStart =
                minOf(
                    remainingConjunctionSearchStart,
                    remainingCount - 1
                )

            val searchEnd =
                minOf(
                    remainingConjunctionSearchEnd,
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
                        start + remainingTargetWords,
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
     * Prevent extremely short chunks (1-2 words) from becoming independent
     * playback streams. Chunks of 3+ words are left alone — merging those
     * back together would undo the whole point of this fix.
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

            if (pendingWords <= shortChunkWordLimit) {
                pending = "$pending $current".trim()
            } else {
                result += pending
                pending = current
            }
        }

        if (pending.isNotEmpty()) {
            if (
                result.isNotEmpty() &&
                wordsOf(pending).size <= shortChunkWordLimit
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