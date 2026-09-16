package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
import java.io.File

/**
 * The wall of eighteen injected turns Bennett's coordinator chat showed as eighteen rows (see [CoordinatorFixtures],
 * `event_wall.json`), between two of the coordinator's updates and before two newer events: one line for the wall —
 * "18 events · 11 GitHub · 7 subagents · 1h 59m span" — and the newest pair open on its own; then the same wall
 * opened, every event a compact row of subject, verb, actor and age, the repeat counted once. Written to
 * `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CoordinatorEventGroupsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val wall = CoordinatorFixtures.json("event_wall.json").getValue("turns").jsonArray.map { it.jsonObject }
    private val lastTurnAt = wall.last().getValue("timestampMillis").jsonPrimitive.long

    @Before
    fun pinClock() {
        AppClock.nowMillis = { lastTurnAt + 41 * 60_000L }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun message(id: String, text: String) = listOf(
        ActivityGroup("$id-group", listOf(ToolCall("$id-call", ToolNames.USER_MESSAGE_TOOL, ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(text)))),
        RunFooter("$id-footer", "run-$id", RunStatus.FINISHED, 38_000, emptyList()),
    )

    private fun turn(index: Int, id: String = "inject-$index"): List<TimelineItem> =
        SystemNotifications.parse(id, wall[index].getValue("text").jsonPrimitive.content, wall[index].getValue("timestampMillis").jsonPrimitive.long)!!.items

    /** The chat: an update, the eighteen silent turns, Bennett asking, the answer, two newer silent turns. */
    private fun items(): List<TimelineItem> {
        val firstAt = wall.first().getValue("timestampMillis").jsonPrimitive.long
        val newer = listOf(
            turn(3, "newer-0").map { (it as SystemNotification).copy(timestampMillis = lastTurnAt + 12 * 60_000L) },
            turn(16, "newer-1").map { (it as SystemNotification).copy(timestampMillis = lastTurnAt + 30 * 60_000L) },
        ).flatten()
        return listOf(UserMessage("u1", "Queue the render shards and keep me posted.", firstAt - 9 * 60_000L)) +
            message("m1", "The render shards are queued: kitchen v2 on R6, backyard v2 on Y1, the limb passes on their own workers. I will report as each lands.") +
            wall.indices.flatMap { turn(it) } +
            listOf(UserMessage("u2", "Where are we on the shards?", lastTurnAt + 4 * 60_000L)) +
            message("m2", "All four shards rendered and their PRs are merged (#57–#63); #65 and #66 are yours and stay open. Nothing needs a decision.") +
            newer
    }

    private fun rows(): List<TranscriptRow> = TranscriptRows.of(CoordinatorTranscript.present(items(), coordinatorMode = true), coordinatorMode = true)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, coordinatorMode = true)) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun coordinatorEventGroups() {
        val rows = rows()
        val groups = rows.filterIsInstance<TranscriptRow.Events>()
        assertThat(groups).hasSize(2)
        assertThat(groups[0].summary.text).isEqualTo("18 events · 11 GitHub · 7 subagents · 1h 59m span")
        assertThat(groups[0].startsOpen).isFalse()
        assertThat(groups[1].summary.text).isEqualTo("2 events · 1 GitHub · 1 subagent · 18m span")
        assertThat(groups[1].startsOpen).isTrue()
        show(rows)
        // The wall closed, the newest pair open: two rows of the twenty on screen.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == 2 }
        capture("72_coordinator_event_groups")
    }

    @Test
    fun coordinatorEventGroupsOpened() {
        show(rows())
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Show events").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("event-row")).fetchSemanticsNodes().size == 19 }
        capture("73_coordinator_event_groups_open")
    }
}
