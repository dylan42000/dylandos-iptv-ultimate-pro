package com.dylandos.iptv.ultimate.ui.focus

import timber.log.Timber

/**
 * Diagnostic logger for remote control key events to help trace key mappings.
 */
object KeyMappingLog {
    private const val MAX_ENTRIES = 50
    private val history = ArrayDeque<String>(MAX_ENTRIES)

    @Synchronized
    fun logKeyEvent(keyCode: Int, actionName: String, handled: Boolean) {
        val entry = "Key=$keyCode ($actionName) handled=$handled at ${System.currentTimeMillis()}"
        if (history.size >= MAX_ENTRIES) {
            history.removeFirst()
        }
        history.addLast(entry)
        Timber.d("KeyMappingLog: $entry")
    }

    @Synchronized
    fun getRecentHistory(): List<String> = history.toList()
}
