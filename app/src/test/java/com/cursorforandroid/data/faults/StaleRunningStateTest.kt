package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Bennett's 0.4.4 Project coordinator: a long chat whose turns were all over on the server, shown as running after a
 * reload. A message sent queued and never went; sending it from the queue tried and did nothing; Stop answered "Run
 * is not active". The account's list still called the coordinator running — the word a Project's injected turns are
 * shown by, since they have no `/v1` run — and the chat's copy on disk held the turn it had last followed as under
 * way. Every one of those readings is the device's, or a word the server no longer stands behind: the server's own
 * answer to a Stop, and a queued message's long wait, are where the chat is put back where the server has it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StaleRunningStateTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val root = BigProject.AGENT_ID
    private val now = 1_800_000_000_000L
    private val turns = BigProject.workerTurns(now - 80 * BigProject.TURN_SPACING_MS, turns = 60)
    private val liveRun = "run-live-coordinator"
    private lateinit var disk: File

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 60L..120L).start()
        server.refusesInactiveCancels = true
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = root, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val liveAt = BigProject.iso(now - 60_000L)
        server.runs[liveRun] = RunDto(id = liveRun, agentId = root, status = "RUNNING", createdAt = liveAt, updatedAt = liveAt)
        server.logs[liveRun] = listOf("status" to """{"runId":"$liveRun","status":"RUNNING"}""", "assistant" to """{"text":"Judges scoring now."}""")
        server.agents[root] = AgentDto(id = root, name = BigProject.AGENT_NAME, status = "ACTIVE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = liveAt, latestRunId = liveRun, url = "https://cursor.com/agents/$root")
        server.v0[root] = V0AgentDto(id = root, name = BigProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[root] = BigProject.v0Transcript(turns)
        server.records[root] = turns.flatMap { it.record }
        server.composers[root] = FaultServer.Composer(root, BigProject.AGENT_NAME, activityMs = now - 5_000L, running = true, project = true)
        disk = folder.newFolder("phone")
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun rig(): FaultRig = FaultRig(server.baseUrl, disk, readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA).also { it.now = now; rigs += it }

    /**
     * The turn is followed live and written to disk; the process dies; the server ends the turn while the account's
     * list goes on calling the coordinator running. The next process opens the chat: running, as the account says.
     */
    private suspend fun reopenedAfterTheTurnEnded(): FaultRig {
        val first = rig()
        first.agents.refresh()
        first.conversations.attach(root)
        first.awaitUntil(30_000) { first.conversations.state(root).value.let { s -> !s.isLoading && s.activeRunId == liveRun && s.runStatus == RunStatus.RUNNING } }
        first.conversations.detach(root)
        first.close()
        rigs -= first

        server.finish(root, liveRun, reply = "Judges scoring now.", at = BigProject.iso(now - 30_000L))
        val second = rig()
        second.agents.refresh()
        second.awaitUntil(15_000) { second.agents.runningScan.value.accountWord[root]?.running == true }
        second.conversations.attach(root)
        second.awaitUntil(30_000) {
            second.conversations.state(root).value.let { s ->
                !s.isLoading && s.activeRunId == liveRun && s.runStatus == RunStatus.RUNNING
            }
        }
        return second
    }

    @Test
    fun `after a reload, Stop's answer that nothing is running puts the chat at rest, and the account's stale word does not undo it`() = runBlocking<Unit> {
        val rig = reopenedAfterTheTurnEnded()
        val conversations = rig.conversations

        val stop = conversations.cancelActiveRun(root)
        assertWithMessage("Stop's result: ${stop.exceptionOrNull()?.message}").that(stop.isSuccess).isTrue()
        rig.awaitUntil(15_000) { conversations.state(root).value.let { s -> s.runStatus?.isActive != true && !s.isStreaming } }
        assertThat(rig.followUps.decide(root).idle).isTrue()

        // The account's list is read again and still calls the coordinator running: nothing has happened since the
        // server said the chat was at rest, so the chat stays there, and a message sent now goes.
        rig.agents.refresh()
        assertThat(server.composers.getValue(root).running).isTrue()
        rig.watch(3_000) {
            assertThat(conversations.state(root).value.runStatus?.isActive).isNotEqualTo(true)
            assertThat(rig.followUps.decide(root).idle).isTrue()
        }

        // A turn the account reports after that — a worker's report waking the coordinator moves its activity — is
        // one the server's answer did not cover: the chat runs again.
        server.composers[root] = server.composers.getValue(root).copy(activityMs = now + 60_000L)
        rig.agents.refresh()
        rig.awaitUntil(20_000) { rig.agents.runningScan.value.accountWord[root]?.running == true }
        rig.awaitUntil(20_000) { conversations.state(root).value.runStatus?.isActive == true }
        assertThat(rig.followUps.decide(root).busy).isTrue()
    }

    @Test
    fun `after a reload, a message queued behind a turn the server has not got is delivered once the wait runs long`() = runBlocking<Unit> {
        val rig = reopenedAfterTheTurnEnded()
        val text = "Put the fee table on the board."
        assertThat(rig.followUps.decide(root).busy).isTrue()
        val queued = rig.followUps.enqueue(root, text)

        rig.awaitUntil(60_000) { server.sent.any { it.first == text } }
        rig.awaitUntil(15_000) { rig.followUps.state(root).value.queue.none { it.id == queued.id } }
        assertThat(server.sent.count { it.first == text }).isEqualTo(1)
    }

    @Test
    fun `after a reload, a message force-sent from the queue goes out although Stop's cancel is refused as not running`() = runBlocking<Unit> {
        val rig = reopenedAfterTheTurnEnded()
        val text = "Send the statements now."
        val queued = rig.followUps.enqueue(root, text)
        assertThat(rig.followUps.sendNow(root, queued.id)).isTrue()

        rig.awaitUntil(30_000) { server.sent.any { it.first == text } || rig.followUps.state(root).value.queue.any { it.id == queued.id && it.error != null } }
        val card = rig.followUps.state(root).value.queue.firstOrNull { it.id == queued.id }
        assertWithMessage("the card: ${card?.error}").that(card?.error).isNull()
        assertThat(server.sent.count { it.first == text }).isEqualTo(1)
        rig.awaitUntil(15_000) { rig.followUps.state(root).value.queue.none { it.id == queued.id } }
    }
}
