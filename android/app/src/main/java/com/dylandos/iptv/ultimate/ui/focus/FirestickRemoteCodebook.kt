package com.dylandos.iptv.ultimate.ui.focus

import android.view.KeyEvent

/**
 * Key codes and constants for Amazon Fire TV remotes, generic Android TV remotes,
 * and game controllers.
 */
object FirestickRemoteCodebook {
    // Standard D-pad & Actions
    const val KEYCODE_DPAD_UP = KeyEvent.KEYCODE_DPAD_UP
    const val KEYCODE_DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN
    const val KEYCODE_DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT
    const val KEYCODE_DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
    const val KEYCODE_DPAD_CENTER = KeyEvent.KEYCODE_DPAD_CENTER
    const val KEYCODE_ENTER = KeyEvent.KEYCODE_ENTER
    const val KEYCODE_BUTTON_A = KeyEvent.KEYCODE_BUTTON_A
    const val KEYCODE_BACK = KeyEvent.KEYCODE_BACK
    const val KEYCODE_MENU = KeyEvent.KEYCODE_MENU

    // Media Keys
    const val KEYCODE_MEDIA_PLAY_PAUSE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    const val KEYCODE_MEDIA_PLAY = KeyEvent.KEYCODE_MEDIA_PLAY
    const val KEYCODE_MEDIA_PAUSE = KeyEvent.KEYCODE_MEDIA_PAUSE
    const val KEYCODE_MEDIA_FAST_FORWARD = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
    const val KEYCODE_MEDIA_REWIND = KeyEvent.KEYCODE_MEDIA_REWIND
    const val KEYCODE_MEDIA_NEXT = KeyEvent.KEYCODE_MEDIA_NEXT
    const val KEYCODE_MEDIA_PREVIOUS = KeyEvent.KEYCODE_MEDIA_PREVIOUS
    const val KEYCODE_MEDIA_STOP = KeyEvent.KEYCODE_MEDIA_STOP
    const val KEYCODE_MEDIA_RECORD = KeyEvent.KEYCODE_MEDIA_RECORD

    // Extended Remote Keys
    const val KEYCODE_GUIDE = KeyEvent.KEYCODE_GUIDE
    const val KEYCODE_CAPTIONS = KeyEvent.KEYCODE_CAPTIONS
    const val KEYCODE_INFO = KeyEvent.KEYCODE_INFO
    const val KEYCODE_CHANNEL_UP = KeyEvent.KEYCODE_CHANNEL_UP
    const val KEYCODE_CHANNEL_DOWN = KeyEvent.KEYCODE_CHANNEL_DOWN
    const val KEYCODE_BOOKMARK = KeyEvent.KEYCODE_BOOKMARK
    const val KEYCODE_VOICE_ASSIST = KeyEvent.KEYCODE_VOICE_ASSIST

    // Color Keys (found on some extended TV remotes)
    const val KEYCODE_PROG_RED = KeyEvent.KEYCODE_PROG_RED
    const val KEYCODE_PROG_GREEN = KeyEvent.KEYCODE_PROG_GREEN
    const val KEYCODE_PROG_YELLOW = KeyEvent.KEYCODE_PROG_YELLOW
    const val KEYCODE_PROG_BLUE = KeyEvent.KEYCODE_PROG_BLUE
}
