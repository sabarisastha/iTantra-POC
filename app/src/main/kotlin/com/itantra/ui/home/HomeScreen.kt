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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.db.HistoryEntity
import com.itantra.model.ModelManager
import com.itantra.model.ModelState
import com.itantra.session.SessionController
import com.itantra.session.SupportedLanguage
import com.itantra.transport.ConnectionState
import com.itantra.transport.DiscoveredDevice
import com.itantra.transport.TransportKind
import com.itantra.transport.TransportManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transport: TransportManager,
    sessionController: SessionController,
    modelManager: ModelManager,
    onNavigateToSession: () -> Unit,
    permissionsGranted: StateFlow<Boolean>,
    historyDao: com.itantra.db.HistoryDao? = null,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(transport, sessionController, modelManager, permissionsGranted, historyDao)),
) {
    val state           by vm.connectionState.collectAsStateWithLifecycle()
    val devices         by vm.discoveredDevices.collectAsStateWithLifecycle()
    val activeTransport by vm.activeTransport.collectAsStateWithLifecycle()
    val wifiOff         by vm.wifiOffDialog.collectAsStateWithLifecycle()
    val language        by vm.selectedLanguage.collectAsStateWithLifecycle()
    
    val sttState          by vm.sttState.collectAsStateWithLifecycle()
    val ttsState          by vm.ttsState.collectAsStateWithLifecycle()
    val isModelDownloaded by vm.isModelDownloaded.collectAsStateWithLifecycle()
    val history           by vm.history.collectAsStateWithLifecycle()

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
                1 -> HistoryContent(
                        history = history,
                        onUpdateAlias = { item, newAlias ->
                            vm.updatePeerAlias(item.peerAddress.ifBlank { item.peerName }, newAlias)
                            vm.updatePeerAliasById(item.id, newAlias)
                        }
                     )
                2 -> SettingsContent(
                        language = language,
                        activeTransport = activeTransport,
                        connectionState = state,
                        isDownloaded = isModelDownloaded,
                        sttState = sttState,
                        ttsState = ttsState,
                        onTransportSelect = { vm.selectTransport(it) },
                        onDownload = { vm.downloadModels() },
                        onClearHistory = { vm.clearHistory() }
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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

        Spacer(modifier = Modifier.height(4.dp))
        
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
fun HistoryContent(
    history: List<HistoryEntity>,
    onUpdateAlias: (HistoryEntity, String) -> Unit = { _, _ -> },
) {
    var editingItem by remember { mutableStateOf<HistoryEntity?>(null) }
    var aliasInput by remember { mutableStateOf("") }
    val df = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    if (editingItem != null) {
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Set Device Alias") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Assign a name to ${editingItem!!.peerName}:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        placeholder = { Text("e.g. Base Station / Unit 2") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateAlias(editingItem!!, aliasInput)
                    editingItem = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
        Spacer(Modifier.height(16.dp))
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No past connections yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { it.id }) { item ->
                    val isSuccess = item.status == "SUCCESS"
                    val isFailed = item.status == "FAILED"
                    val statusColor = when {
                        isSuccess -> Color(0xFF4CAF50)
                        isFailed  -> MaterialTheme.colorScheme.error
                        else      -> Color(0xFFFF9800)
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                aliasInput = item.alias ?: ""
                                editingItem = item
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val hasAlias = !item.alias.isNullOrBlank()
                                    Text(
                                        text = if (hasAlias) item.alias!! else item.peerName,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (hasAlias) {
                                        Text(
                                            text = "(${item.peerName})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            aliasInput = item.alias ?: ""
                                            editingItem = item
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Alias",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = statusColor.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = item.status,
                                            color = statusColor,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(df.format(Date(item.timestampMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (isSuccess) "${item.durationMs / 1000}s" else "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("via ${item.transportType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(item.languageUsed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsContent(
    language: SupportedLanguage,
    activeTransport: TransportKind = TransportKind.WIFI_DIRECT,
    connectionState: ConnectionState = ConnectionState.Idle,
    isDownloaded: Boolean,
    sttState: ModelState,
    ttsState: ModelState,
    onTransportSelect: (TransportKind) -> Unit = {},
    onDownload: () -> Unit,
    onClearHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val hasMic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasLoc = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                 ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val isDownloading = sttState is ModelState.Downloading || ttsState is ModelState.Downloading
    val sttProgress = (sttState as? ModelState.Downloading)?.progress ?: 0
    val ttsProgress = (ttsState as? ModelState.Downloading)?.progress ?: 0
    val totalProgress = if (isDownloading) (sttProgress + ttsProgress) / 2f / 100f else 0f

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
        }

        // Section 1: AI VOICE MODELS
        item {
            SettingsCard(title = "AI Voice Models") {
                Text("Selected Language: ${language.displayName} (${language.code})", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("STT: ${if (isDownloaded) "Ready ✓" else "Not downloaded"}", style = MaterialTheme.typography.bodySmall, color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("TTS: ${if (isDownloaded) "Ready ✓" else "Not downloaded"}", style = MaterialTheme.typography.bodySmall, color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                if (isDownloaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Models Downloaded for Offline Inference", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (isDownloading) {
                    LinearProgressIndicator(progress = { totalProgress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Text("Downloading models... ${(totalProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download Models for ${language.displayName}")
                    }
                }
            }
        }

        // Section 2: COMMUNICATION
        item {
            SettingsCard(title = "Communication") {
                Text("Preferred Transport", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TransportKind.entries.forEach { kind ->
                        val selected = activeTransport == kind
                        FilterChip(
                            selected = selected,
                            onClick = { onTransportSelect(kind) },
                            label = { Text(kind.label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (kind == TransportKind.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }
        }

        // Section 3: PERMISSIONS
        item {
            SettingsCard(title = "Permissions") {
                PermissionStatusRow("Microphone (Audio & STT)", hasMic)
                Spacer(Modifier.height(8.dp))
                PermissionStatusRow("Bluetooth & Nearby Devices", true)
                Spacer(Modifier.height(8.dp))
                PermissionStatusRow("Location (Offline Emergency)", hasLoc)
            }
        }

        // Section 4: OFFLINE STATUS
        item {
            SettingsCard(title = "Offline Status") {
                val allReady = isDownloaded && hasMic
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (allReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allReady) MaterialTheme.colorScheme.primary else Color(0xFFFF9800)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (allReady) "100% OFFLINE CAPABLE" else "PARTIALLY OFFLINE",
                        fontWeight = FontWeight.Bold,
                        color = if (allReady) MaterialTheme.colorScheme.primary else Color(0xFFFF9800)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (allReady) "All required speech models and local transports are loaded. No internet required."
                           else "Download missing models to enable full offline two-way speech synthesis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Section 5: DIAGNOSTICS
        item {
            SettingsCard(title = "Diagnostics") {
                DiagnosticRow("Active Transport", activeTransport.label)
                DiagnosticRow("Connection State", connectionState.javaClass.simpleName)
                DiagnosticRow("Audio Pipeline", "16kHz Mono Float PCM")
                DiagnosticRow("TTS Engine", "Sherpa-ONNX VITS / AndroidSystemFallback")
                DiagnosticRow("Heartbeat", "Active (10s interval)")
            }
        }

        // Section 6: DATA & PRIVACY
        item {
            SettingsCard(title = "Data & Privacy") {
                Text("Connection logs are stored strictly on-device in a local Room database.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onClearHistory, 
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear Connection History")
                }
            }
        }

        // Section 7: ABOUT
        item {
            SettingsCard(title = "About") {
                Text("iTantra", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("Offline Multilingual Voice & Emergency Communication", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Version 1.0.0 • SIH 2026", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PermissionStatusRow(name: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall)
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (granted) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        ) {
            Text(
                text = if (granted) "Granted ✓" else "Missing ✗",
                color = if (granted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
    }
}
