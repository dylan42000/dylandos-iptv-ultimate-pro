package com.dylandos.iptv.ultimate.ui.screens.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.model.MovieInfo
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamMovieInfo
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs
import javax.inject.Inject

data class MovieDetailUiState(
    val movie: XtreamMovie? = null,
    val info: MovieInfo? = null,
    val similarMovies: List<XtreamMovie> = emptyList(),
    val isLoadingInfo: Boolean = true,
    val error: String? = null,
    /** True when info was fetched but the server returned empty/null metadata. */
    val infoEmpty: Boolean = false,
    /** Route to navigate to when Play is pressed; cleared after navigation. */
    val navigateToPlayer: String? = null,
)

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val favoriteDao: FavoriteDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val selectedStreamId: Int = savedStateHandle.get<Int>("streamId") ?: 0
    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()
    private var detailLoadJob: Job? = null
    private var loadedStreamId: Int? = null

    /** Reactive favorite state — true when this movie is in the Favorites list. */
    val isFavorite: StateFlow<Boolean> = favoriteDao.isFavorite(selectedStreamId, "vod")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavorite() {
        viewModelScope.launch {
            if (isFavorite.value) {
                favoriteDao.deleteByStreamId(selectedStreamId, "vod")
            } else {
                favoriteDao.insert(FavoriteEntity(selectedStreamId, "vod", System.currentTimeMillis()))
            }
        }
    }

    fun loadDetails(streamId: Int = selectedStreamId, force: Boolean = false) {
        if (streamId <= 0) {
            _uiState.value = _uiState.value.copy(
                isLoadingInfo = false,
                error = "Invalid movie ID"
            )
            return
        }
        if (!force && loadedStreamId == streamId && !_uiState.value.isLoadingInfo) return
        if (!force && detailLoadJob?.isActive == true && loadedStreamId == streamId) return

        detailLoadJob?.cancel()
        loadedStreamId = streamId
        detailLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingInfo = true, error = null, infoEmpty = false)

            try {
                // Resolve movie without depending on the full-catalog cache.
                // Per-category paging never fills vodStreamsCache, so after browsing
                // many titles the old path always showed "Movie not found".
                val movie = resolveMovie(streamId)
                if (movie != null) {
                    _uiState.value = _uiState.value.copy(movie = movie)
                }

                val catalogHint = withContext(Dispatchers.IO) {
                    xtreamRepository.getCachedVodStreams().orEmpty()
                }
                val movieInfo = xtreamRepository.getVodInfo(streamId).getOrThrow()
                if (loadedStreamId == streamId) {
                    val resolved = movie
                        ?: movieFromInfo(streamId, movieInfo)
                        ?: _uiState.value.movie
                    resolved?.let { xtreamRepository.rememberMovies(listOf(it)) }

                    val hasInfo = (
                        !movieInfo.info.plot.isNullOrBlank() ||
                        !movieInfo.info.description.isNullOrBlank() ||
                        !movieInfo.info.genre.isNullOrBlank() ||
                        !movieInfo.info.director.isNullOrBlank() ||
                        !movieInfo.info.cast.isNullOrBlank() ||
                        !movieInfo.info.actors.isNullOrBlank()
                    )

                    _uiState.value = _uiState.value.copy(
                        movie = resolved,
                        info = movieInfo.info,
                        similarMovies = emptyList(),
                        isLoadingInfo = false,
                        infoEmpty = !hasInfo,
                        error = if (resolved == null) "Movie not found" else null,
                    )
                    // Show playable details before ranking a potentially huge catalog.
                    val similar = withContext(Dispatchers.Default) {
                        buildRecommendations(resolved, movieInfo.info, catalogHint)
                    }
                    if (loadedStreamId == streamId) {
                        _uiState.value = _uiState.value.copy(similarMovies = similar)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "MovieDetail: failed to load info for streamId=$streamId")
                if (loadedStreamId == streamId) {
                    // Keep any resolved movie so Play still works even if metadata fails.
                    val fallback = _uiState.value.movie ?: resolveMovie(streamId)
                    _uiState.value = _uiState.value.copy(
                        movie = fallback,
                        isLoadingInfo = false,
                        error = if (fallback == null) (e.message ?: "Movie not found") else null,
                    )
                }
            }
        }
    }

    fun play() {
        val movie = _uiState.value.movie ?: return
        xtreamRepository.pendingStreamTitle = movie.name
        _uiState.value = _uiState.value.copy(
            navigateToPlayer = "player/vod/${movie.streamId}/${movie.containerExtension ?: "mp4"}"
        )
    }

    fun playSimilar(movie: XtreamMovie) {
        xtreamRepository.pendingOpenMovie = movie
        xtreamRepository.rememberMovies(listOf(movie))
        xtreamRepository.pendingStreamTitle = movie.name
        _uiState.value = _uiState.value.copy(
            navigateToPlayer = "player/vod/${movie.streamId}/${movie.containerExtension ?: "mp4"}"
        )
    }

    fun onNavigated() {
        _uiState.value = _uiState.value.copy(navigateToPlayer = null)
    }

    private fun resolveMovie(streamId: Int): XtreamMovie? {
        val pending = xtreamRepository.pendingOpenMovie
        if (pending != null && pending.streamId == streamId) {
            xtreamRepository.pendingOpenMovie = null
            xtreamRepository.rememberMovies(listOf(pending))
            return pending
        }
        return xtreamRepository.getRememberedMovie(streamId)
            ?: xtreamRepository.getCachedVodStreams()?.firstOrNull { it.streamId == streamId }
    }

    private fun movieFromInfo(streamId: Int, info: XtreamMovieInfo): XtreamMovie {
        val md = info.movieData
        return XtreamMovie(
            num = 0,
            name = md?.name?.takeIf { it.isNotBlank() }
                ?: info.info.name?.takeIf { it.isNotBlank() }
                ?: "Movie $streamId",
            streamType = "movie",
            streamId = md?.streamId?.takeIf { it > 0 } ?: streamId,
            streamIcon = info.info.movieImage ?: info.info.coverBig,
            rating = null,
            rating5Based = info.info.rating5Based,
            added = md?.added,
            categoryId = md?.categoryId,
            containerExtension = md?.containerExtension,
            customSid = md?.customSid,
            directSource = md?.directSource,
        )
    }

    private fun buildRecommendations(
        selected: XtreamMovie?,
        selectedInfo: MovieInfo?,
        catalog: List<XtreamMovie>
    ): List<XtreamMovie> {
        if (selected == null) return emptyList()
        val selectedTokens = titleTokens(selected.name)
        val selectedRating = selectedInfo?.rating5Based?.takeIf { it > 0.0 }
            ?: selected.rating5Based.takeIf { it > 0.0 }
            ?: 3.0

        val ranked = catalog.asSequence()
            .filter { it.streamId != selected.streamId && !it.streamIcon.isNullOrBlank() }
            .map { candidate ->
                val sharedTitleTokens = titleTokens(candidate.name).intersect(selectedTokens).size
                val sameCategory = !selected.categoryId.isNullOrBlank() &&
                    candidate.categoryId == selected.categoryId
                val ratingDistance = abs(candidate.rating5Based - selectedRating)
                val score =
                    sharedTitleTokens * 55.0 +
                    (if (sameCategory) 32.0 else 0.0) +
                    (18.0 - ratingDistance * 6.0).coerceAtLeast(0.0) +
                    candidate.rating5Based.coerceAtLeast(0.0) * 2.0 +
                    (if (!candidate.added.isNullOrBlank()) 3.0 else 0.0)
                candidate to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()

        val result = ArrayList<XtreamMovie>(15)
        val perCategory = mutableMapOf<String, Int>()
        for (candidate in ranked) {
            val category = candidate.categoryId.orEmpty()
            val categoryLimit = if (category == selected.categoryId) 8 else 3
            if ((perCategory[category] ?: 0) >= categoryLimit) continue
            result += candidate
            perCategory[category] = (perCategory[category] ?: 0) + 1
            if (result.size == 15) break
        }
        return result
    }

    private fun titleTokens(title: String): Set<String> {
        val stopWords = setOf("the", "a", "an", "and", "of", "in", "to", "part", "movie")
        return title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords && it.toIntOrNull() == null }
            .toSet()
    }
}
