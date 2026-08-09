package com.dylandos.iptv.ultimate.ui.screens.movies

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * DYLANDOS IPTV ULTIMATE — Movie Detail Screen
 *
 * Full-screen detail view for a selected movie.
 *
 * Layout (landscape / Firestick):
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  [Backdrop full-width, dimmed]                                  │
 * │  ┌─ Poster ─┐  Title          ★ 8.4   2024 · Action · 2h 3m   │
 * │  │ 2:3 art  │  Plot / description (4 lines max)                 │
 * │  │          │  Director: …  Cast: …                             │
 * │  └──────────┘  [▶ PLAY]  [↩ BACK]                              │
 * ├─────────────────────────────────────────────────────────────────┤
 * │  Similar Titles ──────────────────────────────────────────────  │
 * │  [Poster][Poster][Poster][Poster][Poster]...                    │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * D-pad:
 *  - Play button: Enter/OK plays the movie
 *  - Back button / TV Back key: returns to Movies grid
 *  - RIGHT from Play → Back; LEFT from Back → Play
 *  - DOWN from action row → Similar Titles rail
 *  - LEFT/RIGHT in rail → navigate posters; OK → navigate to that movie's detail
 */
@Composable
fun MovieDetailScreen(
    navController: NavController,
    viewModel: MovieDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val playFR    = remember { FocusRequester() }
    val backFR    = remember { FocusRequester() }   // LOW-014: programmatic focus for Back button
    val similarFR = remember { FocusRequester() }   // HIGH-003: only attached to index-0 card

    LaunchedEffect(viewModel.selectedStreamId) {
        viewModel.loadDetails(viewModel.selectedStreamId)
    }

    // Navigate to player when state fires
    LaunchedEffect(uiState.navigateToPlayer) {
        uiState.navigateToPlayer?.let { route ->
            viewModel.onNavigated()
            navController.navigateSafe(route)
        }
    }

    // Auto-focus Play button once content loads
    LaunchedEffect(uiState.movie) {
        if (uiState.movie != null) {
            kotlinx.coroutines.delay(120)
            runCatching { playFR.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
    ) {
        when {
            uiState.movie == null && uiState.isLoadingInfo -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            }

            uiState.movie == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Movie not found", color = TextSecondary)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { navController.popBackStack() },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                            Text("Go Back")
                        }
                    }
                }
            }

            else -> {
                val movie  = uiState.movie!!
                val info   = uiState.info
                val ctx    = LocalContext.current

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // ── Hero: backdrop + poster + metadata ───────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                    ) {
                        // Backdrop image (first backdrop_path, or stream_icon as fallback)
                        val backdropUrl = info?.backdropPath?.firstOrNull()
                            ?: info?.coverBig
                            ?: movie.streamIcon
                        AsyncImage(
                            model = ImageRequest.Builder(ctx)
                                .data(backdropUrl?.takeIf { it.isNotBlank() })
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = ColorPainter(BgSurface2),
                            error = ColorPainter(BgSurface2),
                            fallback = ColorPainter(BgSurface2),
                        )
                        // Gradient scrim over backdrop for readability
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color(0x99000000),
                                        0.5f to Color(0xBB000000),
                                        1f to BgBase
                                    )
                                )
                        )
                        // Left-side scrim so poster is readable
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color(0x00000000),
                                        0.35f to Color(0x00000000),
                                        1f to Color(0xDD000000)
                                    )
                                )
                        )

                        // Content row: poster (left) + metadata (right)
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // Poster
                            AsyncImage(
                                model = ImageRequest.Builder(ctx)
                                    .data(movie.streamIcon?.takeIf { it.isNotBlank() })
                                    .crossfade(true)
                                    .build(),
                                contentDescription = movie.name,
                                modifier = Modifier
                                    .width(110.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.5.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                                placeholder = ColorPainter(BgSurface3),
                                error = ColorPainter(BgSurface3),
                                fallback = ColorPainter(BgSurface3),
                            )

                            // Metadata column
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Title
                                Text(
                                    text = movie.name,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 22.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Badges row: rating | year | genre | duration
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val rating = info?.rating5Based?.takeIf { it > 0 }
                                        ?: movie.rating5Based.takeIf { it > 0 }
                                    if (rating != null && rating > 0.0) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                tint = StatusWarning,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = "%.1f".format(rating),
                                                color = StatusWarning,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                    val year = info?.releaseDate?.take(4)
                                    if (!year.isNullOrBlank()) {
                                        MetaBadge(year)
                                    }
                                    val genre = info?.genre?.split(",")?.firstOrNull()?.trim()
                                    if (!genre.isNullOrBlank()) {
                                        MetaBadge(genre)
                                    }
                                    val mpaaRating = info?.mpaaRating?.takeIf { it.isNotBlank() }
                                    if (mpaaRating != null) {
                                        MetaBadge(mpaaRating)
                                    }
                                    val dur = info?.duration?.trim()
                                    if (!dur.isNullOrBlank()) {
                                        MetaBadge(dur)
                                    }
                                }

                                // Plot / description
                                val plot = info?.plot?.trim()?.ifBlank { null }
                                    ?: info?.description?.trim()?.ifBlank { null }
                                if (!plot.isNullOrBlank()) {
                                    Text(
                                        text = plot,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Director
                                val director = info?.director?.trim()?.ifBlank { null }
                                if (!director.isNullOrBlank()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Director:", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text(director, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }

                                // Cast
                                val cast = (info?.cast ?: info?.actors)?.trim()?.ifBlank { null }
                                if (!cast.isNullOrBlank()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Cast:", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text(cast, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Action buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // PLAY button
                                    DetailActionButton(
                                        label = "▶  PLAY",
                                        isPrimary = true,
                                        focusRequester = playFR,
                                        onRight = { runCatching { backFR.requestFocus() } },  // LOW-014
                                        onLeft  = { /* nothing at left edge */ },
                                        onClick = { viewModel.play() }
                                    )

                                    // BACK button — LOW-014: backFR allows programmatic focus
                                    DetailActionButton(
                                        label = "↩  BACK",
                                        isPrimary = false,
                                        focusRequester = backFR,
                                        onLeft  = { runCatching { playFR.requestFocus() } },
                                        onRight = { /* nothing at right edge */ },
                                        onClick = { navController.popBackStack() }
                                    )

                                    // FAVORITE toggle button
                                    DetailActionButton(
                                        label = if (isFavorite) "★  UNFAVE" else "☆  FAVE",
                                        isPrimary = false,
                                        focusRequester = null,
                                        onLeft  = { runCatching { backFR.requestFocus() } },
                                        onRight = { /* nothing at right edge */ },
                                        onClick = { viewModel.toggleFavorite() }
                                    )

                                    // Retry info load if it failed OR if server returned empty info
                                    if ((!uiState.isLoadingInfo && uiState.error != null) ||
                                        (!uiState.isLoadingInfo && uiState.infoEmpty)) {
                                        DetailActionButton(
                                            label = if (uiState.infoEmpty) "⟳  RELOAD INFO" else "⟳  RETRY",
                                            isPrimary = false,
                                            focusRequester = null,
                                            onLeft  = { /* nothing */ },
                                            onRight = { /* nothing */ },
                                            onClick = { viewModel.loadDetails(force = true) }
                                        )
                                    }
                                }
                            }
                        }

                        // Back arrow top-left (remote-accessible)
                        IconButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }

                        // Loading spinner over info area
                        if (uiState.isLoadingInfo) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp),
                                color = Accent,
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    // ── Similar Titles rail ──────────────────────────────────
                    if (uiState.similarMovies.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Recommended For You",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "Ranked by franchise, category, rating and catalog quality",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                        )

                        val railState = rememberLazyListState()
                        LazyRow(
                            state = railState,
                            contentPadding = PaddingValues(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                            // HIGH-003: Do NOT add focusRequester(similarFR) here.
                            // Adding it to BOTH the container LazyRow AND the first child card
                            // causes a duplicate FocusRequester assignment → IllegalStateException
                            // on Compose BOM 2024.12.01+. Focus is correctly placed on the first
                            // card via SimilarMovieCard(focusRequester = if (index == 0) similarFR).
                        ) {
                            itemsIndexed(uiState.similarMovies) { index, similar ->
                                SimilarMovieCard(
                                    movie = similar,
                                    focusRequester = if (index == 0) similarFR else null,
                                    onClick = {
                                        // Navigate to the detail screen of the similar title
                                        navController.navigateSafe("movie_detail/${similar.streamId}")
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

// ── Action Button ─────────────────────────────────────────────────────────────

@Composable
private fun DetailActionButton(
    label: String,
    isPrimary: Boolean,
    focusRequester: FocusRequester?,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "btnScale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isPrimary  -> Color(0xFFE50914)            // solid red PLAY
                    isFocused  -> Color(0x26FFFFFF)            // ghost button focus fill
                    else       -> Color(0x14FFFFFF)            // ghost button rest
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isPrimary -> Color(0xFFE50914)
                    isFocused -> DylandosPalette.Cyan
                    else      -> Color(0x33FFFFFF)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interactionSource)
            .clickable { onClick() }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft  -> { onLeft();  true }
                    Key.DirectionRight -> { onRight(); true }
                    Key.Enter, Key.DirectionCenter -> { onClick(); true }
                    else -> false
                }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPrimary) Color.White else if (isFocused) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

// ── Meta Badge ────────────────────────────────────────────────────────────────

@Composable
private fun MetaBadge(text: String) {
    Box(
        modifier = Modifier
            .background(BgSurface3, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = TextSecondary, fontSize = 11.sp)
    }
}

// ── Similar Movie Card ────────────────────────────────────────────────────────

@Composable
private fun SimilarMovieCard(
    movie: XtreamMovie,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "simScale"
    )
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusable(interactionSource = interactionSource)
                .clickable { onClick() }
                .border(
                    width = if (isFocused) 2.dp else 0.dp,
                    color = if (isFocused) Accent else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(movie.streamIcon?.takeIf { it.isNotBlank() })
                    .crossfade(true)
                    .build(),
                contentDescription = movie.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(BgSurface3),
                error = ColorPainter(BgSurface3),
                fallback = ColorPainter(BgSurface3),
            )
            if (isFocused) {
                Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.12f)))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = movie.name,
            color = if (isFocused) Accent else TextSecondary,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
