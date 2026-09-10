package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnoozeDurationTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `each pick is a duration from now, forever never expires`() {
        assertThat(SnoozeDuration.FiveMinutes.untilMillis(now)).isEqualTo(now + 5 * 60_000L)
        assertThat(SnoozeDuration.FifteenMinutes.untilMillis(now)).isEqualTo(now + 15 * 60_000L)
        assertThat(SnoozeDuration.ThirtyMinutes.untilMillis(now)).isEqualTo(now + 30 * 60_000L)
        assertThat(SnoozeDuration.OneHour.untilMillis(now)).isEqualTo(now + 60 * 60_000L)
        assertThat(SnoozeDuration.ThreeHours.untilMillis(now)).isEqualTo(now + 3 * 60 * 60_000L)
        assertThat(SnoozeDuration.SixHours.untilMillis(now)).isEqualTo(now + 6 * 60 * 60_000L)
        assertThat(SnoozeDuration.TwelveHours.untilMillis(now)).isEqualTo(now + 12 * 60 * 60_000L)
        assertThat(SnoozeDuration.OneDay.untilMillis(now)).isEqualTo(now + 24 * 60 * 60_000L)
        assertThat(SnoozeDuration.Forever.untilMillis(now)).isEqualTo(SnoozeDuration.FOREVER)
    }

    @Test
    fun `the picker is nine choices in three rows of three`() {
        assertThat(SnoozeDuration.rows.flatten()).containsExactlyElementsIn(SnoozeDuration.entries).inOrder()
        assertThat(SnoozeDuration.rows.map { it.size }).containsExactly(3, 3, 3)
        assertThat(SnoozeDuration.rows[0].map { it.label }).containsExactly("5m", "15m", "30m").inOrder()
        assertThat(SnoozeDuration.rows[1].map { it.label }).containsExactly("1h", "3h", "6h").inOrder()
        assertThat(SnoozeDuration.rows[2].map { it.label }).containsExactly("12h", "1d", "Forever").inOrder()
    }
}
