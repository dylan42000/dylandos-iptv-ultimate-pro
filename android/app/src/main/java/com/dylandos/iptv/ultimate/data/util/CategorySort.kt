package com.dylandos.iptv.ultimate.data.util

import com.dylandos.iptv.ultimate.data.model.XtreamCategory

/**
 * US / English / North-American priority keywords.
 *
 * If a category name contains any of these tokens (case-insensitive) it is
 * promoted to the front of the list so American / English content is always
 * the first thing users see in Live TV, Movies, Series, and the Guide.
 *
 * "NA" is accepted only as a complete token, so it does not promote names
 * such as "NBA" or "NAT GEO".
 */
private val US_EN_TOKENS = listOf(
    "US",
    "USA",
    "U.S.",
    "UNITED STATES",
    "AMERICA",
    "AMERICAN",
    "EN",
    "ENG",
    "ENGLISH",
    "N.A.",
    "NA",
    "NORTH AMERICA",
    "NORTH AMERICAN"
)

/** Category names that START with these are also promoted. */
private val US_EN_TOKEN_PATTERN = Regex("(^|[^A-Z0-9])(${US_EN_TOKENS.joinToString("|") { Regex.escape(it) }})([^A-Z0-9]|$)")

/** Returns `true` when a category should be sorted to the top. */
fun XtreamCategory.isUsEnPriority(): Boolean {
    return isUsEnPriorityLabel(categoryName)
}

/** Same lightweight priority rule for channel names, including Replay channel lists. */
fun isUsEnPriorityLabel(label: String): Boolean =
    US_EN_TOKEN_PATTERN.containsMatchIn(label.uppercase())

/**
 * Sorts a category list so US/English/NA entries come first (preserving
 * relative order within each group), followed by everything else.
 *
 * @param hiddenCategoryIds  set of category_id strings to remove entirely
 * @param usEnFirst          when `true` the US/EN promotion is applied
 */
fun sortCategories(
    categories: List<XtreamCategory>,
    hiddenCategoryIds: Set<String> = emptySet(),
    usEnFirst: Boolean = true
): List<XtreamCategory> {
    val visible = if (hiddenCategoryIds.isEmpty()) categories
                  else categories.filter { it.categoryId !in hiddenCategoryIds }
    return if (!usEnFirst) visible
    else {
        val priority = visible.filter { it.isUsEnPriority() }
        val rest     = visible.filter { !it.isUsEnPriority() }
        priority + rest
    }
}
