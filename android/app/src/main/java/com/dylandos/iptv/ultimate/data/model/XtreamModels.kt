package com.dylandos.iptv.ultimate.data.model

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * FlexibleStringAdapter — safely deserializes JSON strings OR numbers as Kotlin String.
 *
 * Xtream Codes API returns category_id as a JSON number (e.g. 123) in stream list
 * responses and as a JSON string ("123") in category list responses.  Gson's strict
 * String reader throws on numbers, setting the field to null and breaking the
 * category filter ("All" shows all channels but individual categories show zero).
 *
 * Apply with @field:JsonAdapter(FlexibleStringAdapter::class) on any String? field
 * that may be sent as a number by the server.
 */
class FlexibleStringAdapter : TypeAdapter<String?>() {
    override fun write(out: JsonWriter, value: String?) {
        if (value == null) out.nullValue() else out.value(value)
    }
    override fun read(`in`: JsonReader): String? = when (`in`.peek()) {
        JsonToken.NULL    -> { `in`.nextNull(); null }
        JsonToken.NUMBER  -> {
            // Read as raw string, then normalize "12.0" → "12".
            // Some Xtream Codes servers encode category_id as a float (e.g. 12.0)
            // in VOD/series stream lists while category list responses use the plain
            // integer string "12". Without normalization the filter comparison
            // "12.0" == "12" is false and categories show zero movies/series.
            val raw = `in`.nextString()
            raw.toDoubleOrNull()
                ?.let { d -> if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong().toString() else raw }
                ?: raw
        }
        JsonToken.STRING  -> `in`.nextString().trim()  // trim guards against trailing spaces
        JsonToken.BOOLEAN -> `in`.nextBoolean().toString()
        else              -> { `in`.skipValue(); null }
    }
}

/**
 * Xtream often sends EPG `start_timestamp` / `stop_timestamp` as JSON strings.
 * A strict Long field then becomes 0 and wall-clock parsing (wrong zone) shifts
 * the whole Firestick guide — classic "shows 7:29 when it is 1:29 Mountain" bug.
 */
class FlexibleLongAdapter : TypeAdapter<Long>() {
    override fun write(out: JsonWriter, value: Long?) {
        out.value(value ?: 0L)
    }

    override fun read(`in`: JsonReader): Long = when (`in`.peek()) {
        JsonToken.NULL -> {
            `in`.nextNull()
            0L
        }
        JsonToken.NUMBER -> {
            val raw = `in`.nextString()
            raw.toLongOrNull()
                ?: raw.toDoubleOrNull()?.toLong()
                ?: 0L
        }
        JsonToken.STRING -> {
            val raw = `in`.nextString().trim()
            raw.toLongOrNull()
                ?: raw.toDoubleOrNull()?.toLong()
                ?: 0L
        }
        else -> {
            `in`.skipValue()
            0L
        }
    }
}

/**
 * FlexibleStringMapAdapter — handles Xtream Codes API's inconsistent video/audio fields.
 *
 * The `video` and `audio` objects inside episode/movie info can have INTEGER values
 * (e.g. "width": 1920, "height": 1080) mixed with strings.  Gson's default
 * Map<String,String> reader crashes on integers.  This adapter safely coerces every
 * JSON primitive to a String, and skips any nested objects entirely.
 */
class FlexibleStringMapAdapter : TypeAdapter<Map<String, String>?>() {
    override fun write(out: JsonWriter, value: Map<String, String>?) {
        if (value == null) { out.nullValue(); return }
        out.beginObject()
        value.forEach { (k, v) -> out.name(k).value(v) }
        out.endObject()
    }

    override fun read(`in`: JsonReader): Map<String, String>? {
        return when (`in`.peek()) {
            JsonToken.NULL    -> { `in`.nextNull(); null }
            JsonToken.STRING  -> { `in`.nextString(); null }  // empty-string sentinel → null
            JsonToken.BEGIN_OBJECT -> {
                val map = mutableMapOf<String, String>()
                `in`.beginObject()
                while (`in`.hasNext()) {
                    val key = `in`.nextName()
                    val value: String = when (`in`.peek()) {
                        JsonToken.STRING  -> `in`.nextString()
                        JsonToken.NUMBER  -> `in`.nextString()   // reads number as-is
                        JsonToken.BOOLEAN -> `in`.nextBoolean().toString()
                        JsonToken.NULL    -> { `in`.nextNull(); "" }
                        else              -> { `in`.skipValue(); "" }  // skip nested objects
                    }
                    map[key] = value
                }
                `in`.endObject()
                map.ifEmpty { null }
            }
            else -> { `in`.skipValue(); null }
        }
    }
}

