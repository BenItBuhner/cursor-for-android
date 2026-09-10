package com.cursorforandroid.ui.agents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SnoozeDuration
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
class SnoozeChatDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the picker shows every duration on one screen`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SnoozeChatDialog(onPick = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Snooze").assertIsDisplayed()
        listOf("5m", "15m", "30m", "1h", "3h", "6h", "12h", "1d", "Forever").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `tapping a cell reports that duration from now`() {
        var picked: Long? = null
        val now = 1_800_000_000_000L
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SnoozeChatDialog(onPick = { picked = it }, onDismiss = {}, nowMillis = now)
            }
        }
        compose.onNodeWithContentDescription("Snooze 15m").performClick()
        assertThat(picked).isEqualTo(SnoozeDuration.FifteenMinutes.untilMillis(now))
    }

    @Test
    fun `holding a chat row offers Snooze and then the duration pad`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AgentRowItem(
                    row = row("bc-1", "Morning standup"),
                    selected = false,
                    prefs = ListPreferences(),
                    actions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                )
            }
        }
        compose.onNodeWithText("Morning standup").performTouchInput { longClick() }
        compose.onNodeWithText("Snooze").assertIsDisplayed().performClick()
        compose.onNodeWithText("Forever").assertIsDisplayed()
        compose.onNodeWithContentDescription("Snooze 1h").assertIsDisplayed()
    }

    private fun row(id: String, name: String) = AgentRow(
        agent = Agent(
            id = id,
            name = name,
            lifecycle = AgentLifecycle.IDLE,
            runStatus = RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
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
