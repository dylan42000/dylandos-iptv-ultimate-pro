package com.dylandos.iptv.ultimate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dylandos.iptv.ultimate.data.db.dao.CachedMovieDao
import com.dylandos.iptv.ultimate.data.db.dao.CachedSeriesDao
import com.dylandos.iptv.ultimate.data.db.dao.ChannelDao
import com.dylandos.iptv.ultimate.data.db.dao.CustomListDao
import com.dylandos.iptv.ultimate.data.db.dao.DvrRecordingDao
import com.dylandos.iptv.ultimate.data.db.dao.EpgChannelAliasDao
import com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.dao.HiddenCategoryDao
import com.dylandos.iptv.ultimate.data.db.dao.ScheduledRecordingDao
import com.dylandos.iptv.ultimate.data.db.dao.VodResumeDao
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
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

/**
 * DYLANDOS IPTV - Room Database
 *
 * Main database for caching channels, favorites, and watch history.
 *
 * v1 → v2: added WatchHistoryEntity + lastFetchedAt to ChannelEntity
 * v2 → v3: added categoryId index to ChannelEntity
 * v3 → v4: added DvrRecordingEntity for persistent DVR recording history
 * v4 → v5: added EpgProgramEntity for 7-day XMLTV EPG storage (Room-indexed)
 * v5 → v6: no schema change — migration protects dvr_recordings from destructive wipe
 *           on app update.  Channels/EPG are still allowed to reset (re-fetchable).
 * v6 → v7: added DVR indexes (status,startTimeMs) for faster Active/Completed tab queries.
 * v7 → v8: added vod_resume table for per-movie resume positions in the Movies section.
 * v10 → v11: favorites composite PK + custom_lists tables.
 * v11 → v12: epg_channel_aliases — store programmes once; aliases map separately.
 * v12 → v13: cached_movies / cached_series — Room-paged VOD catalogs (OOM kill).
 * v13 → v14: DVR recordingType/matchKeyword + custom_lists.parentListId (folders).
 * v14 → v15: wipe EPG tables so Firestick re-ingests with UTC-correct timestamps.
 * v15 → v16: wipe EPG again after strengthened short-EPG / Room alignment for ~6h skew.
 */
