package com.dylandos.iptv.ultimate.data.network

import retrofit2.http.GET
import retrofit2.http.Query

const val TMDB_IMAGE_BASE    = "https://image.tmdb.org/t/p/w500"
const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"

// ── Response models ────────────────────────────────────────────────────────────

data class TmdbMovie(
    val id: Int = 0,
    val title: String = "",
    val overview: String = "",
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double = 0.0,
    val release_date: String = ""
) {
    fun posterUrl()   = poster_path?.let   { "$TMDB_IMAGE_BASE$it"    }
    fun backdropUrl() = backdrop_path?.let { "$TMDB_BACKDROP_BASE$it" }
}

data class TmdbTv(
    val id: Int = 0,
    val name: String = "",
    val overview: String = "",
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val vote_average: Double = 0.0,
    val first_air_date: String = ""
) {
    fun posterUrl()   = poster_path?.let   { "$TMDB_IMAGE_BASE$it"    }
    fun backdropUrl() = backdrop_path?.let { "$TMDB_BACKDROP_BASE$it" }
}

data class TmdbMovieSearchResponse(val results: List<TmdbMovie> = emptyList())
data class TmdbTvSearchResponse   (val results: List<TmdbTv>    = emptyList())

// ── API service ────────────────────────────────────────────────────────────────

/**
 * Retrofit service for The Movie Database (TMDB).
 *
 * Fetch high-resolution posters and backdrops for VOD and Series details screens.
 * Inject via Hilt; the Retrofit instance is provided by NetworkModule with base URL
 * "https://api.themoviedb.org/3/".
 *
 * Usage example:
 *   val result = tmdbApiService.searchMovie("Breaking Bad", apiKey = BuildConfig.TMDB_API_KEY)
 *   val posterUrl = result.results.firstOrNull()?.posterUrl()
 */
interface TmdbApiService {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query")    title:    String,
        @Query("api_key")  apiKey:   String,
        @Query("language") language: String = "en-US",
        @Query("page")     page:     Int    = 1
    ): TmdbMovieSearchResponse

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query")    title:    String,
        @Query("api_key")  apiKey:   String,
        @Query("language") language: String = "en-US",
        @Query("page")     page:     Int    = 1
    ): TmdbTvSearchResponse
}
