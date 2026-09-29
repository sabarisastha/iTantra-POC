package com.itantra.ui.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.itantra.audio.DutyCycleInfo
import com.itantra.session.FloorState
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.session.SupportedLanguage
import com.itantra.session.TranscriptEntry
import com.itantra.telemetry.TelemetryState
import com.itantra.transport.ConnectionState
import com.itantra.utils.LiveLocationState
import com.itantra.utils.LocationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SessionViewModel(
    private val sessionController: SessionController,
) : ViewModel() {

    val mode:              StateFlow<SessionMode>           = sessionController.mode
    val floorState:        StateFlow<FloorState>            = sessionController.floorState
    val transcript:        StateFlow<List<TranscriptEntry>> = sessionController.transcript
    val selectedLanguage:  StateFlow<SupportedLanguage>     = sessionController.selectedLanguage
    val connectionState:   StateFlow<ConnectionState>       = sessionController.transport.connectionState
    val telemetry:         StateFlow<TelemetryState>        = sessionController.telemetry
    val dutyCycleInfo:     StateFlow<DutyCycleInfo>         = sessionController.dutyCycleInfo ?: MutableStateFlow(DutyCycleInfo())
    val liveLocationState: StateFlow<LiveLocationState>     = sessionController.liveLocationState

    fun setMode(mode: SessionMode)            = sessionController.setMode(mode)
    fun setLanguage(lang: SupportedLanguage)  = sessionController.setLanguage(lang)
    fun requestFloorAndArm()                  = sessionController.requestFloorAndArm()
    fun releaseFloorAndDisarm()               = sessionController.releaseFloorAndDisarm()

    fun triggerAlert(
        latitude: Double? = null,
        longitude: Double? = null,
        accuracy: Float? = null,
        locationTimestampMs: Long? = null,
        locationStatus: String? = null,
    ) {
        sessionController.triggerAlert(latitude, longitude, accuracy, locationTimestampMs, locationStatus)
    }

    fun triggerAlert(context: Context) {
        viewModelScope.launch {
            val result = LocationHelper.getCurrentLocation(context)
            val loc = result.locationInfo
            sessionController.triggerAlert(
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                accuracy = loc?.accuracyMeters,
                locationTimestampMs = loc?.timestampMs,
                locationStatus = result.status.label,
            )
        }
    }

    fun replayReceivedAudio(sequenceId: Int): Boolean = sessionController.replayReceivedAudio(sequenceId)
    fun disconnect()                          = sessionController.disconnect()

    companion object {
        fun factory(sessionController: SessionController) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionViewModel(sessionController) as T
        }
    }
}