@Database(
    entities = [
        ChannelEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        DvrRecordingEntity::class,
        EpgProgramEntity::class,
        EpgChannelAliasEntity::class,
        VodResumeEntity::class,
        HiddenCategoryEntity::class,
        ScheduledRecordingEntity::class,
        CustomListEntity::class,
        CustomListItemEntity::class,
        CachedMovieEntity::class,
        CachedSeriesEntity::class
    ],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun dvrRecordingDao(): DvrRecordingDao
    abstract fun epgProgramDao(): EpgProgramDao
    abstract fun epgChannelAliasDao(): EpgChannelAliasDao
    abstract fun vodResumeDao(): VodResumeDao
    abstract fun hiddenCategoryDao(): HiddenCategoryDao
    abstract fun scheduledRecordingDao(): ScheduledRecordingDao
    abstract fun customListDao(): CustomListDao
    abstract fun cachedMovieDao(): CachedMovieDao
    abstract fun cachedSeriesDao(): CachedSeriesDao

    companion object {
        /**
         * v5 → v6 migration: no schema changes in this release.
         * Declaring an explicit migration prevents Room's fallbackToDestructiveMigration()
         * from wiping the dvr_recordings table when the user updates from v1.9.1-BETA.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No structural changes — migration is a no-op to preserve existing rows.
                // Future schema additions go here as ALTER TABLE statements.
            }
        }

        /** v6 → v7: add DVR indexes for faster sorted status queries. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dvr_recordings_startTimeMs ON dvr_recordings(startTimeMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_dvr_recordings_status_startTimeMs ON dvr_recordings(status, startTimeMs)"
                )
            }
        }

        /** v7 → v8: add vod_resume table for per-movie resume positions. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vod_resume (
                        streamId    INTEGER NOT NULL PRIMARY KEY,
                        title       TEXT    NOT NULL,
                        positionMs  INTEGER NOT NULL DEFAULT 0,
                        durationMs  INTEGER NOT NULL DEFAULT 0,
                        lastWatchedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hidden_categories (
                        providerKey TEXT NOT NULL,
                        contentType TEXT NOT NULL,
                        categoryId TEXT NOT NULL,
                        hiddenAt INTEGER NOT NULL,
                        PRIMARY KEY(providerKey, contentType, categoryId)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_hidden_categories_providerKey_contentType
                    ON hidden_categories(providerKey, contentType)
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scheduled_recordings (
                        id TEXT NOT NULL PRIMARY KEY,
                        channelName TEXT NOT NULL,
                        channelId INTEGER NOT NULL,
                        streamUrl TEXT NOT NULL,
                        programTitle TEXT NOT NULL,
                        scheduledStartMs INTEGER NOT NULL,
                        scheduledEndMs INTEGER NOT NULL,
                        preBufferMs INTEGER NOT NULL,
                        postBufferMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_recordings_scheduledStartMs " +
                        "ON scheduled_recordings(scheduledStartMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_recordings_scheduledEndMs " +
                        "ON scheduled_recordings(scheduledEndMs)"
                )
            }
        }

        /**
         * v10 -> v11: favorites use (streamId, streamType) as their identity.
         * Xtream providers commonly reuse numeric IDs across live, VOD and series;
         * the old single-column key silently replaced a favorite of another type.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorites_new (
                        streamId INTEGER NOT NULL,
                        streamType TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        PRIMARY KEY(streamId, streamType)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR REPLACE INTO favorites_new(streamId, streamType, addedAt)
                    SELECT streamId, streamType, addedAt FROM favorites
                """.trimIndent())
                db.execSQL("DROP TABLE favorites")
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_favorites_streamType_addedAt
                    ON favorites(streamType, addedAt)
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_lists (
                        id TEXT NOT NULL PRIMARY KEY,
                        providerKey TEXT NOT NULL,
                        profileId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        iconToken TEXT,
                        colorToken TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        sortPosition INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_custom_lists_providerKey_profileId_normalizedName ON custom_lists(providerKey, profileId, normalizedName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_lists_providerKey_profileId_sortPosition ON custom_lists(providerKey, profileId, sortPosition)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_list_items (
                        listId TEXT NOT NULL,
                        contentType TEXT NOT NULL,
                        contentId TEXT NOT NULL,
                        providerKey TEXT NOT NULL,
                        profileId TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        sortPosition INTEGER NOT NULL,
                        fallbackTitle TEXT,
                        fallbackArtworkUrl TEXT,
                        PRIMARY KEY(listId, contentType, contentId, providerKey, profileId),
                        FOREIGN KEY(listId) REFERENCES custom_lists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_list_items_listId_sortPosition ON custom_list_items(listId, sortPosition)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_custom_list_items_providerKey_profileId_contentType_contentId ON custom_list_items(providerKey, profileId, contentType, contentId)")
            }
        }

        /**
         * v11 → v12: alias map for EPG so programmes are stored once under the
         * canonical XMLTV channel id. Safe additive migration — no user data wiped.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS epg_channel_aliases (
                        alias TEXT NOT NULL PRIMARY KEY,
                        canonicalChannelId TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_epg_channel_aliases_canonicalChannelId " +
                        "ON epg_channel_aliases(canonicalChannelId)"
                )
            }
        }

        /**
         * v12 → v13: additive VOD/Series catalog cache tables for Room LIMIT/OFFSET
         * paging. Safe — no user favorites/DVR/EPG rows touched.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_movies (
                        categoryKey TEXT NOT NULL,
                        streamId INTEGER NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        num INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        streamType TEXT NOT NULL,
                        streamIcon TEXT,
                        rating TEXT,
                        rating5Based REAL NOT NULL,
                        added TEXT,
                        categoryId TEXT,
                        containerExtension TEXT,
                        customSid TEXT,
                        directSource TEXT,
                        PRIMARY KEY(categoryKey, streamId)
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_movies_categoryKey_sortIndex " +
                        "ON cached_movies(categoryKey, sortIndex)"
                )
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_series (
                        categoryKey TEXT NOT NULL,
                        seriesId INTEGER NOT NULL,
                        sortIndex INTEGER NOT NULL,
                        num INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        cover TEXT,
                        plot TEXT,
                        castNames TEXT,
                        director TEXT,
                        genre TEXT,
                        releaseDate TEXT,
                        lastModified TEXT,
                        rating TEXT,
                        rating5Based REAL NOT NULL,
                        youtubeTrailer TEXT,
                        episodeRunTime TEXT,
                        categoryId TEXT,
                        PRIMARY KEY(categoryKey, seriesId)
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cached_series_categoryKey_sortIndex " +
                        "ON cached_series(categoryKey, sortIndex)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scheduled_recordings ADD COLUMN recordingType TEXT NOT NULL DEFAULT 'once'"
                )
                db.execSQL(
                    "ALTER TABLE scheduled_recordings ADD COLUMN matchKeyword TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("ALTER TABLE custom_lists ADD COLUMN parentListId TEXT")
            }
        }

        /** Drop stale EPG so the next Guide load re-parses with UTC-correct logic. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM epg_programs")
                db.execSQL("DELETE FROM epg_channel_aliases")
            }
        }

        /** Re-wipe EPG after ~6h Mountain skew alignment hardening. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM epg_programs")
                db.execSQL("DELETE FROM epg_channel_aliases")
            }
        }

        /** v16 → v17: add programTitle column to dvr_recordings for show-aware naming. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dvr_recordings ADD COLUMN programTitle TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
