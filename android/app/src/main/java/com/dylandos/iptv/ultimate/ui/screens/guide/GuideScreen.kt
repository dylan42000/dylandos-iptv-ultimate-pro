package com.dylandos.iptv.ultimate.ui.screens.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.ui.navigation.popBackStackSafeDebounced
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrViewModel
import com.dylandos.iptv.ultimate.ui.theme.*

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Extracts the dominant dark color from a channel logo URL using Palette API.
 * Returns [BgSurface2] if the image cannot be loaded or has no dominant swatch.
 * The result is cached per [imageUrl] via [remember].
 */
@Composable
private fun rememberDominantColor(imageUrl: String?): Color {
    var color by remember(imageUrl) { mutableStateOf(BgSurface2) }
    val context = LocalContext.current
    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                // HIGH-007: Use the app-wide Coil singleton instead of constructing a
                // new ImageLoader(context) here. Each ImageLoader() call spins up its own
                // OkHttpClient + thread pool; on a 500-channel guide that wastes hundreds
                // of MB and opens 500 separate connection pools.
                val loader  = context.imageLoader  // Coil singleton from DylandosApp
                val request = ImageRequest.Builder(context).data(imageUrl).allowHardware(false).build()
                val result  = loader.execute(request)
                val bitmap  = (result as? SuccessResult)
                    ?.drawable
                    ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                    ?: return@withContext
                val palette = Palette.from(bitmap).generate()
                val swatch  = palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.dominantSwatch
                swatch?.rgb?.let { rgb -> color = Color(rgb) }
            } catch (_: Exception) { /* keep default */ }
        }
    }
    return color
}

// ── Layout Constants ──────────────────────────────────────────────────────────

private val CHANNEL_COL_WIDTH = 160.dp
private val TIME_ROW_HEIGHT   = 40.dp
private val DP_PER_MINUTE     = 5.dp
private val CAT_PILL_HEIGHT   = 40.dp

/** Row height driven by Settings rowHeightMode — Compact=48dp, Normal=64dp, Large=80dp */
fun rowHeightForMode(mode: String): androidx.compose.ui.unit.Dp = when (mode) {
    "Compact" -> 48.dp
    "Large"   -> 80.dp
    else      -> 64.dp   // "Normal" default — fits ~15+ channels on a 1080p TV
}

/**
 * DYLANDOS IPTV ULTIMATE — EPG Guide Screen (Sling / Comcast Style)
 *
 * Layout:
 *  ┌────────┬──────────────────────────────────────────────────────────────────┐
 *  │TOP BAR │ ← [NOW →]  Time Navigation                                      │
 *  ├────────┤ Category Filter Pills                                            │
 *  │CHANNEL │  8:00 PM ─────────────── │ ──────────── 9:00 PM ──────────────  │
 *  │  COL   │  [=== Current Program ===│] [== Upcoming Program ============ ]  │
 *  │ (fixed)│  [=Upcoming=] [=========│====== Long Program ================]  │
 *  └────────┴──────────────────────────│────────────────────────────────────── │
 *                                   NOW▼ (red line)
 *
 * Architecture:
 *  - Two synchronized LazyColumns (channel info left, programs right) share listState
 *  - Time header and all program rows share hScrollState for horizontal sync
 *  - NOW indicator is a red Box overlay positioned at current time offset
 */
