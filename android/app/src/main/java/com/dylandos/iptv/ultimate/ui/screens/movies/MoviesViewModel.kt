package com.dylandos.iptv.ultimate.ui.screens.movies

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.dylandos.iptv.ultimate.data.db.dao.CachedMovieDao
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.dao.VodResumeDao
import com.dylandos.iptv.ultimate.data.db.entity.CachedMovieEntity
import com.dylandos.iptv.ultimate.data.db.entity.VodResumeEntity
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.network.CategoryContentType
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.util.sortCategories
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
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
import javax.inject.Inject

@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val vodResumeDao: VodResumeDao,
    private val favoriteDao: FavoriteDao,
    private val cachedMovieDao: CachedMovieDao,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val POSTER_COLS = 5
        private const val KEY_SELECTED_CATEGORY_ID = "movies_selectedCategoryId"
        private const val KEY_FOCUSED_ROW = "movies_focusedRow"
        private const val KEY_FOCUSED_COL = "movies_focusedCol"
        private const val KEY_GRID_FIRST_INDEX = "movies_grid_first_index"
        private const val KEY_GRID_FIRST_OFFSET = "movies_grid_first_offset"
        private const val KEY_CATEGORY_FIRST_INDEX = "movies_category_first_index"
        private const val KEY_CATEGORY_FIRST_OFFSET = "movies_category_first_offset"
        private const val LOAD_TIMEOUT_MS = 25_000L
        /** Hard cap when user browses "All Movies" — full catalogs OOM Firestick (~2GB). */
        private const val MAX_ALL_ITEMS = 200
        /** Cap per-category lists so a huge folder cannot flood Room/USB. */
        private const val MAX_CATEGORY_ITEMS = 300
        const val FAVORITES_CATEGORY_ID = "FAVORITES"
        private val FAVORITES_CATEGORY = XtreamCategory(
            categoryId = FAVORITES_CATEGORY_ID,
            categoryName = "★ Favorites",
            parentId = 0
        )
    }

    private data class LoadedMoviesResult(
        val categoryList: List<XtreamCategory>,
        val activeCategoryId: String,
        val itemCount: Int,
        val resumeMap: Map<Int, VodResumeEntity>,
        val boundedRow: Int,
        val boundedCol: Int
    )

    private fun normalizeCategoryId(raw: String?): String = raw?.trim().orEmpty()

    private val KEY_US_FIRST_MOVIES = booleanPreferencesKey("us_en_first_movies")

    private val _uiState = MutableStateFlow(
        MoviesUiState(
            selectedCategoryId = savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "ALL",
            focusedRow = (savedStateHandle[KEY_FOCUSED_ROW] ?: 0).coerceAtLeast(0),
            focusedCol = (savedStateHandle[KEY_FOCUSED_COL] ?: 0).coerceAtLeast(0),
            gridFirstVisibleItemIndex = (savedStateHandle[KEY_GRID_FIRST_INDEX] ?: 0).coerceAtLeast(0),
            gridFirstVisibleItemOffset = (savedStateHandle[KEY_GRID_FIRST_OFFSET] ?: 0).coerceAtLeast(0),
            categoryRailFirstVisibleIndex = (savedStateHandle[KEY_CATEGORY_FIRST_INDEX] ?: 0).coerceAtLeast(0),
            categoryRailFirstVisibleOffset = (savedStateHandle[KEY_CATEGORY_FIRST_OFFSET] ?: 0).coerceAtLeast(0)
        )
    )
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private val _favoriteMovieIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteMovieIds: StateFlow<Set<Int>> = _favoriteMovieIds.asStateFlow()

    /**
     * Paginated stream for the active category. Pages from Room LIMIT/OFFSET —
     * never from a fully materialized list held in the ViewModel.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagingData: Flow<PagingData<XtreamMovie>> = _uiState
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
                    MovieListPagingSource { limit, offset ->
                        cachedMovieDao.page(categoryKey, limit, offset).map { it.toXtreamMovie() }
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    private val _navigateToPlayer = MutableStateFlow<String?>(null)
    val navigateToPlayer: StateFlow<String?> = _navigateToPlayer.asStateFlow()

    private val _navigateToDetail = MutableStateFlow<Int?>(null)
    val navigateToDetail: StateFlow<Int?> = _navigateToDetail.asStateFlow()

    private var savedCategoryId: String
        get() = savedStateHandle.get<String>(KEY_SELECTED_CATEGORY_ID) ?: "ALL"
        set(value) = savedStateHandle.set(KEY_SELECTED_CATEGORY_ID, value)

    init {
        viewModelScope.launch {
            favoriteDao.getAllFavorites()
                .map { list -> list.filter { it.streamType == "vod" }.map { it.streamId }.toSet() }
                .distinctUntilChanged()
                .collect { ids ->
                    _favoriteMovieIds.value = ids
                    val state = _uiState.value
                    if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
                        loadCategoryContent(FAVORITES_CATEGORY_ID, showLoading = false)
                    }
                }
        }
        viewModelScope.launch {
            xtreamRepository.observeHiddenCategoryIds(CategoryContentType.MOVIES)
                .distinctUntilChanged()
                .collect {
                    if (_uiState.value.categories.isNotEmpty() && !_uiState.value.isLoading) load()
                }
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
                    val usFirst = prefs[KEY_US_FIRST_MOVIES] ?: true
                    val rawCategories = xtreamRepository.getVodCategories().getOrDefault(emptyList())
                    val visibleRawCategories = xtreamRepository.filterVisibleCategories(
                        CategoryContentType.MOVIES,
                        rawCategories
                    )

                    val categoryList = withContext(Dispatchers.Default) {
                        val categories = contentFilterRepository.filterCategories(visibleRawCategories)
                        val allCat = XtreamCategory("ALL", "All Movies", 0)
                        listOf(allCat, FAVORITES_CATEGORY) + sortCategories(categories, usEnFirst = usFirst)
                    }

                    val requestedCat = normalizeCategoryId(savedCategoryId)
                    val activeCatId = when {
                        requestedCat == FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID
                        requestedCat == "ALL" -> "ALL"
                        requestedCat.isNotBlank() &&
                            categoryList.any { normalizeCategoryId(it.categoryId) == requestedCat } -> requestedCat
                        else -> categoryList
                            .firstOrNull {
                                val id = normalizeCategoryId(it.categoryId)
                                id != "ALL" && id != FAVORITES_CATEGORY_ID
                            }?.categoryId?.let(::normalizeCategoryId) ?: "ALL"
                    }

                    val itemCount = persistCategory(activeCatId)

                    val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
                    val resumeMap = vodResumeDao.getRecentlyWatched(cutoff).associateBy { it.streamId }

                    val requestedRow = (savedStateHandle[KEY_FOCUSED_ROW] ?: _uiState.value.focusedRow).coerceAtLeast(0)
                    val requestedCol = (savedStateHandle[KEY_FOCUSED_COL] ?: _uiState.value.focusedCol).coerceAtLeast(0)
                    val boundedIndex = if (itemCount <= 0) 0
                    else (requestedRow * POSTER_COLS + requestedCol).coerceIn(0, itemCount - 1)
                    val boundedRow = if (itemCount <= 0) 0 else boundedIndex / POSTER_COLS
                    val boundedCol = if (itemCount <= 0) 0 else boundedIndex % POSTER_COLS

                    LoadedMoviesResult(
                        categoryList = categoryList,
                        activeCategoryId = activeCatId,
                        itemCount = itemCount,
                        resumeMap = resumeMap,
                        boundedRow = boundedRow,
                        boundedCol = boundedCol
                    )
                }

                if (loaded.activeCategoryId != savedCategoryId) {
                    savedCategoryId = loaded.activeCategoryId
                }

                _uiState.value = _uiState.value.copy(
                    categories = loaded.categoryList,
                    selectedCategoryId = loaded.activeCategoryId,
                    itemCount = loaded.itemCount,
                    catalogGeneration = _uiState.value.catalogGeneration + 1,
                    resumeMap = loaded.resumeMap,
                    focusedRow = loaded.boundedRow,
                    focusedCol = loaded.boundedCol,
                    isLoading = false
                )
                persistFocus(row = loaded.boundedRow, col = loaded.boundedCol)
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "Movies load timed out")
                _uiState.value = _uiState.value.copy(
                    error = "Movies took too long to load. Please retry.",
                    isLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load movies")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false
                )
            }
        }
    }

    /**
     * Fetch category from network, write to Room, discard the network list.
     * Returns the persisted row count.
     */
    private suspend fun persistCategory(categoryId: String): Int {
        val filtered = fetchMoviesForCategory(categoryId)
        val rows = filtered.mapIndexed { index, movie -> movie.toCachedEntity(categoryId, index) }
        // Drop network list reference before Room write completes on IO.
        withContext(Dispatchers.IO) {
            cachedMovieDao.replaceCategory(categoryId, rows)
        }
        return rows.size
    }

    private suspend fun fetchMoviesForCategory(categoryId: String): List<XtreamMovie> {
        return when (categoryId) {
            FAVORITES_CATEGORY_ID -> resolveFavoriteMovies(_favoriteMovieIds.value)
            "ALL" -> {
                // A5: cap at the network layer — the stream reader stops parsing after
                // 2x the display cap (headroom so the content filter still finds enough
                // matches) instead of materializing a 50k-item catalog on a 2 GB stick.
                val raw = xtreamRepository.getVodStreams(maxItems = MAX_ALL_ITEMS * 2).getOrDefault(emptyList())
                val filtered = contentFilterRepository.filterMovies(raw)
                if (filtered.size > MAX_ALL_ITEMS) {
                    Timber.w("Movies ALL capped at $MAX_ALL_ITEMS (fetched ${raw.size})")
                    filtered.take(MAX_ALL_ITEMS)
                } else filtered
            }
            else -> {
                val raw = xtreamRepository.getVodStreams(categoryId, maxItems = MAX_CATEGORY_ITEMS * 2).getOrDefault(emptyList())
                val filtered = contentFilterRepository.filterMovies(raw)
                if (filtered.size > MAX_CATEGORY_ITEMS) {
                    Timber.w(
                        "Movies category $categoryId capped at $MAX_CATEGORY_ITEMS " +
                            "(provider returned ${filtered.size})"
                    )
                    filtered.take(MAX_CATEGORY_ITEMS)
                } else filtered
            }
        }
    }

    private suspend fun resolveFavoriteMovies(ids: Set<Int>): List<XtreamMovie> {
        if (ids.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                xtreamRepository.getVodInfo(id).getOrNull()?.let { info ->
                    val data = info.movieData
                    XtreamMovie(
                        num = 0,
                        name = data?.name ?: info.info.name ?: "Movie $id",
                        streamType = "movie",
                        streamId = data?.streamId ?: id,
                        streamIcon = info.info.movieImage ?: info.info.coverBig,
                        rating = info.info.rating5Based.takeIf { it > 0 }?.toString(),
                        rating5Based = info.info.rating5Based,
                        added = data?.added,
                        categoryId = data?.categoryId,
                        containerExtension = data?.containerExtension ?: "mp4",
                        customSid = data?.customSid,
                        directSource = data?.directSource
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
                Timber.w(e, "Movies category load timed out")
                _uiState.value = _uiState.value.copy(
                    error = "Category took too long to load. Please retry.",
                    isCategoryLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load movie category $categoryId")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isCategoryLoading = false
                )
            }
        }
    }

    fun loadIfNeeded() {
        if (_uiState.value.categories.isEmpty() && !_uiState.value.isLoading) {
            load()
        } else {
            val catId = savedCategoryId
            if (_uiState.value.selectedCategoryId != catId) {
                selectCategory(catId)
            }
        }
    }

    fun refreshResume() {
        viewModelScope.launch {
            try {
                val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
                val resumeList = vodResumeDao.getRecentlyWatched(cutoff)
                _uiState.value = _uiState.value.copy(resumeMap = resumeList.associateBy { it.streamId })
            } catch (e: Exception) {
                Timber.w(e, "refreshResume failed")
            }
        }
    }

    fun selectCategory(categoryId: String) {
        val requestedCategoryId = normalizeCategoryId(categoryId)
        val state = _uiState.value
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

    fun toggleMovieFavorite(streamId: Int) {
        viewModelScope.launch {
            favoriteDao.toggle(streamId, "vod")
        }
    }

    fun showDetail(movie: XtreamMovie) {
        xtreamRepository.pendingOpenMovie = movie
        xtreamRepository.rememberMovies(listOf(movie))
        _navigateToDetail.value = movie.streamId
    }

    fun onNavigatedToDetail() {
        _navigateToDetail.value = null
    }

    fun playMedia(movie: XtreamMovie) {
        xtreamRepository.pendingStreamTitle = movie.name
        _navigateToPlayer.value = "player/vod/${movie.streamId}/${movie.containerExtension ?: "mp4"}"
    }

    fun onNavigatedToPlayer() {
        _navigateToPlayer.value = null
    }

    fun moveFocus(dRow: Int, dCol: Int, colsPerRow: Int) {
        val state = _uiState.value
        val total = state.itemCount
        if (total == 0) return
        val rows = (total + colsPerRow - 1) / colsPerRow

        var newRow = state.focusedRow + dRow
        var newCol = state.focusedCol + dCol

        newCol = newCol.coerceIn(0, colsPerRow - 1)
        newRow = newRow.coerceIn(0, rows - 1)

        val newIdx = newRow * colsPerRow + newCol
        if (newIdx < total) {
            _uiState.value = state.copy(focusedRow = newRow, focusedCol = newCol)
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
}

private fun XtreamMovie.toCachedEntity(categoryKey: String, sortIndex: Int) = CachedMovieEntity(
    categoryKey = categoryKey,
    streamId = streamId,
    sortIndex = sortIndex,
    num = num,
    name = name,
    streamType = streamType,
    streamIcon = streamIcon,
    rating = rating,
    rating5Based = rating5Based,
    added = added,
    categoryId = categoryId,
    containerExtension = containerExtension,
    customSid = customSid,
    directSource = directSource
)

private fun CachedMovieEntity.toXtreamMovie() = XtreamMovie(
    num = num,
    name = name,
    streamType = streamType,
    streamId = streamId,
    streamIcon = streamIcon,
    rating = rating,
    rating5Based = rating5Based,
    added = added,
    categoryId = categoryId,
    containerExtension = containerExtension,
    customSid = customSid,
    directSource = directSource
)

data class MoviesUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String = "ALL",
    /** Count of items persisted in Room for the active category. */
    val itemCount: Int = 0,
    /** Bumped when the Room catalog for the active category is replaced. */
    val catalogGeneration: Int = 0,
    val resumeMap: Map<Int, VodResumeEntity> = emptyMap(),
    val focusedRow: Int = 0,
    val focusedCol: Int = 0,
    val gridFirstVisibleItemIndex: Int = 0,
    val gridFirstVisibleItemOffset: Int = 0,
    val categoryRailFirstVisibleIndex: Int = 0,
    val categoryRailFirstVisibleOffset: Int = 0,
    val isLoading: Boolean = false,
    val isCategoryLoading: Boolean = false,
    val error: String? = null
)
