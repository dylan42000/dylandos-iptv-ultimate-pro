package com.dylandos.iptv.ultimate.ui.navigation

import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import com.dylandos.iptv.ultimate.data.db.entity.WatchHistoryEntity
import com.dylandos.iptv.ultimate.data.model.Episode
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.data.model.XtreamSeriesInfo
import com.dylandos.iptv.ultimate.data.network.PendingSeriesPlaybackContext
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.util.episodesForSeasonRaw
import com.dylandos.iptv.ultimate.data.util.firstSelectableSeasonNumber
import timber.log.Timber

/**
 * Resumes from watch history when possible; otherwise first episode of first season.
 * Sets pendingStreamTitle on the repository so PlayerViewModel can show the correct
 * "Series S01E01 – Title" display name in the OSD.
 */
suspend fun buildSeriesPlayerRoute(
    series: XtreamSeries,
    xtreamRepository: XtreamRepository,
    watchHistoryDao: WatchHistoryDao
): String? {
    val info = xtreamRepository.getSeriesInfo(series.seriesId).getOrNull() ?: return null
    val resume = watchHistoryDao.getLastWatched(series.seriesId)
    val picked = pickEpisodeForPlayback(info, resume) ?: return null
    // Compose a human-readable title for the player OSD
    val seasonStr = picked.season.toString().padStart(2, '0')
    val epStr     = picked.episodeNum.toString().padStart(2, '0')
    val epTitle   = picked.title.ifBlank { "" }
    val displayTitle = buildString {
        append(series.name).append(" ")
        append("S${seasonStr}E${epStr}")
        if (epTitle.isNotBlank()) append(" \u2013 $epTitle")
    }
    xtreamRepository.pendingStreamTitle = displayTitle
    xtreamRepository.pendingSeriesPlaybackContext = PendingSeriesPlaybackContext(
        episodeId = picked.id,
        seriesId = series.seriesId,
        seriesName = series.name,
        seasonNumber = picked.season,
        episodeNumber = picked.episodeNum,
        episodeTitle = picked.title
    )
    return Screen.Player.createRoute(
        "series",
        picked.id,
        picked.containerExtension.ifEmpty { "mp4" }
    )
}

private fun pickEpisodeForPlayback(
    info: XtreamSeriesInfo,
    resume: WatchHistoryEntity?
): Episode? {
    if (resume != null) {
        for (eps in info.episodes.values) {
            eps.find { it.id == resume.episodeId }?.let { return it }
        }
    }
    val season = firstSelectableSeasonNumber(info)
    val eps = episodesForSeasonRaw(info, season)
    if (eps.isNotEmpty()) return eps.firstOrNull()
    val any = info.episodes.values.flatten()
        .minByOrNull { 10_000 * it.season + it.episodeNum }
    if (any == null) {
        Timber.w("No episodes in series map")
    }
    return any
}
