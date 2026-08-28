package com.dylandos.iptv.ultimate.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Centralized time formatting engine for DYLANDOS IPTV ULTIMATE.
 * Ensures consistent 12h / 24h formatting across Live TV, Guide, Player, and DVR.
 */
object TimeFormatter {

    /**
     * Display-zone override for Fire TV builds whose firmware reports UTC even though the
     * device is physically configured elsewhere. Programme timestamps remain UTC epoch
     * instants; this controls rendering only and must never be used while parsing EPG data.
     * "device" delegates to Android's normal default timezone.
     */
    @Volatile private var displayTimeZoneId: String = "device"

    fun setDisplayTimeZone(id: String?) {
        displayTimeZoneId = id?.trim()?.takeIf { it.isNotEmpty() } ?: "device"
    }

    fun displayTimeZone(): TimeZone {
        if (displayTimeZoneId.equals("device", ignoreCase = true)) return TimeZone.getDefault()
        val candidate = TimeZone.getTimeZone(displayTimeZoneId)
        return if (candidate.id == "GMT" &&
            !displayTimeZoneId.equals("GMT", ignoreCase = true) &&
            !displayTimeZoneId.equals("UTC", ignoreCase = true)
        ) TimeZone.getDefault() else candidate
    }

    /** Formats a timestamp into a short time string (e.g., "8:30 PM" or "20:30"). */
    fun formatShortTime(
        epochMillis: Long,
        timeFormat: String = "12h",
        timeZone: TimeZone = displayTimeZone()
    ): String {
        val pattern = if (timeFormat.equals("24h", ignoreCase = true)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(epochMillis))
    }

    /** Formats start and end timestamps into a clean range string (e.g. "8:00 PM – 9:00 PM"). */
    fun formatTimeRange(
        startMs: Long,
        endMs: Long,
        timeFormat: String = "12h",
        timeZone: TimeZone = displayTimeZone()
    ): String {
        val pattern = if (timeFormat.equals("24h", ignoreCase = true)) "HH:mm" else "h:mm a"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
        return "${sdf.format(Date(startMs))} – ${sdf.format(Date(endMs))}"
    }

    /** Formats a window header label (e.g. "Thu, Aug 14  8:00 PM" or "Thu, Aug 14  20:00"). */
    fun formatWindowLabel(
        windowStartMs: Long,
        timeFormat: String = "12h",
        timeZone: TimeZone = displayTimeZone()
    ): String {
        val pattern = if (timeFormat.equals("24h", ignoreCase = true)) {
            "EEE, MMM d  HH:mm"
        } else {
            "EEE, MMM d  h:mm a"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(windowStartMs))
    }

    /** Formats a full date and time for DVR or details (e.g., "Aug 14, 2026  8:30 PM"). */
    fun formatFullDateTime(
        epochMillis: Long,
        timeFormat: String = "12h",
        timeZone: TimeZone = displayTimeZone()
    ): String {
        val pattern = if (timeFormat.equals("24h", ignoreCase = true)) {
            "MMM d, yyyy  HH:mm"
        } else {
            "MMM d, yyyy  h:mm a"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(epochMillis))
    }
}

/**
 * Composable helper that emits the current wall-clock time and ticks every second or 30 seconds.
 */
@Composable
fun rememberLiveClock(timeFormat: String = "12h"): String {
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1000L)
        }
    }

    return remember(currentTime, timeFormat) {
        TimeFormatter.formatShortTime(currentTime, timeFormat)
    }
}
