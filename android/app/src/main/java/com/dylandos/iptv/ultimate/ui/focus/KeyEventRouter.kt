package com.dylandos.iptv.ultimate.ui.focus

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Firestick 4K remote key codes.
 * DPAD_CENTER / OK = 23, BACK = 4, MENU = 82
 * Media keys: PLAY_PAUSE = 85, FF = 90, RW = 89 (alt: 87/86)
 */
object FirestickKeyMap {
    val DPAD_UP         = Key(19)
    val DPAD_DOWN       = Key(20)
    val DPAD_LEFT       = Key(21)
    val DPAD_RIGHT      = Key(22)
    val DPAD_CENTER     = Key(23)
    val BACK            = Key(4)
    val MENU            = Key(82)
    val PLAY_PAUSE      = Key(85)
    val FAST_FORWARD    = Key(90)
    val REWIND          = Key(89)
    val FAST_FORWARD2   = Key(87)
    val REWIND2         = Key(86)
    /** BUTTON_A / OK button on some Firestick firmware builds (keyCode 96). */
    val BUTTON_A        = Key(96)

    fun isConfirmKey(key: Key): Boolean =
        key == DPAD_CENTER || key == Key.Enter || key == Key.NumPadEnter || key == BUTTON_A

    fun isBackKey(key: Key): Boolean =
        key == BACK || key == Key.Escape

    fun isPlaybackKey(key: Key): Boolean =
        key == PLAY_PAUSE || key == FAST_FORWARD || key == REWIND ||
        key == FAST_FORWARD2 || key == REWIND2
}

fun KeyEvent.isRemoteConfirmKey(): Boolean {
    if (type != KeyEventType.KeyDown) return false
    if (FirestickKeyMap.isConfirmKey(key)) return true
    return when (nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_BUTTON_A -> true
        else -> false
    }
}

val LocalNavigationSidebarFocusRequester = compositionLocalOf<FocusRequester?> { null }

/**
 * Child focusables get first chance to consume LEFT. An unhandled edge press
 * falls through here and returns focus to the active sidebar destination.
 */
fun Modifier.focusNavigationSidebarOnLeft(): Modifier = composed {
    val sidebarFocusRequester = LocalNavigationSidebarFocusRequester.current
    onKeyEvent { event ->
        if (
            event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionLeft &&
            sidebarFocusRequester != null
        ) {
            runCatching { sidebarFocusRequester.requestFocus() }.isSuccess
        } else {
            false
        }
    }
}
