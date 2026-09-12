package com.dylandos.iptv.ultimate.player.timeshift

/**
 * TimeshiftRingMath — pure, unit-testable sizing math for the live-TV timeshift ring.
 *
 * v5.0 replaces the old hard 512 MB cap (`LazyExoPlayerHost.TIMESHIFT_CACHE_BYTES`)
 * with a dynamic cap driven by free disk space and (optionally) a user-requested
 * rewind window. No Android dependencies — safe for JVM unit tests.
 *
 * Rules (Firestick-first: USB flash shared with DVR, 2 GB RAM, ~2.5 Mbps typical stream):
 *  - Prefer MIN_RING_BYTES (512 MB), but leave half of known remaining free space
 *    available for DVR and the OS, even when that requires a smaller ring.
 *  - Auto mode: use up to 1/8 of free space, capped at MAX_RING_BYTES (2 GB).
 *    This intentionally leaves plenty of space and write bandwidth for DVR.
 *  - Window mode: size for exactly `windowMinutes` of rewind at the estimated
 *    bitrate, but never more than one quarter of free space, still clamped to the bounds.
 */
object TimeshiftRingMath {

    /** Absolute floor — matches the old hard cap so behavior never gets worse. */
    const val MIN_RING_BYTES = 512L * 1024L * 1024L          // 512 MB

    /** Conservative ceiling — the USB is also used for DVR recordings. */
    const val MAX_RING_BYTES = 2L * 1024L * 1024L * 1024L    // 2 GB

    /** Auto mode uses at most 1/8 of free space. */
    const val FREE_SPACE_FRACTION = 8L

    /** Window mode never consumes more than one quarter of free space. */
    const val FREE_SPACE_SAFETY_FACTOR = 4L

    /** Conservative live-stream estimate used for window-mode sizing (2.5 Mbps). */
    const val DEFAULT_BITRATE_BPS = 2_500_000L

    /**
     * Compute the ring max size in bytes.
     *
     * @param freeBytes     free space on the timeshift target (<= 0 = unknown).
     * @param windowMinutes desired rewind window; 0 (or negative) = auto sizing.
     * @param bitrateBps    estimated stream bitrate, only used in window mode.
     */
    fun computeRingMaxBytes(
        freeBytes: Long,
        windowMinutes: Int = 0,
        bitrateBps: Long = DEFAULT_BITRATE_BPS
    ): Long {
        if (freeBytes <= 0L) return MIN_RING_BYTES
        val requested = if (windowMinutes > 0) {
            // Window mode: exact size for the requested rewind, safety-capped at
            // one quarter of free space so we never fill the drive.
            val need = (windowMinutes.toDouble() * 60.0 * bitrateBps.coerceAtLeast(1L) / 8.0)
                .coerceAtMost(MAX_RING_BYTES.toDouble()).toLong()
            minOf(need, freeBytes / FREE_SPACE_SAFETY_FACTOR)
        } else {
            // Auto mode: 1/8 of free space, never above the ceiling.
            maxOf(MIN_RING_BYTES, freeBytes / FREE_SPACE_FRACTION)
        }
        // Leave at least half the remaining disk available for DVR and the OS.
        val safeCap = minOf(MAX_RING_BYTES, (freeBytes / 2L).coerceAtLeast(1L))
        return requested.coerceAtLeast(MIN_RING_BYTES).coerceAtMost(safeCap)
    }

    /**
     * How many minutes of rewind a ring of [ringBytes] holds at [bitrateBps].
     * Used for the "Timeshift window" label in Settings / player overlay.
     */
    fun ringHoldsMinutes(
        ringBytes: Long,
        bitrateBps: Long = DEFAULT_BITRATE_BPS
    ): Long =
        if (bitrateBps <= 0L || ringBytes <= 0L) 0L
        else ringBytes * 8L / bitrateBps / 60L
}
