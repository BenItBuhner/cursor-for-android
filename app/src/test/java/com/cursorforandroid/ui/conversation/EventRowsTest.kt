package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wall of injected turns (see `event_wall.json`) on screen: the stretch's one line says how many; opened, the
 * group's line says of what, and opens onto every event as its own compact row — subject, verb, actor, age, a count
 * for a repeat — each still opening onto its own report and its agent's chat; the newest group open on its own only when small.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class EventRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }
    private val opened = mutableListOf<String>()

    private fun wallItems(count: Int = wall.size): List<TimelineItem> = wall.take(count).flatMapIndexed { index, turn ->
        SystemNotifications.parse("inject-$index", turn.getValue("text").jsonPrimitive.content, turn.getValue("timestampMillis").jsonPrimitive.long)!!.items
    }

    @Before
    fun pinClock() {
        // Two hours and a minute after the wall's last turn: its rows read "2h", "3h", "4h".
        AppClock.nowMillis = { wall.last().getValue("timestampMillis").jsonPrimitive.long + 121 * 60_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(items: List<TimelineItem>): List<TranscriptRow> {
        val rows = TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = { opened += it }, coordinatorMode = true)) {
                    Column { rows.forEach { TranscriptRowView(it) } }
                }
            }
        }
        compose.waitForIdle()
        return rows
    }

    /** The one line the stretch between two messages is: opened, it lists its steps, the events behind their own line. */
    private fun openStretch() {
        compose.onNodeWithTag("stretch").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("event-group")).assertCountEquals(0)
        compose.onAllNodesWithText("18 events").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-group")).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun `the wall is one closed line that opens onto every event as its compact row`() {
        val rows = show(wallItems())
        val stretch = rows.single() as TranscriptRow.Stretch
        val group = (stretch.listed.single() as TranscriptRow.Entry.Events).group
        // The stretch's line says the count; nothing of the events is on screen until it is opened.
        compose.onNodeWithText("18 events").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        openStretch()
        // Inside: the group's own line, still closed; no "Subagent completed" or "GitHub notification" label anywhere.
        compose.onNodeWithTag("event-group").assertIsDisplayed()
        compose.onNodeWithText("\u00B7 11 GitHub \u00B7 7 subagents \u00B7 1h 59m span").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        assertThat(compose.onAllNodesWithText("GitHub notification").fetchSemanticsNodes()).isEmpty()

        compose.onNodeWithContentDescription("Show events").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == group.events.size }
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(17)
        // A GitHub event: the pull request, the action, the actor, its age.
        compose.onNodeWithText("#64").assertIsDisplayed()
        // Three pull requests were opened, all by Bennett.
        compose.onAllNodesWithText(" \u00B7 opened").assertCountEquals(3)
        compose.onAllNodesWithText(" \u00B7 BenItBuhner").assertCountEquals(3)
        compose.onAllNodesWithText(" \u00B7 merged").assertCountEquals(6)
        // A subagent's: its title and what became of it; its age from the wall's last turn.
        compose.onNodeWithText("Hand & Arm Renders").assertIsDisplayed()
        compose.onNodeWithText("#66").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("4h").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodesWithText("2h").fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodesWithText("Subagent completed").fetchSemanticsNodes()).isEmpty()
        // The repeat is one row counted twice.
        compose.onNodeWithTag("event-count", useUnmergedTree = true).assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Match product sets to their references B").fetchSemanticsNodes()).hasSize(1)
        compose.onNodeWithText("\u00D72").assertIsDisplayed()

        compose.onNodeWithContentDescription("Hide events").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `inside the open group a row still opens onto its report, and a subagent's onto its chat`() {
        show(wallItems(3))
        // The stretch's line, then the group's: three events, closed, as any group of three or more.
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        compose.onAllNodesWithText("3 events").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-group")).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Show events").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == 3 }
        assertThat(compose.onAllNodesWithText("Rendered the hand & arm stills", substring = true).fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Hand & Arm Renders").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Rendered the hand & arm stills", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The pull request's row opens onto its sentence and URL.
        compose.onNodeWithText("#64").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("https://github.com/BenItBuhner/revenue-scaling-pipeline/pull/64", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The subagent's chat is a tap on its arrow.
        compose.onAllNodes(hasTestTag("open-worker")).assertCountEquals(2)
        compose.onAllNodes(hasTestTag("open-worker"))[0].performClick()
        assertThat(opened).containsExactly("bc-00000000-1a2d-5218-8e2e-7464ea74f671")
    }

    @Test
    fun `the newest group of two opens on its own once its stretch is`() {
        val rows = show(wallItems(2))
        val stretch = rows.single() as TranscriptRow.Stretch
        assertThat(((stretch.listed.single() as TranscriptRow.Entry.Events).group).startsOpen).isTrue()
        compose.onNodeWithText("2 events").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("event-row")).assertCountEquals(0)
        compose.onAllNodesWithText("2 events").onFirst().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Hand & Arm Renders").assertIsDisplayed()
        compose.onNodeWithText("#64").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide events").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `a lone event is its compact row, with no group line and no stretch line`() {
        show(wallItems(1))
        compose.onAllNodes(hasTestTag("event-group")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("stretch")).assertCountEquals(0)
        compose.onNodeWithTag("event-row").assertIsDisplayed()
        compose.onNodeWithText("Hand & Arm Renders").assertIsDisplayed()
        compose.onNodeWithText(" \u00B7 completed").assertIsDisplayed()
        compose.onNodeWithText("4h").assertIsDisplayed()
        assertThat(compose.onAllNodes(hasText("events", substring = true)).fetchSemanticsNodes()).isEmpty()
    }
}
