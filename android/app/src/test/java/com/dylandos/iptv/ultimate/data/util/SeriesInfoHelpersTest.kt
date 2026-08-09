package com.dylandos.iptv.ultimate.data.util

import com.dylandos.iptv.ultimate.data.model.Episode
import com.dylandos.iptv.ultimate.data.model.EpisodeInfo
import com.dylandos.iptv.ultimate.data.model.Season
import com.dylandos.iptv.ultimate.data.model.SeriesInfo
import com.dylandos.iptv.ultimate.data.model.XtreamSeriesInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C6: unit tests for series season derivation — the logic behind the
 * "no seasons shown" / season-focus bugs (A4).
 */
class SeriesInfoHelpersTest {

    private fun info(
        seasons: List<Season> = emptyList(),
        episodes: Map<String, List<Episode>> = emptyMap()
    ) = XtreamSeriesInfo(
        seasons = seasons,
        info = SeriesInfo(
            name = "Test Series",
            cover = null, plot = null, cast = null, director = null, genre = null,
            releaseDate = null, lastModified = null, rating = null,
            backdropPath = null, youtubeTrailer = null, episodeRunTime = null, categoryId = null
        ),
        episodes = episodes
    )

    private fun episode(id: String, num: Int, season: Int = 1) = Episode(
        id = id,
        episodeNum = num,
        title = "Ep $num",
        containerExtension = "mp4",
        info = EpisodeInfo(
            tmdbId = null, releaseDate = null, plot = null, duration = null,
            rating = null, coverBig = null
        ),
        customSid = null,
        added = null,
        season = season,
        directSource = null
    )

    @Test
    fun `explicit seasons are sorted by number`() {
        val s2 = Season(2, "Season 2", 3, null, null, null, null)
        val s1 = Season(1, "Season 1", 5, null, null, null, null)
        val result = displaySeasons(info(seasons = listOf(s2, s1)))
        assertEquals(listOf(1, 2), result.map { it.seasonNumber })
        assertEquals("Season 1", result[0].name)
    }

    @Test
    fun `episode-map keys derive seasons when seasons list is empty`() {
        val result = displaySeasons(
            info(episodes = mapOf(
                "1" to listOf(episode("e1", 1), episode("e2", 2)),
                "2" to listOf(episode("e3", 1, season = 2))
            ))
        )
        assertEquals(listOf(1, 2), result.map { it.seasonNumber })
        assertEquals(2, result[0].episodeCount)
        assertEquals(1, result[1].episodeCount)
    }

    @Test
    fun `zero-padded and S-prefixed episode keys resolve`() {
        val eps = listOf(episode("e2", 2), episode("e1", 1))
        val info = info(episodes = mapOf("01" to eps))
        assertEquals(2, episodesForSeasonRaw(info, 1).size)
        assertEquals(1, episodesForSeasonRaw(info, 1)[0].episodeNum) // sorted by num

        val sPrefixed = info(episodes = mapOf("S2" to listOf(episode("e3", 1, season = 2))))
        assertEquals(1, episodesForSeasonRaw(sPrefixed, 2).size)
    }

    @Test
    fun `episode season field is the last resort`() {
        val info = info(episodes = mapOf(
            "weird-key" to listOf(episode("a", 1, season = 3))
        ))
        val seasons = displaySeasons(info)
        assertEquals(listOf(3), seasons.map { it.seasonNumber })
        assertEquals(1, seasons[0].episodeCount)
    }

    @Test
    fun `empty info defaults to season 1`() {
        assertEquals(1, firstSelectableSeasonNumber(info()))
    }

    @Test
    fun `first selectable season prefers explicit order`() {
        val s3 = Season(3, "S3", 1, null, null, null, null)
        val s1 = Season(1, "S1", 1, null, null, null, null)
        assertEquals(1, firstSelectableSeasonNumber(info(seasons = listOf(s3, s1))))
    }
}
