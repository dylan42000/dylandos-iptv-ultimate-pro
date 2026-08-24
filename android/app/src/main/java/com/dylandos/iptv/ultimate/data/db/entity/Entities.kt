package com.dylandos.iptv.ultimate.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * VodResumeEntity — tracks per-movie resume positions for the VOD (Movies) section.
 *
 * Written by PlayerViewModel when a VOD stream is playing (every 30s + on stop).
 * Read by MoviesViewModel to show resume progress bars on poster cards.
 * All times stored as UTC epoch millis.
 */
@Entity(tableName = "vod_resume")
data class VodResumeEntity(
    @PrimaryKey val streamId: Int,
    val title: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastWatchedAt: Long = System.currentTimeMillis()
) {
    /** Progress percentage 0-100. Returns 0 if duration unknown. */
    val progressPct: Int get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
    /** True if the movie was watched to >90% completion — treated as done. */
    val isWatched: Boolean get() = progressPct >= 90
}

/**
 * DYLANDOS IPTV - Room Entities
 */

@Entity(
    tableName = "channels",
    indices = [Index(value = ["categoryId"])]
)
data class ChannelEntity(
    @PrimaryKey val streamId: Int,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val epgChannelId: String?,
    val streamType: String,
    val hasArchive: Boolean = false,
    /** UTC epoch millis when this row was last fetched from the server. Used for cache TTL. */
    val lastFetchedAt: Long = 0L
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["streamId", "streamType"],
    indices = [Index(value = ["streamType", "addedAt"])]
)
data class FavoriteEntity(
    val streamId: Int,
    val streamType: String, // "live", "vod", "series"
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * WatchHistoryEntity — tracks per-episode resume positions.
 *
 * Written by PlayerViewModel when playback position changes.
 * Read by SeriesViewModel to show "Resume from Ep X" in the detail popup.
 */
@Entity(
    tableName = "watch_history",
    indices = [Index(value = ["seriesId"])]
)
data class WatchHistoryEntity(
    @PrimaryKey val episodeId: String,          // unique episode stream ID (String — can be non-numeric)
    val seriesId: Int,
    val seriesName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val positionMs: Long = 0L,                  // last known playback position
    val durationMs: Long = 0L,                  // total duration (0 if unknown)
    val lastWatchedAt: Long = System.currentTimeMillis()
) {
    /** Progress percentage 0-100, clamped. Returns 0 if duration unknown. */
    val progressPct: Int get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
    /** True if the episode was watched to >85% completion. */
    val isWatched: Boolean get() = progressPct >= 85
}

/**
 * DvrRecordingEntity — persists completed DVR recordings in Room.
 *
 * Survives process death. Loaded by DvrRecordingRepository on startup.
 * Recording status values: "RECORDING", "COMPLETED", "FAILED"
 */
@Entity(
    tableName = "dvr_recordings",
    indices = [
        Index(value = ["status"]),
        Index(value = ["startTimeMs"]),
        Index(value = ["status", "startTimeMs"])
    ]
)
data class DvrRecordingEntity(
    @PrimaryKey val id: String,                 // UUID string from DvrRecording.id
    val channelName: String,
    val channelId: Int,
    val streamUrl: String,
    val filePath: String,
    val startTimeMs: Long,
    val endTimeMs: Long,                        // 0 if still recording
    val fileSizeBytes: Long = 0L,
    val status: String = "COMPLETED",           // "RECORDING" | "COMPLETED" | "FAILED"
    val programTitle: String = ""
)

/**
 * EpgProgramEntity — 7-day XMLTV EPG data fetched from /xmltv.php.
 *
 * Composite index on (channelId, startTime) enables fast time-window queries:
 *   SELECT * WHERE channelId = ? AND endTime > windowStart AND startTime < windowEnd
 * Index on endTime enables fast cleanup of stale entries.
 *
 * channelId = XMLTV channel attribute, matches XtreamChannel.epgChannelId.
 * All times stored as UTC epoch millis.
 */
@Entity(
    tableName = "epg_programs",
    indices = [
        Index(value = ["channelId", "startTime"]),   // fast window-range lookup
        Index(value = ["endTime"])                    // fast stale-entry cleanup
    ]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: String,       // maps to XtreamChannel.epgChannelId
    val title: String,
    val description: String = "",
    val startTime: Long,         // UTC epoch millis
    val endTime: Long            // UTC epoch millis
)

/**
 * Maps XMLTV display-name / id variants onto the canonical channel id used in
 * [EpgProgramEntity.channelId]. Keeps programme rows stored once while matching
 * still resolves Xtream `epg_channel_id` aliases.
 */
@Entity(
    tableName = "epg_channel_aliases",
    indices = [Index(value = ["canonicalChannelId"])]
)
data class EpgChannelAliasEntity(
    @PrimaryKey val alias: String,
    val canonicalChannelId: String
)

@Entity(
    tableName = "hidden_categories",
    primaryKeys = ["providerKey", "contentType", "categoryId"],
    indices = [Index(value = ["providerKey", "contentType"])]
)
data class HiddenCategoryEntity(
    val providerKey: String,
    val contentType: String,
    val categoryId: String,
    val hiddenAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "scheduled_recordings",
    indices = [Index(value = ["scheduledStartMs"]), Index(value = ["scheduledEndMs"])]
)
data class ScheduledRecordingEntity(
    @PrimaryKey val id: String,
    val channelName: String,
    val channelId: Int,
    val streamUrl: String,
    val programTitle: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val preBufferMs: Long,
    val postBufferMs: Long,
    val recordingType: String = "once",
    val matchKeyword: String = ""
)

/** A user-defined, provider/profile-scoped collection of IPTV content. */
@Entity(
    tableName = "custom_lists",
    indices = [
        Index(value = ["providerKey", "profileId", "normalizedName"], unique = true),
        Index(value = ["providerKey", "profileId", "sortPosition"])
    ]
)
data class CustomListEntity(
    @PrimaryKey val id: String,
    val providerKey: String,
    val profileId: String,
    val name: String,
    val normalizedName: String,
    val iconToken: String? = null,
    val colorToken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sortPosition: Int = 0,
    /** Null = top-level list/folder; otherwise parent folder id. */
    val parentListId: String? = null
)

/** A content reference in a custom list; deleting the parent list cascades here. */
@Entity(
    tableName = "custom_list_items",
    primaryKeys = ["listId", "contentType", "contentId", "providerKey", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = CustomListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["listId", "sortPosition"]),
        Index(value = ["providerKey", "profileId", "contentType", "contentId"])
    ]
)
data class CustomListItemEntity(
    val listId: String,
    val contentType: String,
    val contentId: String,
    val providerKey: String,
    val profileId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val sortPosition: Int = 0,
    val fallbackTitle: String? = null,
    val fallbackArtworkUrl: String? = null
)

