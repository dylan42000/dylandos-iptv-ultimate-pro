package com.dylandos.iptv.ultimate.ui.screens.player

/**
 * Deferred Media3 ExoPlayer — constructed only when USB timeshift or LibVLC fallback
 * actually needs it. Avoids always-on dual engines on Firestick.
 */

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dylandos.iptv.ultimate.player.timeshift.TimeshiftRingMath
import timber.log.Timber
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LazyExoPlayerHost(
    private val context: Context
) {
    companion object {
        /**
         * Media3 timeshift ring floor — 512 MB. v5.0 sizes the ring dynamically
         * (TimeshiftRingMath) up to 2 GB based on free USB space / user window;
         * this constant remains as the minimum for callers that need a number.
         */
        const val TIMESHIFT_CACHE_BYTES = TimeshiftRingMath.MIN_RING_BYTES
    }

    @Volatile private var player: ExoPlayer? = null
    @Volatile private var timeshiftCache: SimpleCache? = null
    private val lock = Any()

    fun orNull(): ExoPlayer? = player

    fun isCreated(): Boolean = player != null

    fun getOrCreate(
        timeshiftEnabled: Boolean,
        timeshiftPath: String?,
        /** v5.0: explicit ring cap in bytes; null = auto (TimeshiftRingMath). */
        ringMaxBytes: Long? = null
    ): ExoPlayer = synchronized(lock) {
        player?.let { return it }

        val cache = if (timeshiftEnabled) {
            timeshiftCache ?: runCatching {
                val effectiveDir = if (!timeshiftPath.isNullOrBlank()) {
                    File(timeshiftPath, "media3_cache")
                } else {
                    File(context.cacheDir, "timeshift_ring")
                }.also { it.mkdirs() }
                // v5.0: dynamic ring — cap = explicit override, else TimeshiftRingMath
                // auto sizing (max(512 MB, min(free/8, 2 GB))). Old builds used a
                // fixed 512 MB regardless of free space.
                val freeBytes = maxOf(effectiveDir.usableSpace, effectiveDir.freeSpace)
                val ringCapBytes = ringMaxBytes
                    ?: TimeshiftRingMath.computeRingMaxBytes(freeBytes)
                Timber.i(
                    "Timeshift ring: cap=${ringCapBytes / (1024 * 1024)}MB on ${effectiveDir.absolutePath} " +
                    "(free=${freeBytes / (1024 * 1024)}MB, holds ~" +
                    "${TimeshiftRingMath.ringHoldsMinutes(ringCapBytes)}min @${TimeshiftRingMath.DEFAULT_BITRATE_BPS / 1_000_000}Mbps)"
                )
                SimpleCache(
                    effectiveDir,
                    LeastRecentlyUsedCacheEvictor(ringCapBytes),
                    StandaloneDatabaseProvider(context)
                )
            }.onFailure { Timber.e(it, "Unable to create timeshift cache") }
                .getOrNull()
                .also { timeshiftCache = it }
        } else null

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val playbackDataSourceFactory = cache?.let { c ->
            CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(dataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } ?: dataSourceFactory
        val loadControl = DefaultLoadControl.Builder()
            // Rewind is served by the USB SimpleCache, not by retaining minutes of
            // decoded media in RAM.  The old 15-minute back buffer could exhaust a
            // 2 GB Firestick after 5–10 minutes of live TV.
            .setBackBuffer(if (timeshiftEnabled) 20_000 else 0, false)
            .build()
        val trackParams = androidx.media3.common.TrackSelectionParameters.Builder(context)
            .setPreferredTextLanguage("en")
            .setSelectUndeterminedTextLanguage(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
            .build()
            .apply {
                trackSelectionParameters = trackParams
                playWhenReady = true
            }
            .also {
                player = it
                val capForLog = (ringMaxBytes
                    ?: TimeshiftRingMath.computeRingMaxBytes(
                        maxOf(timeshiftPath?.let { File(it, "media3_cache").usableSpace } ?: 0L, 0L)
                    )) / (1024 * 1024)
                Timber.i(
                    "Media3 ExoPlayer created (timeshift=$timeshiftEnabled, cacheCapMb=$capForLog)"
                )
            }
    }

    fun release() = synchronized(lock) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { timeshiftCache?.release() }
        timeshiftCache = null
    }
}
