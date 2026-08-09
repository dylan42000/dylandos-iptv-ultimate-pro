package com.dylandos.iptv.ultimate.ui.screens.livetv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DYLANDOS IPTV ULTIMATE — Live TV Screen
 *
 * Layout:
 *   ┌─────────────┬────────────────────────────────────────────────┐
 *   │  Categories │  Channel List (with EPG now-playing info)       │
 *   │    ~12%     │                    ~88%                         │
 *   └─────────────┴────────────────────────────────────────────────┘
 *
 * D-pad behaviour:
 *   UP / DOWN  → zap channels (focused channel scrolls into view)
 *   LEFT       → move focus to category rail
 *   RIGHT      → move focus back to channel list
 *   OK/CENTER  → play selected channel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen(
    navController: NavController,
    viewModel: LiveTvViewModel = hiltViewModel(),
    settingsViewModel: com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel = hiltViewModel(),
    listsViewModel: com.dylandos.iptv.ultimate.ui.screens.customlists.CustomListsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val listsState by listsViewModel.state.collectAsState()
    var unlockedCategories by remember { mutableStateOf(setOf<String>()) }
    var pinPromptCategory by remember { mutableStateOf<String?>(null) }
    var pinDigits by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var addToListChannel by remember { mutableStateOf<XtreamChannel?>(null) }

    fun requestCategory(categoryId: String) {
        val locked = settingsState.parentalEnabled &&
            settingsState.parentalPin.isNotEmpty() &&
            categoryId in settingsState.lockedLiveCategories &&
            categoryId !in unlockedCategories
        if (locked) {
            pinPromptCategory = categoryId
            pinDigits = ""
            pinError = null
        } else {
            viewModel.selectCategory(categoryId)
        }
    }
    val channelListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.channelListFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.channelListFirstVisibleOffset
    )
    val categoryListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.categoryListFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.categoryListFirstVisibleOffset
    )
    val categoryRailFocusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current


    // Auto-scroll channel list to keep focused channel visible during channel jumps
    LaunchedEffect(uiState.focusedChannelIndex, uiState.filteredChannels.size) {
        if (uiState.filteredChannels.isNotEmpty()) {
            val bounded = uiState.focusedChannelIndex.coerceIn(0, uiState.filteredChannels.lastIndex)
            val isVisible = channelListState.layoutInfo.visibleItemsInfo.any { it.index == bounded }
            if (!isVisible) {
                channelListState.animateScrollToItem(bounded)
            }
        }
    }

    // Use cache-first load to avoid forced rebuffer/network storms when revisiting Live TV.
    LaunchedEffect(Unit) { viewModel.loadChannels(forceRefresh = false) }

    // Restore focus to the currently playing channel when returning from Player.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.syncFocusedChannelFromPlayback()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(channelListState) {
        snapshotFlow { channelListState.firstVisibleItemIndex to channelListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onChannelListScrollChanged(index, offset)
            }
    }

    LaunchedEffect(categoryListState) {
        snapshotFlow { categoryListState.firstVisibleItemIndex to categoryListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onCategoryListScrollChanged(index, offset)
            }
    }

    LaunchedEffect(uiState.selectedCategoryId, uiState.categories.size) {
        val selectedIndex = uiState.categories.indexOfFirst { it.categoryId == uiState.selectedCategoryId }
        if (selectedIndex >= 0) {
            categoryListState.animateScrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
            .onKeyEvent { event ->
                // Capture numeric keys anywhere in the screen for channel number jump
                if (event.type == KeyEventType.KeyDown) {
                    event.key.toDigitOrNull()?.let { digit ->
                        viewModel.onNumberKeyPressed(digit)
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ───────────────────────────────────────────────
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live TV", fontWeight = FontWeight.Bold)
                        if (uiState.isRefreshing) {
                            Surface(
                                color = Accent.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        strokeWidth = 1.5.dp,
                                        color = Accent
                                    )
                                    Text("Refreshing\u2026", color = Accent, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = TextPrimary
                )
            )

            LiveCommandStrip(
                channelCount = uiState.filteredChannels.size,
                categoryName = uiState.categories
                    .firstOrNull { it.categoryId == uiState.selectedCategoryId }
                    ?.categoryName
                    ?: "All Channels",
                hasActiveChannel = uiState.activeStreamId != null
            )

            LiveFocusDeck(
                channel = uiState.filteredChannels.getOrNull(uiState.focusedChannelIndex),
                programs = uiState.filteredChannels.getOrNull(uiState.focusedChannelIndex)
                    ?.let { uiState.epgData[it.streamId] }
                    ?: emptyList(),
                categoryName = uiState.categories
                    .firstOrNull { it.categoryId == uiState.selectedCategoryId }
                    ?.categoryName
                    ?: "All Channels",
                channelIndex = uiState.focusedChannelIndex,
                channelCount = uiState.filteredChannels.size,
                isFavorite = uiState.filteredChannels
                    .getOrNull(uiState.focusedChannelIndex)
                    ?.streamId
                    ?.let { it in uiState.favoriteChannelIds } == true,
                isActive = uiState.filteredChannels
                    .getOrNull(uiState.focusedChannelIndex)
                    ?.streamId == uiState.activeStreamId
            )

            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Accent)
                            Text("Loading channels...", color = TextSecondary)
                        }
                    }
                }

                uiState.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Error: ${uiState.error}", color = StatusError)
                            Button(onClick = { viewModel.loadChannels() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) { Text("Retry") }
                        }
                    }
                }

                else -> {
                    // ── Split layout: Category Rail + Channel List ────────
                    Row(modifier = Modifier.fillMaxSize()) {

                        // ── Category Rail (12%) ───────────────────────────
                        CategoryRail(
                            categories = uiState.categories,
                            selectedCategoryId = uiState.selectedCategoryId,
                            onCategorySelected = { requestCategory(it) },
                            onCategoryMenu = { viewModel.hideLiveCategory(it) },
                            onRightToChannels = { runCatching { channelListFocusRequester.requestFocus() } },
                            listState = categoryListState,
                            focusRequester = categoryRailFocusRequester,
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(0.12f)
                        )

                        // Divider
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(BorderDefault)
                        )

                        // ── Channel List (88%) ────────────────────────────
                        ChannelList(
                            channels = uiState.filteredChannels,
                            epgData = uiState.epgData,
                            activeStreamId = uiState.activeStreamId,
                            favoriteIds = uiState.favoriteChannelIds,
                            focusedIndex = uiState.focusedChannelIndex,
                            listState = channelListState,
                            onChannelFocused = { viewModel.setFocusedChannel(it) },
                            onChannelSelected = { channel ->
                                val idx = uiState.filteredChannels.indexOf(channel)
                                viewModel.setFocusedChannel(idx)
                                viewModel.setActiveChannel(idx)
                                navController.navigateSafe(
                                    Screen.Player.createRoute("live", channel.streamId.toString(), "ts")
                                )
                            },
                            onChannelMenu = { channel -> addToListChannel = channel },
                            onZapUp = { viewModel.zapUp() },
                            onZapDown = { viewModel.zapDown() },
                            onLeftEdge = { runCatching { categoryRailFocusRequester.requestFocus() } },
                            focusRequester = channelListFocusRequester,
                            onVisibleChannelsChanged = { first, last ->
                                viewModel.onVisibleChannelsChanged(first, last)
                            },
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(0.88f)
                        )
                    }
                }
            }
        }

        // ── Channel Number Jump Overlay ───────────────────────────────────────
        // Appears when user types digits on the remote. Dismisses after 1.5s.
        AnimatedVisibility(
            visible = uiState.showChannelJumpOverlay,
            enter = fadeIn(),
            exit  = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color  = BgSurface.copy(alpha = 0.94f),
                shape  = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, Accent),
                tonalElevation = 8.dp
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text       = uiState.channelNumberInput.ifEmpty { "—" },
                        fontSize   = 64.sp,
                        fontWeight = FontWeight.Black,
                        color      = Accent
                    )
                    Text(
                        text          = "CHANNEL",
                        fontSize      = 12.sp,
                        color         = TextSecondary,
                        letterSpacing = 4.sp
                    )
                }
            }
        }

        pinPromptCategory?.let { catId ->
            AlertDialog(
                onDismissRequest = { pinPromptCategory = null; pinDigits = ""; pinError = null },
                title = { Text("Parental PIN") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter PIN to open this locked category.", color = TextSecondary)
                        OutlinedTextField(
                            value = pinDigits,
                            onValueChange = {
                                if (it.length <= 4 && it.all(Char::isDigit)) {
                                    pinDigits = it
                                    pinError = null
                                }
                            },
                            label = { Text("4-digit PIN") },
                            singleLine = true
                        )
                        pinError?.let { Text(it, color = StatusError, fontSize = 12.sp) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (settingsViewModel.verifyParentalPin(pinDigits)) {
                            unlockedCategories = unlockedCategories + catId
                            viewModel.selectCategory(catId)
                            pinPromptCategory = null
                            pinDigits = ""
                            pinError = null
                        } else {
                            pinError = "Incorrect PIN"
                        }
                    }) { Text("Unlock") }
                },
                dismissButton = {
                    TextButton(onClick = { pinPromptCategory = null; pinDigits = ""; pinError = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        addToListChannel?.let { channel ->
            val addableLists = listsState.lists.filter { it.iconToken != "folder" }
            AlertDialog(
                onDismissRequest = { addToListChannel = null },
                title = { Text("Add ${channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            viewModel.toggleFavoriteChannel(channel)
                            addToListChannel = null
                        }) { Text("Toggle Favorite", color = Accent) }
                        if (addableLists.isEmpty()) {
                            Text("Create a list in My Lists first.", color = TextSecondary, fontSize = 12.sp)
                        } else {
                            addableLists.forEach { list ->
                                TextButton(onClick = {
                                    listsViewModel.addLiveChannel(
                                        listId = list.id,
                                        streamId = channel.streamId,
                                        title = channel.name,
                                        artwork = channel.streamIcon
                                    )
                                    addToListChannel = null
                                }) { Text("Add to ${list.name}", color = AccentSecondary) }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { addToListChannel = null }) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun LiveFocusDeck(
    channel: XtreamChannel?,
    programs: List<XtreamEpgProgram>,
    categoryName: String,
    channelIndex: Int,
    channelCount: Int,
    isFavorite: Boolean,
    isActive: Boolean
) {
    val nowSec by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            value = nowMs / 1000L
            delay(60_000L - (nowMs % 60_000L))
        }
    }
    val current = programs.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
    val next = current?.let { now -> programs.firstOrNull { it.startTimestamp >= now.stopTimestamp } }
        ?: programs.firstOrNull { it.startTimestamp > nowSec }
    val duration = ((current?.stopTimestamp ?: 0L) - (current?.startTimestamp ?: 0L)).toFloat()
    val progress = if (current != null && duration > 0f) {
        ((nowSec - current.startTimestamp) / duration).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = BgSurface2.copy(alpha = 0.86f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.30f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Accent.copy(alpha = 0.16f),
                            AccentBlue.copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
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
                    Text(
                        text = channel?.name?.take(2)?.uppercase() ?: "TV",
                        color = Accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = channel?.name ?: "Choose a channel",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    LiveSignalPill(if (isActive) "ON SCREEN" else "READY", if (isActive) StatusSuccess else Accent)
                    if (isFavorite) LiveSignalPill("FAVORITE", StatusWarning)
                }
                Text(
                    text = current?.title?.ifBlank { "Guide info loading" } ?: "Guide info loading",
                    color = TextAccent,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.10f)
                )
                Text(
                    text = "Next: ${next?.title?.ifBlank { "TBA" } ?: "TBA"}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "MENU adds/removes this channel from the ★ Favorites tab",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveSignalPill(categoryName.take(28), AccentBlue)
                Text(
                    text = if (channelCount > 0) "${channelIndex + 1} / $channelCount" else "0 / 0",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LiveSignalPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.38f))
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LiveCommandStrip(
    channelCount: Int,
    categoryName: String,
    hasActiveChannel: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(BgSurface2, BgBabyBlue.copy(alpha = 0.74f), BgSurface)
                )
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LiveCommandChip(
            icon = Icons.Default.Tv,
            label = "%,d channels".format(channelCount),
            color = Accent
        )
        LiveCommandChip(
            icon = Icons.Default.GridView,
            label = categoryName,
            color = AccentBlue
        )
        LiveCommandChip(
            icon = Icons.Default.FiberDvr,
            label = "REC from player",
            color = StatusError
        )
        LiveCommandChip(
            icon = Icons.Default.Replay10,
            label = "Timeshift ready",
            color = StatusInfo
        )
        LiveCommandChip(
            icon = Icons.Default.Keyboard,
            label = "OK watch · MENU favorite · digits jump",
            color = StatusSuccess,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = if (hasActiveChannel) StatusSuccess.copy(alpha = 0.18f) else TextTertiary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.dp,
                if (hasActiveChannel) StatusSuccess.copy(alpha = 0.4f) else BorderSubtle
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (hasActiveChannel) StatusSuccess else TextTertiary)
                )
                Text(
                    text = if (hasActiveChannel) "Playing" else "No channel active",
                    color = if (hasActiveChannel) StatusSuccess else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun LiveCommandChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(36.dp),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Numeric key → digit helper ────────────────────────────────────────────────

private fun Key.toDigitOrNull(): Char? = when (this) {
    Key.Zero,  Key.NumPad0 -> '0'
    Key.One,   Key.NumPad1 -> '1'
    Key.Two,   Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four,  Key.NumPad4 -> '4'
    Key.Five,  Key.NumPad5 -> '5'
    Key.Six,   Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine,  Key.NumPad9 -> '9'
    else -> null
}

// ── Category Rail ─────────────────────────────────────────────────────────────

@Composable
private fun CategoryRail(
    categories: List<XtreamCategory>,
    selectedCategoryId: String,
    onCategorySelected: (String) -> Unit,
    onCategoryMenu: (String) -> Unit,
    onRightToChannels: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    LazyColumn(
        state = listState,
        modifier = modifier.background(BgSurface),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        val selectedIndex = categories.indexOfFirst { it.categoryId == selectedCategoryId }.coerceAtLeast(0)
        itemsIndexed(
            items = categories,
            key = { _, category -> category.categoryId }
        ) { idx, category ->
            val isSelected = category.categoryId == selectedCategoryId
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (idx == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onCategorySelected(category.categoryId) }
                    .onFocusChanged { }
                    .background(
                        when {
                            isFocused  -> FocusBgStrong
                            isSelected -> Accent.copy(alpha = 0.22f)
                            else       -> Color.Transparent
                        },
                        RoundedCornerShape(4.dp)
                    )
                    .then(
                        if (isFocused)
                            Modifier.border(3.dp, Accent, RoundedCornerShape(4.dp))
                        else if (isSelected)
                            Modifier.border(1.5.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        else Modifier
                    )
                    .onKeyEvent { event ->
                        when {
                            event.isRemoteConfirmKey() -> {
                                onCategorySelected(category.categoryId)
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                                onRightToChannels()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Menu -> {
                                onCategoryMenu(category.categoryId)
                                true
                            }
                            // MEDIUM-011: Do NOT manually handle DirectionUp / DirectionDown here.
                            // Compose's built-in focus traversal handles vertical navigation
                            // correctly. Manually calling moveFocus() duplicates the event on
                            // some Fire TV remotes, causing double-skip between categories.
                            else -> false
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = category.categoryName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) Accent else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ── Channel List ──────────────────────────────────────────────────────────────

@Composable
private fun ChannelList(
    channels: List<XtreamChannel>,
    epgData: Map<Int, List<XtreamEpgProgram>>,
    activeStreamId: Int?,
    favoriteIds: Set<Int>,
    focusedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onChannelFocused: (Int) -> Unit,
    onChannelSelected: (XtreamChannel) -> Unit,
    onChannelMenu: (XtreamChannel) -> Unit,
    onZapUp: () -> Unit,
    onZapDown: () -> Unit,
    onLeftEdge: () -> Unit,
    focusRequester: FocusRequester,
    onVisibleChannelsChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val nowSec by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            value = nowMs / 1000L
            delay(60_000L - (nowMs % 60_000L))
        }
    }
    // Trigger EPG load for the visible viewport on every scroll
    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
            first to last
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                onVisibleChannelsChanged(first, last)
            }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = channels,
            key = { _, channel -> channel.streamId }
        ) { index, channel ->
            val epgPrograms = epgData[channel.streamId] ?: emptyList()
            val nowPlaying = epgPrograms.firstOrNull { it.startTimestamp <= nowSec && it.stopTimestamp > nowSec }
                ?: epgPrograms.firstOrNull()
            val isFocused = index == focusedIndex

            val upcoming = if (nowPlaying != null) {
                epgPrograms.filter { it.startTimestamp >= nowPlaying.stopTimestamp }.take(3)
            } else {
                epgPrograms.drop(1).take(3)
            }

            ChannelRow(
                channel = channel,
                nowPlaying = nowPlaying,
                upcomingPrograms = upcoming,
                nowSec = nowSec,
                isFocused = isFocused,
                isActive = channel.streamId == activeStreamId,
                isFavorite = channel.streamId in favoriteIds,
                onFocused = { onChannelFocused(index) },
                onSelected = { onChannelSelected(channel) },
                onMenu = { onChannelMenu(channel) },
                onLeftEdge = onLeftEdge,
                focusRequester = if (index == focusedIndex) focusRequester else null
            )
        }
    }
}

// ── Channel Row ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: XtreamChannel,
    nowPlaying: XtreamEpgProgram?,
    upcomingPrograms: List<XtreamEpgProgram> = emptyList(),
    nowSec: Long = System.currentTimeMillis() / 1000L,
    isFocused: Boolean,
    isActive: Boolean,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onSelected: () -> Unit,
    onMenu: () -> Unit,
    onLeftEdge: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val actualFocused by interactionSource.collectIsFocusedAsState()
    val showFocus = isFocused || actualFocused
    val rowHeight by animateDpAsState(
        targetValue = when {
            showFocus && upcomingPrograms.isNotEmpty() -> 112.dp
            nowPlaying != null -> 76.dp
            else -> 60.dp
        },
        animationSpec = tween(140),
        label = "channelRowHeight"
    )
    val rowColor by animateColorAsState(
        targetValue = when {
            showFocus -> FocusBgStrong
            isActive -> StatusSuccess.copy(alpha = 0.12f)
            else -> BgSurface2
        },
        animationSpec = tween(120),
        label = "channelRowColor"
    )

    // tween(150) border alpha — replaces the former rememberInfiniteTransition which ran
    // continuously on every visible row (not just the focused one), causing ~14 unnecessary
    // recompositions/frame during D-pad fast-scroll through the channel list.
    val glowAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showFocus) 1.0f else 0f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "channelBorderAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .graphicsLayer {
                scaleX = if (showFocus) 1.012f else 1f
                scaleY = if (showFocus) 1.012f else 1f
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
                onLongClick = onMenu
            )
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                when {
                    event.isRemoteConfirmKey() -> {
                        onSelected()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Menu -> {
                        onMenu()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                        onLeftEdge()
                        true
                    }
                    else -> false
                }
            }
            .then(
                when {
                    showFocus -> Modifier.border(
                        4.dp,
                        Accent.copy(alpha = glowAlpha),
                        RoundedCornerShape(8.dp)
                    )
                    isActive -> Modifier.border(
                        3.dp,
                        StatusSuccess.copy(alpha = 0.95f),
                        RoundedCornerShape(8.dp)
                    )
                    else -> Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = rowColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            if (showFocus) Accent.copy(alpha = 0.18f) else Color.Transparent,
                            if (isActive) StatusSuccess.copy(alpha = 0.12f) else AccentBlue.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel Number badge
            Surface(
                color = AccentSurface,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.width(36.dp)
            ) {
                Text(
                    text = channel.num.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    maxLines = 1
                )
                if (isFavorite) {
                    Text(
                        text = "★",
                        color = StatusWarning,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Channel logo — S-030: scale 32→40dp on focus for visual feedback
            val logoScale by animateFloatAsState(
                targetValue = if (showFocus) 1.18f else 1f,
                animationSpec = tween(120),
                label = "logoScale"
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                    }
                    .clip(RoundedCornerShape(4.dp))
                    .background(BgSurface3)
            ) {
                if (!channel.streamIcon.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.streamIcon,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Accent,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Channel name + now-playing EPG
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nowPlaying != null) {
                    Text(
                        text = "▶ ${nowPlaying.title.ifBlank { "Now Playing" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // S-019: EPG progress bar showing how far through the current program we are
                    val dur = (nowPlaying.stopTimestamp - nowPlaying.startTimestamp).toFloat()
                    if (dur > 0f) {
                        val progress = ((nowSec - nowPlaying.startTimestamp) / dur).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 3.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = Accent,
                            trackColor = TextTertiary.copy(alpha = 0.3f)
                        )
                    }
                    if (showFocus) {
                        upcomingPrograms.forEachIndexed { index, program ->
                            Text(
                                text = "${formatEpgTime(program.startTimestamp)}  ${program.title.ifBlank { "TBA" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (index == 0) AccentSecondary else TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }

            // Live badge
            Surface(
                color = if (isActive) StatusSuccess.copy(alpha = 0.24f)
                else StatusError.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (isActive) "ON NOW" else "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) StatusSuccess else StatusError,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun formatEpgTime(epochSeconds: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).apply {
        timeZone = java.util.TimeZone.getDefault()
    }.format(Date(epochSeconds * 1000L))
