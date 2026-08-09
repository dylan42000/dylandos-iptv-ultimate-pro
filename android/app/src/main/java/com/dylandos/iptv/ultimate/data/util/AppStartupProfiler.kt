package com.dylandos.iptv.ultimate.data.util

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppStartupProfiler — Measures and logs cold-start performance milestones.
 *
 * Target: cold start to interactive < 2 500 ms on a Firestick 4K.
 *
 * Milestones to record (typical order):
 *   1. "app_create_start"   — beginning of Application.onCreate()
 *   2. "hilt_injected"      — Hilt injection complete
 *   3. "coil_ready"         — Coil image loader configured
 *   4. "work_manager_ready" — WorkManager initialized
 *   5. "first_frame"        — first Compose frame drawn (call from MainActivity)
 *   6. "splash_dismissed"   — splash screen gone, user sees UI
 *   7. "data_ready"         — categories loaded from Room cache, UI is interactive
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate():
 * profiler.recordMilestone("app_create_start")
 *
 * // In MainActivity, inside first LaunchedEffect:
 * profiler.recordMilestone("first_frame")
 * profiler.logSummary()
 * ```
 *
 * All timing is relative to the moment the profiler singleton is first created
 * (i.e., the instant Hilt initialises it during Application.onCreate()).
 */
@Singleton
class AppStartupProfiler @Inject constructor() {

    /** Timestamp of profiler creation — used as T0 for all elapsed calculations. */
    private val t0 = System.currentTimeMillis()

    /** Ordered list of (label, elapsedMs) pairs recorded via [recordMilestone]. */
    private val milestones = mutableListOf<Pair<String, Long>>()

    /**
     * Record a named milestone.
     *
     * @param name Human-readable label for the event (e.g. "data_ready").
     *             Use snake_case for easy log grepping.
     */
    fun recordMilestone(name: String) {
        val elapsed = System.currentTimeMillis() - t0
        synchronized(milestones) {
            milestones.add(name to elapsed)
        }
        Timber.d("STARTUP  %-32s  %4d ms".format(name, elapsed))
    }

    /**
     * Print a formatted summary table to Timber.
     *
     * Call once after the final milestone (typically "data_ready").
     * Emits a warning if total time exceeds the 2 500 ms target.
     */
    fun logSummary() {
        val snapshot: List<Pair<String, Long>>
        synchronized(milestones) {
            snapshot = milestones.toList()
        }

        Timber.d("═══════════════════════════════════════════════")
        Timber.d("         DYLANDOS STARTUP PERFORMANCE          ")
        Timber.d("═══════════════════════════════════════════════")

        var prev = 0L
        snapshot.forEach { (name, elapsed) ->
            val delta = elapsed - prev
            Timber.d("  %-30s  %5d ms  (+%d ms)".format(name, elapsed, delta))
            prev = elapsed
        }

        val total = snapshot.lastOrNull()?.second ?: 0L
        Timber.d("───────────────────────────────────────────────")
        Timber.d("  TOTAL                             %5d ms".format(total))

        if (total > TARGET_MS) {
            Timber.w("  ⚠ EXCEEDS ${TARGET_MS}ms TARGET by ${total - TARGET_MS}ms — investigate bottlenecks")
        } else {
            Timber.d("  ✓ WITHIN ${TARGET_MS}ms TARGET (${TARGET_MS - total}ms headroom)")
        }
        Timber.d("═══════════════════════════════════════════════")
    }

    /**
     * Return the elapsed ms for a previously recorded milestone, or -1 if not found.
     * Useful for conditional logic (e.g., showing a "slow device" hint).
     */
    fun getElapsedFor(name: String): Long {
        synchronized(milestones) {
            return milestones.firstOrNull { it.first == name }?.second ?: -1L
        }
    }

    /**
     * Clear all recorded milestones. Useful for warm-start comparisons in debug builds.
     */
    fun reset() {
        synchronized(milestones) {
            milestones.clear()
        }
    }

    companion object {
        /** Cold-start target in milliseconds (Firestick 4K baseline). */
        const val TARGET_MS = 2_500L
    }
}
