package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.GoalFixtures
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.AccountQueueRows
import com.cursorforandroid.ui.conversation.GoalStrip
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The goal above the composer, and the goal's events in the transcript, rendered from the wire shapes as they arrive
 * (see [GoalFixtures]). Three frames: the strip for an active goal stacked over the account's queue and the composer
 * that filed them; the same strip opened onto the whole objective and its details, for a goal the account says is
 * paused; and the transcript of the goal's turns — the goal set, lifted out of the work into a row of its own, the
 * continuation Cursor started, and the completion — with the strip saying the goal is complete. Same device
 * qualifiers as [AppScreenshotTest]; the clock is pinned so the count reads the same on every run.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class GoalStripScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private val objective = GoalFixtures.shape("createGoal", "args", "toJson").jsonObject.getValue("objective").jsonPrimitive.content
    private val t0 = 1_789_380_000_000L
    private val now = t0 + 4_512_000L

    /** The rows say how long ago each turn was; pinned to the fixture's clock so they read the same on every run. */
    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun replay(fixture: String, runId: String): List<TimelineItem> {
        val source = Buffer().writeUtf8(GoalFixtures.text(fixture))
        val live = TimelineBuilder.LiveRun(runId, timed = false)
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event?.let(live::apply)
        }
        return live.snapshot()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun goalStripOverTheQueueAndTheComposer() {
        val items = listOf(UserMessage("m1", "/goal $objective", t0)) + replay("goal_run.sse", "run-goal-001")
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal.status).isEqualTo(GoalStatus.ACTIVE)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GoalStrip(goal = goal, clock = { now }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        AccountQueueRows(
                            queue = listOf(PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS)),
                            inFlightIds = emptySet(),
                            onSendNow = {},
                            onRemove = {},
                            onUpdate = { _, _ -> },
                            onEditing = { _, _ -> },
                            onSteerNow = {},
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up (queues on your account)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Goal active").fetchSemanticsNodes().isNotEmpty() }
        capture("66_composer_goal_strip")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun goalStripOpenedOntoItsDetails() {
        val goal = Goal.fromAccount(objective, GoalStatus.PAUSED, activeDurationMs = 5_100_000L, lastAccruedAtMs = null, continuationCount = 3, goalId = "8a1c")
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GoalStrip(goal = goal, clock = { now }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = "Follow up…",
                            onSend = {},
                            isRunning = false,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("goal-strip").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("3 turns started by Cursor for it.").fetchSemanticsNodes().isNotEmpty() }
        // The strip grows into place over a few frames; the frame is of it settled.
        compose.mainClock.advanceTimeBy(1_000L)
        capture("67_composer_goal_strip_open")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun goalEventsInTheTranscript() {
        val continuation = SystemNotifications.parse(
            "m2",
            "<system_notification source=\"goal\">\nContinue working toward the active thread goal.\n\n<objective>\n$objective\n</objective>\n</system_notification>",
            now,
        )!!.items
        val items = listOf(UserMessage("m1", "/goal $objective", t0)) + replay("goal_run.sse", "run-goal-001") + continuation + replay("goal_completed_run.sse", "run-goal-004")
        val goal = GoalTranscript.derive(items)!!
        assertThat(goal.status).isEqualTo(GoalStatus.COMPLETE)
        val shown = GoalTranscript.lift(items)
        assertThat(shown.filterIsInstance<SystemNotification>().map { it.title }).containsExactly("Goal set", SystemNotifications.GOAL_CONTINUED, "Goal completed").inOrder()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            shown.forEach { TimelineItemView(it) }
                            Spacer(Modifier.weight(1f))
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            GoalStrip(goal = goal, clock = { now }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                            ComposerBox(
                                value = "",
                                onValueChange = {},
                                placeholder = "Follow up…",
                                onSend = {},
                                isRunning = false,
                                onStop = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}),
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Goal completed").fetchSemanticsNodes().size == 2 }
        capture("68_transcript_goal_events")
    }
}
