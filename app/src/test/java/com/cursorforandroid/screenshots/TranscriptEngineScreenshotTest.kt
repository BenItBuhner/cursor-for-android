package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.LongProject
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.RECORD_FALLBACK_TITLE
import com.cursorforandroid.ui.conversation.TraceStatusRow
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The 240-turn Project opened under the Stable transcript engine (see [TranscriptEngine]), through the real pipeline
 * on the fault harness with the account's record refused the way Bennett's phone met it on 2026-09-21 — and never
 * asked: the transcript from the documented endpoints, the coordinator's messages among the rows from the runs'
 * logs, a turn whose log expired named by its calm row, and no "Account transcript unavailable" notice over the
 * composer, because under Stable the documented path is the design and not a fallback. Written to `screenshots/`
 * and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TranscriptEngineScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - LongProject.TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt)
    private val live get() = turns.last()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 40L..90L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        // The record refused in the server's words, as on Bennett's phone — never met under Stable.
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", FaultServer.REMOVED_UNARY))
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        // The logs of the window's older turns are gone (Cursor keeps a log about a day), the newest finished turn's
        // still there: whichever order the replays land in, the same turns read as expired, so the frame is the same.
        turns.dropLast(2).takeLast(8).forEach { server.logs.remove(it.runId) }
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    /** The chat as the pipeline renders it under [engine], settled: the window's traces read and the frame still. */
    private fun open(engine: TranscriptEngine): ConversationState = runBlocking {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true, engine = engine).also {
            it.now = live.startedAt + 90_000L
            rig = it
        }
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.awaitUntil(60_000) { state.let { !it.isLoading && it.items.isNotEmpty() } }
        rig.awaitUntil(90_000) {
            val quiet = state.traceStatus.pending == 0 && rig.conversations.loadDiagnostics(agentId)!!.let { it.traceQueue == 0 && it.traceInFlight == 0 && !it.traceWorkerRunning }
            if (!quiet) return@awaitUntil false
            val items = state.items
            delay(1_000)
            state.items == items
        }
        state
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(state: ConversationState, rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true, onDismissNotice = {})) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (state.traceStatus.let { it.pending + it.expired + it.failed > 0 }) TraceStatusRow(state.traceStatus, onRetry = {})
                            rows.forEach { TranscriptRowView(it) }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun longProjectOnStable() {
        AppClock.nowMillis = { live.startedAt + 90_000L }
        val state = open(TranscriptEngine.STABLE)
        // Nothing of the record: not asked, not refused, no notice.
        assertThat(server.seen.map { it.route }).containsNoneOf(Route.RecordState, Route.Blob, Route.Record)
        assertThat(state.recordFallback).isNull()
        assertThat(state.items.any { it.id.startsWith("expired-") }).isTrue()
        val rows = TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = true).rows
        // The newest rows, as the screen opens on them: the expired turn's row, the coordinator's word, its stretches,
        // the workers it queued work for (a subagent row each) and the live stretch.
        show(state, rows.takeLast(9))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(TimelineBuilder.EXPIRED_TITLE).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("record-fallback")).assertCountEquals(0)
        compose.onAllNodesWithText(RECORD_FALLBACK_TITLE, substring = true).assertCountEquals(0)
        capture("124_long_project_stable")
    }
}
