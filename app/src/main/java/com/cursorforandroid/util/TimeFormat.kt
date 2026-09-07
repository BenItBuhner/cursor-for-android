package com.cursorforandroid.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {
    // Resolved on every call so a runtime locale change is honoured.
    private val dateFormatter get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /** Compact relative age for list rows: "now", "4m", "3h", "2d", "Sep 4". */
    fun relativeShort(epochMillis: Long, nowMillis: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = (nowMillis - epochMillis).coerceAtLeast(0)
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            days < 7 -> "${days}d"
            else -> dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
        }
    }

    /** "3m 5s", "45s", "1h 12m" — matches the "Worked 3m 5s" row. */
    fun duration(durationMs: Long?): String? {
        if (durationMs == null || durationMs < 0) return null
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** "0:07", "12:30", "1:05:09" — the length badge on a video poster. */
    fun clock(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0) + 500) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds) else "%d:%02d".format(Locale.US, minutes, seconds)
    }
}
