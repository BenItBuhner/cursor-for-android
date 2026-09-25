package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.AgentLinkIcon
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Links to agents in a reply, each opening with its status icon (see [AgentLinkIcon]): the dot grid on the four lanes
 * still working, the arrowhead on the two that have finished. The chat is the one Bennett reported (a coordinator
 * listing the lanes it runs), served through the demo seat of the graph with every lane a chat of the list, so the
 * icons read the list the sidebar reads. Dark and light; the grid is caught on a fixed step of its animation.
 *
 * [demoFrames] is not a golden: with `AGENT_LINK_DEMO_FRAMES` set to a directory it writes the frames of the walkthrough
 * video there, two lanes finishing on the server while the chat is open and their icons turning over on the refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinkStatusScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { Instant.parse(STARTED_AT).toEpochMilli() + 2 * 3_600_000L }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            appVersion = SCREENSHOT_APP_VERSION,
            demo = CursorBackend(api, streamer, isDemo = true),
        )
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun seed() = runBlocking {
        LANES.forEach { lane ->
            if (lane.running) api.addRunningAgent(lane.id, lane.name, "run-${lane.id}", createdAt = STARTED_AT)
            else api.addIdleAgent(lane.id, lane.name, "run-${lane.id}", createdAt = STARTED_AT)
        }
        api.addFinishedAgent(COORDINATOR, "Revenue Scaling Pipeline", Triple("run-coordinator", PROMPT, REPLY), firstRunAt = STARTED_AT)
        // The finished run's log is served whole, so its activity is read and no "Loading the activity" spinner turns above it.
        streamer.emit("run-coordinator", RunStreamEvent.Assistant(REPLY))
        streamer.emit("run-coordinator", RunStreamEvent.Result("run-coordinator", RunStatus.FINISHED, REPLY, 60_000, null))
        streamer.emit("run-coordinator", RunStreamEvent.Done)
        graph.session.enterDemo()
        graph.agents.refresh()
    }

    private fun finishOnServer(id: String) {
        val runId = "run-$id"
        api.runs[runId] = api.runs.getValue(runId).copy(status = "FINISHED", result = "Done.", durationMs = 60_000, updatedAt = STARTED_AT)
        api.agents[id] = api.agents.getValue(id).copy(status = "IDLE")
        api.v0[id] = api.v0.getValue(id).copy(status = "FINISHED")
    }

    private fun show(mode: ThemeMode) {
        seed()
        // The test harness cancels an infinite animation for good if it starts while the clock auto-advances, which
        // would freeze the dot grid on its first step; so the clock is paused from the start and stepped by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, COORDINATOR, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodes(hasText("Push AI receptionist", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                graph.conversations.state(COORDINATOR).value.traceStatus.pending == 0 &&
                compose.onAllNodes(hasText("Loading the activity", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    /** Moves the clock on to [phase] ms into the grid's loop, so a frame catches the same step however long loading took. */
    private fun advanceToGridPhase(phase: Long) {
        val now = compose.mainClock.currentTime
        compose.mainClock.advanceTimeBy(GRID_LOOP_MILLIS - now % GRID_LOOP_MILLIS + phase)
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun frame(mode: ThemeMode, name: String) {
        show(mode)
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(4)
        assertThat(count(AgentLinkIcon.TAG_IDLE)).isEqualTo(2)
        advanceToGridPhase(GRID_PHASE_MILLIS)
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun agentLinkStatusDark() = frame(ThemeMode.Dark, "640_agent_link_status_dark")

    @Test
    fun agentLinkStatusLight() = frame(ThemeMode.Light, "641_agent_link_status_light")

    @Test
    fun demoFrames() {
        val dir = System.getenv("AGENT_LINK_DEMO_FRAMES")
        assumeTrue(dir != null)
        val mode = if (System.getenv("AGENT_LINK_DEMO_THEME") == "light") ThemeMode.Light else ThemeMode.Dark
        val out = File(dir!!).apply { mkdirs() }
        show(mode)
        advanceToGridPhase(0)
        var index = 0
        fun roll(frames: Int) = repeat(frames) {
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            captureScreenRoboImage(File(out, "f%04d.png".format(index++)).path, RoborazziOptions())
        }
        fun finish(id: String) {
            finishOnServer(id)
            runBlocking { graph.agents.refresh() }
        }
        roll(45)
        finish(LANES[1].id)
        roll(40)
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(3)
        finish(LANES[2].id)
        roll(40)
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(2)
        finish(LANES[0].id)
        roll(50)
        assertThat(count(AgentLinkIcon.TAG_RUNNING)).isEqualTo(1)
    }

    private data class Lane(val id: String, val name: String, val running: Boolean)

    private companion object {
        const val STARTED_AT = "2026-09-25T09:00:00.000Z"
        const val COORDINATOR = "bc-7e0c3f1a-2b4d-4c6e-8f10-1a2b3c4d5e6f"
        const val GRID_LOOP_MILLIS = 1_400L
        /** Mid-way through the grid's fourth 175 ms step, so the capture is one pattern and never a boundary between two. */
        const val GRID_PHASE_MILLIS = 612L
        const val SETTLE_MILLIS = 1_000L
        const val FRAME_MILLIS = 40L

        val LANES = listOf(
            Lane("bc-11a1c0de-0000-4000-8000-000000000001", "Push AI receptionist to scale", running = true),
            Lane("bc-11a1c0de-0000-4000-8000-000000000002", "Build cold-email fleet at scale", running = true),
            Lane("bc-11a1c0de-0000-4000-8000-000000000003", "Scale agency lane with full resources", running = true),
            Lane("bc-11a1c0de-0000-4000-8000-000000000004", "Scale YouTube channels with full resources", running = false),
            Lane("bc-11a1c0de-0000-4000-8000-000000000005", "Scale TikTok and Pinterest fleets", running = true),
            Lane("bc-11a1c0de-0000-4000-8000-000000000006", "Scale dropship and merch lanes", running = false),
        )

        const val PROMPT = "Assume full resources on every lane. Who owns what, and what does each one still need from me?"

        val REPLY = """
            What's running now, one owner per lane, all assuming full resources. Each will name what still limits us once access isn't the excuse, work around it, and hand you one exact list of what it needs:

            - [Push AI receptionist to scale](${LANES[0].id}): his whole playbook, with the AI sales agent on inbound calls and a ${'$'}497 tier for high-ticket trades next to the ${'$'}99 self-serve plan.
            - [Build cold-email fleet at scale](${LANES[1].id}): the shared engine for every business lane. That's inbox and domain rotation, warm-up, auto-pause on complaints, reply routing, and national prospect lists.
            - [Scale agency lane with full resources](${LANES[2].id}): the render agency plus its upsells, closing without calls.
            - [Scale YouTube channels with full resources](${LANES[3].id}): bodycam long-form, sister channels and the Shorts channels at 30 videos a day.
            - [Scale TikTok and Pinterest fleets](${LANES[4].id}): dozens of accounts, product videos and the software funnel.
            - [Scale dropship and merch lanes](${LANES[5].id}): the live store plus merch at fleet scale.

            When they land, I'll roll it all into the game plan as a full-resources scale case, lane by lane, with the numbers.
        """.trimIndent()
    }
}
