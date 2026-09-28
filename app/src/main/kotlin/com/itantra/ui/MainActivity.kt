package com.itantra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.itantra.iTantraApplication
import com.itantra.ui.theme.iTantraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private val app get() = application as iTantraApplication
    
    val permissionsGranted = MutableStateFlow(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            android.util.Log.w("MainActivity", "Permissions denied: $denied")
        }
        val recordAudioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (recordAudioGranted && locationGranted) {
            permissionsGranted.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredPermissions()

        setContent {
            iTantraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background,
                ) {
                    MainNavGraph(
                        transport         = app.transport,
                        sessionController = app.sessionController,
                        modelManager      = app.modelManager,
                        permissionsGranted = permissionsGranted,
                        historyDao = app.historyDao
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            app.transport.shutdown()
            app.sessionController.shutdown()
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            // ACCESS_FINE_LOCATION is required for Wi-Fi Direct peer discovery on ALL Android versions.
            // On Android 13+, NEARBY_WIFI_DEVICES is also required alongside it.
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            @Suppress("DEPRECATION")
            perms += Manifest.permission.BLUETOOTH
            @Suppress("DEPRECATION")
            perms += Manifest.permission.BLUETOOTH_ADMIN
        }

        val denied = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            permissionsGranted.value = true
            android.util.Log.d("MainActivity", "All permissions already granted")
        } else {
            android.util.Log.d("MainActivity", "Requesting permissions: $denied")
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}

