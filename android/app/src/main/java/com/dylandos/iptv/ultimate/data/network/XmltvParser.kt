package com.dylandos.iptv.ultimate.data.network

import android.util.Xml
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

/**
 * DYLANDOS IPTV ULTIMATE — XMLTV Streaming Parser
 *
 * Parses the XMLTV format served by Xtream at /xmltv.php (7-day EPG for all channels).
 * Uses Android XmlPullParser (streaming SAX-style) to handle large files (50 MB+)
 * without loading the entire document into memory.
 *
 * Programmes are stored once under the canonical XMLTV channel id. Display-name /
 * id variants are returned separately as alias → canonical mappings so Room does
 * not N× duplicate every programme row.
 *
 * Timezone rules:
 * - Timestamps with an explicit offset (`±HHMM` / `±HH:MM` / trailing `Z`) are parsed
 *   as absolute instants (correct on every Fire TV timezone).
 * - Offset-less `yyyyMMddHHmmss` values are interpreted in [sourceTimeZone], never the
 *   device zone — device-local parsing was shifting whole grids on Firestick.
 */
object XmltvParser {

    data class ParseResult(
        val programs: List<EpgProgramEntity>,
        /** Every known alias (id, display-name, normalised keys) → canonical channel id. */
        val aliasToCanonical: Map<String, String>
    )

    private val dateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                isLenient = false
                // Pattern includes an explicit offset; formatter TZ is unused for those.
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    /**
     * Parse a single XMLTV start/stop attribute into UTC epoch millis.
     *
     * @param sourceTimeZone Zone used only when the timestamp has no offset suffix.
     * @param timeOffsetHours Manual offset in hours to shift the parsed time.
     */
    fun parseTime(raw: String, sourceTimeZone: TimeZone = TimeZone.getTimeZone("UTC"), timeOffsetHours: Int = 0): Long {
        if (raw.isBlank()) return 0L

        var cleaned = raw.trim()
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            .replace(Regex("(\\d{14})([Zz])$"), "$1 +0000")
            .replace(Regex("(\\d{14})([+-]\\d{4})$"), "$1 $2")

        // Offset-less wall clock → interpret in the feed/provider zone, not device TZ.
        if (Regex("^\\d{14}$").matches(cleaned)) {
            val local = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                isLenient = false
                timeZone = sourceTimeZone
            }.parse(cleaned)
            val offsetMinutes = sourceTimeZone.getOffset(local?.time ?: System.currentTimeMillis()) / 60_000
            val sign = if (offsetMinutes >= 0) "+" else "-"
            val absolute = kotlin.math.abs(offsetMinutes)
            cleaned += " %s%02d%02d".format(Locale.US, sign, absolute / 60, absolute % 60)
        }

