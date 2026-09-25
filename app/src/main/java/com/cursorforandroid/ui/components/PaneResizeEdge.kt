package com.cursorforandroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.fastFirstOrNull
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which side of the boundary the pane a [PaneResizeEdge] resizes stands on. */
enum class PaneSide { Start, End }

/**
 * The boundary between two panes side by side — the wide window's rail and the chat, the chat and the panel pinned
 * beside it — made draggable to resize the pane on [side]. Nothing marks it: the divider already there is the edge, and
 * a strip [PaneResizeEdgeWidth] wide straddling it takes a drag across from anywhere along its length. The pane's edge
 * follows the finger from where it was when the drag began, through [onResize]; the caller clamps it to the pane's
 * range and hears the finger lift in [onResizeDone].
 *
 * Of what happens in the strip it takes only that drag, and leaves the rest to the pane under it as though the strip
 * were not there: a tap, a press, a vertical scroll of the transcript or the list. A drag that begins in one of the
 * window's back-gesture strips ([edges]) is left to back, and one cancelled from outside — the system taking it —
 * puts the pane back as the drag found it and keeps nothing: [onResizeCancelled], with the width it started from.
 *
 * A mouse over the strip shows the resize cursor and, pressed there, keeps it until the button comes up; its drag starts
 * at the first move across. The divider is lit only while a drag is under way, and TalkBack widens or narrows the pane
 * by a step through its actions.
 *
 * A finger's drag is felt once where the pane comes up against the end of its range and stops following ([Haptic.SlotTick]),
 * however far on the finger pushes, and felt there again only once the pane has come back off that end by [StopRearm]. A
 * mouse's drag and TalkBack's steps are not felt: the hand is not on the screen.
 */
@Composable
fun PaneResizeEdge(
    side: PaneSide,
    paneWidth: () -> Dp,
    onResize: (Dp) -> Unit,
    onResizeDone: () -> Unit,
    contentDescription: String,
    edges: BackGestureEdges?,
    modifier: Modifier = Modifier,
    onResizeCancelled: (from: Dp) -> Unit = onResize,
) {
    val highlight = CursorTheme.colors.accent
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // A drag toward the end widens a pane on the start side and narrows one on the end side; RTL mirrors the screen.
    val widening = (if (side == PaneSide.Start) 1f else -1f) * (if (rtl) -1f else 1f)
    val width by rememberUpdatedState(paneWidth)
    val resize by rememberUpdatedState(onResize)
    val done by rememberUpdatedState(onResizeDone)
    val cancelled by rememberUpdatedState(onResizeCancelled)
    var dragging by remember { mutableStateOf(false) }
    var mouseHeld by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (dragging) 1f else 0f, tween(if (dragging) LightMillis else DimMillis), label = "paneEdgeLit")
    val drag = remember { DragFromStart() }
    val haptics = rememberHaptics()
    val reach = LocalConfiguration.current.screenWidthDp.dp
    Box(
        modifier
            .fillMaxHeight()
            .width(PaneResizeEdgeWidth)
            .drawBehind {
                if (lit > 0f) {
                    val line = HighlightWidth.toPx()
                    drawRect(
                        highlight.copy(alpha = HighlightAlpha * lit),
                        topLeft = Offset((size.width - line) / 2f, 0f),
                        size = Size(line, size.height),
                    )
                }
            }
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = "${width().value.roundToInt()} dp wide"
                customActions = listOf(
                    CustomAccessibilityAction(WIDEN) { resize(width() + AccessibilityStep); done(); true },
                    CustomAccessibilityAction(NARROW) { resize(width() - AccessibilityStep); done(); true },
                )
            }
            // While a mouse holds the strip, what hears the pointer from here in reaches a window's width either side, so
            // the mouse is over the strip wherever it goes until the button comes up: the cursor stays the resize cursor
            // however far one move outruns the edge, and on past where the pane stops. What is drawn and what TalkBack
            // reads stay the strip.
            .layout { measurable, constraints ->
                val extra = if (mouseHeld) reach.roundToPx() else 0
                val placeable = measurable.measure(constraints.offset(horizontal = 2 * extra))
                layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
            }
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .dragAcross(
                edges = edges,
                onMouseHeld = { mouseHeld = it },
                onStart = { mouse ->
                    dragging = true
                    drag.start(width(), felt = !mouse)
                },
                onDrag = { dx ->
                    val asked = drag.by(with(density) { (widening * dx).toDp() })
                    resize(asked)
                    if (drag.stopped(asked, width())) haptics.perform(Haptic.SlotTick)
                },
                onStop = {
                    dragging = false
                    done()
                },
                onCancel = {
                    dragging = false
                    cancelled(drag.from)
                },
            ),
    )
}

/**
 * The width a drag started from and how far it has gone since, so the edge tracks the finger rather than the clamp; and
 * where the clamp last stopped the pane, so that stop is felt once rather than with every move that pushes on past it.
 */
private class DragFromStart {
    var from = 0.dp
        private set
    private var travel = 0.dp
    private var felt = false

    /** The width the pane stopped at, until it has come back off it by [StopRearm]. */
    private var stoppedAt: Dp? = null

    fun start(width: Dp, felt: Boolean) {
        from = width
        travel = 0.dp
        this.felt = felt
        stoppedAt = null
    }

