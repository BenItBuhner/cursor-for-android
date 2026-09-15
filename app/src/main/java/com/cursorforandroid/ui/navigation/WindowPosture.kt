package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the left rail is on screen: the full sidebar, or nothing — the two states cursor.com's own toggle moves
 * between (the sidebar's top-left button hides it; the content header's button brings it back).
 */
enum class RailState {
    Expanded,
    Hidden,
    ;

    /** The other state: what the toggle moves to. */
    val next: RailState get() = when (this) { Expanded -> Hidden; Hidden -> Expanded }

    companion object {
        fun parse(name: String?): RailState? = entries.firstOrNull { it.name == name }
    }
}

/** Material's window width classes, the three the app lays out for. */
enum class WidthClass {
    /** Under 600 dp: a phone in portrait, a foldable's cover display. The sidebar is a drawer, the panel a sheet. */
    Compact,
    /** 600–839 dp: a small tablet in portrait. A column for the rail. */
    Medium,
    /** 840 dp and up: a phone on its side, a foldable's inner display, a tablet. The rail's column, expanded by default. */
    Expanded,
    ;

    companion object {
        fun of(windowWidthDp: Int): WidthClass = when {
            windowWidthDp < MEDIUM_MIN_DP -> Compact
            windowWidthDp < EXPANDED_MIN_DP -> Medium
            else -> Expanded
        }

        const val MEDIUM_MIN_DP = 600
        const val EXPANDED_MIN_DP = 840
    }
}

/**
 * The shell's layout decision for one window: its width class, the rail's state, the panel's width, and from those
 * whether the right panel fits beside the content as a pane or goes over it as a sheet. Computed by the shell from
 * the window size class and what the reader chose, read by the screens through [LocalWindowPosture].
 *
 * The rule for the pane: the content column must keep [CONTENT_MIN_DP] beside the rail and the panel; where it
 * cannot — a foldable's inner display with the sidebar showing, a phone on its side — the panel is a sheet, as on a
 * phone. A compact window is always the drawer-and-sheet layout, whatever the rail state saved for wider ones.
 */
data class WindowPosture(
    val widthClass: WidthClass,
    val windowWidthDp: Int,
    /** Under 480 dp tall (a phone on its side): rows above the composer keep to one line, the header stays a single row. */
    val compactHeight: Boolean = false,
    val rail: RailState = RailState.Hidden,
    val panelWidthDp: Int = PANEL_DEFAULT_DP,
) {
    /** A column for the rail rather than the drawer. */
    val wide: Boolean get() = widthClass != WidthClass.Compact

    /** The rail's state as laid out: a compact window has no rail (the drawer stands in), whatever was saved. */
    val effectiveRail: RailState get() = if (wide) rail else RailState.Hidden

    val railWidthDp: Int
        get() = when (effectiveRail) {
            RailState.Expanded -> RAIL_EXPANDED_DP
            RailState.Hidden -> 0
        }

    /** The panel's width as a pane, clamped to what the window allows. */
    val paneWidthDp: Int get() = clampPanelWidth(panelWidthDp, windowWidthDp)

    /** Whether an open panel sits beside the content (a pane) rather than over it (a sheet). */
    val panelAsPane: Boolean get() = wide && windowWidthDp - railWidthDp - paneWidthDp >= CONTENT_MIN_DP

    companion object {
        const val RAIL_EXPANDED_DP = 278
        const val PANEL_MIN_DP = 320
        /** The web's Project panel is 800px wide on a 1859px window; a pane can grow to that where the window allows (see [clampPanelWidth]). */
        const val PANEL_MAX_DP = 800
        const val PANEL_DEFAULT_DP = 360
        const val CONTENT_MIN_DP = 380
        const val COMPACT_HEIGHT_MAX_DP = 480

        /** [width] held to `[PANEL_MIN_DP, min(PANEL_MAX_DP, window / 2)]`. */
        fun clampPanelWidth(width: Int, windowWidthDp: Int): Int = width.coerceIn(PANEL_MIN_DP, maxOf(PANEL_MIN_DP, minOf(PANEL_MAX_DP, windowWidthDp / 2)))

        /** The rail a window of [widthClass] opens with before the reader has chosen: the web's sidebar on any wide window. */
        fun defaultRail(widthClass: WidthClass): RailState = if (widthClass == WidthClass.Compact) RailState.Hidden else RailState.Expanded

        /** A phone's posture: the drawer and the sheet. */
        fun compact(windowWidthDp: Int = 411): WindowPosture = WindowPosture(WidthClass.Compact, windowWidthDp)
    }
}

/** The posture the shell laid the window out in; screens composed outside the shell (tests, previews) see a phone's. */
val LocalWindowPosture = staticCompositionLocalOf { WindowPosture.compact() }
