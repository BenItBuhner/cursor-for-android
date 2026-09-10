package com.cursorforandroid.ui.agents

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the picker shows every duration on one screen`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SnoozeChatDialog(onPick = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Snooze").assertIsDisplayed()
        compose.onNodeWithText("Soon").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Longer").assertIsDisplayed()
        listOf("5 minutes", "15 minutes", "30 minutes", "1 hour", "3 hours", "6 hours", "12 hours", "1 day", "Forever")
            .forEach { title ->
                compose.onNodeWithText(title).assertIsDisplayed()
            }
    }

    @Test
    fun `tapping a chip reports that duration from now`() {
        var picked: Long? = null
        val now = 1_800_000_000_000L
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SnoozeChatDialog(onPick = { picked = it }, onDismiss = {}, nowMillis = now)
            }
        }
        compose.onNodeWithContentDescription("Snooze 15 minutes").performClick()
        assertThat(picked).isEqualTo(SnoozeDuration.FifteenMinutes.untilMillis(now))
    }

    @Test
    fun `holding a chat row offers Snooze and then the duration sheet`() {
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
        compose.onNodeWithContentDescription("Snooze 1 hour").assertIsDisplayed()
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
