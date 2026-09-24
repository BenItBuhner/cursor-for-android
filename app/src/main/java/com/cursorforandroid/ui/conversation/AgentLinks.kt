package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.AgentLink
import com.cursorforandroid.ui.components.InlineMarkdown
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Where a tapped link to an agent goes. */
sealed interface AgentLinkRoute {
    /** The agent's page on cursor.com: a `#desktop` link means the agent's VM desktop, which the page shows. */
    data class Web(val url: String) : AgentLinkRoute

    /** The agent's chat, which the list already holds. */
    data class Chat(val agentId: String) : AgentLinkRoute

    /** The agent's chat once it has been read by its id: the list does not hold it, and the read may be refused. */
    data class Fetch(val agentId: String) : AgentLinkRoute

    companion object {
        /** [inApp] is false where nothing can open a chat, and every link then goes to cursor.com. */
        fun of(link: AgentLink, isLoaded: (String) -> Boolean, inApp: Boolean = true): AgentLinkRoute = when {
            link.isDesktop || !inApp -> Web(link.webUrl)
            isLoaded(link.agentId) -> Chat(link.agentId)
            else -> Fetch(link.agentId)
        }
    }
}

sealed interface AgentLinkState {
    data object Idle : AgentLinkState

    /** The linked agent is being read by its id before its chat opens. */
    data class Opening(val link: AgentLink) : AgentLinkState

    /** The read was refused or failed; [message] is the server's own word for why. */
    data class Failed(val link: AgentLink, val message: String) : AgentLinkState
}

/**
 * Opens the links to agents a chat's messages carry ([AgentLink]): a listed agent's chat at once, through [openChat] —
 * the screen's way to another chat, which puts it on the back stack — an unlisted one once [fetch] has read it
 * (landing it in the list), and a `#desktop` link on cursor.com through [openWeb]. A read that fails is held in
 * [state] with the server's message, for [AgentLinkDialog] to show with the way to cursor.com. A second link tapped
 * while a read is out replaces the first.
 */
@Stable
class AgentLinkOpener(
    private val scope: CoroutineScope,
    private val isLoaded: (String) -> Boolean,
    private val fetch: suspend (String) -> Result<*>,
    private val openWeb: (String) -> Unit,
    private val describe: (Throwable) -> String = Throwable::userMessage,
) {
    /** The screen's way to another chat; null where there is none, and links go to cursor.com instead. */
    var openChat: ((String) -> Unit)? = null

    var state: AgentLinkState by mutableStateOf(AgentLinkState.Idle)
        private set

    private var job: Job? = null

    fun open(link: AgentLink) {
        cancel()
        val chat = openChat
        when (val route = AgentLinkRoute.of(link, isLoaded, inApp = chat != null)) {
            is AgentLinkRoute.Web -> openWeb(route.url)
            is AgentLinkRoute.Chat -> chat?.invoke(route.agentId)
            is AgentLinkRoute.Fetch -> read(link)
        }
    }

    private fun read(link: AgentLink) {
        state = AgentLinkState.Opening(link)
        job = scope.launch {
            val result = fetch(link.agentId)
            // The read wraps whatever it throws, a cancellation included: one that stopped this job is not a failure.
            ensureActive()
            val failure = result.exceptionOrNull()
            job = null
            if (failure == null) {
                state = AgentLinkState.Idle
                openChat?.invoke(link.agentId) ?: openWeb(link.webUrl)
            } else {
                state = AgentLinkState.Failed(link, describe(failure))
            }
        }
    }

    /** Stops a read under way, or puts a failure away. */
    fun cancel() {
        job?.cancel()
        job = null
        state = AgentLinkState.Idle
    }

    /** The failed link's page on cursor.com, which may still show what this account could not read. */
    fun openOnWeb() {
        val failed = state as? AgentLinkState.Failed ?: return
        state = AgentLinkState.Idle
        openWeb(failed.link.webUrl)
    }
}

/**
 * The [AgentLinkOpener] of a chat, reading the agents off [graph]'s list and by their id. [onOpenChat] is read at the
 * moment a chat opens, so a new lambda each composition is fine.
 */
@Composable
fun rememberAgentLinkOpener(graph: AppGraph, onOpenChat: ((String) -> Unit)?): AgentLinkOpener {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val opener = remember(graph, scope, uriHandler) {
        AgentLinkOpener(
            scope = scope,
            isLoaded = { id -> graph.agents.agent(id) != null },
            fetch = { id -> graph.agents.loadDetail(id) },
            openWeb = InlineMarkdown.opener(uriHandler),
        )
    }
    opener.openChat = onOpenChat
    return opener
}

object AgentLinkTags {
    const val OPENING = "agent_link_opening"
    const val CANCEL = "agent_link_cancel"
    const val FAILED = "agent_link_failed"
    const val OPEN_ON_WEB = "agent_link_open_on_web"
    const val CLOSE = "agent_link_close"
}

/**
 * What an [AgentLinkOpener] has to say, in the app's dialog idiom: a spinner with Cancel while a read takes longer
 * than a moment (a quick one opens the chat without anything flashing up first), and a refused read with the
 * server's message, the agent's id, and the way to its page on cursor.com.
 */
@Composable
fun AgentLinkDialog(opener: AgentLinkOpener) {
    when (val state = opener.state) {
        AgentLinkState.Idle -> Unit
        is AgentLinkState.Opening -> OpeningDialog(state.link, onCancel = opener::cancel)
        is AgentLinkState.Failed -> FailedDialog(state, onOpenOnWeb = opener::openOnWeb, onClose = opener::cancel)
    }
}

@Composable
private fun OpeningDialog(link: AgentLink, onCancel: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var shown by remember(link) { mutableStateOf(false) }
    LaunchedEffect(link) {
        delay(OPENING_DIALOG_DELAY_MILLIS)
        shown = true
    }
    if (!shown) return
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(AgentLinkTags.OPENING),
        containerColor = colors.elevated,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 14.dp)
                Spacer(Modifier.width(10.dp))
                Text("Opening the agent\u2026", style = type.base, color = colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(AgentLinkTags.CANCEL)) {
                Text("Cancel", style = type.baseMedium, color = colors.textPrimary)
            }
        },
    )
}

@Composable
private fun FailedDialog(state: AgentLinkState.Failed, onOpenOnWeb: () -> Unit, onClose: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(AgentLinkTags.FAILED),
        containerColor = colors.elevated,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        shape = CursorTheme.shapes.xl,
        title = { Text("Couldn't open this agent", style = type.sectionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.message, style = type.base, color = colors.textSecondary)
                Text(state.link.agentId, style = type.code, color = colors.textQuaternary)
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenOnWeb, modifier = Modifier.testTag(AgentLinkTags.OPEN_ON_WEB)) {
                Text("Open on cursor.com", style = type.baseMedium, color = colors.link)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag(AgentLinkTags.CLOSE)) {
                Text("Close", style = type.baseMedium, color = colors.textPrimary)
            }
        },
    )
}

private const val OPENING_DIALOG_DELAY_MILLIS = 400L
