package com.dylandos.iptv.ultimate.data.filter

import android.content.Context
import androidx.datastore.preferences.core.*
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamMovie
import com.dylandos.iptv.ultimate.data.model.XtreamSeries
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DYLANDOS IPTV ULTIMATE — Content Filter Repository
 *
 * Filters channels, movies, and series by IPTV stream-name prefix.
 *
 * Common prefix formats:
 *   US|  USA|  UK|  GB|  CA|  AU|  EN|  ENG|  NA|  [US]  (US)  US:
 *
 * Two modes:
 *   WHITELIST — only show streams whose prefix is in the allowed set
 *   BLACKLIST — hide streams whose prefix is in the blocked set
 *
 * "Show untagged" controls streams with no detectable prefix.
 *
 * Usage — call filterChannels / filterMovies / filterSeries in your ViewModel
 * after the raw list comes back from the Xtream API.
 */
@Singleton
class ContentFilterRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // English / North America default whitelist for US users across Omega, Trex, Strong,
        // and most Xtream panels. These are stored as normalized tokens, not literal labels.
        val DEFAULT_EN_PREFIXES: Set<String> = setOf(
            "US", "USA",
            "EN", "ENG", "ENGLISH",
            "NA", "NAM", "NORTHAMERICA",
            "UNITEDSTATES", "AMERICA",
            "CA", "CAN", "CANADA",
            "AU", "AUS",
            "NZ", "NEWZEALAND",
            "UK", "GB", "GBR", "UNITEDKINGDOM",
            "IE"
        )

        val DEFAULT_CLEANUP_BLOCKS: Set<String> = setOf(
            "XXX", "ADULT", "18", "18PLUS", "FORADULTS",
            "TEST", "BACKUP", "DUPLICATE", "RADIO", "MUSIC",
            "VOD", "MOVIES", "MOVIE", "SERIES", "SHOWS",
            "24", "247", "247CHANNELS", "REPLAY", "CATCHUP"
        )

        private val CATEGORY_NOISE_TOKENS: Set<String> = DEFAULT_CLEANUP_BLOCKS + setOf(
            "HEVC", "H265", "H264", "SD", "FHD", "UHD", "4K", "VIP", "EVENTS"
        )

        val REGION_PRESETS: List<FilterPreset> = listOf(
            FilterPreset(
                id = "omega-trex-strong-en-na",
                title = "Omega / Trex / Strong: English NA",
                description = "Best starting point for US Firestick use. Shows EN, NA, US, USA, Canada, UK, AU/NZ and hides noisy adult/test/VOD category labels.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = DEFAULT_EN_PREFIXES,
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "us-canada",
                title = "US + Canada",
                description = "Live TV focused North America preset for providers that label groups as US, USA, CA, CAN, NA or English.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("US", "USA", "UNITEDSTATES", "AMERICA", "CA", "CAN", "CANADA", "NA", "NORTHAMERICA", "EN", "ENG", "ENGLISH"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "english-worldwide",
                title = "English Worldwide",
                description = "Keeps English-language regions: US, Canada, UK, Ireland, Australia, New Zealand and generic EN/English groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("EN", "ENG", "ENGLISH", "US", "USA", "CA", "CANADA", "UK", "GB", "GBR", "IE", "AU", "AUS", "NZ", "NEWZEALAND"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "spanish-latin",
                title = "Spanish / Latin America",
                description = "Keeps ES/Spanish and common Latin America country groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("ES", "ESP", "SPA", "SPANISH", "LATINO", "LATIN", "MX", "MEX", "MEXICO", "AR", "ARG", "CO", "COL", "CL", "CHILE", "PE", "PERU"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "portuguese-brazil",
                title = "Portuguese / Brazil",
                description = "Keeps PT/Portuguese and Brazil groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("PT", "POR", "PORTUGUESE", "BR", "BRA", "BRAZIL", "BRASIL"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "french",
                title = "French",
                description = "Keeps FR/French, France, Quebec, Belgium and Switzerland French-facing groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("FR", "FRA", "FRENCH", "FRANCE", "QUEBEC", "CAFR", "BE", "BELGIUM", "CH", "SWITZERLAND"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "arabic-mena",
                title = "Arabic / MENA",
                description = "Keeps Arabic and Middle East / North Africa groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("AR", "ARA", "ARABIC", "MENA", "AE", "UAE", "SA", "SAU", "EG", "QA", "KW", "BH", "OM", "MA", "DZ", "TN"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "south-asia",
                title = "Hindi / South Asia",
                description = "Keeps India, Pakistan, Bangladesh and common South Asian language groups.",
                mode = FilterMode.WHITELIST,
                allowedPrefixes = setOf("IN", "IND", "INDIA", "HIN", "HINDI", "UR", "URDU", "PK", "PAK", "BD", "BANGLA", "TAMIL", "TELUGU", "PUNJABI"),
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = false
            ),
            FilterPreset(
                id = "cleanup-only",
                title = "Cleanup Only",
                description = "Does not limit by country. Hides common clutter such as adult, test, backup, music, radio, VOD and series categories.",
                mode = FilterMode.BLACKLIST,
                allowedPrefixes = DEFAULT_EN_PREFIXES,
                blockedPrefixes = DEFAULT_CLEANUP_BLOCKS,
                showUntagged = true
            )
        )

        /** Full set of known prefixes for auto-detection UI */
        val KNOWN_PREFIXES: Set<String> = setOf(
            "US|", "USA|", "EN|", "ENG|", "NA|", "CA|", "UK|", "GB|",
            "AU|", "NZ|", "IE|", "FR|", "FRA|", "DE|", "DEU|", "GER|",
            "ES|", "ESP|", "SPA|", "PT|", "POR|", "IT|", "ITA|",
            "MX|", "MEX|", "AR|", "ARG|", "BR|", "BRA|",
            "NL|", "HOL|", "BE|", "CH|", "AT|",
            "PL|", "POL|", "CZ|", "SK|", "HU|", "RO|",
            "RU|", "RUS|", "UA|", "BY|",
            "TR|", "TUR|", "GR|", "RS|", "HR|", "BA|",
            "SE|", "SWE|", "NO|", "NOR|", "DK|", "DEN|", "FI|", "FIN|",
            "IN|", "IND|", "PK|", "PAK|", "BD|", "LK|",
            "AE|", "UAE|", "SA|", "SAU|", "KW|", "QA|", "BH|", "OM|",
            "EG|", "MA|", "DZ|", "TN|", "LY|",
            "NG|", "GH|", "KE|", "ZA|", "ZAF|",
            "JP|", "JPN|", "KR|", "KOR|", "CN|", "CHN|", "TW|", "HK|",
            "TH|", "VN|", "PH|", "ID|", "MY|", "SG|",
            "IL|", "ISR|", "IR|", "IRN|"
        )

        private val KEY_FILTER_ENABLED  = booleanPreferencesKey("content_filter_enabled")
        private val KEY_FILTER_MODE     = stringPreferencesKey("content_filter_mode")   // WHITELIST | BLACKLIST
        private val KEY_ALLOWED_PREFIXES = stringPreferencesKey("content_filter_allowed")
        private val KEY_BLOCKED_PREFIXES = stringPreferencesKey("content_filter_blocked")
        private val KEY_SHOW_UNTAGGED   = booleanPreferencesKey("content_filter_show_untagged")
    }

    // ── Settings Flow ──────────────────────────────────────────────────────────

    val filterSettings: Flow<FilterSettings> = context.dataStore.data.map { prefs ->
        FilterSettings(
            isEnabled = prefs[KEY_FILTER_ENABLED] ?: false,
            mode = try {
                FilterMode.valueOf(prefs[KEY_FILTER_MODE] ?: FilterMode.WHITELIST.name)
            } catch (_: IllegalArgumentException) { FilterMode.WHITELIST },
            allowedPrefixes = (prefs[KEY_ALLOWED_PREFIXES] ?: DEFAULT_EN_PREFIXES.joinToString(","))
                .split(",").mapNotNull { normalizePrefix(it) }.toSet(),
            blockedPrefixes = (prefs[KEY_BLOCKED_PREFIXES] ?: "")
                .split(",").mapNotNull { normalizePrefix(it) }.toSet(),
            showUntagged = prefs[KEY_SHOW_UNTAGGED] ?: true
        )
    }

    // ── In-memory 60s settings cache ──────────────────────────────────────────
    // PERF FIX: Home/Movies/Series each called filterSettings.first() 2-4 times per
    // navigation session — 7+ DataStore reads per session. Cache with 60s TTL eliminates
    // redundant disk reads while still picking up any user preference changes promptly.
    @Volatile private var _cachedSettings: FilterSettings? = null
    @Volatile private var _cacheTimestampMs: Long = 0L

    private suspend fun getSettings(): FilterSettings {
        val now = System.currentTimeMillis()
        _cachedSettings?.let { cached ->
            if (now - _cacheTimestampMs < 60_000L) return cached
        }
        val fresh = filterSettings.first()
        _cachedSettings = fresh
        _cacheTimestampMs = now
        return fresh
    }

    /** Invalidate the in-memory cache (called from [saveSettings]). */
    private fun invalidateCache() {
        _cachedSettings = null
        _cacheTimestampMs = 0L
    }

    suspend fun saveSettings(settings: FilterSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILTER_ENABLED]  = settings.isEnabled
            prefs[KEY_FILTER_MODE]     = settings.mode.name
            prefs[KEY_ALLOWED_PREFIXES] = settings.allowedPrefixes.mapNotNull { normalizePrefix(it) }.joinToString(",")
            prefs[KEY_BLOCKED_PREFIXES] = settings.blockedPrefixes.mapNotNull { normalizePrefix(it) }.joinToString(",")
            prefs[KEY_SHOW_UNTAGGED]   = settings.showUntagged
        }
        invalidateCache()
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    /** Filter a list of live TV channels. Returns all if filter is disabled. */
    suspend fun filterChannels(channels: List<XtreamChannel>): List<XtreamChannel> {
        val settings = getSettings()
        if (!settings.isEnabled) return channels
        return channels.filter { matchesFilter(it.name, settings) }
    }

    /** Filter a list of movies. Returns all if filter is disabled. */
    suspend fun filterMovies(movies: List<XtreamMovie>): List<XtreamMovie> {
        val settings = getSettings()
        if (!settings.isEnabled) return movies
        return movies.filter { matchesFilter(it.name, settings) }
    }

    /** Filter a list of series. Returns all if filter is disabled. */
    suspend fun filterSeries(series: List<XtreamSeries>): List<XtreamSeries> {
        val settings = getSettings()
        if (!settings.isEnabled) return series
        return series.filter { matchesFilter(it.name, settings) }
    }

    /** Filter a category list — removes categories whose name prefix doesn't match. */
    suspend fun filterCategories(categories: List<XtreamCategory>): List<XtreamCategory> {
        val settings = getSettings()
        if (!settings.isEnabled) return categories
        return categories.filter { matchesCategoryFilter(it.categoryName, settings) }
    }

    /**
     * Scans a list of channel/stream names and returns a map of
     * detected prefix → count, sorted by count descending.
     * Used by the Filter Setup UI to show only prefixes present in the user's list.
     */
    fun detectPrefixes(names: List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        names.forEach { name ->
            val prefix = extractPrefix(name)
            if (prefix != null) counts[prefix] = (counts[prefix] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun matchesFilter(name: String, settings: FilterSettings): Boolean {
        val tokens = extractTokens(name)
        val matchedAnyKnownToken = tokens.isNotEmpty()
        return when (settings.mode) {
            FilterMode.WHITELIST -> {
                val allowed = settings.allowedPrefixes.mapNotNull { normalizePrefix(it) }.toSet()
                tokens.any { it in allowed || allowed.any { allowedToken -> tokenMatches(it, allowedToken) } } ||
                    (!matchedAnyKnownToken && settings.showUntagged)
            }
            FilterMode.BLACKLIST -> {
                val blocked = settings.blockedPrefixes.mapNotNull { normalizePrefix(it) }.toSet()
                val isBlocked = tokens.any { it in blocked || blocked.any { blockedToken -> tokenMatches(it, blockedToken) } }
                !isBlocked && (matchedAnyKnownToken || settings.showUntagged)
            }
        }
    }

    private fun matchesCategoryFilter(name: String, settings: FilterSettings): Boolean {
        val tokens = extractTokens(name)
        val compactName = normalizePrefix(name).orEmpty()
        val looksNumberedOrNoisy = tokens.any { it in CATEGORY_NOISE_TOKENS } ||
            Regex("^\\s*(\\d+|\\d{2}/\\d{2}|24\\s*/\\s*7)").containsMatchIn(name) ||
            compactName.startsWith("24") ||
            compactName.startsWith("247")
        return when (settings.mode) {
            FilterMode.WHITELIST -> {
                if (looksNumberedOrNoisy) return false
                val allowed = settings.allowedPrefixes.mapNotNull { normalizePrefix(it) }.toSet()
                tokens.any { it in allowed || allowed.any { allowedToken -> tokenMatches(it, allowedToken) } } ||
                    (tokens.isEmpty() && settings.showUntagged)
            }
            FilterMode.BLACKLIST -> matchesFilter(name, settings)
        }
    }

    private val rePipe    = Regex("^([A-Z]{2,4})\\|",     RegexOption.IGNORE_CASE)
    private val reColon   = Regex("^([A-Z]{2,4}):",       RegexOption.IGNORE_CASE)
    private val reBracket = Regex("^\\[([A-Z]{2,4})\\]",  RegexOption.IGNORE_CASE)
    private val reParen   = Regex("^\\(([A-Z]{2,4})\\)",  RegexOption.IGNORE_CASE)

    internal fun extractPrefix(name: String): String? {
        if (name.isBlank()) return null
        rePipe.find(name)?.let    { return normalizePrefix(it.groupValues[1]) }
        reColon.find(name)?.let   { return normalizePrefix(it.groupValues[1]) }
        reBracket.find(name)?.let { return normalizePrefix(it.groupValues[1]) }
        reParen.find(name)?.let   { return normalizePrefix(it.groupValues[1]) }
        return null
    }

    internal fun extractTokens(name: String): Set<String> {
        if (name.isBlank()) return emptySet()
        val tokens = linkedSetOf<String>()
        extractPrefix(name)?.let(tokens::add)
        val cleaned = name
            .uppercase()
            .replace("&", " AND ")
            .replace(Regex("[\\[\\](){}]"), " ")
        cleaned
            .split(Regex("[\\s|:;/\\\\,_\\-.+]+"))
            .asSequence()
            .mapNotNull { normalizePrefix(it) }
            .filter { it.length in 2..24 }
            .take(12)
            .forEach(tokens::add)

        val compact = normalizePrefix(cleaned).orEmpty()
        listOf(
            "NORTH AMERICA" to "NORTHAMERICA",
            "UNITED STATES" to "UNITEDSTATES",
            "UNITED KINGDOM" to "UNITEDKINGDOM",
            "NEW ZEALAND" to "NEWZEALAND",
            "LATIN AMERICA" to "LATIN",
            "FOR ADULTS" to "FORADULTS"
        ).forEach { (phrase, token) ->
            if (cleaned.contains(phrase) || compact.contains(token)) tokens.add(token)
        }
        return tokens
    }

    private fun tokenMatches(candidate: String, configured: String): Boolean {
        if (candidate == configured) return true
        if (candidate.length >= 3 && configured.length >= 3) {
            return candidate.startsWith(configured) || configured.startsWith(candidate)
        }
        return false
    }

    internal fun normalizePrefix(prefix: String): String? =
        prefix.trim()
            .uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' }
            .takeIf { it.isNotBlank() }
}

// ── Data classes ────────────────────────────────────────────────────────────

data class FilterSettings(
    val isEnabled: Boolean = false,
    val mode: FilterMode = FilterMode.WHITELIST,
    val allowedPrefixes: Set<String> = ContentFilterRepository.DEFAULT_EN_PREFIXES,
    val blockedPrefixes: Set<String> = emptySet(),
    val showUntagged: Boolean = true
)

enum class FilterMode { WHITELIST, BLACKLIST }

data class FilterPreset(
    val id: String,
    val title: String,
    val description: String,
    val mode: FilterMode,
    val allowedPrefixes: Set<String>,
    val blockedPrefixes: Set<String>,
    val showUntagged: Boolean
)
