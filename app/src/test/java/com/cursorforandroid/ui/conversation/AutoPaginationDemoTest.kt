package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The demo behind `media/auto-pagination/`: a tool-heavy chat, its newest turns events and one of them live (its
 * thinking streamed at a hundred tokens a second), opened and then scrolled up through by touch, every other frame
 * written to `AUTO_PAGINATION_FRAMES`. Skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AutoPaginationDemoTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val frames = System.getenv("AUTO_PAGINATION_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4096)
    private val now = Instant.parse("2026-09-28T01:00:00Z").toEpochMilli()
    private val agentId = "bc-auto-pagination-demo"
    private val replies = 40
    private val events = 12
    private val live = "run-${replies + events + 1}"
    private var frame = 0
    private var streamedMs = 0L
    private var tokens = 0

    @Before
    fun setUp() {
        assumeTrue(frames != null)
        frames!!.mkdirs()
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun read(turn: Int, n: Int, status: String = "completed") = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "run-$turn-c$n",
            name = "read_file",
            status = status,
            args = buildJsonObject { put("path", JsonPrimitive("app/src/main/Turn${turn}File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")); put("path", JsonPrimitive("app/src/main/Turn${turn}File$n.kt")) }) },
        ),
    )

    private fun github(turn: Int) =
        "<timestamp>Monday, Sep 28, 2026</timestamp>\n<system_notification source=\"github\" pr=\"https://github.com/acme/app/pull/${40 + turn}\" action=\"synchronize\" sender=\"cursor[bot]\">\nA subscribed pull request was updated.\n</system_notification>"

    private fun prompt(turn: Int) = "Prompt $turn: read the modules and say what changed."

    private fun reply(turn: Int) = "Reply $turn: the modules changed in three places. The repository now pages by what it draws, " +
        "the presenter folds each run's tool calls into one line, and the panel keeps its place as older turns land."

    private fun seed() = runBlocking {
        val total = replies + events
        val first = Instant.ofEpochMilli(now - (total + 1) * 3_600_000L)
        api.addFinishedAgent(agentId, "Tool heavy", *Array(replies) { Triple("run-${it + 1}", prompt(it + 1), reply(it + 1)) }, firstRunAt = first.toString())
        val messages = api.transcripts.getValue(agentId).toMutableList()
        for (turn in replies + 1..total + 1) {
            val runId = "run-$turn"
            val at = first.plusSeconds(3600L * (turn - 1)).toString()
            val running = runId == live
            api.runs[runId] = RunDto(id = runId, agentId = agentId, status = if (running) "RUNNING" else "FINISHED", createdAt = at, updatedAt = at, durationMs = if (running) null else 30_000L)
            messages += V0ConversationMessageDto("$runId-u", "user_message", github(turn))
        }
        api.transcripts[agentId] = messages
        val newest = api.runs.getValue(live)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = newest.updatedAt)
        api.v0[agentId] = V0AgentDto(id = agentId, name = "Tool heavy", status = "RUNNING")
        for (turn in 1..total) {
            val runId = "run-$turn"
            val text = if (turn <= replies) reply(turn) else null
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            repeat(CALLS) { n -> streamer.emit(runId, read(turn, n + 1)) }
            text?.let { streamer.emit(runId, RunStreamEvent.Assistant(it)) }
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, text, 30_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, read(total + 1, 1))
    }

    /** The live turn's thinking, a word a token, at [TOKENS_PER_SECOND] of the video's time; a read every few seconds. */
    private fun stream(ms: Long) = runBlocking {
        streamedMs += ms
        val due = (streamedMs * TOKENS_PER_SECOND / 1000).toInt()
        while (tokens < due) {
            streamer.emit(live, RunStreamEvent.Thinking(WORDS[tokens % WORDS.size] + " "))
            tokens++
            if (tokens % 300 == 0) streamer.emit(live, read(replies + events + 1, tokens / 300 + 1))
        }
    }

    private fun capture() {
        val file = File.createTempFile("auto-pagination-frame", ".png")
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2 / 2 * 2, bitmap.height / 2 / 2 * 2, true)
        File(frames, "frame-%04d.png".format(frame++)).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One video frame: the clock and the stream a frame on, then the screen. */
    private fun tick() {
        stream(FRAME_MS)
        compose.mainClock.advanceTimeBy(FRAME_MS)
        capture()
    }

    private val transcript get() = hasScrollToIndexAction()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `open a tool heavy chat and scroll up through it`() {
        seed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        repeat(OPEN_FRAMES) { tick() }
        var swipes = 0
        val first = hasAnyAncestor(transcript) and hasText(prompt(1))
        while (swipes < SWIPES && compose.onAllNodes(first, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(transcript).performTouchInput { down(Offset(centerX, top + height * 0.3f)) }
            repeat(SWIPE_FRAMES) {
                stream(FRAME_MS)
                compose.onNode(transcript).performTouchInput { moveBy(Offset(0f, SWIPE_STEP_PX), delayMillis = FRAME_MS) }
                capture()
            }
            compose.onNode(transcript).performTouchInput { up() }
            repeat(REST_FRAMES) { tick() }
            swipes++
        }
        repeat(OPEN_FRAMES) { tick() }
    }

    private companion object {
        const val CALLS = 6
        const val TOKENS_PER_SECOND = 100
        const val FRAME_MS = 33L
        const val OPEN_FRAMES = 90
        const val SWIPES = 16
        const val SWIPE_FRAMES = 14
        const val SWIPE_STEP_PX = 70f
        const val REST_FRAMES = 20
        val WORDS = ("Reading the pull request's new commits against the modules the last turn touched, the pager's " +
            "window and the presenter's folded stretches, to see whether anything the reply described has moved since.").split(' ')
    }
}
