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

/** The share of the window the panel asks for until it is dragged: half, and the chat the other half. */
private const val HalfWindow = 0.5f

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
 * Until its edge is dragged, the panel asks for half the window. Where that leaves the rail room beside the chat, the
 * panel and the chat share what the rail leaves of the window half and half instead ([splits]), and follow the rail as
 * it comes and goes; where it does not, the rail makes way and the two share the whole window. Dragged, the panel
 * keeps the share of the window it was dragged to, so it comes back in proportion on a window of another size, from
 * [PinnedPanelMinWidth] up to all the chat leaves at its narrowest.
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
    /** The widest the panel can be dragged to on this window: all the chat leaves at its narrowest. */
    val panelMax: Dp,
    /** Whether the panel, not yet dragged, shares with the chat, half and half, what the rail leaves of the window. */
    val splits: Boolean,
) {
    companion object {
        /** Whether a window of [window] has room for the panel at its narrowest beside the chat at its narrowest. */
        fun pinnable(window: Dp): Boolean = window - ChatMinWidth >= PinnedPanelMinWidth

        /** [panelFraction]: the share of the window the panel was last dragged to; null until it has been. */
        fun of(window: Dp, railExpanded: Boolean, railWidth: Dp, panelOpen: Boolean, panelFraction: Float?): PaneWidths {
            val pinnable = pinnable(window)
            val panelMax = if (pinnable) window - ChatMinWidth else PinnedPanelMinWidth
            val asked = (window * (panelFraction ?: HalfWindow)).coerceIn(PinnedPanelMinWidth, panelMax)
            val besidePanel = window - ChatMinWidth - asked
            val splits = pinnable && panelFraction == null && besidePanel >= RailMinWidth
            val panelShown = pinnable && panelOpen
            val railRoom = when {
                !panelShown -> maxOf(window - ChatMinWidth, CursorDimens.sidebarWidth)
                splits -> window - ChatMinWidth * 2
                else -> besidePanel
            }
            val railFits = railRoom >= RailMinWidth
            val railMax = railRoom.coerceAtMost(RailMaxWidth)
            val rail = railWidth.coerceIn(RailMinWidth, maxOf(railMax, RailMinWidth))
            return PaneWidths(
                rail = rail,
                railShown = railExpanded && railFits,
                railFits = railFits,
                railMax = railMax,
                pinnable = pinnable,
                panelShown = panelShown,
                panel = if (splits && railExpanded) (window - rail) / 2 else asked,
                panelMax = panelMax,
                splits = splits,
            )
        }
    }
}
