package com.cursorforandroid.ui.panel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaneWidthsTest {

    private fun widths(window: Dp, railExpanded: Boolean = true, rail: Dp = CursorDimens.sidebarWidth, panelOpen: Boolean = true, panel: Dp = PinnedPanelDefaultWidth) =
        PaneWidths.of(window, railExpanded, rail, panelOpen, panel)

    private fun PaneWidths.chat(window: Dp): Dp = window - (if (railShown) rail else 0.dp) - (if (panelShown) panel else 0.dp)

    @Test
    fun `a tablet on its side has the rail, the chat and the panel side by side at their own widths`() {
        val w = widths(1280.dp)
        assertThat(w.railShown).isTrue()
        assertThat(w.rail).isEqualTo(CursorDimens.sidebarWidth)
        assertThat(w.panelShown).isTrue()
        assertThat(w.panel).isEqualTo(400.dp)
        assertThat(w.chat(1280.dp)).isEqualTo(602.dp)
    }

    @Test
    fun `the rail comes in narrower before it gives way, and gives way before the panel does`() {
        // A foldable's inner screen: the panel at its narrowest leaves the rail 240dp beside a chat at its minimum.
        val narrow = widths(840.dp, panel = PinnedPanelMinWidth)
        assertThat(narrow.railShown).isTrue()
        assertThat(narrow.rail).isEqualTo(240.dp)
        assertThat(narrow.chat(840.dp)).isEqualTo(ChatMinWidth)
        // Wider, until the rail is down to its minimum...
        val tight = widths(840.dp, panel = 320.dp)
        assertThat(tight.railShown).isTrue()
        assertThat(tight.rail).isEqualTo(RailMinWidth)
        // ...and past that it is out of the way, rather than the chat going under its minimum.
        val past = widths(840.dp, panel = 321.dp)
        assertThat(past.railShown).isFalse()
        assertThat(past.railFits).isFalse()
        assertThat(past.panel).isEqualTo(321.dp)
        assertThat(past.chat(840.dp)).isEqualTo(519.dp)
    }

    @Test
    fun `with the rail out of the way the panel comes in narrower, never the chat`() {
        val w = widths(840.dp, panel = 900.dp)
        assertThat(w.railShown).isFalse()
        assertThat(w.panel).isEqualTo(520.dp)
        assertThat(w.panelMax).isEqualTo(520.dp)
        assertThat(w.chat(840.dp)).isEqualTo(ChatMinWidth)
    }

    @Test
    fun `a tablet held upright pins the panel with the rail out of the way at the default width`() {
        val w = widths(800.dp)
        assertThat(w.pinnable).isTrue()
        assertThat(w.panelShown).isTrue()
        assertThat(w.railShown).isFalse()
        assertThat(w.chat(800.dp)).isEqualTo(400.dp)
        // At its narrowest it leaves the rail exactly its minimum.
        val narrow = widths(800.dp, panel = PinnedPanelMinWidth)
        assertThat(narrow.railShown).isTrue()
        assertThat(narrow.rail).isEqualTo(RailMinWidth)
    }

    @Test
    fun `the panel is dragged between its minimum and the lesser of its maximum and what leaves the chat its own`() {
        assertThat(widths(1280.dp, panel = 100.dp).panel).isEqualTo(PinnedPanelMinWidth)
        assertThat(widths(1280.dp, panel = 900.dp).panel).isEqualTo(PinnedPanelMaxWidth)
        assertThat(widths(1280.dp).panelMax).isEqualTo(PinnedPanelMaxWidth)
        assertThat(widths(800.dp).panelMax).isEqualTo(480.dp)
        // At its widest on a tablet on its side there is still room for the rail as it was.
        val widest = widths(1280.dp, panel = PinnedPanelMaxWidth)
        assertThat(widest.railShown).isTrue()
        assertThat(widest.rail).isEqualTo(CursorDimens.sidebarWidth)
        assertThat(widest.railMax).isEqualTo(320.dp)
    }

    @Test
    fun `a window with no room for the panel beside the chat at their narrowest keeps it a sheet`() {
        val w = widths(599.dp)
        assertThat(w.pinnable).isFalse()
        assertThat(w.panelShown).isFalse()
        assertThat(w.railShown).isTrue()
        assertThat(widths(600.dp).pinnable).isTrue()
    }

    @Test
    fun `with the panel shut the rail keeps the width it has always had on the narrowest wide windows`() {
        assertThat(widths(600.dp, panelOpen = false).rail).isEqualTo(CursorDimens.sidebarWidth)
        assertThat(widths(411.dp, panelOpen = false).rail).isEqualTo(CursorDimens.sidebarWidth)
        assertThat(widths(600.dp, panelOpen = false).railMax).isEqualTo(280.dp)
        // Asked wider, it goes as far as its maximum where the chat keeps its own.
        val wide = widths(1280.dp, rail = 520.dp, panelOpen = false)
        assertThat(wide.rail).isEqualTo(RailMaxWidth)
        assertThat(wide.railFits).isTrue()
        assertThat(widths(1280.dp, rail = 100.dp, panelOpen = false).rail).isEqualTo(RailMinWidth)
    }

    @Test
    fun `a rail asked wider than the pinned panel leaves room for comes in to fit`() {
        val w = widths(1280.dp, rail = RailMaxWidth, panel = PinnedPanelMaxWidth)
        assertThat(w.railShown).isTrue()
        assertThat(w.rail).isEqualTo(320.dp)
        assertThat(w.chat(1280.dp)).isEqualTo(ChatMinWidth)
    }

    @Test
    fun `a collapsed rail is not shown but still says whether it would fit`() {
        val roomy = widths(1280.dp, railExpanded = false)
        assertThat(roomy.railShown).isFalse()
        assertThat(roomy.railFits).isTrue()
        val tight = widths(840.dp, railExpanded = false)
        assertThat(tight.railShown).isFalse()
        assertThat(tight.railFits).isFalse()
    }

    @Test
    fun `the size class turns at 840dp`() {
        assertThat(PaneWidthClass.of(600.dp)).isEqualTo(PaneWidthClass.Medium)
        assertThat(PaneWidthClass.of(839.dp)).isEqualTo(PaneWidthClass.Medium)
        assertThat(PaneWidthClass.of(840.dp)).isEqualTo(PaneWidthClass.Expanded)
        assertThat(PaneWidthClass.of(1280.dp)).isEqualTo(PaneWidthClass.Expanded)
    }
}
