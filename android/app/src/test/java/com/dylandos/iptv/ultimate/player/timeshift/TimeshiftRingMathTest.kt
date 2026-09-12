package com.dylandos.iptv.ultimate.player.timeshift

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.0: unit tests for the dynamic timeshift ring sizing math. Pure JVM.
 *
 * Constants: MIN = 512 MB, MAX = 2 GB. Auto mode = 1/8 of free space.
 * Window mode = window × bitrate, capped at one quarter of free space.
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
    fun `auto mode uses one eighth of free space`() {
        // 8 GB free -> 1 GB ring.
        assertEquals(1 * GB, TimeshiftRingMath.computeRingMaxBytes(8 * GB))
        // 16 GB free -> 2 GB ring.
        assertEquals(2 * GB, TimeshiftRingMath.computeRingMaxBytes(16 * GB))
    }

    @Test
    fun `auto mode never drops below the 512MB floor`() {
        // 1 GB free -> 128 MB would be 1/8, floor kicks in.
        assertEquals(TimeshiftRingMath.MIN_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(1 * GB))
    }

    @Test
    fun `auto mode never exceeds the 2GB ceiling`() {
        // 64 GB free -> 8 GB would be 1/8, ceiling kicks in.
        assertEquals(TimeshiftRingMath.MAX_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(64 * GB))
        // 16 GB free -> exactly 2 GB, allowed.
        assertEquals(TimeshiftRingMath.MAX_RING_BYTES, TimeshiftRingMath.computeRingMaxBytes(16 * GB))
    }

    @Test
    fun `window mode sizes for the requested rewind window`() {
        // 60 min at 2.5 Mbps = 60*60*2_500_000/8 = 1_125_000_000 bytes.
        val expected = 60L * 60L * 2_500_000L / 8L
        assertEquals(expected, TimeshiftRingMath.computeRingMaxBytes(10 * GB, windowMinutes = 60))
    }

    @Test
    fun `window mode never exceeds one quarter of free space`() {
        // Need 1.125 GB but only 2 GB free -> capped at 512 MB.
        val expected = 512 * MB
        assertEquals(expected, TimeshiftRingMath.computeRingMaxBytes(2 * GB, windowMinutes = 60))
    }

    @Test
    fun `tiny disks leave half the space available for DVR`() {
        // The nominal 512MB floor must never overfill a 256MB disk.
        assertEquals(
            128 * MB,
            TimeshiftRingMath.computeRingMaxBytes(256 * MB, windowMinutes = 60)
        )
    }

    @Test
    fun `window mode still respects the ceiling on huge disks`() {
        // 240 min needs 4.5 GB, so the conservative 2 GB cap applies.
        assertEquals(
            TimeshiftRingMath.MAX_RING_BYTES,
            TimeshiftRingMath.computeRingMaxBytes(64 * GB, windowMinutes = 240)
        )
    }

    @Test
    fun `ringHoldsMinutes converts bytes to rewind time at 2 point 5 Mbps`() {
        // 512 MB @ 2.5 Mbps ≈ 28 min (integer math).
        assertEquals(28L, TimeshiftRingMath.ringHoldsMinutes(TimeshiftRingMath.MIN_RING_BYTES))
        // 2 GB @ 2.5 Mbps ≈ 114 min.
        assertEquals(114L, TimeshiftRingMath.ringHoldsMinutes(TimeshiftRingMath.MAX_RING_BYTES))
    }

    @Test
    fun `ringHoldsMinutes guards against bad input`() {
        assertEquals(0L, TimeshiftRingMath.ringHoldsMinutes(0L))
        assertEquals(0L, TimeshiftRingMath.ringHoldsMinutes(1L * GB, bitrateBps = 0L))
    }
}
