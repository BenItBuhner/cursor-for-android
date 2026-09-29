package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The demo behind `media/progressive-beta-open/`: [com.cursorforandroid.data.faults.BetaColdOpenTest]'s 300-turn
 * chat opened cold on the Beta engine at a phone's round trips, drawn as the conversation screen draws it. Each frame
 * goes to `PROGRESSIVE_BETA_FRAMES` with its wall-clock offset from the open in `times.txt`, so the video plays at
 * the load's real pace. Skipped without it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class BetaColdOpenDemoTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val frames = System.getenv("PROGRESSIVE_BETA_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = "bc-long-regular-chat"
    private val firstAt = 1_800_000_000_000L - TURNS * 300_000L
    private val times = StringBuilder()
    private var frame = 0

    private fun prompt(i: Int) = "Prompt $i: look at the pager and say what changed."
    private fun reply(i: Int) = "Reply $i: the pager reads a page ahead of the window now, and the tests pass."

    private fun recordTurn(i: Int): List<JsonObject> = buildList {
        add(buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt(i)); put("createdAt", (firstAt + i * 300_000L).toString()) }) })
        add(buildJsonObject { put("toolCall", buildJsonObject { put("tool", "CLIENT_SIDE_TOOL_V2_READ_FILE"); put("toolCallId", "toolu_$i"); put("name", "read_file"); put("rawArgs", """{"target_file":"app/src/Pager$i.kt"}""") }) })
        add(buildJsonObject { put("finalToolResult", buildJsonObject { put("toolCallId", "toolu_$i"); put("result", buildJsonObject { put("success", buildJsonObject { put("contents", "fun pager$i() {}"); put("totalLines", 1) }) }) }) })
        add(buildJsonObject { put("text", reply(i)) })
        add(buildJsonObject { put("text", ""); put("isMessageDone", true) })
    }

    @Before
    fun setUp() {
        assumeTrue(frames != null)
        frames!!.mkdirs()
        server = FaultServer(rttMillis = 300L..900L).start()
        (1..TURNS).forEach { i ->
            val at = Instant.ofEpochMilli(firstAt + i * 300_000L).toString()
            server.runs["run-$i"] = RunDto(id = "run-$i", agentId = agentId, status = "FINISHED", createdAt = at, updatedAt = Instant.ofEpochMilli(firstAt + i * 300_000L + 40_000L).toString(), durationMs = 40_000L, result = reply(i))
        }
        server.agents[agentId] = AgentDto(id = agentId, name = "Pager", status = "IDLE", createdAt = Instant.ofEpochMilli(firstAt).toString(), updatedAt = Instant.ofEpochMilli(firstAt + TURNS * 300_000L + 40_000L).toString(), latestRunId = "run-$TURNS", url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Pager", status = "FINISHED")
        server.transcripts[agentId] = (1..TURNS).flatMap { i -> listOf(V0ConversationMessageDto("run-$i-u", "user_message", prompt(i)), V0ConversationMessageDto("run-$i-a", "assistant_message", reply(i))) }
        server.records[agentId] = (1..TURNS).flatMap(::recordTurn)
        server.prefetchBlobs = 0
        server.pageSize = 100
    }

    @After
    fun tearDown() {
        rig?.close()
        if (::server.isInitialized) server.close()
    }

    private fun capture(atMs: Long) {
        val file = File.createTempFile("beta-open-frame", ".png")
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2 / 2 * 2, bitmap.height / 2 / 2 * 2, true)
        val name = "frame-%04d.png".format(frame++)
        File(frames, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        times.append(name).append(' ').append(atMs).append('\n')
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `open a long chat cold on the Beta engine`() {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA).also {
            it.now = 1_800_000_000_000L
            rig = it
        }
        val presenter = TranscriptPresenter()
        val elapsed = mutableLongStateOf(0L)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = false, onDismissNotice = {})) {
                    val colors = CursorTheme.colors
                    val type = CursorTheme.typography
                    val state by rig.conversations.state(agentId).collectAsState()
                    val rows = remember(state.items) { presenter.present(state.items, coordinatorMode = false, runActive = false).rows }
                    val shown = state.items.count { it is UserMessage }
                    Column(Modifier.fillMaxSize().background(colors.canvas)) {
                        Row(Modifier.fillMaxWidth().background(colors.sidebar).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Pager  ·  $TURNS turns", style = type.sectionTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Text("%.1f s  ·  %d turns shown".format(elapsed.longValue / 1000.0, shown), style = type.base, color = colors.textSecondary)
                        }
                        LazyColumn(
                            state = rememberLazyListState(),
                            reverseLayout = true,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(rows.asReversed(), key = { it.key }) { row -> TranscriptRowView(row, Modifier.fillMaxWidth()) }
                            if (state.hasOlder || state.isLoadingOlder) item(key = "older") { OlderTurnsRow(isLoading = state.isLoadingOlder, onLoad = {}) }
                            if (rows.isEmpty()) {
                                item(key = "loading") {
                                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        SpinnerRing(size = 14.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Loading…", style = type.base, color = colors.textQuaternary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(FRAME_MS)
        capture(0L)
        val started = System.nanoTime()
        runBlocking { rig.conversations.attach(agentId) }
        var advanced = 0L
        var settledAt = -1L
        while (true) {
            val now = (System.nanoTime() - started) / 1_000_000
            val step = (now - advanced).coerceAtLeast(FRAME_MS)
            advanced += step
            elapsed.longValue = now
            compose.mainClock.advanceTimeBy(step)
            capture(now)
            val s = rig.conversations.state(agentId).value
            val whole = !s.isLoading && !s.isLoadingOlder && s.traceStatus.pending == 0 && s.items.count { it is UserMessage } >= WINDOW
            if (whole && settledAt < 0) settledAt = now
            if ((settledAt >= 0 && now - settledAt >= HOLD_MS) || now > 60_000L) break
        }
        File(frames, "times.txt").writeText(times.toString())
    }

    private companion object {
        const val TURNS = 300
        const val WINDOW = 10
        const val FRAME_MS = 16L
        const val HOLD_MS = 2_500L
    }
}
