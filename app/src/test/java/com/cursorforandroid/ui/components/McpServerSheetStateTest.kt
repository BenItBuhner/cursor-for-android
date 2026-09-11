package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Filling in this form means leaving the app for a password manager, which is exactly when a low-memory device kills
 * the process. The form comes back, minus the two fields that hold credentials: the instance-state bundle is written
 * to disk, and a token restored from there is worse than one that has to be pasted again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class McpServerSheetStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setSheet(restorer: StateRestorationTester) {
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                McpServerSheet(
                    server = null,
                    others = emptyList<McpServer>(),
                    onSave = {},
                    onDelete = null,
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `a half-filled form comes back after the process is killed`() {
        val restorer = StateRestorationTester(compose)
        setSheet(restorer)

        compose.onNodeWithText("linear").performTextInput("my-server")
        compose.onNodeWithText("https://mcp.linear.app/mcp").performTextInput("https://example.test/mcp")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("my-server").assertExists()
        compose.onNodeWithText("https://example.test/mcp").assertExists()
    }

    @Test
    fun `the header that carries the token is not written to the bundle`() {
        val restorer = StateRestorationTester(compose)
        setSheet(restorer)

        compose.onNodeWithText("Authorization: Bearer …").performTextInput("Authorization: Bearer sk-secret")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Authorization: Bearer sk-secret").assertDoesNotExist()
        // The placeholder is back, so the field is empty rather than gone.
        compose.onNodeWithText("Authorization: Bearer …").assertExists()
    }
}
