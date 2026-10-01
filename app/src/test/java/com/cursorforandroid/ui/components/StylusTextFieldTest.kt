package com.cursorforandroid.ui.components

import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.home.SheetSearchField
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An S Pen in the text fields does what a finger does there: a tap places the caret where it lands (a pen tap moves a
 * few dp on the glass as it lifts; a finger's moves as much), a press held on a word and dragged selects, and a drag
 * scrolls a field whose text is taller than its box. Under a keyboard that does not take stylus handwriting —
 * Samsung Keyboard, the S26 Ultra's — nothing in a field is ever the IME's to write; under one that does, a tap, a
 * press and a stroke with the side button held still are not writing. A pen tap on a field's box off its line of text
 * lands on the field, as a finger's does there.
 *
 * The events are the raw `MotionEvent`s Android delivers, tool type `TOOL_TYPE_STYLUS` ([PointerStroke]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi", shadows = [ShadowHandwritingInputMethodManager::class, ShadowOffscreenMagnifier::class])
class StylusTextFieldTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    @After
    fun resetIme() {
        ShadowHandwritingInputMethodManager.started.clear()
        ShadowHandwritingInputMethodManager.writes = true
    }

    private fun samsungKeyboard() {
        ShadowHandwritingInputMethodManager.writes = false
    }

    private fun showComposer(text: String) {
        compose.setContent {
            var draft by remember { mutableStateOf(text) }
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                    Spacer(Modifier.weight(1f))
                    ComposerBox(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "Ask anything",
                        onSend = {},
                        plusMenu = ComposerMenuActions(onPickMedia = {}),
                        modelLabel = "Composer 2",
                        onModel = {},
                        modifier = Modifier.padding(12.dp).testTag("composer"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showSheetSearch() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                    Spacer(Modifier.height(80.dp))
                    Column(Modifier.testTag("sheet-search")) {
                        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Search repositories")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val composerField: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("composer")))
    private val searchField: SemanticsNodeInteraction get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sheet-search")))

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInWindow
    private fun px(dp: Float): Float = with(compose.density) { dp.dp.toPx() }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun selection(node: SemanticsNodeInteraction): TextRange = node.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    /** Where, in the window, the boundary before [offset] sits on its line, halfway down it. */
    private fun boundaryInWindow(node: SemanticsNodeInteraction, offset: Int): Offset {
        val text = layout(node)
        val line = text.getLineForOffset(offset)
        val origin = bounds(node).topLeft
        return origin + Offset(text.getHorizontalPosition(offset, usePrimaryDirection = true), (text.getLineTop(line) + text.getLineBottom(line)) / 2f)
    }

    private val window: View get() = compose.activity.window.decorView

    private fun stylus(buttonState: Int = 0) = PointerStroke.stylus(compose, window, buttonState)

    private fun assertNoHandwriting() {
        compose.waitForIdle()
        assertThat(ShadowHandwritingInputMethodManager.started).isEmpty()
    }

    @Test
    fun `a pen tap that moves a few dp as it lifts puts the caret where it landed`() {
        samsungKeyboard()
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val gamma = text.indexOf("gamma")
        val at = boundaryInWindow(composerField, gamma)

        // Under the touch slop, and past the 2dp handwriting slop Compose's own text fields take as writing.
        stylus().down(at - Offset(px(2f), px(1.5f))).moveBy(Offset(px(2f), px(1.5f)), steps = 3).up()

        composerField.assertIsFocused()
        assertThat(selection(composerField)).isEqualTo(TextRange(gamma))
        assertNoHandwriting()
    }

    @Test
    fun `a pen tap that moves as it lifts is a tap under a keyboard that writes too`() {
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val delta = text.indexOf("delta")
        val at = boundaryInWindow(composerField, delta)

        stylus().down(at - Offset(px(3f), 0f)).moveBy(Offset(px(3f), 0f), steps = 3).up()

        composerField.assertIsFocused()
        assertThat(selection(composerField)).isEqualTo(TextRange(delta))
        assertNoHandwriting()
    }

    @Test
    fun `a pen held on a word, wavering as a hand does, and dragged selects the text it crosses`() {
        samsungKeyboard()
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val beta = text.indexOf("beta")
        val delta = text.indexOf("delta")
        val start = boundaryInWindow(composerField, beta + 2)
        val end = boundaryInWindow(composerField, delta + 3)

        stylus()
            .down(start)
            .moveBy(Offset(px(3f), px(1f)), steps = 3)
            .hold(700)
            .moveBy(end - start - Offset(px(3f), px(1f)), steps = 12)
            .up()

        val selected = selection(composerField)
        assertThat(selected.collapsed).isFalse()
        assertThat(selected.min).isAtMost(beta)
        assertThat(selected.max).isAtLeast(delta + 3)
        assertNoHandwriting()
    }

    @Test
    fun `a press held and dragged selects under a keyboard that writes too`() {
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val beta = text.indexOf("beta")
        val delta = text.indexOf("delta")
        val start = boundaryInWindow(composerField, beta + 2)
        val end = boundaryInWindow(composerField, delta + 3)

        stylus()
            .down(start)
            .moveBy(Offset(px(2f), px(1f)), steps = 2)
            .hold(700)
            .moveBy(end - start - Offset(px(2f), px(1f)), steps = 12)
            .up()

        val selected = selection(composerField)
        assertThat(selected.collapsed).isFalse()
        assertThat(selected.min).isAtMost(beta)
        assertThat(selected.max).isAtLeast(delta + 3)
        assertNoHandwriting()
    }

    @Test
    fun `a pen dragged up a composer whose text is taller than it scrolls the text`() {
        samsungKeyboard()
        val lines = (1..30).map { "line $it" }
        showComposer(lines.joinToString("\n"))
        val field = bounds(composerField)
        val text = layout(composerField)
        val line = text.getLineBottom(0) - text.getLineTop(0)
        // The collapsed composer shows a dozen lines at most: the rest are below its box.
        assertThat(text.size.height.toFloat()).isGreaterThan(field.height + 10 * line)

        stylus().down(Offset(field.center.x, field.bottom - line)).moveBy(Offset(0f, -(field.height - 2 * line)), steps = 16).up()
        assertNoHandwriting()

        // What is at the top of the box now is a later line: a tap there puts the caret on it.
        stylus().down(Offset(field.left + px(2f), field.top + line / 2)).up()
        composerField.assertIsFocused()
        val caretLine = layout(composerField).getLineForOffset(selection(composerField).start)
        assertThat(caretLine).isAtLeast(4)
    }

    @Test
    fun `a pen tap just above the composer's line of text lands on it, as a finger's does`() {
        samsungKeyboard()
        val text = "alpha beta gamma"
        showComposer(text)
        val gamma = text.indexOf("gamma")
        val onLine = boundaryInWindow(composerField, gamma)
        val field = bounds(composerField)

        stylus().down(Offset(onLine.x, field.top - px(8f))).up()

        composerField.assertIsFocused()
        assertThat(selection(composerField)).isEqualTo(TextRange(gamma))
        assertNoHandwriting()
    }

    @Test
    fun `a pen tap on a search row below its line of text lands on the field`() {
        samsungKeyboard()
        showSheetSearch()
        val row = bounds(compose.onNode(hasTestTag("sheet-search")))
        val field = bounds(searchField)
        assertThat(row.bottom - field.bottom).isGreaterThan(px(4f))

        stylus().down(Offset(field.center.x, row.bottom - px(3f))).up()

        searchField.assertIsFocused()
        assertNoHandwriting()
    }

    @Test
    fun `a pen stroke with the side button held is not writing`() {
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val at = boundaryInWindow(composerField, text.indexOf("beta"))

        stylus(buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY).down(at).moveBy(Offset(px(120f), px(4f))).up()

        assertNoHandwriting()
    }

    @Test
    fun `under a keyboard that does not write, a pen stroke across the composer is never handed to it`() {
        samsungKeyboard()
        val text = "alpha beta gamma delta epsilon"
        showComposer(text)
        val at = boundaryInWindow(composerField, text.indexOf("beta"))

        stylus().down(at).moveBy(Offset(px(120f), px(6f))).up()

        assertNoHandwriting()
    }
}
