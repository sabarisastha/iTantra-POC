package com.itantra.vad

/**
 * Interface for Voice Activity Detection engine.
 */
interface VadEngine {
    /**
     * Process a single PCM audio frame (e.g. 512 samples at 16kHz mono PCM-16).
     * @return [VadResult] with probability and binary speech flag.
     */
    suspend fun processFrame(pcmFrame: ShortArray): VadResult

    /** Reset recurrent/hidden state across speech utterances */
    fun reset()

    /** Release all native / ONNX session resources */
    fun close()
}

/**
 * Result of VAD inference for a single frame.
 */
data class VadResult(
    val speechProbability: Float,
    val isSpeech: Boolean,
)