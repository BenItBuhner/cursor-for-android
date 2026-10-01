package com.cursorforandroid.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.launch

/**
 * Makes this box — a text input's visible box, its text field inside — what an S Pen meets there, for the field
 * inside, which sits in a [StylusTextInput]:
 *
 * - Writing. With [handwriting] and an IME that takes stylus handwriting (`InputMethodManager.isStylusHandwritingAvailable`,
 *   Android 14 on — Gboard; not Samsung Keyboard), a pen stroke that starts on the box or, as Android allows around an
 *   `EditText`, [WritingSlackVertical] above or below it or [WritingSlackHorizontal] to either side, and travels past
 *   the touch slop before the long-press timeout with no barrel button held, focuses the field and is handed to the
 *   IME (`InputMethodManager.startStylusHandwriting`). From then until the pen lifts the stroke is consumed, so
 *   nothing around the field takes it as a drag — not the right panel, the sidebar drawer, a sheet or a list, not even
 *   one that caught the pen going down while it was still sliding or flinging. A tap, a press held still, a stroke
 *   with the side button held, and every stroke under an IME that cannot write are the field's as a finger's are:
 *   the caret, a selection, the field's scroll.
 * - Reach. Compose gives a finger the 48dp minimum touch target and nothing else (its hit test asks for
 *   [PointerType.Touch]), so a field one line high takes a finger from well above and below its glyphs and a pen only
 *   on them. A pen tap on the box, or within the margin a finger would have around it, that nothing under it took is
 *   given to the window again as that finger's tap: the field focuses and puts the caret where a finger's tap would.
 *
 * The area shares pointer input with its siblings: whatever lies under the slack still gets every finger.
 */
fun Modifier.stylusWriting(enabled: Boolean = true, handwriting: Boolean = true): Modifier =
    if (!enabled) {
        this
    } else {
        this
            .layout { measurable, constraints ->
                // The slack is laid out around the box and handed back: the box measures and places as it did without it.
                val horizontal = WritingSlackHorizontal.roundToPx()
                val vertical = WritingSlackVertical.roundToPx()
                val placeable = measurable.measure(constraints.offset(2 * horizontal, 2 * vertical))
                layout(placeable.width - 2 * horizontal, placeable.height - 2 * vertical) { placeable.place(-horizontal, -vertical) }
            }
            .then(StylusWritingElement(handwriting))
            .padding(horizontal = WritingSlackHorizontal, vertical = WritingSlackVertical)
    }

/**
 * Hosts a `BasicTextField` whose pen input is the [stylusWriting] box around it to decide. Compose's own text fields
 * (foundation 1.7 through at least 1.12) take any stylus movement past the handwriting slop — 2dp — before the
 * long-press timeout as writing, and consume the rest of the stroke whether or not the IME can write: an S Pen tap that
 * moves as it lifts places no caret, a press that wavers before it is held selects nothing, and a drag never scrolls
 * the field. Inside, that detector's slop is out of reach; the rest of the view configuration is the platform's.
 */
@Composable
fun StylusTextInput(content: @Composable () -> Unit) {
    val platform = LocalViewConfiguration.current
    val configuration = remember(platform) { WritingLeftToTheBox(platform) }
    CompositionLocalProvider(LocalViewConfiguration provides configuration, content = content)
}

private class WritingLeftToTheBox(platform: ViewConfiguration) : ViewConfiguration by platform {
    override val handwritingSlop: Float get() = Float.POSITIVE_INFINITY
}

/** How far past a text input's box a pen stroke still writes into it: `EditText`'s default handwriting bounds. */
internal val WritingSlackVertical = 40.dp
internal val WritingSlackHorizontal = 10.dp

private data class StylusWritingElement(val handwriting: Boolean) : ModifierNodeElement<StylusWritingNode>() {
    override fun create() = StylusWritingNode(handwriting)

    override fun update(node: StylusWritingNode) {
        node.handwriting = handwriting
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "stylusWriting"
        properties["handwriting"] = handwriting
    }
}

