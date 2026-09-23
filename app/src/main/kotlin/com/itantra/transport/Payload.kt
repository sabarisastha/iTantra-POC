package com.itantra.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Wire format for all data exchanged between devices over the TCP socket.
 *
 * Includes speech segments, emergency alerts, and floor-control protocol messages.
 */
@Serializable
data class Payload(
    val type: PayloadType,
    val text: String = "",
    val languageCode: String = "",
    val sequenceId: Int = 0,
    /** [System.currentTimeMillis] on the sender when transport.send() was called. */
    val sentTimestampMs: Long = 0L,
    /** Unique sender identifier used for deterministic floor collision tie-breaking. */
    val senderId: String = "",
    /** Set locally by receiver when dequeued; NOT included in transmitted JSON. */
    @Transient
    val receivedTimestampMs: Long = 0L,
    
    // Emergency Location Data
    val latitude: Double? = null,
    val longitude: Double? = null,
)

enum class PayloadType {
    /** Normal transcribed speech segment. */
    SPEECH,

    /** High-priority alert — must play at maximum volume, non-interruptible. */
    ALERT,

    /** Half-duplex floor control: request permission to transmit. */
    REQUEST_FLOOR,

    /** Half-duplex floor control: confirm/grant floor ownership. */
    FLOOR_GRANTED,

    /** Half-duplex floor control: reject floor request (channel is busy). */
    FLOOR_DENIED,

    /** Half-duplex floor control: release floor ownership back to IDLE. */
    RELEASE_FLOOR,

    /** Synchronize session mode (PTT vs PHONE) across connected peers. */
    MODE_CHANGE,

    /** Reserved for future handshake / keepalive messages. */
    CONTROL,

    SESSION_INIT,
    SESSION_ACK,
    HEARTBEAT
}