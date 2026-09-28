package com.itantra.vad

import android.util.Log

private const val TAG = "SpeechSegmenter"

enum class SegmenterEvent {
    SILENCE,
    SPEECH_START,
    SPEECH_CONTINUING,
    SPEECH_END
}

/**
 * Temporal Speech Segmenter on top of frame-by-frame VAD probabilities.
 *
 * Prevents cutoffs and speech truncation by using:
 * - Attack debounce (consecutive voiced frames before declaring SPEECH_START)
 * - Hangover hold-off (sustained silence frames before declaring SPEECH_END)
 * - Configurable thresholds to ignore short inter-syllable pauses.
 */
class SpeechSegmenter(
    private val speechStartThreshold: Float = 0.50f,
    private val speechEndThreshold: Float = 0.35f,
    private val attackFrames: Int = 3,          // ~96 ms voiced before SPEECH_START
    private val hangoverFrames: Int = 16,       // ~512 ms silence before SPEECH_END
) {
    private enum class State { IN_SILENCE, IN_SPEECH }

    private var currentState = State.IN_SILENCE
    private var consecutiveVoiced = 0
    private var consecutiveSilence = 0

    fun process(vadResult: VadResult): SegmenterEvent {
        val prob = vadResult.speechProbability

        return when (currentState) {
            State.IN_SILENCE -> {
                if (prob >= speechStartThreshold) {
                    consecutiveVoiced++
                    if (consecutiveVoiced >= attackFrames) {
                        currentState = State.IN_SPEECH
                        consecutiveSilence = 0
                        runCatching { Log.d(TAG, "SpeechSegmenter: SILENCE -> SPEECH_START (prob=${prob.fmt()})") }
                        SegmenterEvent.SPEECH_START
                    } else {
                        SegmenterEvent.SILENCE
                    }
                } else {
                    consecutiveVoiced = 0
                    SegmenterEvent.SILENCE
                }
            }
            State.IN_SPEECH -> {
                if (prob < speechEndThreshold) {
                    consecutiveSilence++
                    if (consecutiveSilence >= hangoverFrames) {
                        currentState = State.IN_SILENCE
                        consecutiveVoiced = 0
                        consecutiveSilence = 0
                        runCatching { Log.d(TAG, "SpeechSegmenter: SPEECH -> SPEECH_END (hangover elapsed)") }
                        SegmenterEvent.SPEECH_END
                    } else {
                        SegmenterEvent.SPEECH_CONTINUING
                    }
                } else {
                    consecutiveSilence = 0
                    SegmenterEvent.SPEECH_CONTINUING
                }
            }
        }
    }

    val isSpeechActive: Boolean get() = currentState == State.IN_SPEECH

    fun reset() {
        currentState = State.IN_SILENCE
        consecutiveVoiced = 0
        consecutiveSilence = 0
        runCatching { Log.d(TAG, "SpeechSegmenter reset to IN_SILENCE") }
    }

    private fun Float.fmt() = "%.2f".format(this)
}