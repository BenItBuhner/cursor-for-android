package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptPresenter
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.fixtures.BigProject
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

/**
 * Bennett's 2026-09-23 frame on the documented path (the Stable engine): a Project of two thousand runs whose run list
 * the server lists oldest first. The chat's newest runs are twenty pages away; the list is read to its end, so the
 * newest turns pair with their runs — a footer, the activity their logs replay — instead of standing as bare prompts
 * folded into one stretch of events. The transcript is not held up behind the read: its prompts are on screen before
 * the last page of runs has landed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OldestFirstRunsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val firstAt = now - BigProject.TURNS * BigProject.TURN_SPACING_MS - 60_000L

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L).start()
        server.pageSize = 100
        server.runsOldestFirst = true
        val turns = BigProject.turns(firstAt)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(firstAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[agentId] = BigProject.v0Transcript(turns)
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    @Test
    fun `an oldest-first list of two thousand runs is read to its end, and the newest turns pair with their runs`() = runBlocking<Unit> {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE).also { it.now = now; rig = it }
        val conversations = rig.conversations
        conversations.attach(agentId)
        // The prompts first, before the list has been read to its end.
        rig.awaitUntil(60_000) { conversations.state(agentId).value.items.any { it is SystemNotification } }
        val pagesAtPaint = server.requests(FaultServer.Route.ListRuns).size
        rig.awaitUntil(120_000) {
            val state = conversations.state(agentId).value
            val load = conversations.loadDiagnostics(agentId)!!
            !state.isLoading && load.runsComplete && state.traceStatus.pending == 0 && load.traceQueue == 0 && load.traceInFlight == 0 && !load.traceWorkerRunning
        }
        delay(1_000)
        val state = conversations.state(agentId).value
        val load = conversations.loadDiagnostics(agentId)!!
        val pages = server.requests(FaultServer.Route.ListRuns).size
        println("pages at paint=$pagesAtPaint, all=$pages, runs=${load.runsLoaded} complete=${load.runsComplete} ${load.pairing?.text}")
        assertThat(pagesAtPaint).isLessThan(pages)
        assertThat(pages).isEqualTo(BigProject.TURNS / 100)
        assertThat(load.runsLoaded).isEqualTo(BigProject.TURNS)
        assertThat(load.runsComplete).isTrue()
        // The window's turns stand with their runs: footers, and the activity their logs replay.
        assertThat(state.items.filterIsInstance<RunFooter>().size).isAtLeast(9)
        val rows = TranscriptPresenter().present(state.items, coordinatorMode = true, runActive = false).rows
        val stretch = rows.filterIsInstance<TranscriptRow.Stretch>().last()
        println("newest stretch: ${stretch.summary.text}")
        assertThat(stretch.summary.action).startsWith("Worked")
        assertThat(stretch.entries.any { it is TranscriptRow.Entry.Call }).isTrue()
    }
}
