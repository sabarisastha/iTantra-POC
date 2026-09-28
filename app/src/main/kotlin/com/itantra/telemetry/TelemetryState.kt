package com.itantra.telemetry

/**
 * Live latency and network transmission metrics for the debug overlay.
 *
 * @property sttLatencyMs Model inference / transcription latency in milliseconds.
 * @property ttsLatencyMs Text-to-speech synthesis / playback start latency in milliseconds.
 * @property rtf Real-Time Factor (inference latency / audio duration).
 * @property e2eLatencyMs Total end-to-end latency from mic pause to playback start in milliseconds.
 * @property payloadBytes Encoded byte size of the transmitted/received payload.
 * @property activeLanguage BCP-47 language code or display name for the speech segment.
 * @property transport Transport mechanism used ("BT" for Bluetooth, "WiFi" for Wi-Fi Direct).
 */
data class TelemetryState(
    val sttLatencyMs: Long = 0L,
    val ttsLatencyMs: Long = 0L,
    val rtf: Float = 0f,
    val e2eLatencyMs: Long = 0L,
    val payloadBytes: Int = 0,
    val activeLanguage: String = "",
    val transport: String = "",
) {
    val hasData: Boolean
        get() = e2eLatencyMs > 0L || sttLatencyMs > 0L || payloadBytes > 0 || ttsLatencyMs > 0L
}
