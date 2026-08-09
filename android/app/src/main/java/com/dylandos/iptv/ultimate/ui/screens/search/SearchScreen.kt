package com.dylandos.iptv.ultimate.ui.screens.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusGroup
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.theme.*

private data class PendingListItem(val id: Int, val type: String, val title: String, val artwork: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    // S-023: Recent searches — in-memory list maintained by ViewModel
    val recentSearches by viewModel.recentSearches.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val customLists by viewModel.customLists.collectAsState()
    var pendingListItem by remember { mutableStateOf<PendingListItem?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { viewModel.loadContent() }
    LaunchedEffect(Unit) {
        viewModel.favoriteMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    val tabs = listOf(
        "All"    to (results.channels.size + results.movies.size + results.series.size),
        "Live"   to results.channels.size,
        "Movies" to results.movies.size,
        "Series" to results.series.size
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { viewModel.setQuery(it) },
                        placeholder = { Text("Search channels, movies, series…", color = TextTertiary) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Accent) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(Icons.Default.Close, "Clear", tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = BgSurface2,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedContainerColor = BgSurface,
                            unfocusedContainerColor = BgSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter ||
                                     event.key == Key.DirectionCenter ||
                                     event.key == Key.MediaPlayPause)
                                ) {
                                    keyboardController?.hide()
                                    false // let the TextField process the key too
                                } else false
                            }
                    )
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

            if (query.isNotBlank()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = BgSurface2,
                    contentColor = Accent,
                    edgePadding = 0.dp
                ) {
                    tabs.forEachIndexed { idx, (label, count) ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = {
                                Text(
                                    text = if (count > 0) "$label ($count)" else label,
                                    color = if (selectedTab == idx) Accent else TextSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }

            when {
                results.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                query.isBlank() -> {
                    // S-023: Show recent searches when available, otherwise show placeholder
                    if (recentSearches.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Recent Searches", color = TextSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                            items(recentSearches) { term ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setQuery(term) }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.History, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
                                    Text(term, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextTertiary,
                                        modifier = Modifier.size(14.dp))
                                }
                                Divider(color = BorderDefault, thickness = 0.5.dp)
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Search, null, tint = TextTertiary, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("Type to search", color = TextTertiary, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                results.channels.isEmpty() && results.movies.isEmpty() && results.series.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, tint = TextTertiary, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No results for \"$query\"", color = TextTertiary, style = MaterialTheme.typography.titleMedium)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .dylandosFocusGroup(trapExit = false),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        val showChannels = selectedTab == 0 || selectedTab == 1
                        val showMovies   = selectedTab == 0 || selectedTab == 2
                        val showSeries   = selectedTab == 0 || selectedTab == 3

                        if (showChannels && results.channels.isNotEmpty()) {
                            item { SearchSectionHeader(label = "Live TV (${results.channels.size})", icon = Icons.Default.Tv, iconTint = Accent) }
                            items(results.channels, key = { "ch_${it.streamId}" }) { ch ->
                                ChannelResultRow(
                                    channel = ch,
                                    isFavorite = "live:${ch.streamId}" in favorites,
                                    onToggleFavorite = { viewModel.toggleFavorite(ch.streamId, "live", ch.name) },
                                    onAddToList = { pendingListItem = PendingListItem(ch.streamId, "live", ch.name, ch.streamIcon) }
                                ) {
                                    viewModel.prepareChannelPlayback(ch)
                                    navController.navigate(
                                        Screen.Player.createRoute("live", ch.streamId.toString(), "ts")
                                    )
                                }
                            }
                        }
                        if (showMovies && results.movies.isNotEmpty()) {
                            item { SearchSectionHeader(label = "Movies (${results.movies.size})", icon = Icons.Default.Movie, iconTint = StatusSuccess) }
                            items(results.movies, key = { "mv_${it.streamId}" }) { movie ->
                                MovieResultRow(
                                    movie = movie,
                                    isFavorite = "vod:${movie.streamId}" in favorites,
                                    onToggleFavorite = { viewModel.toggleFavorite(movie.streamId, "vod", movie.name) },
                                    onAddToList = { pendingListItem = PendingListItem(movie.streamId, "vod", movie.name, movie.streamIcon) }
                                ) {
                                    // Stash the search hit so MovieDetail never depends on full-catalog cache.
                                    viewModel.prepareMovieDetail(movie)
                                    navController.navigate(Screen.MovieDetail.createRoute(movie.streamId))
                                }
                            }
                        }
                        if (showSeries && results.series.isNotEmpty()) {
                            item { SearchSectionHeader(label = "Series (${results.series.size})", icon = Icons.Default.Theaters, iconTint = StatusInfo) }
                            items(results.series, key = { "sr_${it.seriesId}" }) { s ->
                                SeriesResultRow(
                                    series = s,
                                    isFavorite = "series:${s.seriesId}" in favorites,
                                    onToggleFavorite = { viewModel.toggleFavorite(s.seriesId, "series", s.name) },
                                    onAddToList = { pendingListItem = PendingListItem(s.seriesId, "series", s.name, s.cover) }
                                ) {
                                    // Open Series detail with full Xtream metadata (plot/cast/seasons).
                                    // Prefer SeriesDetail route over Series grid + pending-open race.
                                    viewModel.setAutoOpenSeries(s)
                                    navController.navigate(Screen.SeriesDetail.createRoute(s.seriesId))
                                }
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
    pendingListItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingListItem = null },
            title = { Text("Add to My List") },
            text = {
                if (customLists.isEmpty()) Text("Create a list from My Lists first.")
                else Column {
                    customLists.forEach { list ->
                        TextButton(onClick = {
                            viewModel.addToCustomList(list.id, item.id, item.type, item.title, item.artwork)
                            pendingListItem = null
                        }) { Text(list.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pendingListItem = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SearchSectionHeader(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider(color = BgSurface2, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun ChannelResultRow(channel: XtreamChannel, isFavorite: Boolean, onToggleFavorite: () -> Unit, onAddToList: () -> Unit, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FocusBgStrong else Color.Transparent)
            .then(if (isFocused) Modifier.border(1.dp, Accent) else Modifier)
            .onKeyEvent { event ->
                when {
                    event.isRemoteConfirmKey() -> { onClick(); true }
                    event.type == KeyEventType.KeyUp && event.key == Key.Menu -> { onToggleFavorite(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(BgSurface2), contentAlignment = Alignment.Center) {
            if (!channel.streamIcon.isNullOrBlank()) {
                AsyncImage(model = channel.streamIcon, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = if (isFocused) AccentBright else TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(color = Color(0xCCE53935), shape = RoundedCornerShape(4.dp)) {
                Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        Icon(Icons.Default.Info, null, tint = if (isFocused) Accent else TextTertiary, modifier = Modifier.size(20.dp))
        FavoriteAction(isFavorite, onToggleFavorite)
        ListAction(onAddToList)
    }
}

@Composable
private fun MovieResultRow(movie: XtreamMovie, isFavorite: Boolean, onToggleFavorite: () -> Unit, onAddToList: () -> Unit, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val metaBits = buildList {
        add("Movie")
        movie.rating5Based.takeIf { it > 0.0 }?.let { add("%.1f★".format(it * 2)) }
        movie.containerExtension?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
    }.joinToString(" • ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FocusBgStrong else Color.Transparent)
            .then(if (isFocused) Modifier.border(1.dp, Accent) else Modifier)
            .onKeyEvent { event ->
                when {
                    event.isRemoteConfirmKey() -> { onClick(); true }
                    event.type == KeyEventType.KeyUp && event.key == Key.Menu -> { onToggleFavorite(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(6.dp)).background(BgSurface2)) {
            AsyncImage(model = movie.streamIcon, contentDescription = movie.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(movie.name, color = if (isFocused) AccentBright else TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                metaBits,
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "OK → full details (plot, cast, rating)",
                color = if (isFocused) Accent else TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.Info, null, tint = if (isFocused) Accent else TextTertiary, modifier = Modifier.size(20.dp))
        FavoriteAction(isFavorite, onToggleFavorite)
        ListAction(onAddToList)
    }
}

@Composable
private fun SeriesResultRow(series: XtreamSeries, isFavorite: Boolean, onToggleFavorite: () -> Unit, onAddToList: () -> Unit, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) FocusBgStrong else Color.Transparent)
            .then(if (isFocused) Modifier.border(1.dp, Accent) else Modifier)
            .onKeyEvent { event ->
                when {
                    event.isRemoteConfirmKey() -> { onClick(); true }
                    event.type == KeyEventType.KeyUp && event.key == Key.Menu -> { onToggleFavorite(); true }
                    else -> false
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(6.dp)).background(BgSurface2)) {
            AsyncImage(model = series.cover, contentDescription = series.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(series.name, color = if (isFocused) AccentBright else TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildList {
                    add("Series")
                    series.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" • "),
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "OK → full details (plot, cast, seasons)",
                color = if (isFocused) Accent else TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.Info, null, tint = if (isFocused) Accent else TextTertiary, modifier = Modifier.size(20.dp))
        FavoriteAction(isFavorite, onToggleFavorite)
        ListAction(onAddToList)
    }
}

@Composable
private fun FavoriteAction(isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    IconButton(onClick = onToggleFavorite) {
        Icon(
            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
            tint = if (isFavorite) StatusError else TextSecondary
        )
    }
}

@Composable
private fun ListAction(onAddToList: () -> Unit) {
    IconButton(onClick = onAddToList) {
        Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to My List", tint = TextSecondary)
    }
}

