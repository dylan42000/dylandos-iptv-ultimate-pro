package com.dylandos.iptv.ultimate.data.network

import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram

/**
 * DYLANDOS IPTV ULTIMATE — Conservative EPG time alignment (v5.0)
 *
 * WHY THIS EXISTS (and why it is so careful):
 * A previous heuristic snapped whole listings by whole hours whenever "now" did not
 * land inside any programme. That was meant to rescue providers whose EPG was a few
 * hours off, but it was applied to ALREADY-CORRECT data (Room XMLTV rows parsed into
 * absolute UTC instants by [XmltvParser]) and it could NOT distinguish three real cases:
 *
 *   1. Genuine provider TZ skew  (all programmes uniformly N hours off — needs fixing)
 *   2. A schedule gap at "now"   (channel between programmes; data is CORRECT — must not touch)
 *   3. Sparse windows            (only the next programme visible, hours away — CORRECT)
 *
 * With only timestamps, a gap at "now" is indistinguishable from a skewed feed: any
 * whole-hour shift that drags a distant programme onto "now" "scores" exactly the same
 * (exactly one programme can contain an instant). That ambiguity was corrupting correct
 * grids by exactly the classic TZ offsets (4–9 h) — the "guide/Live TV is ~4 hours
 * ahead" bug on Firestick.
 *
 * The fix: NEVER guess from timestamps alone. The only trustworthy corroboration is the
 * provider's explicit `now_playing` flag (a statement of "this programme is on NOW"),
 * which some Xtream panels include. Wall-clock/epoch reconciliation (see
 * [XtreamRepository.reconcileProviderEpgTime]) already fixes genuine TZ skew at the
 * network boundary using the `start`/`end` text, so a flag-less guess here is never
 * needed and always dangerous.
 *
 * Callers must NOT run this over Room XMLTV rows ([XmltvParser] output is already
 * absolute-correct). Only feed it provider short-EPG / panel listings.
 */
object EpgTimeAligner {

    /**
     * Align [programs] so that a provider-flagged "now playing" programme lands on
     * [nowSec]. Returns the input unchanged unless a flagged programme exists, its
     * midpoint is a whole number of hours from now, and shifting the listing by that
     * amount puts the flagged programme exactly on "now".
     *
     * @param nowSec current wall-clock time as epoch seconds (device-independent).
     */
    fun align(programs: List<XtreamEpgProgram>, nowSec: Long): List<XtreamEpgProgram> {
        if (programs.isEmpty()) return programs

        val usable = programs.filter {
            it.startTimestamp > 1_000_000_000L && it.stopTimestamp > it.startTimestamp
        }
        if (usable.isEmpty()) return programs

        // Rule 1 — something is on right now: the data is self-consistent. Never shift.
        if (usable.any { nowSec in it.startTimestamp until it.stopTimestamp }) return programs

        // Rule 2 — no explicit "now playing" corroboration: do not guess. A schedule gap
        // at "now" is indistinguishable from a skewed feed by timestamps alone.
        val flagged = usable.filter { it.nowPlaying == 1 }
        if (flagged.isEmpty()) return programs

        // Rule 3 — the flagged programme's midpoint, snapped to whole hours, is the
        // candidate shift. Only apply when the shifted listing actually lands the
        // flagged programme on "now" (flagScore == 1) and the shift is sane.
        val midShifts = flagged.map { (it.startTimestamp + it.stopTimestamp) / 2L - nowSec }
        val median = midShifts.sorted()[midShifts.size / 2]
        val snappedHours = ((median / 3_600.0).toInt())
        if (snappedHours == 0 || kotlin.math.abs(snappedHours) > 12) return programs

        val shiftSec = snappedHours * 3_600L
        val flagLandsOnNow = flagged.any {
            nowSec in (it.startTimestamp - shiftSec) until (it.stopTimestamp - shiftSec)
        }
        if (!flagLandsOnNow) return programs

        return programs.map { prog ->
            if (prog.startTimestamp <= 0L) prog
            else prog.copy(
                startTimestamp = prog.startTimestamp - shiftSec,
                stopTimestamp = prog.stopTimestamp - shiftSec
            )
        }
    }
}
