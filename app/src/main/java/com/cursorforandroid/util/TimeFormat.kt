package com.cursorforandroid.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object TimeFormat {
    // Resolved on every call so a runtime locale change is honoured.
    private val timeFormatter get() = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateFormatter get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val dateYearFormatter get() = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

    /** "Yesterday at 12:14 AM" — the header above a user message in the conversation view. */
    fun conversationStamp(epochMillis: Long, nowMillis: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        val time = timeFormatter.format(then)
        return when {
            days <= 0 -> "Today at $time"
            days == 1L -> "Yesterday at $time"
            then.year == now.year -> "${dateFormatter.format(then)} at $time"
            else -> "${dateYearFormatter.format(then)} at $time"
        }
    }

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
}
