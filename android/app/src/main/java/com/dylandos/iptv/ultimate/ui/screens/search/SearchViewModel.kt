package com.dylandos.iptv.ultimate.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.dao.CustomListDao
import com.dylandos.iptv.ultimate.data.db.entity.CustomListItemEntity
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.buildSeriesPlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchResults(
    val channels: List<XtreamChannel> = emptyList(),
    val movies: List<XtreamMovie> = emptyList(),
    val series: List<XtreamSeries> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val favoriteDao: FavoriteDao,
    private val customListDao: CustomListDao
) : ViewModel() {
    private val providerKey = xtreamRepository.currentProviderKey.ifBlank { "local" }
    private val profileId = "default"

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    // S-023: In-memory recent searches (last 10, cleared on app restart — no DataStore overhead)
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    val favorites: StateFlow<Set<String>> = favoriteDao.getAllFavorites()
        .map { rows -> rows.mapTo(mutableSetOf()) { "${it.streamType}:${it.streamId}" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _favoriteMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val favoriteMessage = _favoriteMessage.asSharedFlow()

    val customLists = customListDao.observeLists(providerKey, profileId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite(streamId: Int, streamType: String, title: String) {
        viewModelScope.launch {
            runCatching { favoriteDao.toggle(streamId, streamType) }
                .onSuccess { added ->
                    _favoriteMessage.emit(if (added) "$title added to Favorites" else "$title removed from Favorites")
                }
                .onFailure { _favoriteMessage.emit("Could not update Favorites") }
        }
    }

    fun addToCustomList(listId: String, streamId: Int, streamType: String, title: String, artwork: String?) {
        viewModelScope.launch {
            runCatching {
                customListDao.addItem(CustomListItemEntity(
                    listId = listId, contentType = streamType, contentId = streamId.toString(),
                    providerKey = providerKey, profileId = profileId,
                    fallbackTitle = title, fallbackArtworkUrl = artwork
                ))
            }.onSuccess { inserted ->
                _favoriteMessage.emit(if (inserted >= 0) "$title added to list" else "$title is already in that list")
            }.onFailure { _favoriteMessage.emit("Could not add item to list") }
        }
    }

    // All content loaded once for instant search
    private var allChannels: List<XtreamChannel> = emptyList()
    private var allMovies: List<XtreamMovie> = emptyList()
    private var allSeries: List<XtreamSeries> = emptyList()
    private var contentLoaded = false
    private var contentLoading = false

    init {
        @OptIn(FlowPreview::class)
        _query
            .debounce(500) // 500 ms prevents DB/memory spam on every keystroke (was 300)
            .onEach { q -> performSearch(q.trim()) }
            .launchIn(viewModelScope)
    }

    fun setQuery(q: String) { _query.value = q }

    fun loadContent() {
        if (contentLoaded || contentLoading) return
        contentLoading = true
        viewModelScope.launch {
            _results.value = _results.value.copy(isLoading = true)
            try {
                allChannels = contentFilterRepository
                    .filterChannels(xtreamRepository.getLiveStreams().getOrDefault(emptyList()))
                allMovies = contentFilterRepository
                    .filterMovies(xtreamRepository.getVodStreams().getOrDefault(emptyList()))
                allSeries = contentFilterRepository
                    .filterSeries(xtreamRepository.getSeries().getOrDefault(emptyList()))
                contentLoaded = true
                _results.value = _results.value.copy(isLoading = false)
                if (_query.value.isNotBlank()) performSearch(_query.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _results.value = _results.value.copy(isLoading = false, error = e.message)
            } finally {
                contentLoading = false
            }
        }
    }

    private suspend fun performSearch(query: String) {
        if (query.isBlank()) {
            _results.value = SearchResults()
            return
        }
        val q = query.lowercase()
        val matches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SearchResults(
            channels = allChannels.asSequence().filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.streamId }.take(50).toList(),
            movies   = allMovies.asSequence().filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.streamId }.take(50).toList(),
            series   = allSeries.asSequence().filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.seriesId }.take(50).toList(),
            isLoading = false
        ) }
        if (_query.value.trim() != query.trim()) return
        _results.value = matches
        // S-023: Save to recent searches list (keep last 10 unique, most recent first)
        if (query.length >= 2) {
            val current = _recentSearches.value.toMutableList()
            current.remove(query)
            current.add(0, query)
            _recentSearches.value = current.take(10)
        }
    }

    /** Use full channel list for zapping (same as live TV + home rails). */
    fun prepareChannelPlayback(ch: XtreamChannel) {
        val idx = allChannels.indexOfFirst { it.streamId == ch.streamId }
        xtreamRepository.liveChannelList = allChannels
        xtreamRepository.liveChannelIndex = if (idx >= 0) idx else 0
    }

    fun playerRouteVod(movie: XtreamMovie): String =
        Screen.Player.createRoute("vod", movie.streamId.toString(), movie.containerExtension ?: "mp4")

    fun openSeriesInPlayer(series: XtreamSeries, onRoute: (String) -> Unit, onFailure: (() -> Unit)? = null) {
        viewModelScope.launch {
            val route = buildSeriesPlayerRoute(series, xtreamRepository, watchHistoryDao)
            if (route != null) onRoute(route)
            else onFailure?.invoke()
        }
    }

    /**
     * Set a pending series on the repository so that when SeriesScreen
     * loads (or is already loaded), it auto-opens the detail popup for this series.
     * Called just before navigating to Screen.Series from search results.
     */
    fun prepareMovieDetail(movie: XtreamMovie) {
        xtreamRepository.pendingOpenMovie = movie
        xtreamRepository.rememberMovies(listOf(movie))
    }

    fun setAutoOpenSeries(series: XtreamSeries) {
        xtreamRepository.pendingOpenSeriesId = series.seriesId
        xtreamRepository.pendingOpenSeries = series
        xtreamRepository.rememberSeries(listOf(series))
    }

    fun setAutoOpenSeries(seriesId: Int) {
        xtreamRepository.pendingOpenSeriesId = seriesId
        xtreamRepository.pendingOpenSeries = xtreamRepository.getRememberedSeries(seriesId)
    }
}
