package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.ui.components.tvButtonFocusable
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.theme.*
import java.io.File

/**
 * DYLANDOS IPTV ULTIMATE — DVR Screen
 *
 * Three tabs:
 *   Active      — currently recording channels with a stop button
 *   Completed   — finished recordings; play or delete
 *   Scheduled   — (placeholder) future EPG-scheduled recordings
 *
 * D-pad:
 *   UP/DOWN     — scroll list
 *   LEFT/RIGHT  — switch tabs
 *   OK          — activate focused button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DvrScreen(
    navController: NavController,
    viewModel: DvrViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showStoragePicker by remember { mutableStateOf(false) }

    // Auto-jump to Completed tab when there are finished recordings but nothing active
    LaunchedEffect(uiState.completedRecordings.size, uiState.activeRecordings.size) {
        if (uiState.activeRecordings.isEmpty() && uiState.completedRecordings.isNotEmpty()) {
            selectedTab = 1
        }
    }

    // Root container — LEFT/RIGHT switches tabs
    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft  -> {
                        if (selectedTab > 0) {
                            selectedTab--
                            true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (selectedTab < 2) {
                            selectedTab++
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ────────────────────────────────────────────────────────
            TopAppBar(
                title = { Text("DVR Manager", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    DvrIconBtn(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface, titleContentColor = TextPrimary
                )
            )

            // Storage status indicator (compact — no longer the only way to set storage)
            if (uiState.storagePath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x1400CC66))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00CC66), modifier = Modifier.size(14.dp))
                    Text("Storage ready: OTG / SAF folder selected", color = Color(0xFF00CC66), fontSize = 12.sp)
                }
            }

            // ── Tabs ───────────────────────────────────────────────────────────
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = BgSurface2,
                contentColor = Accent
            ) {
                listOf("Active (${uiState.activeRecordings.size})", "Completed", "Scheduled").forEachIndexed { i, label ->
                    val dvrTabInteraction = remember(i) { MutableInteractionSource() }
                    val dvrTabFocused by dvrTabInteraction.collectIsFocusedAsState()
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label, fontSize = 13.sp) },
                        modifier = Modifier
                            .then(if (dvrTabFocused) Modifier.background(Accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp)) else Modifier)
                            .focusable(interactionSource = dvrTabInteraction)
                    )
                }
            }

            // ── Content ────────────────────────────────────────────────────────
            val storagePickerLaunch: () -> Unit = {
                showStoragePicker = true
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> ActiveTab(
                        recordings = uiState.activeRecordings,
                        viewModel = viewModel,
                        uiState = uiState,
                        onPickStorage = storagePickerLaunch
                    )
                    1 -> CompletedTab(uiState.completedRecordings, viewModel, navController)
                    2 -> ScheduledTab(
                        scheduledRecordings = uiState.scheduledRecordings,
                        onCancel = { viewModel.cancelScheduled(it) },
                        hasConflict = { viewModel.hasScheduleConflict(it) }
                    )
                }
            }
        }

        if (showStoragePicker) {
            DvrStoragePickerScreen(
                onSelected = { option ->
                    option.persistedStorageValue()?.let { viewModel.setStoragePath(it) }
                    showStoragePicker = false
                },
                onDismiss = { showStoragePicker = false }
            )
        }

        // ── Snackbar status message ────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState.statusMessage != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            uiState.statusMessage?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.clearStatus()
                }
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    containerColor = BgElevated,
                    contentColor = TextPrimary
                ) { Text(msg) }
            }
        }
    }
}

// ── Active Recordings Tab ─────────────────────────────────────────────────────

@Composable
private fun ActiveTab(
    recordings: List<DvrRecording>,
    viewModel: DvrViewModel,
    uiState: DvrUiState,
    onPickStorage: () -> Unit
) {
    // Auto-focus the storage picker card on entry so Firestick D-pad starts here, not the Back button
    val storageCardFR = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { storageCardFR.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Storage Location Button (D-pad focusable, at top of tab content) ─
        StoragePickerCard(uiState = uiState, onPickStorage = onPickStorage, focusRequester = storageCardFR)
        DvrConfidencePanel(uiState = uiState)

        if (recordings.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Default.RadioButtonChecked,
                    title = "No active recordings",
                    subtitle = "Start recording from the Live TV channel list or the player"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recordings, key = { it.id }) { rec ->
                    ActiveRecordingCard(
                        rec = rec,
                        isStopping = rec.id in uiState.stoppingRecordingIds,
                        onStop = { viewModel.stopRecording(rec.id) }
                    )
                }
            }
        }
    }
}

/**
 * D-pad-focusable storage picker card shown at the top of the Active tab.
 * Press OK/Enter to open the SAF folder picker.
 * Optional [focusRequester] lets the caller auto-focus this card on screen entry.
 */
