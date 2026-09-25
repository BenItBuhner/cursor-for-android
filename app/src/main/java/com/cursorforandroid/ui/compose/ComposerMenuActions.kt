package com.cursorforandroid.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.ui.auth.openLoginPage
import com.cursorforandroid.ui.components.CONNECTORS_MANAGE_URL
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.ConnectorMenu
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * Binds the composer's "+" menu to the app: the photo picker for Images and videos, the document picker for Files
 * (Extended mode, when [onPickFiles] is given), recent skill names from preferences, and the MCP page — the account's
 * connectors in Extended mode, the encrypted list of the app's own servers otherwise. Shared by the New Chat and
 * conversation composers so both menus behave the same.
 */
@Composable
fun rememberComposerMenuActions(graph: AppGraph, onPickMedia: () -> Unit, onPickFiles: (() -> Unit)? = null): ComposerMenuActions {
    val servers by graph.mcpServers.servers.collectAsStateWithLifecycle()
    val recentSkills by graph.prefs.recentSkills.collectAsStateWithLifecycle(initialValue = emptyList())
    val capabilities by graph.extendedMode.capabilities.collectAsStateWithLifecycle(initialValue = Capabilities.DOCUMENTED)
    val scope = rememberCoroutineScope()
    return ComposerMenuActions(
        onPickMedia = onPickMedia,
        onPickFiles = onPickFiles,
        recentSkills = recentSkills,
        onSkillUsed = { name -> scope.launch { graph.prefs.rememberSkill(name) } },
        mcpServers = servers,
        onToggleMcpServer = { server, enabled -> graph.mcpServers.setEnabled(server.id, enabled) },
        onSaveMcpServer = graph.mcpServers::save,
        onDeleteMcpServer = { graph.mcpServers.delete(it.id) },
        connectors = if (capabilities.accountConnectors) rememberConnectorMenu(graph) else null,
    )
}

/**
 * The account's connectors for the MCP page. A sign-in opens in a Custom Tab, which shares the browser's cursor.com
 * session: the account's sign-in URL returns to cursor.com's own OAuth callback, which finishes it there. Coming back
 * to the app reads the statuses again.
 */
@Composable
private fun rememberConnectorMenu(graph: AppGraph): ConnectorMenu {
    val repository = graph.connectors
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbar = CursorTheme.colors.canvas
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(repository) {
        repository.onReturn()
        onPauseOrDispose {}
    }
    return ConnectorMenu(
        connectors = state.connectors,
        loading = state.loading,
        error = state.error,
        notice = state.notice,
        onOpen = { repository.refresh() },
        onToggle = { connector, enabled -> repository.setEnabled(connector.id, enabled) },
        onConnect = { connector ->
            scope.launch {
                val url = repository.signInUrl(connector.id) ?: return@launch
                openLoginPage(context, url, toolbar)
            }
        },
        onManage = { openLoginPage(context, CONNECTORS_MANAGE_URL, toolbar) },
    )
}
