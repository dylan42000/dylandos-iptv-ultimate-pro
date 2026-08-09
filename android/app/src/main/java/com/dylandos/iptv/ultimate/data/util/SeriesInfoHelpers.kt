package com.dylandos.iptv.ultimate.data.util

import com.dylandos.iptv.ultimate.data.model.Episode
import com.dylandos.iptv.ultimate.data.model.Season
import com.dylandos.iptv.ultimate.data.model.XtreamSeriesInfo
import kotlin.math.max

/**
 * Many Xtream panels return a rich [XtreamSeriesInfo.episodes] map but an empty
 * or incomplete [XtreamSeriesInfo.seasons] list. The UI must not treat "no seasons" as
 * "no data" — we derive display seasons and resolve episode list keys in multiple forms.
 */
fun displaySeasons(info: XtreamSeriesInfo): List<Season> {
    if (info.seasons.isNotEmpty()) {
        return info.seasons.sortedBy { it.seasonNumber }
    }
    val fromKeys = info.episodes.keys.mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()
    if (fromKeys.isNotEmpty()) {
        return fromKeys.map { n ->
            val count = episodesForSeasonRaw(info, n).size
            Season(
                seasonNumber = n,
                name = "Season $n",
                episodeCount = max(0, count),
                overview = null,
                airDate = null,
                cover = null,
                coverBig = null
            )
        }
    }
    // Flatten by episode.season
    return info.episodes.values.flatten()
        .map { it.season }
        .distinct()
        .sorted()
        .map { n ->
            val count = info.episodes.values.flatten().count { it.season == n }
            Season(
                seasonNumber = n,
                name = "Season $n",
                episodeCount = count,
                overview = null,
                airDate = null,
                cover = null,
                coverBig = null
            )
        }
}

/**
 * Resolves the episode list for a tab season number using all common Xtream key shapes
 * ("1", "01", "S1", …) and, if needed, filters the flattened map by [Episode.season].
 */
fun episodesForSeasonRaw(info: XtreamSeriesInfo, season: Int): List<Episode> {
    val m = info.episodes
    if (m.isEmpty()) {
        return emptyList()
    }
    m[season.toString()]?.takeIf { it.isNotEmpty() }?.let { return it.sortedBy { e -> e.episodeNum } }
    m[season.toString().padStart(2, '0')]?.takeIf { it.isNotEmpty() }?.let { return it.sortedBy { e -> e.episodeNum } }
    m["S$season"]?.let { if (it.isNotEmpty()) return it.sortedBy { e -> e.episodeNum } }
    m["s$season"]?.let { if (it.isNotEmpty()) return it.sortedBy { e -> e.episodeNum } }
    for ((k, v) in m) {
        if (k.trim().toIntOrNull() == season && v.isNotEmpty()) return v.sortedBy { e -> e.episodeNum }
    }
    return m.values.flatten()
        .filter { it.season == season }
        .sortedBy { it.episodeNum }
}

/** First season number to select when opening the series dialog. */
fun firstSelectableSeasonNumber(info: XtreamSeriesInfo): Int {
    val d = displaySeasons(info)
    if (d.isNotEmpty()) return d.first().seasonNumber
    val fromKeys = info.episodes.keys.mapNotNull { it.toIntOrNull() }.minOrNull()
    if (fromKeys != null) return fromKeys
    val fromEp = info.episodes.values.flatten().minOfOrNull { it.season }
    if (fromEp != null) return fromEp
    return 1
}
