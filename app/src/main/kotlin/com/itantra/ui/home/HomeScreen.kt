package com.itantra.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.session.SessionController
import com.itantra.session.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.DiscoveredDevice
import com.itantra.transport.TransportManager
import com.itantra.transport.TransportKind
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transport: TransportManager,
    sessionController: SessionController,
    modelManager: ModelManager,
    onNavigateToSession: () -> Unit,
    permissionsGranted: StateFlow<Boolean>,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(transport, sessionController, modelManager, permissionsGranted)),
) {
    val state    by vm.connectionState.collectAsStateWithLifecycle()
    val devices  by vm.discoveredDevices.collectAsStateWithLifecycle()
    val activeTransport by vm.activeTransport.collectAsStateWithLifecycle()
    val wifiOff  by vm.wifiOffDialog.collectAsStateWithLifecycle()
    val language by vm.selectedLanguage.collectAsStateWithLifecycle()
    
    val sttState by vm.sttState.collectAsStateWithLifecycle()
    val ttsState by vm.ttsState.collectAsStateWithLifecycle()
    val isModelDownloaded by vm.isModelDownloaded.collectAsStateWithLifecycle()

    var langMenuExpanded by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.discover()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasNavigated by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected && !hasNavigated) {
            hasNavigated = true
            onNavigateToSession()
        } else if (state !is ConnectionState.Connected) {
            hasNavigated = false
        }
    }

    if (wifiOff) {
        AlertDialog(
            onDismissRequest = { vm.dismissWifiDialog() },
            title   = { Text("Transport unavailable") },
            text    = { Text("Enable the selected transport and grant its nearby-device permission, then refresh.") },
            confirmButton = { TextButton(onClick = { vm.dismissWifiDialog() }) { Text("OK") } },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (currentTab) {
                0 -> HomeContent(
                        state = state,
                        devices = devices,
                        activeTransport = activeTransport,
                        language = language,
                        langMenuExpanded = langMenuExpanded,
                        onLangMenuToggle = { langMenuExpanded = it },
                        onLanguageSelect = { vm.setLanguage(it) },
                        onTransportSelect = { vm.selectTransport(it) },
                        onConnect = { vm.connectTo(it) },
                        onRefresh = { vm.discover() },
                        isModelDownloaded = isModelDownloaded
                     )
                1 -> HistoryContent()
                2 -> SettingsContent(
                        language = language,
                        isDownloaded = isModelDownloaded,
                        sttState = sttState,
                        ttsState = ttsState,
                        onDownload = { vm.downloadModels() }
                     )
            }
        }
    }
}

@Composable
fun HomeContent(
    state: ConnectionState,
    devices: List<DiscoveredDevice>,
    activeTransport: TransportKind,
    language: SupportedLanguage,
    langMenuExpanded: Boolean,
    onLangMenuToggle: (Boolean) -> Unit,
    onLanguageSelect: (SupportedLanguage) -> Unit,
    onTransportSelect: (TransportKind) -> Unit,
    onConnect: (String) -> Unit,
    onRefresh: () -> Unit,
    isModelDownloaded: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("iTantra", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
            
            // Transport Toggle Segmented
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(40.dp)
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    TransportKind.entries.forEach { kind ->
                        val selected = activeTransport == kind
                        val canSwitch = state is ConnectionState.Idle || state is ConnectionState.Discovering || state is ConnectionState.Error
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable(enabled = canSwitch && !selected) { onTransportSelect(kind) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = kind.label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }

        // Language Dropdown
        Box(modifier = Modifier.align(Alignment.End)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.clickable { onLangMenuToggle(true) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(language.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { onLangMenuToggle(false) }) {
                SupportedLanguage.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text("${lang.displayName} (${lang.code})") },
                        leadingIcon = { if (lang == language) Icon(Icons.Default.Check, null) },
                        onClick = { onLanguageSelect(lang); onLangMenuToggle(false) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Nearby Devices", style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Device List
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("No devices found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "1. Ensure ${activeTransport.label} & Location (GPS) are turned ON.\n2. For Bluetooth phone-to-phone, pair devices once in Android Bluetooth settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(devices, key = { it.address }) { device ->
                    PeerListItem(
                        device = device,
                        isConnected = state is ConnectionState.Connected,
                        isConnecting = state is ConnectionState.Connecting,
                        isReady = isModelDownloaded,
                        onConnect = { onConnect(device.address) }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerListItem(device: DiscoveredDevice, isConnected: Boolean, isConnecting: Boolean, isReady: Boolean, onConnect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Default.Devices, null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(if (isConnected) "Connected" else "Available", style = MaterialTheme.typography.bodySmall, color = if(isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onConnect, 
                enabled = !isConnected && !isConnecting && isReady,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (isConnected) "Active" else "Connect", color = if(!isConnected && !isConnecting && isReady) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun HistoryContent() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
        Spacer(Modifier.height(32.dp))
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No past connections yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun SettingsContent(
    language: SupportedLanguage,
    isDownloaded: Boolean,
    sttState: ModelState,
    ttsState: ModelState,
    onDownload: () -> Unit
) {
    val isDownloading = sttState is ModelState.Downloading || ttsState is ModelState.Downloading
    val sttProgress = (sttState as? ModelState.Downloading)?.progress ?: 0
    val ttsProgress = (ttsState as? ModelState.Downloading)?.progress ?: 0
    val totalProgress = if (isDownloading) (sttProgress + ttsProgress) / 2f / 100f else 0f

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
        
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("AI Voice Models", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                Text("Required for offline inference in ${language.displayName}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                
                if (isDownloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Models Downloaded", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                } else if (isDownloading) {
                    LinearProgressIndicator(progress = { totalProgress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text("Downloading... please wait.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download Models")
                    }
                }
            }
        }
    }
}
