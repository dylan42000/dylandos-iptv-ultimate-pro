package com.dylandos.iptv.ultimate.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.buildSeriesPlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

data class HomeStats(
    val liveChannels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val liveLoading: Boolean = true,
    val moviesLoading: Boolean = true,
    val seriesLoading: Boolean = true,
    val isLoading: Boolean = true,
    val isLoaded: Boolean = false,
    val error: String? = null
)

enum class HomeSectionStatus { IDLE, LOADING, LOADED, ERROR }

/** Lightweight home content for Firestick dashboard + compact rails. */
data class HomeContent(
    val featured: List<XtreamMovie> = emptyList(),
    val liveNow: List<XtreamChannel> = emptyList(),
    val topMovies: List<XtreamMovie> = emptyList(),
    val topSeries: List<XtreamSeries> = emptyList(),
    val recentMovies: List<XtreamMovie> = emptyList(),
    val moviesLabel: String = "Recently added movies",
    val seriesLabel: String = "Recently added series",
    val liveStatus: HomeSectionStatus = HomeSectionStatus.IDLE,
    val moviesStatus: HomeSectionStatus = HomeSectionStatus.IDLE,
    val seriesStatus: HomeSectionStatus = HomeSectionStatus.IDLE,
    val isLoaded: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val ratings: com.dylandos.iptv.ultimate.data.network.MediaRatingsRepository
) : ViewModel() {

    private val _stats = MutableStateFlow(HomeStats())
    val stats: StateFlow<HomeStats> = _stats.asStateFlow()

    private val _content = MutableStateFlow(HomeContent())
    val content: StateFlow<HomeContent> = _content.asStateFlow()
    private var loadJob: Job? = null
    private var discoverySignature = ""

    fun loadHomeData(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val signature = ratings.preferences.getString("language", "en").orEmpty() + ratings.preferences.getString("tmdb_key", "").orEmpty().hashCode()
        val refresh = force || signature != discoverySignature
        discoverySignature = signature
        if (!refresh && _stats.value.isLoaded && _content.value.isLoaded) return
        loadJob = viewModelScope.launch {
            if (!xtreamRepository.isConnected) {
                _stats.value = HomeStats(
                    liveLoading = false,
                    moviesLoading = false,
                    seriesLoading = false,
                    isLoading = false,
                    isLoaded = false,
                    error = "Not connected. Login from Settings to load Home content."
                )
                return@launch
            }

            _stats.update {
                it.copy(
                    liveLoading = refresh || _content.value.liveStatus != HomeSectionStatus.LOADED,
                    moviesLoading = refresh || _content.value.moviesStatus != HomeSectionStatus.LOADED,
                    seriesLoading = refresh || _content.value.seriesStatus != HomeSectionStatus.LOADED,
                    isLoading = true,
                    error = null
                )
            }

            supervisorScope {
                if (refresh || _content.value.liveStatus != HomeSectionStatus.LOADED) launch { loadLiveSection() }
                if (refresh || _content.value.moviesStatus != HomeSectionStatus.LOADED) launch { loadMoviesSection() }
                if (refresh || _content.value.seriesStatus != HomeSectionStatus.LOADED) launch { loadSeriesSection() }
            }

            val allLoaded = listOf(
                _content.value.liveStatus,
                _content.value.moviesStatus,
                _content.value.seriesStatus
            ).all { it == HomeSectionStatus.LOADED }
            _content.update { it.copy(isLoaded = allLoaded) }
            _stats.update {
                it.copy(
                    isLoading = false,
                    isLoaded = allLoaded,
                    error = if (allLoaded) null else "Some Home sections could not load. Reopen Home to retry only the missing sections."
                )
            }
        }
    }

    private suspend fun loadLiveSection() {
        _content.update { it.copy(liveStatus = HomeSectionStatus.LOADING) }
        fetchWithRetry("Live TV", 30_000L) { xtreamRepository.getLiveStreams().getOrThrow() }
            .onSuccess { raw ->
                val filtered = withContext(Dispatchers.Default) { contentFilterRepository.filterChannels(raw) }
                val rail = withContext(Dispatchers.Default) { filtered.sortedBy { it.num }.take(HOME_RAIL_LIMIT) }
                _content.update { it.copy(liveNow = rail, liveStatus = HomeSectionStatus.LOADED) }
                _stats.update { it.copy(liveChannels = filtered.size, liveLoading = false) }
                Timber.d("Home Live TV loaded: %d", filtered.size)
            }
            .onFailure { error ->
                Timber.e(error, "Home Live TV failed")
                _content.update { it.copy(liveStatus = HomeSectionStatus.ERROR) }
                _stats.update { it.copy(liveLoading = false) }
            }
    }

    private suspend fun loadMoviesSection() {
        _content.update { it.copy(moviesStatus = HomeSectionStatus.LOADING) }
        fetchWithRetry("Movies", 60_000L) { xtreamRepository.getVodStreams().getOrThrow() }
            .onSuccess { raw ->
                val filtered = withContext(Dispatchers.Default) { contentFilterRepository.filterMovies(raw) }
                val rails = withContext(Dispatchers.Default) {
                    val top = filtered.sortedByDescending { it.added?.toLongOrNull() ?: 0L }.take(HOME_RAIL_LIMIT)
                    val recent = filtered.asSequence()
                        .filter { !it.added.isNullOrBlank() }
                        .sortedByDescending { it.added }
                        .take(HOME_RAIL_LIMIT)
                        .toList()
                    Pair(top, recent)
                }
                _content.update {
                    it.copy(
                        topMovies = rails.first,
                        moviesLabel = "Recently added movies",
                        recentMovies = rails.second,
                        featured = emptyList(),
                        moviesStatus = HomeSectionStatus.LOADED
                    )
                }
                _stats.update { it.copy(movies = filtered.size, moviesLoading = false) }
                try {
                    val discovered = ratings.discover("movie")
                    val ranked = withContext(Dispatchers.Default) { ratings.matchAvailable(discovered, filtered) { it.name } }
                    if (ranked.isNotEmpty()) _content.update { it.copy(topMovies = ranked, moviesLabel = "Top rated recent movies · TMDB") }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { /* The provider catalog remains usable offline. */ }
                Timber.d("Home Movies loaded: %d", filtered.size)
            }
            .onFailure { error ->
                Timber.e(error, "Home Movies failed")
                _content.update { it.copy(moviesStatus = HomeSectionStatus.ERROR) }
                _stats.update { it.copy(moviesLoading = false) }
            }
    }

    private suspend fun loadSeriesSection() {
        _content.update { it.copy(seriesStatus = HomeSectionStatus.LOADING) }
        fetchWithRetry("Series", 60_000L) { xtreamRepository.getSeries().getOrThrow() }
            .onSuccess { raw ->
                val filtered = withContext(Dispatchers.Default) { contentFilterRepository.filterSeries(raw) }
                val rail = withContext(Dispatchers.Default) {
                    filtered.sortedByDescending { it.lastModified?.toLongOrNull() ?: 0L }.take(HOME_RAIL_LIMIT)
                }
                _content.update { it.copy(topSeries = rail, seriesLabel = "Recently added series", seriesStatus = HomeSectionStatus.LOADED) }
                _stats.update { it.copy(series = filtered.size, seriesLoading = false) }
                try {
                    val discovered = ratings.discover("tv")
                    val ranked = withContext(Dispatchers.Default) { ratings.matchAvailable(discovered, filtered) { it.name } }
                    if (ranked.isNotEmpty()) _content.update { it.copy(topSeries = ranked, seriesLabel = "Top rated recent series · TMDB") }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { }
                Timber.d("Home Series loaded: %d", filtered.size)
            }
            .onFailure { error ->
                Timber.e(error, "Home Series failed")
                _content.update { it.copy(seriesStatus = HomeSectionStatus.ERROR) }
                _stats.update { it.copy(seriesLoading = false) }
            }
    }

    private suspend fun <T> fetchWithRetry(
        label: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): Result<T> {
        var lastError: Throwable = IllegalStateException("$label did not load")
        repeat(3) { attempt ->
            if (attempt > 0) delay(750L * attempt)
            val result = runCatching { withTimeout(timeoutMs) { block() } }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull() ?: lastError
            Timber.w(lastError, "Home %s attempt %d failed", label, attempt + 1)
        }
        return Result.failure(lastError)
    }

    /**
     * Sets the channel zapping list for the player (same as Live TV before navigating to player).
     */
    fun setLiveZapForRail(channelsInRail: List<XtreamChannel>, selected: XtreamChannel) {
        val idx = channelsInRail.indexOfFirst { it.streamId == selected.streamId }
        xtreamRepository.liveChannelList = channelsInRail
        xtreamRepository.liveChannelIndex = if (idx >= 0) idx else 0
    }

    fun playMovieRoute(movie: XtreamMovie): String =
        Screen.Player.createRoute("vod", movie.streamId.toString(), movie.containerExtension ?: "mp4")

    /**
     * Async: fetches series info and returns a player route, or null on failure.
     */
    fun playSeriesFromRail(series: XtreamSeries, onRoute: (String) -> Unit, onFailure: (() -> Unit)? = null) {
        viewModelScope.launch {
            val route = buildSeriesPlayerRoute(series, xtreamRepository, watchHistoryDao)
            if (route != null) onRoute(route)
            else onFailure?.invoke()
        }
    }

    private companion object {
        const val HOME_RAIL_LIMIT = 14
    }
}