    fun by(delta: Dp): Dp {
        travel += delta
        return from + travel
    }

    /** Whether the pane, asked to be [asked] wide and held at [width], has just come up against the end of its range. */
    fun stopped(asked: Dp, width: Dp): Boolean {
        stoppedAt?.let { if (abs((width - it).value) < StopRearm.value) return false }
        stoppedAt = null
        if (abs((asked - width).value) < StopTolerance.value) return false
        stoppedAt = width
        return felt
    }
}

/**
 * A drag across the strip, heard before the panes under it hear anything: hit testing stops at the first sibling it
 * reaches unless that one shares, and this one does, so the panes go on hearing every event; and it listens on the
 * initial pass, so a drag across is its own once past touch slop, consumed before something under the strip that
 * scrolls sideways — a code view running to the panel's edge — would take it. It consumes nothing before then, and
 * gives the gesture up at once if it crosses touch slop no further across than up or down. [onMouseHeld] hears a mouse
 * button go down on the strip (true) and that gesture end (false), whether or not it became a drag; [onStart] hears
 * whether a mouse is the one dragging.
 */
private fun Modifier.dragAcross(
    edges: BackGestureEdges?,
    onMouseHeld: (Boolean) -> Unit,
    onStart: (mouse: Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
): Modifier = this then DragAcrossElement(edges, onMouseHeld, onStart, onDrag, onStop, onCancel)

private data class DragAcrossElement(
    val edges: BackGestureEdges?,
    val onMouseHeld: (Boolean) -> Unit,
    val onStart: (mouse: Boolean) -> Unit,
    val onDrag: (Float) -> Unit,
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
) : ModifierNodeElement<DragAcrossNode>() {
    override fun create() = DragAcrossNode(edges, onMouseHeld, onStart, onDrag, onStop, onCancel)

    override fun update(node: DragAcrossNode) {
        node.edges = edges
        node.onMouseHeld = onMouseHeld
        node.onStart = onStart
        node.onDrag = onDrag
        node.onStop = onStop
        node.onCancel = onCancel
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "dragAcross"
    }
}

private class DragAcrossNode(
    var edges: BackGestureEdges?,
    var onMouseHeld: (Boolean) -> Unit,
    var onStart: (mouse: Boolean) -> Unit,
    var onDrag: (Float) -> Unit,
    var onStop: () -> Unit,
    var onCancel: () -> Unit,
) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // An ancestor heard this down first, on the same pass: [edges] already knows whether it began in a strip.
                if (edges?.gestureStartedInEdge == true) return@awaitEachGesture
                val mouse = down.type == PointerType.Mouse
                if (mouse) onMouseHeld(true)
                try {
                    val across = awaitDragAcross(down) ?: return@awaitEachGesture
                    onStart(mouse)
                    onDrag(across)
                    if (followAcross(down.id) { onDrag(it) }) onStop() else onCancel()
                } finally {
                    if (mouse) onMouseHeld(false)
                }
            }
        },
    )

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings() = true
}

/**
 * The travel across once [down]'s pointer is past slop, consumed; null for anything else. A finger has to clear touch
 * slop further across than up or down, so a scroll that wanders sideways stays the pane's. A mouse drags nothing else
 * here — a list doesn't scroll to one — and the cursor has already said what the strip is, so its first move across is
 * the edge's, whatever it did before.
 */
private suspend fun AwaitPointerEventScope.awaitDragAcross(down: PointerInputChange): Float? {
    val mouse = down.type == PointerType.Mouse
    val slop = viewConfiguration.touchSlop * if (mouse) MouseSlopRatio else 1f
    var travel = Offset.Zero
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.fastFirstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        travel += change.positionChange()
        if (mouse) {
            if (abs(travel.x) <= slop) continue
        } else {
            if (travel.getDistance() <= slop) continue
            if (abs(travel.x) <= abs(travel.y)) return null
        }
        change.consume()
        return travel.x
    }
}

/**
 * Follows [pointer] across, a move at a time, until it lifts (true) or the gesture is cancelled from outside (false):
 * a cancel comes as a lift already consumed.
 */
private suspend fun AwaitPointerEventScope.followAcross(pointer: PointerId, onDrag: (Float) -> Unit): Boolean {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.fastFirstOrNull { it.id == pointer } ?: return false
        if (change.changedToUpIgnoreConsumed()) {
            val lifted = !change.isConsumed
            change.consume()
            return lifted
        }
        onDrag(change.positionChangeIgnoreConsumed().x)
        change.consume()
    }
}

/** The strip the edge takes over the boundary, half either side: nothing is drawn there at rest. */
val PaneResizeEdgeWidth = 16.dp

/** How much of touch slop a mouse has to clear: it holds still where a finger wobbles. */
private const val MouseSlopRatio = 0.125f / 18f

/** How far short of where it was asked to be a pane is held before it counts as stopped at the end of its range. */
private val StopTolerance = 0.5.dp

/** How far back off the end of its range a pane comes before reaching it again is felt again: more than a held finger wobbles. */
private val StopRearm = 8.dp

private val HighlightWidth = 2.dp
private const val HighlightAlpha = 0.6f
private const val LightMillis = 90
private const val DimMillis = 180
private val AccessibilityStep = 40.dp
private const val WIDEN = "Widen"
private const val NARROW = "Narrow"