@Composable
private fun StoragePickerCard(uiState: DvrUiState, onPickStorage: () -> Unit, focusRequester: FocusRequester? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val hasStorage = uiState.storagePath != null || uiState.storageIsExternalPreferred
    val hasCapacityStats = uiState.storageFreeBytes >= 0L && uiState.storageTotalBytes > 0L
    val freeGb = uiState.storageFreeBytes.coerceAtLeast(0L) / 1_073_741_824.0
    val totalGb = uiState.storageTotalBytes.coerceAtLeast(0L) / 1_073_741_824.0
    val storageLine = if (hasCapacityStats) {
        "%.1f GB free of %.1f GB".format(freeGb, totalGb)
    } else {
        "Capacity hidden by Fire OS - DVR will verify with a write test"
    }
    val usedFraction = if (hasCapacityStats) {
        ((uiState.storageTotalBytes - uiState.storageFreeBytes).toFloat() / uiState.storageTotalBytes.toFloat())
            .coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (isFocused) Modifier.border(3.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onPickStorage
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onPickStorage(); true }
                    else -> false
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = if (hasStorage)
                Color(0x1400CC66)
            else if (isFocused) Accent.copy(alpha = 0.15f)
            else Color(0x22FFAA00)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (hasStorage) Icons.Default.CheckCircle else Icons.Default.FolderOpen,
                contentDescription = "Storage",
                tint = if (hasStorage) Color(0xFF00CC66) else if (isFocused) Accent else StatusWarning,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasStorage) uiState.storageLabel else "Set Recording Storage Location",
                    color = if (hasStorage) Color(0xFF00CC66) else if (isFocused) Accent else StatusWarning,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (hasStorage) storageLine
                           else "Press OK to choose USB / OTG drive folder",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                if (hasCapacityStats) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = if (usedFraction > 0.90f) StatusError else AccentBlue,
                        trackColor = Color.White.copy(alpha = 0.12f)
                    )
                } else if (hasStorage) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusSuccess, modifier = Modifier.size(13.dp))
                        Text(
                            text = "False 0% reports are ignored until the write probe fails.",
                            color = StatusSuccess,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasStorage && uiState.storageDetail.isNotBlank()) {
                    Text(
                        text = uiState.storageDetail,
                        color = TextTertiary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
            if (!hasStorage) {
                Surface(
                    color = if (isFocused) Accent else Accent.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "[ OK ]",
                        color = if (isFocused) Color.Black else Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            } else {
                Surface(
                    color = Color(0x2200CC66),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "USB FIRST",
                        color = Color(0xFF00CC66),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DvrConfidencePanel(uiState: DvrUiState) {
    val capacityKnown = uiState.storageFreeBytes >= 0L && uiState.storageTotalBytes > 0L
    val freeLabel = if (capacityKnown) {
        "%.1f GB free".format(uiState.storageFreeBytes / 1_073_741_824.0)
    } else {
        "Fire OS capacity hidden"
    }
    val healthColor = when {
        uiState.storageWarning != null -> StatusWarning
        uiState.storageIsExternalPreferred -> StatusSuccess
        else -> Accent
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        color = BgSurface2.copy(alpha = 0.72f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, healthColor.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.CheckCircle, null, tint = healthColor, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "DVR confidence: ${if (uiState.storageIsExternalPreferred) "USB-first" else "storage ready"}",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
                Text(
                    text = "$freeLabel - every recording runs a folder write probe before start",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DvrHealthChip("${uiState.activeRecordings.size} active", StatusError)
            DvrHealthChip(if (capacityKnown) "meter ready" else "probe mode", healthColor)
        }
    }
}

@Composable
private fun DvrHealthChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f))
    ) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}


@Composable
private fun ActiveRecordingCard(rec: DvrRecording, isStopping: Boolean, onStop: () -> Unit) {
    // Track Stop button focus to light up the card border
    val stopInteraction = remember { MutableInteractionSource() }
    val stopFocused     by stopInteraction.collectIsFocusedAsState()

    // Live elapsed timer — refreshes every second
    var elapsedLabel by remember { mutableStateOf(rec.formattedDuration()) }
    LaunchedEffect(rec.id) {
        while (true) {
            elapsedLabel = rec.formattedDuration()
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (stopFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = BgSurface2),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recording dot pulse
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(StatusError, shape = RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rec.channelName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Started: ${rec.formattedDate()}", color = TextSecondary, fontSize = 11.sp)
                    Text("Elapsed: $elapsedLabel", color = Accent, fontSize = 11.sp)
                }
            }

            // Stop button — D-pad focus lands here directly (no card-level focusable blocker)
            IconButton(
                onClick = onStop,
                enabled = !isStopping,
                modifier = Modifier
                    .tvButtonFocusable(interactionSource = stopInteraction)
                    .background(Color(0x22FF4444), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    if (isStopping) Icons.Default.HourglassTop else Icons.Default.Stop,
                    if (isStopping) "Stopping recording" else "Stop Recording",
                    tint = if (isStopping) TextSecondary else StatusError
                )
            }
        }
    }
}

// ── Completed Tab ─────────────────────────────────────────────────────────────

@Composable
private fun CompletedTab(
    recordings: List<DvrRecording>,
    viewModel: DvrViewModel,
    navController: NavController
) {
    if (recordings.isEmpty()) {
        EmptyState(
            icon = Icons.Default.VideoLibrary,
            title = "No completed recordings",
            subtitle = "Completed recordings will appear here"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(recordings, key = { it.id }) { rec ->
                CompletedRecordingCard(
                    rec,
                    onPlay = {
                        // Open the premium Recording Details screen; PLAY there launches
                        // the player with the permanent file URI (stream type "dvr").
                        navController.navigate(
                            Screen.RecordingDetail.createRoute(rec.id)
                        )
                    },
                    onDelete = { viewModel.deleteRecording(rec.id) }
                )
            }
        }
    }
}

@Composable
private fun CompletedRecordingCard(rec: DvrRecording, onPlay: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val actualFileSize = remember(rec.filePath, rec.fileSizeBytes) {
        resolveDvrFileSize(context, rec.filePath)
    }
    val playableBytes = maxOf(rec.fileSizeBytes, actualFileSize)
    val canPlay = rec.filePath.isNotEmpty() && playableBytes > 0L
    val failedRecording = rec.filePath.isEmpty() || !canPlay

    // Separate FocusRequesters for each interactive element
    val playFocusReq    = remember { FocusRequester() }
    val deleteFocusReq  = remember { FocusRequester() }
    val cancelFocusReq  = remember { FocusRequester() }
    val confirmFocusReq = remember { FocusRequester() }

    // Track focus on individual buttons — card border lights up when ANY button inside is focused
    val playInteraction   = remember { MutableInteractionSource() }
    val deleteInteraction = remember { MutableInteractionSource() }
    val playFocused       by playInteraction.collectIsFocusedAsState()
    val deleteFocused     by deleteInteraction.collectIsFocusedAsState()
    val cardHighlighted   = playFocused || deleteFocused

    // Auto-focus Cancel button when confirmation row appears
    LaunchedEffect(showDeleteConfirm) {
        if (showDeleteConfirm) {
            kotlinx.coroutines.delay(50)  // allow AnimatedVisibility to complete its enter transition
            runCatching { cancelFocusReq.requestFocus() }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (cardHighlighted) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = BgSurface2),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(rec.channelName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(rec.formattedDate(), color = TextSecondary, fontSize = 11.sp)
                        Text(rec.formattedDuration(), color = TextTertiary, fontSize = 11.sp)
                        if (failedRecording) {
                            Text("Failed: no video bytes written", color = StatusError, fontSize = 11.sp)
                        } else {
                            Text(rec.formattedSize(), color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                    if (rec.filePath.isNotEmpty()) {
                        Text(rec.filePath, color = TextTertiary, fontSize = 10.sp, maxLines = 1)
                    }
                }

                // Action buttons — Play is the primary D-pad landing point (LEFT/RIGHT between them)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft  -> { runCatching { playFocusReq.requestFocus() };   true }
                            Key.DirectionRight -> { runCatching { deleteFocusReq.requestFocus() }; true }
                            else -> false
                        }
                    }
                ) {
                    // Play button — disabled while filePath is empty or the recording failed to write bytes.
                    IconButton(
                        onClick = onPlay,
                        enabled = canPlay,
                        modifier = Modifier.tvButtonFocusable(
                            focusRequester    = playFocusReq,
                            interactionSource = playInteraction
                        )
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "Play",
                            tint = if (canPlay) Accent else TextSecondary.copy(alpha = 0.4f)
                        )
                    }
                    // Delete button
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.tvButtonFocusable(
                            focusRequester    = deleteFocusReq,
                            interactionSource = deleteInteraction
                        )
                    ) {
                        Icon(Icons.Default.Delete, "Delete", tint = StatusError)
                    }
                }
            }

            // Inline delete confirmation — Cancel auto-focuses; LEFT/RIGHT between buttons
            AnimatedVisibility(showDeleteConfirm) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft  -> { runCatching { cancelFocusReq.requestFocus() };  true }
                                Key.DirectionRight -> { runCatching { confirmFocusReq.requestFocus() }; true }
                                Key.Back -> { showDeleteConfirm = false; runCatching { deleteFocusReq.requestFocus() }; true }
                                else -> false
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Delete this recording?",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            showDeleteConfirm = false
                            runCatching { deleteFocusReq.requestFocus() }
                        },
                        modifier = Modifier.tvButtonFocusable(focusRequester = cancelFocusReq)
                    ) { Text("Cancel", fontSize = 11.sp) }
                    Button(
                        onClick = { onDelete(); showDeleteConfirm = false },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusError.copy(alpha = 0.7f)),
                        modifier = Modifier.tvButtonFocusable(focusRequester = confirmFocusReq)
                    ) { Text("Delete", fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun resolveDvrFileSize(context: Context, filePath: String): Long {
    if (filePath.isBlank()) return 0L
    return runCatching {
        when {
            filePath.startsWith("content://") -> {
                val uri = Uri.parse(filePath)
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0) cursor.getLong(idx) else 0L
                    } else {
                        0L
                    }
                } ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    afd.length.coerceAtLeast(0L)
                } ?: 0L
            }
            filePath.startsWith("file://") -> File(Uri.parse(filePath).path.orEmpty()).length()
            else -> File(filePath).length()
        }
    }.getOrDefault(0L)
}

