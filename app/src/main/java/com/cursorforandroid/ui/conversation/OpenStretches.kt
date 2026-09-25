package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.StretchSteps
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            single is TranscriptRow.Entry.Thought -> if (thoughtListed(stretch.key, single)) StretchSteps.Open.Thought else StretchSteps.Open.Closed
            else -> StretchSteps.Open.Closed
        }
    }

    /** Whether [stretchKey] reads as open: closed as soon as it is closing, while its steps are still fading. */
    fun isOpen(stretchKey: String): Boolean = toggled[stretchKey] == true && leaving != stretchKey

    /** Asks for [stretchKey] opened or closed, done on the next frame (see [run]). */
    fun setOpen(stretchKey: String, open: Boolean) {
        asked.trySend(stretchKey to open)
    }

    /** A lone thought's own toggle, apart from the stretch's: a thought opened is not a stretch opened when its first call lands. */
    fun isThoughtOpen(stretchKey: String, entry: TranscriptRow.Entry.Thought): Boolean =
        thoughtListed(stretchKey, entry) && leaving != THOUGHT + stretchKey

    private fun thoughtListed(stretchKey: String, entry: TranscriptRow.Entry.Thought): Boolean =
        entry.block.text.isNotBlank() && (toggled[THOUGHT + stretchKey] ?: entry.block.isStreaming)

    fun setThoughtOpen(stretchKey: String, open: Boolean) {
        asked.trySend(THOUGHT + stretchKey to open)
    }

    /**
     * Whether the reader has just opened or closed something: for [MOTION_WINDOW_MS] after, the list's rows slide to
     * where the change puts them (see [rowMotion]). Only then, so rows landing from the stream or a page never move.
     */
    var moving by mutableStateOf(false)
        private set

    /** What is being closed, its steps fading out where they are before they go (see [isLeaving]); else null. */
    private var leaving by mutableStateOf<String?>(null)

    /** Whether [step] is one of those fading out. */
    fun isLeaving(step: TranscriptRow.Step): Boolean {
        val closing = leaving ?: return false
        return if (closing.startsWith(THOUGHT)) step.text != null && step.stretchKey == closing.substring(THOUGHT.length) else step.stretchKey == closing
    }

    private val asked = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)

    /**
     * Opens and closes what is asked, two frames after the tap. A tap that opens also stops the list following (see
     * [TranscriptScroll.toggling]): its frame lays the rows out in their new order where they were, nothing sliding,
     * or they would all slide from where the other order had them. The next gives the rows their slide ([moving]) —
     * the list follows only rows that had one on the frame before — and the one after opens. A close first fades the
     * steps out where they are, [STEP_FADE_OUT_MS], and only then takes them away: rows coming into view as they go
     * start wherever the list puts them, and must not be drawn over steps still showing. Ends each window
     * [MOTION_WINDOW_MS] after the last change in it. Runs for as long as the caller does.
     */
    suspend fun run() = coroutineScope {
        var settling: Job? = null
        for ((key, open) in asked) {
            settling?.cancel()
            withFrameNanos { }
            withFrameNanos {
                moving = true
                if (!open) leaving = key
            }
            if (!open) delay(STEP_FADE_OUT_MS.toLong())
            withFrameNanos {
                toggled[key] = open
                leaving = null
            }
            settling = launch {
                delay(MOTION_WINDOW_MS)
                moving = false
            }
        }
    }

    /** The list's tallest height in the current window (see [holdingHeight]). */
    internal var heldHeight = 0

    companion object {
        private const val THOUGHT = "thought:"
        const val MOTION_WINDOW_MS = 400L

        val Saver: Saver<OpenStretches, Any> = Saver(
            save = { open -> ArrayList(open.toggled.entries.map { "${if (it.value) 1 else 0}${it.key}" }) },
            restore = { saved -> OpenStretches((saved as List<*>).filterIsInstance<String>().associate { it.substring(1) to (it[0] == '1') }) },
        )
    }
}

/** The screen's [OpenStretches], where its list lists open stretches' steps; null where rows are drawn in a plain column, each stretch then opening in place. */
internal val LocalOpenStretches = staticCompositionLocalOf<OpenStretches?> { null }

/**
 * Keeps the list no shorter than it has been for as long as [open]'s rows move ([OpenStretches.moving]), top-down
 * ([topDown]) only. A short transcript's list is as tall as its rows, so a close would shrink it at once and cut off
 * the rows still sliding up into the space; the height kept is empty space below them. Bottom-anchored, the extra
 * height would move every row down instead.
 */
internal fun Modifier.holdingHeight(open: OpenStretches, topDown: Boolean): Modifier = layout { measurable, constraints ->
    val holding = topDown && open.moving
    val min = if (holding) open.heldHeight.coerceIn(constraints.minHeight, constraints.maxHeight) else constraints.minHeight
    val placeable = measurable.measure(constraints.copy(minHeight = min))
    open.heldHeight = if (holding) maxOf(open.heldHeight, placeable.height) else placeable.height
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
internal fun rememberOpenStretches(agentId: String): OpenStretches {
    val open = rememberSaveable(agentId, saver = OpenStretches.Saver) { OpenStretches() }
    LaunchedEffect(open) { open.run() }
    return open
}

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
 * How a row of the transcript's list moves. An open stretch's steps are items of their own, so it cannot unfold as one
 * column did; instead, for a moment after the reader opens or closes one ([OpenStretches.moving]), the rows on screen
 * slide to their new places — those below an opened stretch down past its steps, which fade in once the rows have
 * mostly gone by; on closing, the steps fade out where they are (see [OpenStretches.run]) and the rows then slide back
 * up. Neither passes over a step half drawn. The list animates only the items it has composed, so this costs the same
 * for a stretch of five steps as of five hundred. Outside that moment nothing slides, and a live stretch's steps
 * appear as they land.
 *
 * Every row carries the modifier, its placement off outside the moment: a placement that is only a snap still moves a
 * row a frame late, and rows landing from the stream must not lag.
 */
@Composable
internal fun LazyItemScope.rowMotion(row: TranscriptRow, open: OpenStretches): Modifier {
    val moving = open.moving
    val motion = Modifier.animateItem(
        fadeInSpec = when {
            row !is TranscriptRow.Step || row.live -> null
            moving -> tween(STEP_FADE_IN_MS, delayMillis = STEP_FADE_IN_DELAY_MS)
            else -> tween(STEP_FADE_IN_MS)
        },
        placementSpec = if (moving) tween(ROW_SLIDE_MS, easing = FastOutSlowInEasing) else null,
        fadeOutSpec = null,
    )
    if (row !is TranscriptRow.Step) return motion
    val shown by animateFloatAsState(if (open.isLeaving(row)) 0f else 1f, tween(STEP_FADE_OUT_MS), label = "step")
    return motion.graphicsLayer { alpha = shown }
}

private const val STEP_FADE_IN_MS = 150
private const val STEP_FADE_IN_DELAY_MS = 150
private const val STEP_FADE_OUT_MS = 100
private const val ROW_SLIDE_MS = 250

/**
 * Laid out shorter at its top, and drawn that much higher, so it sits [gap] below the row above rather than the list's
 * spacing. Taken in whole pixels of each, as the list and the stretch's column round them, so the gap is the column's.
 */
private fun Modifier.gapAbove(gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = (TranscriptItemSpacing.roundToPx() - gap.roundToPx()).coerceIn(0, placeable.height)
    layout(placeable.width, placeable.height - dy) { placeable.place(0, -dy) }
}

