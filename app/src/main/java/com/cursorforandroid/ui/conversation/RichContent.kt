package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.ToolTruncation
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.ShimmerText
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * What a tool call opens onto beyond its output: the diff of an edit, the text of a file it read or wrote, the
 * subagent it delegated to, the question it asked and the answer it got. Pictures and recordings are not here —
 * they sit under the group whether or not it is open ([GroupMediaStrip]) — nor is a question still waiting, which
 * is a card of its own ([PendingQuestionCard]).
 */
@Composable
internal fun ToolPayloadView(call: ToolCall, modifier: Modifier = Modifier) {
    when (val payload = call.payload) {
        is ToolPayload.FileDiff -> DiffBlock(payload, modifier)
        is ToolPayload.FileContent -> FileCard(payload, modifier)
        is ToolPayload.Subagent -> SubagentCard(payload, modifier)
        is ToolPayload.Question -> if (call.pendingQuestion == null) QuestionCard(payload, pending = false, modifier = modifier)
        is ToolPayload.GeneratedImage, is ToolPayload.Recording, null -> Unit
    }
}

/** Whether the line opens onto a [ToolPayloadView]: text payloads only, and a question once it has been answered. */
internal fun ToolCall.hasExpandablePayload(): Boolean = when (payload) {
    is ToolPayload.FileDiff, is ToolPayload.FileContent, is ToolPayload.Subagent -> true
    is ToolPayload.Question -> pendingQuestion == null
    is ToolPayload.GeneratedImage, is ToolPayload.Recording, null -> false
}

/**
 * The stream left the call's arguments or result out for size: said in a line rather than left to look like a call
 * that produced nothing.
 */
@Composable
internal fun TruncationNote(truncation: ToolTruncation, modifier: Modifier = Modifier) {
    val what = when {
        truncation.args && truncation.result -> "The input and output were too large for the stream"
        truncation.result -> "The output was too large for the stream"
        else -> "The input was too large for the stream"
    }
    Text(what, style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary, modifier = modifier)
}

/**
 * A unified diff the way the desktop's inline diff shows one: the file's name and its `+12 -3` on top, then each
 * line on the git colours — additions on a green wash, removals on a red one, hunk headers dimmed — in monospace,
 * scrolling sideways rather than wrapping, so the columns line up. A diff the stream cut short says so at the end.
 */
