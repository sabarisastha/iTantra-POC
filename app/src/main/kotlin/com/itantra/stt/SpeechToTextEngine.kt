package com.itantra.stt

import kotlinx.coroutines.flow.Flow

/**
 * Core abstraction for speech-to-text conversion.
 *
 * Implementations must:
 * - Capture audio from the microphone (via AudioRecord or equivalent)
 * - Detect speech segment boundaries (pause / silence detection — VAD)
 * - Emit one [RecognizedSentence] per detected segment
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TO SWAP IN A REAL MODEL: create a new class implementing this interface.
 * See README.md → "Swapping to Real Models". Only iTantraApplication.kt changes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
interface SpeechToTextEngine {

    /**
     * Hot stream of recognized speech segments.
     * Emits whenever the VAD detects a speech-to-silence transition.
     * Collect this *before* calling [start].
     */
    val recognizedSentences: Flow<RecognizedSentence>

    /**
     * Set the current target language for recognition.
     */
    fun setLanguage(languageCode: String)

    /** Begin microphone capture and recognition. Idempotent. */
    fun start()

    /**
     * Signal the engine to stop capturing.
     * Any segment whose VAD boundary was already detected may still be emitted.
     */
    fun stop()

    /** Release all STT resources. Safe to call multiple times. */
    fun shutdown() {}
}

/**
 * A single recognized speech segment emitted by [SpeechToTextEngine].
 *
 * @param text          The transcript text (placeholder in mock; model output in production).
 * @param languageCode  BCP-47 language code, e.g. "hi", "en".
 * @param isAlert       True if this segment should be treated as a priority alert.
 * @param timestampMs   [System.currentTimeMillis] when the VAD silence boundary was detected.
 * @param sequenceId    Monotonically increasing per engine instance; used for deduplication.
 */
data class RecognizedSentence(
    val text: String,
    val languageCode: String,
    val isAlert: Boolean,
    val timestampMs: Long,
    val sequenceId: Int,
)
