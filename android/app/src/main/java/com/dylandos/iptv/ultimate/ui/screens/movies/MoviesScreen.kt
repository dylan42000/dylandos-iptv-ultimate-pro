package com.dylandos.iptv.ultimate.ui.screens.movies

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.ColorPainter
import com.dylandos.iptv.ultimate.data.db.entity.VodResumeEntity
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos

/**
 * DYLANDOS IPTV ULTIMATE — Movies Screen (Netflix style)
 *
 * Layout:
 *   ┌──────────┬─────────────────────────────────────────────────────┐
 *   │ Category │  Netflix-style poster grid (85-90%)                 │
 *   │  rail    │  [Poster][Poster][Poster]...                        │
 *   │  10-15%  │  [Poster][Poster][Poster]...                        │
 *   └──────────┴─────────────────────────────────────────────────────┘
 *
 * D-pad: UP/DOWN/LEFT/RIGHT navigate poster grid; LEFT from col 0 → category rail.
 */
private const val POSTER_COLS = 5   // Larger Firestick poster wall for couch-distance readability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    navController: NavController,
    viewModel: MoviesViewModel = hiltViewModel(),
    settingsViewModel: com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val navigateToPlayer by viewModel.navigateToPlayer.collectAsState()
    val navigateToDetail by viewModel.navigateToDetail.collectAsState()
    // Paginated movie list — only 20 items decoded into memory at a time (OOM fix).
    val lazyMovies = viewModel.pagingData.collectAsLazyPagingItems()
    // Collected from a DEDICATED StateFlow — separate from uiState so that adding/
    // removing a favorite does NOT trigger a full-grid recomposition.
    val favoriteIds by viewModel.favoriteMovieIds.collectAsState()
    var unlockedCategories by remember { mutableStateOf(setOf<String>()) }
    var pinPromptCategory by remember { mutableStateOf<String?>(null) }
    var pinDigits by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun requestCategory(categoryId: String) {
        val locked = settingsViewModel.isMovieCategoryLocked(categoryId) &&
            categoryId !in unlockedCategories
        if (locked) {
            pinPromptCategory = categoryId
            pinDigits = ""
            pinError = null
        } else {
            viewModel.selectCategory(categoryId)
        }
    }

    // Focus requesters for the two panels
    val categoryFR  = remember { FocusRequester() }
    val gridEntryFR = remember { FocusRequester() }
    val firstPosterBringIntoView = remember { BringIntoViewRequester() }
    val categoryListState = rememberLazyListState(
        initialFirstVisibleItemIndex = uiState.categoryRailFirstVisibleIndex,
        initialFirstVisibleItemScrollOffset = uiState.categoryRailFirstVisibleOffset
    )
    val posterGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = uiState.gridFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = uiState.gridFirstVisibleItemOffset
    )

    // ONE-SHOT focus flag: category rail auto-focus runs only once per screen entry.
    // Previously used LaunchedEffect(uiState.categories.isNotEmpty()) which re-fired
    // every time the 5-min VOD cache expired and triggered a reload — trapping D-pad
    // focus on the category rail instead of returning to the poster grid.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    var pendingGridFocusMove by remember { mutableStateOf(false) }
    var moveFocusAfterCategoryLoad by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var shouldRestoreFocusOnResume by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                shouldRestoreFocusOnResume = initialFocusDone
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
        viewModel.refreshResume()   // re-check positions when returning from player
    }

    // Focus restoration on initial load AND on resume from details/player
    LaunchedEffect(
        uiState.categories.isNotEmpty(),
        uiState.isLoading,
        uiState.itemCount,
        lazyMovies.itemCount,
        shouldRestoreFocusOnResume
    ) {
        val needsInitial = uiState.categories.isNotEmpty() && !uiState.isLoading && !initialFocusDone
        val needsResume = shouldRestoreFocusOnResume && uiState.itemCount > 0 && lazyMovies.itemCount > 0

        if (!needsInitial && !needsResume) return@LaunchedEffect

        if (needsInitial) initialFocusDone = true
        if (needsResume) shouldRestoreFocusOnResume = false

        val maxIdx = (uiState.itemCount - 1).coerceAtLeast(0)
        val restoredIndex = (uiState.focusedRow * POSTER_COLS + uiState.focusedCol).coerceIn(0, maxIdx)
        val shouldRestoreGrid = needsResume

        if (shouldRestoreGrid) {
            runCatching { posterGridState.scrollToItem(restoredIndex) }
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

    LaunchedEffect(posterGridState) {
        snapshotFlow { posterGridState.firstVisibleItemIndex to posterGridState.firstVisibleItemScrollOffset }
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
    LaunchedEffect(uiState.isCategoryLoading, uiState.selectedCategoryId, lazyMovies.itemCount) {
        if (moveFocusAfterCategoryLoad && !uiState.isCategoryLoading && lazyMovies.itemCount > 0) {
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
        lazyMovies.itemCount
    ) {
        if (!pendingGridFocusMove) return@LaunchedEffect
        if (uiState.itemCount <= 0) {
            // Keep focus parked on the rail and KEEP the flag armed. Clearing it here
            // (previous behavior) meant pressing OK/Down during a slow catalog load
            // consumed the handoff, so when data landed the user was stuck in the rail.
            runCatching { categoryFR.requestFocus() }
            return@LaunchedEffect
        }
        if (lazyMovies.itemCount <= 0) {
            // Paging has not composed the first poster yet — keep flag set.
            return@LaunchedEffect
        }
        // Re-enter the remembered poster; category changes reset it in the ViewModel.
        val entryIndex = (uiState.focusedRow * POSTER_COLS + uiState.focusedCol)
            .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0))
        posterGridState.scrollToItem(entryIndex)
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
        // Soft-clear after timeout so we do not spin forever when paging never attaches FR.
        // One last delayed retry: Firestick composition can settle well after the 3s window
        // (slow Room paging + image decode burst) — without it the user sits stuck on the rail.
        delay(400)
        runCatching { firstPosterBringIntoView.bringIntoView() }
        val lastChance = try {
            (gridEntryFR.requestFocus() as? Boolean) ?: true
        } catch (_: Throwable) {
            false
        }
        if (lastChance) Timber.d("Movies: focus handoff succeeded on delayed retry")
        pendingGridFocusMove = false
    }

    LaunchedEffect(navigateToPlayer) {
        navigateToPlayer?.let { route ->
            viewModel.onNavigatedToPlayer()
            navController.navigateSafe(route)
        }
    }

    LaunchedEffect(navigateToDetail) {
        navigateToDetail?.let { streamId ->
            viewModel.onNavigatedToDetail()
            navController.navigateSafe("movie_detail/$streamId")
        }
    }

    val focusedIndex = (uiState.focusedRow * POSTER_COLS + uiState.focusedCol)
        .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0))
    val focusedMovie = if (uiState.itemCount > 0 && focusedIndex < lazyMovies.itemCount) {
        lazyMovies[focusedIndex]
    } else null
    val ambientContext = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
        // Note: NO onKeyEvent here — Compose native TV focus traversal handles D-pad.
        // Outer-box key interception fought with the native traversal and caused
        // focus to escape to the TopAppBar back button (→ user navigated back to Home).
    ) {
        // Sparkle App Mode ambient: focused poster bleeds into the background.
        if (!focusedMovie?.streamIcon.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ambientContext)
                    .data(focusedMovie?.streamIcon)
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

            // ── Top Bar ───────────────────────────────────────────────
            TopAppBar(
                title = { Text("Movies", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        // Keep Back out of the TV focus order so category/poster OK
                        // can never accidentally activate it and return to Home.
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
                            Text("Loading movies...", color = TextSecondary)
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
                        MovieCategoryRail(
                            categories = uiState.categories,
                            selectedId = uiState.selectedCategoryId,
                            lockedIds = settingsState.lockedMovieCategories.takeIf {
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

                        // Divider
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(BorderDefault)
                        )

                        // ── Poster Grid (88%) ─────────────────────────────
                        MoviePosterGrid(
                            movies = lazyMovies,
                            resumeMap = uiState.resumeMap,
                            favoriteIds = favoriteIds,
                            gridState = posterGridState,
                            entryItemFR = gridEntryFR,
                            entryBringIntoView = firstPosterBringIntoView,
                            entryItemIndex = (uiState.focusedRow * POSTER_COLS + uiState.focusedCol)
                                .coerceIn(0, (uiState.itemCount - 1).coerceAtLeast(0)),
                            categoryFR = categoryFR,
                            onItemFocused = { row, col -> viewModel.setFocus(row, col) },
                            onItemSelected = { movie, row, col ->
                                viewModel.setFocus(row, col)
                                viewModel.showDetail(movie)
                            },
                            onToggleFavorite = { movie -> viewModel.toggleMovieFavorite(movie.streamId) },
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

        pinPromptCategory?.let { catId ->
            AlertDialog(
                onDismissRequest = { pinPromptCategory = null; pinDigits = ""; pinError = null },
                title = { Text("Parental PIN") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter PIN to unlock this movie category", color = TextSecondary)
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
private fun CinemaPulseStrip(
    movie: XtreamMovie?,
    movieCount: Int,
    favoriteCount: Int,
    resumeCount: Int,
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
                            StatusWarning.copy(alpha = 0.12f),
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
                if (!movie?.streamIcon.isNullOrBlank()) {
                    AsyncImage(
                        model = movie?.streamIcon,
                        contentDescription = movie?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = movie?.name ?: "Cinema Pulse",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Focused shelf: $categoryName",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    MoviePulseChip("%,d titles".format(movieCount), Accent)
                    MoviePulseChip("$favoriteCount favorites", StatusError)
                    MoviePulseChip("$resumeCount continue", StatusSuccess)
                    movie?.rating?.takeIf { it.isNotBlank() }?.let { MoviePulseChip("rating $it", StatusWarning) }
                }
            }
            MoviePulseChip("OK opens details", StatusInfo)
        }
    }
}

@Composable
private fun MoviePulseChip(label: String, color: Color) {
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
private fun MoviesCommandStrip(
    movieCount: Int,
    categoryName: String,
    favoriteCount: Int,
    resumeCount: Int
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
        MoviesCommandChip(Icons.Default.Movie, "%,d movies".format(movieCount), Accent)
        MoviesCommandChip(Icons.Default.GridView, categoryName, AccentBlue, Modifier.weight(1f))
        MoviesCommandChip(Icons.Default.Favorite, "$favoriteCount favorites", StatusError)
        MoviesCommandChip(Icons.Default.PlayCircle, "$resumeCount resume", StatusSuccess)
        MoviesCommandChip(Icons.Default.Keyboard, "OK details · LEFT categories · MENU favorite", StatusInfo, Modifier.weight(1.2f))
    }
}

@Composable
private fun MoviesCommandChip(
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

// ── Category Rail —————————————————————————————————————————————

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MovieCategoryRail(
    categories: List<XtreamCategory>,
    selectedId: String,
    lockedIds: Set<String> = emptySet(),
    onSelected: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
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
        // key {} ensures Compose reuses existing composables on category list updates
        // instead of recreating all items — preserves MutableInteractionSource/focus state.
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
                            isSelected -> Accent.copy(alpha = 0.18f)
                            isFocused  -> BgElevated
                            else       -> Color.Transparent
                        }
                    )
                    .then(
                        if (isFocused || isSelected)
                            Modifier.border(
                                if (isFocused) 2.dp else 1.dp,
                                if (isFocused) Accent else Accent.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp)
                            )
                        else Modifier
                    )
                    .onKeyEvent { event ->
                        when {
                            event.isRemoteConfirmKey() -> {
                                // Confirm only selects — parent schedules safe grid focus after load.
                                onSelected(cat.categoryId)
                                true
                            }
                            // RIGHT returns to the poster grid without changing category.
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                                onRightKey()
                                true
                            }
                            // Keep focus inside Movies; Back is the intentional way out.
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                                runCatching { sidebarFocus?.requestFocus() }; true
                            }
                            else -> false
                        }
                    }
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onSelected(cat.categoryId)
                    }
                    .padding(horizontal = 8.dp, vertical = 9.dp)
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
private fun MoviePosterGrid(
    movies: LazyPagingItems<XtreamMovie>,
    resumeMap: Map<Int, VodResumeEntity>,
    favoriteIds: Set<Int>,
    gridState: LazyGridState,
    entryItemFR: FocusRequester,
    entryBringIntoView: BringIntoViewRequester,
    entryItemIndex: Int,
    categoryFR: FocusRequester,     // used by LEFT-edge cards to return to category rail
    onItemFocused: (row: Int, col: Int) -> Unit,
    onItemSelected: (XtreamMovie, Int, Int) -> Unit,
    onToggleFavorite: (XtreamMovie) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(POSTER_COLS),
        modifier = modifier
            // Prevent focus escaping UPWARD to the TopAppBar back button at row 0
,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Paged items: Compose only keeps ~60 items in the LazyGrid at a time.
        // itemKey ensures Compose reuses existing composables on list updates.
        items(
            count = movies.itemCount,
            key = movies.itemKey { it.streamId }
        ) { index ->
            val movie = movies[index] ?: return@items
            val row = index / POSTER_COLS
            val col = index % POSTER_COLS

            MoviePosterCard(
                movie = movie,
                resumeEntry = resumeMap[movie.streamId],
                isFavorite = favoriteIds.contains(movie.streamId),
                col = col,
                focusRequester = if (index == entryItemIndex) entryItemFR else null,
                bringIntoViewRequester = if (index == entryItemIndex) entryBringIntoView else null,
                onFocused = { onItemFocused(row, col) },
                onSelected = { onItemSelected(movie, row, col) },
                onToggleFavorite = { onToggleFavorite(movie) },
                onLeftEdge = { try { categoryFR.requestFocus() } catch (_: Exception) {} }
            )
        }
    }
}

// ── Poster Card ───────────────────────────────────────────────────────────────

@Composable
private fun MoviePosterCard(
    movie: XtreamMovie,
    resumeEntry: VodResumeEntity?,
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

    // PERF FIX: tween(120) replaces MediumBouncy spring — 40+ simultaneous spring
    // animations caused visible frame drops on Firestick 4K during D-pad navigation.
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showFocus) 1.08f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 120),
        label = "posterScale"
    )

    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)       // Standard movie poster ratio
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
                    // MENU / long-press equivalent — toggle favorite
                    event.type == KeyEventType.KeyUp && event.key == Key.Menu -> {
                        onToggleFavorite(); true
                    }
                    // LEFT at column 0 → return focus to category rail
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft && col == 0 && onLeftEdge != null -> {
                        onLeftEdge()
                        true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null) { onSelected() }
            .then(
                if (showFocus)
                    Modifier.border(2.5.dp, Color(0xFFFF6B7A), RoundedCornerShape(7.dp))
                else Modifier
            )
    ) {
        // Poster image — uses ImageRequest so error/placeholder show a dark surface
        // instead of transparent nothing when the URL fails to load
        val context = LocalContext.current
        // diskCacheKey = streamId: IPTV CDN URLs rotate between sessions for the
        // same poster image. Using a stable key ensures Coil reuses cached images even
        // when the URL changes, eliminating redundant poster downloads.
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(movie.streamIcon?.takeIf { it.isNotBlank() })
                .crossfade(false)
                .diskCacheKey("poster_${movie.streamId}")
                .memoryCacheKey("poster_${movie.streamId}")
                // PERF: Decode at thumbnail size to prevent GC thrashing.
                // A 5-col 1080p grid needs ~150×225 px per poster.
                .size(150, 225)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = movie.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(BgSurface3),
            error = ColorPainter(BgSurface3),
            fallback = ColorPainter(BgSurface3),
        )

        // Top-right rating badge, matching the redesign poster wall.
        if (!movie.rating.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = movie.rating,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }

        // Gradient overlay + title at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE5000000)),
                        startY = 0f
                    )
                )
                .padding(8.dp)
        ) {
            Column {
                Text(
                    text = movie.name,
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Focus glow overlay
        if (showFocus) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Accent.copy(alpha = 0.08f))
            )
        }

        // S-007: Heart icon hint — shows when focused to indicate long-press adds to favorites
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

        // Resume progress bar (5%–89% watched)
        if (resumeEntry != null && resumeEntry.progressPct in 5..89) {
            LinearProgressIndicator(
                progress = { resumeEntry.progressPct / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter),
                color = Accent,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
        }

        // "Watched" checkmark badge for fully watched movies (>= 90%)
        if (resumeEntry != null && resumeEntry.isWatched) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Watched",
                tint = Accent,
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
            )
        }
    }
}
