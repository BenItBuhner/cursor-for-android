package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * The demo behind `media/older-messages-pagination/`: a chat whose newest turns fold into one collapsed stretch
 * (Bennett, 2026-10-02), opened until the list comes to rest. Skipped without `OLDER_MESSAGES_PAGINATION_DEMO`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class OlderMessagesPaginationDemoTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = System.getenv("OLDER_MESSAGES_PAGINATION_DEMO")?.takeIf { it.isNotBlank() }?.let(::File)
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4096)
    private val now = Instant.parse("2026-10-02T01:00:00Z").toEpochMilli()
    private val agentId = "bc-older-messages-pagination"
    private lateinit var graph: AppGraph
    private var frame = 0
    private lateinit var frames: File

    @Before
    fun setUp() {
        assumeTrue(out != null)
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
            args = buildJsonObject { put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) },
            result = buildJsonObject {
                put(
                    "success",
                    buildJsonObject {
                        put("content", JsonPrimitive("val x = $n"))
                        put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt"))
                    },
                )
            },
        ),
    )

    private fun github(turn: Int) =
        "<timestamp>Thursday, Oct 2, 2026</timestamp>\n<system_notification source=\"github\" pr=\"https://github.com/acme/app/pull/${40 + turn}\" action=\"synchronize\" sender=\"cursor[bot]\">\nA subscribed pull request was updated.\n</system_notification>"

    private fun prompt(turn: Int) = "Prompt $turn: read the modules and say what changed."

    private fun seed() = runBlocking {
        val total = REPLIES + EVENTS
        val first = Instant.ofEpochMilli(now - (total + 1) * 3_600_000L)
        api.addFinishedAgent(
            agentId,
            AGENT,
            *Array(REPLIES) { Triple("run-${it + 1}", prompt(it + 1), "Reply ${it + 1}") },
            firstRunAt = first.toString(),
        )
        val messages = api.transcripts.getValue(agentId).toMutableList()
        for (turn in REPLIES + 1..total + 1) {
            val runId = "run-$turn"
            val at = first.plusSeconds(3600L * (turn - 1)).toString()
            val running = runId == LIVE
            api.runs[runId] = RunDto(
                id = runId,
                agentId = agentId,
                status = if (running) "RUNNING" else "FINISHED",
                createdAt = at,
                updatedAt = at,
                durationMs = if (running) null else 30_000L,
            )
            messages += V0ConversationMessageDto("$runId-u", "user_message", github(turn))
        }
        api.transcripts[agentId] = messages
        val newest = api.runs.getValue(LIVE)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = LIVE, updatedAt = newest.updatedAt)
        api.v0[agentId] = V0AgentDto(id = agentId, name = AGENT, status = "RUNNING")
        for (turn in 1..total) {
            val runId = "run-$turn"
            val text = if (turn <= REPLIES) "Reply $turn" else null
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            repeat(CALLS) { n -> streamer.emit(runId, read(turn, n + 1)) }
            text?.let { streamer.emit(runId, RunStreamEvent.Assistant(it)) }
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, text, 30_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(LIVE, RunStreamEvent.Status(LIVE, RunStatus.RUNNING))
        repeat(CALLS) { n -> streamer.emit(LIVE, read(total + 1, n + 1, status = if (n < 3) "running" else "completed")) }
    }

    private fun capture() {
        val file = File.createTempFile("older-messages-frame", ".png")
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2 / 2 * 2, bitmap.height / 2 / 2 * 2, true)
        File(frames, "frame-%04d.png".format(frame++)).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(device: String) {
        frames = File(checkNotNull(out), device).also { it.mkdirs() }
        frame = 0
        seed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, streamer, isDemo = true),
        )
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        var stable = 0
        var lastItems = -1
        var ticks = 0
        while (ticks < MAX_TICKS && stable < REST_TICKS) {
            compose.waitForIdle()
            capture()
            ticks++
            val state = graph.conversations.state(agentId).value
            val items = state.items.size
            val atRest = !state.isLoading && !state.isLoadingOlder && state.traceStatus.pending == 0
            stable = if (atRest && items == lastItems) stable + 1 else 0
            lastItems = items
            Thread.sleep(FRAME_MS)
        }
        repeat(HOLD_TICKS) {
            compose.waitForIdle()
            capture()
            Thread.sleep(FRAME_MS)
        }
        File(frames, "frame-%04d.png".format(frame - 1)).copyTo(File(frames, "still.png"), overwrite = true)
    }

    @Test
    fun phone() = open("phone")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-320dpi")
    fun tablet() = open("tablet")

    private companion object {
        const val AGENT = "Polymarket Bot Scaling & Research"
        const val REPLIES = 20
        const val EVENTS = 80
        const val CALLS = 8
        const val LIVE = "run-${REPLIES + EVENTS + 1}"
        const val FRAME_MS = 33L
        const val MAX_TICKS = 180
        const val REST_TICKS = 6
        const val HOLD_TICKS = 12
    }
}
