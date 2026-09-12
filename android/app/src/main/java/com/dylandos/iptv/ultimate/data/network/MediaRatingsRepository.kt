package com.dylandos.iptv.ultimate.data.network

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class MediaRatings(val scores: List<String> = emptyList(), val trailer: String? = null)
data class DiscoveryTitle(val title: String, val original: String, val year: String)

@Singleton
class MediaRatingsRepository @Inject constructor(@ApplicationContext context: Context) {
    val preferences = context.getSharedPreferences("media_discovery", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, Pair<Long, MediaRatings>>(100, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MediaRatings>>?) = size > 100
    }
    private fun JsonObject.string(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun fetch(base: String, params: Map<String, String>): JsonObject {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "Metadata service unavailable" }
            JsonParser.parseString(it.body?.string().orEmpty()).asJsonObject
        }
    }
    suspend fun discover(kind: String): List<DiscoveryTitle> = withContext(Dispatchers.IO) {
        require(kind in listOf("movie", "tv"))
        val key = preferences.getString("tmdb_key", "").orEmpty().trim()
        if (key.isEmpty()) return@withContext emptyList()
        val language = preferences.getString("language", "en").orEmpty()
        val today = java.time.LocalDate.now()
        val dateField = if (kind == "movie") "primary_release_date" else "first_air_date"
        (1..3).flatMap { page ->
            fetch("https://api.themoviedb.org/3/discover/$kind", mapOf(
                "api_key" to key, "language" to language, "with_original_language" to language,
                "include_adult" to "false", "sort_by" to "vote_average.desc", "vote_count.gte" to "100",
                "$dateField.gte" to today.minusYears(3).toString(), "$dateField.lte" to today.toString(), "page" to page.toString()
            )).getAsJsonArray("results")?.map {
                val obj = it.asJsonObject
                DiscoveryTitle(obj.string(if (kind == "movie") "title" else "name"),
                    obj.string(if (kind == "movie") "original_title" else "original_name"),
                    obj.string(if (kind == "movie") "release_date" else "first_air_date").take(4))
            }.orEmpty()
        }
    }
    fun <T> matchAvailable(discovery: List<DiscoveryTitle>, catalog: List<T>, name: (T) -> String): List<T> {
        fun normalize(value: String) = MediaTitle.normalized(value)
        val wanted = discovery.flatMap { listOf(normalize(it.title), normalize(it.original)) }.toSet()
        val candidates = catalog.asSequence().filter { normalize(name(it)) in wanted }.groupBy { normalize(name(it)) }
        return discovery.mapNotNull { entry ->
            (candidates[normalize(entry.title)] ?: candidates[normalize(entry.original)]).orEmpty()
                .firstOrNull { item ->
                    val year = MediaTitle.parse(name(item)).year
                    year == null || year == entry.year
                }
        }.distinct().take(14)
    }
    suspend fun lookup(title: String, kind: String): MediaRatings = withContext(Dispatchers.IO) {
        require(kind == "movie" || kind == "tv")
        val key = preferences.getString("tmdb_key", "").orEmpty().trim()
        if (key.isEmpty()) return@withContext MediaRatings()
        val language = preferences.getString("language", "en").orEmpty()
        val omdb = preferences.getString("omdb_key", "").orEmpty().trim()
        val cacheKey = "$kind|$title|$language|${key.hashCode()}|${omdb.hashCode()}"
        synchronized(cache) { cache[cacheKey] }?.takeIf { System.currentTimeMillis() - it.first < 6 * 60 * 60_000L }
            ?.let { return@withContext it.second }
        val parsed = MediaTitle.parse(title)
        val year = parsed.year
        val query = parsed.title
        fun normalized(value: String) = MediaTitle.normalized(value)
        val search = fetch("https://api.themoviedb.org/3/search/$kind", mapOf("api_key" to key, "query" to query, "language" to language))
        val matches = search.getAsJsonArray("results")?.map { it.asJsonObject }?.filter {
            val date = it.string(if (kind == "movie") "release_date" else "first_air_date")
            val name = it.string(if (kind == "movie") "title" else "name")
            val original = it.string(if (kind == "movie") "original_title" else "original_name")
            (normalized(query) == normalized(name) || normalized(query) == normalized(original)) && (year == null || date.startsWith(year))
        }.orEmpty()
        // Ambiguous remakes without a year must not inherit another title's rating.
        val match = matches.singleOrNull() ?: return@withContext MediaRatings()
        val detail = fetch("https://api.themoviedb.org/3/$kind/${match.string("id")}", mapOf("api_key" to key, "language" to language, "append_to_response" to "videos,external_ids"))
        val scores = mutableListOf<String>()
        val votes = detail.string("vote_count").toIntOrNull() ?: 0
        val score = detail.string("vote_average").toDoubleOrNull()
        if (votes > 0 && score != null && score in 0.0..10.0) scores += "TMDB ${String.format(java.util.Locale.US, "%.1f", score)}/10 ($votes votes)"
        val trailer = detail.getAsJsonObject("videos")?.getAsJsonArray("results")?.map { it.asJsonObject }
            ?.filter { it.string("site") == "YouTube" && it.string("type") == "Trailer" }
            ?.sortedByDescending { it.string("official") == "true" }?.firstOrNull()?.string("key")
        val imdbId = detail.getAsJsonObject("external_ids")?.string("imdb_id").orEmpty().ifEmpty { detail.string("imdb_id") }
        if (omdb.isNotEmpty() && imdbId.matches(Regex("tt[0-9]+"))) {
            runCatching {
                fetch("https://www.omdbapi.com/", mapOf("apikey" to omdb, "i" to imdbId))
                    .getAsJsonArray("Ratings")?.forEach {
                        val rating = it.asJsonObject
                        val source = rating.string("Source")
                        if (source in listOf("Internet Movie Database", "Rotten Tomatoes") && rating.string("Value") != "N/A") {
                            scores += "${if (source == "Internet Movie Database") "IMDb" else source} ${rating.string("Value")}"
                        }
                    }
            }
        }
        MediaRatings(scores, trailer).also { synchronized(cache) { cache[cacheKey] = System.currentTimeMillis() to it } }
    }
}
