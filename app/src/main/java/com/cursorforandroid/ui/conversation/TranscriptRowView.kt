package com.cursorforandroid.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * One row of the transcript (see [TranscriptRow]): the messages as themselves, a coordinator's update as a reply, a
 * worker's card, a step's pictures, the question a run waits on, an injected turn as its line and a run of silent
 * ones behind one ([EventGroupView]) — and everything else between two messages as one [StretchView].
 */
@Composable
fun TranscriptRowView(row: TranscriptRow, modifier: Modifier = Modifier) {
    // Each composition of a row, first or again, counted for the chat's diagnostics (see TranscriptPerf).
    SideEffect { TranscriptPerf.focused?.rowComposed() }
    when (row) {
        is TranscriptRow.Item -> TimelineItemView(row.item, modifier)
        is TranscriptRow.Message -> CoordinatorMessageView(row.call, row.call.payload as ToolPayload.CoordinatorMessage, modifier)
        is TranscriptRow.Worker -> WorkerTaskCard(row.call, row.call.payload as ToolPayload.WorkerAction, modifier)
        is TranscriptRow.Media -> GroupMediaStrip(row.group, modifier)
        is TranscriptRow.Question -> {
            val agentId = LocalMarkdownMedia.current?.agentId
            val uriHandler = LocalUriHandler.current
            PendingQuestionCard(row.call, modifier, onOpenInBrowser = agentId?.let { id -> { uriHandler.openUri(CursorEndpoints.webUrl(id)) } })
        }
        is TranscriptRow.Stretch -> StretchView(row, modifier)
        is TranscriptRow.Event -> EventRow(row.notification, row.count, modifier)
        is TranscriptRow.Events -> EventGroupView(row, modifier)
        is TranscriptRow.Failure -> RunFailureRow(row.footer, modifier)
    }
}

/**
 * Everything the agent did between two messages behind one line — "Worked 4m · 148 events · 3 edits", shimmering
 * "Working" while the run still writes — that opens onto the sequence verbatim and in order: each thought as dimmed
 * prose, each tool call as the line it always was (opening onto its command, diff or output as before), each of a
 * coordinator's working notes as dimmed markdown, each injected turn as its line and a run of them behind one
 * ([EventGroupView]). A stretch of one step is that step, drawn as it would be alone; a note never is.
 */
@Composable
internal fun StretchView(stretch: TranscriptRow.Stretch, modifier: Modifier = Modifier) {
    stretch.single?.let { entry ->
        SingleEntry(entry, modifier)
        return
    }
    var expanded by rememberSaveable(stretch.key) { mutableStateOf(false) }
    val summary = stretch.summary
    Column(modifier.fillMaxWidth().testTag("stretch")) {
        DisclosureRow(
            action = summary.action,
            details = summary.details,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            busy = summary.busy,
            lineStats = summary.lineStats,
        )
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).testTag("stretch-steps"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Each entry owns its slot, so an opened output stays with the call it was opened on as the list grows.
                stretch.listed.forEach { entry -> key(entry.key) { EntryView(entry) } }
            }
        }
    }
}

/** One entry of an open stretch, as Cursor lists a step. */
@Composable
private fun EntryView(entry: TranscriptRow.Entry) {
    when (entry) {
        is TranscriptRow.Entry.Thought -> ThoughtText(entry.block.text, Modifier.padding(vertical = 4.dp))
        is TranscriptRow.Entry.Call -> StepLine(entry)
        is TranscriptRow.Entry.Note -> NoteText(entry)
        is TranscriptRow.Entry.Footer -> RunFooterView(entry.footer, interrupted = entry.interrupted)
        is TranscriptRow.Entry.Line -> SummaryLine(entry.row.label, entry.row.value)
        is TranscriptRow.Entry.Event -> EventRow(entry.row.notification, entry.row.count, Modifier.padding(vertical = 2.dp))
        is TranscriptRow.Entry.Events -> EventGroupView(entry.group, Modifier.padding(vertical = 2.dp))
        is TranscriptRow.Entry.Failure -> RunFailureRow(entry.footer, Modifier.padding(vertical = 2.dp))
    }
}

/** A tool call's line: a coordinator's row (a message to a worker, a status check) when it is one, else the plain line. */
@Composable
private fun StepLine(entry: TranscriptRow.Entry.Call) {
    if (!CoordinatorStep(entry.call)) ToolCallLine(entry.call)
}

/** A coordinator's working note inside an open stretch: its prose, dimmed, with a reply's press-and-hold. */
@Composable
private fun NoteText(entry: TranscriptRow.Entry.Note) {
    val text = entry.message.markdown.trim()
    if (text.isEmpty()) return
    MessageActions(text = entry.message.markdown, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("stretch-note")) {
        MarkdownText(entry.message.markdown, Modifier.fillMaxWidth(), style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary, streaming = entry.message.isStreaming)
    }
}

/** A stretch of one entry is drawn as that entry would be on its own: nothing to summarise. */
@Composable
private fun SingleEntry(entry: TranscriptRow.Entry, modifier: Modifier) {
    when (entry) {
        is TranscriptRow.Entry.Thought -> ThoughtDisclosure(entry, modifier)
        is TranscriptRow.Entry.Call -> Column(modifier.fillMaxWidth()) { StepLine(entry) }
        is TranscriptRow.Entry.Note -> BackgroundMessage(entry.message, modifier)
        is TranscriptRow.Entry.Footer -> RunFooterView(entry.footer, modifier, interrupted = entry.interrupted)
        is TranscriptRow.Entry.Line -> SummaryLine(entry.row.label, entry.row.value, modifier)
        is TranscriptRow.Entry.Event -> EventRow(entry.row.notification, entry.row.count, modifier)
        // Never alone: a run of events is behind the stretch's own summary (see [TranscriptRow.Stretch.single]).
        is TranscriptRow.Entry.Events -> EventGroupView(entry.group, modifier)
        // A failed run with nothing else in its stretch: its line, the footer's duration in it (see [TranscriptRow.Stretch.single]).
        is TranscriptRow.Entry.Failure -> RunFailureRow(entry.footer, modifier)
    }
}

/** A lone thought: "Thinking" while it streams, then "Thought", closed onto its text. */
@Composable
private fun ThoughtDisclosure(entry: TranscriptRow.Entry.Thought, modifier: Modifier) {
    var toggled by rememberSaveable(entry.key) { mutableStateOf<Boolean?>(null) }
    val text = entry.block.text.trim()
    val streaming = entry.block.isStreaming
    val expandable = text.isNotEmpty()
    val expanded = expandable && (toggled ?: streaming)
    val seconds = entry.block.durationSeconds
    Column(modifier.fillMaxWidth()) {
        DisclosureRow(
            action = if (streaming) "Thinking" else "Thought",
            details = if (streaming || seconds == null) null else if (seconds <= 0) "briefly" else "${seconds}s",
            expanded = expanded,
            onToggle = { toggled = !expanded },
            busy = streaming,
            expandable = expandable,
        )
        AnimatedVisibility(visible = expanded) {
            ThoughtText(text, Modifier.padding(top = 6.dp, bottom = 4.dp))
        }
    }
}
