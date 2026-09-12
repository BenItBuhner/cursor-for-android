package com.cursorforandroid.ui.panel

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueueLoad
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The Queue and steering section (spec §7), Extended mode: the run's controls — pause or resume the turn, stop it,
 * wake the machine — a line to steer the turn under way without stopping it, with what the account did with the
 * last steer, and the account's queue with each message's send now, move up or down, reword, remove, and deliver as
 * a steer. What the account has not said yet, or would not say, is a named state under the caption.
 */
@Composable
internal fun QueueSection(state: PanelState, actions: PanelActions) {
    val capabilities = state.capabilities
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("queue-section")) {
        if (capabilities.steering) {
            RunControlsRow(state, actions)
            SteerRow(state, actions)
        }
        if (capabilities.accountQueue) {
            AccountQueue(state, actions)
        } else {
            RequiresExtendedRow(SectionAvailability.RequiresExtended(DefaultPanelSections.QUEUE_REASON, ready = true), extendedOn = capabilities.anyExtended)
        }
    }
}

/**
 * Pause or resume the turn (`PauseBackgroundComposer` / `ResumeBackgroundComposer`), stop it (the documented cancel)
 * and wake the machine (`WakeBackgroundComposer`); each says when it is on its way. Worn by the Overview and the
 * Queue section alike.
 */
@Composable
internal fun RunControlsRow(state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val controls = state.controls
    val running = state.isRunning
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("run-controls"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (controls.isPaused == true) {
            ControlButton("Resume", CursorIcons.Play, busy = controls.isBusy("resume"), onClick = actions::resumeRun)
        } else {
            ControlButton("Pause", CursorIcons.Pause, busy = controls.isBusy("pause"), enabled = running, onClick = actions::pauseRun)
        }
        if (running) ControlButton("Stop", CursorIcons.Stop, onClick = actions::stopRun)
        ControlButton("Wake", CursorIcons.Lightning, busy = controls.isBusy("wake"), enabled = !running, onClick = actions::wake)
    }
}

@Composable
private fun ControlButton(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    CursorButton(if (busy) "$label…" else label, onClick, icon = icon, enabled = enabled && !busy, height = 28.dp, modifier = Modifier.testTag("control-$label"))
}

/** A line into the running turn: delivered at the agent's next step, with the account's word on it once it answers. */
@Composable
private fun SteerRow(state: PanelState, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var text by rememberSaveable("steer-${state.agentId}") { mutableStateOf("") }
    val running = state.isRunning
    val busy = state.controls.isBusy("steer")
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = running && !busy,
                textStyle = type.base.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .cursorSurface(colors.fillFaint, colors.strokeSubtle, CursorTheme.shapes.base)
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .semantics { contentDescription = "Steer the running turn" }
                    .testTag("steer-field"),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                if (running) "Change course without stopping the turn…" else "Steering needs a turn under way",
                                style = type.base,
                                color = colors.textQuaternary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            CursorButton(
                if (busy) "Steering…" else "Steer",
                primary = true,
                enabled = running && text.isNotBlank() && !busy,
                height = 32.dp,
                onClick = {
                    actions.steer(text)
                    text = ""
                },
                modifier = Modifier.testTag("steer-send"),
            )
        }
        val outcome = state.controls.lastSteer
        Text(
            outcome?.message ?: "Delivered into the running turn at the agent's next step; a follow-up would wait for the turn to end.",
            style = type.small,
            color = if (outcome != null) colors.textTertiary else colors.textQuaternary,
            modifier = Modifier.padding(top = 4.dp).testTag("steer-outcome"),
        )
    }
}

