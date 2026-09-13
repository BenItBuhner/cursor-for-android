package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.WorkerStatus
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A Project coordinator's steps as what they are. The coordinator works through its workers — it creates them,
 * messages them, asks how they stand, stops them, reads their transcripts — and speaks to the user through a tool of
 * its own; on the documented stream each of those is a tool call like any other, and as plain lines they read
 * "Created agent", "Sent message" with nothing to open. Here each is first-class: a card per worker created, naming
 * it and saying where it stands (live, from the list's row, once the worker is loaded here) that opens its
 * conversation; a row per worker in a status check; a compact row for a message or a stop, opening onto the message;
 * and the coordinator's own words to the user as a message in its voice, set apart from an agent's reply.
 */
@Composable
internal fun CoordinatorStep(call: ToolCall, modifier: Modifier = Modifier): Boolean {
    // The cards take a little more air than a line in the step list's 2dp rhythm.
    when (val payload = call.payload) {
        is ToolPayload.CoordinatorMessage -> CoordinatorMessageView(call, payload, modifier.padding(vertical = 4.dp))
        is ToolPayload.WorkerAction -> when (payload.kind) {
            ToolPayload.WorkerAction.Kind.Created -> WorkerTaskCard(call, payload, modifier.padding(vertical = 3.dp))
            ToolPayload.WorkerAction.Kind.Status -> WorkerStatusRows(call, payload, modifier)
            ToolPayload.WorkerAction.Kind.Messaged, ToolPayload.WorkerAction.Kind.Stopped, ToolPayload.WorkerAction.Kind.ReadTranscript -> WorkerActionRow(call, payload, modifier)
        }
        else -> return false
    }
    return true
}

/**
 * Where a worker stands, in one word with its colour: the list's live row when the worker is loaded here (its
 * pending question, its run, how the run ended), else what the coordinator's tool reported, else nothing.
 */
internal data class WorkerState(val label: String, val tint: Color, val fill: Color, val busy: Boolean = false)

@Composable
internal fun workerState(agent: Agent?, reported: WorkerStatus?): WorkerState? {
    val colors = CursorTheme.colors
    fun of(label: String, tint: Color, busy: Boolean = false) = WorkerState(label, tint, tint.copy(alpha = 0.14f), busy)
    if (agent != null) {
        return when {
            agent.hasPendingInteraction -> of("Needs input", colors.orange)
            agent.isRunning -> of("Working", colors.textPrimary, busy = true)
            agent.runStatus == RunStatus.FINISHED -> of("Finished", colors.green)
            agent.runStatus == RunStatus.ERROR -> of("Failed", colors.red)
            agent.runStatus == RunStatus.CANCELLED -> of("Cancelled", colors.textTertiary)
            agent.runStatus == RunStatus.EXPIRED -> of("Expired", colors.textTertiary)
            agent.isArchived -> of("Archived", colors.textTertiary)
            else -> null
        }
    }
    val label = reported?.statusLabel ?: return null
    return when (label) {
        "Working" -> of(label, colors.textPrimary, busy = true)
        "Finished" -> of(label, colors.green)
        "Failed" -> of(label, colors.red)
        "Needs input" -> of(label, colors.orange)
        else -> of(label, colors.textTertiary)
    }
}

/**
 * The card of a worker the coordinator created: the worker's name (the list's, once it is loaded; the coordinator's
 * until then), where it stands, and the first lines of what it was asked. Tapping it opens the worker's conversation
 * when the worker's id is known and the screen can navigate; while the call is still running the card says so.
 */
@Composable
internal fun WorkerTaskCard(call: ToolCall, action: ToolPayload.WorkerAction, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val controls = LocalTranscriptControls.current
    val reported = action.worker
    val agentId = reported?.agentId
    val agent = agentId?.let(controls.agentById)
    val name = agent?.name?.takeIf { it.isNotBlank() } ?: reported?.name?.takeIf { it.isNotBlank() } ?: action.title?.takeIf { it.isNotBlank() } ?: agentId ?: "New agent"
    val state = if (call.isRunning) WorkerState("Creating", colors.textTertiary, colors.fill, busy = true) else workerState(agent, reported)
    val open = controls.onOpenAgent?.takeIf { agentId != null && reported.isCloudAgent }?.let { handler -> { handler(agentId!!) } }
    CursorCard(
        modifier
            .fillMaxWidth()
            .testTag("worker-card")
            .semantics { contentDescription = "Worker $name" + (state?.let { ", ${it.label}" } ?: "") },
        fill = colors.fillFaint,
        border = Color.Transparent,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable({ open?.invoke() }, CursorTheme.shapes.lg, enabled = open != null)
                .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Multitask, null, tint = colors.iconTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShimmerText(
                        if (call.isRunning) "Creating $name" else name,
                        style = type.baseMedium,
                        color = colors.textPrimary,
                        active = call.isRunning,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state != null && !call.isRunning) {
                        Spacer(Modifier.width(8.dp))
                        Pill(state.label, tint = state.tint, fill = state.fill)
                    }
                }
                action.text?.trim()?.takeIf { it.isNotEmpty() }?.let { prompt ->
                    Text(prompt, style = type.small, color = colors.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val facts = listOfNotNull(
                    agent?.branchName?.let { "on $it" },
                    (agent?.prUrl ?: reported?.prUrl)?.let { "PR open" },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" \u00B7 "), style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (open != null) {
                Spacer(Modifier.width(6.dp))
                Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * A status check as one row per worker — its state, name and where it stands — rather than a "Checked agents" line
 * with the report behind it. Each row opens the worker when it can be opened.
 */
@Composable
internal fun WorkerStatusRows(call: ToolCall, action: ToolPayload.WorkerAction, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val controls = LocalTranscriptControls.current
    Column(modifier.fillMaxWidth().testTag("worker-status")) {
        Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            ShimmerText(call.action, style = type.base, color = colors.textSecondary, active = call.isRunning, maxLines = 1)
            val count = action.workers.size
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(if (count == 1) "1 agent" else "$count agents", style = type.base, color = colors.textTertiary, maxLines = 1)
            }
        }
        if (action.workers.isNotEmpty()) {
            CursorCard(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), fill = colors.fillFaint, border = Color.Transparent) {
                Column {
                    action.workers.forEachIndexed { index, reported ->
                        if (index > 0) HairlineDivider()
                        val agent = reported.agentId?.let(controls.agentById)
                        val name = agent?.name?.takeIf { it.isNotBlank() } ?: reported.name?.takeIf { it.isNotBlank() } ?: reported.agentId ?: "Agent"
                        val state = workerState(agent, reported)
                        val open = controls.onOpenAgent?.takeIf { reported.isCloudAgent }?.let { handler -> { handler(reported.agentId!!) } }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable({ open?.invoke() }, CursorTheme.shapes.base, enabled = open != null)
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(state?.tint ?: colors.iconQuaternary))
                            Spacer(Modifier.width(9.dp))
                            Text(name, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            state?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(it.label, style = type.small, color = it.tint, maxLines = 1)
                            }
                            if ((agent?.prUrl ?: reported.prUrl) != null) {
                                Spacer(Modifier.width(8.dp))
                                Icon(CursorIcons.GitPullRequest, "Pull request", tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
                            }
                            if (open != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A message to a worker, a stop, a transcript read: one compact row naming the worker — "Messaged Stripe webhook
 * handler · Delivered as queued" — that opens onto what was said, with the way to the worker's chat under it.
 */
@Composable
internal fun WorkerActionRow(call: ToolCall, action: ToolPayload.WorkerAction, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val controls = LocalTranscriptControls.current
    val reported = action.worker
    val agent = reported?.agentId?.let(controls.agentById)
    val name = agent?.name?.takeIf { it.isNotBlank() } ?: reported?.name?.takeIf { it.isNotBlank() } ?: reported?.agentId
    val body = listOfNotNull(action.title?.trim()?.takeIf { it.isNotEmpty() }?.let { "**$it**" }, action.text?.trim()?.takeIf { it.isNotEmpty() }).joinToString("\n\n").ifEmpty { null }
    val open = controls.onOpenAgent?.takeIf { reported?.isCloudAgent == true }?.let { handler -> { handler(reported!!.agentId!!) } }
    val expandable = body != null || open != null
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "chevron")
    val verb = when (action.kind) {
        ToolPayload.WorkerAction.Kind.Messaged -> if (call.isRunning) "Messaging" else if (call.isError) "Message to" else "Messaged"
        ToolPayload.WorkerAction.Kind.Stopped -> if (call.isRunning) "Stopping" else if (call.isError) "Stop" else "Stopped"
        ToolPayload.WorkerAction.Kind.ReadTranscript -> if (call.isRunning) "Reading the transcript of" else "Read the transcript of"
        else -> call.action
    }
    Column(modifier.fillMaxWidth().testTag("worker-action")) {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable({ expanded = !expanded }, CursorTheme.shapes.base, enabled = expandable)
                .heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.Multitask, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            ShimmerText(verb, style = type.base, color = colors.textSecondary, active = call.isRunning, maxLines = 1)
            if (name != null) {
                Spacer(Modifier.width(4.dp))
                Text(name, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            val note = if (call.isError) "attempted" else action.note?.takeIf { action.kind != ToolPayload.WorkerAction.Kind.ReadTranscript }
            if (note != null) {
                Spacer(Modifier.width(4.dp))
                Text("\u00B7 $note", style = type.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expandable) {
                Spacer(Modifier.width(4.dp))
                Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp).rotate(chevron))
            }
        }
        if (expandable) {
            AnimatedVisibility(visible = expanded) {
                CursorCard(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), fill = colors.fillFaint, border = Color.Transparent) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        body?.let {
                            if (action.kind == ToolPayload.WorkerAction.Kind.ReadTranscript) {
                                Text(it, style = type.code, color = colors.textSecondary, maxLines = 40, overflow = TextOverflow.Ellipsis)
                            } else {
                                MarkdownText(it, style = type.base, color = colors.textSecondary)
                            }
                        }
                        if (action.truncated) Text("\u2026 cut short by the stream", style = type.small, color = colors.textQuaternary)
                        if (open != null) {
                            Text("Open agent", style = type.small, color = colors.link, modifier = Modifier.pressable(open, CursorTheme.shapes.base).padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The coordinator speaking to the user (`send_to_user`), in a voice of its own: not a reply's bare prose and not the
 * user's bubble, but a message set behind a coloured rule with "Coordinator" over it, so a Project's transcript
 * tells the coordinator's words from a worker's report and from the agent's own thinking aloud. Press and hold for
 * the same actions as any message.
 */
@Composable
internal fun CoordinatorMessageView(call: ToolCall, payload: ToolPayload.CoordinatorMessage, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val accent = colors.blue
    MessageActions(text = payload.message, modifier = modifier.fillMaxWidth().clip(CursorTheme.shapes.lg)) {
        // The rule is drawn, not laid out: the message's blocks measure by subcomposition, which an intrinsic
        // height cannot read, so a bar sized to the column's height is painted behind it instead.
        val bar = accent.copy(alpha = 0.7f)
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("coordinator-message")
                .drawBehind {
                    val width = 2.dp.toPx()
                    drawRoundRect(bar, size = Size(width, size.height), cornerRadius = CornerRadius(width, width))
                }
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CursorIcons.Layers, null, tint = accent, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text("Coordinator", style = type.small.copy(letterSpacing = 0.2.sp), color = accent, maxLines = 1)
                if (call.isRunning) {
                    Spacer(Modifier.width(6.dp))
                    ShimmerText("sending", style = type.small, color = colors.textQuaternary, active = true, maxLines = 1)
                }
            }
            MarkdownText(payload.message, style = type.message, color = colors.textPrimary)
        }
    }
}
