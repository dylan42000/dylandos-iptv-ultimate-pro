package com.dylandos.iptv.ultimate.player.timeshift

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.0: unit tests for the dynamic timeshift ring sizing math. Pure JVM.
 *
 * Constants: MIN = 512 MB, MAX = 8 GB. Auto mode = 1/4 of free space.
 * Window mode = window × bitrate, capped at half of free space.
 */
class TimeshiftRingMathTest {

    private val MB = 1024L * 1024L
    private val GB = 1024L * MB

    @Test
    fun `unknown free space falls back to the 512MB minimum`() {
        assertEquals(TimeshiftRingMath.MIN_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(0L))
        assertEquals(TimeshiftRingMath.MIN_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(-1L))
    }

    @Test
    fun `auto mode uses one quarter of free space`() {
        // 4 GB free -> 1 GB ring.
        assertEquals(1 * GB, TimeshiftRingMath.computeRingMaxBytes(4 * GB))
        // 8 GB free -> 2 GB ring.
        assertEquals(2 * GB, TimeshiftRingMath.computeRingMaxBytes(8 * GB))
    }

    @Test
    fun `auto mode never drops below the 512MB floor`() {
        // 1 GB free -> 256 MB would be 1/4, floor kicks in.
        assertEquals(TimeshiftRingMath.MIN_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(1 * GB))
    }

    @Test
    fun `auto mode never exceeds the 8GB ceiling`() {
        // 64 GB free -> 16 GB would be 1/4, ceiling kicks in.
        assertEquals(TimeshiftRingMath.MAX_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(64 * GB))
        // 32 GB free -> exactly 8 GB, allowed.
        assertEquals(TimeshiftRingMath.MAX_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(32 * GB))
    }

    @Test
    fun `window mode sizes for the requested rewind window`() {
        // 60 min at 5 Mbps = 60*60*5_000_000/8 = 2_250_000_000 bytes (~2.25 GB).
        val expected = 60L * 60L * 5_000_000L / 8L
        assertEquals(expected, TimeshiftRingMath.computeRingMaxBytes(10 * GB, windowMinutes = 60))
    }

    @Test
    fun `window mode never exceeds half of free space`() {
        // Need 2.25 GB but only 2 GB free -> capped at 1 GB.
        val expected = 1 * GB
        assertEquals(expected, TimeshiftRingMath.computeRingMaxBytes(2 * GB, windowMinutes = 60))
    }

    @Test
    fun `window mode still respects the floor on tiny disks`() {
        // Need 2.25 GB, free/2 = 128 MB -> floor 512 MB wins.
        assertEquals(
            TimeshiftRingMath.MIN_RING_BYTES,
            TimeshiftRingMath.computeRingMaxBytes(256 * MB, windowMinutes = 60)
        )
    }

    @Test
    fun `window mode still respects the ceiling on huge disks`() {
        // Need 2.25 GB, free/2 = 16 GB -> min is 2.25 GB, but test a 240-min request:
        // 240*60*5e6/8 = 9 GB -> clamped to MAX.
        assertEquals(
            TimeshiftRingMath.MAX_RING_BYTES,
            TimeshiftRingMath.computeRingMaxBytes(64 * GB, windowMinutes = 240)
        )
    }

    @Test
    fun `ringHoldsMinutes converts bytes to rewind time at 5 Mbps`() {
        // 512 MB @ 5 Mbps ≈ 14 min (integer math).
        assertEquals(14L, TimeshiftRingMath.ringHoldsMinutes(TimeshiftRingMath.MIN_RING_BYTES))
        // 8 GB @ 5 Mbps ≈ 229 min.
        assertEquals(229L, TimeshiftRingMath.ringHoldsMinutes(TimeshiftRingMath.MAX_RING_BYTES))
    }

    @Test
    fun `ringHoldsMinutes guards against bad input`() {
        assertEquals(0L, TimeshiftRingMath.ringHoldsMinutes(0L))
        assertEquals(0L, TimeshiftRingMath.ringHoldsMinutes(1L * GB, bitrateBps = 0L))
    }
}
