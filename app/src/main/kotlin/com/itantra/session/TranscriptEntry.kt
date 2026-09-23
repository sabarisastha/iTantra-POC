package com.itantra.session

/**
 * Direction of a transcript entry relative to this device.
 */
enum class Direction { SENT, RECEIVED }

/**
 * A single entry in the transcript log, carrying the full timestamp chain needed
 * to compute per-segment latency for the debug overlay.
 *
 * Timestamp chain (all in [System.currentTimeMillis] epoch ms):
 *   [micPauseDetectedMs] → [sttCompleteMs] → [sentMs] → [receivedMs] → [ttsStartedMs]
 *
 * For [Direction.SENT] entries, [receivedMs] and [ttsStartedMs] are 0.
 * For [Direction.RECEIVED] entries, [micPauseDetectedMs] and [sttCompleteMs] are
 * copied from the sender's [sentMs] (best approximation without clock sync).
 */
data class TranscriptEntry(
    val direction: Direction,
    val text: String,
    val languageCode: String,
    val isAlert: Boolean,
    val sequenceId: Int,
    val sessionMode: SessionMode,
    
    // Emergency Location Data
    val latitude: Double? = null,
    val longitude: Double? = null,

    /** When the VAD detected end of the speech segment on the sender's device. */
    val micPauseDetectedMs: Long,
    /** When the STT engine emitted the transcript (≈ micPauseDetectedMs in mock). */
    val sttCompleteMs: Long,
    /** When transport.send() was called. */
    val sentMs: Long,
    /** When the payload was dequeued from the receive flow. 0 for SENT entries. */
    val receivedMs: Long,
    /** When TTS.speak() was invoked. 0 for SENT entries. */
    val ttsStartedMs: Long,
) {
    /** Model inference latency (0 ms in mock; real value in production). */
    val deltaModelMs: Long   get() = sttCompleteMs - micPauseDetectedMs

    /** Time from STT output to bytes on the wire. */
    val deltaQueueMs: Long   get() = sentMs - sttCompleteMs

    /** Network + socket latency. Meaningful only for RECEIVED entries. */
    val deltaNetworkMs: Long get() = if (receivedMs > 0L) receivedMs - sentMs else 0L

    /** Receive-to-TTS latency. Meaningful only for RECEIVED entries. */
    val deltaTtsStartMs: Long get() =
        if (ttsStartedMs > 0L && receivedMs > 0L) ttsStartedMs - receivedMs else 0L

    /** Total end-to-end latency: mic pause → TTS started. */
    val totalLatencyMs: Long get() =
        deltaModelMs + deltaQueueMs + deltaNetworkMs + deltaTtsStartMs
}
