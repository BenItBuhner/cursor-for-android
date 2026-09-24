package com.cursorforandroid.ui.components

import android.view.KeyEvent
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What [EnterFallbackConnection] lets through to the field's own connection, and which newlines it offers up as an
 * Enter, however the IME puts them in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EnterFallbackConnectionTest {

    /** The field's connection: what reached it. */
    private class Field : InputConnectionWrapper(null, false) {
        val committed = mutableListOf<Pair<String, TextAttribute?>>()
        val keys = mutableListOf<Pair<Int, Int>>()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committed += text.toString() to null
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
            committed += text.toString() to textAttribute
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keys += event.keyCode to event.action
            return true
        }
    }

    private val field = Field()
    private val asked = mutableListOf<Boolean>()
    private var enters = 0
    private var claims = true
    private val connection = EnterFallbackConnection(field) { alone ->
        asked += alone
        if (claims) ({ enters++ }) else null
    }
    private val attribute = TextAttribute.Builder().setTextConversionSuggestions(listOf("ship it")).build()

    private fun key(action: Int, keyCode: Int = KeyEvent.KEYCODE_ENTER, repeat: Int = 0, meta: Int = 0) =
        KeyEvent(0L, 0L, action, keyCode, repeat, meta)

    @Test
    fun `a lone newline claimed is the Enter instead, however it is committed`() {
        assertThat(connection.commitText("\n", 1)).isTrue()
        assertThat(connection.commitText("\n", 1, attribute)).isTrue()
        assertThat(enters).isEqualTo(2)
        assertThat(asked).containsExactly(true, true)
        assertThat(field.committed).isEmpty()
    }

    @Test
    fun `a newline on the end of a commit is asked about with text before it, which goes in either way`() {
        connection.commitText("ship\n", 1)
        connection.commitText("it\n", 1, attribute)
        assertThat(enters).isEqualTo(2)
        assertThat(asked).containsExactly(false, false)
        assertThat(field.committed).containsExactly("ship" to null, "it" to attribute).inOrder()
    }

    @Test
    fun `a newline with text after it is never asked about, and goes in whole`() {
        connection.commitText("one\ntwo", 1)
        connection.commitText("\nthree", 1, attribute)
        connection.commitText("four\n\n", 1)
        assertThat(asked).isEmpty()
        assertThat(enters).isEqualTo(0)
        assertThat(field.committed).containsExactly("one\ntwo" to null, "\nthree" to attribute, "four\n\n" to null).inOrder()
    }

    @Test
    fun `a newline not claimed goes in as it came`() {
        claims = false
        connection.commitText("\n", 1)
        connection.commitText("ship\n", 1, attribute)
        assertThat(asked).containsExactly(true, false)
        assertThat(field.committed).containsExactly("\n" to null, "ship\n" to attribute).inOrder()
    }

    @Test
    fun `the IME's own Enter key, taken, takes its repeats and release with it, and the next key is read afresh`() {
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN))
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1))
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP))
        assertThat(enters).isEqualTo(1)
        assertThat(field.keys).isEmpty()

        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        claims = false
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER))
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertThat(field.keys).containsExactly(
            KeyEvent.KEYCODE_A to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_A to KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_NUMPAD_ENTER to KeyEvent.ACTION_UP,
        ).inOrder()
    }

    @Test
    fun `an Enter key whose release was lost does not swallow the next press`() {
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN))
        claims = false
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN))
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP))
        assertThat(enters).isEqualTo(1)
        assertThat(field.keys).containsExactly(KeyEvent.KEYCODE_ENTER to KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER to KeyEvent.ACTION_UP).inOrder()
    }

    @Test
    fun `an Enter key with a modifier held is not asked about`() {
        connection.sendKeyEvent(key(KeyEvent.ACTION_DOWN, meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        connection.sendKeyEvent(key(KeyEvent.ACTION_UP, meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertThat(asked).isEmpty()
        assertThat(field.keys).containsExactly(KeyEvent.KEYCODE_ENTER to KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER to KeyEvent.ACTION_UP).inOrder()
    }
}
