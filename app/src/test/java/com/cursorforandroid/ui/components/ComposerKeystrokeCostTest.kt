package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelChoice
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RecomposeCounter
import com.cursorforandroid.util.RecomposeScopes
import com.cursorforandroid.util.threadAllocatedBytes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a keystroke costs the composer, with its text hoisted into the owner as every screen does. The owner and the
 * composer's body run again for the changed value; the field, its decoration, the `/` popover, the "+" menu and the
 * footer do not, in a short draft or one of several kilobytes. A bare text field beside it, typed into the same way,
 * is what the text field and the test's input cost on their own, which grows with the text whatever the composer does;
 * what the composer allocates beyond that is its overhead.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerKeystrokeCostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scopes = RecomposeScopes()
    private var data: CompositionData? = null
    private var value by mutableStateOf("")
    private val bare = TextFieldState()

    private val composerField get() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(COMPOSER)))
    private val bareField get() = compose.onNode(hasSetTextAction() and hasTestTag(BARE))

    @After fun tearDown() = RecomposeCounter.uninstall()

    private fun show() {
        val opus = LiveModelCatalog.model("claude-opus-5.5")
        var modePill by mutableStateOf<ModePills.Pill?>(null)
        var model by mutableStateOf<ModelChoice?>(ModelChoice(opus, opus.defaultVariant))
        val plusMenu = ComposerMenuActions(onPickMedia = {})
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(scopes) }
            val d = currentComposer.compositionData
            SideEffect { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    ComposerBox(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = "Follow up…",
                        onSend = {},
                        plusMenu = plusMenu,
                        modelLabel = model?.label,
                        onModel = {},
                        modePill = modePill,
                        onModePill = { modePill = it },
                        extendedModes = true,
                        models = LiveModelCatalog.models,
                        currentModel = model,
                        onPickModel = { model = it },
                        modifier = Modifier.testTag(COMPOSER),
                    )
                    // As wide as the composer's field and in its type, so it lays out the same lines of the same text.
                    BasicTextField(
                        bare,
                        Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerPadding + CursorDimens.composerTextInset).testTag(BARE),
                        textStyle = CursorTheme.typography.input.copy(color = CursorTheme.colors.textPrimary),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 10),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Per keystroke into the composer: recompose scopes that ran, the app's composable bodies that ran by name, and
     * the median bytes allocated; and the median bytes a keystroke into the bare field with the same text allocates.
     */
    private class Measured(val scopes: Double, val appScopes: Double, val bodies: Map<String, Int>, val composerKb: Long, val bareKb: Long, val report: String) {
        /** What the composer allocates for a keystroke beyond what the text field itself does. */
        val overheadKb get() = composerKb - bareKb
    }

    private fun measure(label: String, draft: String): Measured {
        value = draft
        compose.runOnIdle { bare.setTextAndPlaceCursorAtEnd(draft) }
        val bareKb = medianKb(bareField)
        composerField.requestFocus()
        repeat(WARM_UP) { composerField.performTextInput("w"); compose.waitForIdle() }
        scopes.observeAll(data!!)
        scopes.reset()
        RecomposeCounter.install()
        val composerKb = medianKb(composerField, warmUp = 0)
        val bodies = RecomposeCounter.snapshot().filterKeys { "CursorTheme.<get-" !in it }.mapValues { it.value / KEYSTROKES }.filterValues { it > 0 }
        RecomposeCounter.uninstall()
        val perKeystroke = scopes.scopes / KEYSTROKES.toDouble()
        val appScopes = scopes.byName.filterKeys { it.startsWith(APP) }.values.sum() / KEYSTROKES.toDouble()
        val report = "keystroke [$label, ${value.length} chars] scopes=${"%.2f".format(perKeystroke)} (app ${"%.2f".format(appScopes)}) composer=${composerKb}KB bare field=${bareKb}KB " +
            "overhead=${composerKb - bareKb}KB\n  bodies: $bodies\n${scopes.top()}"
        println(report)
        return Measured(perKeystroke, appScopes, bodies, composerKb, bareKb, report)
    }

    private fun medianKb(field: SemanticsNodeInteraction, warmUp: Int = WARM_UP): Long {
        field.requestFocus()
        repeat(warmUp) { field.performTextInput("w"); compose.waitForIdle() }
        val bytes = LongArray(KEYSTROKES) { i ->
            val before = threadAllocatedBytes()
            field.performTextInput(if (i % 6 == 5) " " else "a")
            compose.waitForIdle()
            threadAllocatedBytes() - before
        }
        bytes.sort()
        return bytes[KEYSTROKES / 2] / 1024
    }

    private fun assertBodyAlone(measured: Measured) {
        // The owner's lambda, which reads the hoisted value; the composer's body, which takes it, is a function. The
        // text field's own cursor handle and the popover's window rerun a scope now and then, which are not the app's.
        assertWithMessage(measured.report).that(measured.appScopes).isAtMost(1.0)
        assertWithMessage(measured.report).that(measured.bodies["ui.components.ComposerBox"]).isEqualTo(1)
        assertWithMessage(measured.report).that(measured.bodies.keys.filter { it.startsWith("ui.components.ComposerBox.") }).isEmpty()
        assertWithMessage(measured.report).that(measured.bodies.keys).containsNoneOf(
            "ui.components.ComposerTextField",
            "ui.components.ComposerFooterMiddle",
            "ui.components.ComposerPlaceholder",
            "ui.components.ComposerPlusMenu",
            "ui.components.SlashCommandPopover",
            "ui.components.scrollEdgeFade",
        )
    }

    @Test
    fun `a keystroke reruns the owner and the composer's body, not the field, the popover or the footer`() {
        show()
        val short = measure("short draft", "")
        assertBodyAlone(short)
        assertWithMessage(short.report).that(short.overheadKb).isAtMost(MAX_OVERHEAD_KB)
    }

    @Test
    fun `a keystroke in a draft of several kilobytes reruns no more of the composer than one in a short draft`() {
        show()
        val short = measure("short draft", "")
        val long = measure("long draft", LONG_DRAFT)
        assertBodyAlone(long)
        assertThat(value).startsWith(LONG_DRAFT)
        // The overhead still grows with the draft, ~20 KB over 5 KB of text, as much as before; this keeps it from more.
        assertWithMessage("${short.report}\n${long.report}").that(long.overheadKb - short.overheadKb).isAtMost(MAX_LONG_DRAFT_EXTRA_KB)
    }

    private companion object {
        const val COMPOSER = "composer"
        const val BARE = "bare-field"
        const val WARM_UP = 40
        const val KEYSTROKES = 90
        const val APP = "com.cursorforandroid."
        /** Rerunning the field, its decoration and the footer's lambdas for every keystroke cost ~44 KB; ~24 KB without. */
        const val MAX_OVERHEAD_KB = 34L
        const val MAX_LONG_DRAFT_EXTRA_KB = 40L
        val LONG_DRAFT = "Refactor the transcript engine so replays share one parse; keep the golden files. ".repeat(60)
    }
}
