package com.itantra.session

/**
 * Operating mode for the active session.
 *
 * Both modes use the SAME pipeline:
 *   SpeechToTextEngine → TransportChannel → TextToSpeechEngine
 * SessionController gates the STT start/stop behaviour; no second pipeline exists.
 */
enum class SessionMode {
    /**
     * Half-duplex, walkie-talkie style.
     * Mic is armed only while the PTT button is held (or until the next pause is detected).
     * One direction transmits at a time.
     */
    PTT,

    /**
     * Full-duplex-feel, automatic.
     * STT runs continuously with automatic pause-based sentence segmentation on both ends.
     * Both sides can transmit independently and simultaneously.
     */
    PHONE,
}
