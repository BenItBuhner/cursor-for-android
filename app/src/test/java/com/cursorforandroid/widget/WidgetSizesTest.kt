package com.cursorforandroid.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.domain.WidgetMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which arrangement a cell size gets, and what the one-line arrangement says about its rows. */
class WidgetSizesTest {

    @Test
    fun `a 2x1 cell is the one-line arrangement, 4x2 the rows, 4x4 the two-line rows`() {
        assertThat(WidgetSizes.layoutFor(DpSize(155.dp, 55.dp))).isEqualTo(WidgetLayout.Small)
        assertThat(WidgetSizes.layoutFor(DpSize(320.dp, 100.dp))).isEqualTo(WidgetLayout.Small)
        assertThat(WidgetSizes.layoutFor(DpSize(320.dp, 158.dp))).isEqualTo(WidgetLayout.Medium)
        assertThat(WidgetSizes.layoutFor(DpSize(320.dp, 268.dp))).isEqualTo(WidgetLayout.Large)
        assertThat(WidgetSizes.layoutFor(DpSize(180.dp, 330.dp))).isEqualTo(WidgetLayout.Medium)
    }

    @Test
    fun `every responsive size maps to its own arrangement`() {
        assertThat(WidgetSizes.layoutFor(WidgetSizes.SMALL)).isEqualTo(WidgetLayout.Small)
        assertThat(WidgetSizes.layoutFor(WidgetSizes.MEDIUM)).isEqualTo(WidgetLayout.Medium)
        assertThat(WidgetSizes.layoutFor(WidgetSizes.LARGE)).isEqualTo(WidgetLayout.Large)
    }

    @Test
    fun `the one-line detail counts what is running and unread, else the rows`() {
        val sample = WidgetData.sample(ThemeMode.Dark, 1_788_900_000_000L)
        val rows: List<AgentRow> = sample.rows(WidgetMode.Recent, 1_788_900_000_000L)
        assertThat(rows.count { it.indicator == AgentIndicator.Running }).isEqualTo(1)
        assertThat(smallDetail(rows, WidgetMode.Recent)).isEqualTo("1 running · 3 unread")
        assertThat(smallDetail(rows.filter { it.indicator == AgentIndicator.Read }, WidgetMode.Recent)).isEqualTo("2 chats")
        assertThat(smallDetail(rows.filter { it.indicator == AgentIndicator.Read }.take(1), WidgetMode.Pinned)).isEqualTo("1 chat")
        assertThat(smallDetail(emptyList(), WidgetMode.Recent)).isEqualTo("0 chats")
        // The Running list's name already says they run.
        assertThat(smallDetail(rows.filter { it.indicator == AgentIndicator.Running }, WidgetMode.Running)).isEqualTo("1 agent")
    }
}