// ── Scheduled Tab ─────────────────────────────────────────────────────────────

@Composable
private fun ScheduledTab(
    scheduledRecordings: List<ScheduledRecording> = emptyList(),
    onCancel: (String) -> Unit = {},
    hasConflict: (ScheduledRecording) -> Boolean = { false }
) {
    if (scheduledRecordings.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Schedule,
            title = "No scheduled recordings",
            subtitle = "Schedule recordings from the EPG Guide by pressing OK on any programme"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(scheduledRecordings, key = { it.id }) { rec ->
                ScheduledRecordingCard(
                    rec = rec,
                    onCancel = { onCancel(rec.id) },
                    conflict = hasConflict(rec)
                )
            }
        }
    }
}

@Composable
private fun ScheduledRecordingCard(
    rec: ScheduledRecording,
    onCancel: () -> Unit,
    conflict: Boolean = false
) {
    val cancelInteraction = remember { MutableInteractionSource() }
    val cancelFocused by cancelInteraction.collectIsFocusedAsState()
    val isUpcoming = rec.scheduledStartMs > System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BgSurface),
        shape = RoundedCornerShape(10.dp),
        border = when {
            cancelFocused -> BorderStroke(2.dp, Accent)
            conflict -> BorderStroke(1.dp, StatusWarning)
            else -> null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when {
                            conflict -> StatusWarning
                            isUpcoming -> Accent
                            else -> StatusWarning
                        },
                        RoundedCornerShape(50)
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(rec.programTitle, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(rec.channelName, style = MaterialTheme.typography.bodySmall, color = Accent)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Schedule, null, tint = TextTertiary, modifier = Modifier.size(12.dp))
                    Text(rec.formattedTime(), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                if (conflict) {
                    Text(
                        "Overlaps another scheduled recording",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusWarning
                    )
                }
                if (!isUpcoming) {
                    Text("Passed — not yet cancelled", style = MaterialTheme.typography.labelSmall, color = StatusWarning)
                }
            }
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                modifier = Modifier.tvButtonFocusable(interactionSource = cancelInteraction)
            ) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Cancel", fontSize = 11.sp)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(64.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextSecondary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextTertiary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun DvrIconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .then(if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
    ) { content() }
}
