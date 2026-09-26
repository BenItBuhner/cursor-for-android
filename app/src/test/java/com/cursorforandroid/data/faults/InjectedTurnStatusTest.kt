package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's 2026-09-24 export of a Project's coordinator: `shown=FINISHED latestRun=FINISHED accountRunning=true
 * rowNewerThanRecordMs=834`, the newest turn a worker's report with nothing after it. A Project's injected turns — a
 * subagent's report, a timer — run with no run in the `/v1` list and leave the row where the last listed run left it,
 * so the row's time could never say the chat went on: the screen took the finished run's word over the account's,
 * which had said "running" after that run was last written.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InjectedTurnStatusTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val root = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val turns = BigProject.workerTurns(now - 20 * BigProject.TURN_SPACING_MS, turns = 6)
    private val report = "<system_notification>\nThe following task has finished. If you were already aware, ignore this notification and do not restate prior responses.\n\n<task>\nkind: subagent\nstatus: success\ntask_id: task-1443\ntitle: Expansion shard 7\nagent_id: bc-worker-1443\ndetail: This is the last output of the subagent:\n\nThe shard ran clean.\n</task>\n</system_notification>"

    private fun TimelineItem.isReport() = (this is SystemNotification && "task-1443" in raw) || (this is UserMessage && "task-1443" in text)

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 150L..300L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = root, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[root] = AgentDto(id = root, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt + 834L), latestRunId = newest.runId, url = "https://cursor.com/agents/$root")
        server.v0[root] = V0AgentDto(id = root, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[root] = BigProject.v0Transcript(turns)
        // The report came in after the last listed run, and the coordinator is on it: the record's newest turn is its prompt alone.
        server.records[root] = turns.flatMap { it.record } + buildJsonObject { put("humanMessage", buildJsonObject { put("text", report); put("createdAt", (now - 10_000L).toString()) }) }
        server.composers[root] = FaultServer.Composer(root, BigProject.AGENT_NAME, activityMs = now - 5_000L, running = true, project = true)
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    @Test
    fun `the account running after the last listed run was written shows the chat running, never finished`() = runBlocking<Unit> {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA).also { it.now = now; rig = it }
        rig.agents.refresh()
        rig.awaitUntil(15_000) { rig.agents.runningScan.value.accountWord[root]?.running == true }

        val conversations = rig.conversations
        val shown = CopyOnWriteArrayList<RunStatus?>()
        val watcher = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            conversations.state(root).collect { s -> if (s.items.any { it.isReport() }) shown += s.runStatus }
        }
        conversations.attach(root)
        rig.awaitUntil(30_000) { conversations.state(root).value.let { s -> !s.isLoading && s.items.any { it.isReport() } } }
        delay(4_000)
        watcher.cancel()

        println("   statuses shown with the report on screen: ${shown.distinct()}")
        assertThat(conversations.state(root).value.runStatus).isEqualTo(RunStatus.RUNNING)
        assertWithMessage("statuses shown with the report on screen").that(shown.filterNotNull().filter { it.isTerminal }).isEmpty()
    }

    @Test
    fun `the account's word from before the last listed run ended leaves the chat finished`() = runBlocking<Unit> {
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA).also { it.now = turns.last().endedAt - 60_000L; rig = it }
        rig.agents.refresh()
        rig.awaitUntil(15_000) { rig.agents.runningScan.value.accountWord[root]?.running == true }
        // A pass still out when the account goes quiet, as a background poll can be: the server writes its answer on
        // arrival and the app stamps it on landing, so it must land before the clock moves or it reads as current.
        rig.scope.launch { rig.agents.refresh(silent = true) }
        delay(60)
        // The account went quiet since; the phone's copy of its word is the one from before the run ended.
        server.composers[root] = server.composers.getValue(root).copy(running = false)
        rig.awaitUntil(15_000) { rig.accountCallsInFlight.get() == 0 }
        // callEnd fires as the body is read, a moment before the repository takes the answer in.
        delay(250)
        rig.awaitUntil(15_000) { rig.accountCallsInFlight.get() == 0 }
        rig.now = now

        rig.conversations.attach(root)
        rig.awaitUntil(30_000) { rig.conversations.state(root).value.let { s -> !s.isLoading && s.runStatus != null } }
        assertThat(rig.conversations.state(root).value.runStatus).isEqualTo(RunStatus.FINISHED)
    }
}
