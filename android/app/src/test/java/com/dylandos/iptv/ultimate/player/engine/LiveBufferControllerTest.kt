package com.dylandos.iptv.ultimate.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.0: unit tests for the Adaptive Buffer Controller — the core of the
 * "channels periodically lag" fix. Pure JVM, no device needed; part of the
 * `gradlew test` CI gate.
 */
class LiveBufferControllerTest {

    private val t0 = 1_000_000L // arbitrary epoch for injectable nowMs

    @Test
    fun `starts at the floor`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000)
        assertEquals(600, c.cacheMs.value)
        assertEquals(0, c.raiseCount)
        assertFalse(c.suggestHdMode)
    }

    @Test
    fun `rebuffer raises the cache by 400ms`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0)
        assertEquals(1000, c.cacheMs.value)
        assertEquals(1, c.raiseCount)
    }

    @Test
    fun `honours an explicit initial cache`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000, initialCacheMs = 2000)
        assertEquals(2000, c.cacheMs.value)
        // rebuffer still raises from the initial value
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0)
        assertEquals(2400, c.cacheMs.value)
        // reset returns to the initial value, not the floor
        c.reset()
        assertEquals(2000, c.cacheMs.value)
    }

    @Test
    fun `initial cache outside the band is clamped`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 2000, initialCacheMs = 9000)
        assertEquals(2000, c.cacheMs.value)
        val low = LiveBufferController(floorMs = 600, ceilingMs = 2000, initialCacheMs = 100)
        assertEquals(600, low.cacheMs.value)
    }

    @Test
    fun `cache never exceeds the ceiling`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 1000)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0)          // 600 -> 1000
        assertEquals(1000, c.cacheMs.value)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0 + 1000)   // at ceiling
        assertEquals(1000, c.cacheMs.value)
        // Only actual raises count; a rebuffer at the ceiling flags HD mode instead.
        assertEquals(1, c.raiseCount)
        assertTrue(c.suggestHdMode) // no room left -> suggest HD mode
    }

    @Test
    fun `suggests hd mode after maxRaises`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000, maxRaises = 3)
        repeat(3) { c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0 + it * 1000L) }
        assertEquals(1800, c.cacheMs.value)
        assertFalse(c.suggestHdMode)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0 + 10_000L)
        assertEquals(1800, c.cacheMs.value) // no further raises
        assertTrue(c.suggestHdMode)
    }

    @Test
    fun `no relax within the first minute after a raise`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0)          // 1000
        repeat(90) { c.onEvent(LiveBufferEvent.HEALTHY_TICK, nowMs = t0 + 10_000L + it * 2_000L) }
        // All ticks happened < 60 s after the raise -> cache stays raised.
        assertEquals(1000, c.cacheMs.value)
    }

    @Test
    fun `relaxes by 200ms after ~3 minutes of clean playback`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000)
        c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0)          // 1000
        // 90 healthy ticks starting 61 s after the raise (every 2 s = ~3 min).
        repeat(90) { c.onEvent(LiveBufferEvent.HEALTHY_TICK, nowMs = t0 + 61_000L + it * 2_000L) }
        assertEquals(800, c.cacheMs.value)
    }

    @Test
    fun `never relaxes below the floor`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000)
        repeat(200) { c.onEvent(LiveBufferEvent.HEALTHY_TICK, nowMs = t0 + it * 2_000L) }
        assertEquals(600, c.cacheMs.value)
        assertEquals(0, c.raiseCount)
    }

    @Test
    fun `reset starts a fresh session`() {
        val c = LiveBufferController(floorMs = 600, ceilingMs = 4000, maxRaises = 2)
        repeat(3) { c.onEvent(LiveBufferEvent.REBUFFER, nowMs = t0 + it * 1000L) }
        assertTrue(c.suggestHdMode)
        c.reset()
        assertEquals(600, c.cacheMs.value)
        assertEquals(0, c.raiseCount)
        assertFalse(c.suggestHdMode)
    }

    @Test
    fun `rejects invalid configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveBufferController(floorMs = 4000, ceilingMs = 600)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveBufferController(floorMs = 50, ceilingMs = 4000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveBufferController(floorMs = 600, ceilingMs = 4000, maxRaises = 0)
        }
    }
}
