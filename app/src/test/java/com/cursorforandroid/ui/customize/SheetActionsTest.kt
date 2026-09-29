package com.cursorforandroid.ui.customize

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet's header actions on their own: compact controls that read their state, take a tap while enabled, and dim otherwise. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SheetActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun `each header action shows its label, is touch-sized, and only an enabled one takes the tap`() {
        var taps = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Row {
                    SheetHeaderAction(readAllAction(3) { taps++ })
                    SheetHeaderAction(SheetAction("Archive finished", enabled = false, onClick = { taps += 100 }))
                    SheetHeaderAction(SheetAction("Reset", onClick = { taps += 10 }))
                }
            }
        }
        compose.onNodeWithText(READ_ALL).assertIsDisplayed()
        compose.onNodeWithTag("sheet-header-action-$READ_ALL").assertIsEnabled().assertHeightIsAtLeast(CursorDimens.touchTarget)
        compose.onNodeWithTag("sheet-header-action-Reset").assertIsEnabled().assertHeightIsAtLeast(CursorDimens.touchTarget)
        compose.onNodeWithTag("sheet-header-action-Archive finished").assertIsNotEnabled()

        compose.onNodeWithTag("sheet-header-action-$READ_ALL").performClick()
        compose.onNodeWithTag("sheet-header-action-Archive finished").performClick()
        compose.onNodeWithTag("sheet-header-action-Reset").performClick()
        assertThat(taps).isEqualTo(11)
    }

    @Test
    fun `read all says how many are unread and goes off when none is`() {
        var unread by mutableIntStateOf(2)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SheetHeaderAction(readAllAction(unread) { unread = 0 }) }
        }
        val readAll = compose.onNodeWithTag("sheet-header-action-$READ_ALL")
        readAll.assertIsEnabled().assert(stateIs("2 unread chats"))

        readAll.performClick()
        readAll.assertIsNotEnabled().assert(stateIs("Nothing unread"))

        unread = 1
        readAll.assertIsEnabled().assert(stateIs("1 unread chat"))
    }
}
