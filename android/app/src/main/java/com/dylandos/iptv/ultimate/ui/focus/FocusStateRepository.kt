package com.dylandos.iptv.ultimate.ui.focus

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the last focused index and category per screen so the UI can restore
 * the user's position when they navigate back. Survives screen navigation as long
 * as the ViewModel that holds it is alive (typically process lifetime via @Singleton).
 */
@Singleton
class FocusStateRepository @Inject constructor() {

    private val focusMap     = mutableMapOf<String, Int>()
    private val categoryMap  = mutableMapOf<String, Int>()

    fun saveFocus(screen: String, index: Int)       { focusMap[screen] = index }
    fun restoreFocus(screen: String): Int           = focusMap[screen] ?: 0

    fun saveCategory(screen: String, index: Int)    { categoryMap[screen] = index }
    fun restoreCategory(screen: String): Int        = categoryMap[screen] ?: 0

    companion object {
        const val SCREEN_MOVIES    = "movies"
        const val SCREEN_SERIES    = "series"
        const val SCREEN_LIVE      = "live_tv"
        const val SCREEN_GUIDE     = "guide"
        const val SCREEN_SEARCH    = "search"
        const val SCREEN_FAVORITES = "favorites"
    }
}
