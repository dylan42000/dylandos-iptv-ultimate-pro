package com.dylandos.iptv.ultimate.data.network

import java.util.TimeZone

/**
 * Curated public XMLTV feeds used as optional EPG fallbacks when the IPTV provider's
 * own XMLTV/short-EPG data is missing or mismatched.
 */
object EpgSourceDefaults {
    val DEFAULT_URLS = listOf(
        "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
        "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"
    )

    val DEFAULT_URL_BLOCK: String = DEFAULT_URLS.joinToString("\n")

    fun parseUrlBlock(raw: String?): List<String> {
        val source = raw?.takeIf { it.isNotBlank() } ?: DEFAULT_URL_BLOCK
        return source
            .split(Regex("[\\n,;|]+"))
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
    }

    fun maxProgramsFor(url: String): Int = when {
        url.contains("US_LOCALS", ignoreCase = true) -> 90_000
        url.contains("US_SPORTS", ignoreCase = true) -> 55_000
        url.contains("US2", ignoreCase = true) -> 110_000
        else -> 70_000
    }

    /**
     * Zone for offset-less timestamps in a third-party feed.
     * Feeds that already include `±HHMM` ignore this (XmltvParser uses the offset).
     */
    fun timeZoneFor(url: String): TimeZone {
        val id = when {
            url.contains("_UK", ignoreCase = true) || url.contains("UK1", ignoreCase = true) ->
                "Europe/London"
            url.contains("_CA", ignoreCase = true) || url.contains("CA2", ignoreCase = true) ->
                "America/Toronto"
            url.contains("_AU", ignoreCase = true) -> "Australia/Sydney"
            url.contains("_US", ignoreCase = true) || url.contains("US2", ignoreCase = true) ||
                url.contains("US_LOCALS", ignoreCase = true) ||
                url.contains("US_SPORTS", ignoreCase = true) ->
                "America/New_York"
            else -> "UTC"
        }
        return TimeZone.getTimeZone(id)
    }
}
