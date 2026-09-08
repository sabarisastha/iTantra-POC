package com.itantra.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.session.SessionController
import com.itantra.session.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.DiscoveredDevice
import com.itantra.transport.TransportChannel
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transport: TransportChannel,
    sessionController: SessionController,
    onNavigateToSession: () -> Unit,
    permissionsGranted: StateFlow<Boolean>,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(transport, sessionController, permissionsGranted)),
) {
    val state    by vm.connectionState.collectAsStateWithLifecycle()
    val devices  by vm.discoveredDevices.collectAsStateWithLifecycle()
    val wifiOff  by vm.wifiOffDialog.collectAsStateWithLifecycle()
    val language by vm.selectedLanguage.collectAsStateWithLifecycle()

    var langMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is ConnectionState.Connected) onNavigateToSession()
    }

    if (wifiOff) {
        AlertDialog(
            onDismissRequest = { vm.dismissWifiDialog() },
            title   = { Text("Wi-Fi Required") },
            text    = { Text("iTantra uses Wi-Fi Direct (WifiP2pManager + TCP sockets) for peer-to-peer communication. Please enable Wi-Fi in Settings, then tap the Refresh button.") },
            confirmButton = { TextButton(onClick = { vm.dismissWifiDialog() }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title   = { Text("iTantra — Connect") },
                actions = {
                    // Language selector — enabled before connecting
                    Box {
                        TextButton(onClick = { langMenuExpanded = true }) {
                            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(language.displayName)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = langMenuExpanded,
                            onDismissRequest = { langMenuExpanded = false },
                        ) {
                            SupportedLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text("${lang.displayName} (${lang.code})") },
                                    leadingIcon = { if (lang == language) Icon(Icons.Default.Check, null) },
                                    onClick = {
                                        vm.setLanguage(lang)
                                        langMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    IconButton(onClick = { vm.discover() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Discover peers")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ConnectionBanner(state)

            Text("Nearby Devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (devices.isEmpty()) {
                EmptyPeerList()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.address }) { device ->
                        PeerCard(
                            device      = device,
                            isConnected = state is ConnectionState.Connected,
                            onConnect   = { vm.connectTo(device.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(state: ConnectionState) {
    val (label, color) = when (state) {
        is ConnectionState.Idle       -> "Idle — tap Refresh to discover peers"                to MaterialTheme.colorScheme.outline
        is ConnectionState.Discovering -> "Discovering nearby devices…"                         to MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting  -> "Connecting…"                                         to MaterialTheme.colorScheme.secondary
        is ConnectionState.Connected   -> "Connected to ${state.peerName} (${if (state.isGroupOwner) "Group Owner" else "Client"})" to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Error       -> "Error: ${state.message}"                             to MaterialTheme.colorScheme.error
    }
    val showSpinner = state is ConnectionState.Discovering || state is ConnectionState.Connecting

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier  = Modifier.padding(14.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
            }
            Text(label, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyPeerList() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outlineVariant)
            Text("No peers found yet", color = MaterialTheme.colorScheme.outline)
            Text("Make sure both phones have Wi-Fi on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun PeerCard(device: DiscoveredDevice, isConnected: Boolean, onConnect: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConnect, enabled = !isConnected) {
                Text(if (isConnected) "Connected" else "Connect")
            }
        }
    }
}
