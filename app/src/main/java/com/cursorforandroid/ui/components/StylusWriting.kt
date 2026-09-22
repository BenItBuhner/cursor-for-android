package com.cursorforandroid.ui.components

import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequesterModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.requestFocus
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.launch

/**
 * Makes this box — a text input's visible box, its text field inside — the place a stylus writes into that field: the
 * box itself and, as Android does around an `EditText`, [WritingSlackVertical] above and below it and
 * [WritingSlackHorizontal] to either side. A pen stroke that starts there and travels past the handwriting slop before
 * the long-press timeout focuses the field and hands the stroke to the IME's handwriting
 * (`InputMethodManager.startStylusHandwriting`, Android 14 on: an S Pen writing into a text field). From then until the
 * pen lifts the stroke is consumed, so nothing around the field takes it as a drag — not the right panel, the sidebar
 * drawer, a sheet or a list, not even one that caught the pen going down while it was still sliding or flinging.
 * Fingers, a mouse, and a pen that only taps or is held still are left alone.
 *
 * Compose's own text fields detect the stroke over the field alone, and the [androidx.compose.foundation.text.input.TextFieldState]
 * one — the composer's — without any slack before foundation 1.8, so a stroke begun a few dp off the composer's line of
 * text was the panel's or the drawer's instead. The area shares pointer input with its siblings: whatever lies under
 * the slack still gets every finger.
 */
fun Modifier.stylusWriting(enabled: Boolean = true): Modifier =
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
            .then(StylusWritingElement)
            .padding(horizontal = WritingSlackHorizontal, vertical = WritingSlackVertical)
    }

/** How far past a text input's box a pen stroke still writes into it: `EditText`'s default handwriting bounds. */
internal val WritingSlackVertical = 40.dp
internal val WritingSlackHorizontal = 10.dp

private object StylusWritingElement : ModifierNodeElement<StylusWritingNode>() {
    override fun create() = StylusWritingNode()

    override fun update(node: StylusWritingNode) = Unit

    override fun hashCode(): Int = "stylusWriting".hashCode()

    override fun equals(other: Any?): Boolean = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "stylusWriting"
    }
}

private class StylusWritingNode :
    DelegatingNode(),
    PointerInputModifierNode,
    FocusEventModifierNode,
    FocusRequesterModifierNode,
    CompositionLocalConsumerModifierNode {

    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
    }

    private val input = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                // Unconsumed or not: a drawer or a list still settling takes the down to stop itself, and the pen is
                // writing all the same.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture
                val horizontal = WritingSlackHorizontal.toPx()
                val vertical = WritingSlackVertical.toPx()
                val overBox = down.position.x >= horizontal && down.position.x < size.width - horizontal &&
                    down.position.y >= vertical && down.position.y < size.height - vertical
                // Over the box, or anywhere around the field that has the keyboard, the stroke is this field's before
                // anything else sees it. In the slack of a field without focus it waits its turn, so two fields whose
                // slack overlaps never both take one stroke.
                val pass = if (focused || overBox) PointerEventPass.Initial else PointerEventPass.Main
                var writing: PointerInputChange? = null
                while (writing == null) {
                    val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                    if ((change.position - down.position).getDistance() > viewConfiguration.handwritingSlop) writing = change
                }
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

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings(): Boolean = true
}

private fun View.startStylusHandwriting() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    context.getSystemService(InputMethodManager::class.java)?.startStylusHandwriting(this)
}
