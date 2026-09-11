package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's Plan and Multitask pills and its orange `/command` highlight: what the owner holds and sends stays
 * the prompt it always was (`/multitask …` in front, plan mode as a flag), and only the presentation changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerPillsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var value by mutableStateOf("")
    private var planMode by mutableStateOf(false)
    private val planChanges = mutableListOf<Boolean>()

    private fun show(modelLabel: String = "Claude Fable 5.1", plan: Boolean = true, mode: ThemeMode = ThemeMode.Dark, width: Dp? = null) {
        compose.setContent {
            CursorTheme(mode = mode) {
                ComposerBox(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "Ask Cursor to build, fix bugs, explore",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = {}),
                    modelLabel = modelLabel,
                    onModel = {},
                    planMode = planMode,
                    onPlanMode = if (plan) ({ on -> planMode = on; planChanges += on }) else null,
                    modifier = Modifier.testTag("composer").then(if (width != null) Modifier.width(width) else Modifier),
                )
            }
        }
    }

    private val field get() = compose.onNode(hasSetTextAction())

    /** The field's own text (its placeholder is part of `Text`, so the draft is read off `EditableText`). */
    private fun shown(): String = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun pillCount(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().size

    @Test
    fun `plan mode is a pill between plus and the model chip, and its cross puts it off`() {
        planMode = true
        show()

        compose.onNodeWithText("Plan").assertIsDisplayed()
        val plus = bounds(hasContentDescription("Add to prompt"))
        val pill = bounds(hasText("Plan"))
        val model = bounds(hasText("Claude Fable 5.1"))
        val send = bounds(hasContentDescription("Send"))
        assertThat(pill.left).isGreaterThan(plus.right)
        assertThat(pill.right).isLessThan(model.left)
        assertThat(model.right).isLessThan(send.left)
        // The model chip is the model's name alone: plan mode no longer rides on it as "· Plan".
        compose.onAllNodes(hasText("· Plan", substring = true)).assertCountEquals(0)

        compose.onNodeWithContentDescription("Remove Plan").performClick()
        compose.runOnIdle {
            assertThat(planMode).isFalse()
            assertThat(planChanges).containsExactly(false)
        }
        assertThat(pillCount("Plan")).isEqualTo(0)
    }

    @Test
    fun `a multitask prompt shows its text beside the pill and is sent with the token in front`() {
        value = "/multitask fix the flaky test"
        show()

        compose.onNodeWithText("Multitask").assertIsDisplayed()
        assertThat(shown()).isEqualTo("fix the flaky test")

        field.performTextInput(" now")
        compose.runOnIdle { assertThat(value).isEqualTo("/multitask fix the flaky test now") }
        assertThat(shown()).isEqualTo("fix the flaky test now")

        compose.onNodeWithContentDescription("Remove Multitask").performClick()
        compose.runOnIdle { assertThat(value).isEqualTo("fix the flaky test now") }
        assertThat(shown()).isEqualTo("fix the flaky test now")
        assertThat(pillCount("Multitask")).isEqualTo(0)
    }

    @Test
    fun `typing multitask and a space turns it into the pill, and later typing keeps the token in front`() {
        show()

        field.performTextInput("/multitask")
        compose.waitForIdle()
        assertThat(pillCount("Multitask")).isEqualTo(0)
        assertThat(shown()).isEqualTo("/multitask")
        compose.runOnIdle { assertThat(value).isEqualTo("/multitask") }

        field.performTextInput(" ")
        compose.waitUntil { pillCount("Multitask") == 1 }
        assertThat(shown()).isEmpty()
        compose.runOnIdle { assertThat(value).isEqualTo("/multitask ") }

        field.performTextInput("ship it")
        compose.runOnIdle { assertThat(value).isEqualTo("/multitask ship it") }
        assertThat(shown()).isEqualTo("ship it")
    }

    @Test
    fun `typing plan and a space turns plan mode on and never reaches the prompt`() {
        show()

        field.performTextInput("/plan ship it")
        compose.waitUntil { planMode }
        compose.waitUntil { pillCount("Plan") == 1 }
        assertThat(shown()).isEqualTo("ship it")
        compose.runOnIdle {
            assertThat(value).isEqualTo("ship it")
            assertThat(planChanges).containsExactly(true)
        }
    }

    @Test
    fun `picking plan or multitask from the popover makes the pill instead of text`() {
        show()

        field.performTextInput("/pl")
        compose.waitUntil { compose.onAllNodes(hasText("Explore first and draft a plan", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/plan").performClick()
        compose.waitUntil { planMode }
        assertThat(shown()).isEmpty()
        compose.runOnIdle { assertThat(value).isEmpty() }

        // Multitask picked next replaces the plan: one slot, one pill.
        field.performTextInput("/mu")
        compose.waitUntil { compose.onAllNodes(hasText("Orchestrate multiple subagents", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/multitask").performClick()
        compose.waitUntil { value == "/multitask " }
        assertThat(shown()).isEmpty()
        compose.runOnIdle {
            assertThat(planMode).isFalse()
            assertThat(planChanges).containsExactly(true, false).inOrder()
        }
        compose.onNodeWithText("Multitask").assertIsDisplayed()
        assertThat(pillCount("Plan")).isEqualTo(0)

        field.performTextInput("ship it")
        compose.runOnIdle { assertThat(value).isEqualTo("/multitask ship it") }
    }

    @Test
    fun `typing the other mode replaces the pill, in the field and in the owner`() {
        planMode = true
        show()
        compose.onNodeWithText("Plan").assertIsDisplayed()

        field.performTextInput("/multitask ")
        compose.waitUntil { pillCount("Multitask") == 1 }
        compose.runOnIdle {
            assertThat(value).isEqualTo("/multitask ")
            assertThat(planMode).isFalse()
        }
        assertThat(pillCount("Plan")).isEqualTo(0)

        field.performTextInput("/plan ")
        compose.waitUntil { pillCount("Plan") == 1 }
        compose.runOnIdle {
            assertThat(value).isEmpty()
            assertThat(planMode).isTrue()
        }
        assertThat(pillCount("Multitask")).isEqualTo(0)
        assertThat(shown()).isEmpty()
    }

    @Test
    fun `multitask from the plus menu puts a plan off`() {
        planMode = true
        show()

        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.onNodeWithText("Multitask").performClick()
        compose.waitUntil { value == "/multitask " }
        compose.runOnIdle { assertThat(planMode).isFalse() }
        compose.waitUntil { pillCount("Plan") == 0 }
        assertThat(pillCount("Multitask")).isEqualTo(1)
    }

    @Test
    fun `an owner holding both modes is shown one pill, never two`() {
        // The view models keep the two exclusive; should one not, the command in the text is what the field hides,
        // so that is the pill worn.
        value = "/multitask fix"
        planMode = true
        show()

        assertThat(pillCount("Multitask")).isEqualTo(1)
        assertThat(pillCount("Plan")).isEqualTo(0)
    }

    @Test
    fun `every other command stays in the text`() {
        show()

        field.performTextInput("/goal ship it /review ")
        compose.runOnIdle { assertThat(value).isEqualTo("/goal ship it /review ") }
        assertThat(shown()).isEqualTo("/goal ship it /review ")
        assertThat(pillCount("Plan") + pillCount("Multitask")).isEqualTo(0)
        assertThat(planChanges).isEmpty()
    }

    @Test
    fun `without plan mode to set, plan is text like any other command`() {
        show(plan = false)

        field.performTextInput("/plan ship it")
        compose.runOnIdle { assertThat(value).isEqualTo("/plan ship it") }
        assertThat(shown()).isEqualTo("/plan ship it")
        assertThat(pillCount("Plan")).isEqualTo(0)
    }

    @Test
    fun `a long model name gives way to the pill rather than pushing send out`() {
        value = "/multitask fix"
        show(modelLabel = "Claude Fable 5.1 Thinking (Max) with the extended context window", width = 300.dp)

        val box = bounds(hasTestTag("composer"))
        val plus = bounds(hasContentDescription("Add to prompt"))
        val multitask = bounds(hasText("Multitask"))
        val model = bounds(hasText("Claude Fable 5.1", substring = true))
        val send = bounds(hasContentDescription("Send"))

        compose.onNodeWithText("Multitask").assertIsDisplayed()
        assertThat(multitask.left).isGreaterThan(plus.right)
        // The chip is still there, between the pill and send, only shorter; send sits inside the box.
        assertThat(model.width).isGreaterThan(0f)
        assertThat(model.left).isAtLeast(multitask.right)
        assertThat(model.right).isAtMost(send.left)
        assertThat(send.right).isAtMost(box.right)
    }

    @Test
    fun `slash commands are painted in the Cursor orange and the rest of the text is not`() {
        show()

        field.performTextInput("ship it")
        compose.waitForIdle()
        assertThat(orangePixelsInField()).isEqualTo(0)

        field.performTextClearance()
        field.performTextInput("/goal ship it")
        compose.waitForIdle()
        assertThat(orangePixelsInField()).isGreaterThan(0)
    }

    @Test
    fun `the highlight is drawn in the light theme too`() {
        show(mode = ThemeMode.Light)

        field.performTextInput("/review ship it")
        compose.waitForIdle()
        assertThat(orangePixelsInField()).isGreaterThan(0)
    }

    /**
     * Pixels of the brand orange (#F54E00) inside the field, anti-aliased against either canvas: far more red than
     * blue, and red well ahead of green — which no shade of the near-white or near-black text, of the placeholder,
     * or of either background is. The window is rasterised the way Roborazzi does it, by drawing the decor view.
     */
    private fun orangePixelsInField(): Int {
        val bounds = field.fetchSemanticsNode().boundsInWindow
        val window = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
            for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val c = Color(window.getPixel(x, y))
                if (c.red - c.blue > 0.45f && c.red - c.green > 0.25f) count++
            }
        }
        return count
    }
}
