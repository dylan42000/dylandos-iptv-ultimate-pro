package com.dylandos.iptv.ultimate.player.timeshift

/**
 * TimeshiftRingMath — pure, unit-testable sizing math for the live-TV timeshift ring.
 *
 * v5.0 replaces the old hard 512 MB cap (`LazyExoPlayerHost.TIMESHIFT_CACHE_BYTES`)
 * with a dynamic cap driven by free disk space and (optionally) a user-requested
 * rewind window. No Android dependencies — safe for JVM unit tests.
 *
 * Rules (Firestick-first: USB flash, 2 GB RAM, ~5 Mbps typical stream):
 *  - Never below MIN_RING_BYTES (512 MB) unless the disk physically can't hold it
 *    (the caller's write-probe in `PlayerScreen.resolveTimeshiftDirectory` already
 *    refuses targets with < 256 MB free).
 *  - Auto mode: use up to 1/4 of free space, capped at MAX_RING_BYTES (8 GB).
 *    At 5 Mbps, 1/4 of a 32 GB stick ≈ 8 GB ≈ 3.5 h of rewind.
 *  - Window mode: size for exactly `windowMinutes` of rewind at the estimated
 *    bitrate, but never more than half of free space, still clamped to the bounds.
 */
object TimeshiftRingMath {

    /** Absolute floor — matches the old hard cap so behavior never gets worse. */
    const val MIN_RING_BYTES = 512L * 1024L * 1024L          // 512 MB

    /** Absolute ceiling — prevents one channel from eating a whole USB stick. */
    const val MAX_RING_BYTES = 8L * 1024L * 1024L * 1024L    // 8 GB

    /** Auto mode uses up to 1/4 of free space. */
    const val FREE_SPACE_FRACTION = 4L

    /** Window mode never consumes more than half of free space. */
    const val FREE_SPACE_SAFETY_FACTOR = 2L

    /** Typical live-stream bitrate used for window-mode sizing (5 Mbps). */
    const val DEFAULT_BITRATE_BPS = 5_000_000L

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
            // half the free space so we never fill the drive.
            val need = windowMinutes.toLong() * 60L * bitrateBps.coerceAtLeast(1L) / 8L
            minOf(need, freeBytes / FREE_SPACE_SAFETY_FACTOR)
        } else {
            // Auto mode: 1/4 of free space, never above the ceiling.
            maxOf(MIN_RING_BYTES, freeBytes / FREE_SPACE_FRACTION)
        }
        return requested.coerceIn(MIN_RING_BYTES, MAX_RING_BYTES)
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
