package com.cursorforandroid.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shell's layout rule, window by window: which class each device is, what rail it opens with, and whether the
 * panel fits beside the content as a pane — the worked examples of the parity spec's responsive plan.
 */
class WindowPostureTest {

    @Test
    fun `width classes follow Material's breakpoints`() {
        assertThat(WidthClass.of(411)).isEqualTo(WidthClass.Compact)
        assertThat(WidthClass.of(409)).isEqualTo(WidthClass.Compact)
        assertThat(WidthClass.of(599)).isEqualTo(WidthClass.Compact)
        assertThat(WidthClass.of(600)).isEqualTo(WidthClass.Medium)
        assertThat(WidthClass.of(839)).isEqualTo(WidthClass.Medium)
        assertThat(WidthClass.of(840)).isEqualTo(WidthClass.Expanded)
        assertThat(WidthClass.of(1497)).isEqualTo(WidthClass.Expanded)
    }

    @Test
    fun `a wide window opens with the sidebar, a compact one with the drawer`() {
        assertThat(WindowPosture.defaultRail(WidthClass.Compact)).isEqualTo(RailState.Hidden)
        assertThat(WindowPosture.defaultRail(WidthClass.Medium)).isEqualTo(RailState.Expanded)
        assertThat(WindowPosture.defaultRail(WidthClass.Expanded)).isEqualTo(RailState.Expanded)
    }

    @Test
    fun `the rail's toggle moves between the sidebar and nothing`() {
        assertThat(RailState.Expanded.next).isEqualTo(RailState.Hidden)
        assertThat(RailState.Hidden.next).isEqualTo(RailState.Expanded)
        assertThat(RailState.parse("Hidden")).isEqualTo(RailState.Hidden)
        // The glyph column an earlier revision saved: not a state any more, so the class's default stands.
        assertThat(RailState.parse("IconOnly")).isNull()
        assertThat(RailState.parse("nope")).isNull()
    }

    @Test
    fun `a compact window is the drawer and the sheet whatever rail was saved`() {
        val phone = WindowPosture(WidthClass.Compact, 411, rail = RailState.Expanded)
        assertThat(phone.wide).isFalse()
        assertThat(phone.effectiveRail).isEqualTo(RailState.Hidden)
        assertThat(phone.railWidthDp).isEqualTo(0)
        assertThat(phone.panelAsPane).isFalse()
    }

    @Test
    fun `the panel is a pane only while the content keeps its floor beside rail and panel`() {
        // Fold inner display, portrait: the sidebar leaves 218 dp — a sheet; hidden, 496 — a pane.
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.Hidden).panelAsPane).isTrue()
        // A phone on its side.
        assertThat(WindowPosture(WidthClass.Expanded, 914, compactHeight = true, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 914, compactHeight = true, rail = RailState.Hidden).panelAsPane).isTrue()
        // A tablet on its side fits three columns with the sidebar showing; upright it needs the sidebar hidden.
        assertThat(WindowPosture(WidthClass.Expanded, 1497, rail = RailState.Expanded).panelAsPane).isTrue()
        assertThat(WindowPosture(WidthClass.Expanded, 936, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 936, rail = RailState.Hidden).panelAsPane).isTrue()
    }

    @Test
    fun `the pane's width is clamped to the window`() {
        assertThat(WindowPosture.clampPanelWidth(100, 1497)).isEqualTo(WindowPosture.PANEL_MIN_DP)
        assertThat(WindowPosture.clampPanelWidth(900, 1497)).isEqualTo(748)
        assertThat(WindowPosture.clampPanelWidth(900, 1855)).isEqualTo(WindowPosture.PANEL_MAX_DP)
        assertThat(WindowPosture.clampPanelWidth(500, 856)).isEqualTo(428)
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.Hidden, panelWidthDp = 700).paneWidthDp).isEqualTo(428)
        // A pane dragged wide enough to starve the content turns the panel into a sheet.
        assertThat(WindowPosture(WidthClass.Expanded, 700, rail = RailState.Hidden, panelWidthDp = 350).panelAsPane).isFalse()
    }

    @Test
    fun `the sidebar's width is clamped to its bounds and to the content's floor`() {
        assertThat(WindowPosture.clampSidebarWidth(100, 1497)).isEqualTo(WindowPosture.SIDEBAR_MIN_DP)
        assertThat(WindowPosture.clampSidebarWidth(900, 1497)).isEqualTo(WindowPosture.SIDEBAR_MAX_DP)
        assertThat(WindowPosture.clampSidebarWidth(278, 1497)).isEqualTo(278)
        // A medium window: the sidebar may not push the content under its floor (600 − 380 = 220).
        assertThat(WindowPosture.clampSidebarWidth(400, 600)).isEqualTo(220)
        assertThat(WindowPosture.clampSidebarWidth(400, 560)).isEqualTo(WindowPosture.SIDEBAR_MIN_DP)
        assertThat(WindowPosture.SIDEBAR_HIDE_BELOW_DP).isLessThan(WindowPosture.SIDEBAR_MIN_DP)
    }

    @Test
    fun `a wider sidebar takes the pane's room, and the panel becomes a sheet before they could overlap`() {
        // Tab landscape: a pane beside the sidebar at any width it can be dragged to.
        assertThat(WindowPosture(WidthClass.Expanded, 1497, rail = RailState.Expanded, sidebarWidthDp = 400).railWidthDp).isEqualTo(400)
        assertThat(WindowPosture(WidthClass.Expanded, 1497, rail = RailState.Expanded, sidebarWidthDp = 400).panelAsPane).isTrue()
        // 1200 dp: 1200 − 400 − 360 = 440 fits; at 1000 dp the same sidebar leaves 240 — a sheet — and 240 dp of sidebar gives the pane back.
        assertThat(WindowPosture(WidthClass.Expanded, 1200, rail = RailState.Expanded, sidebarWidthDp = 400).panelAsPane).isTrue()
        assertThat(WindowPosture(WidthClass.Expanded, 1000, rail = RailState.Expanded, sidebarWidthDp = 400).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 1000, rail = RailState.Expanded, sidebarWidthDp = 240).panelAsPane).isTrue()
        // Hidden, the width does not count; a compact window has no sidebar column whatever was dragged.
        assertThat(WindowPosture(WidthClass.Expanded, 1000, rail = RailState.Hidden, sidebarWidthDp = 400).railWidthDp).isEqualTo(0)
        assertThat(WindowPosture(WidthClass.Compact, 411, rail = RailState.Expanded, sidebarWidthDp = 400).railWidthDp).isEqualTo(0)
        // The default posture is the web's resting width.
        assertThat(WindowPosture(WidthClass.Expanded, 1497, rail = RailState.Expanded).railWidthDp).isEqualTo(278)
    }
}
