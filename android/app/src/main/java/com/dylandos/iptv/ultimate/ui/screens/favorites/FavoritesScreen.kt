package com.dylandos.iptv.ultimate.ui.screens.favorites

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class FavoriteDisplayItem(
    val favorite: FavoriteEntity,
    val title: String,
    val imageUrl: String?,
    val detail: String,
    val extension: String = "mp4",
    val available: Boolean = true
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val xtreamRepository: XtreamRepository
) : ViewModel() {

    /** All favorites streamed live from Room — updates automatically on add/remove. */
    val favorites: StateFlow<List<FavoriteEntity>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _displayItems = MutableStateFlow<List<FavoriteDisplayItem>>(emptyList())
    val displayItems: StateFlow<List<FavoriteDisplayItem>> = _displayItems.asStateFlow()
    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    init {
        viewModelScope.launch {
            favorites.collectLatest { entries -> resolveFavorites(entries) }
        }
    }

    private suspend fun resolveFavorites(entries: List<FavoriteEntity>) {
        if (entries.isEmpty()) {
            _displayItems.value = emptyList()
            return
        }
        _isResolving.value = true
        try {
            // Resolve by ID — never re-fetch full VOD/series catalogs (Firestick OOM).
            _displayItems.value = entries.map { favorite ->
                when (favorite.streamType) {
                    "live" -> {
                        val cached = xtreamRepository.getCachedLiveStreams()
                            ?.firstOrNull { it.streamId == favorite.streamId }
                        if (cached != null) {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = cached.name,
                                imageUrl = cached.streamIcon,
                                detail = if (cached.tvArchive == 1) "Live TV · Catch-up available" else "Live TV",
                                extension = "ts"
                            )
                        } else {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = "Channel ${favorite.streamId}",
                                imageUrl = null,
                                detail = "Live TV",
                                extension = "ts"
                            )
                        }
                    }
                    "vod" -> {
                        val remembered = xtreamRepository.getRememberedMovie(favorite.streamId)
                        val info = xtreamRepository.getVodInfo(favorite.streamId).getOrNull()
                        if (info != null) {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = info.movieData?.name ?: info.info.name ?: remembered?.name
                                    ?: "Movie ${favorite.streamId}",
                                imageUrl = info.info.movieImage ?: info.info.coverBig
                                    ?: remembered?.streamIcon,
                                detail = listOfNotNull(
                                    "Movie",
                                    info.info.rating5Based.takeIf { it > 0 }?.let { "%.1f".format(it) }
                                ).joinToString(" · "),
                                extension = info.movieData?.containerExtension
                                    ?: remembered?.containerExtension
                                    ?: "mp4"
                            )
                        } else if (remembered != null) {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = remembered.name,
                                imageUrl = remembered.streamIcon,
                                detail = "Movie",
                                extension = remembered.containerExtension ?: "mp4"
                            )
                        } else {
                            // Still openable — MovieDetail builds from get_vod_info / pending stash.
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = "Movie ${favorite.streamId}",
                                imageUrl = null,
                                detail = "Movie",
                                extension = "mp4",
                                available = true
                            )
                        }
                    }
                    "series" -> {
                        val remembered = xtreamRepository.getRememberedSeries(favorite.streamId)
                        val info = xtreamRepository.getSeriesInfo(favorite.streamId).getOrNull()
                        if (info != null) {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = info.info.name.ifBlank { remembered?.name ?: "Series ${favorite.streamId}" },
                                imageUrl = info.info.cover ?: remembered?.cover,
                                detail = listOfNotNull(
                                    "TV Series",
                                    info.info.genre?.substringBefore(',')?.trim()?.takeIf(String::isNotBlank),
                                    info.info.rating?.takeIf(String::isNotBlank)
                                ).joinToString(" · ")
                            )
                        } else if (remembered != null) {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = remembered.name,
                                imageUrl = remembered.cover,
                                detail = "TV Series"
                            )
                        } else {
                            FavoriteDisplayItem(
                                favorite = favorite,
                                title = "Series ${favorite.streamId}",
                                imageUrl = null,
                                detail = "TV Series",
                                available = true
                            )
                        }
                    }
                    else -> FavoriteDisplayItem(
                        favorite = favorite,
                        title = "Item ${favorite.streamId}",
                        imageUrl = null,
                        detail = favorite.streamType,
                        available = false
                    )
                }
            }
        } finally {
            _isResolving.value = false
        }
    }

    fun prepareLiveFavorite(streamId: Int) {
        val channels = xtreamRepository.getCachedLiveStreams().orEmpty()
        xtreamRepository.liveChannelList = channels
        xtreamRepository.liveChannelIndex = channels.indexOfFirst { it.streamId == streamId }
            .coerceAtLeast(0)
    }

    fun prepareMovieFavorite(streamId: Int) {
        val remembered = xtreamRepository.getRememberedMovie(streamId)
        if (remembered != null) {
            xtreamRepository.pendingOpenMovie = remembered
        }
    }

    fun prepareSeriesFavorite(seriesId: Int) {
        xtreamRepository.pendingOpenSeriesId = seriesId
        xtreamRepository.pendingOpenSeries = xtreamRepository.getRememberedSeries(seriesId)
    }

    fun removeFavorite(streamId: Int, streamType: String) {
        viewModelScope.launch {
            favoriteDao.deleteByStreamId(streamId, streamType)
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    navController: NavController,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val allItems by viewModel.displayItems.collectAsState()
    val isResolving by viewModel.isResolving.collectAsState()

    // Section tabs: All | Live TV | Movies | Series
    val tabs = listOf("All", "Live TV", "Movies", "Series")
    var selectedTab by remember { mutableIntStateOf(0) }

    val displayList = when (selectedTab) {
        1 -> allItems.filter { it.favorite.streamType == "live" }
        2 -> allItems.filter { it.favorite.streamType == "vod" }
        3 -> allItems.filter { it.favorite.streamType == "series" }
        else -> allItems
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── App Bar ──────────────────────────────────────────────────────
            TopAppBar(
                title = {
                    Text(
                        "Favorites",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Accent
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = Accent
                )
            )

            // ── Section Tabs ─────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, label ->
                    val selected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .dylandosFocusable(onClick = { selectedTab = index })
                            .background(
                                if (selected) AccentSurface else BgSurface2,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (selected) 1.dp else 0.dp,
                                color = if (selected) Accent else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            label,
                            color = if (selected) Accent else TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ── List content ─────────────────────────────────────────────────
            if (isResolving && displayList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            } else if (displayList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No favorites yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Long-press any channel, movie, or series\nto add it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                        // S-013: CTA button so first-time users can immediately browse content
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { navController.popBackStack() },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Default.Explore, null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Browse Content", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text(
                    text = "MY FAVORITES",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    gridItems(displayList, key = { "${it.favorite.streamId}_${it.favorite.streamType}" }) { item ->
                        FavoritePoster(
                            item = item,
                            onPlay      = {
                                if (!item.available) return@FavoritePoster
                                when (item.favorite.streamType) {
                                    "live" -> {
                                        viewModel.prepareLiveFavorite(item.favorite.streamId)
                                        navController.navigateSafe(
                                            Screen.Player.createRoute(
                                                "live",
                                                item.favorite.streamId.toString(),
                                                "ts"
                                            )
                                        )
                                    }
                                    "vod" -> {
                                        viewModel.prepareMovieFavorite(item.favorite.streamId)
                                        navController.navigateSafe(
                                            Screen.MovieDetail.createRoute(item.favorite.streamId)
                                        )
                                    }
                                    "series" -> {
                                        viewModel.prepareSeriesFavorite(item.favorite.streamId)
                                        navController.navigateSafe(Screen.Series.route)
                                    }
                                }
                            },
                            onRemove    = {
                                viewModel.removeFavorite(
                                    item.favorite.streamId,
                                    item.favorite.streamType
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Poster card ─────────────────────────────────────────────────────────────────

@Composable
private fun FavoritePoster(
    item: FavoriteDisplayItem,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "fav_scale"
    )
    val typeLabel = when (item.favorite.streamType) {
        "live"   -> "Live TV"
        "vod"    -> "Movies"
        "series" -> "Series"
        else     -> "Channel"
    }

    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(BgSurface3)
                .dylandosFocusable(onClick = onPlay)
        ) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.imageUrl?.takeIf { it.isNotBlank() })
                    .diskCacheKey("favorite_${item.favorite.streamType}_${item.favorite.streamId}")
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .size(180, 270)
                    .build(),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Focus ring
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(3.dp, DylandosPalette.Cyan, RoundedCornerShape(12.dp))
                )
            }

            // Filled red heart badge (top-right) — click to remove
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC101018))
                    .dylandosFocusable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Remove from Favorites",
                    tint = Color(0xFFE50914),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Bottom scrim so titles baked into posters stay legible
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            color = if (isFocused) DylandosPalette.Cyan else if (item.available) TextPrimary else TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = typeLabel,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
