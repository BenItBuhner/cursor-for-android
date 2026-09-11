package com.cursorforandroid.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {
    /**
     * Built once per locale rather than per call: a row's timestamp is formatted on every recomposition of the
     * agent list, the recents carousel and the widget, and compiling a pattern allocates a whole printer chain. The
     * locale is still read every time, so a runtime change is honoured on the first format after it.
     */
    @Volatile private var formatters: Formatters = Formatters(Locale.getDefault())

    private class Formatters(val locale: Locale) {
        val date: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
        val dateYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
    }

    private fun formatters(): Formatters {
        val locale = Locale.getDefault()
        val current = formatters
        if (current.locale == locale) return current
        return Formatters(locale).also { formatters = it }
    }

    private val dateFormatter: DateTimeFormatter get() = formatters().date
    private val dateYearFormatter: DateTimeFormatter get() = formatters().dateYear

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

    /** "Dec 6" this year, "Jan 3, 2027" otherwise — an absolute date for something that is going to happen. */
    fun date(epochMillis: Long, nowMillis: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val then = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return if (then.year == now.year) dateFormatter.format(then) else dateYearFormatter.format(then)
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
