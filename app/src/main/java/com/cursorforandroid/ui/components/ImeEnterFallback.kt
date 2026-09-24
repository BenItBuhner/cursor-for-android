package com.cursorforandroid.ui.components

import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Takes a newline the IME types for a physical Enter as that Enter: [onEnter] runs instead. An IME that handles the
 * physical keyboard itself can be handed the Enter before the field's key handlers take it ([sendOnHardwareEnter]
 * sends one pressed on a composition on to the IME) and answer with a newline of its own, committed as text or sent as
 * an Enter key of its own, which reads as the on-screen keyboard's. Which newlines were a physical Enter's is
 * [PhysicalKeyboard.typedEnter]'s to say; the on-screen keyboard's Enter, once physical keys are seen reaching the
 * field first, keeps its newline. Only a newline on its own counts, committed where the text is not being composed,
 * or one on the end of a commit the IME makes straight after a physical Enter reached it; a paste of lines is text.
 *
 * [onEnter] is what a physical Enter does in [content]'s field right now — send, or pick from an open popover — and
 * null while it does nothing, when the IME's newline goes in as it came. [composing] is whether the field holds a
 * composition.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImeEnterFallback(onEnter: (() -> Unit)?, composing: () -> Boolean, content: @Composable () -> Unit) {
    val currentOnEnter by rememberUpdatedState(onEnter)
    val currentComposing by rememberUpdatedState(composing)
    val configuration by rememberUpdatedState(LocalConfiguration.current)
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing = nextHandler.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                        EnterFallbackConnection(request.createInputConnection(outAttributes)) { alone ->
                            if (alone && currentComposing()) return@EnterFallbackConnection null
                            val enter = PhysicalKeyboard.typedEnter(configuration, alone)
                            currentOnEnter.takeIf { enter }
                        }
                },
            )
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/**
 * The field's connection with newlines [enterFor] claims taken out. [enterFor] is asked about a newline committed on
 * its own ([alone]) or on the end of other text, and answers with what to do instead of it, or null to let it in.
 */
internal class EnterFallbackConnection(
    target: InputConnection,
    private val enterFor: (alone: Boolean) -> (() -> Unit)?,
) : InputConnectionWrapper(target, false) {

    /** The IME's own Enter key was taken as a physical Enter; the rest of that key goes with it. */
    private var tookEnterKey = false

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        commit(text) { super.commitText(it, newCursorPosition) }

    // The platform's wrapper hands this straight to the target. Compose's own wrapper around this connection turns it
    // into the call above today, but nothing holds it to that.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        commit(text) { super.commitText(it ?: "", newCursorPosition, textAttribute) }

    private inline fun commit(text: CharSequence?, commit: (CharSequence?) -> Boolean): Boolean {
        val newlineAt = text?.indexOf('\n') ?: -1
        if (text == null || newlineAt < 0 || newlineAt != text.lastIndex) return commit(text)
        val enter = enterFor(text.length == 1) ?: return commit(text)
        val committed = text.length == 1 || commit(text.subSequence(0, newlineAt))
        enter()
        return committed
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ENTER && event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.sendKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            tookEnterKey = false
            val enter = if (event.hasNoModifiers()) enterFor(true) else null
            if (enter != null) {
                tookEnterKey = true
                enter()
                return true
            }
        }
        if (tookEnterKey) {
            if (event.action == KeyEvent.ACTION_UP) tookEnterKey = false
            return true
        }
        return super.sendKeyEvent(event)
    }
}
