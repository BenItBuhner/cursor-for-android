package com.cursorforandroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Whether the composer is grown over nearly the whole height it can have — the window's, above the keyboard while there
 * is one — so a long prompt can be read and edited as a page rather than through a ten-line slot.
 *
 * The composer offers it ([ComposerBox]'s button beside "+") only while its text has run past its own height and
 * scrolls inside it, and where growing would give it at least [MinGain] more; it collapses again on its button, Back,
 * Ctrl+Shift+E, or a send. The height eases between the two over [ExpandMillis] ([fraction], 0 collapsed, 1 expanded),
 * the text, the caret and the selection staying as they are: the field is the same field throughout.
 *
 * Held by the composer, or by its owner when the owner lays out around it (the New Chat pane gives up the room above
 * it as it grows, see `PageCentring`); saved with the screen, so a rotation or a fold leaves it as it was.
 */
@Stable
class ComposerExpansion internal constructor(expanded: Boolean) {
    var expanded by mutableStateOf(expanded)
        private set

    internal val progress = Animatable(if (expanded) 1f else 0f)

    /** 0 collapsed, 1 expanded, in between while the height eases from one to the other. */
    val fraction: Float get() = progress.value

    /** The composer's height as it last stood collapsed, and the most it could grow to then; pixels. */
    internal var collapsedPx by mutableIntStateOf(0)
    internal var roomPx by mutableIntStateOf(0)

    /** The field's own height as it last stood collapsed; plain, since only the next layout reads it. */
    internal var fieldPx = 0

    fun expand() {
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun toggle() {
        expanded = !expanded
    }

    /**
     * Whether the button shows: while expanded or on the way, or while the text [overflowing] the composer's own height
     * scrolls inside it and there is room to grow into.
     */
    internal fun offered(overflowing: Boolean, minGainPx: Int): Boolean =
        expanded || progress.value > 0f || (overflowing && roomPx - collapsedPx >= minGainPx)

    companion object {
        /** The least growing must add for the composer to offer it: less, and the button would move nothing. */
        val MinGain: Dp = 48.dp

        internal val Saver = Saver<ComposerExpansion, Boolean>(save = { it.expanded }, restore = { ComposerExpansion(it) })
    }
}

/** A [ComposerExpansion] saved with the screen, easing its height whenever it is expanded or collapsed. */
@Composable
fun rememberComposerExpansion(): ComposerExpansion {
    val expansion = rememberSaveable(saver = ComposerExpansion.Saver) { ComposerExpansion(false) }
    LaunchedEffect(expansion, expansion.expanded) {
        expansion.progress.animateTo(if (expansion.expanded) 1f else 0f, tween(ExpandMillis, easing = FastOutSlowInEasing))
    }
    return expansion
}

/**
 * The composer's height as [expansion] has it. Collapsed, the composer is measured as it always was, and what it measured
 * is noted along with the room it had ([room] when the owner says, else the height its parent allows when that is
 * bounded; none in an unbounded parent, which never offers expanding). Expanding, the height runs from the collapsed one
 * ([collapsed], which follows edits made meanwhile) to the room, read on every frame so it follows the keyboard, and the
 * content is measured at exactly that height; the field takes whatever the attachments and the footer leave it.
 */
internal fun Modifier.expandableHeight(expansion: ComposerExpansion, room: (() -> Int)?, collapsed: () -> Int): Modifier =
    layout { measurable, constraints ->
        val bounded = constraints.hasBoundedHeight
        val available = room?.invoke()?.let { if (bounded) it.coerceAtMost(constraints.maxHeight) else it }
            ?: if (bounded) (constraints.maxHeight - ExpandedTopGap.roundToPx()).coerceAtLeast(0) else 0
        if (expansion.roomPx != available) expansion.roomPx = available
        val p = expansion.progress.value
        if (p == 0f) {
            val placeable = measurable.measure(constraints)
            if (expansion.collapsedPx != placeable.height) expansion.collapsedPx = placeable.height
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val from = collapsed()
            val to = maxOf(from, available)
            val height = (from + (to - from) * p).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

/**
 * What the composer would measure collapsed now: as it last stood collapsed, less what the field has given up since if
 * the text was cut below the ten lines it scrolled past. Expanding is only offered while the field is at its cap, so
 * [ComposerExpansion.fieldPx] is that cap; [contentPx] is the text's own height, [minFieldPx] the field's least.
 */
internal fun collapsedEstimate(expansion: ComposerExpansion, contentPx: Int?, minFieldPx: Int): Int {
    val cap = expansion.fieldPx
    if (contentPx == null || cap <= 0) return expansion.collapsedPx
    val field = contentPx.coerceIn(minOf(minFieldPx, cap), cap)
    return expansion.collapsedPx - (cap - field)
}

/**
 * Left above a composer expanded into its parent's height: under a chat's header, or under the status bar where a wide
 * pane's header has given its band back, the composer never quite meets the edge.
 */
internal val ExpandedTopGap = 8.dp

/** The composer's height easing between collapsed and expanded. */
internal const val ExpandMillis = 280

/** The field's line cap collapsed, past which it scrolls. */
internal const val CollapsedMaxLines = 10
