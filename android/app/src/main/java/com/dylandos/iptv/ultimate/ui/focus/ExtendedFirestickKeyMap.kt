package com.dylandos.iptv.ultimate.ui.focus

import android.view.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * Extended remote key matching for Fire TV, Leanback, and Android TV remotes.
 */
object ExtendedFirestickKeyMap {

    fun isGuideKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_GUIDE ||
               code == KeyEvent.KEYCODE_TV_DATA_SERVICE ||
               code == KeyEvent.KEYCODE_PROG_GREEN
    }

    fun isSubtitleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_CAPTIONS ||
               code == KeyEvent.KEYCODE_PROG_YELLOW
    }

    fun isPlayPauseKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
               code == KeyEvent.KEYCODE_MEDIA_PLAY ||
               code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
               code == KeyEvent.KEYCODE_HEADSETHOOK
    }

    fun isFastForwardKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
               code == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ||
               code == KeyEvent.KEYCODE_MEDIA_NEXT
    }

    fun isRewindKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_MEDIA_REWIND ||
               code == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ||
               code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }

    fun isChannelUpKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_CHANNEL_UP ||
               code == KeyEvent.KEYCODE_PAGE_UP
    }

    fun isChannelDownKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_CHANNEL_DOWN ||
               code == KeyEvent.KEYCODE_PAGE_DOWN
    }

    fun isBookmarkKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_BOOKMARK ||
               code == KeyEvent.KEYCODE_PROG_RED
    }

    fun isInfoKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val code = event.nativeKeyEvent.keyCode
        return code == KeyEvent.KEYCODE_INFO ||
               code == KeyEvent.KEYCODE_PROG_BLUE
    }

    fun isMenuKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        return event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU
    }
}
