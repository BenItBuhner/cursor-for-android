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
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
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
 * puts the pane back as the drag found it and keeps nothing.
 *
 * A mouse over the strip shows the resize cursor, the divider is lit only while a drag is under way, and TalkBack widens
 * or narrows the pane by a step through its actions.
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
) {
    val highlight = CursorTheme.colors.accent
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // A drag toward the end widens a pane on the start side and narrows one on the end side; RTL mirrors the screen.
    val widening = (if (side == PaneSide.Start) 1f else -1f) * (if (rtl) -1f else 1f)
    val width by rememberUpdatedState(paneWidth)
    val resize by rememberUpdatedState(onResize)
    val done by rememberUpdatedState(onResizeDone)
    var dragging by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (dragging) 1f else 0f, tween(if (dragging) LightMillis else DimMillis), label = "paneEdgeLit")
    val drag = remember { DragFromStart() }
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
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .dragAcross(
                edges = edges,
                onStart = {
                    dragging = true
                    drag.start(width())
                },
                onDrag = { dx -> resize(drag.by(with(density) { (widening * dx).toDp() })) },
                onStop = {
                    dragging = false
                    done()
                },
                onCancel = {
                    dragging = false
                    resize(drag.from)
                },
            )
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = "${width().value.roundToInt()} dp wide"
                customActions = listOf(
                    CustomAccessibilityAction(WIDEN) { resize(width() + AccessibilityStep); done(); true },
                    CustomAccessibilityAction(NARROW) { resize(width() - AccessibilityStep); done(); true },
                )
            },
    )
}

/** The width a drag started from and how far it has gone since, so the edge tracks the finger rather than the clamp. */
private class DragFromStart {
    var from = 0.dp
        private set
    private var travel = 0.dp

    fun start(width: Dp) {
        from = width
        travel = 0.dp
    }

    fun by(delta: Dp): Dp {
        travel += delta
        return from + travel
    }
}

/**
 * A drag across the strip, heard before the panes under it hear anything: hit testing stops at the first sibling it
 * reaches unless that one shares, and this one does, so the panes go on hearing every event; and it listens on the
 * initial pass, so a drag across is its own once past touch slop, consumed before something under the strip that
 * scrolls sideways — a code view running to the panel's edge — would take it. It consumes nothing before then, and
 * gives the gesture up at once if it crosses touch slop no further across than up or down.
 */
private fun Modifier.dragAcross(
    edges: BackGestureEdges?,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
): Modifier = this then DragAcrossElement(edges, onStart, onDrag, onStop, onCancel)

private data class DragAcrossElement(
    val edges: BackGestureEdges?,
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
) : ModifierNodeElement<DragAcrossNode>() {
    override fun create() = DragAcrossNode(edges, onStart, onDrag, onStop, onCancel)

    override fun update(node: DragAcrossNode) {
        node.edges = edges
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
    var onStart: () -> Unit,
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
                val across = awaitDragAcross(down.id, viewConfiguration.touchSlop) ?: return@awaitEachGesture
                onStart()
                onDrag(across)
                if (followAcross(down.id) { onDrag(it) }) onStop() else onCancel()
            }
        },
    )

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings() = true
}

/** The travel across once [pointer] is past [slop] further across than up or down, consumed; null for anything else. */
private suspend fun AwaitPointerEventScope.awaitDragAcross(pointer: PointerId, slop: Float): Float? {
    var travel = Offset.Zero
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (!change.pressed || change.isConsumed) return null
        travel += change.positionChange()
        if (travel.getDistance() > slop) {
            if (abs(travel.x) <= abs(travel.y)) return null
            change.consume()
            return travel.x
        }
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

private val HighlightWidth = 2.dp
private const val HighlightAlpha = 0.6f
private const val LightMillis = 90
private const val DimMillis = 180
private val AccessibilityStep = 40.dp
private const val WIDEN = "Widen"
private const val NARROW = "Narrow"
