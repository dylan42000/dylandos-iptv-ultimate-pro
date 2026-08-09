package com.dylandos.iptv.ultimate.data.network

import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v5.0 time-fix regression tests for [EpgTimeAligner].
 *
 * The old alignEpgProgramsToNow heuristic shifted whole listings by whole hours
 * whenever "now" did not land inside a programme — it could not tell a provider
 * timezone skew from a schedule gap at "now", so CORRECT grids got shifted by
 * the classic 4–9 h offsets (the "guide/Live TV is ~4 hours ahead" bug).
 *
 * These tests pin the new contract:
 *  1. Correct data is NEVER shifted — with or without a gap at "now".
 *  2. Only an explicit provider `now_playing` flag can authorise a shift,
 *     and only when the flagged programme actually lands on "now" afterwards.
 */
class EpgTimeAlignerTest {

    /** nowSec — arbitrary fixed instant: 2026-08-07 14:30 local-ish wall clock. */
    private val nowSec = 1_783_000_000L

    private fun prog(
        start: Long,
        stop: Long,
        nowPlaying: Int = 0,
        title: String = "P$start"
    ) = XtreamEpgProgram(
        id = "1",
        epgId = "ch",
        title = title,
        lang = null,
        start = "",
        end = "",
        description = null,
        channelId = "ch",
        startTimestamp = start,
        stopTimestamp = stop,
        nowPlaying = nowPlaying,
        hasArchive = 0
    )

    /** Current programme is on now → data is self-consistent → untouched. */
    @Test
    fun `current programme present is never shifted`() {
        val programs = listOf(
            prog(nowSec - 3_600L, nowSec),          // just ended
            prog(nowSec, nowSec + 3_600L),          // ON NOW
            prog(nowSec + 3_600L, nowSec + 7_200L)  // next
        )
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }

    /** THE regression: correct data with a gap at "now" must stay untouched. */
    @Test
    fun `correct data with a gap at now is never shifted`() {
        // Channel went off air; the next programme starts 4h from now. This is EXACTLY
        // the case the old heuristic corrupted: a +4h shift (a "classic TZ skew" hour)
        // drags the 18:00 programme onto "now", so the whole grid was shown 4h ahead.
        val programs = listOf(
            prog(nowSec - 7_200L, nowSec - 3_600L),            // 13:00–14:00 (ended)
            prog(nowSec + 4 * 3_600L, nowSec + 5 * 3_600L),    // 18:00–19:00
            prog(nowSec + 5 * 3_600L, nowSec + 6 * 3_600L)     // 19:00–20:00
        )
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }

    /** Sparse window: only the next programme, hours away → untouched. */
    @Test
    fun `sparse single programme hours away is never shifted`() {
        val programs = listOf(
            prog(nowSec + 5 * 3_600L, nowSec + 6 * 3_600L) // next slot 5h away
        )
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }

    /** Genuine provider skew WITHOUT a now_playing flag → conservative, untouched. */
    @Test
    fun `skewed data without now_playing flag is never guessed`() {
        // All programmes uniformly 4h ahead of now, no flag. Timestamps alone cannot
        // distinguish this from a gap; we refuse to guess (reconcileProviderEpgTime
        // handles real skew at the network boundary using wall-clock text).
        val programs = listOf(
            prog(nowSec + 4 * 3_600L, nowSec + 5 * 3_600L),
            prog(nowSec + 5 * 3_600L, nowSec + 6 * 3_600L),
            prog(nowSec + 6 * 3_600L, nowSec + 7 * 3_600L)
        )
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }

    /** Provider explicitly flags a programme as now playing → snap to now. */
    @Test
    fun `now_playing flag authorises a whole-hour snap`() {
        // Flagged programme is 4h ahead of now; the flag says "this is on now".
        val flagged = prog(nowSec + 4 * 3_600L, nowSec + 5 * 3_600L, nowPlaying = 1)
        val other = prog(nowSec + 5 * 3_600L, nowSec + 6 * 3_600L)
        val programs = listOf(flagged, other)

        val aligned = EpgTimeAligner.align(programs, nowSec)

        assertEquals(nowSec, aligned[0].startTimestamp)             // 4h shift applied
        assertEquals(nowSec + 3_600L, aligned[0].stopTimestamp)
        assertEquals(nowSec + 3_600L, aligned[1].startTimestamp)    // whole listing shifted
        assertEquals(nowSec + 7_200L, aligned[1].stopTimestamp)
    }

    /** Flag exists but a whole-hour snap would NOT land it on now → untouched. */
    @Test
    fun `flag that does not land on now after snapping is ignored`() {
        // Flagged programme starts 5h25m away (mid +5h55m). Snapping to +5h leaves the
        // shifted window at +25m..+85m relative to now — it does NOT contain now → no apply.
        val off = prog(nowSec + 19_500L, nowSec + 23_100L, nowPlaying = 1)
        val programs = listOf(off)
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }

    /** Empty and invalid listings pass through untouched. */
    @Test
    fun `empty and invalid listings are untouched`() {
        assertSame(emptyList<XtreamEpgProgram>(), EpgTimeAligner.align(emptyList(), nowSec))
        val invalid = listOf(prog(0L, 0L))
        assertSame(invalid, EpgTimeAligner.align(invalid, nowSec))
    }

    /** A programme ON NOW overrides a stale now_playing flag (flag is redundant). */
    @Test
    fun `current programme wins even if a flag points elsewhere`() {
        val current = prog(nowSec - 1_800L, nowSec + 1_800L) // genuinely on now
        val staleFlag = prog(nowSec + 6 * 3_600L, nowSec + 7 * 3_600L, nowPlaying = 1)
        val programs = listOf(current, staleFlag)
        assertSame(programs, EpgTimeAligner.align(programs, nowSec))
    }
}
