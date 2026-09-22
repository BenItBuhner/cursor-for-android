package com.cursorforandroid.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * A pointer put down on a window the way Android delivers it: raw [MotionEvent]s into the window's decor [view],
 * carrying the tool type, which is all that tells an S Pen from a finger (the test rule's touch injection only ever
 * sends fingers). Positions are the window's pixels ([androidx.compose.ui.semantics.SemanticsNode.boundsInWindow]);
 * each move is one 16 ms frame of event time, well inside the long-press timeout.
 */
class PointerStroke(private val compose: ComposeTestRule, private val view: View, private val toolType: Int) {
    private val downTime = SystemClock.uptimeMillis()
    private var eventTime = downTime
    private var at = Offset.Zero

    fun down(position: Offset): PointerStroke = apply {
        at = position
        send(MotionEvent.ACTION_DOWN)
    }

    /** Travels [by] in [steps] equal moves, one frame each. */
    fun moveBy(by: Offset, steps: Int = 10): PointerStroke = apply {
        repeat(steps) {
            at += by / steps.toFloat()
            eventTime += FrameMillis
            send(MotionEvent.ACTION_MOVE)
        }
    }

    fun up(): PointerStroke = apply {
        eventTime += FrameMillis
        send(MotionEvent.ACTION_UP)
    }

    private fun send(action: Int) {
        val properties = MotionEvent.PointerProperties().also {
            it.id = 0
            it.toolType = toolType
        }
        val coords = MotionEvent.PointerCoords().also {
            it.x = at.x
            it.y = at.y
            it.pressure = 1f
            it.size = 1f
        }
        val source = if (toolType == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
        compose.runOnUiThread { view.dispatchTouchEvent(event) }
        event.recycle()
        compose.waitForIdle()
    }

    companion object {
        const val FrameMillis = 16L

        fun stylus(compose: ComposeTestRule, view: View) = PointerStroke(compose, view, MotionEvent.TOOL_TYPE_STYLUS)

        fun finger(compose: ComposeTestRule, view: View) = PointerStroke(compose, view, MotionEvent.TOOL_TYPE_FINGER)
    }
}

/**
 * The input method manager with an IME that writes: what a text field calls to hand a stylus stroke to it is recorded
 * instead of reaching a system service Robolectric does not have.
 */
@Implements(InputMethodManager::class)
class ShadowHandwritingInputMethodManager : ShadowInputMethodManager() {
    @Implementation(minSdk = 34)
    protected fun startStylusHandwriting(view: View) {
        started += view
    }

    companion object {
        val started = mutableListOf<View>()
    }
}
