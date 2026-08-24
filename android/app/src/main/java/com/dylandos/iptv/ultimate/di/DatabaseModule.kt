package com.dylandos.iptv.ultimate.di

import android.content.Context
import androidx.room.Room
import com.dylandos.iptv.ultimate.data.db.AppDatabase
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DYLANDOS IPTV - Database Module
 *
 * Provides Room database and DAOs
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "dylandos_iptv_db"
        )
            .addMigrations(
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17
            )
            // Ancient installs (schema v1–v4) have no safe migration chain into the
            // current EPG/DVR schema. Destructive fallback is intentional last resort
            // for those versions only — v5+ always uses the explicit migrations above
            // so favorites / DVR / resume are preserved.
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4)
            .build()
    }

    @Provides
    fun provideChannelDao(database: AppDatabase): ChannelDao = database.channelDao()

    @Provides
    fun provideFavoriteDao(database: AppDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideWatchHistoryDao(database: AppDatabase): WatchHistoryDao = database.watchHistoryDao()

    @Provides
    fun provideDvrRecordingDao(database: AppDatabase): DvrRecordingDao = database.dvrRecordingDao()

    @Provides
    fun provideEpgProgramDao(database: AppDatabase): EpgProgramDao = database.epgProgramDao()

    @Provides
    fun provideEpgChannelAliasDao(database: AppDatabase): EpgChannelAliasDao =
        database.epgChannelAliasDao()

    @Provides
    fun provideVodResumeDao(database: AppDatabase): VodResumeDao = database.vodResumeDao()

    @Provides
    fun provideHiddenCategoryDao(database: AppDatabase): HiddenCategoryDao = database.hiddenCategoryDao()

    @Provides
    fun provideScheduledRecordingDao(database: AppDatabase): ScheduledRecordingDao =
        database.scheduledRecordingDao()

    @Provides
    fun provideCustomListDao(database: AppDatabase): CustomListDao = database.customListDao()

    @Provides
    fun provideCachedMovieDao(database: AppDatabase): CachedMovieDao = database.cachedMovieDao()

    @Provides
    fun provideCachedSeriesDao(database: AppDatabase): CachedSeriesDao = database.cachedSeriesDao()
}
