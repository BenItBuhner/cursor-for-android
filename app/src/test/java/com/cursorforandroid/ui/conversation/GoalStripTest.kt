package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The goal strip's states, and its place above the queue's rows and the composer. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class GoalStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val objective = "Ship the 0.3.10 release: every open PR of the panel worker merged, CI green on main, the tag pushed and the release notes published with screenshots of the new goal strip."
    private val t0 = 1_789_380_000_000L

    private fun show(goal: Goal, clock: () -> Long) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                GoalStrip(goal = goal, clock = clock)
            }
        }
    }

    @Test
    fun `an active goal shows its label, the objective, and a count that ticks`() {
        var now = t0 + 4_512_000L
        show(Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = t0), clock = { now })
        compose.onNodeWithTag("goal-label", useUnmergedTree = true).assertTextContains("Goal active")
        compose.onNodeWithTag("goal-elapsed", useUnmergedTree = true).assertTextContains(" · 1h 15m 12s")
        compose.onNodeWithTag("goal-objective", useUnmergedTree = true).assertTextContains(objective)
        compose.onNodeWithTag("goal-details", useUnmergedTree = true).assertDoesNotExist()

        now += 3_000L
        compose.mainClock.advanceTimeBy(1_100L)
        compose.waitForIdle()
        compose.onNodeWithTag("goal-elapsed", useUnmergedTree = true).assertTextContains(" · 1h 15m 15s")
    }

    @Test
    fun `a paused goal holds its accrued time and says so`() {
        var now = t0
        show(Goal(objective, GoalStatus.PAUSED, activeDurationMs = 5_100_000L, lastChange = Goal.Change.Paused), clock = { now })
        compose.onNodeWithTag("goal-label", useUnmergedTree = true).assertTextContains("Goal paused")
        compose.onNodeWithTag("goal-elapsed", useUnmergedTree = true).assertTextContains(" · 1h 25m 0s")
        now += 60_000L
        compose.mainClock.advanceTimeBy(2_100L)
        compose.waitForIdle()
        compose.onNodeWithTag("goal-elapsed", useUnmergedTree = true).assertTextContains(" · 1h 25m 0s")
    }

    @Test
    fun `a completed goal says so, with the time it took`() {
        show(Goal(objective, GoalStatus.COMPLETE, activeDurationMs = 7_260_000L, lastChange = Goal.Change.Completed), clock = { t0 })
        compose.onNodeWithTag("goal-label", useUnmergedTree = true).assertTextContains("Goal completed")
        compose.onNodeWithTag("goal-elapsed", useUnmergedTree = true).assertTextContains(" · 2h 1m 0s")
    }

    @Test
    fun `an updated goal leads with the change, and one whose time no source told shows no count`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    GoalStrip(goal = Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = t0, lastChange = Goal.Change.Updated), clock = { t0 + 5_000L }, modifier = Modifier.fillMaxWidth())
                    GoalStrip(goal = Goal("Bare", GoalStatus.ACTIVE), clock = { t0 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        compose.onNodeWithText("Goal updated", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(" · 5s", useUnmergedTree = true).assertIsDisplayed()
        // No timestamp anywhere: the label stands alone, no count made up.
        assertThat(compose.onAllNodesWithTag("goal-elapsed", useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `a tap opens the strip onto the whole objective and the details, a second tap closes it`() {
        show(Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = t0, continuationCount = 3, source = Goal.Source.Account), clock = { t0 + 65_000L })
        compose.onNodeWithTag("goal-details", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("goal-strip").performClick()
        // The strip grows into place over a few frames; let the animation run out before reading its bounds.
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithTag("goal-details", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Cursor keeps working toward this goal between turns until it is complete.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3 turns started by Cursor for it.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("From the goal Cursor keeps on the chat.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("goal-strip").performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onNodeWithTag("goal-details", useUnmergedTree = true).assertDoesNotExist()
        // The details name what the status means, in every state.
        assertThat(details(Goal("x", GoalStatus.PAUSED), null)).containsExactly("Held: Cursor starts no new turn for this goal until it is resumed.", "From the goal calls and continuations in this chat's transcript.").inOrder()
        assertThat(details(Goal("x", GoalStatus.COMPLETE, activeDurationMs = 60_000L, continuationCount = 1), "1m 0s").first()).isEqualTo("Complete after 1m 0s of work.")
        assertThat(details(Goal("x", GoalStatus.COMPLETE, continuationCount = 1), null)).contains("1 turn started by Cursor for it.")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the strip stacks above the queue's rows and the composer without overlapping either`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    GoalStrip(goal = Goal(objective, GoalStatus.ACTIVE, accruingSinceMillis = t0), clock = { t0 + 1_000L }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    AccountQueueRows(
                        queue = listOf(PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS)),
                        inFlightIds = emptySet(),
                        onSendNow = {},
                        onRemove = {},
                        onUpdate = { _, _ -> },
                        onEditing = { _, _ -> },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                    ComposerBox(value = "", onValueChange = {}, placeholder = "Follow up…", onSend = {}, isRunning = true, onStop = {}, plusMenu = ComposerMenuActions(onPickMedia = {}), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag("goal-strip").fetchSemanticsNode().boundsInRoot
        val queue = compose.onNodeWithTag("account-queue").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithText("Follow up…").fetchSemanticsNode().boundsInRoot
        assertThat(strip.bottom).isAtMost(queue.top)
        assertThat(queue.bottom).isAtMost(composer.top)
        // The strip's surface and the queue's row share their edges: both are docked cards, inset alike (DockedCardsTest).
        val row = compose.onNodeWithTag("account-queue-row").fetchSemanticsNode().boundsInRoot
        assertThat(strip.left).isEqualTo(row.left)
        assertThat(strip.right).isEqualTo(row.right)
    }
}
