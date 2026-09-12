package com.dylandos.iptv.ultimate.ui.screens.series

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.ColorPainter
import com.dylandos.iptv.ultimate.data.model.Episode
import com.dylandos.iptv.ultimate.data.model.Season
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.data.model.XtreamSeriesInfo
import com.dylandos.iptv.ultimate.data.db.entity.WatchHistoryEntity
import com.dylandos.iptv.ultimate.data.util.displaySeasons
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos

private const val SERIES_COLS = 5

/**
 * DYLANDOS IPTV ULTIMATE — Series Screen (Netflix style)
 *
 * Layout mirrors Movies: 12% category rail + 88% poster grid.
 * OK on a poster opens a large centered dialog: vertical **seasons** rail (left)
 * and **episodes** list (right) — tuned for Fire TV D-pad (Up/Down in each pane,
 * Right from a season to episodes, Left from an episode back to seasons, Back to close).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    navController: NavController,
    viewModel: SeriesViewModel = hiltViewModel(),
    settingsViewModel: com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val navigateToPlayer by viewModel.navigateToPlayer.collectAsState()
    val navigateToDetail by viewModel.navigateToDetail.collectAsState()
    // Paginated series list — only 20 items decoded into memory at a time (OOM fix).
    val lazySeries = viewModel.pagingData.collectAsLazyPagingItems()
    // Collected from a DEDICATED StateFlow — separate from uiState so that adding/
    // removing a favorite does NOT trigger a full-grid recomposition.
    val favoriteIds by viewModel.favoriteSeriesIds.collectAsState()
    var unlockedCategories by remember { mutableStateOf(setOf<String>()) }
    var pinPromptCategory by remember { mutableStateOf<String?>(null) }
    var pinDigits by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun requestCategory(categoryId: String) {
        val locked = settingsViewModel.isSeriesCategoryLocked(categoryId) &&
            categoryId !in unlockedCategories
        if (locked) {
            pinPromptCategory = categoryId
            pinDigits = ""
            pinError = null
        } else {
            viewModel.selectCategory(categoryId)
        }
    }
    val categoryFR  = remember { FocusRequester() }
    val gridEntryFR = remember { FocusRequester() }
    val firstPosterBringIntoView = remember { BringIntoViewRequester() }
    val categoryListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.categoryRailFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.categoryRailFirstVisibleOffset
    )
    val seriesGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = uiState.gridFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = uiState.gridFirstVisibleItemOffset
    )

    // ONE-SHOT focus flag: category rail auto-focus runs only once per screen entry.
    // Without this guard, every cold recomposition (rotation, process kill) re-fires
    // the focus request, snapping focus to the category rail even when the user was
    // navigating the poster grid. Mirrors the same fix already applied in MoviesScreen.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    var pendingGridFocusMove by remember { mutableStateOf(false) }
    var moveFocusAfterCategoryLoad by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
        // Search → Series detail: consume pending open on first composition as well as resume.
        viewModel.consumePendingOpenIfAny()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var shouldRestoreFocusOnResume by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.consumePendingOpenIfAny()
                shouldRestoreFocusOnResume = initialFocusDone
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.selectedSeries?.seriesId) {
        uiState.selectedSeries?.seriesId?.let { viewModel.loadSelectedSeriesDetails(it) }
    }

    // Focus restoration on initial load AND on resume from details/player
    LaunchedEffect(
        uiState.categories.isNotEmpty(),
        uiState.isLoading,
        uiState.itemCount,
        lazySeries.itemCount,
        shouldRestoreFocusOnResume
    ) {
        val needsInitial = uiState.categories.isNotEmpty() && !uiState.isLoading && !initialFocusDone
        val needsResume = shouldRestoreFocusOnResume && uiState.itemCount > 0 && lazySeries.itemCount > 0

        if (!needsInitial && !needsResume) return@LaunchedEffect

        if (needsInitial) initialFocusDone = true
        if (needsResume) shouldRestoreFocusOnResume = false

        val maxIdx = (uiState.itemCount - 1).coerceAtLeast(0)
        val restoredIndex = (uiState.focusedRow * SERIES_COLS + uiState.focusedCol).coerceIn(0, maxIdx)
        val shouldRestoreGrid = needsResume

        if (shouldRestoreGrid) {
            runCatching { seriesGridState.scrollToItem(restoredIndex) }
            delay(48)
            runCatching { firstPosterBringIntoView.bringIntoView() }
            val deadline = System.nanoTime() + 2_500_000_000L
            while (System.nanoTime() < deadline) {
                withFrameNanos { }
                runCatching { firstPosterBringIntoView.bringIntoView() }
                val focused = try {
                    when (val result: Any = gridEntryFR.requestFocus()) {
                        is Boolean -> result
                        else -> true
                    }
                } catch (_: Throwable) {
                    false
                }
                if (focused) break
                delay(48)
            }
        } else {
            withFrameNanos { }
            runCatching { categoryFR.requestFocus() }
        }
    }

    // CRITICAL-003 FIX: When the series detail popup closes, scroll grid to the focused
    // item and restore focus to it. Without this scroll+focus pair, requestFocus() on a
    // card that is outside the viewport silently fails → entire D-pad stops working.
    var prevShowPopup by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.showPopup) {
        val wasDismissed = prevShowPopup && !uiState.showPopup
        prevShowPopup = uiState.showPopup
        if (wasDismissed && uiState.itemCount > 0) {
            delay(80)   // let Dialog leave-animation finish before scrolling
            val entryIdx = (uiState.focusedRow * SERIES_COLS + uiState.focusedCol)
                .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0))
            runCatching { seriesGridState.scrollToItem(entryIdx) }
            delay(48)   // wait for recompose after scroll
            runCatching { gridEntryFR.requestFocus() }
        }
    }

    LaunchedEffect(seriesGridState) {
        snapshotFlow { seriesGridState.firstVisibleItemIndex to seriesGridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onGridScrollChanged(index, offset)
            }
    }

    LaunchedEffect(categoryListState) {
        snapshotFlow { categoryListState.firstVisibleItemIndex to categoryListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onCategoryRailScrollChanged(index, offset)
            }
    }

    // After category content finishes loading, move focus to the grid if OK selected a category.
    LaunchedEffect(uiState.isCategoryLoading, uiState.selectedCategoryId, lazySeries.itemCount) {
        if (moveFocusAfterCategoryLoad && !uiState.isCategoryLoading && lazySeries.itemCount > 0) {
            moveFocusAfterCategoryLoad = false
            pendingGridFocusMove = true
        }
    }

    // Defer category -> grid focus handoff until after category filter recomposition.
    // Retry requestFocus until it succeeds (or timeout) — clearing the flag on a
    // failed one-shot left Firestick stuck on the category rail after OK.
    LaunchedEffect(
        pendingGridFocusMove,
        uiState.selectedCategoryId,
        uiState.itemCount,
        lazySeries.itemCount
    ) {
        if (!pendingGridFocusMove) return@LaunchedEffect
        if (uiState.itemCount <= 0) {
            // Keep focus parked on the rail and KEEP the flag armed (A2/A4) — clearing it
            // on a slow load consumed the handoff and left the user stuck on the rail.
            runCatching { categoryFR.requestFocus() }
            return@LaunchedEffect
        }
        if (lazySeries.itemCount <= 0) {
            return@LaunchedEffect
        }
        // Re-enter the remembered poster; category changes reset it in the ViewModel.
        val entryIndex = (uiState.focusedRow * SERIES_COLS + uiState.focusedCol)
            .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0))
        seriesGridState.scrollToItem(entryIndex)
        delay(48)
        runCatching { firstPosterBringIntoView.bringIntoView() }
        val deadline = System.nanoTime() + 3_000_000_000L // 3s — Firestick composition is slow
        while (pendingGridFocusMove && System.nanoTime() < deadline) {
            withFrameNanos { }
            runCatching { firstPosterBringIntoView.bringIntoView() }
            val focused = try {
                when (val result: Any = gridEntryFR.requestFocus()) {
                    is Boolean -> result
                    else -> true
                }
            } catch (_: Throwable) {
                false
            }
            if (focused) {
                pendingGridFocusMove = false
                return@LaunchedEffect
            }
            delay(48)
        }
        // One last delayed retry before soft-clear — see MoviesScreen (A2/A4).
        delay(400)
        runCatching { firstPosterBringIntoView.bringIntoView() }
        val lastChance = try {
            (gridEntryFR.requestFocus() as? Boolean) ?: true
        } catch (_: Throwable) {
            false
        }
        if (lastChance) Timber.d("Series: focus handoff succeeded on delayed retry")
        pendingGridFocusMove = false
    }

    LaunchedEffect(navigateToPlayer) {
        navigateToPlayer?.let { route ->
            viewModel.onNavigatedToPlayer()
            navController.navigateSafe(route)
        }
    }

    LaunchedEffect(navigateToDetail) {
        navigateToDetail?.let { seriesId ->
            viewModel.onNavigatedToDetail()
            navController.navigateSafe(Screen.SeriesDetail.createRoute(seriesId))
        }
    }

    val focusedIndex = (uiState.focusedRow * SERIES_COLS + uiState.focusedCol)
        .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0))
    val focusedSeries = if (uiState.itemCount > 0 && focusedIndex < lazySeries.itemCount) {
        lazySeries[focusedIndex]
    } else null
    val ambientContext = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
        // Note: NO onKeyEvent — Compose native TV focus traversal handles all D-pad.
        // Manual key interception fought with native traversal and caused focus to
        // escape to the TopAppBar back button → navigating back to Home screen.
    ) {
        if (!focusedSeries?.cover.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ambientContext)
                    .data(focusedSeries?.cover)
                    .crossfade(180)
                    .size(160, 90)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.22f },
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xCC050609),
                                Color(0x99050609),
                                Color(0xF0050609)
                            )
                        )
                    )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {

            TopAppBar(
                title = { Text("TV Series", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.focusProperties { canFocus = false }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = TextPrimary
                )
            )


            when {
                uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Accent)
                            Text("Loading series...", color = TextSecondary)
                        }
                    }
                }

                uiState.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Error: ${uiState.error}", color = StatusError)
                            Button(
                                onClick = { viewModel.load() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) { Text("Retry") }
                        }
                    }
                }

                else -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxSize()) {

                        // ── Category Rail (12%) ───────────────────────────
                        SeriesCategoryRail(
                            categories = uiState.categories,
                            selectedId = uiState.selectedCategoryId,
                            lockedIds = settingsState.lockedSeriesCategories.takeIf {
                                settingsState.parentalEnabled && settingsState.parentalPin.isNotEmpty()
                            }.orEmpty(),
                            onSelected = {
                                requestCategory(it)
                                moveFocusAfterCategoryLoad = true
                            },
                            listState = categoryListState,
                            focusRequester = categoryFR,
                            onRightKey = { pendingGridFocusMove = true },
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(96.dp)
                        )

                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(BorderDefault)
                        )

                        // ── Poster Grid (88%) ─────────────────────────────
                        SeriesPosterGrid(
                            series = lazySeries,
                            favoriteIds = favoriteIds,
                            gridState = seriesGridState,
                            entryItemFR = gridEntryFR,
                            entryBringIntoView = firstPosterBringIntoView,
                            entryItemIndex = (uiState.focusedRow * SERIES_COLS + uiState.focusedCol)
                                .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0)),
                            categoryFR = categoryFR,
                            onItemFocused = { row, col -> viewModel.setFocus(row, col) },
                            onItemSelected = { series, row, col ->
                                viewModel.setFocus(row, col)
                                viewModel.openSeries(series)
                            },
                            onToggleFavorite = { series -> viewModel.toggleSeriesFavorite(series.seriesId) },
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f)
                        )
                        }
                        if (uiState.isCategoryLoading) {
                            CircularProgressIndicator(
                                color = Accent,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp)
                            )
                        }
                    }
                }
            }
        }

        // Series detail is a dedicated route (SeriesDetailScreen) — grid restores focus when it pops.

        pinPromptCategory?.let { catId ->
            AlertDialog(
                onDismissRequest = { pinPromptCategory = null; pinDigits = ""; pinError = null },
                title = { Text("Parental PIN") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter PIN to unlock this series category", color = TextSecondary)
                        OutlinedTextField(
                            value = pinDigits,
                            onValueChange = { pinDigits = it.filter { c -> c.isDigit() }.take(4); pinError = null },
                            label = { Text("4-digit PIN") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                            )
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
    }
}

@Composable
private fun BingeRadarStrip(
    series: XtreamSeries?,
    seriesCount: Int,
    favoriteCount: Int,
    watchedCount: Int,
    categoryName: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = BgSurface2.copy(alpha = 0.82f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AccentBlue.copy(alpha = 0.14f),
                            Accent.copy(alpha = 0.08f),
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
                    .width(44.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgSurface3),
                contentAlignment = Alignment.Center
            ) {
                if (!series?.cover.isNullOrBlank()) {
                    AsyncImage(
                        model = series?.cover,
                        contentDescription = series?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Theaters, null, tint = Accent, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = series?.name ?: "Binge Radar",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = series?.genre?.takeIf { it.isNotBlank() } ?: "Focused shelf: $categoryName",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    BingeRadarChip("%,d shows".format(seriesCount), Accent)
                    BingeRadarChip("$favoriteCount favorites", StatusError)
                    BingeRadarChip("$watchedCount watched", StatusSuccess)
                    series?.rating?.takeIf { it.isNotBlank() }?.let { BingeRadarChip("rating $it", StatusWarning) }
                }
            }
            BingeRadarChip("OK opens seasons", StatusInfo)
        }
    }
}

@Composable
private fun BingeRadarChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f))
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SeriesCommandStrip(
    seriesCount: Int,
    categoryName: String,
    favoriteCount: Int,
    watchedCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(BgSurface2, BgBabyBlue.copy(alpha = 0.70f), BgSurface)
                )
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SeriesCommandChip(Icons.Default.Theaters, "%,d series".format(seriesCount), Accent)
        SeriesCommandChip(Icons.Default.GridView, categoryName, AccentBlue, Modifier.weight(1f))
        SeriesCommandChip(Icons.Default.Favorite, "$favoriteCount favorites", StatusError)
        SeriesCommandChip(Icons.Default.CheckCircle, "$watchedCount watched", StatusSuccess)
        SeriesCommandChip(Icons.Default.Keyboard, "OK seasons · LEFT categories · BACK closes", StatusInfo, Modifier.weight(1.2f))
    }
}

@Composable
private fun SeriesCommandChip(
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

// ── Category Rail ─────────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SeriesCategoryRail(
    categories: List<XtreamCategory>,
    selectedId: String,
    lockedIds: Set<String> = emptySet(),
    onSelected: (String) -> Unit,
    listState: LazyListState,
    focusRequester: FocusRequester,
    onRightKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sidebarFocus = com.dylandos.iptv.ultimate.ui.focus.LocalNavigationSidebarFocusRequester.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .background(Color(0x660B1220))
                        .focusProperties {
                left = sidebarFocus ?: FocusRequester.Default
            },
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(categories, key = { _, cat -> cat.categoryId }) { index, cat ->
            val isSelected = cat.categoryId == selectedId
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isSelected || (index == 0 && categories.none { it.categoryId == selectedId })) Modifier.focusRequester(focusRequester) else Modifier)
                    .background(
                        when {
                            isSelected -> Accent.copy(alpha = 0.22f)
                            isFocused  -> FocusBgStrong
                            else       -> Color.Transparent
                        }
                    )
                    .then(
                        if (isFocused || isSelected)
                            Modifier.border(
                                if (isFocused) 2.5.dp else 1.dp,
                                Accent,
                                RoundedCornerShape(4.dp)
                            )
                        else Modifier
                    )
                    .onKeyEvent { event ->
                        when {
                            event.isRemoteConfirmKey() -> {
                                onSelected(cat.categoryId)
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                                onRightKey()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                                runCatching { sidebarFocus?.requestFocus() }; true
                            }
                            else -> false
                        }
                    }
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onSelected(cat.categoryId)
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cat.categoryId in lockedIds && cat.categoryId != selectedId) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = StatusWarning,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = cat.categoryName,
                        color = if (isSelected) Accent else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Poster Grid ───────────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SeriesPosterGrid(
    series: LazyPagingItems<XtreamSeries>,
    favoriteIds: Set<Int>,
    gridState: LazyGridState,
    entryItemFR: FocusRequester,
    entryBringIntoView: BringIntoViewRequester,
    entryItemIndex: Int,
    categoryFR: FocusRequester,
    onItemFocused: (row: Int, col: Int) -> Unit,
    onItemSelected: (XtreamSeries, Int, Int) -> Unit,
    onToggleFavorite: (XtreamSeries) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(SERIES_COLS),
        modifier = modifier
,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            count = series.itemCount,
            key = series.itemKey { it.seriesId }
        ) { index ->
            val s = series[index] ?: return@items
            val row = index / SERIES_COLS
            val col = index % SERIES_COLS

            SeriesPosterCard(
                series = s,
                isFavorite = favoriteIds.contains(s.seriesId),
                col = col,
                focusRequester = if (index == entryItemIndex) entryItemFR else null,
                bringIntoViewRequester = if (index == entryItemIndex) entryBringIntoView else null,
                onFocused = { onItemFocused(row, col) },
                onSelected = { onItemSelected(s, row, col) },
                onToggleFavorite = { onToggleFavorite(s) },
                onLeftEdge = { try { categoryFR.requestFocus() } catch (_: Exception) {} }
            )
        }
    }
}

@Composable
private fun SeriesPosterCard(
    series: XtreamSeries,
    isFavorite: Boolean = false,
    col: Int,
    focusRequester: FocusRequester? = null,
    bringIntoViewRequester: BringIntoViewRequester? = null,
    onFocused: () -> Unit,
    onSelected: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLeftEdge: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isCompFocused by interactionSource.collectIsFocusedAsState()
    val showFocus = isCompFocused

    // PERF FIX (HIGH-006): tween(120) replaces MediumBouncy spring — same fix applied in
    // MoviesScreen. 40+ simultaneous spring animations caused visible frame drops on Firestick 4K
    // during D-pad navigation. Linear tween at 120ms is imperceptible to the user but costs
    // ~4x fewer animation frames per card.
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showFocus) 1.08f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120),
        label = "seriesScale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(7.dp))
            .then(if (bringIntoViewRequester != null) Modifier.bringIntoViewRequester(bringIntoViewRequester) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                when {
                    // DPAD_CENTER / Enter / BUTTON_A — explicit confirm key on Firestick
                    event.isRemoteConfirmKey() -> { onSelected(); true }
                    event.type == KeyEventType.KeyUp && event.key == Key.Menu -> {
                        onToggleFavorite(); true
                    }
                    // LEFT at column 0 → return focus to category rail
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft && col == 0 && onLeftEdge != null -> {
                        onLeftEdge(); true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null) { onSelected() }
            .then(if (showFocus) Modifier.border(2.5.dp, Color(0xFFFF6B7A), RoundedCornerShape(7.dp)) else Modifier)
    ) {
        if (!series.cover.isNullOrBlank()) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(series.cover)
                    .crossfade(false)
                    .diskCacheKey("series_${series.seriesId}")
                    .memoryCacheKey("series_${series.seriesId}")
                    // PERF: Decode at thumbnail size to prevent GC thrashing.
                    // A 5-col 1080p grid needs ~150×225 px per poster.
                    .size(150, 225)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = series.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(BgSurface3),
                error = ColorPainter(BgSurface3),
                fallback = ColorPainter(BgSurface3),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(BgSurface3),
                contentAlignment = Alignment.Center
            ) {
                Text(series.name.take(3), color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (!series.rating.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = series.rating,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE5000000))))
                .padding(8.dp)
        ) {
            Column {
                Text(series.name, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        if (showFocus) {
            Box(modifier = Modifier.fillMaxSize().background(Accent.copy(alpha = 0.08f)))
        }

        // S-015: Heart icon hint — shows when focused to indicate long-press adds to favorites
        if (showFocus) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = "Add to favorites",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(16.dp)
                    .background(Color(0x66000000), androidx.compose.foundation.shape.CircleShape)
                    .padding(2.dp)
            )
        }
    }
}

// ── Series Detail Popup (Fire TV: two-pane seasons + episodes) ────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SeriesDetailPopup(
    series: XtreamSeries,
    detail: XtreamSeriesInfo?,
    selectedSeason: Int,
    isLoading: Boolean,
    error: String?,
    resumeEpisode: WatchHistoryEntity?,
    watchedEpisodeIds: Set<String>,
    recommendations: List<XtreamSeries>,
    viewModel: SeriesViewModel,
    onClose: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    fullScreen: Boolean = false
) {
    val isFavorite by viewModel.isFavoriteFlow(series.seriesId).collectAsState(initial = false)
    // Do NOT focus the outer Card — that races seasons/episodes and forces users to
    // press Center before DPAD works. SeriesDetailTwoPane owns initial focus.


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (fullScreen) BgBase else Color(0x80000000))
    ) {
        Card(
            modifier = Modifier
                .then(
                    if (fullScreen) Modifier.fillMaxSize()
                    else Modifier
                        .fillMaxWidth(0.78f)
                        .fillMaxHeight(0.82f)
                        .align(Alignment.Center)
                        .padding(12.dp)
                )
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        onClose()
                        true
                    } else false
                },
            colors = CardDefaults.cardColors(containerColor = BgElevated),
            shape = if (fullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (fullScreen) 0.dp else 20.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header: poster + title + short plot; close is remote-only (Back) / mouse click
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgSurface2)
                ) {
                    val popupContext = LocalContext.current
                    val headerBackdrop = detail?.info?.backdropPath?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: series.backdropPath?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: series.cover?.takeIf { it.isNotBlank() }

                    if (!headerBackdrop.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(popupContext)
                                .data(headerBackdrop)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp),
                            contentScale = ContentScale.Crop,
                            placeholder = ColorPainter(BgSurface3),
                            error = ColorPainter(BgSurface3),
                            fallback = ColorPainter(BgSurface3),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0x55000000), Color(0xC0000000), BgSurface2)
                                )
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (!series.cover.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(popupContext)
                                    .data(series.cover)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = series.name,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                                placeholder = ColorPainter(BgSurface3),
                                error = ColorPainter(BgSurface3),
                                fallback = ColorPainter(BgSurface3),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Column(Modifier.weight(1f)) {
                        Text(
                            text = series.name,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        val info = detail?.info
                        val rating = info?.rating5Based?.takeIf { it > 0.0 }
                            ?: series.rating5Based.takeIf { it > 0.0 }
                        val year = info?.releaseDate?.take(4)
                            ?: series.releaseDate?.take(4)
                        val genre = info?.genre?.split(",")?.firstOrNull()?.trim()
                            ?: series.genre?.split(",")?.firstOrNull()?.trim()
                        val runtime = info?.episodeRunTime?.trim()?.takeIf { !it.isNullOrBlank() }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        ) {
                            if (rating != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = StatusWarning,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "%.1f".format(rating),
                                        color = StatusWarning,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (!year.isNullOrBlank()) {
                                SeriesMetaBadge(year)
                            }
                            if (!genre.isNullOrBlank()) {
                                SeriesMetaBadge(genre)
                            }
                            if (!runtime.isNullOrBlank()) {
                                SeriesMetaBadge(runtime)
                            }
                        }

                        if (!info?.plot.isNullOrBlank()) {
                            Text(
                                text = info?.plot ?: "",
                                color = TextSecondary,
                                fontSize = if (fullScreen) 14.sp else 12.sp,
                                lineHeight = if (fullScreen) 20.sp else 16.sp,
                                maxLines = if (fullScreen) 5 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "D-pad: move between seasons and episodes. OK to play. Back to close.",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }

                        com.dylandos.iptv.ultimate.ui.components.MediaRatingsPanel(series.name, "tv", info?.youtubeTrailer ?: series.youtubeTrailer)
                        val director = info?.director?.trim()?.ifBlank { null }
                            ?: series.director?.trim()?.ifBlank { null }
                        if (!director.isNullOrBlank()) {
                            Text(
                                text = "Director: $director",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        val cast = info?.cast?.trim()?.ifBlank { null }
                            ?: series.cast?.trim()?.ifBlank { null }
                        if (!cast.isNullOrBlank()) {
                            Text(
                                text = "Cast: $cast",
                                color = TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                        // Favorite toggle
                        IconButton(
                            onClick = { viewModel.toggleSeriesFavorite(series.seriesId) },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                if (isFavorite) Icons.Default.Star else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                                tint = if (isFavorite) androidx.compose.ui.graphics.Color(0xFFFFD700) else TextSecondary
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextSecondary
                            )
                        }
                    }
                }
                if (recommendations.isNotEmpty()) {
                    // Non-focusable strip — recommendations above seasons polluted DPAD
                    // paths and stole focus from Resume / first season.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgSurface2)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "YOU MAY ALSO LIKE",
                            color = Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                        LazyRow(
                            userScrollEnabled = false,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(top = 6.dp)
                        ) {
                            items(recommendations.take(6), key = { "rec_${it.seriesId}" }) { recommendation ->
                                SeriesRecommendationCard(
                                    series = recommendation,
                                    onClick = { viewModel.openSeries(recommendation) },
                                    focusable = false
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = BorderDefault, thickness = 1.dp)

                when {
                    isLoading -> {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Accent,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    error != null -> {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = error,
                                color = StatusError,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                    detail != null -> {
                        SeriesDetailTwoPane(
                            detail = detail,
                            selectedSeason = selectedSeason,
                            resumeEpisode = resumeEpisode,
                            watchedEpisodeIds = watchedEpisodeIds,
                            viewModel = viewModel,
                            onPlayEpisode = onPlayEpisode,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesRecommendationCard(
    series: XtreamSeries,
    onClick: () -> Unit,
    focusable: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(190.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) FocusBgStrong else BgSurface3)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Accent else BorderDefault,
                RoundedCornerShape(8.dp)
            )
            .then(if (focusable) Modifier.focusable() else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (focusable && event.isRemoteConfirmKey()) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(enabled = focusable, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = series.cover,
            contentDescription = series.name,
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(BgElevated),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                series.name,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (series.rating5Based > 0.0) {
                Text(
                    "★ %.1f".format(series.rating5Based),
                    color = StatusWarning,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun SeriesMetaBadge(text: String) {
    Box(
        modifier = Modifier
            .background(BgSurface3, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = TextSecondary, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeriesDetailTwoPane(
    detail: XtreamSeriesInfo,
    selectedSeason: Int,
    resumeEpisode: WatchHistoryEntity?,
    watchedEpisodeIds: Set<String>,
    viewModel: SeriesViewModel,
    onPlayEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedSeasons = remember(detail) {
        displaySeasons(detail)
    }
    val seasonFr = remember(orderedSeasons) {
        orderedSeasons.associate { s -> s.seasonNumber to FocusRequester() }
    }
    val firstEpisodeFr = remember { FocusRequester() }
    val resumeFr = remember { FocusRequester() }
    val episodes = viewModel.episodesForSeason(detail, selectedSeason)

    // Counter-based trigger: incrementing it re-launches the LaunchedEffect without
    // the self-cancellation race that a boolean flag would introduce.
    var wantEpisodeFocusTick by remember { mutableStateOf(0) }
    LaunchedEffect(wantEpisodeFocusTick) {
        if (wantEpisodeFocusTick == 0) return@LaunchedEffect
        // A4: retry until the episode LazyColumn accepts focus. The old 32 ms one-shot
        // fired before the newly selected season's episodes composed → user got stuck on
        // the season rail until pressing Center again. Poll every 50 ms up to 2.5 s.
        val deadline = System.nanoTime() + 2_500_000_000L
        while (System.nanoTime() < deadline) {
            delay(50)
            val focused = try {
                when (val result: Any = firstEpisodeFr.requestFocus()) {
                    is Boolean -> result
                    else -> true
                }
            } catch (_: Throwable) {
                false
            }
            if (focused) return@LaunchedEffect
        }
        Timber.w("Series: first-episode focus timed out (season $selectedSeason)")
    }

    LaunchedEffect(detail, resumeEpisode?.episodeId) {
        // Retry until Resume / first season accepts focus. Detail + seasons often
        // compose after the first frame on Firestick — a short window forced users
        // to press Center before D-pad worked.
        if (orderedSeasons.isEmpty() && resumeEpisode == null) return@LaunchedEffect
        val deadline = System.nanoTime() + 2_500_000_000L
        while (System.nanoTime() < deadline) {
            delay(50)
            val focused = when {
                resumeEpisode != null -> try {
                    when (val result: Any = resumeFr.requestFocus()) {
                        is Boolean -> result
                        else -> true
                    }
                } catch (_: Throwable) {
                    false
                }
                orderedSeasons.isNotEmpty() -> {
                    val sn = orderedSeasons.first().seasonNumber
                    try {
                        val fr = seasonFr[sn]
                        if (fr == null) false
                        else when (val result: Any = fr.requestFocus()) {
                            is Boolean -> result
                            else -> true
                        }
                    } catch (_: Throwable) {
                        false
                    }
                }
                else -> false
            }
            if (focused) return@LaunchedEffect
        }
    }

    fun focusSeason(sn: Int) = try { seasonFr[sn]?.requestFocus() } catch (_: Exception) {}

    if (orderedSeasons.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No season information available.", color = TextTertiary, fontSize = 16.sp)
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .dylandosScreenBackground()
    ) {
        if (resumeEpisode != null) {
            Button(
                onClick = {
                    val match = detail.episodes.values.flatten()
                        .firstOrNull { it.id == resumeEpisode.episodeId }
                    if (match != null) onPlayEpisode(match)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(min = 50.dp)
                    .focusRequester(resumeFr)
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                            val firstSn = orderedSeasons.firstOrNull()?.seasonNumber
                            if (firstSn != null) {
                                viewModel.selectSeason(firstSn)
                                focusSeason(firstSn)
                            }
                            true
                        } else false
                    },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Resume S${resumeEpisode.seasonNumber}E${resumeEpisode.episodeNumber}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ── Seasons rail (D-pad UP / DOWN) ──
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .background(BgSurface2)
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "SEASONS",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(orderedSeasons, key = { s -> s.seasonNumber }) { season ->
                        val isFirst = season.seasonNumber == orderedSeasons.first().seasonNumber
                        val isSelected = season.seasonNumber == selectedSeason
                        val fr = seasonFr[season.seasonNumber] ?: return@items
                        val episodeCount = viewModel.episodesForSeason(detail, season.seasonNumber).size
                        SeriesSeasonListItem(
                            season = season,
                            isSelected = isSelected,
                            focusRequester = fr,
                            isFirstSeason = isFirst,
                            resumeFr = if (resumeEpisode != null) resumeFr else null,
                            onSelect = {
                                viewModel.selectSeason(season.seasonNumber)
                                // A4: OK on a season also hands focus to its episodes (with
                                // retry), instead of stranding the user on the season rail.
                                wantEpisodeFocusTick++
                            },
                            onDpadUp = {
                                if (isFirst) {
                                    if (resumeEpisode != null) {
                                        try { resumeFr.requestFocus() } catch (_: Exception) {}
                                        true
                                    } else false
                                } else false
                            },
                            onDpadRight = {
                                if (episodeCount > 0) {
                                    wantEpisodeFocusTick++
                                    true
                                } else false
                            }
                        )
                    }
                }
            }
            VerticalDivider(
                color = BorderDefault,
                thickness = 1.dp,
                modifier = Modifier.fillMaxHeight()
            )

            // ── Episodes (D-pad in list; Left returns to current season) ──
            Column(Modifier.weight(1f)) {
                Text(
                    text = "EPISODES",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 2.dp
                    )
                )
                val epCount = episodes.size
                Text(
                    text = "Season $selectedSeason  ·  $epCount item${if (epCount == 1) "" else "s"}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 8.dp
                    )
                )
                if (episodes.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No episodes for this season", color = TextTertiary, fontSize = 15.sp)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            top = 0.dp,
                            end = 12.dp,
                            bottom = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        itemsIndexed(
                            items = episodes,
                            key = { _, ep -> ep.id }
                        ) { index, ep ->
                            EpisodeRow(
                                episode = ep,
                                isWatched = ep.id in watchedEpisodeIds,
                                onPlay = { onPlayEpisode(ep) },
                                focusRequester = if (index == 0) firstEpisodeFr else null,
                                onDpadLeftToSeasons = {
                                    focusSeason(selectedSeason)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeriesSeasonListItem(
    season: Season,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    isFirstSeason: Boolean,
    resumeFr: FocusRequester?,
    onSelect: () -> Unit,
    onDpadUp: () -> Boolean,
    onDpadRight: () -> Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val title = "Season ${season.seasonNumber}"
    val sub = if (season.episodeCount > 0) "${season.episodeCount} ep${if (season.episodeCount == 1) "" else "s"}"
    else if (!season.name.isNullOrBlank()) season.name
    else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                if (isSelected || isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(
                if (isFocused) AccentSurfaceHover
                else if (isSelected) BgSurface3
                else BgBase,
                RoundedCornerShape(8.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onSelect() }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.isRemoteConfirmKey() -> {
                        onSelect()
                        onDpadRight()
                        true
                    }
                    e.key == Key.DirectionUp -> onDpadUp()
                    e.key == Key.DirectionRight -> {
                        onSelect()
                        onDpadRight()
                    }
                    else -> false
                }
            }
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = if (isSelected) Accent else TextPrimary,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        if (sub != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "($sub)",
                color = TextTertiary,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    isWatched: Boolean = false,
    onPlay: () -> Unit,
    focusRequester: FocusRequester? = null,
    onDpadLeftToSeasons: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(
                if (isFocused) AccentSurfaceHover else BgSurface3.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp)
            )
            .onKeyEvent { e ->
                when {
                    e.isRemoteConfirmKey() -> {
                        onPlay()
                        true
                    }
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft -> {
                        onDpadLeftToSeasons()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onPlay() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = AccentSurface,
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = "S${episode.season}E${episode.episodeNum}",
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = episode.title.ifBlank { "Episode ${episode.episodeNum}" },
                color = if (isWatched) TextTertiary else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            episode.info?.duration?.let { dur ->
                Text(
                    text = dur,
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
        if (isWatched) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Watched",
                tint = StatusSuccess,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = if (isFocused) Accent else TextSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
