package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which agents a link in a message should show as working: the ids of the list's rows whose latest run is going
 * ([com.cursorforandroid.domain.Agent.isRunning], the sidebar's own rule), read at the moment an icon draws. Any
 * other id — a finished agent, or one the list does not hold — shows at rest, as the desktop shows it.
 */
@Stable
class AgentLinkStatuses(private val running: State<Set<String>>) {
    fun isRunning(agentId: String): Boolean = agentId in running.value
}

/** The statuses of the screen that provides them; absent, every link to an agent shows at rest. */
val LocalAgentLinkStatuses = staticCompositionLocalOf<AgentLinkStatuses?> { null }

/** [AgentLinkStatuses] off [agents]' list, following it as runs start and finish. */
@Composable
fun rememberAgentLinkStatuses(agents: AgentRepository): AgentLinkStatuses {
    val flow = remember(agents) { agents.state.map { s -> runningIds(s.agents) }.distinctUntilChanged() }
    val running = flow.collectAsStateWithLifecycle(initialValue = remember(agents) { runningIds(agents.state.value.agents) })
    return remember(running) { AgentLinkStatuses(running) }
}

private fun runningIds(agents: List<Agent>): Set<String> = agents.filter { it.isRunning }.mapTo(HashSet()) { it.id }

/**
 * The status icon a link to an agent opens with, the desktop's (`SubagentMarkdownLink`): the dot grid while the agent
 * works ([RunningGlyph], its `sine_3x3` loader), the arrowhead ([CursorIcons.AgentPointer]) once it is at rest, and
 * the monitor for a link to its VM desktop. It sits in a 1em square slot followed by 0.2857em of space, dropped
 * 0.0714em below the baseline, in the link's colour: the desktop's `vertical-align` and `margin-inline-end`.
 *
 * The slot is a placeholder in the paragraph's text ([InlineMarkdown.render] with `agentLinkIcons`), inside the
 * link's annotation, so the icon wraps with the first word of the label and a tap on it opens the link. Only the
 * icon reads the status, so a run finishing redraws the icon and not the paragraph around it.
 */
object AgentLinkIcon {
    private const val PREFIX = "agent-link-status:"
    private const val SLOT_EM = 1f
    private const val GAP_EM = 0.2857f
    private const val DROP_EM = 0.0714f

    const val TAG_RUNNING = "agent_link_status_running"
    const val TAG_IDLE = "agent_link_status_idle"
    const val TAG_DESKTOP = "agent_link_status_desktop"

    /** The inline content id of the icon for a link to [target], the link's own address. */
    fun id(target: String): String = PREFIX + target

    /** The icons [annotated]'s links to agents need, or an empty map when it has none. */
    fun inlineContent(annotated: AnnotatedString, color: Color): Map<String, InlineTextContent> {
        val targets = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNullTo(LinkedHashSet()) { (it.item as? LinkAnnotation.Url)?.url?.takeIf { url -> AgentLink.parse(url) != null } }
        if (targets.isEmpty()) return emptyMap()
        val placeholder = Placeholder((SLOT_EM + GAP_EM).em, SLOT_EM.em, PlaceholderVerticalAlign.AboveBaseline)
        return targets.associate { target ->
            val link = AgentLink.parse(target)!!
            id(target) to InlineTextContent(placeholder) { StatusIcon(link, color) }
        }
    }

    @Composable
    private fun StatusIcon(link: AgentLink, color: Color) {
        val statuses = LocalAgentLinkStatuses.current
        val running by remember(statuses, link.agentId) { derivedStateOf { statuses?.isRunning(link.agentId) == true } }
        Box(Modifier.slot(), propagateMinConstraints = true) {
            when {
                link.isDesktop -> Icon(CursorIcons.Desktop, null, tint = color, modifier = Modifier.testTag(TAG_DESKTOP))
                running -> RunningGlyph(Modifier.testTag(TAG_RUNNING), color = color)
                else -> Icon(CursorIcons.AgentPointer, null, tint = color, modifier = Modifier.testTag(TAG_IDLE))
            }
        }
    }

    /** A square as tall as the placeholder at its start, dropped below the baseline; the rest of the width is the gap. */
    private fun Modifier.slot(): Modifier = layout { measurable, constraints ->
        val side = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, side) { placeable.place(0, (side * DROP_EM / SLOT_EM).toInt()) }
    }
}
