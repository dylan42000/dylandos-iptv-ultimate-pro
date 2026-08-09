package com.dylandos.iptv.ultimate.ui.navigation

import android.os.SystemClock
import androidx.navigation.NavController
import timber.log.Timber

/** D-pad / Fire TV remotes sometimes deliver Back twice in one physical press. */
@Volatile
private var lastBackPopUptimeMs: Long = 0L
private const val MIN_BACK_POP_INTERVAL_MS = 450L

@Volatile
private var lastNavigateUptimeMs: Long = 0L

@Volatile
private var lastNavigateRoute: String = ""

private const val MIN_NAVIGATE_INTERVAL_MS = 350L

/** Avoids crashes / activity finish when the back stack is already at root. */
fun NavController.popBackStackSafe(): Boolean {
    return try {
        if (previousBackStackEntry != null) {
            popBackStack()
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Timber.w(e, "popBackStackSafe")
        false
    }
}

/**
 * [popBackStackSafe] with debounce so rapid duplicate Back events do not pop twice
 * (e.g. player closes then another screen).
 */
fun NavController.popBackStackSafeDebounced(): Boolean {
    val now = SystemClock.uptimeMillis()
    if (now - lastBackPopUptimeMs < MIN_BACK_POP_INTERVAL_MS) {
        Timber.d("popBackStack: ignored duplicate Back within ${MIN_BACK_POP_INTERVAL_MS}ms")
        return true
    }
    val ok = popBackStackSafe()
    if (ok) lastBackPopUptimeMs = now
    return ok
}

/**
 * Safe navigate wrapper for TV remotes that can emit duplicate enter presses.
 * Returns false if navigation was skipped or failed.
 */
fun NavController.navigateSafe(route: String): Boolean {
    return try {
        val now = SystemClock.uptimeMillis()
        val duplicateRoute = route == lastNavigateRoute
        val tooSoon = (now - lastNavigateUptimeMs) < MIN_NAVIGATE_INTERVAL_MS
        if (duplicateRoute && tooSoon) {
            Timber.d("navigateSafe: ignored duplicate route=$route")
            return false
        }
        navigate(route)
        lastNavigateRoute = route
        lastNavigateUptimeMs = now
        true
    } catch (e: Exception) {
        Timber.w(e, "navigateSafe($route)")
        false
    }
}
