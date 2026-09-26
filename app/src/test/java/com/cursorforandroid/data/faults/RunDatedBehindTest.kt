package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A turn whose run is dated behind the chat's last finished one — a server clock a little off, a run created on
 * another host — is still the run the agent's record names as its latest. Followed from the record and then read
 * as not the newest by the run list's dates, the chat went round a loop: the load put it back on the finished run,
 * the look for the next run put it on the new one again and read the whole chat once more, every round trip, for
 * as long as the turn ran.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunDatedBehindTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun addChat(id: String, at: Long) {
        val runId = "run-done-$id"
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
        server.logs[runId] = listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":60000}""")
        server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Start."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done."))
        server.composers.putIfAbsent(id, FaultServer.Composer(id, id, activityMs = at))
    }

    private fun rig(engine: TranscriptEngine): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("disk-${rigs.size}"), readTimeoutMs = 20_000L, extended = true, engine = engine, http2 = true).also {
            it.now = now
            rigs += it
        }

    private fun reads(agentId: String, route: FaultServer.Route) = server.seen.count { it.route == route && agentId in it.path }

    private fun runDatedBehindIsFollowedWithoutRereading(engine: TranscriptEngine) = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        val agentId = "bc-skewed"
        addChat(agentId, now)
        // The server dates the new run an hour behind the turn it finished last.
        server.clock = { now - 3_600_000L }
        val run = server.startTurnElsewhere(agentId, "Carry on.", "run-behind")
        val rig = rig(engine)
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.awaitUntil(15_000) { rig.conversations.state(agentId).value.let { !it.isLoading && it.isStreaming && it.activeRunId == run.id } }
        delay(1_000)
        val before = server.seen.size
        val agentReads = reads(agentId, FaultServer.Route.GetAgent)
        val listReads = reads(agentId, FaultServer.Route.ListRuns)
        delay(WATCH_MS)
        val agentReadsDuring = reads(agentId, FaultServer.Route.GetAgent) - agentReads
        val listReadsDuring = reads(agentId, FaultServer.Route.ListRuns) - listReads
        val requests = server.seen.size - before
        val state = rig.conversations.state(agentId).value
        println("BEHIND $engine requests=$requests getAgent=$agentReadsDuring listRuns=$listReadsDuring active=${state.activeRunId} streaming=${state.isStreaming} · ${server.seen.drop(before).groupingBy { it.route }.eachCount()}")

        assertThat(state.activeRunId).isEqualTo(run.id)
        assertThat(state.isStreaming).isTrue()
        assertWithMessage("run list reads while the turn ran").that(listReadsDuring).isAtMost(2)
        assertWithMessage("agent record reads while the turn ran").that(agentReadsDuring).isAtMost(3)
        assertWithMessage("requests in ${WATCH_MS / 1000} s of a turn followed").that(requests).isAtMost(30)

        // The turn ends: the chat comes to rest on it, and stays quiet.
        server.endTurn(agentId, durationMs = 5_000L)
        server.composers[agentId] = server.composers.getValue(agentId).copy(running = false)
        server.touch(agentId)
        // The list's next poll, which tells the row the account is idle.
        rig.agents.refresh()
        val ended = runCatching { rig.awaitUntil(15_000) { rig.conversations.state(agentId).value.let { !it.isStreaming && it.runStatus?.isActive == false } } }.isSuccess
        rig.conversations.state(agentId).value.let { println("BEHIND $engine end ended=$ended active=${it.activeRunId} status=${it.runStatus} streaming=${it.isStreaming} reconnecting=${it.isReconnecting} run=${server.runs[run.id]?.status} agent=${server.agents[agentId]?.status} · ${rig.conversations.stats()}") }
        assertThat(ended).isTrue()
        delay(2_000)
        val settled = server.seen.size
        delay(QUIET_MS)
        val after = rig.conversations.state(agentId).value
        val restRequests = server.seen.size - settled
        println("BEHIND $engine ended active=${after.activeRunId} status=${after.runStatus} rest=$restRequests · ${server.seen.drop(settled).groupingBy { it.route }.eachCount()}")
        rig.conversations.detach(agentId)
        assertThat(after.activeRunId).isEqualTo(run.id)
        assertThat(after.runStatus?.isActive).isFalse()
        assertWithMessage("requests in ${QUIET_MS / 1000} s at rest").that(restRequests).isAtMost(10)
    }

    @Test
    fun `a run dated behind the last finished one is followed without reading the chat again and again (Beta)`() =
        runDatedBehindIsFollowedWithoutRereading(TranscriptEngine.BETA)

    @Test
    fun `a run dated behind the last finished one is followed without reading the chat again and again (Stable)`() =
        runDatedBehindIsFollowedWithoutRereading(TranscriptEngine.STABLE)

    private companion object {
        const val WATCH_MS = 10_000L
        const val QUIET_MS = 5_000L
    }
}
