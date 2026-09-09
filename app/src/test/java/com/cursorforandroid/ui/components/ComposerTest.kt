package com.cursorforandroid.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the model chip sits next to send, not next to plus`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Ask Cursor to build, fix bugs, explore",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickFiles = {}),
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                )
            }
        }
        compose.waitForIdle()

        val plus = compose.onNodeWithContentDescription("Add to prompt").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithText("Claude Fable 5.1").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot

        assertThat(model.left).isGreaterThan(plus.right)
        assertThat(model.right).isLessThan(send.left)
        val gapToSend = send.left - model.right
        val gapFromPlus = model.left - plus.right
        assertThat(gapToSend).isLessThan(gapFromPlus)
    }
}
