package com.itantra.ui.session

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.itantra.session.Direction
import com.itantra.session.FloorState
import com.itantra.session.SessionController
import com.itantra.session.SessionMode
import com.itantra.session.SupportedLanguage
import com.itantra.session.TranscriptEntry
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

    var latencyExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
                onClick          = { vm.triggerAlert() },
                icon             = { Icon(Icons.Default.Warning, contentDescription = null) },
                text             = { Text("Send Alert") },
                containerColor   = MaterialTheme.colorScheme.errorContainer,
                contentColor     = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Synchronized PTT / Phone mode toggle ─────────────────────────
            ModeToggleRow(mode = mode, onModeChange = { vm.setMode(it) })

            // ── Half-Duplex Floor Status Banner ──────────────────────────────
            FloorStatusBanner(mode = mode, floorState = floorState)

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
private fun FloorStatusBanner(mode: SessionMode, floorState: FloorState) {
    if (mode == SessionMode.PHONE) {
        Card(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
        Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.widthIn(max = 290.dp)) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.isAlert) Icon(Icons.Default.Warning, null, tint = fg, modifier = Modifier.size(13.dp))
                    Text(
                        text  = if (sent) "↑ TRANSMITTED [${entry.sessionMode}]" else "↓ RECEIVED [${entry.sessionMode}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.65f),
                    )
                }
                Text(entry.text, color = fg, style = MaterialTheme.typography.bodyMedium)
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

// ─────────────────────────────────────────────────────────────────────────────
// Latency debug overlay
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LatencyPanel(entries: List<TranscriptEntry>, expanded: Boolean, onToggle: () -> Unit) {
    val lastRecv = entries.lastOrNull { it.direction == Direction.RECEIVED }

    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text("⏱ Latency Debug", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onToggle, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector      = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                if (lastRecv == null) {
                    Text(
                        "No RECEIVED entries yet — send a message from the other phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LatencyRow("Δ Model (STT inference)",          lastRecv.deltaModelMs)
                        LatencyRow("Δ Queue (STT complete → wire)",    lastRecv.deltaQueueMs)
                        LatencyRow("Δ Network (sent → received)",      lastRecv.deltaNetworkMs)
                        LatencyRow("Δ TTS start (received → speak)",   lastRecv.deltaTtsStartMs)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        LatencyRow("Σ Total (mic pause → TTS start)",  lastRecv.totalLatencyMs, bold = true)
                        Text(
                            "seq#${lastRecv.sequenceId} • ${lastRecv.languageCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
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
                ms < 100L  -> Color(0xFF4CAF50)   // green — fast
                ms < 500L  -> Color(0xFFFFC107)   // amber — acceptable
                else       -> Color(0xFFF44336)   // red — needs work
            },
        )
    }
}