@Composable
fun GuideScreen(
    navController: NavController,
    viewModel: GuideViewModel = hiltViewModel(),
    dvrViewModel: DvrViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val dvrState by dvrViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDayPicker by remember { mutableStateOf(false) }
    if (showDayPicker) {
        AlertDialog(
            onDismissRequest = { showDayPicker = false },
            title = { Text("Choose guide day") },
            text = {
                Column {
                    repeat(7) { offset ->
                        val zone = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.displayTimeZone()
                        val day = java.util.Calendar.getInstance(zone).apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                        TextButton(onClick = {
                            viewModel.showTime(day.timeInMillis)
                            showDayPicker = false
                        }) {
                            Text(if (offset == 0) "Today" else SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).apply { timeZone = zone }.format(day.time))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDayPicker = false }) { Text("Close") } }
        )
    }

    // Dynamic row height from Settings
    val rowHeight = rowHeightForMode(uiState.rowHeightMode)

    // Shared state for synchronized scrolling
    val hScrollState = rememberScrollState(initial = uiState.horizontalScrollOffset)
    val channelListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.verticalListFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.verticalListFirstVisibleOffset
    )
    val programListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.verticalListFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.verticalListFirstVisibleOffset
    )

    // ── D-pad focus management ────────────────────────────────────────────────
    // gridFocusRequester: placed on the main guide grid Row so pressing DOWN
    // from a category pill lands directly in the channel grid.
    val gridFocusRequester = remember { FocusRequester() }

    // ── Category state — hoisted to screen level so the root Box UP handler
    // can move focus back from the grid to the category pills ──────────────
    var focusedCatIdx by remember { mutableIntStateOf(0) }
    val catFocusRequesters = remember(uiState.categories.size) {
        List(uiState.categories.size) { FocusRequester() }
    }

    // Restore where the user left off: selected category + focused channel/grid position.
    LaunchedEffect(
        uiState.isLoading,
        uiState.categories.size,
        uiState.selectedCategoryId,
        uiState.focusedChannelIndex,
        uiState.verticalListFirstVisibleIndex
    ) {
        if (!uiState.isLoading && uiState.categories.isNotEmpty()) {
            val selectedCatIdx = uiState.categories
                .indexOfFirst { it.categoryId == uiState.selectedCategoryId }
                .let { if (it >= 0) it else 0 }
            focusedCatIdx = selectedCatIdx
            val shouldRestoreGridFocus =
                uiState.focusedChannelIndex > 0 ||
                uiState.verticalListFirstVisibleIndex > 0 ||
                uiState.selectedCategoryId != "ALL"

            kotlinx.coroutines.delay(120)
            if (shouldRestoreGridFocus) {
                runCatching { gridFocusRequester.requestFocus() }
            } else {
                runCatching { catFocusRequesters.getOrNull(selectedCatIdx)?.requestFocus() }
            }
        }
    }

    // Auto-scroll list to keep the D-pad focused channel visible
    LaunchedEffect(uiState.focusedChannelIndex) {
        val idx = uiState.focusedChannelIndex
        if (uiState.filteredChannels.isNotEmpty() && idx >= 0) {
            val visible = channelListState.layoutInfo.visibleItemsInfo
            val firstVisible = visible.firstOrNull()?.index ?: channelListState.firstVisibleItemIndex
            val lastVisible = visible.lastOrNull()?.index ?: firstVisible
            val target = when {
                idx < firstVisible -> idx
                idx > lastVisible -> (idx - (visible.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
                else -> null
            }
            if (target != null) {
                channelListState.scrollToItem(target)
                programListState.scrollToItem(target)
            }
        }
    }

    var shouldRestoreFocusOnResume by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncFocusedChannelFromPlayback()
                shouldRestoreFocusOnResume = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(shouldRestoreFocusOnResume) {
        if (shouldRestoreFocusOnResume) {
            shouldRestoreFocusOnResume = false
            delay(100)
            runCatching { gridFocusRequester.requestFocus() }
        }
    }

    // Program options dialog state
    data class ProgramDialogData(
        val title: String, val channelName: String, val channelId: Int,
        val startMs: Long, val endMs: Long, val canTimeShift: Boolean
    )
    data class GuideRecordMenuData(
        val channel: XtreamChannel,
        val currentProgram: XtreamEpgProgram?,
        val nextProgram: XtreamEpgProgram?
    )
    var programDialog by remember { mutableStateOf<ProgramDialogData?>(null) }
    var selectedTimeMs by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var guideRecordMenu by remember { mutableStateOf<GuideRecordMenuData?>(null) }
    var recordingChannelToPick by remember { mutableStateOf<XtreamChannel?>(null) }
    var pendingScheduleConflict by remember {
        mutableStateOf<Triple<ProgramDialogData, List<com.dylandos.iptv.ultimate.ui.screens.dvr.ScheduledRecording>, () -> Unit>?>(null)
    }

    fun requestSchedule(
        channelName: String,
        channelId: Int,
        programTitle: String,
        startMs: Long,
        endMs: Long,
        closeDialog: () -> Unit
    ) {
        val conflicts = dvrViewModel.findOverlappingSchedules(startMs, endMs)
        val doSchedule = {
            dvrViewModel.scheduleRecordingByChannelId(
                channelName = channelName,
                channelId = channelId,
                programTitle = programTitle,
                startTimeMs = startMs,
                endTimeMs = endMs
            )
            closeDialog()
        }
        if (conflicts.isEmpty()) {
            doSchedule()
        } else {
            pendingScheduleConflict = Triple(
                ProgramDialogData(programTitle, channelName, channelId, startMs, endMs, false),
                conflicts,
                doSchedule
            )
        }
    }

    fun requestSeriesOrKeywordRule(
        type: String,
        channelName: String,
        channelId: Int,
        keyword: String,
        closeDialog: () -> Unit
    ) {
        val nowSec = System.currentTimeMillis() / 1000L
        val programs = uiState.epgData[channelId].orEmpty()
        val needle = keyword.trim().lowercase()
        val matches = programs.mapNotNull { p ->
            val title = p.title.orEmpty()
            val hay = title.lowercase()
            val hit = when (type) {
                "series" -> hay == needle || hay.startsWith(needle)
                else -> hay.contains(needle)
            }
            if (!hit || p.stopTimestamp <= nowSec) null
            else Triple(p.startTimestamp * 1000L, p.stopTimestamp * 1000L, title.ifBlank { keyword })
        }.take(24)
        val ruleConflicts = dvrViewModel.findRuleConflicts(matches)
        val saveRuleAndMatches = {
            dvrViewModel.scheduleRecordingByChannelId(
                channelName = channelName,
                channelId = channelId,
                programTitle = "[$type] $keyword",
                startTimeMs = matches.firstOrNull()?.first ?: System.currentTimeMillis(),
                endTimeMs = matches.firstOrNull()?.second
                    ?: (System.currentTimeMillis() + 3_600_000L),
                recordingType = type,
                matchKeyword = keyword
            )
            matches.forEach { (start, end, title) ->
                if (dvrViewModel.findOverlappingSchedules(start, end).isEmpty()) {
                    dvrViewModel.scheduleRecordingByChannelId(
                        channelName = channelName,
                        channelId = channelId,
                        programTitle = title,
                        startTimeMs = start,
                        endTimeMs = end,
                        recordingType = "once",
                        matchKeyword = keyword
                    )
                }
            }
            closeDialog()
        }
        if (ruleConflicts.isEmpty()) {
            saveRuleAndMatches()
        } else {
            val sample = ruleConflicts.first()
            pendingScheduleConflict = Triple(
                ProgramDialogData(
                    "$type rule \"$keyword\" — ${ruleConflicts.size} conflict window(s)",
                    channelName,
                    channelId,
                    sample.second.firstOrNull()?.scheduledStartMs ?: System.currentTimeMillis(),
                    sample.second.firstOrNull()?.scheduledEndMs ?: System.currentTimeMillis(),
                    false
                ),
                ruleConflicts.flatMap { it.second }.distinctBy { it.id },
                saveRuleAndMatches
            )
        }
    }

    fun openRecordMenuForFocusedChannel() {
        val channel = uiState.filteredChannels.getOrNull(uiState.focusedChannelIndex) ?: return
        val selected = uiState.epgData[channel.streamId].orEmpty().firstOrNull {
            selectedTimeMs in (it.startTimestamp * 1000L) until (it.stopTimestamp * 1000L)
        }
        if (selected != null) {
            programDialog = ProgramDialogData(selected.title, channel.name, channel.streamId,
                selected.startTimestamp * 1000L, selected.stopTimestamp * 1000L, selected.hasArchive > 0 || channel.tvArchive > 0)
            return
        }
        val nowSec = System.currentTimeMillis() / 1000L
        val programs = uiState.epgData[channel.streamId].orEmpty().sortedBy { it.startTimestamp }
        val current = programs.firstOrNull { nowSec in it.startTimestamp until it.stopTimestamp }
        val next = programs.firstOrNull { it.startTimestamp > nowSec }
        guideRecordMenu = GuideRecordMenuData(
            channel = channel,
            currentProgram = current,
            nextProgram = next
        )
    }

    // Show schedule recording dialog when a program is tapped
    programDialog?.let { dlg ->
        val nowMs = System.currentTimeMillis()
        val canTimeShiftNow = dlg.canTimeShift && nowMs >= dlg.startMs
        // Past programmes on archive-capable channels should always offer catch-up
        // even when the EPG cell's hasArchive flag was missing.
        val canCatchUpPast = dlg.endMs < nowMs && dlg.canTimeShift
        val showWatchFromStart = canTimeShiftNow || canCatchUpPast
        AlertDialog(
            onDismissRequest = { programDialog = null },
            title = { Text(dlg.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatTimeRange(dlg.startMs, dlg.endMs),
                        color = TextTertiary,
                        fontSize = 12.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        viewModel.setActiveChannelByStreamId(dlg.channelId)
                        programDialog = null
                        navController.navigateSafe(Screen.Player.createRoute("live", dlg.channelId.toString(), "ts"))
                    }) { Text("Watch Live", color = Accent) }

                    if (showWatchFromStart) {
                        TextButton(onClick = {
                            viewModel.setActiveChannelByStreamId(dlg.channelId)
                            viewModel.setPendingPlaybackTitle("${dlg.channelName} - ${dlg.title}")
                            val token = viewModel.buildTimeShiftToken(
                                streamId = dlg.channelId,
                                startMs = dlg.startMs,
                                endMs = dlg.endMs
                            )
                            programDialog = null
                            navController.navigateSafe(Screen.Player.createRoute("timeshift", token, "ts"))
                        }) { Text("Watch From Start", color = AccentBright) }
                    }

                    TextButton(onClick = {
                        requestSchedule(
                            channelName = dlg.channelName,
                            channelId = dlg.channelId,
                            programTitle = dlg.title,
                            startMs = dlg.startMs,
                            endMs = dlg.endMs,
                            closeDialog = { programDialog = null }
                        )
                    }, enabled = dlg.endMs > nowMs) { Text("Schedule This Program (${dlg.title})", color = AccentSecondary) }
                    if (dlg.startMs > nowMs) {
                        TextButton(onClick = {
                            val enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                            if (enabled) com.dylandos.iptv.ultimate.workers.ProgramReminderWorker.schedule(context, dlg.channelId, dlg.channelName, dlg.title, dlg.startMs, dlg.endMs)
                            android.widget.Toast.makeText(context, if (enabled) "Reminder set for two minutes before the program. Sleeping devices may deliver it later." else "Enable app notifications to receive reminders.", android.widget.Toast.LENGTH_LONG).show()
                            programDialog = null
                        }) { Text("Remind me") }
                        TextButton(onClick = {
                            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(com.dylandos.iptv.ultimate.workers.ProgramReminderWorker.key(dlg.channelId, dlg.startMs))
                            android.widget.Toast.makeText(context, "Reminder canceled", android.widget.Toast.LENGTH_SHORT).show()
                            programDialog = null
                        }) { Text("Cancel reminder") }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(dlg.channelName, dlg.channelId, 30, null, dlg.title)
                            programDialog = null
                        }) { Text("Record 30m", color = AccentBright, fontSize = 12.sp) }
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(dlg.channelName, dlg.channelId, 60, null, dlg.title)
                            programDialog = null
                        }) { Text("Record 1h", color = AccentBright, fontSize = 12.sp) }
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(dlg.channelName, dlg.channelId, 120, null, dlg.title)
                            programDialog = null
                        }) { Text("Record 2h", color = AccentBright, fontSize = 12.sp) }
                    }

                    TextButton(onClick = {
                        requestSeriesOrKeywordRule(
                            type = "series",
                            channelName = dlg.channelName,
                            channelId = dlg.channelId,
                            keyword = dlg.title,
                            closeDialog = { programDialog = null }
                        )
                    }) { Text("Series Rule (same title)", color = AccentBright) }

                    TextButton(onClick = {
                        requestSeriesOrKeywordRule(
                            type = "keyword",
                            channelName = dlg.channelName,
                            channelId = dlg.channelId,
                            keyword = dlg.title,
                            closeDialog = { programDialog = null }
                        )
                    }) { Text("Keyword Rule (EPG match)", color = AccentBright) }
                }
            },
            confirmButton = {
                TextButton(onClick = { programDialog = null }) { Text("Close") }
            },
            containerColor = BgSurface2,
            titleContentColor = TextPrimary
        )
    }

    guideRecordMenu?.let { menu ->
        AlertDialog(
            onDismissRequest = { guideRecordMenu = null },
            title = {
                Text(
                    text = "${menu.channel.name} - Recording & Live Options",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val accounts = dvrState.recordingAccounts
                    if (accounts.size > 1) {
                        Surface(
                            color = AccentSurface,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        ) {
                            Text(
                                "Dual-Subscription Active: DVR recordings auto-route to secondary account",
                                color = Accent,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    TextButton(onClick = {
                        navController.navigateSafe(
                            Screen.Player.createRoute("live", menu.channel.streamId.toString(), "ts")
                        )
                        guideRecordMenu = null
                    }) {
                        Text("Watch Channel Now", color = Accent)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(menu.channel.name, menu.channel.streamId, 30, null, menu.currentProgram?.title)
                            guideRecordMenu = null
                        }) { Text("Record 30m", color = AccentSecondary, fontSize = 12.sp) }
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(menu.channel.name, menu.channel.streamId, 60, null, menu.currentProgram?.title)
                            guideRecordMenu = null
                        }) { Text("Record 1h", color = AccentSecondary, fontSize = 12.sp) }
                        TextButton(onClick = {
                            dvrViewModel.recordLiveChannelForDuration(menu.channel.name, menu.channel.streamId, 120, null, menu.currentProgram?.title)
                            guideRecordMenu = null
                        }) { Text("Record 2h", color = AccentSecondary, fontSize = 12.sp) }
                    }

                    TextButton(onClick = {
                        if (accounts.size > 1) {
                            recordingChannelToPick = menu.channel
                        } else {
                            dvrViewModel.recordLiveChannel(
                                channelName = menu.channel.name,
                                streamId = menu.channel.streamId,
                                accountId = accounts.firstOrNull()?.id,
                                programTitle = menu.currentProgram?.title
                            )
                        }
                        guideRecordMenu = null
                    }) {
                        Text("Record Channel (Until Stopped)", color = AccentSecondary)
                    }

                    menu.currentProgram?.let { current ->
                        TextButton(onClick = {
                            requestSchedule(
                                channelName = menu.channel.name,
                                channelId = menu.channel.streamId,
                                programTitle = current.title.ifBlank { "Current Program" },
                                startMs = current.startTimestamp * 1000L,
                                endMs = current.stopTimestamp * 1000L,
                                closeDialog = { guideRecordMenu = null }
                            )
                        }) {
                            Text(
                                text = "Record Current: ${current.title.ifBlank { "Current Program" }}",
                                color = AccentSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    menu.nextProgram?.let { next ->
                        TextButton(onClick = {
                            requestSchedule(
                                channelName = menu.channel.name,
                                channelId = menu.channel.streamId,
                                programTitle = next.title.ifBlank { "Next Program" },
                                startMs = next.startTimestamp * 1000L,
                                endMs = next.stopTimestamp * 1000L,
                                closeDialog = { guideRecordMenu = null }
                            )
                        }) {
                            Text(
                                text = "Schedule Next: ${next.title.ifBlank { "Next Program" }}",
                                color = AccentSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (menu.currentProgram == null && menu.nextProgram == null) {
                        Text(
                            text = "No EPG entries loaded for this channel yet.",
                            color = TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { guideRecordMenu = null }) { Text("Close") }
            },
            containerColor = BgSurface2,
            titleContentColor = TextPrimary
        )
    }

    pendingScheduleConflict?.let { (dlg, conflicts, proceed) ->
        AlertDialog(
            onDismissRequest = { pendingScheduleConflict = null },
            title = { Text("Recording Conflict", maxLines = 1) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "\"${dlg.title}\" overlaps ${conflicts.size} scheduled recording(s):",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    conflicts.take(4).forEach { c ->
                        Text(
                            "• ${c.programTitle} (${c.channelName}) — ${c.formattedTime()}",
                            color = StatusWarning,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (conflicts.size > 4) {
                        Text("…and ${conflicts.size - 4} more", color = TextTertiary, fontSize = 12.sp)
                    }
                    Text(
                        "Firestick can run up to 3 concurrent recordings. Schedule anyway?",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingScheduleConflict = null
                    proceed()
                }) { Text("Schedule Anyway", color = AccentSecondary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingScheduleConflict = null }) { Text("Cancel") }
            },
            containerColor = BgSurface2,
            titleContentColor = TextPrimary
        )
    }

    recordingChannelToPick?.let { channel ->
        AlertDialog(
            onDismissRequest = { recordingChannelToPick = null },
            title = { Text("Choose Recording Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dvrState.recordingAccounts.forEach { account ->
                        val isActive = account.id == dvrState.activeRecordingAccountId
                        TextButton(
                            onClick = {
                                dvrViewModel.recordLiveChannel(
                                    channelName = channel.name,
                                    streamId = channel.streamId,
                                    accountId = account.id
                                )
                                recordingChannelToPick = null
                            }
                        ) {
                            Text(
                                text = if (isActive) "${account.nickname} (active)" else account.nickname,
                                color = if (isActive) Accent else TextPrimary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { recordingChannelToPick = null }) { Text("Cancel") }
            },
            containerColor = BgSurface2,
            titleContentColor = TextPrimary
        )
    }

    val windowMinutes = remember(uiState.windowStartMs, uiState.windowEndMs) {
        ((uiState.windowEndMs - uiState.windowStartMs) / 60_000L).toInt()
    }
    val totalGridWidthDp = DP_PER_MINUTE * windowMinutes

    fun moveProgram(direction: Int) {
        val channel = uiState.filteredChannels.getOrNull(uiState.focusedChannelIndex)
        val programs = uiState.epgData[channel?.streamId].orEmpty().sortedBy { it.startTimestamp }
        val current = programs.firstOrNull { selectedTimeMs in (it.startTimestamp * 1000L) until (it.stopTimestamp * 1000L) }
        val target = if (direction > 0) {
            current?.let { it.stopTimestamp * 1000L + 1 }
                ?: programs.firstOrNull { it.startTimestamp * 1000L > selectedTimeMs }?.let { it.startTimestamp * 1000L + 1 }
                ?: (selectedTimeMs + 30 * 60_000L)
        } else {
            current?.let { it.startTimestamp * 1000L - 1 }
                ?: programs.lastOrNull { it.stopTimestamp * 1000L < selectedTimeMs }?.let { it.stopTimestamp * 1000L - 1 }
                ?: (selectedTimeMs - 30 * 60_000L)
        }
        val now = System.currentTimeMillis()
        selectedTimeMs = target.coerceIn(now - now % 3_600_000L, now + 7 * 86_400_000L - 1)
        if (selectedTimeMs !in uiState.windowStartMs until uiState.windowEndMs) viewModel.showTime(selectedTimeMs)
        scope.launch {
            val minutes = ((selectedTimeMs - uiState.windowStartMs) / 60_000L).toInt().coerceAtLeast(0)
            hScrollState.scrollTo(with(density) { (DP_PER_MINUTE * minutes).toPx().toInt() }.coerceIn(0, hScrollState.maxValue))
        }
    }

    // Auto-scroll to NOW on first load / when window shifts
    LaunchedEffect(uiState.windowStartMs) {
        val nowMs = System.currentTimeMillis()
        if (selectedTimeMs !in uiState.windowStartMs until uiState.windowEndMs) {
            selectedTimeMs = if (nowMs in uiState.windowStartMs until uiState.windowEndMs) nowMs else uiState.windowStartMs
            hScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }

    // Keep both columns vertically synchronized using channel list as source-of-truth.
    LaunchedEffect(channelListState, programListState) {
        snapshotFlow { channelListState.firstVisibleItemIndex to channelListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onGuideVerticalScrollChanged(index, offset)
                val needsSync = programListState.firstVisibleItemIndex != index ||
                    programListState.firstVisibleItemScrollOffset != offset
                if (needsSync) {
                    programListState.scrollToItem(index, offset)
                }
            }
    }

    LaunchedEffect(hScrollState) {
        snapshotFlow { hScrollState.value }
            .distinctUntilChanged()
            .collect { scrollPx ->
                viewModel.onGuideHorizontalScrollChanged(scrollPx)
            }
    }

    // Viewport-based EPG loading: notify ViewModel when visible rows change
    LaunchedEffect(programListState) {
        snapshotFlow {
            val first = programListState.firstVisibleItemIndex
            val count = programListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            first to (first + count - 1)
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                viewModel.onVisibleChannelsChanged(first, last)
            }
    }

    // Root Box: intercepts D-pad for the entire guide screen.
    // CRITICAL: ENTER/CENTER must be consumed here as a fallback — otherwise Firestick
    // propagates it to the Activity which may trigger system navigation (Home/Back).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        // At top of channel list → move D-pad focus back to category pills
                        if (uiState.focusedChannelIndex <= 0 && catFocusRequesters.isNotEmpty()) {
                            val idx = focusedCatIdx.coerceIn(0, catFocusRequesters.size - 1)
                            runCatching { catFocusRequesters[idx].requestFocus() }
                        } else {
                            viewModel.zapUp()
                        }
                        true
                    }
                    Key.DirectionDown -> { viewModel.zapDown(); true }
                    // Fallback ENTER: navigate to the currently D-pad-highlighted channel.
                    // Individual program cells handle ENTER first (consuming it) so this
                    // only fires when focus is on the grid container itself.
                    Key.DirectionCenter, Key.Enter -> {
                        openRecordMenuForFocusedChannel()
                        true  // ALWAYS consume — prevents Firestick from firing Home/Back
                    }
                    // D-pad LEFT/RIGHT: scroll the time axis by one 30-minute slot.
                    // Category pills consume LEFT/RIGHT themselves (returning true) so this
                    // only fires when focus is on the program grid area — exactly what we want.
                    Key.DirectionLeft -> {
                        moveProgram(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        moveProgram(1)
                        true
                    }
                    Key.Menu -> {
                        openRecordMenuForFocusedChannel()
                        true
                    }
                    Key.Back -> {
                        navController.popBackStackSafeDebounced()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // ── Top App Bar ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(BgSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { navController.popBackStackSafeDebounced() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Accent)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "TV GUIDE",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontSize = 16.sp
                    )
                    val liveClock = com.dylandos.iptv.ultimate.ui.util.rememberLiveClock()
                    Surface(
                        color = BgSurface2,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                            Text(liveClock, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.shiftBack() }) {
                        Icon(Icons.Default.ChevronLeft, "Back 2h", tint = Accent, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        text = formatWindowLabel(uiState.windowStartMs),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(onClick = { viewModel.shiftForward() }) {
                        Icon(Icons.Default.ChevronRight, "Fwd 2h", tint = Accent, modifier = Modifier.size(28.dp))
                    }
                    // NOW jump button — resets window to current hour AND scrolls horizontally
                    Surface(
                        onClick = {
                            viewModel.jumpToNow()   // resets windowStartMs to current hour
                            scope.launch {
                                val nowMs = System.currentTimeMillis()
                                val minutesFromStart = ((nowMs - uiState.windowStartMs) / 60_000L).coerceAtLeast(0L)
                                val targetPx = with(density) { (DP_PER_MINUTE * minutesFromStart.toInt()).toPx().toInt() }
                                hScrollState.animateScrollTo((targetPx - with(density) { 120.dp.toPx() }.toInt()).coerceAtLeast(0))
                            }
                        },
                        color = Accent,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "NOW",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showDayPicker = true }) { Text("Choose day") }
            Text("Left/right: programs · OK/Menu: options", color = Color.LightGray, fontSize = 12.sp)
        }

        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(48.dp))
                        Text("Loading TV Guide...", color = TextSecondary, fontSize = 16.sp)
                        Text("Fetching channels and program data", color = TextTertiary, fontSize = 13.sp)
                    }
                }
            }

            uiState.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(Icons.Default.Error, "Error", tint = StatusError, modifier = Modifier.size(64.dp))
                        Text("Guide Error", color = TextPrimary, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                        Text(uiState.error ?: "", color = TextSecondary)
                        Button(
                            onClick = { viewModel.load() },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) { Text("Retry") }
                    }
                }
            }

            else -> {
                // ── Category filter pills — Row + horizontalScroll keeps ALL
                // FocusRequesters attached (LazyRow detaches off-screen items).
                val catScrollState = rememberScrollState()

                // Auto-scroll pill strip to keep selected category visible
                LaunchedEffect(uiState.selectedCategoryId) {
                    val selIdx = uiState.categories
                        .indexOfFirst { it.categoryId == uiState.selectedCategoryId }
                    if (selIdx > 0) {
                        val approxPx = with(density) { (128.dp * selIdx).toPx().toInt() }
                        catScrollState.animateScrollTo(approxPx.coerceAtLeast(0))
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CAT_PILL_HEIGHT)
                        .background(BgSurface2)
                        .horizontalScroll(catScrollState)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState.categories.forEachIndexed { idx, cat ->
                        val isSelected = cat.categoryId == uiState.selectedCategoryId
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        val fr = catFocusRequesters.getOrNull(idx)

                        // Hot-pink neon scale pop when D-pad cursor lands on this pill
                        val pillScale by animateFloatAsState(
                            targetValue = if (isFocused) 1.04f else 1f,
                            animationSpec = androidx.compose.animation.core.tween(150),
                            label = "catPillScale$idx"
                        )

                        Surface(
                            onClick = {
                                viewModel.selectCategory(cat.categoryId)
                                focusedCatIdx = idx
                            },
                            color = when {
                                isSelected -> Accent
                                isFocused  -> Accent.copy(alpha = 0.30f)
                                else       -> BgElevated
                            },
                            shape = RoundedCornerShape(20.dp),
                            border = when {
                                isFocused  -> androidx.compose.foundation.BorderStroke(3.dp, AccentBright)
                                isSelected -> androidx.compose.foundation.BorderStroke(2.dp, Accent.copy(alpha = 0.7f))
                                else       -> null
                            },
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = pillScale
                                    scaleY = pillScale
                                }
                                .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
                                .focusable(interactionSource = interactionSource)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    when (keyEvent.key) {
                                        Key.DirectionRight -> {
                                            val next = idx + 1
                                            if (next < uiState.categories.size) {
                                                catFocusRequesters.getOrNull(next)?.requestFocus()
                                                focusedCatIdx = next
                                            }
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            val prev = idx - 1
                                            if (prev >= 0) {
                                                catFocusRequesters.getOrNull(prev)?.requestFocus()
                                                focusedCatIdx = prev
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            // Move to grid — explicit requestFocus is more reliable
                                            // than moveFocus(Down) on Firestick
                                            runCatching { gridFocusRequester.requestFocus() }
                                            true
                                        }
                                        Key.DirectionUp -> true  // consume \u2014 pills ARE the top row
                                        Key.DirectionCenter, Key.Enter -> {
                                            viewModel.selectCategory(cat.categoryId)
                                            focusedCatIdx = idx
                                            true
                                        }
                                        else -> false
                                    }
                                }
                        ) {
                            Text(
                                text = cat.categoryName,
                                color = when {
                                    isSelected -> Color.Black
                                    isFocused  -> AccentBright
                                    else       -> TextSecondary
                                },
                                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // ── Main guide grid ───────────────────────────────────
                // gridFocusRequester is placed here so initial D-pad focus lands on the
                // grid, not on the Back button in the TopAppBar.
                Row(modifier = Modifier
                    .weight(1f)
                    .focusRequester(gridFocusRequester)
                    .focusable()
                ) {

                    // LEFT: Fixed channel column (fillMaxHeight so LazyColumn fills it correctly)
                    Column(modifier = Modifier
                        .width(CHANNEL_COL_WIDTH)
                        .fillMaxHeight()
                    ) {
                        // Corner cell aligned with time header
                        Box(
                            modifier = Modifier
                                .width(CHANNEL_COL_WIDTH)
                                .height(TIME_ROW_HEIGHT)
                                .background(BgSurface)
                                .border(width = 0.5.dp, color = BorderDefault),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("CHANNELS", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        // Synchronized vertical scroll (same listState as program grid)
                        // weight(1f) ensures it takes remaining height after the corner Box
                        LazyColumn(
                            state = channelListState,
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(uiState.filteredChannels, key = { idx, ch -> "${ch.streamId}_$idx" }) { idx, channel ->
                                ChannelInfoCell(
                                    channel = channel,
                                    isFocused = uiState.focusedChannelIndex == idx,
                                    isFavorite = channel.streamId in uiState.favoriteChannelIds,
                                    rowHeight = rowHeight,
                                    showChannelNumber = uiState.showChannelNumbers,
                                    onProgramOptions = {
                                        viewModel.focusChannelByStreamId(channel.streamId)
                                        openRecordMenuForFocusedChannel()
                                    },
                                    onFavorite = { viewModel.toggleFavoriteChannel(channel) },
                                    onRecord = {
                                        val accounts = dvrState.recordingAccounts
                                        if (accounts.size > 1) {
                                            recordingChannelToPick = channel
                                        } else {
                                            dvrViewModel.recordLiveChannel(
                                                channelName = channel.name,
                                                streamId = channel.streamId,
                                                accountId = accounts.firstOrNull()?.id
                                            )
                                        }
                                    },
                                    onPlay = {
                                        viewModel.setFocusedChannel(idx)
                                        viewModel.setActiveChannel(idx)
                                        navController.navigateSafe(
                                            Screen.Player.createRoute("live", channel.streamId.toString(), "ts")
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // RIGHT: Horizontally scrollable time header + program grid
                    Box(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.fillMaxSize()) {

                            // Time ruler header
                            TimeRulerRow(
                                windowStartMs = uiState.windowStartMs,
                                windowMinutes = windowMinutes,
                                hScrollState  = hScrollState
                            )

                            // Program grid — Canvas-based high-performance renderer.
                            // Each row uses a single Canvas draw call instead of creating
                            // N×Box composable nodes (one per program block), eliminating
                            // the composable tree explosion that caused EPG lag and OOM.
                            EpgCanvasGrid(
                                channels            = uiState.filteredChannels,
                                epgData             = uiState.epgData,
                                windowStartMs       = uiState.windowStartMs,
                                windowEndMs         = uiState.windowEndMs,
                                hScrollState        = hScrollState,
                                listState           = programListState,
                                rowHeight           = rowHeight,
                                dpPerMinute         = DP_PER_MINUTE,
                                focusedChannelIndex = uiState.focusedChannelIndex,
                                selectedTimeMs      = selectedTimeMs,
                                modifier            = Modifier.weight(1f),
                                onProgramSelected   = { channelId, channelName, title, startMs, endMs, hasArchive ->
                                    viewModel.focusChannelByStreamId(channelId)
                                    val channelHasArchive = uiState.filteredChannels
                                        .firstOrNull { it.streamId == channelId }
                                        ?.tvArchive
                                        ?.let { it > 0 }
                                        ?: false
                                    programDialog = ProgramDialogData(
                                        title       = title,
                                        channelName = channelName,
                                        channelId   = channelId,
                                        startMs     = startMs,
                                        endMs       = endMs,
                                        canTimeShift = hasArchive || channelHasArchive
                                    )
                                }
                            )
                        }

                        // NOW indicator red line
                        NowIndicator(
                            windowStartMs = uiState.windowStartMs,
                            hScrollState  = hScrollState,
                            topOffset     = TIME_ROW_HEIGHT
                        )
                    }
                }
            }
        }
        } // end Column
    } // end root Box (D-pad handler)
}

@Composable
private fun GuideCommandDeck(
    channel: XtreamChannel?,
    programs: List<XtreamEpgProgram>,
    channelIndex: Int,
    channelCount: Int,
    windowLabel: String,
    guideItemCount: Int,
    matchLabel: String? = null
) {
    val now = System.currentTimeMillis()
    val nowSec = now / 1000L
    val current = programs.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
        ?: programs.firstOrNull()
    val next = current?.let { active -> programs.firstOrNull { it.startTimestamp >= active.stopTimestamp } }
        ?: programs.drop(1).firstOrNull()
    val progress = if (current != null) {
        val start = current.startTimestamp * 1000L
        val end = current.stopTimestamp * 1000L
        if (end > start) ((now - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f) else 0f
    } else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        color = BgSurface2.copy(alpha = 0.88f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.26f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Accent.copy(alpha = 0.12f),
                            AccentBlue.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSurface3),
                contentAlignment = Alignment.Center
            ) {
                if (!channel?.streamIcon.isNullOrBlank()) {
                    AsyncImage(
                        model = channel?.streamIcon,
                        contentDescription = channel?.name,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(Icons.Default.LiveTv, null, tint = Accent, modifier = Modifier.size(26.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = channel?.name ?: "Guide Command Center",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    GuideDeckChip("ROW ${if (channelCount > 0) channelIndex + 1 else 0}/$channelCount", Accent)
                }
                Text(
                    text = current?.title?.ifBlank { "No guide title" } ?: "Move through the guide to inspect programs",
                    color = TextAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.10f)
                )
                Text(
                    text = "Next: ${next?.title?.ifBlank { "TBA" } ?: "TBA"}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "MENU stars this channel for the ★ Favorites guide tab",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GuideDeckChip(windowLabel, AccentBlue)
                GuideDeckChip("%,d guide items".format(guideItemCount), StatusSuccess)
                if (!matchLabel.isNullOrBlank()) {
                    GuideDeckChip(matchLabel, AccentSecondary)
                }
            }
        }
    }
}

@Composable
private fun GuideDeckChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f))
    ) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

// ── Channel Info Cell (left fixed column) ─────────────────────────────────────

@Composable
private fun ChannelInfoCell(
    channel: XtreamChannel,
    isFocused: Boolean,
    isFavorite: Boolean,
    rowHeight: Dp,
    showChannelNumber: Boolean = true,
    onProgramOptions: () -> Unit,
    onFavorite: () -> Unit,
    onRecord: () -> Unit,
    onPlay: () -> Unit
) {
    val dominantColor = rememberDominantColor(channel.streamIcon)
    val interactionSource = remember { MutableInteractionSource() }
    val showHighlight = isFocused

    Box(
        modifier = Modifier
            .width(CHANNEL_COL_WIDTH)
            .height(rowHeight)
            .background(
                when {
                    showHighlight -> dominantColor.copy(alpha = 0.55f)
                    else          -> dominantColor.copy(alpha = 0.22f)
                }
            )
            .then(
                if (showHighlight)
                    // Always use Accent (hot pink neon) \u2014 dominantColor is often too dark to see
                    Modifier.border(width = 3.dp, color = Accent)
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .clickable { onPlay() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { onProgramOptions(); true }
                    Key.Menu -> { onProgramOptions(); true }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Channel logo
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface2),
                contentAlignment = Alignment.Center
            ) {
                if (!channel.streamIcon.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.streamIcon,
                        contentDescription = channel.name,
                        modifier = Modifier.size(40.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        color = Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Channel name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    color = if (isFocused) TextPrimary else TextSecondary,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }

            // Record button
            IconButton(
                onClick = onFavorite,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) StatusWarning else TextTertiary.copy(alpha = 0.8f),
                    modifier = Modifier.size(15.dp)
                )
            }

            IconButton(
                onClick = onRecord,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = "Record",
                    tint = StatusError.copy(alpha = if (isFocused) 1f else 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Time Ruler Row ────────────────────────────────────────────────────────────

@Composable
private fun TimeRulerRow(
    windowStartMs: Long,
    windowMinutes: Int,
    hScrollState: androidx.compose.foundation.ScrollState
) {
    val now = System.currentTimeMillis()
    val slotCount = windowMinutes / 30
    val slotWidthDp = DP_PER_MINUTE * 30

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TIME_ROW_HEIGHT)
            .background(BgSurface)
            .horizontalScroll(hScrollState, enabled = false)
    ) {
        repeat(slotCount) { slot ->
            val slotStartMs = windowStartMs + slot * 30 * 60_000L
            val slotEndMs   = slotStartMs + 30 * 60_000L
            val isCurrentSlot = now in slotStartMs until slotEndMs

            Box(
                modifier = Modifier
                    .width(slotWidthDp)
                    .fillMaxHeight()
                    .background(
                        if (isCurrentSlot) Accent.copy(alpha = 0.1f) else Color.Transparent
                    )
                    .border(width = 0.5.dp, color = BorderSubtle),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(if (isCurrentSlot) Accent else BorderDefault)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatShortTime(slotStartMs),
                        color = if (isCurrentSlot) AccentBright else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isCurrentSlot) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.width(80.dp))
    }
}

// ── Program Row ───────────────────────────────────────────────────────────────

@Composable
private fun ProgramRow(
    channel: XtreamChannel,
    programs: List<XtreamEpgProgram>,
    windowStartMs: Long,
    windowEndMs: Long,
    totalGridWidthDp: Dp,
    hScrollState: androidx.compose.foundation.ScrollState,
    rowHeight: Dp,
    isFocusedRow: Boolean,
    onFocusRow: () -> Unit,
    onZapUp: () -> Unit,
    onZapDown: () -> Unit,
    onProgramSelected: (title: String, startMs: Long, endMs: Long) -> Unit
) {
    val now = System.currentTimeMillis()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (isFocusedRow) BgSurface2.copy(alpha = 0.5f) else Color.Transparent)
            .horizontalScroll(hScrollState)
    ) {
        if (programs.isEmpty()) {
            Box(
                modifier = Modifier
                    .width(totalGridWidthDp + 80.dp)
                    .fillMaxHeight()
                    .background(BgSurface3)
                    .border(0.5.dp, BorderSubtle)
                    .clickable { onFocusRow(); onProgramSelected(channel.name, System.currentTimeMillis(), System.currentTimeMillis() + 3600_000L) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.LiveTv, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                    Text(channel.name, color = TextTertiary, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val visiblePrograms = programs.filter { prog ->
                prog.stopTimestamp * 1000L > windowStartMs &&
                prog.startTimestamp * 1000L < windowEndMs
            }

            // Gap filler before first program
            if (visiblePrograms.isNotEmpty()) {
                val firstStart = visiblePrograms.first().startTimestamp * 1000L
                if (firstStart > windowStartMs) {
                    val gapMins = ((firstStart - windowStartMs) / 60_000L).toInt()
                    if (gapMins > 0) {
                        Box(
                            modifier = Modifier
                                .width(DP_PER_MINUTE * gapMins)
                                .fillMaxHeight()
                                .background(BgSurface3)
                                .border(0.5.dp, BorderSubtle)
                        )
                    }
                }
            }

            visiblePrograms.forEach { prog ->
                val progStartMs  = prog.startTimestamp * 1000L
                val progEndMs    = prog.stopTimestamp  * 1000L
                val clampedStart = maxOf(progStartMs, windowStartMs)
                val clampedEnd   = minOf(progEndMs,   windowEndMs)
                val durationMins = ((clampedEnd - clampedStart) / 60_000L).toInt().coerceAtLeast(1)
                val cellWidthDp  = DP_PER_MINUTE * durationMins

                val isLive = now in progStartMs until progEndMs
                val isPast = progEndMs <= now
                val title  = prog.title.ifBlank { "No Info" }
                val time   = formatProgramTime(progStartMs, progEndMs)
                val progressFraction = if (isLive) {
                    ((now - progStartMs).toFloat() / (progEndMs - progStartMs).toFloat()).coerceIn(0f, 1f)
                } else 0f

                val interactionSource = remember { MutableInteractionSource() }
                val isCellFocused by interactionSource.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .width(cellWidthDp)
                        .fillMaxHeight()
                        .background(
                            when {
                                isLive && isCellFocused -> Accent.copy(alpha = 0.28f)
                                isLive  -> Accent.copy(alpha = 0.14f)
                                isPast  -> BgSurface3.copy(alpha = 0.6f)
                                isCellFocused -> BgElevated
                                else    -> BgSurface3
                            }
                        )
                        .then(
                            if (isCellFocused || isLive)
                                Modifier.border(
                                    width = if (isCellFocused) 2.dp else 1.dp,
                                    color = if (isCellFocused) AccentBright else Accent.copy(alpha = 0.5f)
                                )
                            else Modifier.border(0.5.dp, BorderSubtle)
                        )
                        .clickable { onFocusRow(); onProgramSelected(title, progStartMs, progEndMs) }
                        .focusable(interactionSource = interactionSource)
                        // D-pad ENTER on a program cell opens the program options dialog.
                        // This MUST return true to prevent the event bubbling to the root Box
                        // fallback (which would navigate directly to the channel instead of
                        // showing the Watch Now / Schedule Recording dialog).
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    onFocusRow()
                                    onProgramSelected(title, progStartMs, progEndMs)
                                    true
                                }
                                else -> false
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = title,
                            color = when {
                                isCellFocused -> TextPrimary
                                isPast  -> TextTertiary
                                isLive  -> TextPrimary
                                else    -> TextSecondary
                            },
                            fontWeight = if (isLive || isCellFocused) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                            maxLines = if (durationMins >= 30) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = time,
                                color = if (isLive) Accent else TextTertiary,
                                fontSize = 10.sp
                            )
                            if (isLive) {
                                Surface(color = Accent, shape = RoundedCornerShape(3.dp)) {
                                    Text(
                                        "LIVE",
                                        color = Color.Black,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 8.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                    // Live progress bar
                    if (isLive && progressFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(progressFraction)
                                .height(3.dp)
                                .background(Brush.horizontalGradient(listOf(Accent, AccentBright)))
                        )
                    }
                }
            }
            Spacer(Modifier.width(80.dp))
        }
    }
}

// ── NOW Indicator (red vertical line) ────────────────────────────────────────

@Composable
private fun NowIndicator(
    windowStartMs: Long,
    hScrollState: androidx.compose.foundation.ScrollState,
    topOffset: Dp
) {
    val now = System.currentTimeMillis()
    if (now < windowStartMs) return

    val density = LocalDensity.current
    val minutesFromStart = ((now - windowStartMs) / 60_000L).toInt()
    val nowOffsetPx = with(density) { (DP_PER_MINUTE * minutesFromStart).toPx() }
    val screenOffsetPx = nowOffsetPx - hScrollState.value
    if (screenOffsetPx < 0) return

    val screenOffsetDp = with(density) { screenOffsetPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .zIndex(10f)
            .padding(top = topOffset)
    ) {
        Box(
            modifier = Modifier
                .offset(x = screenOffsetDp)
                .width(2.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(StatusError, StatusError.copy(alpha = 0.3f))))
        )
        Box(
            modifier = Modifier
                .offset(x = screenOffsetDp - 4.dp, y = (-2).dp)
                .size(10.dp)
                .background(StatusError, CircleShape)
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatWindowLabel(windowStartMs: Long): String =
    com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatWindowLabel(windowStartMs)

private fun formatProgramTime(startMs: Long, endMs: Long): String =
    com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatTimeRange(startMs, endMs)
