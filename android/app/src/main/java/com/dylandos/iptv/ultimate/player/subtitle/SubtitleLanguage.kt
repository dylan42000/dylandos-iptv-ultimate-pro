package com.dylandos.iptv.ultimate.player.subtitle

/**
 * Match Settings ISO-639-2/B codes (eng/fra/…) to player track tags (en/eng/English).
 */
object SubtitleLanguage {

    private val aliases: Map<String, Set<String>> = mapOf(
        "eng" to setOf("eng", "en", "english"),
        "fra" to setOf("fra", "fre", "fr", "french"),
        "spa" to setOf("spa", "es", "spanish", "castellano"),
        "ger" to setOf("ger", "deu", "de", "german", "deutsch"),
        "deu" to setOf("ger", "deu", "de", "german", "deutsch"),
        "por" to setOf("por", "pt", "portuguese", "português", "portugues"),
        "ita" to setOf("ita", "it", "italian", "italiano"),
        "jpn" to setOf("jpn", "ja", "jp", "japanese"),
        "chi" to setOf("chi", "zho", "zh", "chinese", "mandarin"),
        "kor" to setOf("kor", "ko", "korean"),
        "rus" to setOf("rus", "ru", "russian"),
        "ara" to setOf("ara", "ar", "arabic"),
        "hin" to setOf("hin", "hi", "hindi"),
        "nld" to setOf("nld", "dut", "nl", "dutch"),
        "pol" to setOf("pol", "pl", "polish"),
        "tur" to setOf("tur", "tr", "turkish"),
    )

    fun normalize(codeOrName: String?): String =
        codeOrName?.trim()?.lowercase().orEmpty()

    fun matches(trackLanguage: String?, preferred: String?): Boolean {
        val track = normalize(trackLanguage)
        val pref = normalize(preferred)
        if (track.isEmpty() || pref.isEmpty()) return false
        if (track == pref || track.startsWith(pref) || pref.startsWith(track)) return true
        val prefSet = aliases[pref] ?: setOf(pref)
        val trackSet = aliases[track] ?: setOf(track)
        if (prefSet.any { it in trackSet || track.startsWith(it) || it.startsWith(track) }) return true
        // Also match when track label embeds the preferred name/code.
        return prefSet.any { track.contains(it) }
    }

    fun labelContainsPreferred(label: String?, preferred: String?): Boolean {
        val text = normalize(label)
        val pref = normalize(preferred)
        if (text.isEmpty() || pref.isEmpty()) return false
        val prefSet = aliases[pref] ?: setOf(pref)
        return prefSet.any { text.contains(it) }
    }

    /** Media3 / ExoPlayer prefer ISO-639-1 (en) over 639-2/B (eng). */
    fun toMedia3LanguageTag(preferred: String?): String {
        val pref = normalize(preferred)
        if (pref.isEmpty()) return "en"
        return when (pref) {
            "eng" -> "en"
            "fra", "fre" -> "fr"
            "spa" -> "es"
            "ger", "deu" -> "de"
            "por" -> "pt"
            "ita" -> "it"
            "jpn" -> "ja"
            "chi", "zho" -> "zh"
            "kor" -> "ko"
            "rus" -> "ru"
            "ara" -> "ar"
            "hin" -> "hi"
            "nld", "dut" -> "nl"
            "pol" -> "pl"
            "tur" -> "tr"
            else -> if (pref.length >= 2) pref.take(2) else pref
        }
    }
}
