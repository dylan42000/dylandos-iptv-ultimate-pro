package com.dylandos.iptv.ultimate.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dylandos.iptv.ultimate.R
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamSeries

import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.theme.*
import com.dylandos.iptv.ultimate.ui.components.tvCardFocusable
import com.dylandos.iptv.ultimate.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * DYLANDOS IPTV ULTIMATE — Home Screen
 *
 * Shows:
 *  • Brand header with logo
 *  • Live stats bar (Live Channels / Movies / Series) — loaded async, shows spinner while loading
 *  • Top nav row — quick jump to any section (D-pad friendly)
 *  • Quick Access grid — all 8 major sections as cards (4 columns, 2 rows)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val content by viewModel.content.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadHomeData()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── Header ───────────────────────────────────────────────────────
            item {
                val activeAccount = settingsState.savedAccounts.find { it.id == settingsState.activeAccountId }
                HomeHeader(
                    activeAccountName = activeAccount?.nickname?.ifBlank { settingsState.username }
                        ?: settingsState.username.ifBlank { null },
                    navController = navController
                )
            }

            item { HomeStatsRow(stats = stats) }

            // ── Fast Firestick dashboard ─────────────────────────────────────
            item {
                val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
                val isNarrow = screenWidthDp < 600
                val columns = if (isNarrow) 2 else 4
                val gridHeight = if (isNarrow) 480.dp else 278.dp

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (isNarrow) 14.dp else 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Start Watching",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(gridHeight),
                        userScrollEnabled = false
                    ) {
                        items(quickAccessItems) { item ->
                            QuickAccessCard(item = item, onClick = { navController.navigateSafe(item.route) })
                        }
                    }
                }
            }

            if (content.liveNow.isNotEmpty()) {
                item {
                    ContentRailLabel(label = "Live TV")
                    LiveRail(
                        channels = content.liveNow,
                        onPlay = { ch ->
                            viewModel.setLiveZapForRail(content.liveNow, ch)
                            navController.navigateSafe(
                                Screen.Player.createRoute("live", ch.streamId.toString(), "ts")
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (content.topMovies.isNotEmpty()) {
                item {
                    ContentRailLabel(label = "Movies")
                    MovieRail(
                        movies = content.topMovies,
                        onPlay = { navController.navigateSafe(viewModel.playMovieRoute(it)) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (content.topSeries.isNotEmpty()) {
                item {
                    ContentRailLabel(label = "Series")
                    SeriesRail(
                        series = content.topSeries,
                        onPlay = { s ->
                            viewModel.playSeriesFromRail(
                                s,
                                onRoute = { navController.navigateSafe(it) }
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ── Hero Banner ─────────────────────────────────────────────────────────────────

@Composable
private fun HeroBanner(
    movies: List<XtreamMovie>,
    onPlay: (XtreamMovie) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    // Track focus on the banner Play button — pause auto-rotation while focused
    var isBannerFocused by remember { mutableStateOf(false) }

    // Auto-scroll every 5 seconds, paused when banner Play button has focus
    LaunchedEffect(movies.size) {
        while (movies.size > 1) {
            delay(5000)
            if (!isBannerFocused) {
                currentIndex = (currentIndex + 1) % movies.size
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(286.dp)
    ) {
        // Featured movie poster as background
        val movie = movies.getOrElse(currentIndex) { movies.first() }
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(movie.streamIcon)
                .crossfade(true)
                .size(640, 360)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCacheKey("hero_banner_${movie.streamId}")
                .build(),
            contentDescription = movie.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Gradient scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xF205070B), Color(0xB5070A10), BgBase.copy(alpha = 0.24f)),
                        startX = 0f,
                        endX = 860f
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, BgBase.copy(alpha = 0.96f)),
                        startY = 120f
                    )
                )
        )
        // Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.62f)
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            Text(
                text = "FEATURED",
                color = Accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = movie.name,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (movie.rating5Based > 0.0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                    Text(
                        text = "%.1f".format(movie.rating5Based * 2),
                        color = Color(0xFFFFC107),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            // Feed focus state into banner so auto-scroll pauses while focused
            SideEffect { isBannerFocused = isFocused }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isFocused) Accent else AccentSurface,
                modifier = Modifier
                    .focusable(interactionSource = interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null) { onPlay(movie) }
                    .then(if (isFocused) Modifier.border(2.dp, AccentBright, RoundedCornerShape(6.dp)) else Modifier)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = if (isFocused) Color.Black else AccentBright, modifier = Modifier.size(16.dp))
                    Text("Watch Now", color = if (isFocused) Color.Black else AccentBright, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Dot indicators
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            movies.forEachIndexed { idx, _ ->
                Box(
                    modifier = Modifier
                        .size(if (idx == currentIndex) 8.dp else 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (idx == currentIndex) Accent else TextTertiary)
                )
            }
        }
    }
}

// ── Content Rail helpers ────────────────────────────────────────────────────────

@Composable
private fun ContentRailLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun MovieRail(movies: List<XtreamMovie>, onPlay: (XtreamMovie) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(movies, key = { it.streamId }) { movie ->
            MoviePosterCard(
                name = movie.name,
                posterUrl = movie.streamIcon,
                rating = if (movie.rating5Based > 0.0) "%.1f".format(movie.rating5Based * 2) else null,
                onClick = { onPlay(movie) }
            )
        }
    }
}

@Composable
private fun LiveRail(channels: List<XtreamChannel>, onPlay: (XtreamChannel) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(channels, key = { it.streamId }) { ch ->
            ChannelCard(
                name = ch.name,
                logoUrl = ch.streamIcon,
                onClick = { onPlay(ch) }
            )
        }
    }
}

@Composable
private fun SeriesRail(series: List<XtreamSeries>, onPlay: (XtreamSeries) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(series, key = { it.seriesId }) { s ->
            MoviePosterCard(
                name = s.name,
                posterUrl = s.cover,
                rating = if (s.rating5Based > 0.0) "%.1f".format(s.rating5Based * 2) else null,
                onClick = { onPlay(s) }
            )
        }
    }
}

@Composable
private fun MoviePosterCard(
    name: String,
    posterUrl: String?,
    rating: String?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.04f else 1f, animationSpec = tween(150), label = "scale")
    Column(
        modifier = Modifier
            .width(110.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .then(if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface2)
        ) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(posterUrl)
                    // PERF: Decode at rail thumbnail size to prevent GC thrashing.
                    .size(110, 155)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (rating != null) {
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(10.dp))
                        Text(rating, color = Color(0xFFFFC107), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            color = if (isFocused) AccentBright else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun ChannelCard(name: String, logoUrl: String?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.04f else 1f, animationSpec = tween(150), label = "scale")
    Column(
        modifier = Modifier
            .width(90.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .then(if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BgSurface2),
            contentAlignment = Alignment.Center
        ) {
            if (!logoUrl.isNullOrBlank()) {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(logoUrl)
                        .crossfade(false)
                        .size(80, 80)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                )
            } else {
                Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(32.dp))
            }
            // LIVE badge
            Surface(
                color = Color(0xCCE53935),
                shape = RoundedCornerShape(bottomEnd = 6.dp),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            color = if (isFocused) AccentBright else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Header ─────────────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    activeAccountName: String?,
    navController: NavController
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Logo + app name — clean typographic wordmark
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.ic_brand_emblem),
                    contentDescription = "DYLANDOS IPTV Logo",
                    modifier = Modifier.size(40.dp)
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "DYLANDOS IPTV",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = "ULTIMATE",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }
            // Right side: search · notifications · account · settings
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HeaderIconButton(Icons.Default.Search, "Search") {
                    navController.navigateSafe(Screen.Search.route)
                }
                HeaderIconButton(Icons.Default.Notifications, "Notifications", badgeCount = 0) {
                    navController.navigateSafe(Screen.Settings.route)
                }
                HeaderIconButton(Icons.Default.AccountCircle, activeAccountName ?: "Account") {
                    navController.navigateSafe(Screen.Settings.route)
                }
                HeaderIconButton(Icons.Default.Settings, "Settings") {
                    navController.navigateSafe(Screen.Settings.route)
                }
            }
        }
    }
}

/** Round, focus-aware header icon button used in the Home top bar. */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isFocused) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                .then(if (isFocused) Modifier.border(1.5.dp, DylandosPalette.Cyan, CircleShape) else Modifier)
                .focusable(interactionSource = interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isFocused) DylandosPalette.Cyan else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B5C)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.coerceAtMost(99).toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Stats Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun HomeStatsRow(stats: HomeStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(
            icon = Icons.Default.Tv,
            label = "CHANNELS",
            count = stats.liveChannels,
            isLoading = stats.liveLoading,
            color = Color(0xFFFF3B5C),   // red tile
            modifier = Modifier.weight(1f)
        )
        StatItem(
            icon = Icons.Default.PlayCircleFilled,
            label = "MOVIES",
            count = stats.movies,
            isLoading = stats.moviesLoading,
            color = Color(0xFF8B5CF6),   // purple tile
            modifier = Modifier.weight(1f)
        )
        StatItem(
            icon = Icons.Default.Theaters,
            label = "TV SERIES",
            count = stats.series,
            isLoading = stats.seriesLoading,
            color = Color(0xFF3B9EFF),   // blue tile
            modifier = Modifier.weight(1f)
        )
        // Show error hint if stats failed (non-intrusive)
        if (stats.error != null) {
            Surface(
                color = Color(0x22FFAA00),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.4f)),
                modifier = Modifier.weight(1.2f)
            ) {
                Text(
                    text = stats.error,
                    color = StatusWarning,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    label: String,
    count: Int,
    isLoading: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF14141F).copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
        modifier = modifier.heightIn(min = 84.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.12f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = color,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "%,d".format(count),
                        color = TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSystemPulseRow() {
    val pulseItems = remember {
        listOf(
            SystemPulseItem(Icons.Default.PlayCircle, "LibVLC Player", "Media3 backup", AccentBlue),
            SystemPulseItem(Icons.Default.FiberDvr, "DVR", "Foreground service", StatusError),
            SystemPulseItem(Icons.Default.Replay10, "Timeshift", "Live rewind ready", StatusInfo),
            SystemPulseItem(Icons.Default.Keyboard, "Fire TV", "Remote mapped", StatusSuccess)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(BgBase, BgBabyBlue.copy(alpha = 0.82f), BgBase)
                )
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        pulseItems.forEach { item ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                color = item.color.copy(alpha = 0.10f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, item.color.copy(alpha = 0.32f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(item.color.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(item.icon, item.title, tint = item.color, modifier = Modifier.size(18.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = TextPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = item.subtitle,
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private data class SystemPulseItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val color: Color
)

// ── Top Navigation Row ─────────────────────────────────────────────────────────

@Composable
private fun HomeTopNav(navController: NavController) {
    val navItems = remember {
        listOf(
            QuickAccessItem("Live TV",    Icons.Default.Tv,                  Screen.LiveTV.route),
            QuickAccessItem("Guide",      Icons.Default.GridView,            Screen.Guide.route),
            QuickAccessItem("Movies",     Icons.Default.Movie,               Screen.Movies.route),
            QuickAccessItem("Series",     Icons.Default.Theaters,            Screen.Series.route),
            QuickAccessItem("DVR",        Icons.Default.RadioButtonChecked,  Screen.Dvr.route),
            QuickAccessItem("Favorites",  Icons.Default.Favorite,            Screen.Favorites.route),
            QuickAccessItem("Search",     Icons.Default.Search,              Screen.Search.route),
            QuickAccessItem("Settings",   Icons.Default.Settings,            Screen.Settings.route)
        )
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface2)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(navItems) { item ->
            TopNavButton(item = item, onClick = { navController.navigateSafe(item.route) })
        }
    }
}

@Composable
private fun TopNavButton(item: QuickAccessItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(150),
        label = "navScale"
    )
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isFocused) FocusBgStrong else Color.Transparent,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (isFocused) Modifier.border(2.5.dp, Accent, RoundedCornerShape(6.dp))
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = if (isFocused) AccentBright else TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = item.title,
                color = if (isFocused) AccentBright else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isFocused) FontWeight.ExtraBold else FontWeight.Normal
            )
        }
    }
}

// ── Quick Access Grid ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAccessCard(item: QuickAccessItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .aspectRatio(1.6f)
            .tvCardFocusable(),
        colors = CardDefaults.cardColors(containerColor = BgSurface2),
        shape = RoundedCornerShape(8.dp),
        border = CardDefaults.outlinedCardBorder().copy(width = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(BgSurface2, BgSurface3.copy(alpha = 0.88f))
                    )
                )
                .padding(14.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = Accent,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── Data ───────────────────────────────────────────────────────────────────────

data class QuickAccessItem(
    val title: String,
    val icon: ImageVector,
    val route: String
)

private val quickAccessItems = listOf(
    QuickAccessItem("Live TV",   Icons.Default.Tv,                 Screen.LiveTV.route),
    QuickAccessItem("Guide",     Icons.Default.GridView,           Screen.Guide.route),
    QuickAccessItem("Movies",    Icons.Default.Movie,              Screen.Movies.route),
    QuickAccessItem("Series",    Icons.Default.Theaters,           Screen.Series.route),
    QuickAccessItem("DVR",       Icons.Default.RadioButtonChecked, Screen.Dvr.route),
    QuickAccessItem("Favorites", Icons.Default.Favorite,          Screen.Favorites.route),
    QuickAccessItem("Search",    Icons.Default.Search,             Screen.Search.route),
    QuickAccessItem("Settings",  Icons.Default.Settings,           Screen.Settings.route)
)
