package com.cursorforandroid.ui.agents

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A long-press menu and the rename dialog behind it are exactly what a font-size change or a low-memory kill
 * interrupts, so both are saved state. Nothing here is a credential.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentRowStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setRow(restorer: StateRestorationTester) {
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AgentRowItem(
                    row = row(),
                    selected = false,
                    prefs = ListPreferences(),
                    actions = AgentRowActions({}, {}, {}, {}, { _, _ -> }),
                )
            }
        }
    }

    @Test
    fun `the long-press menu is still open after the process is killed`() {
        val restorer = StateRestorationTester(compose)
        setRow(restorer)

        compose.onNodeWithText("Morning standup").performTouchInput { longClick() }
        compose.onNodeWithText("Archive").assertExists()

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Archive").assertExists()
    }

    private fun row() = AgentRow(
        agent = Agent(
            id = "today",
            name = "Morning standup",
            lifecycle = AgentLifecycle.IDLE,
            runStatus = RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/today",
            createdAtMillis = 0L,
            updatedAtMillis = 0L,
            latestRunId = null,
            repoUrl = null,
            startingRef = null,
        ),
        indicator = AgentIndicator.Read,
        isPinned = false,
        isUnread = false,
        launchedFromThisDevice = false,
    )
}
