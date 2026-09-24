package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
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
import java.time.Instant

/**
 * How long a turn started on another device takes to reach a chat open on this one, end to end, at a phone's
 * 300–900 ms round trips and with the account holding the live stream as it does in production (HTTP/2, headers
 * and heartbeats for the whole hold): from the moment the server has the turn to the moment its prompt is on screen
 * with the turn under way. Each engine, a worker's chat and a Project's coordinator, and the moments a chat is most
 * often in when it happens — long at rest, a moment after coming to rest, right after the turn before it ended (a
 * coordinator's workers report back to back), back from the background, after a network blip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WatchLatencyTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val now = 1_800_000_000_000L
    private val worker = BigProject.WORKERS.first()
    private val coordinator = BigProject.AGENT_ID
    private var turnsStarted = 0

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L, http2 = true).start()
        server.liveHeartbeatMs = 5_000L
        addChat(worker, "Scanner", BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6))
        addChat(coordinator, BigProject.AGENT_NAME, BigProject.turns(now - 121 * BigProject.TURN_SPACING_MS, turns = 120))
        server.composers[coordinator] = FaultServer.Composer(coordinator, BigProject.AGENT_NAME, activityMs = now - 60_000L, project = true)
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = now - 90_000L, manager = coordinator)
        server.workers[coordinator] = listOf(worker to "MANAGER_SPAWN_KIND_CREATED")
        // A turn started elsewhere stays under way until the test ends it: its stream delivers what it has and drops, and the hub comes back to it.
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/run-elsewhere-")
    }

    @After
    fun tearDown() {
        rig?.close()
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

    private fun rig(engine: TranscriptEngine): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("rig-${engine.name}"), readTimeoutMs = 20_000L, extended = true, engine = engine, http2 = true).also {
            it.now = now
            rig = it
        }

    private fun FaultRig.state(id: String): ConversationState = conversations.state(id).value

    private fun ConversationState.shows(text: String): Boolean = items.any { it is UserMessage && it.text == text }

    private fun ConversationState.restful(): Boolean =
        !isLoading && !isStreaming && !isReconnecting && runStatus?.isActive != true && items.isNotEmpty() && traceStatus.pending == 0

    /** Opens [id] as the screen does — the chat and its queue's poll — and waits for it to come to rest. */
    private suspend fun FaultRig.openAtRest(id: String) {
        agents.refresh()
        conversations.attach(id)
        steering.attach(id)
        awaitUntil(120_000) { state(id).restful() }
    }

    /** Another device starts a turn in [id]; returns its prompt. */
    private fun startElsewhere(id: String): String {
        val n = ++turnsStarted
        val prompt = "Turn $n from the desktop: check the fee table for market ${200 + n}."
        server.startTurnElsewhere(id, prompt, "run-elsewhere-$n")
        return prompt
    }

    /** The turn another device started in [id] ends on the server: the run, the row, the list entry and the record all say so. */
    private fun endElsewhere(id: String) {
        server.endTurn(id, durationMs = 20_000L)
        val at = Instant.ofEpochMilli(now + 90_000L).toString()
        server.agents[id]?.let { server.agents[id] = it.copy(updatedAt = at) }
        server.composers[id]?.let { server.composers[id] = it.copy(running = false, activityMs = it.activityMs + 30_000L) }
        server.records[id]?.let { steps -> server.records[id] = steps + buildJsonObject { put("text", ""); put("isMessageDone", true) } }
        server.transcripts[id]?.let { server.transcripts[id] = it + V0ConversationMessageDto("run-elsewhere-$turnsStarted-a", "assistant_message", "Done.") }
        server.touch(id)
    }

    /** From the server having the turn to its prompt on screen with the turn under way, in ms. */
    private suspend fun FaultRig.onScreenAfter(id: String, prompt: String, startedNanos: Long, limitMs: Long = 45_000L): Long {
        try {
            awaitUntil(limitMs) { state(id).let { it.shows(prompt) && it.runStatus?.isActive == true } }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            val s = state(id)
            println("   MISSED: not on screen within $limitMs ms — prompt shown ${s.shows(prompt)}, run ${s.activeRunId} ${s.runStatus}, streaming ${s.isStreaming}")
            return -1L
        }
        return (System.nanoTime() - startedNanos) / 1_000_000
    }

    private fun report(label: String, ms: Long) = println("   latency | $label | $ms ms")

    /** The chat long at rest (8 s: the live stream held and heartbeating, the list entry polled), then the turn. */
    private suspend fun atRest(engine: TranscriptEngine, id: String, label: String) {
        val rig = rig(engine)
        rig.openAtRest(id)
        delay(8_000)
        val started = System.nanoTime()
        val prompt = startElsewhere(id)
        report(label, rig.onScreenAfter(id, prompt, started))
    }

    @Test fun `Beta worker at rest`() = runBlocking { atRest(TranscriptEngine.BETA, worker, "Beta worker, at rest") }

    @Test fun `Stable worker at rest`() = runBlocking { atRest(TranscriptEngine.STABLE, worker, "Stable worker, at rest") }

    @Test fun `Beta coordinator at rest`() = runBlocking { atRest(TranscriptEngine.BETA, coordinator, "Beta coordinator, at rest") }

    @Test fun `Stable coordinator at rest`() = runBlocking { atRest(TranscriptEngine.STABLE, coordinator, "Stable coordinator, at rest") }

    /** The turn starts a moment after the chat came to rest: before the watch's first look. */
    private suspend fun justAfterRest(engine: TranscriptEngine, id: String, label: String) {
        val rig = rig(engine)
        rig.openAtRest(id)
        delay(300)
        val started = System.nanoTime()
        val prompt = startElsewhere(id)
        report(label, rig.onScreenAfter(id, prompt, started))
    }

    @Test fun `Beta worker just after rest`() = runBlocking { justAfterRest(TranscriptEngine.BETA, worker, "Beta worker, just after rest") }

    @Test fun `Stable worker just after rest`() = runBlocking { justAfterRest(TranscriptEngine.STABLE, worker, "Stable worker, just after rest") }

    /**
     * A coordinator's day: a turn ends on the phone and the next one starts elsewhere [gapsMs] later — a worker's
     * report, a message from the desktop — each measured.
     */
    private suspend fun backToBack(engine: TranscriptEngine, id: String, label: String, gapsMs: List<Long> = listOf(1_500L, 4_000L, 8_000L)) {
        val rig = rig(engine)
        rig.openAtRest(id)
        delay(3_000)
        var prompt = startElsewhere(id)
        rig.onScreenAfter(id, prompt, System.nanoTime())
        for (gap in gapsMs) {
            delay(2_000)
            endElsewhere(id)
            rig.awaitUntil(60_000) { rig.state(id).restful() }
            delay(gap)
            val started = System.nanoTime()
            prompt = startElsewhere(id)
            report("$label, ${gap} ms after the last turn ended", rig.onScreenAfter(id, prompt, started))
        }
    }

    @Test fun `Beta coordinator back to back`() = runBlocking { backToBack(TranscriptEngine.BETA, coordinator, "Beta coordinator") }

    @Test fun `Stable coordinator back to back`() = runBlocking { backToBack(TranscriptEngine.STABLE, coordinator, "Stable coordinator") }

    /** Away from the screen for 12 s, back, and the turn starts 1.5 s later; and a turn that started while away, from the return. */
    private suspend fun afterResume(engine: TranscriptEngine, id: String, label: String) {
        val rig = rig(engine)
        rig.openAtRest(id)
        delay(3_000)
        rig.conversations.pause(id)
        delay(6_000)
        rig.now += 12_000L
        var prompt = startElsewhere(id)
        delay(6_000)
        rig.now += 12_000L
        val back = System.nanoTime()
        rig.conversations.resume(id)
        report("$label, a turn started while away, from the return", rig.onScreenAfter(id, prompt, back))
        delay(2_000)
        endElsewhere(id)
        rig.awaitUntil(60_000) { rig.state(id).restful() }
        rig.conversations.pause(id)
        delay(4_000)
        rig.now += 12_000L
        rig.conversations.resume(id)
        delay(1_500)
        val started = System.nanoTime()
        prompt = startElsewhere(id)
        report("$label, a turn started 1.5 s after the return", rig.onScreenAfter(id, prompt, started))
    }

    @Test fun `Beta worker after resume`() = runBlocking { afterResume(TranscriptEngine.BETA, worker, "Beta worker") }

    @Test fun `Stable worker after resume`() = runBlocking { afterResume(TranscriptEngine.STABLE, worker, "Stable worker") }

    /** The live stream fails [failures] times running — a network handoff — then holds again; the turn starts [afterMs] later. */
    @Test fun `Beta worker after a network blip`() = runBlocking {
        val rig = rig(TranscriptEngine.BETA)
        server.script(Route.Live, *Array(3) { Fault.Gateway(502) })
        rig.openAtRest(worker)
        rig.awaitUntil(60_000) { server.requests(Route.Live).size >= 3 }
        delay(20_000)
        val started = System.nanoTime()
        val prompt = startElsewhere(worker)
        report("Beta worker, 20 s after 3 live-stream failures", rig.onScreenAfter(worker, prompt, started))
        println("   live requests: ${server.requests(Route.Live).size}, list-entry reads: ${server.composerReads.count { it == worker }}")
    }

    /** The sidebar refreshing every 3 s beside the open chat (Extended mode: the account's list, page by page, over the same throttle). */
    @Test fun `Beta worker with the sidebar busy`() = runBlocking {
        val rig = rig(TranscriptEngine.BETA)
        rig.openAtRest(worker)
        val busy = rig.scope.launch { while (true) { rig.agents.refresh(); delay(3_000) } }
        delay(8_000)
        val started = System.nanoTime()
        val prompt = startElsewhere(worker)
        report("Beta worker, sidebar refreshing", rig.onScreenAfter(worker, prompt, started))
        busy.cancel()
    }
}