/** The account's queue for this chat, oldest first, with every edit the account offers on a queued message. */
@Composable
private fun AccountQueue(state: PanelState, actions: PanelActions) {
    val controls = state.controls
    val queue = controls.queue
    val load = controls.queueLoad
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelCaption(
                when {
                    queue.isEmpty() -> "Queued on your account"
                    else -> "Queued on your account · ${queue.size}"
                },
                modifier = Modifier.weight(1f),
            )
            if (load is QueueLoad.Loading) {
                SpinnerRing(size = 12.dp, modifier = Modifier.padding(end = 12.dp))
            } else {
                TouchTarget(size = 24.dp, touchSize = 36.dp, shape = CircleShape, onClick = actions::refreshQueue, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(CursorIcons.Refresh, "Refresh the queue", tint = CursorTheme.colors.iconTertiary, modifier = Modifier.size(13.dp))
                }
            }
        }
        when {
            load is QueueLoad.Unavailable -> StateRow(icon = CursorIcons.Warning, title = "The queue can't be read", detail = load.reason, tint = CursorTheme.colors.orange, actionLabel = "Retry", onAction = actions::refreshQueue, modifier = Modifier.testTag("queue-unavailable"))
            queue.isEmpty() && load is QueueLoad.Loaded -> EmptyRow("Nothing queued", if (state.isRunning) "A follow-up sent now waits here, behind the turn under way, and goes out when it ends." else "Follow-ups sent while a turn is under way wait here.")
            queue.isEmpty() -> LoadingRow("Reading the queue…")
            else -> queue.forEachIndexed { index, item ->
                QueuedRow(
                    item = item,
                    index = index,
                    count = queue.size,
                    inFlight = item.id in controls.inFlightQueueIds,
                    canSteer = state.capabilities.steering && state.isRunning,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun QueuedRow(item: PendingFollowup, index: Int, count: Int, inFlight: Boolean, canSteer: Boolean, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var editing by rememberSaveable("queue-edit-${item.id}") { mutableStateOf(false) }
    var text by rememberSaveable("queue-text-${item.id}") { mutableStateOf(item.text) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .cursorSurface(colors.fillFaint, Color.Transparent, CursorTheme.shapes.lg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "Queued on your account, ${index + 1} of $count" }
            .testTag("queued-followup"),
    ) {
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = type.base.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().testTag("queued-edit"),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                CursorButton("Cancel", { editing = false; text = item.text; actions.queueMarkEditing(item.id, false) }, height = 26.dp)
                Spacer(Modifier.width(6.dp))
                CursorButton("Save", { editing = false; actions.queueUpdate(item.id, text) }, primary = true, enabled = text.isNotBlank(), height = 26.dp, modifier = Modifier.testTag("queued-save"))
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.Top) {
            Text("${index + 1}", style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textQuaternary, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.previewText, style = type.base, color = if (inFlight) colors.textTertiary else colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val notes = listOfNotNull(
                    item.source?.let { "from ${it.name.lowercase().replace('_', ' ')}" },
                    "Being edited on another device".takeIf { item.isEditing },
                )
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inFlight) {
                Spacer(Modifier.width(8.dp))
                SpinnerRing(size = 12.dp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (!inFlight) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(CursorIcons.ArrowUp, "Move up", enabled = index > 0) { actions.queueMove(item.id, up = true) }
                Glyph(CursorIcons.ArrowDown, "Move down", enabled = index < count - 1) { actions.queueMove(item.id, up = false) }
                Glyph(CursorIcons.Pencil, "Edit queued follow-up") { text = item.text; editing = true; actions.queueMarkEditing(item.id, true) }
                Glyph(CursorIcons.Trash, "Remove queued follow-up") { actions.queueDelete(item.id) }
                Spacer(Modifier.weight(1f))
                if (canSteer) {
                    CursorButton("Steer now", { actions.queueSteerNow(item.id) }, height = 26.dp, modifier = Modifier.testTag("queued-steer"))
                    Spacer(Modifier.width(6.dp))
                }
                CursorButton("Send now", { actions.queueSendNow(item.id) }, primary = true, height = 26.dp, modifier = Modifier.testTag("queued-send-now"))
            }
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    TouchTarget(size = 26.dp, touchSize = 36.dp, shape = CircleShape, onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription, tint = if (enabled) colors.iconTertiary else colors.iconQuaternary.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
    }
}

/** The hint beside "Queue and steering" while the section is closed. */
internal fun queueHint(state: PanelState): String? {
    if (!state.capabilities.accountQueue && !state.capabilities.steering) return null
    val queued = state.controls.queue.size
    return listOfNotNull(
        "Paused".takeIf { state.controls.isPaused == true },
        queued.takeIf { it > 0 }?.let { "$it queued" },
    ).joinToString(" · ").ifEmpty { null }
}
