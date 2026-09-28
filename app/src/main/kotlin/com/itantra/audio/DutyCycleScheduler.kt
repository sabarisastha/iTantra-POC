package com.itantra.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private const val TAG = "ITAN-DUTY-CYCLE"

/**
 * Duty cycle tiers based on device battery percentage.
 */
enum class DutyCycleTier(val label: String, val intervalMs: Long) {
    ABOVE_50("ACTIVE (>50%)", DutyCycleScheduler.INTERVAL_ABOVE_50_MS),
    BETWEEN_20_AND_50("MODERATE (20-50%)", DutyCycleScheduler.INTERVAL_BETWEEN_20_AND_50_MS),
    BELOW_20("POWER_SAVE (<20%)", DutyCycleScheduler.INTERVAL_BELOW_20_MS);
}

/**
 * Observable snapshot of the current duty cycle state.
 */
data class DutyCycleInfo(
    val tier: DutyCycleTier = DutyCycleTier.ABOVE_50,
    val batteryPercent: Int = 100,
    val isPipelineActive: Boolean = false,
)

/**
 * Battery-aware scheduler for continuous Phone Mode.
 *
 * Checks for sound in short periodic bursts with an interval that adapts
 * to battery level, minimizing idle CPU and battery drain while keeping
 * speech detection responsive.
 *
 * Fully decoupled from VAD/STT engines.
 */
class DutyCycleScheduler(
    private val context: Context? = null,
    private val rmsThreshold: Float = DEFAULT_RMS_THRESHOLD,
) {

    companion object {
        const val INTERVAL_ABOVE_50_MS = 150L
        const val INTERVAL_BETWEEN_20_AND_50_MS = 600L
        const val INTERVAL_BELOW_20_MS = 2500L

        const val DEFAULT_RMS_THRESHOLD = 0.015f
        private const val BURST_SAMPLE_RATE = 16000
        private const val BURST_SAMPLE_COUNT = 800 // 50ms at 16kHz
    }

    private val _dutyCycleInfo = MutableStateFlow(DutyCycleInfo())
    val dutyCycleInfo: StateFlow<DutyCycleInfo> = _dutyCycleInfo.asStateFlow()

    @Volatile private var isPipelineActive = false
    private var schedulerJob: Job? = null

    /**
     * Reads current battery percentage safely.
     * Defaults to 100% on error or when BatteryManager is unavailable.
     */
    fun getBatteryLevel(): Int {
        return try {
            val ctx = context ?: return 100
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            if (cap in 0..100) cap else 100
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery level: ${e.message}")
            100
        }
    }

    /**
     * Resolves the duty cycle tier from the current battery percentage.
     */
    fun getCurrentTier(): DutyCycleTier {
        val battery = getBatteryLevel()
        return when {
            battery > 50 -> DutyCycleTier.ABOVE_50
            battery in 20..50 -> DutyCycleTier.BETWEEN_20_AND_50
            else -> DutyCycleTier.BELOW_20
        }
    }

    /**
     * Calculates the Root Mean Square (RMS) energy of a normalized float audio buffer [-1.0, 1.0].
     */
    fun calculateRms(buffer: FloatArray): Float {
        if (buffer.isEmpty()) return 0f
        return try {
            var sum = 0.0
            for (sample in buffer) {
                sum += (sample * sample)
            }
            sqrt(sum / buffer.size).toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "calculateRms (float) failed: ${e.message}")
            0f
        }
    }

    /**
     * Calculates the Root Mean Square (RMS) energy of a PCM-16 short audio buffer.
     */
    fun calculateRms(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        return try {
            var sum = 0.0
            for (sample in buffer) {
                val norm = sample / 32768.0
                sum += (norm * norm)
            }
            sqrt(sum / buffer.size).toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "calculateRms (short) failed: ${e.message}")
            0f
        }
    }

    /**
     * Updates whether the full audio pipeline is currently active.
     */
    fun setPipelineActive(active: Boolean) {
        isPipelineActive = active
        val currentBattery = getBatteryLevel()
        val currentTier = getCurrentTier()
        _dutyCycleInfo.value = DutyCycleInfo(
            tier = currentTier,
            batteryPercent = currentBattery,
            isPipelineActive = active,
        )
    }

    /**
     * Starts the duty-cycle monitoring loop.
     *
     * On each tick:
     * - Reads battery and updates duty-cycle tier
     * - If pipeline is not active, performs a lightweight audio burst check
     * - If RMS exceeds [rmsThreshold], signals [onActivatePipeline]
     * - Delays according to the current tier's interval
     */
    fun startMonitoring(
        scope: CoroutineScope,
        onActivatePipeline: () -> Unit,
    ): Job {
        schedulerJob?.cancel()
        return scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting duty cycle scheduler loop")
            while (isActive) {
                try {
                    val battery = getBatteryLevel()
                    val tier = getCurrentTier()
                    _dutyCycleInfo.value = DutyCycleInfo(
                        tier = tier,
                        batteryPercent = battery,
                        isPipelineActive = isPipelineActive,
                    )

                    if (!isPipelineActive) {
                        val rms = sampleBurstRms()
                        if (rms >= rmsThreshold) {
                            Log.d(TAG, "Burst RMS ($rms) >= threshold ($rmsThreshold) — triggering full pipeline")
                            setPipelineActive(true)
                            onActivatePipeline()
                        }
                    }

                    val interval = _dutyCycleInfo.value.tier.intervalMs
                    delay(interval)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in duty cycle tick: ${e.message}", e)
                    delay(INTERVAL_ABOVE_50_MS) // Fail-safe to responsive tier interval
                }
            }
        }.also { schedulerJob = it }
    }

    /**
     * Captures a quick 50ms audio burst using AudioRecord and measures RMS.
     */
    @SuppressLint("MissingPermission")
    private fun sampleBurstRms(): Float {
        var recorder: AudioRecord? = null
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                BURST_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = maxOf(minBuf, BURST_SAMPLE_COUNT * 2)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                BURST_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    BURST_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return 0f
            }

            val buffer = ShortArray(BURST_SAMPLE_COUNT)
            recorder.startRecording()
            val read = recorder.read(buffer, 0, buffer.size)
            recorder.stop()
            recorder.release()
            recorder = null

            if (read > 0) {
                calculateRms(if (read == buffer.size) buffer else buffer.copyOf(read))
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.w(TAG, "sampleBurstRms failed: ${e.message}")
            0f
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Stops the scheduler loop.
     */
    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        setPipelineActive(false)
        Log.d(TAG, "Duty cycle scheduler stopped")
    }
}
