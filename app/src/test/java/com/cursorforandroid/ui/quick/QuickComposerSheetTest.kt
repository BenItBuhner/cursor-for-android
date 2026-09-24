package com.cursorforandroid.ui.quick

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.ui.compose.NewAgentUiState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The quick composer's context row names the branch the way the New Chat pane does: "current" for a machine's
 * checkout left unpicked, "default" on Cloud, and the ref once one is picked — folded into the one chip and opened.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QuickComposerSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val repo = Repository("https://github.com/bennett/codex-poly-bot")
    private val onMachine = NewAgentUiState(
        repositories = listOf(repo),
        selectedRepo = repo,
        machineStartEnabled = true,
        selectedDevice = DeviceTarget.machine("bennett"),
    )

    @Test
    fun `the branch chip says current on a machine's checkout, default on Cloud, and the ref once picked`() {
        val scene = mutableStateOf(onMachine to false)
        compose.setContent {
            val (state, expanded) = scene.value
            CursorTheme(mode = ThemeMode.Dark) {
                QuickComposerSheet(
                    state = state,
                    commands = SlashCatalog.BUILT_IN,
                    plusMenu = null,
                    contextExpanded = expanded,
                    onExpandContext = {},
                    onDismiss = {},
                    onCancel = {},
                    onSend = {},
                    onCancelSend = {},
                    onPrompt = {},
                )
            }
        }
        compose.onNodeWithText("codex-poly-bot · current · bennett").assertIsDisplayed()

        scene.value = onMachine to true
        compose.onNodeWithText("current").assertIsDisplayed()

        scene.value = onMachine.copy(selectedDevice = DeviceTarget.Cloud) to false
        compose.onNodeWithText("codex-poly-bot · default · Cloud").assertIsDisplayed()

        scene.value = onMachine.copy(ref = "feature/accuracy") to false
        compose.onNodeWithText("codex-poly-bot · feature/accuracy · bennett").assertIsDisplayed()
    }
}
