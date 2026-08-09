package com.dylandos.iptv.ultimate.data.network

import java.util.Locale

/**
 * DYLANDOS IPTV ULTIMATE — EPG ↔ Channel fuzzy matcher (Module 3.1)
 *
 * IPTV providers are sloppy with EPG data. The XMLTV `channel` id rarely matches the
 * Xtream `epg_channel_id` 1:1, and many channels ship no usable id at all. This matcher
 * runs the multi-pass fallback the guide uses to maximise EPG population:
 *
 *   1. exact `epg_channel_id`            (handled by the caller via the DAO)
 *   2. light id variants (no suffix…)    (handled by the caller)
 *   3. sanitised exact match             ← [sanitize] + [SanitizedIndex.exact]
 *   4. fuzzy match (Levenshtein)         ← [SanitizedIndex.closest]
 *
 * Sanitisation strips quality tags (HD/FHD/UHD/4K/SD…), common country codes/prefixes,
 * provider noise (VIP, backup, ‖ markers), and every non-alphanumeric character, then
 * lowercases. "BBC One HD ᴿᴬᵂ |UK|" and the XMLTV id "bbcone.uk" both collapse to "bbcone".
 *
 * Everything here is pure/allocation-light so it is safe to run for thousands of channels
 * on a 2 GB Firestick: the [SanitizedIndex] is built once per refresh and reused.
 */
object EpgChannelMatcher {

    // Quality / language / provider noise tokens removed wholesale before sanitising.
    private val NOISE_TOKENS = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "265", "264",
        "fullhd", "ultrahd", "hq", "lq", "vip", "raw", "backup", "alt", "feed",
        "channel", "tv", "ch", "the"
    )

    // Leading/trailing country codes seen as separate tokens, e.g. "US: ESPN", "ESPN (UK)".
    private val COUNTRY_TOKENS = setOf(
        "us", "usa", "uk", "ca", "gb", "au", "nz", "ie", "in", "pk", "de", "fr", "es",
        "it", "nl", "pt", "br", "mx", "ar", "tr", "ru", "pl", "ro", "gr", "ar", "sa",
        "ae", "qa", "eu", "latino", "latin", "english", "arabic", "spanish"
    )

    /**
     * Reduce a channel/EPG name to a comparable fingerprint:
     * lowercase, strip bracketed country tags, drop noise/country tokens, keep [a-z0-9] only.
     */
    fun sanitize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.lowercase(Locale.US)
        // Drop bracketed qualifiers like "(US)", "[FHD]", "{backup}".
        s = s.replace(Regex("[\\(\\[{][^\\)\\]}]*[\\)\\]}]"), " ")
        // Split on any non-alphanumeric so we can filter token-wise.
        val tokens = s.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        val kept = tokens.filterIndexed { index, token ->
            val isEdge = index == 0 || index == tokens.lastIndex
            when {
                token in NOISE_TOKENS -> false
                // Only strip a country token when it sits at the edge (prefix/suffix);
                // an interior "us" could be part of a real name.
                isEdge && token in COUNTRY_TOKENS -> false
                else -> true
            }
        }
        // If filtering removed everything (e.g. name was just "HD US"), fall back to the
        // raw alphanumerics so we never produce an empty key for a real channel.
        val source = if (kept.isEmpty()) tokens else kept
        return source.joinToString("")
    }

    /** Classic iterative Levenshtein edit distance with an early-out [max] cap. */
    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            // Whole row already exceeds the cap — no path can come back under it.
            if (rowMin > max) return max + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /**
     * Pre-sanitised index of the EPG channel ids actually present in storage.
     * Built once per refresh from `EpgProgramDao.getDistinctChannelIds()`.
     */
    class SanitizedIndex(rawChannelIds: Collection<String>) {
        // sanitisedKey → original channelId (first writer wins; ids are usually unique anyway)
        private val byKey: Map<String, String>
        private val keys: List<String>

        init {
            val map = LinkedHashMap<String, String>(rawChannelIds.size)
            for (id in rawChannelIds) {
                val key = sanitize(id)
                if (key.isNotEmpty() && key !in map) map[key] = id
            }
            byKey = map
            keys = map.keys.toList()
        }

        val isEmpty: Boolean get() = byKey.isEmpty()

        /** Exact sanitised-key hit → original channelId, or null. */
        fun exact(name: String): String? {
            val key = sanitize(name)
            if (key.isEmpty()) return null
            return byKey[key]
        }

        /**
         * Closest sanitised channelId within an adaptive edit-distance budget, or null.
         * Budget scales with the shorter key length so short names need near-exact matches
         * while longer names tolerate a little more drift. Substring containment (one key
         * fully inside the other) is accepted directly — covers "skysportsmain" vs "skysports".
         */
        fun closest(name: String): String? {
            val target = sanitize(name)
            if (target.isEmpty() || keys.isEmpty()) return null
            byKey[target]?.let { return it }

            var bestKey: String? = null
            var bestDistance = Int.MAX_VALUE
            for (key in keys) {
                if (key.length >= 4 && target.length >= 4 &&
                    (key.contains(target) || target.contains(key))
                ) {
                    return byKey[key]
                }
                val budget = maxOf(1, minOf(target.length, key.length) / 4)
                val d = levenshtein(target, key, budget)
                if (d <= budget && d < bestDistance) {
                    bestDistance = d
                    bestKey = key
                }
            }
            return bestKey?.let { byKey[it] }
        }
    }
}
