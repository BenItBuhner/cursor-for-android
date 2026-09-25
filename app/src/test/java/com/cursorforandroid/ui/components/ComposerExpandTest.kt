package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The composer's expand button: offered only once the text scrolls inside the composer, and gone again when it no
 * longer does; tapped, the composer eases up over the room it has — the transcript above squeezed out of the way — and
 * back down on the button, Back, Ctrl+Shift+E or a send, the text, the caret and the footer untouched throughout.
 * Laid out as a chat docks it: a transcript taking what is left over a composer at the foot of the window.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerExpandTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var draft by mutableStateOf("")
    private val sent = ArrayList<String>()
    private var density = 1f

    private fun show(bounded: Boolean = true) {
        compose.setContent {
            density = LocalDensity.current.density
            CursorTheme(mode = ThemeMode.Dark) {
                val composer = @androidx.compose.runtime.Composable {
                    ComposerBox(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "Follow up…",
                        onSend = { sent += draft; draft = "" },
                        plusMenu = ComposerMenuActions(onPickMedia = {}),
                        modelLabel = "Claude Fable 5.1",
                        onModel = {},
                        modifier = Modifier.testTag(COMPOSER),
                    )
                }
                if (bounded) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().testTag(TRANSCRIPT))
                        composer()
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { composer() }
                }
            }
        }
        compose.waitForIdle()
    }

    private val field get() = compose.onNode(hasSetTextAction())
    private val expandButton = hasTestTag("composer-expand")

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun offered() = compose.onAllNodes(expandButton, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun named(description: String) = compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tapExpand() {
        compose.onNode(expandButton, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }
    private fun dp(px: Float) = px / density
    private fun rootHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
    private fun editable() = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
    private fun selection() = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    private fun type(text: String) {
        compose.runOnIdle { draft = text }
        compose.waitForIdle()
    }

    @Test
    fun `offered only while the text scrolls inside the composer, and gone again when it no longer does`() {
        show()
        type(lines(4))
        assertThat(offered()).isFalse()
        type(lines(30))
        assertThat(offered()).isTrue()
        assertThat(named("Expand composer")).isTrue()
        type(lines(3))
        assertThat(offered()).isFalse()
    }

    @Test
    fun `expanded, the composer takes all but a sliver of the room and the transcript gives way, and collapsed it is back`() {
        show()
        type(lines(30))
        val collapsed = bounds(hasTestTag(COMPOSER))
        val transcriptBefore = bounds(hasTestTag(TRANSCRIPT)).height
        tapExpand()
        val expanded = bounds(hasTestTag(COMPOSER))
        // The window less the host's 16dp margins and the 8dp left above an expanded composer.
        assertThat(dp(expanded.height)).isWithin(1.5f).of(dp(rootHeight()) - 32f - ExpandedTopGap.value)
        assertThat(expanded.bottom).isWithin(1f).of(collapsed.bottom)
        assertThat(dp(bounds(hasTestTag(TRANSCRIPT)).height)).isWithin(1f).of(ExpandedTopGap.value)
        assertThat(transcriptBefore).isGreaterThan(expanded.height / 2)
        assertThat(named("Collapse composer")).isTrue()
        // The field shows far more of the text than its ten lines did.
        val fieldHeight = bounds(hasSetTextAction()).height
        assertThat(fieldHeight).isGreaterThan(collapsed.height * 2)

        tapExpand()
        assertThat(bounds(hasTestTag(COMPOSER))).isEqualTo(collapsed)
        assertThat(named("Expand composer")).isTrue()
    }

    @Test
    fun `the height eases up and down rather than jumping`() {
        show()
        type(lines(30))
        val collapsed = bounds(hasTestTag(COMPOSER)).height
        compose.mainClock.autoAdvance = false
        compose.onNode(expandButton, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(ExpandMillis / 2L)
        val midway = bounds(hasTestTag(COMPOSER)).height
        compose.mainClock.advanceTimeBy(ExpandMillis.toLong() + 100)
        val expanded = bounds(hasTestTag(COMPOSER)).height
        assertThat(midway).isGreaterThan(collapsed + 20 * density)
        assertThat(midway).isLessThan(expanded - 20 * density)

        compose.onNode(expandButton, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(ExpandMillis / 2L)
        val down = bounds(hasTestTag(COMPOSER)).height
        assertThat(down).isGreaterThan(collapsed + 20 * density)
        assertThat(down).isLessThan(expanded - 20 * density)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(bounds(hasTestTag(COMPOSER)).height).isEqualTo(collapsed)
    }

    @Test
    fun `the text and the caret are the same field's throughout, and editing while expanded carries back down`() {
        show()
        type(lines(30))
        field.performClick()
        compose.runOnIdle { }
        val caret = checkNotNull(selection())
        tapExpand()
        assertThat(editable()).isEqualTo(lines(30))
        assertThat(selection()).isEqualTo(caret)
        // Typed where the caret was left, not at the end.
        field.performTextInput(" and more")
        tapExpand()
        val edited = lines(30).replaceRange(caret.start, caret.end, " and more")
        assertThat(editable()).isEqualTo(edited)
        assertThat(selection()).isEqualTo(TextRange(caret.start + " and more".length))
        compose.runOnIdle { assertThat(draft).isEqualTo(edited) }
    }

    @Test
    fun `cut down while expanded, the composer collapses to its new height and the button goes`() {
        show()
        type(lines(30))
        tapExpand()
        type(lines(2))
        // Still expanded: the reader asked for it and only they put it away.
        assertThat(named("Collapse composer")).isTrue()
        tapExpand()
        compose.waitForIdle()
        assertThat(offered()).isFalse()
        val composer = bounds(hasTestTag(COMPOSER))
        type(lines(2))
        assertThat(bounds(hasTestTag(COMPOSER))).isEqualTo(composer)
    }

    @Test
    fun `a send takes the composer back down`() {
        show()
        type(lines(30))
        tapExpand()
        compose.onNode(hasContentDescription("Send"), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertThat(sent).containsExactly(lines(30))
        assertThat(offered()).isFalse()
        assertThat(dp(bounds(hasTestTag(TRANSCRIPT)).height)).isGreaterThan(400f)
    }

    @Test
    fun `Back collapses the expanded composer and is not taken otherwise`() {
        show()
        type(lines(30))
        tapExpand()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(named("Expand composer")).isTrue()
        assertThat(compose.activity.isFinishing).isFalse()
        compose.runOnIdle { assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Ctrl+Shift+E expands and collapses from a hardware keyboard, only while the button is offered`() {
        show()
        type(lines(3))
        field.performClick()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.E) } } }
        compose.waitForIdle()
        assertThat(offered()).isFalse()
        type(lines(30))
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.E) } } }
        compose.waitForIdle()
        assertThat(named("Collapse composer")).isTrue()
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.E) } } }
        compose.waitForIdle()
        assertThat(named("Expand composer")).isTrue()
        assertThat(editable()).isEqualTo(lines(30))
    }

    @Test
    fun `beside the plus its touch square is 40dp and meets the plus's without overlapping, and the send row stays put`() {
        show()
        type(lines(2))
        val main = bounds(hasTestTag("composer-main"))
        type(lines(30))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val touch = compose.onAllNodes(hasClickAction() and (hasAnyAncestor(expandButton) or expandButton), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot
        val plus = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(COMPOSER)), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.minBy { it.left }
        assertThat(dp(touch.width)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(dp(touch.height)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(plus.right).isAtMost(touch.left + 0.5f)
        assertThat(touch.center.y).isWithin(0.5f).of(main.center.y)
        // The mic and send row (#365) does not move.
        assertThat(bounds(hasTestTag("composer-main"))).isEqualTo(main)
    }

    @Test
    fun `in a parent with no height to give, expanding is never offered`() {
        show(bounded = false)
        type(lines(30))
        assertThat(offered()).isFalse()
    }

    private companion object {
        const val COMPOSER = "composer"
        const val TRANSCRIPT = "transcript"

        fun lines(n: Int) = (1..n).joinToString("\n") { "Line $it of a long prompt about the login redirect" }
    }
}
