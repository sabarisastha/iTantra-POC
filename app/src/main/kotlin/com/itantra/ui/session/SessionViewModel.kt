package com.itantra.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.itantra.session.FloorState
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.session.SupportedLanguage
import com.itantra.session.TranscriptEntry
import com.itantra.transport.ConnectionState
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    private val sessionController: SessionController,
) : ViewModel() {

    val mode:             StateFlow<SessionMode>           = sessionController.mode
    val floorState:       StateFlow<FloorState>            = sessionController.floorState
    val transcript:       StateFlow<List<TranscriptEntry>> = sessionController.transcript
    val selectedLanguage: StateFlow<SupportedLanguage>     = sessionController.selectedLanguage
    val connectionState:  StateFlow<ConnectionState>       = sessionController.transport.connectionState

    fun setMode(mode: SessionMode)            = sessionController.setMode(mode)
    fun setLanguage(lang: SupportedLanguage)  = sessionController.setLanguage(lang)
    fun requestFloorAndArm()                  = sessionController.requestFloorAndArm()
    fun releaseFloorAndDisarm()               = sessionController.releaseFloorAndDisarm()
    fun triggerAlert()                        = sessionController.triggerAlert()
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
