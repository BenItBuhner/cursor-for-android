package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A run the server says failed, on screen: one compact line — "Run failed · <the server's reason> · <when>" — of
 * its own after the stretch while the conversation has not moved past it, inside the stretch (listed when it opens,
 * the summary ending "· failed") once it has; never the banner builds before 0.3.41 drew, not even from the trace
 * such a build left on disk. A reason the line had to cut short opens in full.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RunFailureRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val t = 1_789_600_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { t + 2 * 3_600_000L + 60_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(items: List<TimelineItem>, runActive: Boolean = false) {
        val rows = TranscriptRows.of(items, coordinatorMode = true, runActive = runActive)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(coordinatorMode = true)) {
                    Column { rows.forEach { TranscriptRowView(it) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun failedTurn(reason: String? = "Tool result not found for toolu_status_01") = listOf(
        UserMessage("u1", "So where we at rn", t),
        AssistantMessage("a1", "Checking where the six workers stand."),
        ActivityGroup("g1", listOf(ToolCall("s1", "getAgentStatus", ToolKind.Coordinator, ToolCall.STATUS_INTERRUPTED, "2 agents"))),
        RunFooter("f1", "run-1", RunStatus.ERROR, 41_000, emptyList(), endedAtMillis = t + 41_000, reason = reason),
    )

    @Test
    fun `the newest run's failure is one line after its stretch with the server's reason and the time, and no banner`() {
        show(failedTurn())
        compose.onNodeWithTag("run-failure").assertIsDisplayed()
        compose.onNodeWithText(RUN_FAILED).assertIsDisplayed()
        compose.onNodeWithTag("run-failure-reason", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 Tool result not found for toolu_status_01").assertIsDisplayed()
        compose.onNodeWithTag("run-failure-time", useUnmergedTree = true).assertIsDisplayed()
        // The stretch says what the run did; the failure is not its verb, and no card stands over the chat.
        compose.onNodeWithText("Worked 41s").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Failed after", substring = true).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasTestTag("stretch")).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `once the conversation moves on the failure is a line inside the stretch, the summary ending failed`() {
        show(failedTurn() + UserMessage("u2", "Carry on from where the status check stopped.", t + 160_000))
        assertThat(compose.onAllNodes(hasTestTag("run-failure")).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Worked 41s").assertIsDisplayed()
        compose.onNodeWithText("1 note \u00B7 1 step \u00B7 failed").assertIsDisplayed()
        // Opened, the line is among the steps, last.
        compose.onNodeWithText("Worked 41s").performClick()
        compose.onNodeWithTag("run-failure", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 Tool result not found for toolu_status_01", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `while the chat runs again the failure is no longer its state and reads inside the stretch`() {
        show(failedTurn(), runActive = true)
        assertThat(compose.onAllNodes(hasTestTag("run-failure")).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("1 note \u00B7 1 step \u00B7 failed").assertIsDisplayed()
    }

    @Test
    fun `a failure the server gave no reason for says how long the run had worked, and the banner an earlier build wrote is not drawn`() {
        show(
            listOf(
                UserMessage("u1", "Go.", t),
                // A trace a build before 0.3.41 wrote: its banner, then the footer without a reason of its own.
                NoticeCard("legacy", NoticeCard.RUN_FAILED, null, NoticeTone.Error),
                RunFooter("f1", "run-1", RunStatus.ERROR, 3_000, emptyList(), endedAtMillis = t + 3_000),
            ),
        )
        compose.onNodeWithTag("run-failure").assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 after 3s").assertIsDisplayed()
        // The failure line stands for the turn alone: no stretch, no card.
        compose.onAllNodes(hasTestTag("stretch")).assertCountEquals(0)
        assertThat(compose.onAllNodesWithText("Worked", substring = true).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a reason the line cuts short opens in full`() {
        val long = "Tool result not found for toolu_01CRGAspS8qhi8Wfr74zYnJA: the worker's answer to the status check never reached the coordinator before the turn's deadline, and the run was ended by the infrastructure rather than by the model."
        show(failedTurn(reason = long))
        compose.onNodeWithTag("run-failure").performClick()
        compose.onNodeWithTag("run-failure-reason-full", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(long, useUnmergedTree = true).assertIsDisplayed()
    }
}
