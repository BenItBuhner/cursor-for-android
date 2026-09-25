package com.cursorforandroid.ui.panel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaneWidthsTest {

    private fun widths(window: Dp, railExpanded: Boolean = true, rail: Dp = CursorDimens.sidebarWidth, panelOpen: Boolean = true, fraction: Float? = null) =
        PaneWidths.of(window, railExpanded, rail, panelOpen, fraction)

    private fun PaneWidths.chat(window: Dp): Dp = window - (if (railShown) rail else 0.dp) - (if (panelShown) panel else 0.dp)

    @Test
    fun `until it is dragged the panel shares what the rail leaves of a tablet on its side half and half with the chat`() {
        val w = widths(1280.dp)
        assertThat(w.splits).isTrue()
        assertThat(w.railShown).isTrue()
        assertThat(w.rail).isEqualTo(CursorDimens.sidebarWidth)
        assertThat(w.panelShown).isTrue()
        assertThat(w.panel).isEqualTo(501.dp)
        assertThat(w.chat(1280.dp)).isEqualTo(501.dp)
        // The rail put away, the two share the whole window...
        val collapsed = widths(1280.dp, railExpanded = false)
        assertThat(collapsed.splits).isTrue()
        assertThat(collapsed.panel).isEqualTo(640.dp)
        assertThat(collapsed.chat(1280.dp)).isEqualTo(640.dp)
        // ...and the rail dragged wider, what is left of it.
        val wideRail = widths(1280.dp, rail = RailMaxWidth)
        assertThat(wideRail.rail).isEqualTo(RailMaxWidth)
        assertThat(wideRail.panel).isEqualTo(440.dp)
        assertThat(wideRail.chat(1280.dp)).isEqualTo(440.dp)
    }

    @Test
    fun `a window with no room for the rail beside half and half puts the rail out of the way and shares the rest`() {
        // A foldable's inner screen.
        val foldable = widths(840.dp)
        assertThat(foldable.splits).isFalse()
        assertThat(foldable.railShown).isFalse()
        assertThat(foldable.railFits).isFalse()
        assertThat(foldable.panel).isEqualTo(420.dp)
        assertThat(foldable.chat(840.dp)).isEqualTo(420.dp)
        // A tablet held upright.
        val upright = widths(800.dp)
        assertThat(upright.pinnable).isTrue()
        assertThat(upright.railShown).isFalse()
        assertThat(upright.panel).isEqualTo(400.dp)
        assertThat(upright.chat(800.dp)).isEqualTo(400.dp)
    }

    @Test
    fun `half and half keeps the rail from the width where its narrowest fits beside half the window and the chat`() {
        val first = widths(1040.dp)
        assertThat(first.splits).isTrue()
        assertThat(first.railShown).isTrue()
        assertThat(first.panel).isEqualTo(381.dp)
        assertThat(first.chat(1040.dp)).isEqualTo(381.dp)
        // As wide as the rail can be dragged, the chat and the panel are still their own.
        val wideRail = widths(1040.dp, rail = RailMaxWidth)
        assertThat(wideRail.rail).isEqualTo(RailMaxWidth)
        assertThat(wideRail.panel).isEqualTo(ChatMinWidth)
        assertThat(wideRail.chat(1040.dp)).isEqualTo(ChatMinWidth)
        val short = widths(1039.dp)
        assertThat(short.splits).isFalse()
        assertThat(short.railShown).isFalse()
        assertThat(short.panel).isEqualTo(519.5.dp)
        assertThat(short.chat(1039.dp)).isEqualTo(519.5.dp)
    }

    @Test
    fun `dragged, the panel keeps its share of the window from one window to another`() {
        val tablet = widths(1280.dp, fraction = 0.375f)
        assertThat(tablet.splits).isFalse()
        assertThat(tablet.panel).isEqualTo(480.dp)
        assertThat(tablet.railShown).isTrue()
        assertThat(tablet.chat(1280.dp)).isEqualTo(522.dp)
        assertThat(widths(1600.dp, fraction = 0.375f).panel).isEqualTo(600.dp)
        assertThat(widths(1024.dp, fraction = 0.375f).panel).isEqualTo(384.dp)
        // No longer half and half, it stays as wide as the rail comes and goes.
        val collapsed = widths(1280.dp, railExpanded = false, fraction = 0.375f)
        assertThat(collapsed.panel).isEqualTo(480.dp)
        assertThat(collapsed.chat(1280.dp)).isEqualTo(800.dp)
    }

    @Test
    fun `the panel is dragged between its minimum and all the chat leaves at its narrowest`() {
        assertThat(widths(1280.dp, fraction = 0.0625f).panel).isEqualTo(PinnedPanelMinWidth)
        assertThat(widths(1280.dp).panelMax).isEqualTo(960.dp)
        assertThat(widths(840.dp).panelMax).isEqualTo(520.dp)
        assertThat(widths(800.dp).panelMax).isEqualTo(480.dp)
        val widest = widths(1280.dp, fraction = 1f)
        assertThat(widest.panel).isEqualTo(960.dp)
        assertThat(widest.railShown).isFalse()
        assertThat(widest.chat(1280.dp)).isEqualTo(ChatMinWidth)
        // Past half a tablet, where it used to stop.
        assertThat(widths(1280.dp, fraction = 0.625f).panel).isEqualTo(800.dp)
    }

    @Test
    fun `the rail comes in narrower before it gives way, and gives way before the panel does`() {
        val narrower = widths(1280.dp, fraction = 0.546875f)
        assertThat(narrower.panel).isEqualTo(700.dp)
        assertThat(narrower.railShown).isTrue()
        assertThat(narrower.rail).isEqualTo(260.dp)
        assertThat(narrower.chat(1280.dp)).isEqualTo(ChatMinWidth)
        // Wider, until the rail is down to its minimum...
        val tight = widths(1280.dp, fraction = 0.59375f)
        assertThat(tight.panel).isEqualTo(760.dp)
        assertThat(tight.railShown).isTrue()
        assertThat(tight.rail).isEqualTo(RailMinWidth)
        // ...and past that it is out of the way, rather than the chat going under its minimum.
        val past = widths(1280.dp, fraction = 0.625f)
        assertThat(past.railShown).isFalse()
        assertThat(past.railFits).isFalse()
        assertThat(past.panel).isEqualTo(800.dp)
        assertThat(past.chat(1280.dp)).isEqualTo(480.dp)
    }

    @Test
    fun `dragged down on a foldable the panel leaves the rail room beside the chat again`() {
        val w = widths(840.dp, fraction = 0.25f)
        assertThat(w.panel).isEqualTo(PinnedPanelMinWidth)
        assertThat(w.railShown).isTrue()
        assertThat(w.rail).isEqualTo(240.dp)
        assertThat(w.chat(840.dp)).isEqualTo(ChatMinWidth)
    }

    @Test
    fun `with the rail out of the way the panel comes in narrower, never the chat`() {
        val w = widths(840.dp, fraction = 0.875f)
        assertThat(w.railShown).isFalse()
        assertThat(w.panel).isEqualTo(520.dp)
        assertThat(w.chat(840.dp)).isEqualTo(ChatMinWidth)
    }

    @Test
    fun `a window with no room for the panel beside the chat at their narrowest keeps it a sheet`() {
        val w = widths(599.dp)
        assertThat(w.pinnable).isFalse()
        assertThat(w.panelShown).isFalse()
        assertThat(w.splits).isFalse()
        assertThat(w.railShown).isTrue()
        val first = widths(600.dp)
        assertThat(first.pinnable).isTrue()
        assertThat(first.panel).isEqualTo(PinnedPanelMinWidth)
        assertThat(first.chat(600.dp)).isEqualTo(ChatMinWidth)
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
    fun `a rail asked wider than the dragged panel leaves room for comes in to fit`() {
        val w = widths(1280.dp, rail = RailMaxWidth, fraction = 0.5f)
        assertThat(w.splits).isFalse()
        assertThat(w.panel).isEqualTo(640.dp)
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
