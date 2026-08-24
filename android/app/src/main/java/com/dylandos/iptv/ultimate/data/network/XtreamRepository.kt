package com.dylandos.iptv.ultimate.data.network

import android.util.Base64
import com.dylandos.iptv.ultimate.data.db.dao.HiddenCategoryDao
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import com.dylandos.iptv.ultimate.data.db.entity.HiddenCategoryEntity
import com.dylandos.iptv.ultimate.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class PendingSeriesPlaybackContext(
    val episodeId: String,
    val seriesId: Int,
    val seriesName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String
)

object CategoryContentType {
    const val LIVE = "live"
    const val MOVIES = "vod"
    const val SERIES = "series"
}

/**
 * DYLANDOS IPTV - Xtream Repository
 *
 * Uses OkHttp directly so the server URL is fully dynamic.
 * Retrofit cannot change base URL at runtime; OkHttp has no such restriction.
 */
@Singleton
class XtreamRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val hiddenCategoryDao: HiddenCategoryDao,
    private val channelDao: com.dylandos.iptv.ultimate.data.db.dao.ChannelDao,
    private val epgProgramDao: com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
) {
    var serverUrl: String = ""
        private set
    var username: String = ""
        private set
    var password: String = ""
        private set
    var isConnected: Boolean = false
        private set
    /**
     * Provider panel timezone from `server_info.timezone` (e.g. "America/New_York").
     * Used for offset-less XMLTV and short-EPG wall-clock reconciliation — never the
     * Fire TV device zone, which was shifting guide times on Firestick.
     */
    @Volatile var providerTimezoneId: String? = null
        private set
    private val activeProviderKey = MutableStateFlow("")
    /** Stable, credential-free identity for provider-scoped local features. */
    val currentProviderKey: String get() = activeProviderKey.value

    /** Resolved provider zone; UTC when unknown (safer than device-local for IPTV). */
    fun providerTimeZone(): TimeZone {
        val raw = providerTimezoneId?.trim().orEmpty()
        if (raw.isEmpty()) return TimeZone.getTimeZone("UTC")
        val zone = TimeZone.getTimeZone(raw)
        // Unknown IDs fall back to GMT in Java — treat that as failure when raw wasn't GMT/UTC.
        if (zone.id == "GMT" && !raw.equals("GMT", ignoreCase = true) &&
            !raw.equals("UTC", ignoreCase = true)
        ) {
            Timber.w("Unknown provider timezone '$raw' — using UTC")
            return TimeZone.getTimeZone("UTC")
        }
        return zone
    }

    // ── Channel zapping state (set by LiveTvScreen/GuideScreen on channel play) ──
    var liveChannelList: List<XtreamChannel> = emptyList()
    var liveChannelIndex: Int = 0

    // ── Pending stream title — set by ViewModels/Routes just before navigating ──
    // PlayerViewModel reads + clears this in loadStream() to display the correct
    // human-readable title instead of the numeric stream ID.
    @Volatile var pendingStreamTitle: String? = null
    @Volatile var pendingSeriesPlaybackContext: PendingSeriesPlaybackContext? = null

    // ── Pending series detail open — set by SearchScreen before navigating to ──
    // SeriesScreen. SeriesViewModel reads + clears this after data loads so the
    // detail popup for the tapped search result auto-opens.
    @Volatile var pendingOpenSeriesId: Int? = null
    /** Full search-result series when available — avoids needing the full catalog in RAM. */
    @Volatile var pendingOpenSeries: XtreamSeries? = null

    /**
     * Movie stashed just before navigating to MovieDetail — required because
     * per-category VOD loading no longer keeps the full catalog in [vodStreamsCache].
     */
    @Volatile var pendingOpenMovie: XtreamMovie? = null

    /** Soft index of recently seen movies (category pages, home, search). */
    private val rememberedMovies = object : LinkedHashMap<Int, XtreamMovie>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, XtreamMovie>?): Boolean =
            size > 2_500
    }

    /** Soft index of recently seen series (category pages, home, search). */
    private val rememberedSeries = object : LinkedHashMap<Int, XtreamSeries>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, XtreamSeries>?): Boolean =
            size > 2_500
    }

    fun rememberMovies(movies: Collection<XtreamMovie>) {
        synchronized(rememberedMovies) {
            for (movie in movies) {
                if (movie.streamId > 0) rememberedMovies[movie.streamId] = movie
            }
        }
    }

    fun getRememberedMovie(streamId: Int): XtreamMovie? =
        synchronized(rememberedMovies) { rememberedMovies[streamId] }

    fun rememberSeries(series: Collection<XtreamSeries>) {
        synchronized(rememberedSeries) {
            for (item in series) {
                if (item.seriesId > 0) rememberedSeries[item.seriesId] = item
            }
        }
    }

    fun getRememberedSeries(seriesId: Int): XtreamSeries? =
        synchronized(rememberedSeries) { rememberedSeries[seriesId] }

    /** Fast lookup of a VOD movie title from the in-memory 5-min cache. */
    fun getCachedVodTitle(streamId: Int): String? =
        getRememberedMovie(streamId)?.name
            ?: vodStreamsCache?.data?.find { it.streamId == streamId }?.name

    /** Returns the full cached VOD stream list (null if not yet loaded or stale). */
    fun getCachedVodStreams(): List<XtreamMovie>? =
        vodStreamsCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.data

    fun getCachedLiveStreams(): List<XtreamChannel>? =
        liveStreamsCache?.takeUnless { it.isStale(LIVE_CACHE_TTL_MS) }?.data

    fun getCachedSeries(): List<XtreamSeries>? =
        seriesCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.data

    // ── In-memory cache ────────────────────────────────────────────────────────
    // Live categories/streams: 5-min TTL (changes more frequently)
    // VOD/Series: 15-min TTL — large lists that rarely change between browsing sessions
    //   Previously 5 min, which caused full reload + focus reset while browsing movies.
    // VodInfo: 15-min TTL — avoids re-fetching detail on every back/forward navigation
    //   and prevents Xtream server rate-limiting when user browses multiple movies quickly.
    private companion object {
        const val LIVE_CACHE_TTL_MS  = 5  * 60 * 1_000L   // 5 minutes for live data
        const val VOD_CACHE_TTL_MS   = 15 * 60 * 1_000L   // 15 minutes for VOD lists
        const val INFO_CACHE_TTL_MS  = 15 * 60 * 1_000L   // 15 minutes for movie/series detail
        const val SHORT_EPG_CACHE_TTL_MS = 15 * 60 * 1_000L
    }

    private data class CacheEntry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isStale(ttlMs: Long) = System.currentTimeMillis() - timestamp > ttlMs
    }

    private var liveCategCache: CacheEntry<List<XtreamCategory>>? = null
    private var liveStreamsCache: CacheEntry<List<XtreamChannel>>? = null
    private var vodCategCache: CacheEntry<List<XtreamCategory>>? = null
    private var vodStreamsCache: CacheEntry<List<XtreamMovie>>? = null
    private var seriesCategCache: CacheEntry<List<XtreamCategory>>? = null
    private var seriesCache: CacheEntry<List<XtreamSeries>>? = null
    private val liveStreamsMutex = Mutex()
    private val vodStreamsMutex = Mutex()
    private val seriesMutex = Mutex()
    // VodInfo cache: keyed by streamId, prevents rate-limiting on rapid movie opens
    private val vodInfoCache = mutableMapOf<Int, CacheEntry<XtreamMovieInfo>>()
    // S-029: seriesInfoCache — capped at 50 entries (series info is heavier: full episode manifest)
    private val seriesInfoCache = mutableMapOf<Int, CacheEntry<XtreamSeriesInfo>>()
    private val shortEpgBatchMutex = Mutex()
    private val shortEpgCache = object :
        LinkedHashMap<Int, CacheEntry<List<XtreamEpgProgram>>>(256, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, CacheEntry<List<XtreamEpgProgram>>>?
        ): Boolean = size > 3_000
    }

    fun invalidateCache() {
        liveCategCache = null; liveStreamsCache = null
        vodCategCache = null; vodStreamsCache = null
        seriesCategCache = null; seriesCache = null
        vodInfoCache.clear()
        seriesInfoCache.clear()
        synchronized(shortEpgCache) { shortEpgCache.clear() }
    }

    // ── Credentials ────────────────────────────────────────────────────

    fun connect(profile: XtreamProfile) {
        this.serverUrl = profile.serverUrl.trimEnd('/')
        this.username = profile.username
        this.password = profile.password
        this.isConnected = true
        this.providerTimezoneId = null
        activeProviderKey.value = providerKey(this.serverUrl, this.username)
        invalidateCache()   // fresh login = stale cache
        liveChannelList = emptyList()
        liveChannelIndex = 0
        Timber.d("Connected to Xtream server: $serverUrl")
    }

    /**
     * Fetch `player_api.php` server_info and cache [providerTimezoneId].
     * Safe to call after [connect]; failures leave timezone as UTC fallback.
     */
    suspend fun refreshProviderTimezone() = withContext(Dispatchers.IO) {
        if (!isConnected || serverUrl.isEmpty()) return@withContext
        try {
            val body = get("$serverUrl/player_api.php?username=$username&password=$password")
            val auth = gson.fromJson(body, XtreamAuth::class.java)
            val tz = auth.serverInfo.timezone?.trim()?.takeIf { it.isNotEmpty() }
            providerTimezoneId = tz
            Timber.i("Provider timezone: ${tz ?: "unknown (UTC fallback)"}")
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh provider timezone")
        }
    }

    /**
     * Switch the active provider (Module 3.2 — account-cache isolation).
     *
     * Performs a mandatory cascade clear of the previous account's persisted catalog
     * (channels + EPG in Room) and all in-memory caches BEFORE the new provider's
     * credentials go live, so account A's channels/guide can never leak into account B.
     * Call this instead of [connect] from the account-switch / re-credential flows.
     */
    suspend fun switchAccount(profile: XtreamProfile) {
        clearCachedCatalog()
        connect(profile)
    }

    /**
     * Cascade-delete the persisted catalog cache (channels, EPG) and reset every
     * in-memory cache/StateFlow-backed structure. Safe to call off the main thread.
     */
    suspend fun clearCachedCatalog() = withContext(Dispatchers.IO) {
        runCatching { channelDao.deleteAll() }
            .onFailure { Timber.w(it, "Account switch: failed to clear channels table") }
        runCatching { epgProgramDao.deleteAll() }
            .onFailure { Timber.w(it, "Account switch: failed to clear epg_programs table") }
        liveChannelList = emptyList()
        liveChannelIndex = 0
        invalidateCache()
        Timber.i("Account switch: cleared persisted channels + EPG and in-memory caches")
    }

    fun disconnect() {
        serverUrl = ""; username = ""; password = ""
        isConnected = false
        providerTimezoneId = null
        activeProviderKey.value = ""
        invalidateCache()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeHiddenCategoryIds(contentType: String): Flow<Set<String>> =
        activeProviderKey.flatMapLatest { providerKey ->
            if (providerKey.isBlank()) {
                flowOf(emptySet())
            } else {
                hiddenCategoryDao.observeHiddenIds(providerKey, contentType).map { it.toSet() }
            }
        }

    fun observeVisibleCategories(
        contentType: String,
        categories: Flow<List<XtreamCategory>>
    ): Flow<List<XtreamCategory>> =
        combine(categories, observeHiddenCategoryIds(contentType)) { items, hiddenIds ->
            items.filterNot { it.categoryId in hiddenIds }
        }

    suspend fun filterVisibleCategories(
        contentType: String,
        categories: List<XtreamCategory>
    ): List<XtreamCategory> {
        val providerKey = activeProviderKey.value
        if (providerKey.isBlank()) return categories
        val hiddenIds = hiddenCategoryDao.getHiddenIds(providerKey, contentType).toHashSet()
        return categories.filterNot { it.categoryId in hiddenIds }
    }

    suspend fun toggleCategoryVisibility(contentType: String, categoryId: String) {
        val providerKey = activeProviderKey.value
        if (providerKey.isBlank() || categoryId.isBlank()) return
        if (categoryId in hiddenCategoryDao.getHiddenIds(providerKey, contentType)) {
            hiddenCategoryDao.show(providerKey, contentType, categoryId)
        } else {
            hiddenCategoryDao.hide(HiddenCategoryEntity(providerKey, contentType, categoryId))
        }
    }

    suspend fun showAllCategories(contentType: String) {
        val providerKey = activeProviderKey.value
        if (providerKey.isNotBlank()) hiddenCategoryDao.showAll(providerKey, contentType)
    }

    suspend fun importHiddenCategoriesIfEmpty(contentType: String, categoryIds: Set<String>) {
        val providerKey = activeProviderKey.value
        if (providerKey.isBlank() || categoryIds.isEmpty()) return
        if (hiddenCategoryDao.getHiddenIds(providerKey, contentType).isNotEmpty()) return
        categoryIds.forEach { categoryId ->
            hiddenCategoryDao.hide(HiddenCategoryEntity(providerKey, contentType, categoryId))
        }
    }

    private fun providerKey(serverUrl: String, username: String): String {
        val raw = "${serverUrl.trimEnd('/').lowercase(Locale.US)}|${username.trim()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /** Build a player_api.php URL with a given action and optional extra params. */
    private fun apiUrl(action: String, vararg extras: Pair<String, String>): String {
        val base = "$serverUrl/player_api.php?username=$username&password=$password&action=$action"
        return if (extras.isEmpty()) base
        else base + extras.joinToString("") { "&${it.first}=${it.second}" }
    }

    /** Execute a GET request and return the response body string, or throw. */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
        return response.body?.string() ?: throw Exception("Empty response body")
    }

    /** Safely parse JSON array. Returns emptyList if the server returns '{}' instead of '[]'. */
    private fun <T> parseJsonArray(body: String, clazz: Class<T>): List<T> {
        val trimmed = body.trim()
        if (trimmed == "{}" || trimmed == "null" || trimmed.isEmpty()) return emptyList()
        val listType = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, clazz).type
        return gson.fromJson(body, listType) ?: emptyList()
    }

    /**
     * Parse large catalog arrays directly from the response stream.
     *
     * Keeping a multi-megabyte JSON String alive beside the parsed catalog creates a
     * large temporary heap spike on Fire TV. Streaming avoids that duplicate allocation.
     */
    private fun <T> getJsonArray(url: String, clazz: Class<T>, maxItems: Int = Int.MAX_VALUE): List<T> {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw Exception("Empty response body")
            val reader = com.google.gson.stream.JsonReader(InputStreamReader(body.byteStream()))
            return try {
                when (reader.peek()) {
                    com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                        val result = ArrayList<T>()
                        reader.beginArray()
                        // A5: stop early at the cap — never parse a 100k-item catalog when the
                        // caller only needs the first N rows. Closing the reader mid-array
                        // discards the tail without parsing it (saves tens of MB on Firestick).
                        while (reader.hasNext() && result.size < maxItems) {
                            result.add(gson.fromJson(reader, clazz))
                        }
                        result
                    }
                    com.google.gson.stream.JsonToken.NULL -> {
                        reader.nextNull()
                        emptyList()
                    }
                    else -> {
                        // Several Xtream servers use {} for an empty result.
                        reader.skipValue()
                        emptyList()
                    }
                }
            } finally {
                reader.close()
            }
        }
    }

    // ── Authentication ─────────────────────────────────────────────────

    /** Validate credentials against the server. Returns XtreamAuth on success. */
    suspend fun authenticate(): Result<XtreamAuth> = withContext(Dispatchers.IO) {
        try {
            val url = "$serverUrl/player_api.php?username=$username&password=$password"
            val body = get(url)
            val auth = gson.fromJson(body, XtreamAuth::class.java)
            if (auth.userInfo.auth == 0) {
                Result.failure(Exception("Invalid username or password"))
            } else {
                providerTimezoneId = auth.serverInfo.timezone?.trim()?.takeIf { it.isNotEmpty() }
                Timber.i("Authenticated; provider timezone=${providerTimezoneId ?: "unknown"}")
                Result.success(auth)
            }
        } catch (e: Exception) {
            Timber.e(e, "Authentication failed")
            Result.failure(e)
        }
    }

    /** Test-only: try connecting and return true/false + error message. */
    suspend fun testConnection(serverUrl: String, username: String, password: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${serverUrl.trimEnd('/')}/player_api.php?username=$username&password=$password"
                val body = get(url)
                val auth = gson.fromJson(body, XtreamAuth::class.java)
                if (auth.userInfo.auth == 0) Pair(false, "Invalid username or password")
                else Pair(true, "Connected successfully")
            } catch (e: Exception) {
                Pair(false, e.message ?: "Connection failed")
            }
        }

    // ── Live TV ────────────────────────────────────────────────────────

    suspend fun getLiveCategories(): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        liveCategCache?.takeUnless { it.isStale(LIVE_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
        try {
            val body = get(apiUrl("get_live_categories"))
            val list = parseJsonArray(body, XtreamCategory::class.java)
            liveCategCache = CacheEntry(list)
            Result.success(list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch live categories")
            Result.failure(e)
        }
    }

    suspend fun getLiveStreams(categoryId: String? = null): Result<List<XtreamChannel>> =
        withContext(Dispatchers.IO) {
            if (categoryId == null) {
                liveStreamsCache?.takeUnless { it.isStale(LIVE_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
                return@withContext liveStreamsMutex.withLock {
                    liveStreamsCache?.takeUnless { it.isStale(LIVE_CACHE_TTL_MS) }?.let {
                        return@withLock Result.success(it.data)
                    }
                    try {
                        val list = getJsonArray(apiUrl("get_live_streams"), XtreamChannel::class.java)
                        liveStreamsCache = CacheEntry(list)
                        Result.success(list)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to fetch live streams")
                        Result.failure(e)
                    }
                }
            }
            try {
                val url = apiUrl("get_live_streams", "category_id" to categoryId)
                val list = getJsonArray(url, XtreamChannel::class.java)
                Result.success(list)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch live streams")
                Result.failure(e)
            }
        }

    fun getLiveStreamUrl(streamId: Int, format: String = "ts"): String =
        "$serverUrl/live/$username/$password/$streamId.$format"

    /**
     * Xtream catch-up / archive endpoint.
     * Start format: yyyy-MM-dd:HH-mm, duration in minutes.
     */
    fun getTimeshiftStreamUrl(
        streamId: Int,
        startTimestampSec: Long,
        durationMinutes: Int,
        extension: String = "ts"
    ): String {
        val safeDuration = durationMinutes.coerceAtLeast(1)
        val startUtc = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startTimestampSec.coerceAtLeast(0L) * 1000L))
        val safeExt = extension.ifBlank { "ts" }
        return "$serverUrl/timeshift/$username/$password/$safeDuration/$startUtc/$streamId.$safeExt"
    }

    // ── VOD / Movies ───────────────────────────────────────────────────

    suspend fun getVodCategories(): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        vodCategCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
        try {
            val body = get(apiUrl("get_vod_categories"))
            val list = parseJsonArray(body, XtreamCategory::class.java)
            vodCategCache = CacheEntry(list)
            Result.success(list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch VOD categories")
            Result.failure(e)
        }
    }

    suspend fun getVodStreams(
        categoryId: String? = null,
        maxItems: Int = Int.MAX_VALUE
    ): Result<List<XtreamMovie>> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            // Full-catalog fetches (Home stats, Search) share the 15-min cache.
            if (maxItems == Int.MAX_VALUE) {
                vodStreamsCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
                return@withContext vodStreamsMutex.withLock {
                    vodStreamsCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let {
                        return@withLock Result.success(it.data)
                    }
                    try {
                        val list = getJsonArray(apiUrl("get_vod_streams"), XtreamMovie::class.java, maxItems)
                        yield()
                        vodStreamsCache = CacheEntry(list)
                        rememberMovies(list)
                        Result.success(list)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to fetch VOD streams")
                        Result.failure(e)
                    }
                }
            }
            // A5: bounded browse fetch (Movies grid). Bypass the shared cache so Home
            // stats / search still see the full catalog, and only materialize the rows
            // the grid actually pages — kills the 50k-item transient spike on Firestick.
            try {
                val list = getJsonArray(apiUrl("get_vod_streams"), XtreamMovie::class.java, maxItems)
                yield()
                rememberMovies(list)
                Result.success(list)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch VOD streams")
                Result.failure(e)
            }
        }
        try {
            val url = apiUrl("get_vod_streams", "category_id" to categoryId!!)
            val list = getJsonArray(url, XtreamMovie::class.java, maxItems)
            rememberMovies(list)
            Result.success(list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch VOD streams")
            Result.failure(e)
        }
    }

    suspend fun getVodInfo(vodId: Int): Result<XtreamMovieInfo> = withContext(Dispatchers.IO) {
        // Check in-memory cache first — prevents Xtream server rate-limiting when the
        // user browses multiple movies quickly (back → next movie → back → next movie).
        vodInfoCache[vodId]?.takeUnless { it.isStale(INFO_CACHE_TTL_MS) }?.let {
            return@withContext Result.success(it.data)
        }
        // Retry up to 2 times with 1s backoff for transient network failures.
        // Without retry, a single dropped packet causes "no info" for the entire session.
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) delay(1000L * attempt)  // 1s, 2s backoff
                val body = get(apiUrl("get_vod_info", "vod_id" to vodId.toString()))
                val info = gson.fromJson(body, XtreamMovieInfo::class.java)
                // HIGH-005: Cap cache at 100 entries (FIFO eviction) to prevent
                // unbounded growth on providers with 10,000+ VOD titles (~50–150 MB).
                // Cap at 30 entries (~6MB) — reduced from 100 to ease Firestick heap pressure.
                // Cap at 80 entries — browsing many movies was evicting useful detail
                // too aggressively (30) and contributing to "Movie not found" UX.
                if (vodInfoCache.size >= 80) vodInfoCache.remove(vodInfoCache.keys.first())
                vodInfoCache[vodId] = CacheEntry(info)
                info.movieData?.let { md ->
                    rememberMovies(
                        listOf(
                            XtreamMovie(
                                num = 0,
                                name = md.name ?: info.info.name ?: "Movie $vodId",
                                streamType = "movie",
                                streamId = md.streamId.takeIf { it > 0 } ?: vodId,
                                streamIcon = info.info.movieImage ?: info.info.coverBig,
                                rating = null,
                                rating5Based = info.info.rating5Based,
                                added = md.added,
                                categoryId = md.categoryId,
                                containerExtension = md.containerExtension,
                                customSid = md.customSid,
                                directSource = md.directSource
                            )
                        )
                    )
                }
                return@withContext Result.success(info)
            } catch (e: Exception) {
                lastException = e
                Timber.w("getVodInfo attempt ${attempt + 1} failed for vodId=$vodId: ${e.message}")
            }
        }
        Timber.e(lastException, "Failed to fetch VOD info after 3 attempts for vodId=$vodId")
        Result.failure(lastException ?: Exception("Unknown error"))
    }

    fun getVodStreamUrl(streamId: Int, extension: String): String =
        "$serverUrl/movie/$username/$password/$streamId.$extension"

    // ── Series ─────────────────────────────────────────────────────────

    suspend fun getSeriesCategories(): Result<List<XtreamCategory>> = withContext(Dispatchers.IO) {
        seriesCategCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
        try {
            val body = get(apiUrl("get_series_categories"))
            val list = parseJsonArray(body, XtreamCategory::class.java)
            seriesCategCache = CacheEntry(list)
            Result.success(list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch series categories")
            Result.failure(e)
        }
    }

    suspend fun getSeries(
        categoryId: String? = null,
        maxItems: Int = Int.MAX_VALUE
    ): Result<List<XtreamSeries>> = withContext(Dispatchers.IO) {
        if (categoryId == null) {
            // Full-catalog fetches (Home stats, Search) share the 15-min cache.
            if (maxItems == Int.MAX_VALUE) {
                seriesCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let { return@withContext Result.success(it.data) }
                return@withContext seriesMutex.withLock {
                    seriesCache?.takeUnless { it.isStale(VOD_CACHE_TTL_MS) }?.let {
                        return@withLock Result.success(it.data)
                    }
                    try {
                        val list = getJsonArray(apiUrl("get_series"), XtreamSeries::class.java, maxItems)
                        yield()
                        seriesCache = CacheEntry(list)
                        rememberSeries(list)
                        Result.success(list)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to fetch series")
                        Result.failure(e)
                    }
                }
            }
            // A5: bounded browse fetch (Series grid) — see getVodStreams().
            try {
                val list = getJsonArray(apiUrl("get_series"), XtreamSeries::class.java, maxItems)
                yield()
                rememberSeries(list)
                Result.success(list)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch series")
                Result.failure(e)
            }
        }
        try {
            val url = apiUrl("get_series", "category_id" to categoryId!!)
            val list = getJsonArray(url, XtreamSeries::class.java, maxItems)
            rememberSeries(list)
            Result.success(list)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch series")
            Result.failure(e)
        }
    }

    suspend fun getSeriesInfo(seriesId: Int): Result<XtreamSeriesInfo> = withContext(Dispatchers.IO) {
        // S-029: Return cached entry if still fresh (same TTL as VOD info)
        seriesInfoCache[seriesId]?.takeUnless { it.isStale(INFO_CACHE_TTL_MS) }?.let {
            return@withContext Result.success(it.data)
        }
        var lastException: Exception? = null
        repeat(3) { attempt ->
            try {
                if (attempt > 0) delay(750L * attempt)
                val body = get(apiUrl("get_series_info", "series_id" to seriesId.toString()))
                val info = gson.fromJson(body, XtreamSeriesInfo::class.java)
                // Cap at 40 — browsing many series was thrashing the tiny 10-entry cache
                // and making Favorites / reopen look "not found" after a session.
                if (seriesInfoCache.size >= 40) seriesInfoCache.remove(seriesInfoCache.keys.first())
                seriesInfoCache[seriesId] = CacheEntry(info)
                return@withContext Result.success(info)
            } catch (e: Exception) {
                lastException = e
                Timber.w("getSeriesInfo attempt ${attempt + 1} failed for seriesId=$seriesId: ${e.message}")
            }
        }
        Timber.e(lastException, "Failed to fetch series info after 3 attempts for seriesId=$seriesId")
        Result.failure(lastException ?: Exception("Unknown error"))
    }

    fun getSeriesStreamUrl(episodeId: String, extension: String): String =
        "$serverUrl/series/$username/$password/$episodeId.$extension"

    // ── EPG ────────────────────────────────────────────────────────────

    /**
     * Xtream API encodes all EPG title and description fields in Base64.
     * This helper safely decodes them, falling back to the raw value on error.
     */
    private fun decodeEpgString(raw: String): String =
        try { String(Base64.decode(raw, Base64.DEFAULT)).trim() }
        catch (_: Exception) { raw }

    /**
     * Xtream panels normally return epoch seconds, but a few return milliseconds.
     * Normalizing at the network boundary keeps EPG comparisons in UTC while every
     * screen formats those instants in the Fire TV device's local timezone.
     */
    private fun normalizeEpgEpochSeconds(value: Long): Long =
        if (value > 20_000_000_000L) value / 1_000L else value

    /** Parse an offset-less provider wall-clock value when no usable Unix epoch exists. */
    private fun parseEpgWallClockSeconds(raw: String, timeZone: TimeZone): Long {
        val value = raw.trim()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?$").matches(value)) {
            return 0L
        }
        val pattern = if (value.length == 16) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd HH:mm:ss"
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                this.timeZone = timeZone
            }.parse(value.replace('T', ' '))?.time?.div(1_000L) ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Xtream XMLTV and short-EPG wall-clock values are conventionally UTC when they omit
     * an offset. Applying server_info.timezone to those values creates an exact whole-zone
     * error (six hours in Mountain daylight time). Prefer a valid epoch, but correct the
     * common panel bug where its epoch was generated from a UTC string as server-local time.
     */
    private fun reconcileProviderEpgTime(raw: String, epochSeconds: Long): Long {
        val providerEpoch = normalizeEpgEpochSeconds(epochSeconds)
        val utcWall = parseEpgWallClockSeconds(raw, TimeZone.getTimeZone("UTC"))
        val providerWall = parseEpgWallClockSeconds(raw, providerTimeZone())
        if (providerEpoch > 1_000_000_000L) {
            if (utcWall > 0L && providerWall > 0L && utcWall != providerWall) {
                val matchesUtc = kotlin.math.abs(providerEpoch - utcWall) <= 180L
                val matchesProvider = kotlin.math.abs(providerEpoch - providerWall) <= 180L
                if (matchesProvider && !matchesUtc) {
                    Timber.d(
                        "EPG: correcting server-local epoch $providerEpoch → UTC wall $utcWall " +
                            "(provider=${providerTimeZone().id})"
                    )
                    return utcWall
                }
            }
            return providerEpoch
        }

        return utcWall.takeIf { it > 0L } ?: providerEpoch
    }

    /**
     * Conservative provider-panel alignment: only shifts a listing when the provider
     * explicitly flags a programme as now playing AND the flagged programme lands on
     * "now" after a whole-hour shift. Do NOT run this over Room XMLTV rows —
     * [XmltvParser] output is already absolute-correct; see [EpgTimeAligner].
     */
    fun alignEpgListing(programs: List<XtreamEpgProgram>): List<XtreamEpgProgram> =
        alignEpgProgramsToNow(programs)

    /**
     * If most listings sit hours ahead/behind "now", snap by whole hours so ON NOW
     * matches real wall-clock programming (fixes residual panel skew).
     */
    private fun alignEpgProgramsToNow(programs: List<XtreamEpgProgram>): List<XtreamEpgProgram> {
        // v5.0: the old hour-snapping heuristic here could not distinguish a provider
        // TZ skew from a schedule gap at "now" — with only timestamps, either way exactly
        // one programme lands on now after a whole-hour shift. Applied to already-correct
        // Room XMLTV rows it shifted whole grids by the classic 4–9 h offsets (the
        // "guide/Live TV is ~4 hours ahead" bug). Now delegated to the conservative
        // flag-only [EpgTimeAligner]; see its KDoc for the full rationale.
        val nowSec = System.currentTimeMillis() / 1000L
        val aligned = EpgTimeAligner.align(programs, nowSec)
        if (aligned !== programs) {
            Timber.i("EPG: applied conservative alignment to ${programs.size} programmes")
        }
        return aligned
    }

    /** Apply Base64 decoding and timestamp normalization to every program. */
    private fun decodeEpgPrograms(
        programs: List<XtreamEpgProgram>,
        timeOffsetHours: Int = 0
    ): List<XtreamEpgProgram> {
        val offsetSec = timeOffsetHours * 3600L
        val decoded = programs.map { p ->
            val startText = decodeEpgString(p.start)
            val endText = decodeEpgString(p.end)
            val rawStartSec = reconcileProviderEpgTime(startText, p.startTimestamp)
            val rawEndSec = reconcileProviderEpgTime(endText, p.stopTimestamp)
            p.copy(
                title       = decodeEpgString(p.title),
                description = p.description?.let { decodeEpgString(it) },
                start       = startText,
                end         = endText,
                startTimestamp = if (rawStartSec > 0L) rawStartSec + offsetSec else 0L,
                stopTimestamp  = if (rawEndSec > 0L) rawEndSec + offsetSec else 0L
            )
        }
        return alignEpgProgramsToNow(decoded)
    }

    /** Clear in-memory short EPG cache when timezone/offset or account changes. */
    fun clearEpgMemoryCache() {
        synchronized(shortEpgCache) {
            shortEpgCache.clear()
        }
    }

    /**
     * Get short EPG (current + next N programs) for a single live stream.
     * Used in Live TV channel list to show "now playing" info.
     */
    suspend fun getShortEpg(
        streamId: Int,
        limit: Int = 4,
        timeOffsetHours: Int = 0
    ): Result<List<XtreamEpgProgram>> =
        withContext(Dispatchers.IO) {
            synchronized(shortEpgCache) {
                shortEpgCache[streamId]
                    ?.takeUnless { it.isStale(SHORT_EPG_CACHE_TTL_MS) }
                    ?.let { return@withContext Result.success(it.data.take(limit)) }
            }
            try {
                val body = get(apiUrl("get_short_epg",
                    "stream_id" to streamId.toString(),
                    "limit" to limit.toString()))
                val listing = gson.fromJson(body, XtreamEpgListing::class.java)
                val decoded = decodeEpgPrograms(listing.epgListings, timeOffsetHours)
                synchronized(shortEpgCache) {
                    shortEpgCache[streamId] = CacheEntry(decoded)
                }
                Result.success(decoded)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch short EPG for stream $streamId")
                Result.failure(e)
            }
        }

    /**
     * Get full simple data table EPG for a single live stream.
     * Used in the cable-style Guide to populate a 4-hour window.
     * FIX: Base64-decode all titles/descriptions before returning.
     */
    suspend fun getSimpleDataTable(
        streamId: Int,
        timeOffsetHours: Int = 0
    ): Result<List<XtreamEpgProgram>> =
        withContext(Dispatchers.IO) {
            try {
                val body = get(apiUrl("get_simple_data_table", "stream_id" to streamId.toString()))
                val listing = gson.fromJson(body, XtreamEpgListing::class.java)
                Result.success(decodeEpgPrograms(listing.epgListings, timeOffsetHours))
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch EPG table for stream $streamId")
                Result.failure(e)
            }
        }

    /**
     * Batch load short EPG for a list of channels.
     * Returns a map of streamId -> list of decoded programs.
     */
    suspend fun batchShortEpg(
        streamIds: List<Int>,
        limit: Int = 4,
        timeOffsetHours: Int = 0
    ): Map<Int, List<XtreamEpgProgram>> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<Int, List<XtreamEpgProgram>>()
        val missingIds = ArrayList<Int>(streamIds.size)
        synchronized(shortEpgCache) {
            streamIds.distinct().forEach { id ->
                val cached = shortEpgCache[id]?.takeUnless { it.isStale(SHORT_EPG_CACHE_TTL_MS) }
                if (cached != null) result[id] = cached.data.take(limit) else missingIds += id
            }
        }
        if (missingIds.isNotEmpty()) {
            shortEpgBatchMutex.withLock {
                val stillMissing = ArrayList<Int>(missingIds.size)
                synchronized(shortEpgCache) {
                    missingIds.forEach { id ->
                        val cached = shortEpgCache[id]?.takeUnless { it.isStale(SHORT_EPG_CACHE_TTL_MS) }
                        if (cached != null) result[id] = cached.data.take(limit) else stillMissing += id
                    }
                }
                stillMissing.chunked(10).forEach { chunk ->
                    coroutineScope {
                        chunk.map { id ->
                            async {
                                id to try {
                                    val body = get(apiUrl("get_short_epg",
                                        "stream_id" to id.toString(),
                                        "limit" to limit.toString()))
                                    val programs = gson.fromJson(body, XtreamEpgListing::class.java).epgListings
                                    decodeEpgPrograms(programs, timeOffsetHours)
                                } catch (e: Exception) {
                                    Timber.w("EPG fetch failed for $id: ${e.message}")
                                    emptyList<XtreamEpgProgram>()
                                }
                            }
                        }.awaitAll().forEach { (id, programs) ->
                            result[id] = programs
                            synchronized(shortEpgCache) {
                                shortEpgCache[id] = CacheEntry(programs)
                            }
                        }
                    }
                    kotlinx.coroutines.delay(140L)
                }
            }
        }
        result
    }

    // ── Connection warm-up ──────────────────────────────────────────────

    /**
     * Pre-warm OkHttp connection pool with a HEAD request.
     * Called by SplashViewModel on cold start so the first real API call is near-instant.
     * No-op if not connected (fresh install / not logged in yet).
     */
    fun prewarm() {
        if (!isConnected || serverUrl.isEmpty()) return
        try {
            val request = Request.Builder()
                .url("$serverUrl/player_api.php?username=$username&password=$password")
                .head()
                .build()
            okHttpClient.newCall(request).execute().close()
            Timber.d("OkHttp connection pool pre-warmed")
        } catch (e: Exception) {
            Timber.w("Prewarm failed (non-critical): ${e.message}")
        }
    }

    // ── XMLTV EPG ────────────────────────────────────────────────────────

    /**
     * Fetch the full 7-day XMLTV EPG file from /xmltv.php and parse it.
     * Returns programmes (once per channel) plus alias→canonical map for Room.
     * Falls back to empty result on any error — short EPG API is the fallback.
     */
    suspend fun fetchXmltvEpg(
        acceptedChannelIds: Set<String> = emptySet(),
        timeOffsetHours: Int = 0
    ): XmltvParser.ParseResult = withContext(Dispatchers.IO) {
        if (!isConnected || serverUrl.isEmpty()) {
            return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
        }
        try {
            val url = "$serverUrl/xmltv.php?username=$username&password=$password"
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("XMLTV fetch failed: HTTP ${response.code}")
                response.close()
                return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
            }
            val stream = response.body?.byteStream()
                ?: run {
                    response.close()
                    return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
                }
            val parsed = xmltvStream(stream, url, response.header("Content-Encoding")).use {
                // XMLTV rows with an explicit +/-HHMM suffix are absolute. Xtream's
                // offset-less xmltv.php rows are conventionally UTC too; parsing them in
                // server_info.timezone applies the provider offset a second time and was
                // the six-hour Firestick guide defect.
                XmltvParser.parse(
                    it,
                    acceptedChannelIds,
                    sourceTimeZone = TimeZone.getTimeZone("UTC"),
                    timeOffsetHours = timeOffsetHours
                )
            }
            response.close()
            Timber.i(
                "XMLTV: fetched ${parsed.programs.size} programs, " +
                    "${parsed.aliasToCanonical.size} aliases"
            )
            parsed
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch XMLTV EPG")
            XmltvParser.ParseResult(emptyList(), emptyMap())
        }
    }

    /**
     * Fetch a third-party XMLTV source. These feeds are intentionally parsed without
     * an accepted-id filter because display-name aliases are what raise guide coverage
     * when provider `epg_channel_id` values are missing or wrong.
     */
    suspend fun fetchExternalXmltvEpg(
        sourceUrl: String,
        maxPrograms: Int = 180_000
    ): XmltvParser.ParseResult = withContext(Dispatchers.IO) {
        val cleanUrl = sourceUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            Timber.w("External XMLTV skipped: invalid URL '$sourceUrl'")
            return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
        }

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "DYLANDOS-IPTV/4.6 Firestick")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Timber.w("External XMLTV fetch failed: HTTP ${response.code} $cleanUrl")
                response.close()
                return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
            }

            val stream = response.body?.byteStream()
                ?: run {
                    response.close()
                    return@withContext XmltvParser.ParseResult(emptyList(), emptyMap())
                }
            val parsed = xmltvStream(stream, cleanUrl, response.header("Content-Encoding")).use {
                XmltvParser.parse(
                    it,
                    acceptedChannelIds = emptySet(),
                    maxPrograms = maxPrograms,
                    sourceTimeZone = EpgSourceDefaults.timeZoneFor(cleanUrl)
                )
            }
            response.close()
            Timber.i(
                "External XMLTV: fetched ${parsed.programs.size} programs, " +
                    "${parsed.aliasToCanonical.size} aliases from $cleanUrl"
            )
            parsed
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch external XMLTV EPG from $cleanUrl")
            XmltvParser.ParseResult(emptyList(), emptyMap())
        }
    }

    private fun xmltvStream(
        rawStream: InputStream,
        url: String,
        contentEncoding: String?
    ): InputStream {
        val buffered = BufferedInputStream(rawStream)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val gzipByMagic = first == 0x1f && second == 0x8b
        val gzipByHeader = contentEncoding?.contains("gzip", ignoreCase = true) == true
        val gzipByName = url.substringBefore('?').endsWith(".gz", ignoreCase = true)
        return if (gzipByMagic || gzipByHeader || gzipByName) GZIPInputStream(buffered) else buffered
    }
}
