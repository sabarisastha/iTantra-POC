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
    val accuracy: Float? = null,
    val locationTimestampMs: Long? = null,
    val locationStatus: String? = null,

    // Component-level Latencies (independent of clock skew)
    val sttLatencyMs: Long = 0L,
    val queueLatencyMs: Long = 0L,
    val networkLatencyMs: Long = 0L,
    val ttsLatencyMs: Long = 0L,
    val totalE2eLatencyMs: Long = 0L,
    val rtf: Float = 0f,

    /** When the VAD detected end of the speech segment on the sender's device. */
    val micPauseDetectedMs: Long = 0L,
    /** When the STT engine emitted the transcript (≈ micPauseDetectedMs in mock). */
    val sttCompleteMs: Long = 0L,
    /** When transport.send() was called. */
    val sentMs: Long = 0L,
    /** When the payload was dequeued from the receive flow. 0 for SENT entries. */
    val receivedMs: Long = 0L,
    /** When TTS.speak() was invoked. 0 for SENT entries. */
    val ttsStartedMs: Long = 0L,
) {
    /** Model inference latency (0 ms in mock; real value in production). */
    val deltaModelMs: Long   get() = if (sttLatencyMs > 0L) sttLatencyMs else (sttCompleteMs - micPauseDetectedMs).coerceAtLeast(0L)

    /** Time from STT output to bytes on the wire. */
    val deltaQueueMs: Long   get() = if (queueLatencyMs > 0L) queueLatencyMs else (sentMs - sttCompleteMs).coerceAtLeast(0L)

    /** Network + socket latency. Meaningful only for RECEIVED entries. */
    val deltaNetworkMs: Long get() = if (networkLatencyMs > 0L) networkLatencyMs else if (receivedMs > 0L) (receivedMs - sentMs).coerceAtLeast(0L) else 0L

    /** Receive-to-TTS latency. Meaningful only for RECEIVED entries. */
    val deltaTtsStartMs: Long get() = if (ttsLatencyMs > 0L) ttsLatencyMs else if (ttsStartedMs > 0L && receivedMs > 0L) (ttsStartedMs - receivedMs).coerceAtLeast(0L) else 0L

    /** Total end-to-end latency: mic pause → TTS started. */
    val totalLatencyMs: Long get() = if (totalE2eLatencyMs > 0L) totalE2eLatencyMs else (deltaModelMs + deltaQueueMs + deltaNetworkMs + deltaTtsStartMs)
}