        return try {
            val parsedMs = dateFormat.get()?.parse(cleaned)?.time ?: 0L
            if (parsedMs > 0L) parsedMs + (timeOffsetHours * 3600_000L) else 0L
        } catch (e: Exception) {
            Timber.w("XMLTV: could not parse time '$raw' (normalized: '$cleaned')")
            0L
        }
    }

    private fun normalizeChannelId(value: String): String =
        value.trim()
            .lowercase(Locale.US)
            .replace(" ", "")
            .replace("_", "")

    private fun channelKeys(value: String): Set<String> {
        val normalized = normalizeChannelId(value)
        val withoutSuffix = normalized.substringBefore('.')
        return setOf(normalized, withoutSuffix)
    }

    /**
     * Parse [stream] as XMLTV XML.
     * Programmes are emitted once per canonical channel id; aliases are returned separately.
     *
     * @param sourceTimeZone Zone for offset-less timestamps (provider TZ or feed region).
     * @param timeOffsetHours Manual offset in hours to shift all timestamps.
     */
    fun parse(
        stream: InputStream,
        acceptedChannelIds: Set<String> = emptySet(),
        sourceTimeZone: TimeZone = TimeZone.getTimeZone("UTC"),
        timeOffsetHours: Int = 0,
        maxPrograms: Int = Int.MAX_VALUE
    ): ParseResult {
        val programs = mutableListOf<EpgProgramEntity>()
        val channelAliases = mutableMapOf<String, Set<String>>()
        val aliasToCanonical = LinkedHashMap<String, String>()
        val acceptedKeys = acceptedChannelIds
            .flatMapTo(hashSetOf()) { channelKeys(it) }
        val now = System.currentTimeMillis()
        val earliestUseful = now - 6 * 3_600_000L
        val latestUseful = now + 8 * 24 * 3_600_000L

        fun rememberAliases(canonical: String, aliases: Collection<String>) {
            if (canonical.isBlank()) return
            aliasToCanonical.putIfAbsent(canonical, canonical)
            aliasToCanonical.putIfAbsent(canonical.lowercase(Locale.US), canonical)
            for (alias in aliases) {
                if (alias.isBlank()) continue
                aliasToCanonical.putIfAbsent(alias, canonical)
                aliasToCanonical.putIfAbsent(alias.lowercase(Locale.US), canonical)
                for (key in channelKeys(alias)) {
                    aliasToCanonical.putIfAbsent(key, canonical)
                }
            }
        }

        try {
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (programs.size >= maxPrograms) break
                if (event == XmlPullParser.START_TAG && parser.name == "channel") {
                    val channelId = parser.getAttributeValue(null, "id") ?: ""
                    val aliases = LinkedHashSet<String>()
                    if (channelId.isNotBlank()) {
                        aliases.add(channelId)
                        aliases.add(channelId.lowercase(Locale.US))
                        aliases.addAll(channelKeys(channelId))
                    }

                    var inner = parser.next()
                    while (!(inner == XmlPullParser.END_TAG && parser.name == "channel")) {
                        if (inner == XmlPullParser.START_TAG && parser.name == "display-name") {
                            val displayName = parser.nextText().trim()
                            if (displayName.isNotBlank()) {
                                aliases.add(displayName)
                                aliases.add(displayName.lowercase(Locale.US))
                                aliases.addAll(channelKeys(displayName))
                            }
                        }
                        inner = parser.next()
                    }

                    if (channelId.isNotBlank() && aliases.isNotEmpty()) {
                        channelAliases[channelId] = aliases
                        rememberAliases(channelId, aliases)
                    }
                } else if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                    val startRaw  = parser.getAttributeValue(null, "start")   ?: ""
                    val stopRaw   = parser.getAttributeValue(null, "stop")    ?: ""
                    val channelId = parser.getAttributeValue(null, "channel") ?: ""
                    val startMs   = parseTime(startRaw, sourceTimeZone, timeOffsetHours)
                    val stopMs    = parseTime(stopRaw, sourceTimeZone, timeOffsetHours)
                    val channelAccepted = acceptedKeys.isEmpty() ||
                        channelKeys(channelId).any { it in acceptedKeys }

                    var title = ""
                    var desc  = ""

                    var inner = parser.next()
                    while (!(inner == XmlPullParser.END_TAG && parser.name == "programme")) {
                        if (inner == XmlPullParser.START_TAG) {
                            when (parser.name) {
                                "title" -> title = parser.nextText()
                                "desc"  -> desc  = parser.nextText()
                            }
                        }
                        inner = parser.next()
                    }

                    if (
                        channelAccepted &&
                        channelId.isNotEmpty() &&
                        title.isNotEmpty() &&
                        startMs > 0L &&
                        stopMs > startMs &&
                        stopMs >= earliestUseful &&
                        startMs <= latestUseful
                    ) {
                        val aliases = linkedSetOf(channelId, channelId.lowercase(Locale.US))
                        aliases.addAll(channelKeys(channelId))
                        channelAliases[channelId]?.let { aliases.addAll(it) }
                        rememberAliases(channelId, aliases)

                        programs.add(
                            EpgProgramEntity(
                                channelId   = channelId,
                                title       = title,
                                description = desc,
                                startTime   = startMs,
                                endTime     = stopMs
                            )
                        )
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Timber.e(e, "XMLTV parse error after ${programs.size} programs")
        }
        Timber.d(
            "XMLTV: parsed ${programs.size} programs, ${aliasToCanonical.size} aliases " +
                "(zone=${sourceTimeZone.id})"
        )
        return ParseResult(programs = programs, aliasToCanonical = aliasToCanonical)
    }
}
