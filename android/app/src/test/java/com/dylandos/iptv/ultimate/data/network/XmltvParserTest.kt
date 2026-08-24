package com.dylandos.iptv.ultimate.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class XmltvParserTest {

    @Test
    fun `offset-less Xtream XMLTV time uses UTC rather than the provider timezone`() {
        val timestamp = XmltvParser.parseTime(
            raw = "20260809120000",
            sourceTimeZone = TimeZone.getTimeZone("UTC")
        )

        // Applying America/Denver here would turn this into 18:00 UTC: the six-hour bug.
        assertEquals(1_786_276_800_000L, timestamp)
    }

    @Test
    fun `explicit XMLTV offset overrides the provider timezone`() {
        val timestamp = XmltvParser.parseTime(
            raw = "20260809120000 +0000",
            sourceTimeZone = TimeZone.getTimeZone("America/Denver")
        )

        assertEquals(1_786_276_800_000L, timestamp)
    }
}
