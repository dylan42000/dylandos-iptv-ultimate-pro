package com.dylandos.iptv.ultimate.data.network

data class ParsedMediaTitle(val title: String, val year: String?)

object MediaTitle {
    private val pipeTag = Regex("\\|[^|]*\\|")
    private val language = Regex("(?i)^(EN|FR|DE|ES|IT|PT|NL|US|UK)\\s*[-:]\\s*")
    private val quality = Regex("(?i)\\b(4k|uhd|fhd|hd|1080p|720p|hdts)\\b")
    private val releaseYear = Regex("(?:[\\s(\\[-])((?:19|20)\\d{2})[\\s)\\]]*$")
    private val punctuation = Regex("[^\\p{L}\\p{N}]")
    fun parse(value: String): ParsedMediaTitle {
        val clean = value.replace(pipeTag, " ").trim().replace(language, "")
            .replace(quality, "").trim(' ', '[', ']', '-')
        val year = releaseYear.find(clean)
        return ParsedMediaTitle((year?.let { clean.substring(0, it.range.first) } ?: clean).trim(' ', '-', '(', '['), year?.groupValues?.get(1))
    }
    fun normalized(value: String) = parse(value).title.lowercase(java.util.Locale.ROOT).replace(punctuation, "")
}
