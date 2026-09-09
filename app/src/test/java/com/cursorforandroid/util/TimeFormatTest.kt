package com.cursorforandroid.util

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TimeFormatTest {

    private val utc = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 9, 4, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val original = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
        // Leave the cache agreeing with the locale the next test starts from.
        TimeFormat.relativeShort(now - 30 * DAY, now, utc)
    }

    private fun formatters(): Any {
        val field = TimeFormat::class.java.getDeclaredField("formatters")
        field.isAccessible = true
        return field.get(TimeFormat)!!
    }

    @Test
    fun `the same locale reuses one formatter instead of compiling a pattern per row`() {
        Locale.setDefault(Locale.US)
        TimeFormat.relativeShort(now - 30 * DAY, now, utc)
        val first = formatters()
        repeat(50) { TimeFormat.relativeShort(now - 30 * DAY, now, utc) }
        TimeFormat.date(now - 400 * DAY, now, utc)
        assertThat(formatters()).isSameInstanceAs(first)
    }

    @Test
    fun `a locale change is still honoured on the next call`() {
        Locale.setDefault(Locale.US)
        assertThat(TimeFormat.relativeShort(now - 30 * DAY, now, utc)).isEqualTo("Aug 5")
        val english = formatters()

        Locale.setDefault(Locale.FRANCE)
        val french = TimeFormat.relativeShort(now - 30 * DAY, now, utc)
        assertThat(french).isNotEqualTo("Aug 5")
        assertThat(formatters()).isNotSameInstanceAs(english)
    }

    @Test
    fun `relative ages read the way the rows show them`() {
        Locale.setDefault(Locale.US)
        assertThat(TimeFormat.relativeShort(now - 59_000, now, utc)).isEqualTo("now")
        assertThat(TimeFormat.relativeShort(now - 60_000, now, utc)).isEqualTo("1m")
        assertThat(TimeFormat.relativeShort(now - 59 * MINUTE, now, utc)).isEqualTo("59m")
        assertThat(TimeFormat.relativeShort(now - HOUR, now, utc)).isEqualTo("1h")
        assertThat(TimeFormat.relativeShort(now - 23 * HOUR, now, utc)).isEqualTo("23h")
        assertThat(TimeFormat.relativeShort(now - DAY, now, utc)).isEqualTo("1d")
        assertThat(TimeFormat.relativeShort(now - 6 * DAY, now, utc)).isEqualTo("6d")
        assertThat(TimeFormat.relativeShort(now - 7 * DAY, now, utc)).isEqualTo("Aug 28")
    }

    @Test
    fun `a timestamp from the future reads as now rather than a negative age`() {
        assertThat(TimeFormat.relativeShort(now + HOUR, now, utc)).isEqualTo("now")
        assertThat(TimeFormat.clock(-5_000)).isEqualTo("0:00")
    }

    @Test
    fun `an absolute date carries the year only when it is not this one`() {
        Locale.setDefault(Locale.US)
        assertThat(TimeFormat.date(now + 93 * DAY, now, utc)).isEqualTo("Dec 6")
        assertThat(TimeFormat.date(now + 486 * DAY, now, utc)).isEqualTo("Jan 3, 2028")
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
