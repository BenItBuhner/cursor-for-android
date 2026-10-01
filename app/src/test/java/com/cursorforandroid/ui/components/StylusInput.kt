package com.cursorforandroid.ui.components

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Magnifier
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
class PointerStroke(
    private val compose: ComposeTestRule,
    private val view: View,
    private val toolType: Int,
    /** `MotionEvent.getButtonState()` for the whole stroke: the S Pen's side button held is [MotionEvent.BUTTON_STYLUS_PRIMARY]. */
    private val buttonState: Int = 0,
) {
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

    /** Stays down where it is for [millis], on the event clock and on the clock Compose's timeouts (a long press) run on. */
    fun hold(millis: Long): PointerStroke = apply {
        eventTime += millis
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
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
        val buttons = if (action == MotionEvent.ACTION_UP) 0 else buttonState
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(properties), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0, source, 0)
        compose.runOnUiThread { view.dispatchTouchEvent(event) }
        event.recycle()
        compose.waitForIdle()
    }

    companion object {
        const val FrameMillis = 16L

        fun stylus(compose: ComposeTestRule, view: View, buttonState: Int = 0) = PointerStroke(compose, view, MotionEvent.TOOL_TYPE_STYLUS, buttonState)

        fun finger(compose: ComposeTestRule, view: View) = PointerStroke(compose, view, MotionEvent.TOOL_TYPE_FINGER)
    }
}

/**
 * The input method manager with an IME that writes ([writes], Gboard's case) or one that does not (Samsung Keyboard's):
 * what a text field calls to hand a stylus stroke to it is recorded instead of reaching a system service Robolectric
 * does not have.
 */
@Implements(InputMethodManager::class)
class ShadowHandwritingInputMethodManager : ShadowInputMethodManager() {
    @Implementation(minSdk = 34)
    protected fun startStylusHandwriting(view: View) {
        started += view
    }

    @Implementation(minSdk = 34)
    protected fun isStylusHandwritingAvailable(): Boolean = writes

    companion object {
        val started = mutableListOf<View>()
        var writes = true
    }
}

/**
 * The magnifier a held press over text opens, kept from the screen: Robolectric cannot copy the window's pixels into
 * it, and the platform's own magnifier crashes dismissing itself when that copy fails.
 */
@Implements(Magnifier::class, minSdk = 28)
class ShadowOffscreenMagnifier {
    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    protected fun update() = Unit

    @Implementation
    protected fun dismiss() = Unit
}
