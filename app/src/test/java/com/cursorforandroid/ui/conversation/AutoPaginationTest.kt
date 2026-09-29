package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The transcript fills itself by what it draws, over the fakes (Bennett, 2026-09-28): a chat whose newest turns are
 * events and tool calls — each folded into one line — opens on its newest reply with nothing tapped, and the reader's
 * scroll up brings the older turns in by itself, the rows being read held where they are as each page lands. The
 * Project the same, its newest turns silent worker reports, on the documented endpoints. See [OlderPaging].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AutoPaginationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-28T01:00:00Z").toEpochMilli()
    private val agentId = "bc-auto-pagination"
    private lateinit var graph: AppGraph
    private var server: FaultServer? = null
    private var rig: FaultRig? = null

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        rig?.close()
        server?.close()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun read(turn: Int, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "run-$turn-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")); put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) }) },
        ),
    )

    private fun github(turn: Int) =
        "<timestamp>Monday, Sep 28, 2026</timestamp>\n<system_notification source=\"github\" pr=\"https://github.com/acme/app/pull/${40 + turn}\" action=\"synchronize\" sender=\"cursor[bot]\">\nA subscribed pull request was updated.\n</system_notification>"

    private fun prompt(turn: Int) = "Prompt $turn: read the modules and say what changed."

    /**
     * A finished chat of [replies] turns the user prompted and the agent answered, [CALLS] tool calls each, then
     * [events] turns Cursor injected (a subscribed pull request's changes) that the agent worked through without a
     * word: the newest reply is "Reply [replies]", [events] turns up.
     */
    private fun seed(replies: Int, events: Int) = runBlocking {
        val total = replies + events
        val first = Instant.ofEpochMilli(now - total * 3_600_000L)
        val prompts = Array(replies) { Triple("run-${it + 1}", prompt(it + 1), "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Tool heavy", *prompts, firstRunAt = first.toString())
        val messages = api.transcripts.getValue(agentId).toMutableList()
        for (turn in replies + 1..total) {
            val runId = "run-$turn"
            val at = first.plusSeconds(3600L * (turn - 1)).toString()
            api.runs[runId] = RunDto(id = runId, agentId = agentId, status = "FINISHED", createdAt = at, updatedAt = at, durationMs = 30_000L)
            messages += V0ConversationMessageDto("$runId-u", "user_message", github(turn))
        }
        api.transcripts[agentId] = messages
        api.agents[agentId] = api.agents.getValue(agentId).copy(latestRunId = "run-$total", updatedAt = api.runs.getValue("run-$total").updatedAt)
        for (turn in 1..total) {
            val runId = "run-$turn"
            val reply = if (turn <= replies) "Reply $turn" else null
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            repeat(CALLS) { n -> streamer.emit(runId, read(turn, n + 1)) }
            reply?.let { streamer.emit(runId, RunStreamEvent.Assistant(it)) }
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 30_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
    }

    private val transcript get() = hasScrollToIndexAction()

    private fun nodes(text: String) =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = false), useUnmergedTree = true).fetchSemanticsNodes()

    /** Whether [text] is drawn inside the transcript's viewport. */
    private fun inView(text: String): Boolean {
        val list = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot
        return nodes(text).any { it.boundsInRoot.let { b -> b.height > 0f && b.bottom > list.top && b.top < list.bottom } }
    }

    private fun settled(): Boolean = graph.conversations.state(agentId).value.let { !it.isLoading && !it.isLoadingOlder && it.traceStatus.pending == 0 }

    /** The texts in view, top to bottom, with where each stands. */
    private fun textsInView(): List<Pair<String, Rect>> {
        val list = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(hasAnyAncestor(transcript), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ")?.let { it to node.boundsInRoot } }
            .filter { (_, b) -> b.height > 0f && b.top >= list.top && b.bottom <= list.bottom }
            .sortedBy { it.second.top }
    }

    /** Only texts drawn once, so a label every turn repeats ("Worked", a duration) is not taken for another. */
    private fun unique(texts: List<Pair<String, Rect>>) = texts.groupBy { it.first }.filterValues { it.size == 1 }.values.map { it.single() }

    @Test
    fun `a chat whose newest turns are events opens on its newest reply, nothing tapped`() {
        seed(replies = 20, events = 16)
        open()
        compose.waitUntil(60_000) { settled() && nodes("Reply 20").isNotEmpty() }
        compose.waitForIdle()
        // The newest turns' one line and the reply above it, on screen together; the window reached back as far as
        // the reply and a screen and a half more, not the whole chat.
        assertThat(inView("Reply 20")).isTrue()
        assertThat(compose.onAllNodes(hasAnyAncestor(transcript) and hasText("16 events", substring = true), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
        val state = graph.conversations.state(agentId).value
        assertWithMessage("paged in whole: ${state.items.size} items").that(state.hasOlder).isTrue()
        assertThat(nodes("Prompt 1: read the modules and say what changed.")).isEmpty()
    }

    @Test
    fun `scrolling up brings the older turns in by itself, the rows being read held where they are`() {
        seed(replies = 60, events = 12)
        open()
        compose.waitUntil(60_000) { settled() && nodes("Reply 60").isNotEmpty() }
        compose.waitForIdle()
        val opened = graph.conversations.state(agentId).value.items.size
        var swipes = 0
        var held = 0
        while (nodes(prompt(1)).isEmpty() && swipes < 120) {
            compose.onNode(transcript).performTouchInput { swipeDown(startY = top + height * 0.2f, endY = top + height * 0.8f, durationMillis = 300) }
            compose.waitForIdle()
            swipes++
            // At rest, a page on its way lands above what the reader is looking at: every row in view stays put.
            val before = unique(textsInView())
            compose.waitUntil(60_000) { settled() }
            compose.waitForIdle()
            val after = unique(textsInView()).toMap()
            for ((text, bounds) in before) {
                val now = after[text] ?: continue
                assertWithMessage("\"$text\" moved from ${bounds.top} to ${now.top} as a page landed (swipe $swipes)").that(abs(now.top - bounds.top)).isAtMost(1f)
                held++
            }
        }
        val state = graph.conversations.state(agentId).value
        assertWithMessage("reached the first prompt in $swipes swipes").that(nodes(prompt(1))).isNotEmpty()
        assertThat(state.hasOlder).isFalse()
        assertThat(state.items.size).isGreaterThan(opened)
        assertThat(held).isGreaterThan(0)
        // Nothing was tapped for it: "Older messages" never had to be.
        assertThat(compose.onAllNodes(hasTestTag("load-older")).fetchSemanticsNodes()).isEmpty()
    }

    /**
     * A Project of four hundred turns on the documented endpoints — the app's default engine — whose newest fourteen
     * turns are worker reports the coordinator answered without a word (see [BigProject]), in a list laid out as the
     * chat's and the panel's are. It opens on the coordinator's newest message to the user, with nothing tapped, and
     * reaches no further than a few pages back.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `a Project whose newest turns are silent reports opens on the coordinator's newest message`() {
        val project = BigProject.AGENT_ID
        val turns = BigProject.turns(now - 400 * BigProject.TURN_SPACING_MS - 60_000L, turns = 400)
        val newestMessage = turns.last { it.message != null }
        val server = FaultServer(rttMillis = 10L..30L).start().also { server = it }
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = project, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        server.agents[project] = AgentDto(id = project, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(turns.last().endedAt), latestRunId = turns.last().runId)
        server.v0[project] = V0AgentDto(id = project, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[project] = BigProject.v0Transcript(turns)
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 8_000L).also { it.now = now; rig = it }
        runBlocking { rig.conversations.attach(project) }
        val presenter = TranscriptPresenter()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true, onDismissNotice = {})) {
                    val state by rig.conversations.state(project).collectAsState()
                    val rows = remember(state.items) { presenter.present(state.items, coordinatorMode = true, runActive = false).rows }
                    val list = rememberLazyListState()
                    OlderPagingEffect(project, list, rows, canPage = state.hasOlder && !state.isLoadingOlder && rows.isNotEmpty()) { rig.conversations.loadOlder(project) }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = list,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("project-transcript"),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(rows.asReversed(), key = { it.key }) { row -> TranscriptRowView(row, Modifier.fillMaxWidth()) }
                            if (state.hasOlder) item(key = "older") { OlderTurnsRow(isLoading = state.isLoadingOlder, onLoad = { rig.conversations.loadOlder(project) }) }
                        }
                    }
                }
            }
        }
        compose.waitUntil(120_000) {
            val s = rig.conversations.state(project).value
            !s.isLoading && !s.isLoadingOlder && s.traceStatus.pending == 0 &&
                presenter.present(s.items, coordinatorMode = true, runActive = false).rows.any { it is TranscriptRow.Message && newestMessage.message!!.take(40) in ((it.call.payload as? ToolPayload.CoordinatorMessage)?.message ?: "") }
        }
        compose.waitForIdle()
        val state = rig.conversations.state(project).value
        assertWithMessage("paged in whole: ${state.items.size} items").that(state.hasOlder).isTrue()
        assertThat(compose.onAllNodes(hasTestTag("project-transcript")).fetchSemanticsNodes()).isNotEmpty()
    }

    private companion object {
        const val CALLS = 12
    }
}
