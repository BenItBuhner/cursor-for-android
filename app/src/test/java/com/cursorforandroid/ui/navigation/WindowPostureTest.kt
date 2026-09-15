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
    fun `each class opens with its own rail`() {
        assertThat(WindowPosture.defaultRail(WidthClass.Compact)).isEqualTo(RailState.Hidden)
        assertThat(WindowPosture.defaultRail(WidthClass.Medium)).isEqualTo(RailState.IconOnly)
        assertThat(WindowPosture.defaultRail(WidthClass.Expanded)).isEqualTo(RailState.Expanded)
    }

    @Test
    fun `the rail's toggle cycles expanded, glyphs, hidden`() {
        assertThat(RailState.Expanded.next).isEqualTo(RailState.IconOnly)
        assertThat(RailState.IconOnly.next).isEqualTo(RailState.Hidden)
        assertThat(RailState.Hidden.next).isEqualTo(RailState.Expanded)
        assertThat(RailState.parse("IconOnly")).isEqualTo(RailState.IconOnly)
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
        // Fold inner display, portrait: the expanded rail leaves 218 dp — a sheet; the glyph rail leaves 440 — a pane.
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.IconOnly).panelAsPane).isTrue()
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.Hidden).panelAsPane).isTrue()
        // A phone on its side.
        assertThat(WindowPosture(WidthClass.Expanded, 914, compactHeight = true, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 914, compactHeight = true, rail = RailState.IconOnly).panelAsPane).isTrue()
        // A tablet on its side fits three columns with any rail; upright it needs the glyph rail.
        assertThat(WindowPosture(WidthClass.Expanded, 1497, rail = RailState.Expanded).panelAsPane).isTrue()
        assertThat(WindowPosture(WidthClass.Expanded, 936, rail = RailState.Expanded).panelAsPane).isFalse()
        assertThat(WindowPosture(WidthClass.Expanded, 936, rail = RailState.IconOnly).panelAsPane).isTrue()
    }

    @Test
    fun `the pane's width is clamped to the window`() {
        assertThat(WindowPosture.clampPanelWidth(100, 1497)).isEqualTo(WindowPosture.PANEL_MIN_DP)
        assertThat(WindowPosture.clampPanelWidth(900, 1497)).isEqualTo(WindowPosture.PANEL_MAX_DP)
        assertThat(WindowPosture.clampPanelWidth(500, 856)).isEqualTo(428)
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.IconOnly, panelWidthDp = 700).paneWidthDp).isEqualTo(428)
        // A pane dragged wide enough to starve the content turns the panel into a sheet.
        assertThat(WindowPosture(WidthClass.Expanded, 856, rail = RailState.IconOnly, panelWidthDp = 428).panelAsPane).isFalse()
    }
}
