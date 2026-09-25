package com.cursorforandroid.ui.panel

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens

/** The narrowest the chat column is ever made beside the rail and a pinned panel: a small phone's width, which its layout is built for. */
internal val ChatMinWidth = 320.dp

/** The pinned panel's narrowest: about what the sheet is on a small phone, which its sections are laid out for. */
internal val PinnedPanelMinWidth = 280.dp

/** The pinned panel's widest: half of a tablet on its side, enough to read a file or a document beside the chat. */
internal val PinnedPanelMaxWidth = 640.dp

/** The width the panel is pinned at until it is dragged: the sheet's column on any window wider than a phone. */
internal val PinnedPanelDefaultWidth = 400.dp

/** The rail's range when dragged: its rows keep a readable title at the narrowest. */
internal val RailMinWidth = 200.dp
internal val RailMaxWidth = 400.dp

/** Where a wide window turns from Medium to Expanded, Material's window size class boundary. */
private val ExpandedWindowWidth = 840.dp

/**
 * A wide window's size class, as far as the pinned panel is concerned: each keeps its own open or closed, so a tablet
 * turned upright comes back to how it was last left upright.
 */
enum class PaneWidthClass(internal val key: String) {
    /** 600dp to 840dp: a tablet held upright, a small window. */
    Medium("medium"),

    /** 840dp and up: a tablet on its side, a foldable's inner screen, a phone on its side. */
    Expanded("expanded");

    companion object {
        fun of(window: Dp): PaneWidthClass = if (window >= ExpandedWindowWidth) Expanded else Medium
    }
}

/**
 * How a wide window shares its width between the sidebar rail, the chat and the conversation panel pinned beside it.
 *
 * The chat keeps [ChatMinWidth] at the least, and beyond that takes whatever the rail and the panel leave. When the
 * three do not fit, the rail gives way first: narrower, down to [RailMinWidth], then out of the way altogether (its
 * header button and Ctrl+B bring it over the chat instead); only then does the panel come in narrower, down to
 * [PinnedPanelMinWidth]. A window with no room for the panel at its narrowest beside the chat at its narrowest does not
 * pin it at all, and the panel stays a sheet over the chat.
 *
 * With the panel shut, the rail is as wide as asked, up to what leaves the chat its minimum; on the narrowest wide
 * windows that still leaves it at least [CursorDimens.sidebarWidth], the width it has always had there.
 */
@Immutable
data class PaneWidths(
    /** The rail column's width while it is out; while it is not, about what it would come out at. */
    val rail: Dp,
    /** Whether the rail stands beside the chat: expanded, and with room for it. */
    val railShown: Boolean,
    /** Whether the rail would find room beside the chat if it were expanded. */
    val railFits: Boolean,
    /** The widest the rail can be dragged to as things stand. */
    val railMax: Dp,
    /** Whether the panel can stand beside the chat on this window at all. */
    val pinnable: Boolean,
    /** Whether the panel stands open beside the chat. */
    val panelShown: Boolean,
    /** The panel's width while it stands open, and the width it opens at. */
    val panel: Dp,
    /** The widest the panel can be dragged to on this window. */
    val panelMax: Dp,
) {
    companion object {
        /** Whether a window of [window] has room for the panel at its narrowest beside the chat at its narrowest. */
        fun pinnable(window: Dp): Boolean = window - ChatMinWidth >= PinnedPanelMinWidth

        fun of(window: Dp, railExpanded: Boolean, railWidth: Dp, panelOpen: Boolean, panelWidth: Dp): PaneWidths {
            val pinnable = pinnable(window)
            val panelMax = if (pinnable) (window - ChatMinWidth).coerceAtMost(PinnedPanelMaxWidth) else PinnedPanelMinWidth
            val panel = panelWidth.coerceIn(PinnedPanelMinWidth, panelMax)
            val panelShown = pinnable && panelOpen
            val railRoom = if (panelShown) window - ChatMinWidth - panel else maxOf(window - ChatMinWidth, CursorDimens.sidebarWidth)
            val railFits = railRoom >= RailMinWidth
            val railMax = railRoom.coerceAtMost(RailMaxWidth)
            return PaneWidths(
                rail = railWidth.coerceIn(RailMinWidth, maxOf(railMax, RailMinWidth)),
                railShown = railExpanded && railFits,
                railFits = railFits,
                railMax = railMax,
                pinnable = pinnable,
                panelShown = panelShown,
                panel = panel,
                panelMax = panelMax,
            )
        }
    }
}
