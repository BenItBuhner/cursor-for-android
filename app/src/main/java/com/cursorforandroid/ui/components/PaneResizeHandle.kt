package com.cursorforandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
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
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/** Which side of the boundary the pane a [PaneResizeHandle] resizes stands on. */
enum class PaneSide { Start, End }

/**
 * The drag handle on the boundary between two panes side by side — the wide window's rail and the chat, the chat and
 * the panel pinned beside it — resizing the pane on [side]. Placed over the boundary, it takes a strip either side of
 * it, and of what happens there it takes only a drag across: a tap, a press, a vertical scroll of the transcript or
 * the list under it go on to the pane below as though it were not there. The pane's edge follows the finger from
 * where it was when the drag began, through [onResize]; the caller clamps it to the pane's range and hears the finger
 * lift in [onResizeDone].
 *
 * A small grip marks it at rest, the way Android's own resizable panes do; a pointer over it shows the resize cursor,
 * and TalkBack widens or narrows the pane by a step through its actions.
 */
@Composable
fun PaneResizeHandle(
    side: PaneSide,
    paneWidth: () -> Dp,
    onResize: (Dp) -> Unit,
    onResizeDone: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // A drag toward the end widens a pane on the start side and narrows one on the end side; RTL mirrors the screen.
    val widening = (if (side == PaneSide.Start) 1f else -1f) * (if (rtl) -1f else 1f)
    val width by rememberUpdatedState(paneWidth)
    val resize by rememberUpdatedState(onResize)
    val done by rememberUpdatedState(onResizeDone)
    var dragging by remember { mutableStateOf(false) }
    val drag = remember { DragFromStart() }
    Box(
        modifier
            .fillMaxHeight()
            .width(PaneResizeHandleWidth)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .sharedHorizontalDrag(
                onStart = {
                    dragging = true
                    drag.start(width())
                },
                onDrag = { dx -> resize(drag.by(with(density) { (widening * dx).toDp() })) },
                onStop = {
                    dragging = false
                    done()
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
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(GripWidth, GripHeight).background(if (dragging) colors.textTertiary else colors.strokeStrong, RoundedCornerShape(percent = 50)))
    }
}

/** The width a drag started from and how far it has gone since, so the edge tracks the finger rather than the clamp. */
private class DragFromStart {
    private var from = 0.dp
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
 * A horizontal drag that shares the pointer with the siblings under the handle: hit testing stops at the first
 * sibling it reaches unless that one shares, and this one does, so the pane below goes on hearing every tap and
 * vertical scroll the handle leaves alone. The handle is the topmost sibling, so it is asked first on each event and
 * a drag across is its own once past touch slop, consumed before the pane below would take it.
 */
private fun Modifier.sharedHorizontalDrag(onStart: () -> Unit, onDrag: (Float) -> Unit, onStop: () -> Unit): Modifier =
    this then SharedHorizontalDragElement(onStart, onDrag, onStop)

private data class SharedHorizontalDragElement(
    val onStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onStop: () -> Unit,
) : ModifierNodeElement<SharedHorizontalDragNode>() {
    override fun create() = SharedHorizontalDragNode(onStart, onDrag, onStop)

    override fun update(node: SharedHorizontalDragNode) {
        node.onStart = onStart
        node.onDrag = onDrag
        node.onStop = onStop
    }
}

private class SharedHorizontalDragNode(
    var onStart: () -> Unit,
    var onDrag: (Float) -> Unit,
    var onStop: () -> Unit,
) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(
        SuspendingPointerInputModifierNode {
            detectHorizontalDragGestures(
                onDragStart = { onStart() },
                onDragEnd = { onStop() },
                onDragCancel = { onStop() },
            ) { change, dx ->
                change.consume()
                onDrag(dx)
            }
        },
    )

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings() = true
}

/** The strip the handle takes over the boundary: half either side. */
val PaneResizeHandleWidth = 32.dp

private val GripWidth = 4.dp
private val GripHeight = 36.dp
private val AccessibilityStep = 40.dp
private const val WIDEN = "Widen"
private const val NARROW = "Narrow"
