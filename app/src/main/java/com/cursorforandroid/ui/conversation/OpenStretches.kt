package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.StretchSteps
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * Which stretches of a chat the reader has opened, held by the screen rather than by each stretch's row: an open
 * stretch's steps are rows of the list's own (see [StretchSteps]), so the list has to know which are open to list
 * them. A stretch is closed until opened; a lone thought is open while it is being written, until the reader says
 * otherwise, as `ThoughtDisclosure` always had it. Kept across configuration changes and process death, per chat.
 */
@Stable
internal class OpenStretches(saved: Map<String, Boolean> = emptyMap()) {
    private val toggled = mutableStateMapOf<String, Boolean>().apply { putAll(saved) }

    /** What [stretch] shows below its line: nothing, its steps, or a lone thought's text. */
    fun of(stretch: TranscriptRow.Stretch): StretchSteps.Open {
        val single = stretch.single
        return when {
            single == null -> if (toggled[stretch.key] == true) StretchSteps.Open.Steps else StretchSteps.Open.Closed
            single is TranscriptRow.Entry.Thought -> if (isThoughtOpen(stretch.key, single)) StretchSteps.Open.Thought else StretchSteps.Open.Closed
            else -> StretchSteps.Open.Closed
        }
    }

    fun isOpen(stretchKey: String): Boolean = toggled[stretchKey] == true

    fun setOpen(stretchKey: String, open: Boolean) { toggled[stretchKey] = open }

    /** A lone thought's own toggle, apart from the stretch's: a thought opened is not a stretch opened when its first call lands. */
    fun isThoughtOpen(stretchKey: String, entry: TranscriptRow.Entry.Thought): Boolean =
        entry.block.text.isNotBlank() && (toggled[THOUGHT + stretchKey] ?: entry.block.isStreaming)

    fun setThoughtOpen(stretchKey: String, open: Boolean) { toggled[THOUGHT + stretchKey] = open }

    companion object {
        private const val THOUGHT = "thought:"

        val Saver: Saver<OpenStretches, Any> = Saver(
            save = { open -> ArrayList(open.toggled.entries.map { "${if (it.value) 1 else 0}${it.key}" }) },
            restore = { saved -> OpenStretches((saved as List<*>).filterIsInstance<String>().associate { it.substring(1) to (it[0] == '1') }) },
        )
    }
}

/** The screen's [OpenStretches], where its list lists open stretches' steps; null where rows are drawn in a plain column, each stretch then opening in place. */
internal val LocalOpenStretches = staticCompositionLocalOf<OpenStretches?> { null }

@Composable
internal fun rememberOpenStretches(agentId: String): OpenStretches = rememberSaveable(agentId, saver = OpenStretches.Saver) { OpenStretches() }

/** A row's content type in the transcript's list: a thought's piece and a run of steps share nothing but their class. */
internal fun transcriptContentType(row: TranscriptRow): Any = when {
    row !is TranscriptRow.Step -> row::class
    row.text != null -> STEP_TEXT
    else -> STEP_ENTRIES
}

private const val STEP_TEXT = "step-text"
private const val STEP_ENTRIES = "step-entries"

/** The space the transcript's list keeps between two of its items. */
internal val TranscriptItemSpacing = 10.dp

/**
 * Steps of an open stretch as a row of their own (see [TranscriptRow.Step]): pulled up into the list's spacing so they
 * sit [TranscriptRow.Step.gapAbove] below the row above, as they did in the stretch's column, and a thought's piece
 * drawn as the thought was, without trimming it (a piece may start on a blank line).
 */
@Composable
internal fun StepRow(step: TranscriptRow.Step, modifier: Modifier = Modifier) {
    Box(modifier.gapAbove(step.gapAbove.dp).padding(bottom = step.endPad.dp).padding(top = step.padTop.dp, bottom = step.padBottom.dp)) {
        val text = step.text
        if (text != null) {
            SideEffect { TranscriptPerf.focused?.stepComposed() }
            Text(text, style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(StretchSteps.GAP.dp)) {
                for (entry in step.entries) key(entry.key) { EntryView(entry) }
            }
        }
    }
}

/**
 * The steps of a stretch opened fade in, where its column used to unfold: growing a list item by item every frame
 * lays out all of it every frame. Nothing slides, and a live stretch's steps appear as they land, as they always did.
 */
internal fun LazyItemScope.stepAppearance(row: TranscriptRow): Modifier =
    if (row is TranscriptRow.Step && !row.live) Modifier.animateItem(fadeInSpec = tween(180), placementSpec = null, fadeOutSpec = null) else Modifier

/**
 * Laid out shorter at its top, and drawn that much higher, so it sits [gap] below the row above rather than the list's
 * spacing. Taken in whole pixels of each, as the list and the stretch's column round them, so the gap is the column's.
 */
private fun Modifier.gapAbove(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = (TranscriptItemSpacing.roundToPx() - gap.roundToPx()).coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - dy) { placeable.place(0, -dy) }
}

