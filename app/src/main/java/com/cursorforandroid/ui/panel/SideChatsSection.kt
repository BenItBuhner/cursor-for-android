package com.cursorforandroid.ui.panel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.projects.NameSheet
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * The chats branched off this one — any chat's, as in Cursor, where "Ask in Side Chat" is on every conversation.
 * Each row opens the side chat in place. The list is what the agent list knows in either mode and, in Extended
 * mode, what `ListBackgroundComposerChildren` says when the section opens; "New side chat" is the Extended half
 * (`StartSideChatBackgroundComposer`), and a refusal reads as an error with a way to try again. The documented API
 * carries no side-chat surface of its own, so default mode lists and says nothing more.
 */
@Composable
internal fun SideChatsSection(state: PanelState, actions: PanelActions) {
    val chats = state.sideChats
    val canStart = DefaultPanelSections.canStartSideChat(state.capabilities, state)
    var naming by rememberSaveable("side-chat-name-${state.agentId}") { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("side-chats-section")) {
        chats.forEach { chat -> SideChatRow(chat, onOpen = { actions.openAgent(chat.id) }) }
        when (val load = state.sideChatsLoad) {
            RemoteLoad.Loading -> if (chats.isEmpty()) LoadingRow("Looking for side chats…")
            is RemoteLoad.Failed -> FailedRow(load.message, onRetry = if (load.retryable) ({ actions.refreshSideChats() }) else null)
            is RemoteLoad.Loaded -> if (chats.isEmpty()) EmptyRow("No side chats yet", "A side chat branches off this conversation with its context, to explore a question without steering the agent off course.")
            // Not read, or not readable from here (the mode is off): the rows above are the whole of what is known.
            RemoteLoad.Idle, is RemoteLoad.Unsupported -> Unit
        }
        if (canStart) {
            when (val creation = state.sideChatCreation) {
                RemoteLoad.Loading -> LoadingRow("Starting a side chat…")
                is RemoteLoad.Failed -> StateRow(
                    icon = CursorIcons.Warning,
                    title = "Couldn't start a side chat",
                    detail = creation.message,
                    tint = CursorTheme.colors.red,
                    actionLabel = "Try again",
                    onAction = { naming = true },
                    modifier = Modifier.testTag("side-chat-failed"),
                )
                else -> PanelRow(
                    title = "New side chat",
                    icon = CursorIcons.Plus,
                    iconTint = CursorTheme.colors.accent,
                    subtitle = "Branch a conversation off this chat",
                    onClick = { naming = true },
                    modifier = Modifier.testTag("new-side-chat"),
                )
            }
        }
    }
    if (naming) {
        NameSheet(title = "New side chat", placeholder = "Name (optional)", action = "Start", onConfirm = actions::startSideChat, onDismiss = { naming = false })
    }
}

/** One side chat: its name, what it is doing, how long since it last moved; opens it. */
@Composable
private fun SideChatRow(chat: Agent, onOpen: () -> Unit) {
    val colors = CursorTheme.colors
    val detail = listOfNotNull(
        when {
            chat.isArchived -> "Archived"
            chat.isRunning -> "Working"
            chat.hasPendingInteraction -> "Needs input"
            else -> null
        },
        chat.updatedAtMillis.takeIf { it > 0 }?.let(TimeFormat::relativeShort),
    ).joinToString(" · ").ifEmpty { null }
    PanelRow(
        title = chat.name,
        icon = CursorIcons.Ask,
        iconTint = if (chat.isRunning) colors.accent else colors.iconTertiary,
        subtitle = detail,
        trailing = { Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp)) },
        onClick = onOpen,
        modifier = Modifier.testTag("side-chat"),
    )
}
