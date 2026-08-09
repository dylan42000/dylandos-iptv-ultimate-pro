package com.dylandos.iptv.ultimate.data.util

import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C6: unit tests for US/EN-first category sorting (Live TV / Movies / Series rails).
 */
class CategorySortTest {

    private fun cat(id: String, name: String) = XtreamCategory(
        categoryId = id,
        categoryName = name,
        parentId = 0
    )

    private val spanish = cat("es", "Español")
    private val usa     = cat("us", "USA Movies")
    private val english = cat("en", "English")
    private val german  = cat("de", "Deutsch")

    @Test
    fun `us and english categories are promoted first`() {
        val sorted = sortCategories(listOf(spanish, usa, german, english), usEnFirst = true)
        assertEquals(listOf("us", "en", "es", "de"), sorted.map { it.categoryId })
    }

    @Test
    fun `usEnFirst false preserves input order`() {
        val sorted = sortCategories(listOf(spanish, usa, german, english), usEnFirst = false)
        assertEquals(listOf("es", "us", "de", "en"), sorted.map { it.categoryId })
    }

    @Test
    fun `hidden categories are removed`() {
        val sorted = sortCategories(
            listOf(spanish, usa, english),
            hiddenCategoryIds = setOf("es"),
            usEnFirst = true
        )
        assertEquals(listOf("us", "en"), sorted.map { it.categoryId })
    }

    @Test
    fun `bare NA does not match N A tokens`() {
        val na = cat("na", "NBA Zone")   // "NA" alone must not match "N.A."
        val sorted = sortCategories(listOf(na, english), usEnFirst = true)
        assertEquals(listOf("en", "na"), sorted.map { it.categoryId })
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<XtreamCategory>(), sortCategories(emptyList()))
    }
}