/**
 * Category-scoped movie catalog cache. Network responses are written once; the
 * Movies screen pages with LIMIT/OFFSET so ViewModels never hold giant lists.
 */
@Entity(
    tableName = "cached_movies",
    primaryKeys = ["categoryKey", "streamId"],
    indices = [Index(value = ["categoryKey", "sortIndex"])]
)
data class CachedMovieEntity(
    val categoryKey: String,
    val streamId: Int,
    val sortIndex: Int,
    val num: Int = 0,
    val name: String,
    val streamType: String = "movie",
    val streamIcon: String? = null,
    val rating: String? = null,
    val rating5Based: Double = 0.0,
    val added: String? = null,
    val categoryId: String? = null,
    val containerExtension: String? = null,
    val customSid: String? = null,
    val directSource: String? = null
)

/**
 * Category-scoped series catalog cache — same paging contract as [CachedMovieEntity].
 */
@Entity(
    tableName = "cached_series",
    primaryKeys = ["categoryKey", "seriesId"],
    indices = [Index(value = ["categoryKey", "sortIndex"])]
)
data class CachedSeriesEntity(
    val categoryKey: String,
    val seriesId: Int,
    val sortIndex: Int,
    val num: Int = 0,
    val name: String,
    val cover: String? = null,
    val plot: String? = null,
    val castNames: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val lastModified: String? = null,
    val rating: String? = null,
    val rating5Based: Double = 0.0,
    val youtubeTrailer: String? = null,
    val episodeRunTime: String? = null,
    val categoryId: String? = null
)
