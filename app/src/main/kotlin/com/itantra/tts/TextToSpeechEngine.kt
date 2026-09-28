package com.itantra.tts

import com.itantra.stt.RecognizedSentence

/**
 * Core abstraction for text-to-speech synthesis and playback.
 *
 * Implementations must:
 * - Synthesize audio from [RecognizedSentence.text] in the target language
 * - Play that audio on the device speaker
 * - For alert sentences ([RecognizedSentence.isAlert] == true):
 *     * Play at maximum volume on STREAM_ALARM
 *     * Hold AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE (non-interruptible)
 *     * Use QUEUE_FLUSH to interrupt any currently playing audio
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * TO SWAP IN A REAL MODEL: create a new class implementing this interface.
 * See README.md → "Swapping to Real Models". Only iTantraApplication.kt changes.
 * ─────────────────────────────────────────────────────────────────────────────
 */
interface TextToSpeechEngine {

    /**
     * Synthesize and play [sentence].
     * Alert sentences MUST be non-interruptible and play at maximum volume.
     * This method may return before playback completes.
     */
    fun speak(sentence: RecognizedSentence)

    /**
     * Synthesize and play [sentence], invoking [onAudioReady] when synthesis is complete and audio playback begins.
     */
    fun speak(sentence: RecognizedSentence, onAudioReady: ((ttsLatencyMs: Long) -> Unit)? = null) {
        speak(sentence)
    }

    /**
     * Synthesize and play [sentence], suspending until audio playback finishes completely.
     */
    suspend fun speakAndAwait(sentence: RecognizedSentence) {
        speak(sentence)
    }

    /** Begin loading a local language model before the first incoming transmission. */
    fun prewarm(languageCode: String) {}

    /** Replay locally cached synthesized audio. Returns false if it has expired. */
    fun replay(sequenceId: Int): Boolean = false

    /** Release all TTS resources. Must be called when the engine is no longer needed. */
    fun shutdown()
}
