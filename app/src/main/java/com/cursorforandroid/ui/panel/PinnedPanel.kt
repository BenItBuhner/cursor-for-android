package com.cursorforandroid.ui.panel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The shell's side of a conversation panel pinned beside the chat, on a window wide enough for both ([PaneWidths]):
 * whether it stands open, which the shell keeps for the window's size class across chats and restarts, and the width
 * it has been given. Every chat's panel follows it, so the panel left open in one chat is open in the next.
 */
@Stable
interface PinnedPanel {
    /** Whether the panel stands open beside the chat on this window's size class; null until that has been read. */
    val open: Boolean?

    /** The width the panel stands at: half the window, or as last dragged, within what the window leaves it beside the chat. */
    val width: Dp

    /**
     * Whether the panel, not yet dragged, shares with the chat, half and half, what the rail leaves of the window
     * ([PaneWidths.splits]): it is half the room the two stand in, following the rail as it comes and goes.
     */
    val splits: Boolean

    /** Puts the panel open or shut as the window has it: carried over from a sheet, not a slide of its own. */
    fun setOpen(open: Boolean)

    /**
     * The panel sets off open or shut on a slide of its own, a button's or a key's ([keyed], a frame ahead): a rail
     * that makes way for it, or comes back as it goes, sets off with it on the same slide from the same frame.
     */
    fun slideOpen(open: Boolean, keyed: Boolean)

    /** The panel's edge dragged to make it [width] wide; the shell holds it to the panel's range. */
    fun resize(width: Dp)

    /** The drag let go: the width it came to is kept. */
    fun resizeDone()

    /** The drag taken away before it let go: the panel goes back to how the drag found it, and nothing is kept. */
    fun resizeCancelled()
}

/** The shell's [PinnedPanel] where the window has room to pin the panel beside the chat; null where it is a sheet over it. */
val LocalPinnedPanel = compositionLocalOf<PinnedPanel?> { null }

/** Whether the panel content is composed in a panel pinned beside the chat rather than a sheet over it. */
internal val LocalPanelPinned = staticCompositionLocalOf { false }
