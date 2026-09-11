package com.cursorforandroid.ui.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pasted-key path: what survives a recreation of the screen, and what deliberately does not. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SignInScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a pasted key and its reveal are never handed to saved instance state`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CursorTheme(mode = ThemeMode.Dark) { SignInScreen(graph) }
        }

        compose.onNodeWithText("Use an API key instead").performClick()
        compose.onNode(hasSetTextAction()).performTextInput(SENTINEL)
        compose.onNodeWithContentDescription("Show key").performClick()
        compose.onNodeWithText(SENTINEL).assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide key").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        // The disclosure is device-local UI state and is remembered; the key is not, so the field is empty again
        // (its placeholder is back) and it is masked once more.
        compose.onNodeWithContentDescription("Hide API key sign-in").assertIsDisplayed()
        compose.onNodeWithText("key_…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show key").assertIsDisplayed()
    }

    private companion object {
        const val SENTINEL = "key_sentinel_never_in_a_bundle"
    }
}
