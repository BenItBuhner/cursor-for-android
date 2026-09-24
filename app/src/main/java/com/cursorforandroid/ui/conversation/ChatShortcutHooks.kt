package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.ui.panel.SidePanelState
import com.cursorforandroid.ui.shortcuts.ChatShortcutTarget
import com.cursorforandroid.ui.shortcuts.LocalChatShortcuts
import com.cursorforandroid.ui.shortcuts.LocalTranscriptFocus
import com.cursorforandroid.ui.shortcuts.TranscriptHitLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How many pages of older turns a palette hit is looked for in before the chat says it is not among them. */
internal const val HIT_OLDER_PAGES = 6

internal const val HIT_NOT_SHOWN = "The match is further back than this chat has loaded"

/** The chat's answers to Ctrl+Shift+B, Ctrl+R, Ctrl+Shift+R and Esc, registered with the shell while it is composed. */
@Composable
internal fun ChatKeyboardShortcuts(agentId: String, viewModel: ConversationViewModel, panelState: SidePanelState) {
    val shortcuts = LocalChatShortcuts.current ?: return
    val scope = rememberCoroutineScope()
    val target = remember(viewModel, panelState, scope) {
        object : ChatShortcutTarget {
            override fun togglePanel(): Boolean {
                scope.launch { if (panelState.isOpen) panelState.close() else panelState.open() }
                return true
            }

            override fun catchUp() = viewModel.catchUp()

            override fun reloadTranscript() = viewModel.reloadTranscriptWithWord()

            // A panel pinned beside the chat is part of the layout, like the rail: Esc leaves the field, not the panel.
            override fun escape(): Boolean {
                if (!panelState.isOpen || panelState.isPinned) return false
                scope.launch { panelState.close() }
                return true
            }
        }
    }
    DisposableEffect(shortcuts, agentId, target) {
        shortcuts.register(agentId, target)
        onDispose { shortcuts.unregister(agentId, target) }
    }
}

/**
 * A search palette hit on its way to this chat: once the rows are in, the list lets go of the newest turn and puts
 * the row the hit is in at the top. A hit in turns older than those loaded pages them in, a few pages at most, and
 * the chat says so when it is further back than that.
 */
@Composable
internal fun TranscriptHitScroll(
    agentId: String,
    rows: List<TranscriptRow>,
    conversation: ConversationState,
    transcriptScroll: TranscriptScroll,
    viewModel: ConversationViewModel,
) {
    // Remembered ahead of the returns below, which the spent request takes on the next pass: the scroll it started
    // runs on to the end rather than being cancelled with a scope that left the composition.
    val scope = rememberCoroutineScope()
    val focus = LocalTranscriptFocus.current ?: return
    val request = focus.pending?.takeIf { it.agentId == agentId } ?: return
    var olderPages by remember(request) { mutableIntStateOf(0) }
    LaunchedEffect(request, rows, conversation.isLoading, conversation.isLoadingOlder) {
        if (rows.isEmpty() && conversation.isLoading) return@LaunchedEffect
        val index = TranscriptHitLocator.rowIndex(rows, request.hit)
        if (index == null) {
            when {
                conversation.isLoading || conversation.isLoadingOlder -> Unit
                conversation.hasOlder && olderPages < HIT_OLDER_PAGES -> {
                    olderPages++
                    viewModel.loadOlder()
                }
                else -> {
                    focus.done(request)
                    viewModel.showMessage(HIT_NOT_SHOWN)
                }
            }
            return@LaunchedEffect
        }
        val key = rows[index].key
        focus.done(request)
        // On the screen's scope: the request is spent, and the recomposition that says so must not cancel the scroll.
        scope.launch {
            transcriptScroll.pin()
            snapshotFlow { transcriptScroll.list.layoutInfo.reverseLayout }.first { !it }
            val topDown = transcriptScroll.topDownIndex(key)
            if (topDown >= 0) transcriptScroll.scrollToTopDown(topDown)
        }
    }
}
