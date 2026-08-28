package com.dylandos.iptv.ultimate.ui.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @After
    fun resetDisplayZone() {
        TimeFormatter.setDisplayTimeZone("device")
    }

    @Test
    fun `Denver override renders a UTC instant in Mountain time`() {
        // 2026-08-24T15:52:00Z is 9:52 AM in Denver during daylight saving time.
        TimeFormatter.setDisplayTimeZone("America/Denver")

        assertEquals("9:52 AM", TimeFormatter.formatShortTime(1_787_586_720_000L, "12h"))
    }
}
