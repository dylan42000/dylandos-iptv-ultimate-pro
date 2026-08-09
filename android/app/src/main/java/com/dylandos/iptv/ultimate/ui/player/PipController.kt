package com.dylandos.iptv.ultimate.ui.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global singleton that lets PlayerScreen signal MainActivity to enter PiP mode
 * without creating a hard Activity dependency inside the Compose tree.
 *
 * Usage:
 *   PlayerScreen  → PipController.activate()   on composition
 *   PlayerScreen  → PipController.deactivate() on dispose
 *   MainActivity  → read shouldEnterPip in onUserLeaveHint()
 */
object PipController {
    private val _shouldEnterPip = MutableStateFlow(false)
    val shouldEnterPip: StateFlow<Boolean> = _shouldEnterPip.asStateFlow()

    fun activate()   { _shouldEnterPip.value = true  }
    fun deactivate() { _shouldEnterPip.value = false }
}
