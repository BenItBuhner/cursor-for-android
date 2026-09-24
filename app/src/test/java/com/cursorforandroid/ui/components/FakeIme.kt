package com.cursorforandroid.ui.components

import android.os.Handler
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager
import android.view.KeyEvent as NativeKeyEvent

/**
 * The IME's place in a window's input pipeline. `ViewRootImpl` hands every key to the IME
 * (`InputMethodManager.dispatchInputEvent`) after the views' pre-IME pass and before the views, the activity
 * included, see it; [FakeIme] answers there in place of the IME Robolectric does not have.
 */
@Implements(InputMethodManager::class)
class ShadowFakeImeInputMethodManager : ShadowInputMethodManager() {
    @Implementation
    protected fun dispatchInputEvent(
        event: InputEvent,
        token: Any?,
        @ClassName("android.view.inputmethod.InputMethodManager\$FinishedInputEventCallback") callback: Any?,
        handler: Handler?,
    ): Int = if (FakeIme.take(event)) DispatchHandled else DispatchNotHandled

    private companion object {
        // InputMethodManager.DISPATCH_HANDLED and DISPATCH_NOT_HANDLED, hidden from the SDK.
        const val DispatchHandled = 1
        const val DispatchNotHandled = 0
    }
}

/**
 * An IME with a physical keyboard attached. As [Style.PassesKeysOn] it leaves every key to the app, as most do; the
 * other styles take a physical Enter and answer it themselves, the way an IME that reads the physical keyboard (for
 * its suggestions or shortcuts) can. What it does with a word it holds as a composition, and how it puts the newline
 * in, is the style's.
 */
internal object FakeIme {
    enum class Style {
        PassesKeysOn,

        /** Commits the word it was composing, then the newline as a commit of its own: LatinIME's way, and Gboard's. */
        CommitsNewline,

        /** Commits the word it was composing and the newline together, in one commit. */
        CommitsWordAndNewline,

        /** Finishes the word it was composing, then sends an Enter key of its own, as `InputMethodService.sendKeyChar('\n')` does. */
        SendsOwnEnter,

        /** Confirms the composition and types nothing more, as a conversion IME (pinyin, kana) does on Enter. */
        ConfirmsComposition,

        /** Takes every key it is handed and hands none on, answering Enter as [CommitsNewline] does. */
        TakesEveryKey,
    }

    var style = Style.PassesKeysOn

    /** The connection it types into, opened on the focused window as a bound IME's is. */
    var connection: InputConnection? = null

    /** The word it holds as a composition, as [compose] set it. */
    private var composing: String? = null

    /** Every key the pipeline handed it, in order. */
    val heard = mutableListOf<NativeKeyEvent>()

    fun reset() {
        style = Style.PassesKeysOn
        connection = null
        composing = null
        heard.clear()
    }

    /** Opens a connection to [view]'s focused field and answers keys in [style] through it. */
    fun bind(view: View, style: Style): InputConnection {
        val opened = checkNotNull(view.onCreateInputConnection(EditorInfo())) { "the view has no field taking text" }
        connection = opened
        this.style = style
        return opened
    }

    /** Holds [word] as a composition at the cursor, as an IME does with the word it is suggesting for. */
    fun compose(word: String) {
        composing = word
        checkNotNull(connection).setComposingText(word, 1)
    }

    /** The on-screen keyboard's Enter key, tapped: a newline committed, as LatinIME commits it. */
    fun tapEnter() {
        checkNotNull(connection).commitText("\n", 1)
    }

    fun heardEnterDowns(): Int = heard.count { it.keyCode == NativeKeyEvent.KEYCODE_ENTER && it.action == NativeKeyEvent.ACTION_DOWN }

    fun take(event: InputEvent): Boolean {
        if (event !is NativeKeyEvent) return false
        heard += NativeKeyEvent(event)
        if (style == Style.PassesKeysOn) return false
        if (event.keyCode != NativeKeyEvent.KEYCODE_ENTER) return style == Style.TakesEveryKey
        if (event.action == NativeKeyEvent.ACTION_DOWN && event.repeatCount == 0) answerEnter()
        return true
    }

    private fun answerEnter() {
        val connection = checkNotNull(connection) { "the IME has no connection to type into" }
        val word = composing
        composing = null
        when (style) {
            Style.CommitsNewline, Style.TakesEveryKey -> {
                if (word != null) connection.commitText(word, 1)
                connection.commitText("\n", 1)
            }
            Style.CommitsWordAndNewline -> connection.commitText(word.orEmpty() + "\n", 1)
            Style.SendsOwnEnter -> {
                if (word != null) connection.finishComposingText()
                connection.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_DOWN))
                connection.sendKeyEvent(ownEnter(NativeKeyEvent.ACTION_UP))
            }
            Style.ConfirmsComposition -> connection.finishComposingText()
            Style.PassesKeysOn -> Unit
        }
    }

    /** An Enter as `InputMethodService.sendDownUpKeyEvents` makes one: the virtual keyboard's device and flags. */
    fun ownEnter(action: Int): NativeKeyEvent {
        val now = SystemClock.uptimeMillis()
        return NativeKeyEvent(
            now, now, action, NativeKeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            NativeKeyEvent.FLAG_SOFT_KEYBOARD or NativeKeyEvent.FLAG_KEEP_TOUCH_MODE,
        )
    }
}

/** The view of the window this node is drawn in: the activity's, or a sheet's own. */
internal val SemanticsNodeInteraction.windowView: View
    get() = (checkNotNull(fetchSemanticsNode().root) as ViewRootForTest).view

/**
 * Presses [keyCode] on [keyboard] the way Android delivers a key: into [view]'s window input queue, down and then up,
 * so it passes the views' pre-IME pass, the IME ([FakeIme]) and then the views and activity, in that order. The key
 * lands after the frame that shows what the IME last typed, as a key pressed at typing speed does.
 */
internal fun ComposeTestRule.pressThroughWindow(
    view: View,
    keyCode: Int,
    keyboard: Keyboard = Keyboard.Physical,
    shift: Boolean = false,
    ctrl: Boolean = false,
) {
    waitForIdle()
    val root = checkNotNull(View::class.java.getMethod("getViewRootImpl").invoke(view)) { "the view is in no window" }
    // A window without focus drops its keys. The system focuses the window it shows on top; Robolectric leaves a
    // dialog's (a sheet's) without.
    if (!view.hasWindowFocus()) {
        runOnUiThread { root.javaClass.getMethod("windowFocusChanged", Boolean::class.javaPrimitiveType).invoke(root, true) }
        waitForIdle()
    }
    val enqueue = root.javaClass.getMethod("enqueueInputEvent", InputEvent::class.java)
    for (action in listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP)) {
        val event = keyEvent(keyCode, action, keyboard, shift = shift, ctrl = ctrl).nativeKeyEvent
        runOnUiThread { enqueue.invoke(root, event) }
        waitForIdle()
    }
}

/**
 * Hands [keyCode] from a physical keyboard to [view]'s window as the IME hands a key back: past the views' pre-IME
 * pass, which never sees it. Unless [release], the key is left held down.
 */
internal fun ComposeTestRule.pressAfterIme(view: View, keyCode: Int, shift: Boolean = false, release: Boolean = true) {
    waitForIdle()
    val actions = if (release) listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP) else listOf(NativeKeyEvent.ACTION_DOWN)
    for (action in actions) {
        val event = keyEvent(keyCode, action, shift = shift).nativeKeyEvent
        runOnUiThread { view.rootView.dispatchKeyEvent(event) }
        waitForIdle()
    }
}
