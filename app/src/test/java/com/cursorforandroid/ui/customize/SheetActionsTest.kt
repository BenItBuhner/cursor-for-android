package com.cursorforandroid.ui.customize

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet's Actions card on its own: a list of rows that read their state, take a tap while enabled, and dim otherwise. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SheetActionsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `each action is a row with its label and subtitle, and only an enabled one takes the tap`() {
        var taps = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ActionsSection(
                    listOf(
                        SheetAction(CursorIcons.CheckCheck, "Read all", "3 unread chats", onClick = { taps++ }),
                        SheetAction(CursorIcons.Archive, "Archive finished", "Nothing finished", enabled = false, onClick = { taps += 100 }),
                        SheetAction(CursorIcons.Refresh, "Refresh", onClick = { taps += 10 }),
                    ),
                )
            }
        }
        compose.onNodeWithText("Actions").assertIsDisplayed()
        compose.onNodeWithTag("sheet-actions").assertIsDisplayed()
        compose.onNodeWithText("Read all").assertIsDisplayed()
        compose.onNodeWithText("3 unread chats").assertIsDisplayed()
        compose.onNodeWithText("Nothing finished").assertIsDisplayed()
        compose.onNodeWithTag("sheet-action-Read all").assertIsEnabled()
        compose.onNodeWithTag("sheet-action-Archive finished").assertIsNotEnabled()

        compose.onNodeWithTag("sheet-action-Read all").performClick()
        compose.onNodeWithTag("sheet-action-Archive finished").performClick()
        compose.onNodeWithTag("sheet-action-Refresh").performClick()
        assertThat(taps).isEqualTo(11)
    }

    @Test
    fun `the read-all action says how many are unread and goes off when none is`() {
        var unread by mutableIntStateOf(2)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { ActionsSection(listOf(readAllAction(unread) { unread = 0 })) }
        }
        compose.onNodeWithText("2 unread chats").assertIsDisplayed()
        compose.onNodeWithTag("sheet-action-$READ_ALL").assertIsEnabled()

        compose.onNodeWithTag("sheet-action-$READ_ALL").performClick()
        compose.onNodeWithText("Nothing unread").assertIsDisplayed()
        compose.onNodeWithTag("sheet-action-$READ_ALL").assertIsNotEnabled()

        unread = 1
        compose.onNodeWithText("1 unread chat").assertIsDisplayed()
        compose.onNodeWithTag("sheet-action-$READ_ALL").assertIsEnabled()
    }
}
