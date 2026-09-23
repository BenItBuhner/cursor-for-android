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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OlderTurnsRow
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
 * Bennett's 2026-09-23 frame, the fixture's way (see [BigProject]): a Project of two thousand turns, the newest
 * fourteen silent worker reports, its run list served oldest first, opened under the Beta transcript engine through
 * the real pipeline on the fault harness. The screen opens on its newest rows with "Older messages" above them: the
 * coordinator's messages between the stretches of reports, the user's last prompt among them — not one stretch of
 * ten events. Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class BigProjectBetaScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val agentId = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val firstAt = now - BigProject.TURNS * BigProject.TURN_SPACING_MS - 60_000L
    private val turns = BigProject.turns(firstAt)
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 40L..90L).start()
        server.pageSize = 100
        server.runsOldestFirst = true
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(firstAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = BigProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    /** The chat as the pipeline renders it under [engine], settled: nothing loading and the server asked nothing for a while. */
    private fun open(engine: TranscriptEngine): ConversationState = runBlocking {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true, engine = engine).also {
            it.now = now
            rig = it
        }
        rig.conversations.attach(agentId)
        rig.awaitUntil(60_000) { state.let { !it.isLoading && it.items.isNotEmpty() } }
        rig.awaitUntil(120_000) {
            val asked = server.seen.size
            val items = state.items
            delay(1_500)
            server.seen.size == asked && state.items == items && !state.isLoadingOlder && state.traceStatus.pending == 0
        }
        state
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(rows: List<TranscriptRow>) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(onOpenAgent = {}, agentById = { null }, coordinatorMode = true, onDismissNotice = {})) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OlderTurnsRow(isLoading = false, onLoad = {}, modifier = Modifier.fillMaxWidth())
                            rows.forEach { TranscriptRowView(it) }
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
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
    fun bigProjectOnBeta() {
        AppClock.nowMillis = { now }
        val state = open(TranscriptEngine.BETA)
        val rows = TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = false).rows
        // The newest rows, as the screen opens on them: messages between the stretches, not one stretch of events.
        val shown = rows.takeLast(SHOWN_ROWS)
        assertThat(shown.count { it is TranscriptRow.Message }).isAtLeast(2)
        show(shown)
        capture("195_big_project_beta")
    }

    private companion object {
        /** The rows a phone's screen holds above the composer, give or take the stretches' heights. */
        const val SHOWN_ROWS = 7
    }
}
