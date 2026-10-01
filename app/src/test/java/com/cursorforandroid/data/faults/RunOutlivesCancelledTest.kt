package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Bennett's 0.4.23 chat (…37efe8, 2026-09-30): two prompts, the older run (…45d49b) still running on the server and
 * the newer one (…3242a4) cancelled, the account calling the chat running. The chat went by the newest run's date: it
 * sat on the cancelled run, called itself running over that run's CANCELLED footer, and never followed the run the
 * server was on — `live: run=- following=false stream=none`, no word of the turn ever drawn. The account's record,
 * which names no turn for either yet, was taken for an answer in an unknown shape because a run of the chat was over,
 * and left alone for ten minutes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunOutlivesCancelledTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val agentId = "bc-outlived"
    private val older = "run-older-running"
    private val newer = "run-newer-cancelled"

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** The older run running and streaming, the newer one [newerStatus]; the agent names the newer one; the account's list calls the chat running; no turn in the record or the transcript yet. */
    private fun chat(newerStatus: String) {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 300L
        server.runs[older] = RunDto(id = older, agentId = agentId, status = "RUNNING", createdAt = iso(now - 180_000L), updatedAt = iso(now - 2_000L))
        server.logs[older] = listOf("status" to """{"runId":"$older","status":"RUNNING"}""", "assistant" to """{"text":"Reading the repository."}""")
        server.runs[newer] = RunDto(id = newer, agentId = agentId, status = newerStatus, createdAt = iso(now - 120_000L), updatedAt = iso(now - 110_000L))
        server.logs[newer] = if (newerStatus == "CANCELLED") {
            listOf("result" to """{"runId":"$newer","status":"CANCELLED","text":"","durationMs":10000}""")
        } else {
            listOf("status" to """{"runId":"$newer","status":"RUNNING"}""")
        }
        server.agents[agentId] = AgentDto(id = agentId, name = "Outlived", status = "ACTIVE", createdAt = iso(now - 180_000L), updatedAt = iso(now - 110_000L), latestRunId = newer, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Outlived", status = newerStatus)
        server.transcripts[agentId] = emptyList()
        server.composers[agentId] = FaultServer.Composer(agentId, "Outlived", activityMs = now - 1_000L, running = true)
    }

    private fun rig(engine: TranscriptEngine): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("disk-${rigs.size}"), readTimeoutMs = 20_000L, extended = true, engine = engine, http2 = true).also {
            it.now = now
            rigs += it
        }

    private fun olderRunIsFollowed(rig: FaultRig) = runBlocking {
        val followed = runCatching {
            rig.awaitUntil(20_000) {
                rig.conversations.state(agentId).value.let { s ->
                    !s.isLoading && s.activeRunId == older && s.isStreaming && s.items.filterIsInstance<AssistantMessage>().any { it.markdown == "Reading the repository." }
                }
            }
        }.isSuccess
        val state = rig.conversations.state(agentId).value
        val diagnostics = rig.conversations.loadDiagnostics(agentId)
        assertWithMessage(
            "followed=$followed active=${state.activeRunId} status=${state.runStatus} streaming=${state.isStreaming} live=${diagnostics?.liveRunId} " +
                "record=${diagnostics?.record?.error} fallback=${state.recordFallback?.reason} items=${state.items.map { it::class.simpleName }}",
        ).that(followed).isTrue()
        assertThat(state.runStatus).isEqualTo(RunStatus.RUNNING)
    }

    private fun onOpen(engine: TranscriptEngine) = runBlocking {
        chat(newerStatus = "CANCELLED")
        val rig = rig(engine)
        rig.agents.refresh()
        rig.awaitUntil(15_000) { rig.agents.runningScan.value.accountWord[agentId]?.running == true }
        rig.conversations.attach(agentId)
        olderRunIsFollowed(rig)
        val state = rig.conversations.state(agentId).value
        // No turn in the record while its one run with any work is under way is not a record of an unknown shape.
        assertWithMessage("record fallback: ${state.recordFallback?.reason}").that(state.recordFallback).isNull()
        assertThat(rig.conversations.loadDiagnostics(agentId)?.record?.error).isNull()
        rig.conversations.detach(agentId)
    }

    @Test
    fun `the older run the account is still on is followed past a newer cancelled one (Beta)`() = onOpen(TranscriptEngine.BETA)

    @Test
    fun `the older run the account is still on is followed past a newer cancelled one (Stable)`() = onOpen(TranscriptEngine.STABLE)

    @Test
    fun `the newer run seen cancelled here leaves the older run the account is still on followed`() = runBlocking {
        chat(newerStatus = "RUNNING")
        val rig = rig(TranscriptEngine.BETA)
        rig.agents.refresh()
        rig.awaitUntil(15_000) { rig.agents.runningScan.value.accountWord[agentId]?.running == true }
        rig.conversations.attach(agentId)
        rig.awaitUntil(20_000) { rig.conversations.state(agentId).value.let { !it.isLoading && it.activeRunId == newer && it.isStreaming } }

        // The server cancels the newer run; the older one runs on, the account still calling the chat running.
        server.runs[newer] = server.runs.getValue(newer).copy(status = "CANCELLED", updatedAt = iso(now - 60_000L))
        server.v0[agentId] = server.v0.getValue(agentId).copy(status = "CANCELLED")
        server.appendRunEvents(newer, listOf("result" to """{"runId":"$newer","status":"CANCELLED","text":"","durationMs":60000}"""))
        server.touch(agentId)
        rig.awaitUntil(20_000) { rig.agents.endedStatus(agentId, newer) == RunStatus.CANCELLED }
        // The account's word from before that end could be of the run that ended: only a word asked for after it counts.
        rig.now = now + 30_000L
        olderRunIsFollowed(rig)
        rig.conversations.detach(agentId)
    }
}