private class StylusWritingNode(var handwriting: Boolean) :
    DelegatingNode(),
    PointerInputModifierNode,
    FocusEventModifierNode,
    FocusRequesterModifierNode,
    CompositionLocalConsumerModifierNode {

    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
    }

    private val writing = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                // Unconsumed or not: a drawer or a list still settling takes the down to stop itself, and the pen is
                // writing all the same.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!handwriting || !down.isPen || currentEvent.buttons.anyPressed) return@awaitEachGesture
                val overBox = box().contains(down.position)
                // Over the box, or anywhere around the field that has the keyboard, the stroke is this field's before
                // anything else sees it. In the slack of a field without focus it waits its turn, so two fields whose
                // slack overlaps never both take one stroke.
                val pass = if (focused || overBox) PointerEventPass.Initial else PointerEventPass.Main
                // The touch slop, not the handwriting slop: a pen tap travels a few dp on the glass, and a drag the
                // field or a list makes of the stroke starts no sooner. Android hands the IME the stroke from its down.
                val slop = viewConfiguration.touchSlop
                var writing: PointerInputChange? = null
                while (writing == null) {
                    val event = awaitPointerEvent(pass)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed || event.buttons.anyPressed) return@awaitEachGesture
                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                    if ((change.position - down.position).getDistance() > slop) writing = change
                }
                if (!currentValueOf(LocalView).imeWrites()) return@awaitEachGesture
                writing.consume()
                write()
                // The rest of the stroke is the IME's; Android cancels it here once the handwriting window has it.
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                    change.consume()
                }
            }
        },
    )

    private val reach = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                // Last pass: whatever under the pen took the tap — the field itself, a button — has consumed it by now.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                if (!down.isPen || down.isConsumed || !fingerReach().contains(down.position)) return@awaitEachGesture
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitUntouchedTap(down) } ?: return@awaitEachGesture
                tapAsFinger(down.position, up.position)
            }
        },
    )

    /** The box without the slack around it, in this node's coordinates. */
    private fun PointerInputScope.box(): Rect {
        val horizontal = WritingSlackHorizontal.toPx()
        val vertical = WritingSlackVertical.toPx()
        return Rect(horizontal, vertical, size.width - horizontal, size.height - vertical)
    }

    /** The box and the margin Compose's minimum touch target gives a finger around a box this size. */
    private fun PointerInputScope.fingerReach(): Rect {
        val box = box()
        val target = viewConfiguration.minimumTouchTargetSize.toSize()
        val horizontal = ((target.width - box.width) / 2f).coerceAtLeast(0f)
        val vertical = ((target.height - box.height) / 2f).coerceAtLeast(0f)
        return Rect(box.left - horizontal, box.top - vertical, box.right + horizontal, box.bottom + vertical)
    }

    /** The pen's lift, if it lifts within the touch slop and nothing has consumed the tap; null otherwise. */
    private suspend fun AwaitPointerEventScope.awaitUntouchedTap(down: PointerInputChange): PointerInputChange? {
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: return null
            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return null
            if (!change.pressed) return change
        }
    }

    private fun write() {
        val wasFocused = focused
        if (!wasFocused && !requestFocus()) return
        val view = currentValueOf(LocalView)
        coroutineScope.launch {
            // A field focused just now opens its input connection on the next frame, and handwriting started before
            // that would have nothing to write into; Compose's own fields wait the same frame.
            if (!wasFocused) withFrameNanos { }
            view.startStylusHandwriting()
        }
    }

    private fun tapAsFinger(down: Offset, up: Offset) {
        if (!isAttached) return
        val view = currentValueOf(LocalView)
        val coordinates = requireLayoutCoordinates()
        val from = coordinates.localToRoot(down)
        val to = coordinates.localToRoot(up)
        // After this pass: the window does not take new events while it is still dispatching the pen's lift.
        view.post {
            val time = SystemClock.uptimeMillis()
            view.dispatchFinger(MotionEvent.ACTION_DOWN, time, from)
            view.dispatchFinger(MotionEvent.ACTION_UP, time, to)
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        writing.onPointerEvent(pointerEvent, pass, bounds)
        reach.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        writing.onCancelPointerInput()
        reach.onCancelPointerInput()
    }

    override fun sharePointerInputWithSiblings(): Boolean = true
}

private val PointerInputChange.isPen: Boolean get() = type == PointerType.Stylus || type == PointerType.Eraser

/** A barrel button held: the S Pen's side button reports as the stylus primary button, or on some builds the secondary. */
private val PointerButtons.anyPressed: Boolean get() = isPrimaryPressed || isSecondaryPressed || isTertiaryPressed

/** Whether the IME takes a stylus stroke as handwriting: Android says to leave the stroke as touch input when it does not. */
private fun View.imeWrites(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        context.getSystemService(InputMethodManager::class.java)?.isStylusHandwritingAvailable == true

private fun View.startStylusHandwriting() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    context.getSystemService(InputMethodManager::class.java)?.startStylusHandwriting(this)
}

private fun View.dispatchFinger(action: Int, time: Long, at: Offset) {
    val properties = MotionEvent.PointerProperties().also {
        it.id = 0
        it.toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    val coords = MotionEvent.PointerCoords().also {
        it.x = at.x
        it.y = at.y
        it.pressure = 1f
        it.size = 1f
    }
    val event = MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    dispatchTouchEvent(event)
    event.recycle()
}