/**
 * FlexibleIntAdapter — safely deserializes JSON integers OR numeric strings as Kotlin Int.
 *
 * Some Xtream Codes servers return integer fields like `episode_count` as JSON strings
 * (e.g. `"episode_count": "10"`) while others return them as proper JSON numbers.
 * Gson's strict Int reader throws on string tokens, leaving the field at its default (0),
 * which caused the "0 eps" bug in the Series seasons rail and zero-count category display.
 *
 * Apply with @field:JsonAdapter(FlexibleIntAdapter::class) on any Int field that may be
 * sent as a string by the server.
 */
class FlexibleIntAdapter : TypeAdapter<Int>() {
    override fun write(out: JsonWriter, value: Int) { out.value(value) }
    override fun read(`in`: JsonReader): Int = when (`in`.peek()) {
        JsonToken.NULL    -> { `in`.nextNull(); 0 }
        JsonToken.NUMBER  -> `in`.nextString().let { raw ->
            raw.toIntOrNull()
                ?: raw.toDoubleOrNull()?.toInt()
                ?: 0
        }
        JsonToken.STRING  -> `in`.nextString().trim().let { s ->
            s.toIntOrNull()
                ?: s.toDoubleOrNull()?.toInt()
                ?: 0
        }
        JsonToken.BOOLEAN -> if (`in`.nextBoolean()) 1 else 0
        else              -> { `in`.skipValue(); 0 }
    }
}

/**
 * DYLANDOS IPTV - Xtream Codes API Models
 * 
 * Data classes matching the Xtream Codes API specification
 */

// ── Authentication Response ────────────────────────────────────────

data class XtreamAuth(
    @SerializedName("user_info") val userInfo: UserInfo,
    @SerializedName("server_info") val serverInfo: ServerInfo
)

data class UserInfo(
    val username: String,
    val password: String,
    val message: String?,
    val auth: Int,
    @SerializedName("status") val status: String,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("is_trial") val isTrial: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("allowed_output_formats") val allowedOutputFormats: List<String>?
)

data class ServerInfo(
    val url: String,
    val port: String,
    @SerializedName("https_port") val httpsPort: String?,
    @SerializedName("server_protocol") val serverProtocol: String,
    @SerializedName("rtmp_port") val rtmpPort: String?,
    val timezone: String?,
    @SerializedName("timestamp_now") val timestampNow: Long,
    @SerializedName("time_now") val timeNow: String?
)

// ── Profile ────────────────────────────────────────────────────────

data class XtreamProfile(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val isActive: Boolean = false
)

// ── Category ───────────────────────────────────────────────────────

data class XtreamCategory(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String,
    @SerializedName("parent_id") val parentId: Int = 0
)

// ── Live Stream/Channel ────────────────────────────────────────────

data class XtreamChannel(
    @SerializedName("num") val num: Int,
    val name: String,
    @SerializedName("stream_type") val streamType: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("added") val added: String?,
    @field:JsonAdapter(FlexibleStringAdapter::class)
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("tv_archive") val tvArchive: Int = 0,
    @SerializedName("direct_source") val directSource: String?,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int = 0
)

// ── VOD / Movie ────────────────────────────────────────────────────

data class XtreamMovie(
    @SerializedName("num") val num: Int,
    val name: String,
    @SerializedName("stream_type") val streamType: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double = 0.0,
    @SerializedName("added") val added: String?,
    @field:JsonAdapter(FlexibleStringAdapter::class)
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("direct_source") val directSource: String?
)

data class XtreamMovieInfo(
    val info: MovieInfo,
    @SerializedName("movie_data") val movieData: MovieData?
)

