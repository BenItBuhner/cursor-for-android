package com.cursorforandroid.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.repo.TranscriptSearchIndex
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.PaletteEntry
import com.cursorforandroid.domain.TranscriptHit
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowGlyph
import com.cursorforandroid.ui.shortcuts.ChatShortcuts
import com.cursorforandroid.ui.shortcuts.CommandPalette
import com.cursorforandroid.ui.shortcuts.PaletteMode
import com.cursorforandroid.ui.shortcuts.PaletteState
import com.cursorforandroid.ui.shortcuts.SwitcherOrder
import com.cursorforandroid.ui.shortcuts.TranscriptFocusRequests

/**
 * What the shell keeps for the hardware keyboard: the palette, what each open chat answers, where a transcript hit is
 * to be scrolled to, the chats visited this session (the quick switcher's order), the sidebar's first ten rows as last
 * drawn (Ctrl+1 … Ctrl+0), and Ctrl+N's ask for the composer.
 */
@Stable
internal class ShellShortcuts {
    val palette = PaletteState()
    val chats = ChatShortcuts()
    val transcriptFocus = TranscriptFocusRequests()

    /** The chats opened this session, the newest first. */
    val visited = mutableStateListOf<String>()

    /** The quick switcher's rows, fixed when it opens so they hold still while Tab steps through them. */
    var switcherRows by mutableStateOf(emptyList<PaletteEntry>())
        private set

    var railRows by mutableStateOf(emptyList<AgentRow>())

    /** Ctrl+N asked for the New Chat composer; the pane clears it once the caret is in the field. */
    var focusComposer by mutableStateOf(false)

    fun visit(agentId: String) {
        visited.remove(agentId)
        visited.add(0, agentId)
        while (visited.size > SwitcherOrder.LIMIT) visited.removeAt(visited.lastIndex)
    }

    /**
     * Ctrl+Tab ([forward]) or Ctrl+Shift+Tab: the switcher opened on the chat before the open one (or the oldest
     * listed), and each press after that one row on or back, around the ends.
     */
    fun stepSwitcher(forward: Boolean, current: String?, entries: () -> List<PaletteEntry>) {
        if (palette.mode == PaletteMode.Switcher) {
            palette.step(if (forward) 1 else -1, switcherRows.size)
            return
        }
        val order = SwitcherOrder.of(visited, entries(), current)
        switcherRows = order
        palette.openSwitcher(SwitcherOrder.start(order, current, forward))
    }

    /** Ctrl let go: the switcher is put away, and its selection is what to open when [commit]; null when it was not up. */
    fun releaseSwitcher(commit: Boolean): PaletteEntry? {
        if (palette.mode != PaletteMode.Switcher) return null
        val picked = switcherRows.getOrNull(palette.switcherIndex)
        palette.close()
        return picked.takeIf { commit }
    }

    companion object {
        /** The search palette's rows before anything is typed. */
        const val RECENT_ROWS = 8

        /** What the switcher and the search find: every chat the list has, archived ones found by search alone. */
        fun entries(list: AgentListUiState, archived: Boolean): List<PaletteEntry> =
            list.allAgents.asSequence().filter { archived || !it.isArchived }.map(PaletteEntry::of).sortedByDescending { it.updatedAtMillis }.toList()
    }
}

/**
 * The palette over the shell, fed from the list and the transcripts this device keeps. The transcripts are read
 * while search is up (see [TranscriptSearchIndex.refresh]): a chat read before and not moved on since is not read again.
 */
@Composable
internal fun PaletteHost(
    shortcuts: ShellShortcuts,
    list: AgentListUiState,
    index: TranscriptSearchIndex,
    onOpen: (PaletteEntry, TranscriptHit?) -> Unit,
) {
    val palette = shortcuts.palette
    val searching = palette.mode == PaletteMode.Search
    val entries = remember(list.allAgents) { ShellShortcuts.entries(list, archived = true) }
    val byId = remember(list.allAgents) { list.allAgents.associateBy { it.id } }
    val transcripts by index.passages.collectAsStateWithLifecycle()
    val reading by index.isReading.collectAsStateWithLifecycle()
    LaunchedEffect(searching) {
        if (searching) index.refresh(entries.map { it.agentId to it.updatedAtMillis })
    }
    val visited = shortcuts.visited.toList()
    val recent = remember(entries, visited) { SwitcherOrder.of(visited, entries.filter { !(byId[it.agentId]?.isArchived ?: false) }, null, ShellShortcuts.RECENT_ROWS) }
    CommandPalette(
        state = palette,
        entries = entries,
        switcher = if (palette.mode == PaletteMode.Switcher) shortcuts.switcherRows else recent,
        transcripts = transcripts,
        readingTranscripts = reading,
        glyph = { id -> byId[id]?.let { AgentRowGlyph(AgentListOrganizer.toRow(it, list.local, list.nowMillis)) } },
        onOpen = onOpen,
        onDismiss = palette::close,
    )
}
