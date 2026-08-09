package com.dylandos.iptv.ultimate.data.db.dao

import androidx.room.*
import com.dylandos.iptv.ultimate.data.db.entity.CachedMovieEntity
import com.dylandos.iptv.ultimate.data.db.entity.CachedSeriesEntity
import com.dylandos.iptv.ultimate.data.db.entity.ChannelEntity
import com.dylandos.iptv.ultimate.data.db.entity.CustomListEntity
import com.dylandos.iptv.ultimate.data.db.entity.CustomListItemEntity
import com.dylandos.iptv.ultimate.data.db.entity.DvrRecordingEntity
import com.dylandos.iptv.ultimate.data.db.entity.EpgChannelAliasEntity
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.db.entity.HiddenCategoryEntity
import com.dylandos.iptv.ultimate.data.db.entity.ScheduledRecordingEntity
import com.dylandos.iptv.ultimate.data.db.entity.VodResumeEntity
import com.dylandos.iptv.ultimate.data.db.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DYLANDOS IPTV - Data Access Objects
 */

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY name ASC")
    fun getChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY name ASC")
    suspend fun getAllChannelsOnce(): List<ChannelEntity>

    /** Returns the most recent lastFetchedAt across all channels (0 if table is empty). */
    @Query("SELECT COALESCE(MAX(lastFetchedAt), 0) FROM channels")
    suspend fun getLastFetchedAt(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE streamId = :streamId AND streamType = :streamType)")
    fun isFavorite(streamId: Int, streamType: String): Flow<Boolean>

    @Query("SELECT * FROM favorites WHERE streamType = :streamType ORDER BY addedAt DESC")
    fun getFavoritesByType(streamType: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE streamId = :streamId AND streamType = :streamType)")
    suspend fun isFavoriteOnce(streamId: Int, streamType: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Delete
    suspend fun delete(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE streamId = :streamId AND streamType = :streamType")
    suspend fun deleteByStreamId(streamId: Int, streamType: String)

    @Transaction
    suspend fun toggle(streamId: Int, streamType: String): Boolean {
        return if (isFavoriteOnce(streamId, streamType)) {
            deleteByStreamId(streamId, streamType)
            false
        } else {
            insert(FavoriteEntity(streamId, streamType, System.currentTimeMillis()))
            true
        }
    }
}

@Dao
interface WatchHistoryDao {
    /** All episodes for a given series, sorted most recently watched first. */
    @Query("SELECT * FROM watch_history WHERE seriesId = :seriesId ORDER BY lastWatchedAt DESC")
    suspend fun getForSeries(seriesId: Int): List<WatchHistoryEntity>

    /** The single most-recently-watched episode for a series (for "Resume from Ep X" button). */
    @Query("SELECT * FROM watch_history WHERE seriesId = :seriesId ORDER BY lastWatchedAt DESC LIMIT 1")
    suspend fun getLastWatched(seriesId: Int): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getForEpisode(episodeId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE seriesId = :seriesId")
    suspend fun deleteForSeries(seriesId: Int)

    /** Prune entries older than [cutoffMs] UTC epoch millis. Used by WatchHistoryPruneWorker. */
    @Query("DELETE FROM watch_history WHERE lastWatchedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

@Dao
interface VodResumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: VodResumeEntity)

    @Query("SELECT * FROM vod_resume WHERE streamId = :streamId LIMIT 1")
    suspend fun getForMovie(streamId: Int): VodResumeEntity?

    /** Load all resume entries as a map of streamId → positionMs for the poster grid overlay. */
    @Query("SELECT * FROM vod_resume WHERE lastWatchedAt > :sinceMs")
    suspend fun getRecentlyWatched(sinceMs: Long): List<VodResumeEntity>

    @Query("DELETE FROM vod_resume WHERE streamId = :streamId")
    suspend fun deleteForMovie(streamId: Int)

    /** Prune entries older than 90 days. */
    @Query("DELETE FROM vod_resume WHERE lastWatchedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}

@Dao
interface DvrRecordingDao {
    /** Ordered newest-first for the Completed tab. */
    @Query("SELECT * FROM dvr_recordings ORDER BY startTimeMs DESC")
    fun getAllFlow(): Flow<List<DvrRecordingEntity>>

    @Query("SELECT * FROM dvr_recordings ORDER BY startTimeMs DESC")
    suspend fun getAll(): List<DvrRecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: DvrRecordingEntity)

    @Query("UPDATE dvr_recordings SET status = :status, endTimeMs = :endTimeMs, fileSizeBytes = :fileSizeBytes WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, endTimeMs: Long, fileSizeBytes: Long)

    /** Stores the permanent file path / content:// URI once the output location is known. */
    @Query("UPDATE dvr_recordings SET filePath = :filePath WHERE id = :id")
    suspend fun updateFilePath(id: String, filePath: String)

    /** Updates the recorded file size (polled every 30 s while actively recording). */
    @Query("UPDATE dvr_recordings SET fileSizeBytes = :bytes WHERE id = :id")
    suspend fun updateFileSizeBytes(id: String, bytes: Long)

    @Query("DELETE FROM dvr_recordings WHERE id = :id")
    suspend fun deleteById(id: String)
}

/**
 * EpgProgramDao — 7-day XMLTV EPG storage.
 *
 * Indexed on (channelId, startTime) so time-window queries are O(log N).
 * Indexed on endTime for fast purge of stale entries.
 */
@Dao
interface EpgProgramDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(programs: List<EpgProgramEntity>)

    /**
     * Fetch all programs for [channelId] that overlap [windowStart]..[windowEnd].
     * A program overlaps if it has not ended before the window starts
     * AND it has not started after the window ends.
     */
    @Query("""
        SELECT * FROM epg_programs
        WHERE channelId = :channelId
          AND endTime   > :windowStart
          AND startTime < :windowEnd
        ORDER BY startTime ASC
    """)
    suspend fun getProgramsForWindow(
        channelId: String,
        windowStart: Long,
        windowEnd: Long
    ): List<EpgProgramEntity>

    /** Delete programs whose endTime is before [cutoffTime] (UTC epoch millis). */
    @Query("DELETE FROM epg_programs WHERE endTime < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    /** Count total programs — used to decide whether to trigger a XMLTV refresh. */
    @Query("SELECT COUNT(*) FROM epg_programs")
    suspend fun count(): Int

    /**
     * Distinct EPG channel ids currently stored. Feeds the fuzzy name matcher
     * (Module 3.1) so channels whose epg_channel_id doesn't line up can still be
     * matched to programs by sanitised name.
     */
    @Query("SELECT DISTINCT channelId FROM epg_programs")
    suspend fun getDistinctChannelIds(): List<String>

    /** Cascade-clear all EPG rows — used on account switch (Module 3.2). */
    @Query("DELETE FROM epg_programs")
    suspend fun deleteAll()
}

@Dao
interface EpgChannelAliasDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(aliases: List<EpgChannelAliasEntity>)

    @Query("SELECT canonicalChannelId FROM epg_channel_aliases WHERE alias = :alias LIMIT 1")
    suspend fun resolve(alias: String): String?

    @Query("DELETE FROM epg_channel_aliases")
    suspend fun deleteAll()
}

@Dao
interface HiddenCategoryDao {
    @Query("SELECT categoryId FROM hidden_categories WHERE providerKey = :providerKey AND contentType = :contentType")
    fun observeHiddenIds(providerKey: String, contentType: String): Flow<List<String>>

    @Query("SELECT categoryId FROM hidden_categories WHERE providerKey = :providerKey AND contentType = :contentType")
    suspend fun getHiddenIds(providerKey: String, contentType: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(entity: HiddenCategoryEntity)

    @Query("DELETE FROM hidden_categories WHERE providerKey = :providerKey AND contentType = :contentType AND categoryId = :categoryId")
    suspend fun show(providerKey: String, contentType: String, categoryId: String)

    @Query("DELETE FROM hidden_categories WHERE providerKey = :providerKey AND contentType = :contentType")
    suspend fun showAll(providerKey: String, contentType: String)
}

@Dao
interface ScheduledRecordingDao {
    @Query("SELECT * FROM scheduled_recordings ORDER BY scheduledStartMs ASC")
    suspend fun getAll(): List<ScheduledRecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledRecordingEntity)

    @Query("DELETE FROM scheduled_recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_recordings WHERE scheduledEndMs < :cutoffMs")
    suspend fun deleteExpired(cutoffMs: Long)
}

@Dao
interface CustomListDao {
    @Query("SELECT * FROM custom_lists WHERE providerKey = :providerKey AND profileId = :profileId ORDER BY sortPosition ASC, createdAt ASC")
    fun observeLists(providerKey: String, profileId: String): Flow<List<CustomListEntity>>

    @Query("SELECT * FROM custom_lists WHERE id = :listId LIMIT 1")
    fun observeList(listId: String): Flow<CustomListEntity?>

    @Query("SELECT * FROM custom_lists WHERE id = :listId LIMIT 1")
    suspend fun getList(listId: String): CustomListEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertList(list: CustomListEntity)

    @Update
    suspend fun updateList(list: CustomListEntity)

    @Query("DELETE FROM custom_lists WHERE id = :listId")
    suspend fun deleteList(listId: String)

    @Query("UPDATE custom_lists SET sortPosition = :sortPosition, updatedAt = :updatedAt WHERE id = :listId")
    suspend fun updateListPosition(listId: String, sortPosition: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM custom_list_items WHERE listId = :listId ORDER BY sortPosition ASC, addedAt ASC")
    fun observeItems(listId: String): Flow<List<CustomListItemEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM custom_list_items WHERE listId = :listId AND contentType = :contentType AND contentId = :contentId AND providerKey = :providerKey AND profileId = :profileId)")
    fun observeMembership(listId: String, contentType: String, contentId: String, providerKey: String, profileId: String): Flow<Boolean>

    @Query("SELECT * FROM custom_list_items WHERE providerKey = :providerKey AND profileId = :profileId AND contentType = :contentType AND contentId = :contentId ORDER BY addedAt DESC")
    fun observeMemberships(providerKey: String, profileId: String, contentType: String, contentId: String): Flow<List<CustomListItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: CustomListItemEntity): Long

    @Query("DELETE FROM custom_list_items WHERE listId = :listId AND contentType = :contentType AND contentId = :contentId AND providerKey = :providerKey AND profileId = :profileId")
    suspend fun removeItem(listId: String, contentType: String, contentId: String, providerKey: String, profileId: String)

    @Query("UPDATE custom_list_items SET sortPosition = :sortPosition WHERE listId = :listId AND contentType = :contentType AND contentId = :contentId AND providerKey = :providerKey AND profileId = :profileId")
    suspend fun updateItemPosition(listId: String, contentType: String, contentId: String, providerKey: String, profileId: String, sortPosition: Int)

    @Transaction
    suspend fun reorderLists(orderedListIds: List<String>) {
        val now = System.currentTimeMillis()
        orderedListIds.forEachIndexed { position, id -> updateListPosition(id, position, now) }
    }

    @Transaction
    suspend fun reorderItems(listId: String, orderedItems: List<CustomListItemEntity>) {
        orderedItems.forEachIndexed { position, item ->
            require(item.listId == listId) { "All reordered items must belong to list $listId" }
            updateItemPosition(listId, item.contentType, item.contentId, item.providerKey, item.profileId, position)
        }
    }
}

/**
 * Room-backed VOD catalog pages — ViewModels write a category once, then page
 * with LIMIT/OFFSET so giant lists never live in StateFlow / AtomicReference.
 */
@Dao
interface CachedMovieDao {
    @Query(
        """
        SELECT * FROM cached_movies
        WHERE categoryKey = :categoryKey
        ORDER BY sortIndex ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun page(categoryKey: String, limit: Int, offset: Int): List<CachedMovieEntity>

    @Query("SELECT COUNT(*) FROM cached_movies WHERE categoryKey = :categoryKey")
    suspend fun count(categoryKey: String): Int

    @Query(
        """
        SELECT * FROM cached_movies
        WHERE categoryKey = :categoryKey
        ORDER BY sortIndex ASC
        LIMIT 1 OFFSET :index
        """
    )
    suspend fun getAt(categoryKey: String, index: Int): CachedMovieEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CachedMovieEntity>)

    @Query("DELETE FROM cached_movies WHERE categoryKey = :categoryKey")
    suspend fun deleteCategory(categoryKey: String)

    @Transaction
    suspend fun replaceCategory(categoryKey: String, rows: List<CachedMovieEntity>) {
        deleteCategory(categoryKey)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface CachedSeriesDao {
    @Query(
        """
        SELECT * FROM cached_series
        WHERE categoryKey = :categoryKey
        ORDER BY sortIndex ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun page(categoryKey: String, limit: Int, offset: Int): List<CachedSeriesEntity>

    @Query("SELECT COUNT(*) FROM cached_series WHERE categoryKey = :categoryKey")
    suspend fun count(categoryKey: String): Int

    @Query(
        """
        SELECT * FROM cached_series
        WHERE categoryKey = :categoryKey
        ORDER BY sortIndex ASC
        LIMIT 1 OFFSET :index
        """
    )
    suspend fun getAt(categoryKey: String, index: Int): CachedSeriesEntity?

    @Query(
        """
        SELECT * FROM cached_series
        WHERE categoryKey = :categoryKey
        ORDER BY sortIndex ASC
        LIMIT :limit
        """
    )
    suspend fun sample(categoryKey: String, limit: Int): List<CachedSeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CachedSeriesEntity>)

    @Query("DELETE FROM cached_series WHERE categoryKey = :categoryKey")
    suspend fun deleteCategory(categoryKey: String)

    @Transaction
    suspend fun replaceCategory(categoryKey: String, rows: List<CachedSeriesEntity>) {
        deleteCategory(categoryKey)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
