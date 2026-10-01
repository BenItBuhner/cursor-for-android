package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.RunMonitor
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptEngine
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
 * What one chat, and the live notification behind it, ask of Cursor per minute while nothing happens on the server
 * that would warrant a request: a turn under way inside a long tool call (its stream open and sending keep-alives,
 * no events), an agent talking at an ordinary pace, eight agents followed for the notification with no chat open, and
 * the same chat with the server refusing (`429`, a `Retry-After`) the record, the stream or the transcript. Every
 * count is per minute of the agents' time — the clock the hub's windows run on is scaled by [SCALE] here, so a
 * [WINDOW_MS] wait stands for a few minutes of the phone sitting on a chat — and held to a budget, so a loop that
 * asks the same thing of the server again and again fails here rather than on a key the API has stopped answering.
 *
 * The refused scenarios are the shared backoff's lock: a `429` or a `Retry-After` heard by one client holds the
 * host for every other (`HostPause`, `RetryInterceptor`, `SseRunStreamer`, `ApiThrottle`), and nothing asks again
 * inside the wait. For the record: v0.4.30 asked 5.25 a minute for one open chat and 8.25 for eight followed agents;
 * v0.4.31's stall watch, resuming every two minutes at its cap, made that 6.75 and 20.25. The watch now runs only for
 * a chat on screen (see `LiveRunHub.stallTimeoutMs`): the followed agents are back to the list's refresh alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatRequestBudgetTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private var monitor: RunMonitor? = null
    private val now = 1_800_000_000_000L
    private val agentId = "bc-budget-0"
    private val run = "run-budget-0"

    @After
    fun tearDown() {
        monitor?.stop()
        rig?.let { r -> r.steering.detach(agentId); r.conversations.detach(agentId); r.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun assistant(text: String) = "assistant" to """{"text":${quote(text)}}"""
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** [count] chats, each on a turn under way streamed live over HTTP/2, the account calling each running. */
    private fun fleet(count: Int) {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = BEAT_MS
        repeat(count) { i ->
            val id = "bc-budget-$i"
            val runId = "run-budget-$i"
            server.runs[runId] = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L))
            server.logs[runId] = listOf("status" to """{"runId":"$runId","status":"RUNNING"}""", assistant("Reading the repository. "))
            server.agents[id] = AgentDto(id = id, name = "Agent $i", status = "ACTIVE", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L), latestRunId = runId, url = "https://cursor.com/agents/$id")
            server.v0[id] = V0AgentDto(id = id, name = "Agent $i", status = "RUNNING")
            server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Fix the flaky test"))
            server.composers[id] = FaultServer.Composer(id, "Agent $i", activityMs = now - 1_000L, running = true)
        }
    }

    private fun openRig(): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE, queuePollMs = 10_000L / SCALE, http2 = true, stallTimeoutMs = STALL_MS).also {
            it.now = now
            rig = it
        }

    private val state get() = rig!!.conversations.state(agentId).value
    private fun said(): String = state.items.filterIsInstance<AssistantMessage>().joinToString("") { it.markdown }

    /** The chat open on screen, its turn followed. */
    private suspend fun FaultRig.openChat() {
        agents.refresh()
        awaitUntil(15_000) { agents.runningScan.value.accountWord[agentId]?.running == true }
        conversations.attach(agentId)
        steering.attach(agentId)
        awaitUntil(30_000) { state.let { !it.isLoading && it.isStreaming && it.activeRunId == run } && said().contains("Reading the repository.") }
        awaitUntil(15_000) { steering.state(agentId).value.isQueueAvailable }
    }

    /** The live notification's monitor over this rig, following every running agent the list has. */
    private suspend fun FaultRig.startMonitor(): RunMonitor {
        val m = RunMonitor(agents, hub, runRecord = { a, r -> agents.runRecord(a, r) }, refreshIntervalMs = 60_000L / SCALE, nowProvider = { now })
        monitor = m
        m.start()
        awaitUntil(30_000) { m.state.value.running.size >= server.agents.values.count { it.status == "ACTIVE" }.coerceAtMost(RunMonitor.MAX_TRACKED) }
        return m
    }

    /** What the server was asked over [windowMs] from now on, by route, scaled to requests per minute of the agents' time. */
    private suspend fun window(label: String, windowMs: Long = WINDOW_MS): Map<Route, Double> {
        val from = server.seen.size
        delay(windowMs)
        val counts = server.seen.drop(from).groupingBy { it.route }.eachCount()
        val minutes = windowMs * SCALE / 60_000.0
        val perMinute = counts.mapValues { (_, n) -> n / minutes }
        val total = counts.values.sum() / minutes
        println("BUDGET $label (${"%.1f".format(minutes)} min of the agents' time): total=${"%.2f".format(total)}/min · ${perMinute.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${"%.2f".format(it.value)}" }}")
        return perMinute
    }

    private fun Map<Route, Double>.total() = values.sum()

    private fun budget(what: String, perMinute: Double, max: Double) =
        assertWithMessage("$what: ${"%.2f".format(perMinute)} requests a minute, over the budget of $max").that(perMinute).isAtMost(max)

    @Test
    fun `one open chat inside a long tool call`() = runBlocking<Unit> {
        fleet(1)
        val rig = openRig()
        rig.openChat()
        val asked = window("open chat, long tool call")
        budget("the run record", asked[Route.GetRun] ?: 0.0, RECORD_PER_MINUTE_QUIET)
        budget("stream opens", asked[Route.Stream] ?: 0.0, STREAM_OPENS_PER_MINUTE_QUIET)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_OPEN_CHAT)
    }

    @Test
    fun `one open chat with the agent talking`() = runBlocking<Unit> {
        fleet(1)
        // A line every few seconds of the agents' time: never quiet for a stall window.
        server.liveRunGenerator = { runId, tick -> if (tick % 5 == 0) listOf(assistant("Step $tick of $runId. ")) else emptyList() }
        val rig = openRig()
        rig.openChat()
        val asked = window("open chat, agent talking")
        budget("the run record", asked[Route.GetRun] ?: 0.0, 0.0)
        budget("stream opens", asked[Route.Stream] ?: 0.0, 0.0)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_OPEN_CHAT)
    }

    @Test
    fun `eight running agents followed for the notification with no chat open`() = runBlocking<Unit> {
        fleet(RunMonitor.MAX_TRACKED)
        val rig = openRig()
        rig.agents.refresh()
        rig.startMonitor()
        // Every followed run has its one stream open before the count starts: what follows must be none.
        rig.awaitUntil(15_000) { server.requests(Route.Stream).size >= RunMonitor.MAX_TRACKED }
        // v0.4.30's window first, for the like-for-like figure; then the long one the stall watch's cap would show in.
        val asked4 = window("eight followed agents, long tool calls, no chat open", REFUSED_WINDOW_MS)
        budget("everything, over v0.4.30's window", asked4.total(), TOTAL_PER_MINUTE_FLEET_STEADY)
        val asked = window("eight followed agents, long tool calls, no chat open")
        // The notification resumes nothing and rebuilds nothing: a run with no chat on it holds its connection.
        budget("stream opens", asked[Route.Stream] ?: 0.0, 0.0)
        // The run records are read by the agent list's refresh, one per followed agent per list, and by nothing else
        // (one refresh's worth of room: a list read just before the window has its records read just inside it).
        val lists = asked[Route.ListAgents] ?: 0.0
        budget("the run records, per list refresh", asked[Route.GetRun] ?: 0.0, RunMonitor.MAX_TRACKED * (lists + 1 / WINDOW_MINUTES))
        budget("everything", asked.total(), TOTAL_PER_MINUTE_FLEET)
    }

    @Test
    fun `one open chat and eight followed agents inside long tool calls`() = runBlocking<Unit> {
        fleet(RunMonitor.MAX_TRACKED)
        val rig = openRig()
        rig.openChat()
        rig.startMonitor()
        val asked = window("open chat and eight followed agents, long tool calls")
        // Only the open chat's stream is ever taken up again; the seven followed beside it hold theirs.
        budget("stream opens", asked[Route.Stream] ?: 0.0, STREAM_OPENS_PER_MINUTE_QUIET)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_FLEET_AND_CHAT)
    }

    @Test
    fun `one open chat with the run record rate limited`() = runBlocking<Unit> {
        fleet(1)
        val rig = openRig()
        rig.openChat()
        server.outage(Route.GetRun, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = RETRY_AFTER_S.toString()))
        val asked = window("open chat, the run record refused with a Retry-After", REFUSED_WINDOW_MS)
        budget("the run record", asked[Route.GetRun] ?: 0.0, RECORD_PER_MINUTE_REFUSED)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_OPEN_CHAT)
    }

    @Test
    fun `one open chat with the stream rate limited after a drop`() = runBlocking<Unit> {
        fleet(1)
        val rig = openRig()
        rig.openChat()
        server.outage(Route.Stream, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = RETRY_AFTER_S.toString()))
        server.dropLiveStreams(run)
        val asked = window("open chat, the stream refused with a Retry-After", REFUSED_WINDOW_MS)
        budget("stream opens", asked[Route.Stream] ?: 0.0, STREAM_OPENS_PER_MINUTE_REFUSED)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_OPEN_CHAT)
    }

    @Test
    fun `a chat opened while the documented API refuses everything`() = runBlocking<Unit> {
        fleet(1)
        for (route in listOf(Route.GetRun, Route.ListRuns, Route.Conversation, Route.Stream, Route.GetAgent)) {
            server.outage(route, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = RETRY_AFTER_S.toString()))
        }
        val rig = openRig()
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.steering.attach(agentId)
        val asked = window("chat opened under a rate limit", REFUSED_WINDOW_MS)
        budget("everything", asked.total(), TOTAL_PER_MINUTE_REFUSED)
    }

    private companion object {
        /** The hub's windows run [SCALE] times faster than production's here: a 30 s stall window is 1.5 s. */
        const val SCALE = 20L
        const val STALL_MS = 30_000L / SCALE
        /** A held stream's keep-alive every 2 s of the agents' time. */
        const val BEAT_MS = 2_000L / SCALE
        /**
         * A quiet window: ten minutes of the agents' time, long enough for the stall watch's doubling pause to reach
         * its cap (30 s, 60, 120, 240, then the cap), so the cap's cost shows in the count.
         */
        const val WINDOW_MS = 10 * 60_000L / SCALE
        const val WINDOW_MINUTES = WINDOW_MS * SCALE / 60_000.0
        /** A refused window: four minutes of the agents' time, several times the wait the refusal names. Also v0.4.30's. */
        const val REFUSED_WINDOW_MS = 4 * 60_000L / SCALE
        /** The wait a refusal names, in the server's seconds: half a minute of the agents' time, scaled. */
        const val RETRY_AFTER_S = 1

        // Budgets are per minute of the agents' time, set above what was measured when each was set (in the
        // comments) with room for a CI runner's noise, and well below what a loop costs: a chat that asked for its
        // record or its stream every few seconds would be tens a minute.
        /** One open chat inside a long tool call: the stall watch's resume, 30 s doubling to 5 min (measured 0.5 and 0.5; 0.6 at the 2 min cap of v0.4.31). */
        const val RECORD_PER_MINUTE_QUIET = 1.0
        const val STREAM_OPENS_PER_MINUTE_QUIET = 1.0
        /** The record or the stream refused with a Retry-After of half a minute (measured 2.0 and 1.5): the wait is waited, not talked over. */
        const val RECORD_PER_MINUTE_REFUSED = 2.5
        const val STREAM_OPENS_PER_MINUTE_REFUSED = 2.5
        /** One open chat, all told: the account's queue read every ten seconds is most of it (measured 6.4 quiet, 8.0 refused). */
        const val TOTAL_PER_MINUTE_OPEN_CHAT = 10.0
        /**
         * Eight followed agents with no chat open: the agent list's refresh a minute — three list routes and a record
         * per followed agent — and nothing else (measured 9.9 over ten minutes; v0.4.30 alike, 8.25 over its four, which
         * held three refreshes rather than four). A fourth refresh falling inside the four-minute window makes 11.0.
         */
        const val TOTAL_PER_MINUTE_FLEET_STEADY = 11.0
        const val TOTAL_PER_MINUTE_FLEET = 12.0
        /** Eight followed agents and one open chat (measured 16.3): the fleet's refresh and the chat's queue read. */
        const val TOTAL_PER_MINUTE_FLEET_AND_CHAT = 20.0
        /** A chat opened while every documented call is refused (measured 7.75): nothing asks again inside the wait. */
        const val TOTAL_PER_MINUTE_REFUSED = 12.0
    }
}
