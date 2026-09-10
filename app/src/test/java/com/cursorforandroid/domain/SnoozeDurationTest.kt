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
    fun `the picker groups nearby times`() {
        assertThat(SnoozeDuration.groups.map { it.first }).containsExactly("Soon", "Later", "Longer").inOrder()
        assertThat(SnoozeDuration.groups.flatMap { it.second }).containsExactlyElementsIn(SnoozeDuration.entries).inOrder()
        assertThat(SnoozeDuration.groups[0].second.map { it.title }).containsExactly("5 minutes", "15 minutes", "30 minutes").inOrder()
        assertThat(SnoozeDuration.groups[1].second.map { it.title }).containsExactly("1 hour", "3 hours", "6 hours", "12 hours").inOrder()
        assertThat(SnoozeDuration.groups[2].second.map { it.title }).containsExactly("1 day", "Forever").inOrder()
    }
}
