package com.itantra.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.session.SessionController
import kotlinx.coroutines.launch

@Composable
fun ModelReadinessScreen(
    sessionController: SessionController,
    modelManager: ModelManager,
    onReady: () -> Unit
) {
    val selectedLang by sessionController.selectedLanguage.collectAsState()
    val sttState by modelManager.sttState.collectAsState()
    val ttsState by modelManager.ttsState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(selectedLang) {
        val isReady = modelManager.isModelDownloaded(selectedLang.code)
        if (isReady) {
            onReady()
        } else {
            downloading = true
            modelManager.preDownloadModels(selectedLang.code)
        }
    }

    LaunchedEffect(sttState, ttsState) {
        if (downloading) {
            if ((sttState == ModelState.Idle || sttState == ModelState.Ready) &&
                (ttsState == ModelState.Idle || ttsState == ModelState.Ready)
            ) {
                // Models finished downloading
                downloading = false
                onReady()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Preparing offline models for ${selectedLang.displayName}...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (sttState is ModelState.Downloading) {
                Text("STT: ${(sttState as ModelState.Downloading).progress}%")
            } else if (sttState is ModelState.Error) {
                Text("STT: ${(sttState as ModelState.Error).message}", color = MaterialTheme.colorScheme.error)
            } else {
                Text("STT: Ready")
            }

            if (ttsState is ModelState.Downloading) {
                Text("TTS: ${(ttsState as ModelState.Downloading).progress}%")
            } else if (ttsState is ModelState.Error) {
                Text("TTS: ${(ttsState as ModelState.Error).message}", color = MaterialTheme.colorScheme.error)
            } else {
                Text("TTS: System Audio Ready")
            }
            
            if (sttState is ModelState.Error || ttsState is ModelState.Error) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        coroutineScope.launch {
                            modelManager.preDownloadModels(selectedLang.code)
                        }
                    }) {
                        Text("Retry")
                    }
                    OutlinedButton(onClick = { onReady() }) {
                        Text("Continue Offline")
                    }
                }
            }
        }
    }
}
