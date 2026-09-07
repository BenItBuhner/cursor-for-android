package com.cursorforandroid.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.components.ComposerMenuActions
import kotlinx.coroutines.launch

/**
 * Binds the composer's "+" menu to the app: the image picker for Files, recent skill names from preferences, and
 * the encrypted MCP server list. Shared by the New Chat and conversation composers so both menus behave the same.
 */
@Composable
fun rememberComposerMenuActions(graph: AppGraph, onPickFiles: () -> Unit): ComposerMenuActions {
    val servers by graph.mcpServers.servers.collectAsStateWithLifecycle()
    val recentSkills by graph.prefs.recentSkills.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    return ComposerMenuActions(
        onPickFiles = onPickFiles,
        recentSkills = recentSkills,
        onSkillUsed = { name -> scope.launch { graph.prefs.rememberSkill(name) } },
        mcpServers = servers,
        onToggleMcpServer = { server, enabled -> graph.mcpServers.setEnabled(server.id, enabled) },
        onSaveMcpServer = graph.mcpServers::save,
        onDeleteMcpServer = { graph.mcpServers.delete(it.id) },
    )
}
