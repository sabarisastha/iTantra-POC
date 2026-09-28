package com.itantra.ui.session

import android.util.Log
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.itantra.utils.LocationHelper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.audio.DutyCycleInfo
import com.itantra.session.Direction
import com.itantra.session.FloorState
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.session.SupportedLanguage
import com.itantra.session.TranscriptEntry
import com.itantra.telemetry.TelemetryState
import com.itantra.transport.ConnectionState
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

private const val TAG = "SessionScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    sessionController: SessionController,
    onNavigateBack: () -> Unit,
    vm: SessionViewModel = viewModel(factory = SessionViewModel.factory(sessionController)),
) {
    val mode            by vm.mode.collectAsStateWithLifecycle()
    val floorState      by vm.floorState.collectAsStateWithLifecycle()
    val entries         by vm.transcript.collectAsStateWithLifecycle()
    val language        by vm.selectedLanguage.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val telemetry       by vm.telemetry.collectAsStateWithLifecycle()
    val dutyCycleInfo   by vm.dutyCycleInfo.collectAsStateWithLifecycle()
    val liveLocation    by vm.liveLocationState.collectAsStateWithLifecycle()

    var latencyExpanded by remember { mutableStateOf(false) }
    var isLocatingForAlert by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // If disconnected, return to Connect screen automatically
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Idle || connectionState is ConnectionState.Error) {
            Log.d(TAG, "Connection state became $connectionState — navigating back to HomeScreen")
            onNavigateBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("iTantra Walkie-Talkie") },
                navigationIcon = {
                    IconButton(onClick = {
                        Log.d(TAG, "Back button clicked — disconnecting session")
                        vm.disconnect()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Disconnect and Go Back")
                    }
                },
                actions = {
                    // Locked Language Indicator
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Language locked during call",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                language.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Explicit Disconnect Action Button
                    IconButton(
                        onClick = {
                            Log.d(TAG, "Disconnect action clicked")
                            vm.disconnect()
                        },
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Disconnect Call",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { 
                    if (isLocatingForAlert) return@ExtendedFloatingActionButton
                    isLocatingForAlert = true
                    coroutineScope.launch {
                        try {
                            val result = LocationHelper.getCurrentLocation(context, timeoutMs = 2500L)
                            val loc = result.locationInfo
                            val statusStr = if (loc != null) "Location available" else result.status.label
                            vm.triggerAlert(
                                latitude = loc?.latitude,
                                longitude = loc?.longitude,
                                accuracy = loc?.accuracyMeters,
                                locationTimestampMs = loc?.timestampMs,
                                locationStatus = statusStr
                            )
                        } finally {
                            isLocatingForAlert = false
                        }
                    }
                },
                icon             = { 
                    if (isLocatingForAlert) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        Icon(Icons.Default.Warning, contentDescription = null)
                    }
                },
                text             = { Text(if (isLocatingForAlert) "Locating & Sending..." else "Send Alert") },
                containerColor   = MaterialTheme.colorScheme.errorContainer,
                contentColor     = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                // ── Synchronized PTT / Phone mode toggle ─────────────────────────
                ModeToggleRow(mode = mode, onModeChange = { vm.setMode(it) })

                // ── Half-Duplex Floor Status Banner ──────────────────────────────
                FloorStatusBanner(mode = mode, floorState = floorState, dutyCycleInfo = dutyCycleInfo)

                // ── Live Peer Distance & Movement Banner ─────────────────────────
                AnimatedVisibility(visible = liveLocation.hasPeerLocation) {
                    LivePeerDistanceBanner(liveLocation = liveLocation)
                }

                // ── Walkie-Talkie PTT Button (only in PTT mode) ───────────────────
                AnimatedVisibility(visible = mode == SessionMode.PTT) {
                    WalkieTalkiePttButton(
                        floorState = floorState,
                        onArm = {
                            Log.d(TAG, "UI: PTT Press -> requestFloorAndArm()")
                            vm.requestFloorAndArm()
                        },
                        onDisarm = {
                            Log.d(TAG, "UI: PTT Release -> releaseFloorAndDisarm()")
                            vm.releaseFloorAndDisarm()
                        },
                    )
                }

                // ── Latency debug overlay ────────────────────────────────────────
                LatencyPanel(entries = entries, expanded = latencyExpanded, onToggle = { latencyExpanded = !latencyExpanded })

                // ── Transcript log ───────────────────────────────────────────────
                Text("Transcript", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TranscriptLog(
                    entries = entries,
                    modifier = Modifier.weight(1f),
                    onReplay = { sequenceId ->
                        val replayed = vm.replayReceivedAudio(sequenceId)
                        if (!replayed) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Voice replay is no longer available")
                            }
                        }
                        replayed
                    },
                )
            }

            // ── Live Telemetry Overlay (top-end via Box + Alignment.TopEnd) ────
            TelemetryOverlay(
                telemetry = telemetry,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode toggle (Synchronized Segmented Control)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ModeToggleRow(
    mode: SessionMode,
    onModeChange: (SessionMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeSegmentButton(
                title = "Walkie-Talkie",
                subtitle = "PTT (Half-Duplex)",
                icon = Icons.Default.RadioButtonChecked,
                isSelected = mode == SessionMode.PTT,
                onClick = { onModeChange(SessionMode.PTT) },
                modifier = Modifier.weight(1f),
            )
            ModeSegmentButton(
                title = "Phone",
                subtitle = "Full-Duplex (VAD)",
                icon = Icons.Default.Phone,
                isSelected = mode == SessionMode.PHONE,
                onClick = { onModeChange(SessionMode.PHONE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeSegmentButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = if (isSelected) 0.85f else 0.7f),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Floor Status Banner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun FloorStatusBanner(
    mode: SessionMode,
    floorState: FloorState,
    dutyCycleInfo: DutyCycleInfo,
) {
    if (mode == SessionMode.PHONE) {
        Card(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.PhoneInTalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Phone Mode: Continuous capture active with Silero VAD",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = "Duty Cycle: ${dutyCycleInfo.tier.label} (${dutyCycleInfo.tier.intervalMs}ms) | Battery: ${dutyCycleInfo.batteryPercent}%${if (dutyCycleInfo.isPipelineActive) " [ACTIVE]" else " [IDLE]"}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        return
    }

    val (label, containerColor, contentColor, icon) = when (floorState) {
        FloorState.IDLE -> Tuple4(
            "Channel Available (Ready to talk)",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.MicNone,
        )
        FloorState.WAITING_FOR_CHANNEL -> Tuple4(
            "Requesting channel...",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Default.HourglassTop,
        )
        FloorState.LOCAL_TRANSMITTING -> Tuple4(
            "Transmitting (Channel Locked)",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.Mic,
        )
        FloorState.REMOTE_TRANSMITTING -> Tuple4(
            "Receiving (Remote peer is talking)",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.AutoMirrored.Filled.VolumeUp,
        )
        FloorState.CHANNEL_BUSY -> Tuple4(
            "Channel Busy (Remote peer owns channel)",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Block,
        )
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = contentColor)
        }
    }
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ─────────────────────────────────────────────────────────────────────────────
// Walkie-Talkie PTT Button
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun WalkieTalkiePttButton(
    floorState: FloorState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    val isTransmitting = floorState == FloorState.LOCAL_TRANSMITTING
    val isWaiting = floorState == FloorState.WAITING_FOR_CHANNEL
    val isRemoteTalking = floorState == FloorState.REMOTE_TRANSMITTING
    val isBusy = floorState == FloorState.CHANNEL_BUSY

    val buttonBgColor = when {
        isTransmitting  -> MaterialTheme.colorScheme.primary
        isWaiting       -> MaterialTheme.colorScheme.tertiary
        isBusy          -> MaterialTheme.colorScheme.error
        isRemoteTalking -> MaterialTheme.colorScheme.surfaceVariant
        else            -> MaterialTheme.colorScheme.primaryContainer
    }

    val buttonFgColor = when {
        isTransmitting  -> MaterialTheme.colorScheme.onPrimary
        isWaiting       -> MaterialTheme.colorScheme.onTertiary
        isBusy          -> MaterialTheme.colorScheme.onError
        isRemoteTalking -> MaterialTheme.colorScheme.outline
        else            -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    val buttonLabel = when {
        isTransmitting  -> "Transmitting…"
        isWaiting       -> "Acquiring…"
        isBusy          -> "Channel Busy"
        isRemoteTalking -> "Remote Talking"
        else            -> "Hold to Talk"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = buttonBgColor,
            shadowElevation = if (isTransmitting) 8.dp else 4.dp,
            border = BorderStroke(
                width = if (isTransmitting || isWaiting) 3.dp else 1.5.dp,
                color = if (isTransmitting) MaterialTheme.colorScheme.primary
                        else if (isBusy) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .size(144.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown().also { it.consume() }
                        Log.d(TAG, "WalkieTalkiePttButton: touch down")
                        onArm()

                        val up = waitForUpOrCancellation()
                        Log.d(TAG, "WalkieTalkiePttButton: touch up/cancelled (${up != null})")
                        onDisarm()
                    }
                },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isWaiting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                            color = buttonFgColor,
                        )
                    } else {
                        Icon(
                            imageVector = when {
                                isTransmitting  -> Icons.Default.Mic
                                isRemoteTalking -> Icons.AutoMirrored.Filled.VolumeUp
                                isBusy          -> Icons.Default.Block
                                else            -> Icons.Default.MicNone
                            },
                            contentDescription = "Push to Talk",
                            tint = buttonFgColor,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    Text(
                        text = buttonLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = buttonFgColor,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live Peer Distance Banner
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LivePeerDistanceBanner(
    liveLocation: com.itantra.utils.LiveLocationState,
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Live Peer Distance: ${LocationHelper.formatDistance(liveLocation.distanceMeters)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (liveLocation.peerLocation != null) {
                    IconButton(
                        onClick = {
                            LocationHelper.openMap(
                                context,
                                liveLocation.peerLocation.latitude,
                                liveLocation.peerLocation.longitude,
                                "Peer Live Location"
                            )
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = "Open Map",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Peer: ${liveLocation.peerMovementState.label} • Me: ${liveLocation.localMovementState.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
                if (liveLocation.lastPeerUpdateMs > 0L) {
                    Text(
                        text = LocationHelper.formatLocationAge(liveLocation.lastPeerUpdateMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (liveLocation.relativeDirection != com.itantra.utils.RelativeDirection.UNAVAILABLE) {
                Text(
                    text = "Relative Heading: ${liveLocation.relativeDirection.label}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transcript log
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TranscriptLog(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
    onReplay: (Int) -> Boolean,
) {
    val listState  = rememberLazyListState()
    val timeFmt    = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    LazyColumn(
        state             = listState,
        modifier          = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding    = PaddingValues(bottom = 80.dp),
    ) {
        items(entries, key = { "${it.sequenceId}-${it.direction}-${it.sentMs}" }) { entry ->
            TranscriptBubble(entry, timeFmt, onReplay)
        }
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry, timeFmt: SimpleDateFormat, onReplay: (Int) -> Boolean) {
    val context = LocalContext.current
    val sent = entry.direction == Direction.SENT
    val bg = when {
        entry.isAlert -> MaterialTheme.colorScheme.errorContainer
        sent          -> MaterialTheme.colorScheme.primaryContainer
        else          -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = when {
        entry.isAlert -> MaterialTheme.colorScheme.onErrorContainer
        sent          -> MaterialTheme.colorScheme.onPrimaryContainer
        else          -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.widthIn(max = 330.dp)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isAlert) Icon(Icons.Default.Warning, null, tint = fg, modifier = Modifier.size(14.dp))
                    Text(
                        text  = if (sent) "↑ TRANSMITTED [${entry.sessionMode}]" else "↓ RECEIVED [${entry.sessionMode}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.65f),
                    )
                }
                Text(entry.text, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = if (entry.isAlert) FontWeight.SemiBold else FontWeight.Normal)
                
                if (entry.isAlert) {
                    if (entry.latitude != null && entry.longitude != null) {
                        val lat = entry.latitude
                        val lon = entry.longitude
                        val accStr = LocationHelper.formatAccuracy(entry.accuracy)
                        val ageStr = LocationHelper.formatLocationAge(entry.locationTimestampMs ?: entry.sentMs)

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "GPS: ${LocationHelper.formatCoordinates(lat, lon)} ${if (accStr.isNotBlank()) "($accStr)" else ""}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (ageStr.isNotBlank()) {
                                            Text(
                                                text = "Fix age: $ageStr",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            LocationHelper.openMap(context, lat, lon, "Alert: ${entry.text}")
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Map,
                                            contentDescription = "Open Map",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                if (!sent) {
                                    val hasPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                                  ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (hasPerm) {
                                        val localLoc = LocationHelper.getLastKnownLocation(context)
                                        if (localLoc != null) {
                                            val dist = LocationHelper.calculateDistanceMeters(localLoc.latitude, localLoc.longitude, lat, lon)
                                            val bearing = LocationHelper.calculateBearing(localLoc.latitude, localLoc.longitude, lat, lon)
                                            val dir = LocationHelper.getBearingDirection(bearing)
                                            val distStr = LocationHelper.formatDistance(dist)
                                            EmergencyCompassView(bearingDeg = bearing, distanceStr = distStr, directionStr = dir)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val statusText = when (entry.locationStatus) {
                            "Permission denied", "PERMISSION_DENIED" -> "Location: Permission denied"
                            "Location services disabled", "LOCATION_SERVICES_DISABLED" -> "Location: Services disabled (GPS off)"
                            "Waiting for location", "WAITING_FOR_LOCATION" -> "Location: Waiting for fix"
                            else -> "Location: Unavailable (No GPS fix)"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${entry.languageCode} • ${timeFmt.format(Date(entry.sentMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f),
                    )
                    if (!sent) {
                        IconButton(onClick = { onReplay(entry.sequenceId) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Replay, contentDescription = "Replay received voice", tint = fg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmergencyCompassView(
    bearingDeg: Float,
    distanceStr: String,
    directionStr: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var deviceAzimuth by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR) {
                    val rotationMatrix = FloatArray(9)
                    android.hardware.SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientation = FloatArray(3)
                    android.hardware.SensorManager.getOrientation(rotationMatrix, orientation)
                    val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                    deviceAzimuth = (azimuth + 360f) % 360f
                }
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    // Relative angle: target bearing relative to device heading
    val relativeAngle = (bearingDeg - deviceAzimuth + 360f) % 360f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        modifier = modifier.padding(vertical = 4.dp).fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compass Dial Canvas
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 4f

                    // Draw outer ring
                    drawCircle(
                        color = Color.Gray.copy(alpha = 0.35f),
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )

                    // Draw North marker dot rotated by -deviceAzimuth
                    val northAngleRad = Math.toRadians((-deviceAzimuth - 90f).toDouble())
                    val nx = center.x + (radius - 8f) * kotlin.math.cos(northAngleRad).toFloat()
                    val ny = center.y + (radius - 8f) * kotlin.math.sin(northAngleRad).toFloat()
                    drawCircle(
                        color = Color(0xFF1976D2),
                        radius = 3.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(nx, ny)
                    )

                    // Draw target emergency pointer rotated by relativeAngle
                    val pointerAngleRad = Math.toRadians((relativeAngle - 90f).toDouble())
                    val px = center.x + (radius - 4f) * kotlin.math.cos(pointerAngleRad).toFloat()
                    val py = center.y + (radius - 4f) * kotlin.math.sin(pointerAngleRad).toFloat()
                    drawLine(
                        color = Color.Red,
                        start = center,
                        end = androidx.compose.ui.geometry.Offset(px, py),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawCircle(
                        color = Color.Red,
                        radius = 4.dp.toPx(),
                        center = center
                    )
                }
            }

            Column {
                Text(
                    text = "EMERGENCY BEARING",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "$distanceStr • $directionStr (${bearingDeg.toInt()}°)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Follow pointer to source",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Latency debug overlay
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LatencyPanel(entries: List<TranscriptEntry>, expanded: Boolean, onToggle: () -> Unit) {
    val lastRecv = entries.lastOrNull { it.direction == Direction.RECEIVED }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = "Latency Breakdown",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (lastRecv != null && !expanded) {
                val totalMs = if (lastRecv.totalE2eLatencyMs > 0L) {
                    lastRecv.totalE2eLatencyMs
                } else if (lastRecv.ttsStartedMs > 0 && lastRecv.micPauseDetectedMs > 0) {
                    lastRecv.ttsStartedMs - lastRecv.micPauseDetectedMs
                } else null
                totalMs?.let {
                    Text(
                        text = "Total: $it ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (it < 1500L) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                if (lastRecv == null) {
                    Text(
                        text = "No received speech segments yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Seq #${lastRecv.sequenceId} (${lastRecv.languageCode})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        val sttMs = if (lastRecv.sttLatencyMs > 0L) lastRecv.sttLatencyMs else if (lastRecv.sttCompleteMs > 0 && lastRecv.micPauseDetectedMs > 0) {
                            lastRecv.sttCompleteMs - lastRecv.micPauseDetectedMs
                        } else 0L
                        LatencyRow(label = "STT (mic pause → text)", ms = sttMs)

                        val netMs = if (lastRecv.networkLatencyMs > 0L) lastRecv.networkLatencyMs else if (lastRecv.receivedMs > 0 && lastRecv.sentMs > 0) {
                            lastRecv.receivedMs - lastRecv.sentMs
                        } else 0L
                        LatencyRow(label = "Transport (send → recv)", ms = netMs)

                        val ttsMs = if (lastRecv.ttsLatencyMs > 0L) lastRecv.ttsLatencyMs else if (lastRecv.ttsStartedMs > 0 && lastRecv.receivedMs > 0) {
                            lastRecv.ttsStartedMs - lastRecv.receivedMs
                        } else 0L
                        LatencyRow(label = "TTS synthesis (start → audio)", ms = ttsMs)

                        val totalMs = if (lastRecv.totalE2eLatencyMs > 0L) lastRecv.totalE2eLatencyMs else (sttMs + netMs + ttsMs)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        LatencyRow(label = "TOTAL latency", ms = totalMs, bold = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyRow(label: String, ms: Long, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label,   style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(
            text      = "$ms ms",
            style     = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color     = when {
                ms < 100L  -> Color(0xFF4CAF50)
                ms < 500L  -> Color(0xFFFFC107)
                else       -> Color(0xFFF44336)
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live Telemetry Overlay
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TelemetryOverlay(
    telemetry: TelemetryState,
    modifier: Modifier = Modifier,
) {
    if (!telemetry.hasData) return

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "STT: ${telemetry.sttLatencyMs}ms | RTF: ${String.format(Locale.US, "%.2f", telemetry.rtf)}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "TTS: ${telemetry.ttsLatencyMs}ms | E2E: ${telemetry.e2eLatencyMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Payload: ${telemetry.payloadBytes}B | Net: ${telemetry.transport.ifBlank { "P2P" }}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
