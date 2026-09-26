package com.itantra.session

/**
 * Floor control state for half-duplex Walkie-Talkie communication.
 *
 * Ensures only one peer can transmit voice at a time.
 */
enum class FloorState {
    /** Channel is idle/free. Any peer can request floor. */
    IDLE,

    /** Local device has requested the floor and is waiting for channel resolution. */
    WAITING_FOR_CHANNEL,

    /** Local device owns the floor; microphone is active and transmitting. */
    LOCAL_TRANSMITTING,

    /** Remote peer owns the floor; listening mode only, local microphone is locked out. */
    REMOTE_TRANSMITTING,

    /** Local device attempted transmission while channel was busy or lost collision tie-break. */
    CHANNEL_BUSY
}