@Composable
fun DiffBlock(diff: ToolPayload.FileDiff, modifier: Modifier = Modifier, showHeader: Boolean = true) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val lines = remember(diff) { diff.lines.filterNot { it.startsWith("\\ No newline") } }
    CursorCard(modifier.fillMaxWidth().testTag("diff-block"), fill = colors.fillFaint, border = Color.Transparent) {
        if (showHeader) {
            PayloadHeader(icon = CursorIcons.Code, title = ToolNames.basename(diff.path), detail = diff.path.takeIf { it != ToolNames.basename(diff.path) }) {
                LineCounts(diff.linesAdded, diff.linesRemoved)
            }
            HairlineDivider()
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            lines.forEach { line ->
                val (fill, tint) = when {
                    line.startsWith("+++") || line.startsWith("---") -> Color.Transparent to colors.textQuaternary
                    line.startsWith("@@") -> Color.Transparent to colors.blue
                    line.startsWith("+") -> colors.gitAdded.copy(alpha = 0.14f) to colors.gitAdded
                    line.startsWith("-") -> colors.gitRemoved.copy(alpha = 0.14f) to colors.gitRemoved
                    line.startsWith("diff ") || line.startsWith("index ") -> Color.Transparent to colors.textQuaternary
                    else -> Color.Transparent to colors.textSecondary
                }
                Text(
                    line.ifEmpty { " " },
                    style = type.code,
                    color = tint,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(fill).padding(horizontal = 10.dp),
                )
            }
            if (diff.truncated) Text("… diff cut short by the stream", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/**
 * The text of a file the agent read or wrote, as a card: the file's name, how many lines, and the text with line
 * numbers in monospace. Long files show their head, with a row that opens the rest, so a read of a thousand lines
 * does not stretch the transcript by a thousand rows unasked.
 */
@Composable
fun FileCard(file: ToolPayload.FileContent, modifier: Modifier = Modifier, showHeader: Boolean = true, initialLines: Int = FileCardInitialLines) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val lines = remember(file) { file.content.replace("\r\n", "\n").lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    var showAll by rememberSaveable(file.path, file.content.length) { mutableStateOf(lines.size <= initialLines) }
    val shown = if (showAll) lines else lines.take(initialLines)
    val gutter = lines.size.toString().length
    CursorCard(modifier.fillMaxWidth().testTag("file-card"), fill = colors.fillFaint, border = Color.Transparent) {
        if (showHeader) {
            val verb = if (file.kind == ToolPayload.FileContent.Kind.Written) "Wrote" else "Read"
            val count = file.totalLines ?: lines.size
            PayloadHeader(icon = CursorIcons.File, title = ToolNames.basename(file.path), detail = file.path.takeIf { it != ToolNames.basename(file.path) }) {
                Text("$verb · $count ${if (count == 1) "line" else "lines"}", style = type.small, color = colors.textQuaternary, maxLines = 1)
            }
            HairlineDivider()
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            shown.forEachIndexed { index, line ->
                Row(Modifier.padding(horizontal = 10.dp)) {
                    Text((index + 1).toString().padStart(gutter), style = type.code, color = colors.textQuaternary, softWrap = false)
                    Spacer(Modifier.width(12.dp))
                    Text(line.ifEmpty { " " }, style = type.code, color = colors.textSecondary, softWrap = false)
                }
            }
        }
        if (!showAll) {
            HairlineDivider()
            Text(
                "Show ${lines.size - shown.size} more lines",
                style = type.small,
                color = colors.link,
                modifier = Modifier.fillMaxWidth().pressable({ showAll = true }, CursorTheme.shapes.base).padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        if (file.truncated) {
            HairlineDivider()
            Text("… file cut short by the stream", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** A subagent the agent delegated to: what it was asked, how long it took, and where its transcript was written. */
@Composable
fun SubagentCard(subagent: ToolPayload.Subagent, modifier: Modifier = Modifier, onOpenAgent: ((String) -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorCard(modifier.fillMaxWidth().testTag("subagent-card"), fill = colors.fillFaint, border = Color.Transparent) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CursorIcons.Sparkle, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(subagent.description ?: "Subagent", style = type.baseMedium, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val facts = listOfNotNull(
                subagent.subagentType?.let { "$it subagent" },
                TimeFormat.duration(subagent.durationMs)?.let { "ran $it" },
                if (subagent.isBackground) "in the background" else null,
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = type.small, color = colors.textQuaternary)
            subagent.transcriptPath?.let { path ->
                Text("Transcript: $path", style = type.code, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val agentId = subagent.agentId
            if (agentId != null && subagent.isCloudAgent && onOpenAgent != null) {
                Text("Open agent", style = type.small, color = colors.link, modifier = Modifier.pressable({ onOpenAgent(agentId) }, CursorTheme.shapes.base).padding(vertical = 2.dp))
            }
        }
    }
}

/**
 * The questions an agent asked, with their options as chips, and — once answered — what was picked. While
 * [pending], the run is paused on it: the card says so. With [onAnswer] (Extended mode, where
 * `SubmitInteractionResponseBackgroundComposer` can carry the answer) the chips are choices — one per question, or any
 * number where the question allows it — with a line for an answer of one's own under each, and a button that sends
 * the lot; [answering] while the send is out, [answered] once the account took it and until the stream shows the call
 * finished. Without it the card is read-only and says that answering happens in Cursor's own clients.
 */
@Composable
fun QuestionCard(
    question: ToolPayload.Question,
    pending: Boolean,
    modifier: Modifier = Modifier,
    onOpenInBrowser: (() -> Unit)? = null,
    onAnswer: ((List<ToolPayload.Question.Answer>) -> Unit)? = null,
    answered: Boolean = false,
    answering: Boolean = false,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val interactive = pending && onAnswer != null && !answered
    // What has been picked and typed so far, per question, kept across recompositions for as long as the card is up.
    val picks = remember(question.questions) { mutableStateMapOf<String, List<String>>() }
    val typed = remember(question.questions) { mutableStateMapOf<String, String>() }
    val complete = question.questions.all { item -> picks[item.id].orEmpty().isNotEmpty() || typed[item.id].orEmpty().isNotBlank() }
    CursorCard(modifier.fillMaxWidth().testTag("question-card"), fill = colors.fillFaint, border = if (pending) colors.orange.copy(alpha = 0.5f) else Color.Transparent) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CursorIcons.Bell, null, tint = if (pending) colors.orange else colors.iconTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                ShimmerText(
                    when {
                        answered && pending -> "Answer sent"
                        pending -> "Waiting for your answer"
                        else -> "Asked"
                    },
                    style = type.baseMedium,
                    color = colors.textSecondary,
                    active = pending && !answered,
                    modifier = Modifier.semantics { heading() },
                )
                question.title?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = type.base, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            question.questions.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.prompt, style = type.message, color = colors.textPrimary)
                    val answer = question.answerFor(item)
                    val chosen = picks[item.id].orEmpty()
                    if (item.options.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.options.forEach { option ->
                                val picked = if (interactive) option.id in chosen else answer != null && (answer == option.label || answer.split(", ").contains(option.label))
                                OptionChip(
                                    option.label,
                                    picked = picked,
                                    onClick = if (!interactive) null else {
                                        {
                                            picks[item.id] = when {
                                                option.id in chosen -> chosen - option.id
                                                item.allowMultiple -> chosen + option.id
                                                else -> listOf(option.id)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (interactive) {
                        AnswerField(
                            value = typed[item.id].orEmpty(),
                            onValueChange = { typed[item.id] = it },
                            placeholder = if (item.options.isEmpty()) "Type your answer…" else "Or type an answer…",
                            enabled = !answering,
                        )
                    }
                    if (answer != null && item.options.none { it.label == answer }) {
                        Text("Answer: $answer", style = type.base, color = colors.textSecondary)
                    }
                    if (item.allowMultiple && answer == null) Text("Pick any number", style = type.tiny, color = colors.textQuaternary)
                }
            }
            if (pending) {
                HairlineDivider()
                when {
                    answered -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(CursorIcons.Check, null, tint = colors.green, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sent; the agent picks it up now.", style = type.small, color = colors.textTertiary)
                    }
                    interactive -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (question.questions.size > 1) "One answer per question." else "Pick one, or type your own.",
                            style = type.small,
                            color = colors.textQuaternary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        CursorButton(
                            if (answering) "Sending…" else "Send answer",
                            primary = true,
                            enabled = complete && !answering,
                            height = 28.dp,
                            onClick = {
                                onAnswer?.invoke(
                                    question.questions.map { item ->
                                        ToolPayload.Question.Answer(item.id, picks[item.id].orEmpty(), typed[item.id]?.trim()?.takeIf { it.isNotEmpty() })
                                    },
                                )
                            },
                            modifier = Modifier.testTag("send-answer"),
                        )
                    }
                    else -> {
                        if (onOpenInBrowser != null) {
                            Text(
                                "Answer on cursor.com",
                                style = type.baseMedium,
                                color = colors.link,
                                modifier = Modifier.pressable(onOpenInBrowser, CursorTheme.shapes.base).padding(vertical = 2.dp),
                            )
                        }
                        Text("Answering needs Cursor's own app or Extended mode; the run waits until then.", style = type.small, color = colors.textQuaternary)
                    }
                }
            }
        }
    }
}

/** A choice on offer: read-only when [onClick] is null, else a toggle worn in the accent while [picked]. */
@Composable
private fun OptionChip(label: String, picked: Boolean, onClick: (() -> Unit)? = null) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.full
    Text(
        label,
        style = CursorTheme.typography.small,
        color = if (picked) colors.onAccent else colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 220.dp)
            .cursorSurface(if (picked) colors.accent else colors.fill, if (picked) Color.Transparent else colors.stroke, shape)
            .then(if (onClick != null) Modifier.pressable(onClick, shape).semantics { selected = picked } else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("option-chip"),
    )
}

/** The line for an answer in one's own words under a question's chips. */
@Composable
private fun AnswerField(value: String, onValueChange: (String) -> Unit, placeholder: String, enabled: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = type.base.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.textPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .cursorSurface(colors.fill, colors.strokeSubtle, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics { contentDescription = placeholder }
            .testTag("answer-field"),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary); inner() } },
    )
}

/**
 * The pictures and recordings a group's calls produced, one under the other, shown whether or not the group is
 * open: an image the agent made is the point of the step, not a detail of it. Each opens like a reply's figure —
 * the image into the lightbox, the recording into the player.
 */
@Composable
internal fun GroupMediaStrip(group: ActivityGroup, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth().testTag("media-strip"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        group.media.forEach { call ->
            when (val payload = call.payload) {
                is ToolPayload.GeneratedImage -> {
                    val src = payload.src ?: return@forEach
                    Column(Modifier.widthIn(max = 320.dp)) {
                        ImageBlock(src, alt = payload.description ?: "Generated image")
                        val caption = listOfNotNull(payload.path?.let(ToolNames::basename), payload.description).firstOrNull()
                        caption?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                is ToolPayload.Recording -> {
                    Column(Modifier.widthIn(max = 320.dp)) {
                        VideoBlock(payload.path, poster = null)
                        val caption = listOfNotNull(ToolNames.basename(payload.path), TimeFormat.duration(payload.durationMs)).joinToString(" · ")
                        Text(caption, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * The question a group is paused on, as its own card under the group: answerable from here when the transcript's
 * controls ([LocalTranscriptControls]) can carry the answer, read-only otherwise.
 */
@Composable
internal fun PendingQuestionCard(call: ToolCall, modifier: Modifier = Modifier, onOpenInBrowser: (() -> Unit)? = null) {
    val question = call.pendingQuestion ?: return
    val controls = LocalTranscriptControls.current
    QuestionCard(
        question,
        pending = true,
        modifier = modifier,
        onOpenInBrowser = onOpenInBrowser,
        onAnswer = controls.onAnswer?.let { answer -> { answers -> answer(call.callId, answers) } },
        answered = call.callId in controls.state.answeredCallIds,
        answering = controls.state.isBusy("answer:${call.callId}"),
    )
}

/** The first row of a diff or file card: a glyph, the file's name, its path dimmed after it, and [trailing] at the end. */
@Composable
private fun PayloadHeader(icon: ImageVector, title: String, detail: String?, trailing: @Composable () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, style = type.baseMedium.copy(fontFamily = type.code.fontFamily), color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Spacer(Modifier.width(6.dp))
            Text(detail, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Box { trailing() }
    }
}

/** "+12 -3" in the git colours; nothing when neither side is known. */
@Composable
internal fun LineCounts(added: Int?, removed: Int?) {
    val colors = CursorTheme.colors
    val style = CursorTheme.typography.small.copy(fontFeatureSettings = "tnum")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        added?.takeIf { it > 0 }?.let { Text("+$it", style = style, color = colors.gitAdded) }
        removed?.takeIf { it > 0 }?.let { Text("-$it", style = style, color = colors.gitRemoved) }
    }
}

/** Lines of a file shown before the rest is folded behind "Show more". */
const val FileCardInitialLines = 60
