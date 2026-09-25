package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Where the time goes when a chat opens and when the reader pulls to catch up, on an account shaped like Bennett's
 * (a Project coordinator, its worker and 28 other agents in the sidebar), at a phone's 300–900 ms round trips over
 * HTTP/2 and 600 KB/s: the moment the newest content is on screen and every request the open made, by route and by
 * when it went out. A profile, not a gate: each case asserts only that the newest content arrived.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OpenLatencyProfileTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val worker = BigProject.WORKERS.first()
    private val coordinator = BigProject.AGENT_ID
    private var elsewhere = 0

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L, http2 = true).start()
        server.bytesPerSecond = 600_000L
        server.liveHeartbeatMs = 5_000L
        addChat(worker, "Scanner", BigProject.workerTurns(now - 41 * BigProject.TURN_SPACING_MS, turns = 40))
        addChat(coordinator, BigProject.AGENT_NAME, BigProject.turns(now - 121 * BigProject.TURN_SPACING_MS, turns = 120))
        server.composers[coordinator] = FaultServer.Composer(coordinator, BigProject.AGENT_NAME, activityMs = now - 60_000L, project = true)
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = now - 90_000L, manager = coordinator)
        server.workers[coordinator] = listOf(worker to "MANAGER_SPAWN_KIND_CREATED")
        repeat(FILLERS) { i -> addFiller(i) }
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/run-elsewhere-")
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun addChat(id: String, name: String, turns: List<BigProject.Turn>) {
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = id, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.lastOrNull())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        server.transcripts[id] = BigProject.v0Transcript(turns)
        server.records[id] = turns.flatMap { it.record }
    }

    /** One of the other agents in the sidebar: a few turns of its own, some running, as a busy account has them. */
    private fun addFiller(i: Int) {
        val id = "bc-filler-$i"
        val at = BigProject.iso(now - (200 + i) * 1_000L)
        server.agents[id] = AgentDto(id = id, name = "Agent $i", status = if (i % 3 == 0) "ACTIVE" else "IDLE", createdAt = at, updatedAt = at, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = "Agent $i", status = "FINISHED")
        server.composers[id] = FaultServer.Composer(id, "Agent $i", activityMs = now - (200 + i) * 1_000L, running = i % 3 == 0)
    }

    private fun rig(disk: File, engine: TranscriptEngine = TranscriptEngine.BETA): FaultRig =
        FaultRig(server.baseUrl, disk, readTimeoutMs = 20_000L, extended = true, engine = engine, http2 = true).also {
            it.now = now
            rigs += it
        }

    private fun FaultRig.state(id: String): ConversationState = conversations.state(id).value
    private fun ConversationState.shows(text: String) = items.any { it is UserMessage && it.text == text }

    private suspend fun FaultRig.settle(id: String) {
        awaitUntil(120_000) {
            val s = state(id)
            val quiet = !s.isLoading && !s.isLoadingOlder && s.items.isNotEmpty() && s.traceStatus.pending == 0
            if (!quiet) return@awaitUntil false
            val items = s.items
            delay(1_000)
            state(id).items == items
        }
    }

    private fun startElsewhere(id: String): String {
        val n = ++elsewhere
        val prompt = "Turn $n from the desktop: check the fee table for market ${300 + n}."
        server.startTurnElsewhere(id, prompt, "run-elsewhere-$n")
        return prompt
    }

    /** Every request since [from], as `+ms route` from [t0Millis] (server clock), for the report. */
    private fun timeline(from: Int, t0Millis: Long): String =
        server.seen.drop(from).joinToString("\n") { "      +${it.atMillis - t0Millis}ms ${it.route} ${it.path.take(70)}" }

    private fun report(label: String, from: Int, t0: Long, newestMs: Long) {
        val asked = server.seen.drop(from).groupingBy { it.route }.eachCount()
        println("PROFILE $label: newest on screen after $newestMs ms; ${asked.values.sum()} requests $asked")
        println(timeline(from, t0))
    }

    @Test
    fun `reopen after two minutes away, a turn taken elsewhere, the sidebar refreshing`() = runBlocking<Unit> {
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        rig.conversations.attach(worker)
        rig.settle(worker)
        rig.conversations.detach(worker)
        delay(1_000)
        rig.now += 120_000L
        val prompt = startElsewhere(worker)

        val from = server.seen.size
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        rig.scope.launch { rig.agents.refresh() }
        rig.conversations.attach(worker)
        rig.awaitUntil(60_000) { rig.state(worker).shows(prompt) }
        val newestMs = (System.nanoTime() - started) / 1_000_000
        report("reopen+sidebar", from, t0, newestMs)
        assertThat(rig.state(worker).shows(prompt)).isTrue()
    }

    @Test
    fun `reopen after the process died, a turn taken elsewhere`() = runBlocking<Unit> {
        val disk = folder.newFolder("disk")
        rig(disk).let { first ->
            first.agents.refresh()
            first.conversations.attach(worker)
            first.settle(worker)
        }
        rigs.forEach { it.close() }
        rigs.clear()
        val prompt = startElsewhere(worker)

        val second = rig(disk)
        second.now += 120_000L
        val from = server.seen.size
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        second.scope.launch { second.agents.refresh() }
        second.conversations.attach(worker)
        second.awaitUntil(60_000) { second.state(worker).items.isNotEmpty() }
        val paintMs = (System.nanoTime() - started) / 1_000_000
        second.awaitUntil(60_000) { second.state(worker).shows(prompt) }
        val newestMs = (System.nanoTime() - started) / 1_000_000
        println("PROFILE cold: cached paint after $paintMs ms")
        report("cold-reopen", from, t0, newestMs)
    }

    @Test
    fun `catch up on an open chat whose watch heard nothing`() = runBlocking<Unit> {
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        rig.conversations.attach(worker)
        rig.settle(worker)
        server.outage(Route.AccountList, Fault.Gateway(502))
        server.outage(Route.Live, Fault.Gateway(502))
        delay(1_500)
        val prompt = startElsewhere(worker)

        val from = server.seen.size
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        val answer = rig.conversations.catchUp(worker)
        val answeredMs = (System.nanoTime() - started) / 1_000_000
        println("PROFILE catch-up answered after $answeredMs ms: $answer")
        rig.awaitUntil(60_000) { rig.state(worker).shows(prompt) }
        report("catch-up", from, t0, (System.nanoTime() - started) / 1_000_000)
    }

    @Test
    fun `reopen the Project coordinator after two minutes, five reports taken meanwhile, the sidebar refreshing`() = runBlocking<Unit> {
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        rig.conversations.attach(coordinator)
        rig.settle(coordinator)
        rig.conversations.detach(coordinator)
        delay(1_000)
        rig.now += 120_000L
        var last = ""
        repeat(5) { last = startElsewhere(coordinator); server.endTurn(coordinator, durationMs = 5_000L) }

        val from = server.seen.size
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        rig.scope.launch { rig.agents.refresh() }
        rig.conversations.attach(coordinator)
        rig.awaitUntil(60_000) { rig.state(coordinator).shows(last) }
        report("project-reopen+sidebar", from, t0, (System.nanoTime() - started) / 1_000_000)
    }

    /** A long turn's stream so far: [rounds] reads of about a kilobyte each, with the agent's narration between them. */
    private fun longTurnLog(runId: String, rounds: Int, tag: String = "r"): List<Pair<String, String>> = buildList {
        repeat(rounds) { i ->
            val args = """{"target_file":"src/module_$i.kt"}"""
            add("tool_call" to """{"callId":"call-$tag-$i","name":"read","status":"running","args":$args}""")
            add("tool_call" to """{"callId":"call-$tag-$i","name":"read","status":"completed","args":$args,"result":{"success":{"content":"${"x".repeat(900)}"}}}""")
            add("assistant" to """{"text":"Checked module $i ($tag). "}""")
        }
    }

    private fun ConversationState.says(text: String) = items.any { it.toString().contains(text) }

    @Test
    fun `come back to a turn still running after its stream was released`() = runBlocking<Unit> {
        val runId = "run-elsewhere-long"
        server.startTurnElsewhere(worker, "A long refactor, from the desktop.", runId)
        server.logs[runId] = listOf("status" to """{"runId":"$runId","status":"RUNNING"}""") + longTurnLog(runId, rounds = 600)
        server.clear(Route.Stream)
        // The turn goes on: every connection gets what the log has so far and is cut, as a stream a turn is still writing.
        server.outage(Route.Stream, Fault.StreamCut(events = 100_000), path = "/$runId/")
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        fun says(text: String) = rig.hub.current(worker, runId)?.items.orEmpty().any { it.toString().contains(text) }
        val first = rig.scope.launch { rig.hub.snapshots(worker, runId).collect {} }
        rig.awaitUntil(90_000) { says("Checked module 599 (r).") }
        first.cancel()
        delay(1_000)
        server.logs[runId] = server.logs.getValue(runId) + longTurnLog(runId, rounds = 3, tag = "new")

        val from = server.seen.size
        val bytesBefore = server.bytesByRoute[Route.Stream] ?: 0L
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        val again = rig.scope.launch { rig.hub.snapshots(worker, runId).collect {} }
        rig.awaitUntil(90_000) { says("Checked module 2 (new).") }
        val newestMs = (System.nanoTime() - started) / 1_000_000
        again.cancel()
        val streamBytes = (server.bytesByRoute[Route.Stream] ?: 0L) - bytesBefore
        val resumes = server.seen.drop(from).filter { it.route == Route.Stream }.map { it.lastEventId ?: "none" }
        println("PROFILE running-return: stream bytes $streamBytes, stream requests with Last-Event-ID $resumes")
        report("running-return", from, t0, newestMs)
        assertThat(resumes.first()).isNotEqualTo("none")
        assertThat(streamBytes).isLessThan(50_000L)
        assertThat(rig.hub.current(worker, runId)!!.items.count { it.toString().contains("Checked module 5 (r).") }).isEqualTo(1)
    }

    /** What a chat held by live sync (see `LiveSync`) costs the reader to open: the delta is read before they ask. */
    private suspend fun FaultRig.openHeld(id: String, label: String, newest: (ConversationState) -> Boolean) {
        val from = server.seen.size
        val t0 = server.nowMillis()
        val started = System.nanoTime()
        conversations.attach(id)
        awaitUntil(60_000) { newest(state(id)) }
        val newestMs = (System.nanoTime() - started) / 1_000_000
        // What opening it still asks for, once the screen has settled: none of it stands between the reader and the turn.
        delay(1_500)
        report(label, from, t0, newestMs)
        assertThat(newestMs).isLessThan(250L)
    }

    @Test
    fun `live sync - reopen after two minutes away, a turn taken elsewhere, the chat held`() = runBlocking<Unit> {
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        rig.conversations.attach(worker)
        rig.settle(worker)
        rig.conversations.detach(worker)
        rig.conversations.hold(worker)
        delay(1_000)
        rig.now += 120_000L
        val prompt = startElsewhere(worker)
        // The sidebar's refresh is the held chat's word; the read it sets off is behind the reader's back.
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.state(worker).shows(prompt) }

        rig.openHeld(worker, "held-reopen") { it.shows(prompt) }
    }

    @Test
    fun `live sync - reopen the Project coordinator after five reports, held`() = runBlocking<Unit> {
        val rig = rig(folder.newFolder("disk"))
        rig.agents.refresh()
        rig.conversations.attach(coordinator)
        rig.settle(coordinator)
        rig.conversations.detach(coordinator)
        rig.conversations.hold(coordinator)
        delay(1_000)
        rig.now += 120_000L
        var last = ""
        repeat(5) {
            last = startElsewhere(coordinator)
            rig.agents.refresh()
            server.endTurn(coordinator, durationMs = 5_000L)
        }
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.state(coordinator).shows(last) }

        rig.openHeld(coordinator, "held-project-reopen") { it.shows(last) }
    }

    @Test
    fun `live sync - after the process died, a held chat's turn was already on the disk`() = runBlocking<Unit> {
        val disk = folder.newFolder("disk")
        val prompt = rig(disk).let { first ->
            first.agents.refresh()
            first.conversations.hold(worker)
            first.conversations.settled(worker)
            first.now += 120_000L
            val prompt = startElsewhere(worker)
            first.agents.refresh()
            first.awaitUntil(60_000) { first.state(worker).shows(prompt) }
            delay(1_500)
            prompt
        }
        rigs.forEach { it.close() }
        rigs.clear()

        val second = rig(disk)
        second.now += 180_000L
        val started = System.nanoTime()
        second.conversations.attach(worker)
        second.awaitUntil(60_000) { second.state(worker).items.isNotEmpty() }
        val paintMs = (System.nanoTime() - started) / 1_000_000
        val firstPaint = second.state(worker)
        println("PROFILE held-cold: cached paint after $paintMs ms, the turn taken elsewhere in it: ${firstPaint.shows(prompt)}")
        assertThat(firstPaint.shows(prompt)).isTrue()
    }

    private companion object {
        const val FILLERS = 28
    }
}
