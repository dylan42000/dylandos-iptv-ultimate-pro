package com.dylandos.iptv.ultimate.ui.screens.series

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.dylandos.iptv.ultimate.data.db.dao.CachedSeriesDao
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import com.dylandos.iptv.ultimate.data.db.entity.CachedSeriesEntity
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.db.entity.WatchHistoryEntity
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.Episode
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.data.model.XtreamSeriesInfo
import com.dylandos.iptv.ultimate.data.network.CategoryContentType
import com.dylandos.iptv.ultimate.data.network.PendingSeriesPlaybackContext
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.util.episodesForSeasonRaw
import com.dylandos.iptv.ultimate.data.util.firstSelectableSeasonNumber
import com.dylandos.iptv.ultimate.data.util.sortCategories
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — Series ViewModel
 *
 * Category rail + poster grid pages from Room. Detail popup loads seasons/episodes
 * on demand. No giant in-memory catalog.
 */
@HiltViewModel
class SeriesViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val favoriteDao: FavoriteDao,
    private val cachedSeriesDao: CachedSeriesDao,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val POSTER_COLS = 5
        private const val KEY_SELECTED_CATEGORY_ID = "series_selectedCategoryId"
        private const val KEY_FOCUSED_ROW = "series_focusedRow"
        private const val KEY_FOCUSED_COL = "series_focusedCol"
        private const val KEY_GRID_FIRST_INDEX = "series_grid_first_index"
        private const val KEY_GRID_FIRST_OFFSET = "series_grid_first_offset"
        private const val KEY_CATEGORY_FIRST_INDEX = "series_category_first_index"
        private const val KEY_CATEGORY_FIRST_OFFSET = "series_category_first_offset"
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val MAX_ALL_ITEMS = 200
        private const val MAX_CATEGORY_ITEMS = 300
        const val FAVORITES_CATEGORY_ID = "FAVORITES"
        private val FAVORITES_CATEGORY = XtreamCategory(
            categoryId = FAVORITES_CATEGORY_ID,
            categoryName = "★ Favorites",
            parentId = 0
        )
    }

    private data class LoadedSeriesResult(
        val categories: List<XtreamCategory>,
        val activeCategoryId: String,
        val itemCount: Int,
        val focusedRow: Int,
        val focusedCol: Int
    )

    private fun normalizeCategoryId(raw: String?): String = raw?.trim().orEmpty()

    private val KEY_US_FIRST_SERIES = booleanPreferencesKey("us_en_first_series")

    private val _uiState = MutableStateFlow(
        SeriesUiState(
            selectedCategoryId = savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "ALL",
            focusedRow = (savedStateHandle[KEY_FOCUSED_ROW] ?: 0).coerceAtLeast(0),
            focusedCol = (savedStateHandle[KEY_FOCUSED_COL] ?: 0).coerceAtLeast(0),
            gridFirstVisibleItemIndex = (savedStateHandle[KEY_GRID_FIRST_INDEX] ?: 0).coerceAtLeast(0),
            gridFirstVisibleItemOffset = (savedStateHandle[KEY_GRID_FIRST_OFFSET] ?: 0).coerceAtLeast(0),
            categoryRailFirstVisibleIndex = (savedStateHandle[KEY_CATEGORY_FIRST_INDEX] ?: 0).coerceAtLeast(0),
            categoryRailFirstVisibleOffset = (savedStateHandle[KEY_CATEGORY_FIRST_OFFSET] ?: 0).coerceAtLeast(0)
        )
    )
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()

    private val _favoriteSeriesIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteSeriesIds: StateFlow<Set<Int>> = _favoriteSeriesIds.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData: Flow<PagingData<XtreamSeries>> = _uiState
        .map { it.selectedCategoryId to it.catalogGeneration }
        .distinctUntilChanged()
        .flatMapLatest { (categoryKey, _) ->
            Pager(
                config = PagingConfig(
                    pageSize = 20,
                    initialLoadSize = 20,
                    prefetchDistance = 5,
                    enablePlaceholders = false
                ),
                pagingSourceFactory = {
                    SeriesListPagingSource { limit, offset ->
                        cachedSeriesDao.page(categoryKey, limit, offset).map { it.toXtreamSeries() }
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    private val _navigateToPlayer = MutableStateFlow<String?>(null)
    val navigateToPlayer: StateFlow<String?> = _navigateToPlayer.asStateFlow()
    private val _navigateToDetail = MutableStateFlow<Int?>(null)
    val navigateToDetail: StateFlow<Int?> = _navigateToDetail.asStateFlow()
    private var detailLoadJob: Job? = null
    private var loadingSeriesId: Int? = null

    private var savedCategoryId: String
        get() = savedStateHandle.get<String>(KEY_SELECTED_CATEGORY_ID) ?: "ALL"
        set(value) = savedStateHandle.set(KEY_SELECTED_CATEGORY_ID, value)

    init {
        viewModelScope.launch {
            favoriteDao.getAllFavorites()
                .map { list -> list.filter { it.streamType == "series" }.map { it.streamId }.toSet() }
                .distinctUntilChanged()
                .collect { ids ->
                    _favoriteSeriesIds.value = ids
                    val state = _uiState.value
                    if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                        loadCategoryContent(FAVORITES_CATEGORY_ID, showLoading = false)
                    }
                }
        }
        viewModelScope.launch {
            xtreamRepository.observeHiddenCategoryIds(CategoryContentType.SERIES)
                .distinctUntilChanged()
                .collect {
                    if (_uiState.value.categories.isNotEmpty() && !_uiState.value.isLoading) load()
                }
        }
    }

    fun loadIfNeeded() {
        if (_uiState.value.categories.isEmpty() && !_uiState.value.isLoading) {
            load()
        } else {
            val catId = normalizeCategoryId(savedCategoryId)
            if (_uiState.value.selectedCategoryId != catId) {
                selectCategory(catId)
            }
            consumePendingOpenSeries()
        }
    }

    fun load() {
        if (!xtreamRepository.isConnected) {
            _uiState.value = _uiState.value.copy(error = "Not connected. Configure credentials in Settings.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val loaded = withTimeout(LOAD_TIMEOUT_MS) {
                    val prefs = context.dataStore.data.first()
                    val usFirst = prefs[KEY_US_FIRST_SERIES] ?: true
                    val rawCategories = xtreamRepository.getSeriesCategories().getOrDefault(emptyList())
                    val visibleRawCategories = xtreamRepository.filterVisibleCategories(
                        CategoryContentType.SERIES,
                        rawCategories
                    )

                    val fullCategoryList = withContext(Dispatchers.Default) {
                        val categories = contentFilterRepository.filterCategories(visibleRawCategories)
                        val allCat = XtreamCategory("ALL", "All Series", 0)
                        listOf(allCat, FAVORITES_CATEGORY) + sortCategories(categories, usEnFirst = usFirst)
                    }

                    val requestedCategoryId = normalizeCategoryId(savedCategoryId)
                    val activeCategoryId = when {
                        requestedCategoryId == FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID
                        requestedCategoryId == "ALL" -> "ALL"
                        requestedCategoryId.isNotBlank() &&
                            fullCategoryList.any { normalizeCategoryId(it.categoryId) == requestedCategoryId } ->
                            requestedCategoryId
                        else -> fullCategoryList
                            .firstOrNull {
                                val id = normalizeCategoryId(it.categoryId)
                                id != "ALL" && id != FAVORITES_CATEGORY_ID
                            }?.categoryId?.let(::normalizeCategoryId) ?: "ALL"
                    }

                    val itemCount = persistCategory(activeCategoryId)

                    val requestedRow = (savedStateHandle[KEY_FOCUSED_ROW] ?: _uiState.value.focusedRow).coerceAtLeast(0)
                    val requestedCol = (savedStateHandle[KEY_FOCUSED_COL] ?: _uiState.value.focusedCol).coerceAtLeast(0)
                    val boundedIndex = if (itemCount <= 0) 0
                    else (requestedRow * POSTER_COLS + requestedCol).coerceIn(0, itemCount - 1)
                    val boundedRow = if (itemCount <= 0) 0 else boundedIndex / POSTER_COLS
                    val boundedCol = if (itemCount <= 0) 0 else boundedIndex % POSTER_COLS

                    LoadedSeriesResult(
                        categories = fullCategoryList,
                        activeCategoryId = activeCategoryId,
                        itemCount = itemCount,
                        focusedRow = boundedRow,
                        focusedCol = boundedCol
                    )
                }

                _uiState.value = _uiState.value.copy(
                    categories = loaded.categories,
                    selectedCategoryId = loaded.activeCategoryId,
                    itemCount = loaded.itemCount,
                    catalogGeneration = _uiState.value.catalogGeneration + 1,
                    focusedRow = loaded.focusedRow,
                    focusedCol = loaded.focusedCol,
                    isLoading = false
                )
                persistFocus(row = loaded.focusedRow, col = loaded.focusedCol)
                consumePendingOpenSeries()
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "Series load timed out")
                _uiState.value = _uiState.value.copy(
                    error = "TV Series took too long to load. Please retry.",
                    isLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load series")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false
                )
            }
        }
    }

    private suspend fun persistCategory(categoryId: String): Int {
        val filtered = fetchSeriesForCategory(categoryId)
        val rows = filtered.mapIndexed { index, series -> series.toCachedEntity(categoryId, index) }
        withContext(Dispatchers.IO) {
            cachedSeriesDao.replaceCategory(categoryId, rows)
        }
        return rows.size
    }

    private suspend fun fetchSeriesForCategory(categoryId: String): List<XtreamSeries> {
        return when (categoryId) {
            FAVORITES_CATEGORY_ID -> resolveFavoriteSeries(_favoriteSeriesIds.value)
            "ALL" -> {
                // A5: cap at the network layer — see MoviesViewModel.fetchMoviesForCategory.
                val raw = xtreamRepository.getSeries(maxItems = MAX_ALL_ITEMS * 2).getOrDefault(emptyList())
                val filtered = contentFilterRepository.filterSeries(raw)
                if (filtered.size > MAX_ALL_ITEMS) {
                    Timber.w("Series ALL capped at $MAX_ALL_ITEMS (fetched ${raw.size})")
                    filtered.take(MAX_ALL_ITEMS)
                } else filtered
            }
            else -> {
                val raw = xtreamRepository.getSeries(categoryId, maxItems = MAX_CATEGORY_ITEMS * 2).getOrDefault(emptyList())
                val filtered = contentFilterRepository.filterSeries(raw)
                if (filtered.size > MAX_CATEGORY_ITEMS) {
                    Timber.w(
                        "Series category $categoryId capped at $MAX_CATEGORY_ITEMS " +
                            "(provider returned ${filtered.size})"
                    )
                    filtered.take(MAX_CATEGORY_ITEMS)
                } else filtered
            }
        }
    }

    private suspend fun resolveFavoriteSeries(ids: Set<Int>): List<XtreamSeries> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                xtreamRepository.getSeriesInfo(id).getOrNull()?.let { info ->
                    XtreamSeries(
                        num = 0,
                        name = info.info.name,
                        seriesId = id,
                        cover = info.info.cover,
                        plot = info.info.plot,
                        cast = info.info.cast,
                        director = info.info.director,
                        genre = info.info.genre,
                        releaseDate = info.info.releaseDate,
                        lastModified = info.info.lastModified,
                        rating = info.info.rating,
                        rating5Based = info.info.rating5Based,
                        backdropPath = info.info.backdropPath,
                        youtubeTrailer = info.info.youtubeTrailer,
                        episodeRunTime = info.info.episodeRunTime,
                        categoryId = info.info.categoryId
                    )
                }
            }
        }
    }

    private fun loadCategoryContent(categoryId: String, showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(
                    selectedCategoryId = categoryId,
                    error = null,
                    isCategoryLoading = true
                )
            }
            try {
                val itemCount = withTimeout(LOAD_TIMEOUT_MS) { persistCategory(categoryId) }
                _uiState.value = _uiState.value.copy(
                    selectedCategoryId = categoryId,
                    itemCount = itemCount,
                    catalogGeneration = _uiState.value.catalogGeneration + 1,
                    focusedRow = 0,
                    focusedCol = 0,
                    gridFirstVisibleItemIndex = 0,
                    gridFirstVisibleItemOffset = 0,
                    isLoading = false,
                    isCategoryLoading = false
                )
                persistFocus(row = 0, col = 0)
                savedStateHandle[KEY_GRID_FIRST_INDEX] = 0
                savedStateHandle[KEY_GRID_FIRST_OFFSET] = 0
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "Series category load timed out")
                _uiState.value = _uiState.value.copy(
                    error = "Category took too long to load. Please retry.",
                    isCategoryLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load series category $categoryId")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isCategoryLoading = false
                )
            }
        }
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        val requestedCategoryId = normalizeCategoryId(categoryId)
        val activeCategoryId = when {
            requestedCategoryId.isBlank() -> "ALL"
            requestedCategoryId == "ALL" -> "ALL"
            requestedCategoryId == FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID
            state.categories.any { normalizeCategoryId(it.categoryId) == requestedCategoryId } -> requestedCategoryId
            else -> "ALL"
        }
        if (activeCategoryId == state.selectedCategoryId && state.itemCount > 0) {
            return
        }
        savedCategoryId = activeCategoryId
        loadCategoryContent(activeCategoryId)
    }

    fun playMedia(episode: Episode) {
        closePopup()
        val state = _uiState.value
        val seriesId = state.selectedSeries?.seriesId ?: 0
        val seriesName = state.selectedSeries?.name
            ?: state.seriesDetail?.info?.name ?: ""
        val seasonStr = episode.season.toString().padStart(2, '0')
        val epStr = episode.episodeNum.toString().padStart(2, '0')
        val epTitle = episode.title.ifBlank { "" }
        val displayTitle = buildString {
            if (seriesName.isNotBlank()) append(seriesName).append(" ")
            append("S${seasonStr}E${epStr}")
            if (epTitle.isNotBlank()) append(" \u2013 $epTitle")
        }
        xtreamRepository.pendingStreamTitle = displayTitle
        xtreamRepository.pendingSeriesPlaybackContext = PendingSeriesPlaybackContext(
            episodeId = episode.id,
            seriesId = seriesId,
            seriesName = seriesName,
            seasonNumber = episode.season,
            episodeNumber = episode.episodeNum,
            episodeTitle = episode.title
        )
        _navigateToPlayer.value = "player/series/${episode.id}/${episode.containerExtension.ifEmpty { "mp4" }}"
    }

    fun onNavigatedToPlayer() {
        _navigateToPlayer.value = null
    }

    fun onNavigatedToDetail() {
        _navigateToDetail.value = null
        xtreamRepository.pendingOpenSeries = null
        xtreamRepository.pendingOpenSeriesId = null
    }

    fun moveFocus(dRow: Int, dCol: Int) {
        val state = _uiState.value
        val total = state.itemCount
        if (total == 0) return
        val rows = (total + POSTER_COLS - 1) / POSTER_COLS

        var newRow = (state.focusedRow + dRow).coerceIn(0, rows - 1)
        var newCol = (state.focusedCol + dCol).coerceIn(0, POSTER_COLS - 1)

        if (newRow * POSTER_COLS + newCol < total) {
            _uiState.value = state.copy(focusedRow = newRow, focusedCol = newCol)
            persistFocus(row = newRow, col = newCol)
        }
    }

    fun setFocus(row: Int, col: Int) {
        _uiState.value = _uiState.value.copy(focusedRow = row, focusedCol = col)
        persistFocus(row = row, col = col)
    }

    fun onGridScrollChanged(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val state = _uiState.value
        if (state.gridFirstVisibleItemIndex == firstVisibleItemIndex &&
            state.gridFirstVisibleItemOffset == firstVisibleItemScrollOffset
        ) return
        _uiState.value = state.copy(
            gridFirstVisibleItemIndex = firstVisibleItemIndex,
            gridFirstVisibleItemOffset = firstVisibleItemScrollOffset
        )
        savedStateHandle[KEY_GRID_FIRST_INDEX] = firstVisibleItemIndex
        savedStateHandle[KEY_GRID_FIRST_OFFSET] = firstVisibleItemScrollOffset
    }

    fun onCategoryRailScrollChanged(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val state = _uiState.value
        if (state.categoryRailFirstVisibleIndex == firstVisibleItemIndex &&
            state.categoryRailFirstVisibleOffset == firstVisibleItemScrollOffset
        ) return
        _uiState.value = state.copy(
            categoryRailFirstVisibleIndex = firstVisibleItemIndex,
            categoryRailFirstVisibleOffset = firstVisibleItemScrollOffset
        )
        savedStateHandle[KEY_CATEGORY_FIRST_INDEX] = firstVisibleItemIndex
        savedStateHandle[KEY_CATEGORY_FIRST_OFFSET] = firstVisibleItemScrollOffset
    }

    private fun persistFocus(row: Int = _uiState.value.focusedRow, col: Int = _uiState.value.focusedCol) {
        savedStateHandle[KEY_FOCUSED_ROW] = row
        savedStateHandle[KEY_FOCUSED_COL] = col
    }

    fun consumePendingOpenIfAny() {
        consumePendingOpenSeries()
    }

    private fun consumePendingOpenSeries() {
        val pending = xtreamRepository.pendingOpenSeries
        val pendingId = xtreamRepository.pendingOpenSeriesId ?: pending?.seriesId ?: return
        xtreamRepository.pendingOpenSeriesId = null
        xtreamRepository.pendingOpenSeries = null
        val series = pending
            ?: xtreamRepository.getRememberedSeries(pendingId)
            ?: return
        openSeries(series)
    }

    fun peekRememberedSeries(seriesId: Int) =
        xtreamRepository.getRememberedSeries(seriesId)

    fun ensureSeriesSelected(seriesId: Int, fallbackTitle: String? = null) {
        if (_uiState.value.selectedSeries?.seriesId == seriesId) {
            loadSelectedSeriesDetails(seriesId)
            return
        }
        val pending = xtreamRepository.pendingOpenSeries?.takeIf { it.seriesId == seriesId }
        val title = fallbackTitle
            ?: xtreamRepository.pendingStreamTitle?.takeIf { it.isNotBlank() }
        val series = xtreamRepository.getRememberedSeries(seriesId)
            ?: pending
            ?: XtreamSeries(
                num = seriesId,
                name = title ?: "Series $seriesId",
                seriesId = seriesId,
                cover = null,
                plot = null,
                cast = null,
                director = null,
                genre = null,
                releaseDate = null,
                lastModified = null,
                rating = null,
                rating5Based = 0.0,
                backdropPath = null,
                youtubeTrailer = null,
                episodeRunTime = null,
                categoryId = null
            )
        xtreamRepository.pendingOpenSeriesId = null
        xtreamRepository.pendingOpenSeries = null
        openSeriesForDetailRoute(series)
    }

    /** Load detail state without emitting another navigation event (in-route swaps). */
    fun openSeriesForDetailRoute(series: XtreamSeries) {
        detailLoadJob?.cancel()
        loadingSeriesId = null
        xtreamRepository.rememberSeries(listOf(series))
        xtreamRepository.pendingOpenSeries = null
        xtreamRepository.pendingOpenSeriesId = null
        _uiState.value = _uiState.value.copy(
            selectedSeries = series,
            seriesDetail = null,
            detailLoading = true,
            detailError = null,
            selectedSeason = 1,
            showPopup = true,
            resumeEpisode = null,
            watchedEpisodeIds = emptySet(),
            recommendedSeries = emptyList()
        )
        loadSelectedSeriesDetails(series.seriesId, force = true)
    }

    fun openSeries(series: XtreamSeries) {
        openSeriesForDetailRoute(series)
        _navigateToDetail.value = series.seriesId
    }

    fun loadSelectedSeriesDetails(seriesId: Int, force: Boolean = false) {
        val selected = _uiState.value.selectedSeries
        if (selected == null || selected.seriesId != seriesId) return
        if (!force && loadingSeriesId == seriesId && detailLoadJob?.isActive == true) return
        if (!force && loadingSeriesId == seriesId && _uiState.value.seriesDetail != null) return

        detailLoadJob?.cancel()
        loadingSeriesId = seriesId
        detailLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(detailLoading = true, detailError = null)
            try {
                val categoryKey = _uiState.value.selectedCategoryId
                val recommendationsDeferred = async(Dispatchers.IO) {
                    val peers = cachedSeriesDao.sample(categoryKey, limit = 200)
                        .map { it.toXtreamSeries() }
                    buildSeriesRecommendations(selected, peers)
                }
                val payload = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val detailDeferred = async {
                            xtreamRepository.getSeriesInfo(seriesId).getOrThrow()
                        }
                        val resumeDeferred = async {
                            runCatching { watchHistoryDao.getLastWatched(seriesId) }
                                .onFailure { Timber.w(it, "Failed to read resume for seriesId=$seriesId") }
                                .getOrNull()
                        }
                        val watchedDeferred = async {
                            runCatching { watchHistoryDao.getForSeries(seriesId) }
                                .onFailure { Timber.w(it, "Failed to read watched history for seriesId=$seriesId") }
                                .getOrDefault(emptyList())
                                .asSequence()
                                .filter { it.isWatched }
                                .map { it.episodeId }
                                .toSet()
                        }
                        Triple(detailDeferred.await(), resumeDeferred.await(), watchedDeferred.await())
                    }
                }
                val recommendations = recommendationsDeferred.await()
                if (_uiState.value.selectedSeries?.seriesId == seriesId) {
                    _uiState.value = _uiState.value.copy(
                        seriesDetail = payload.first,
                        selectedSeason = firstSelectableSeasonNumber(payload.first),
                        detailLoading = false,
                        resumeEpisode = payload.second,
                        watchedEpisodeIds = payload.third,
                        recommendedSeries = recommendations
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Series detail pipeline failed")
                if (_uiState.value.selectedSeries?.seriesId == seriesId) {
                    _uiState.value = _uiState.value.copy(
                        detailError = e.message ?: "Failed to open series",
                        detailLoading = false
                    )
                }
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _uiState.value = _uiState.value.copy(selectedSeason = seasonNumber)
    }

    fun closePopup() {
        detailLoadJob?.cancel()
        loadingSeriesId = null
        xtreamRepository.pendingOpenSeries = null
        xtreamRepository.pendingOpenSeriesId = null
        _navigateToDetail.value = null
        _uiState.value = _uiState.value.copy(showPopup = false, selectedSeries = null)
    }

    fun episodesForSeason(info: XtreamSeriesInfo, season: Int): List<Episode> =
        episodesForSeasonRaw(info, season)

    fun getSeriesStreamUrl(episodeId: String, extension: String): String =
        xtreamRepository.getSeriesStreamUrl(episodeId, extension)

    fun isFavoriteFlow(seriesId: Int) = favoriteDao.isFavorite(seriesId, "series")

    fun toggleSeriesFavorite(seriesId: Int) {
        viewModelScope.launch {
            val current = favoriteDao.isFavorite(seriesId, "series").first()
            if (current) {
                favoriteDao.deleteByStreamId(seriesId, "series")
            } else {
                favoriteDao.insert(FavoriteEntity(seriesId, "series", System.currentTimeMillis()))
            }
        }
    }

    private fun buildSeriesRecommendations(
        selected: XtreamSeries,
        catalog: List<XtreamSeries>
    ): List<XtreamSeries> {
        val selectedGenres = tokens(selected.genre)
        val selectedCast = tokens(selected.cast)
        val selectedDirector = selected.director?.trim()?.lowercase().orEmpty()
        return catalog.asSequence()
            .filter { it.seriesId != selected.seriesId && !it.cover.isNullOrBlank() }
            .map { candidate ->
                val genreMatches = tokens(candidate.genre).intersect(selectedGenres).size
                val castMatches = tokens(candidate.cast).intersect(selectedCast).size
                val sameDirector = selectedDirector.isNotBlank() &&
                    candidate.director?.trim()?.lowercase() == selectedDirector
                val sameCategory = !selected.categoryId.isNullOrBlank() &&
                    candidate.categoryId == selected.categoryId
                val ratingDistance = abs(candidate.rating5Based - selected.rating5Based)
                val score = genreMatches * 42.0 +
                    castMatches.coerceAtMost(3) * 8.0 +
                    if (sameDirector) 24.0 else 0.0 +
                    if (sameCategory) 18.0 else 0.0 +
                    (12.0 - ratingDistance * 4.0).coerceAtLeast(0.0) +
                    candidate.rating5Based
                candidate to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(12)
            .toList()
    }

    private fun tokens(value: String?): Set<String> =
        value.orEmpty()
            .lowercase()
            .split(',', '/', '|')
            .map { it.trim() }
            .filter { it.length > 2 }
            .toSet()
}

private fun XtreamSeries.toCachedEntity(categoryKey: String, sortIndex: Int) = CachedSeriesEntity(
    categoryKey = categoryKey,
    seriesId = seriesId,
    sortIndex = sortIndex,
    num = num,
    name = name,
    cover = cover,
    plot = plot,
    castNames = cast,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    lastModified = lastModified,
    rating = rating,
    rating5Based = rating5Based,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    categoryId = categoryId
)

private fun CachedSeriesEntity.toXtreamSeries() = XtreamSeries(
    num = num,
    name = name,
    seriesId = seriesId,
    cover = cover,
    plot = plot,
    cast = castNames,
    director = director,
    genre = genre,
    releaseDate = releaseDate,
    lastModified = lastModified,
    rating = rating,
    rating5Based = rating5Based,
    backdropPath = null,
    youtubeTrailer = youtubeTrailer,
    episodeRunTime = episodeRunTime,
    categoryId = categoryId
)

data class SeriesUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String = "ALL",
    val itemCount: Int = 0,
    val catalogGeneration: Int = 0,
    val focusedRow: Int = 0,
    val focusedCol: Int = 0,
    val gridFirstVisibleItemIndex: Int = 0,
    val gridFirstVisibleItemOffset: Int = 0,
    val categoryRailFirstVisibleIndex: Int = 0,
    val categoryRailFirstVisibleOffset: Int = 0,
    val isLoading: Boolean = false,
    val isCategoryLoading: Boolean = false,
    val error: String? = null,
    val showPopup: Boolean = false,
    val selectedSeries: XtreamSeries? = null,
    val seriesDetail: XtreamSeriesInfo? = null,
    val selectedSeason: Int = 1,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val resumeEpisode: WatchHistoryEntity? = null,
    val watchedEpisodeIds: Set<String> = emptySet(),
    val recommendedSeries: List<XtreamSeries> = emptyList()
)
