package com.dylandos.iptv.ultimate.data.network

import com.dylandos.iptv.ultimate.data.model.*
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * DYLANDOS IPTV - Xtream Codes API Interface
 * 
 * Retrofit service interface for Xtream Codes API
 */
interface XtreamApiService {

    /**
     * Authenticate and get account info
     */
    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String
    ): XtreamAuth

    /**
     * Get live TV categories
     */
    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): List<XtreamCategory>

    /**
     * Get live streams/channels
     */
    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamChannel>

    /**
     * Get VOD categories
     */
    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<XtreamCategory>

    /**
     * Get VOD streams (movies)
     */
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamMovie>

    /**
     * Get movie info
     */
    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_info",
        @Query("vod_id") vodId: Int
    ): XtreamMovieInfo

    /**
     * Get series categories
     */
    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): List<XtreamCategory>

    /**
     * Get series list
     */
    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null
    ): List<XtreamSeries>

    /**
     * Get series info with episodes
     */
    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: Int
    ): XtreamSeriesInfo

    /**
     * Get short EPG for a stream
     */
    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: Int,
        @Query("limit") limit: Int = 10
    ): XtreamEpgListing
}