data class MovieInfo(
    @SerializedName("tmdb_id") val tmdbId: String?,
    val name: String?,
    @SerializedName("o_name") val originalName: String?,
    @SerializedName("cover_big") val coverBig: String?,
    @SerializedName("movie_image") val movieImage: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("actors") val actors: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("description") val description: String?,
    val plot: String?,
    val age: String?,
    @SerializedName("mpaa_rating") val mpaaRating: String?,
    @SerializedName("rating_5based") val rating5Based: Double = 0.0,
    val country: String?,
    val genre: String?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    val duration: String?,
    @SerializedName("duration_secs") val durationSecs: Int = 0,
    @field:JsonAdapter(FlexibleStringMapAdapter::class)
    @SerializedName("video") val video: Map<String, String>? = null,
    @field:JsonAdapter(FlexibleStringMapAdapter::class)
    @SerializedName("audio") val audio: Map<String, String>? = null,
    @SerializedName("bitrate") val bitrate: Int = 0
)

data class MovieData(
    @SerializedName("stream_id") val streamId: Int,
    val name: String?,
    @SerializedName("added") val added: String?,
    @field:JsonAdapter(FlexibleStringAdapter::class)
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("direct_source") val directSource: String?
)

// ── Series ─────────────────────────────────────────────────────────

data class XtreamSeries(
    @SerializedName("num") val num: Int,
    val name: String,
    @SerializedName("series_id") val seriesId: Int,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("last_modified") val lastModified: String?,
    val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double = 0.0,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("episode_run_time") val episodeRunTime: String?,
    @field:JsonAdapter(FlexibleStringAdapter::class)
    @SerializedName("category_id") val categoryId: String?
)

data class XtreamSeriesInfo(
    val seasons: List<Season>,
    val info: SeriesInfo,
    val episodes: Map<String, List<Episode>>
)

data class Season(
    @field:JsonAdapter(FlexibleIntAdapter::class)
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String?,
    @field:JsonAdapter(FlexibleIntAdapter::class)
    @SerializedName("episode_count") val episodeCount: Int,
    @SerializedName("overview") val overview: String?,
    @SerializedName("air_date") val airDate: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("cover_big") val coverBig: String?
)

data class SeriesInfo(
    val name: String,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("last_modified") val lastModified: String?,
    val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double = 0.0,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("youtube_trailer") val youtubeTrailer: String?,
    @SerializedName("episode_run_time") val episodeRunTime: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class Episode(
    val id: String,
    @SerializedName("episode_num") val episodeNum: Int,
    val title: String,
    @SerializedName("container_extension") val containerExtension: String,
    val info: EpisodeInfo?,
    @SerializedName("custom_sid") val customSid: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("season") val season: Int,
    @SerializedName("direct_source") val directSource: String?
)

data class EpisodeInfo(
    @SerializedName("tmdb_id") val tmdbId: String?,
    @SerializedName("releasedate") val releaseDate: String?,
    val plot: String?,
    @SerializedName("duration_secs") val durationSecs: Int = 0,
    val duration: String?,
    @field:JsonAdapter(FlexibleStringMapAdapter::class)
    @SerializedName("video") val video: Map<String, String>? = null,
    @field:JsonAdapter(FlexibleStringMapAdapter::class)
    @SerializedName("audio") val audio: Map<String, String>? = null,
    @SerializedName("bitrate") val bitrate: Int = 0,
    val rating: String?,
    @SerializedName("season") val season: Int = 0,
    @SerializedName("cover_big") val coverBig: String?
)

// ── EPG Program ────────────────────────────────────────────────────

data class XtreamEpgListing(
    @SerializedName("epg_listings") val epgListings: List<XtreamEpgProgram>
)

data class XtreamEpgProgram(
    val id: String,
    @SerializedName("epg_id") val epgId: String,
    val title: String,
    val lang: String?,
    val start: String,
    val end: String,
    val description: String?,
    @SerializedName("channel_id") val channelId: String,
    @field:JsonAdapter(FlexibleLongAdapter::class)
    @SerializedName("start_timestamp") val startTimestamp: Long = 0L,
    @field:JsonAdapter(FlexibleLongAdapter::class)
    @SerializedName("stop_timestamp") val stopTimestamp: Long = 0L,
    @SerializedName("now_playing") val nowPlaying: Int = 0,
    @SerializedName("has_archive") val hasArchive: Int = 0,
    /**
     * Original provider EPG values retained only in memory for Replay URL construction.
     * The visible [startTimestamp]/[stopTimestamp] may be corrected to make the guide
     * match real time; providers can still index archive files using their original clock.
     */
    @Transient val archiveStartTimestamp: Long = 0L,
    @Transient val archiveStopTimestamp: Long = 0L,
    @Transient val archiveStartWallClock: String? = null
)
