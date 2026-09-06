package com.cursorforandroid.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SectionLabel
import com.cursorforandroid.ui.components.CursorTopBar
import com.cursorforandroid.ui.theme.CursorTheme

/** Agents that need a look: finished-but-unread and failed runs. */
@Composable
fun InboxScreen(
    state: AgentListUiState,
    actions: AgentRowActions,
    onOpenSidebar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorTopBar(
            title = "Inbox",
            leading = { if (onOpenSidebar != null) CursorIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar) },
        )
        if (state.inbox.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Icon(CursorIcons.Inbox, null, tint = colors.textPlaceholder, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text("You're all caught up", style = CursorTheme.typography.body, color = colors.textSecondary)
                Spacer(Modifier.height(4.dp))
                Text("Finished and failed agents you haven't opened yet show up here.", style = CursorTheme.typography.secondary, color = colors.textPlaceholder, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                val failed = state.inbox.filter { it.indicator == com.cursorforandroid.domain.AgentIndicator.Error }
                val unread = state.inbox.filter { it.indicator != com.cursorforandroid.domain.AgentIndicator.Error }
                if (failed.isNotEmpty()) {
                    item("hdr-failed") { SectionLabel("Needs attention", Modifier.padding(horizontal = 8.dp)) }
                    items(failed, key = { "f-${it.agent.id}" }) { AgentRowItem(it, selected = false, prefs = state.prefs.copy(showWorkspace = true), actions = actions) }
                    item("gap") { Spacer(Modifier.fillMaxWidth().height(8.dp)) }
                }
                if (unread.isNotEmpty()) {
                    item("hdr-unread") { SectionLabel("Unread", Modifier.padding(horizontal = 8.dp)) }
                    items(unread, key = { "u-${it.agent.id}" }) { AgentRowItem(it, selected = false, prefs = state.prefs.copy(showWorkspace = true), actions = actions) }
                }
            }
        }
    }
}
