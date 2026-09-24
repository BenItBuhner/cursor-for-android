package com.cursorforandroid.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.HeaderElement
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.domain.WidgetMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which arrangement a cell size gets, whether its header is drawn, and what the one-line arrangement says about its rows. */
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
    fun `every arrangement starts at its own size`() {
        assertThat(WidgetSizes.layoutFor(WidgetSizes.SMALL)).isEqualTo(WidgetLayout.Small)
        assertThat(WidgetSizes.layoutFor(WidgetSizes.MEDIUM)).isEqualTo(WidgetLayout.Medium)
        assertThat(WidgetSizes.layoutFor(WidgetSizes.LARGE)).isEqualTo(WidgetLayout.Large)
    }

    /** The cube, its gap and the corner button (or the line's end) come off the width first; a Project is 30dp. */
    @Test
    fun `the one-line Projects strip holds as many Projects as its width has room for`() {
        val corner = CornerButtonSpec(CornerAction.NewChat, CornerStyle.White, size = 28.dp, inset = 10.dp)
        assertThat(projectSlots(110.dp, corner, logo = true)).isEqualTo(1)
        assertThat(projectSlots(170.dp, corner, logo = true)).isEqualTo(3)
        assertThat(projectSlots(250.dp, corner, logo = true)).isEqualTo(5)
        assertThat(projectSlots(330.dp, corner, logo = true)).isEqualTo(8)
        // Without the corner button the line's end padding is all that is taken.
        assertThat(projectSlots(110.dp, null, logo = true)).isEqualTo(2)
        assertThat(projectSlots(20.dp, null, logo = true)).isEqualTo(0)
    }

    /** Without the cube the first icon starts where a row's glyph does, and the cube's room goes to Projects. */
    @Test
    fun `the strip without the logo has room for more Projects`() {
        val corner = CornerButtonSpec(CornerAction.NewChat, CornerStyle.White, size = 28.dp, inset = 10.dp)
        assertThat(projectSlots(110.dp, corner, logo = false)).isEqualTo(2)
        assertThat(projectSlots(170.dp, corner, logo = false)).isEqualTo(4)
        assertThat(projectSlots(250.dp, corner, logo = false)).isEqualTo(6)
        assertThat(projectSlots(330.dp, corner, logo = false)).isEqualTo(9)
    }

    @Test
    fun `the header hides itself under its height while it is set to, and only then`() {
        val defaults = ChatsWidgetSettings()
        // A 4x2 cell in portrait keeps it; the same cell in landscape gives its room to the rows.
        assertThat(WidgetSizes.showsHeader(DpSize(320.dp, 158.dp), defaults)).isTrue()
        assertThat(WidgetSizes.showsHeader(DpSize(560.dp, 118.dp), defaults)).isFalse()
        assertThat(WidgetSizes.showsHeader(DpSize(320.dp, WidgetSizes.HEADER_MIN_HEIGHT), defaults)).isTrue()
        assertThat(WidgetSizes.showsHeader(DpSize(560.dp, 118.dp), defaults.copy(headerAutoHide = false))).isTrue()
    }

    @Test
    fun `a header with nothing to draw is no header, whatever the height`() {
        val bare = ChatsWidgetSettings(header = emptySet(), headerAutoHide = false)
        assertThat(WidgetSizes.showsHeader(DpSize(320.dp, 330.dp), bare)).isFalse()
        // The only part left is the button the corner already is.
        val onlyNewChat = ChatsWidgetSettings(header = setOf(HeaderElement.NewChat), cornerAction = CornerAction.NewChat)
        assertThat(WidgetSizes.showsHeader(DpSize(320.dp, 330.dp), onlyNewChat)).isFalse()
        assertThat(WidgetSizes.showsHeader(DpSize(320.dp, 330.dp), onlyNewChat.copy(cornerAction = CornerAction.Search))).isTrue()
    }

    @Test
    fun `the one-line Projects detail counts the Projects at work and the Projects`() {
        val rows = ProjectsWidgetFixture.snapshot(ThemeMode.Dark, 1_788_900_000_000L).rows(WidgetMode.Projects, 1_788_900_000_000L)
        // The coordinator at work and the Project whose chats are.
        assertThat(smallDetail(rows, WidgetMode.Projects)).isEqualTo("2 running · 7 Projects")
        assertThat(smallDetail(rows.filter { !it.hasRunningDescendant && it.indicator != AgentIndicator.Running }.take(1), WidgetMode.Projects)).isEqualTo("1 Project")
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
