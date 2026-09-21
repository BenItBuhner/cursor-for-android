package com.cursorforandroid.data.faults

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.data.repo.PromptlessFixture
import com.cursorforandroid.data.repo.TurnPairing
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
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
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The documented path on a chat with runs no prompt started — Bennett's Polymarket Project, 2026-09-21, 0.3.56: 298
 * prompts, 324 runs, the account's record refused. Laid over by position, the newest twenty-six runs had no prompt
 * and the prompts that started them stood twenty-six turns up: four coordinator replies in a row with no user
 * bubble between them (`internal/reference/missing-prompts-2026-09-21-0917`). The acceptance test, through the real
 * pipeline: every `/v0` prompt on screen exactly once, in `/v0`'s order, the live run's prompt directly above the
 * live run's activity — with the runs' results as the evidence, and without them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PromptlessRunsTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - PromptlessFixture.TURNS * LongProject.TURN_SPACING_MS
    private val turns = PromptlessFixture.turns(firstAt)
    private val live get() = turns.last()
    private val transcript = LongProject.v0Transcript(turns)
    private val promptIds = transcript.filter { it.type == "user_message" }.map { it.id }

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 60L..140L).start()
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun seed(withResults: Boolean, running: Boolean = true) {
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = if (withResults) LongProject.result(turn) else null)
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = if (running) "ACTIVE" else "IDLE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = if (running) "RUNNING" else "FINISHED")
        server.transcripts[agentId] = transcript
        server.records[agentId] = turns.flatMap { it.record }
        // The record refused in the server's words: the documented path stands in, as on Bennett's phone.
        server.outage(Route.RecordState, Fault.Status(404, "unimplemented", FaultServer.REMOVED_UNARY))
        if (running) {
            // The live run's stream delivers what it has and drops; the hub keeps coming back to it.
            server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        } else {
            // The newest turn is over: its record finished, its log closed.
            server.runs[live.runId] = server.runs.getValue(live.runId).copy(status = "FINISHED", durationMs = 40_000L, updatedAt = LongProject.iso(live.startedAt + 40_000L))
            server.logs[live.runId] = live.log + ("result" to """{"runId":"${live.runId}","status":"FINISHED","text":"","durationMs":40000}""")
        }
    }

    /** What the pairing must say about the runs the list fetched: the fixture's prompt-less ones among them, and the prompts whose runs are older than the page. */
    private fun expectedOf(runsLoaded: Int): Triple<Int, Int, Int> {
        val fetched = turns.takeLast(runsLoaded)
        val promptless = fetched.count { it.promptless }
        val prompted = fetched.count { !it.promptless }
        return Triple(promptless, 298 - prompted, prompted)
    }

    private fun rig(): FaultRig = FaultRig(server.baseUrl, folder.newFolder("rig-${System.nanoTime()}"), readTimeoutMs = 8_000L, extended = true).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    /** The prompts on screen, in screen order, by the `/v0` id each was drawn from (a user's bubble or an injected turn's row). */
    private fun promptsOnScreen(items: List<TimelineItem> = state.items): List<String> =
        items.filter { (it is UserMessage || it is SystemNotification) && it.id.substringBefore('#') in promptIds }.map { it.id.substringBefore('#') }

    private fun liveItems(items: List<TimelineItem> = state.items): List<TimelineItem> = items.filter { it.id.startsWith("activity-${live.runId}-") || it.id.startsWith("asst-${live.runId}-") }

    private suspend fun FaultRig.open() {
        agents.refresh()
        conversations.attach(agentId)
        awaitUntil(60_000) { state.let { !it.isLoading && liveItems(it.items).isNotEmpty() } }
        awaitSettled()
    }

    /** The traces of the window's finished runs read and the frame still for a moment: what the screen shows once the load is done. */
    private suspend fun FaultRig.awaitSettled() {
        awaitUntil(90_000) {
            val quiet = state.traceStatus.pending == 0 && conversations.loadDiagnostics(agentId)!!.let { it.traceQueue == 0 && it.traceInFlight == 0 && !it.traceWorkerRunning }
            if (!quiet) return@awaitUntil false
            val items = state.items
            kotlinx.coroutines.delay(1_000)
            state.items == items
        }
    }

    /** The window's turns as the pairing has them, newest first, by their `/v0` prompt ids: what the screen must show. */
    private fun expectedWindow(items: List<TimelineItem>): List<String> {
        // Every prompt the screen shows is one of the transcript's, once, and the order is the transcript's.
        val shown = promptsOnScreen(items)
        assertThat(shown).containsNoDuplicates()
        assertThat(shown).isInOrder(compareBy<String> { promptIds.indexOf(it) })
        // And nothing in between was skipped: the prompts shown are a contiguous tail of the transcript's.
        assertThat(shown).isEqualTo(promptIds.takeLast(shown.size))
        return shown
    }

    private fun assertLivePromptHeadsLiveRun(items: List<TimelineItem>) {
        val livePrompt = items.indexOfLast { it is UserMessage && it.id == "${live.runId}-u" }
        assertThat(livePrompt).isAtLeast(0)
        // Everything after the live run's prompt is the live run's: nothing else is drawn between them or after.
        val after = items.drop(livePrompt + 1)
        assertThat(after).isNotEmpty()
        assertThat(after.all { it.id.startsWith("activity-${live.runId}-") || it.id.startsWith("asst-${live.runId}-") || it.id.startsWith("footer-${live.runId}") }).isTrue()
        // And no prompt of the transcript's is drawn under a run that is not its own: the user's turns on screen each have their prompt directly above their run.
        val onScreen = items.mapNotNull { item -> item.id.substringBefore('#').removeSuffix("-u").takeIf { item is UserMessage } }.toSet()
        val newest = turns.filter { it.isUser && it.runId in onScreen }
        assertThat(newest).isNotEmpty()
        newest.forEach { turn ->
            val promptAt = items.indexOfFirst { it.id == "${turn.runId}-u" }
            val runAt = items.indexOfFirst { it.id.startsWith("activity-${turn.runId}-") || it.id.startsWith("asst-${turn.runId}-") }
            assertThat(promptAt).isAtLeast(0)
            if (runAt >= 0) {
                assertThat(runAt).isGreaterThan(promptAt)
                // Nothing of another run's between the prompt and its run's first item.
                assertThat(items.subList(promptAt + 1, runAt).none { it.id.startsWith("activity-") && !it.id.startsWith("activity-${turn.runId}-") }).isTrue()
            }
        }
    }

    @Test
    fun `with the runs' results every prompt shows once in order and the newest prompts head their own runs`() = runBlocking<Unit> {
        seed(withResults = true)
        val rig = rig()
        rig.open()
        val items = state.items
        val shown = expectedWindow(items)
        // The window: the newest ten turns, every one of them a prompted turn here.
        assertThat(shown).hasSize(10)
        assertLivePromptHeadsLiveRun(items)
        val diagnostics = rig.conversations.loadDiagnostics(agentId)!!
        val pairing = diagnostics.pairing!!
        val (promptless, runless, prompted) = expectedOf(diagnostics.runsLoaded)
        assertThat(pairing.promptless).isEqualTo(promptless)
        assertThat(pairing.runless).isEqualTo(runless)
        // Every fetched run a prompt started is settled by evidence: an injected turn by its time, the user's by its result, the live one as the live one.
        assertThat(pairing.timestamp + pairing.result + pairing.live).isEqualTo(prompted)
        assertThat(pairing.position).isEqualTo(0)
        assertThat(pairing.live).isEqualTo(1)
        // Older turns, on scroll: still the transcript's order, still no prompt lost or doubled.
        rig.conversations.loadOlder(agentId)
        rig.awaitUntil(30_000) { !state.isLoadingOlder && state.items.size > items.size }
        rig.awaitSettled()
        val more = expectedWindow(state.items)
        assertThat(more.size).isGreaterThan(shown.size)
        assertLivePromptHeadsLiveRun(state.items)
    }

    @Test
    fun `without results the prompts still show once in order, the live prompt heads the live run, and the diagnostics say what settled the rest`() = runBlocking<Unit> {
        seed(withResults = false)
        val rig = rig()
        rig.open()
        val items = state.items
        expectedWindow(items)
        // The one prompt awaiting its reply and the one run under way are each other's, whatever position says.
        val livePrompt = items.indexOfLast { it is UserMessage && it.id == "${live.runId}-u" }
        assertThat(livePrompt).isAtLeast(0)
        assertThat(items.drop(livePrompt + 1).all { it.id.startsWith("activity-${live.runId}-") || it.id.startsWith("asst-${live.runId}-") }).isTrue()
        val diagnostics = rig.conversations.loadDiagnostics(agentId)!!
        val pairing = diagnostics.pairing!!
        val (promptless, runless, prompted) = expectedOf(diagnostics.runsLoaded)
        assertThat(pairing.live).isEqualTo(1)
        assertThat(pairing.result).isEqualTo(0)
        // The injected turns still by their time; the user's finished turns by position, nothing else telling.
        assertThat(pairing.timestamp).isEqualTo(turns.takeLast(diagnostics.runsLoaded).count { !it.promptless && !it.isUser })
        assertThat(pairing.position).isEqualTo(turns.takeLast(diagnostics.runsLoaded).count { it.isUser } - 1)
        assertThat(pairing.timestamp + pairing.position + pairing.live).isEqualTo(prompted)
        assertThat(pairing.promptless).isEqualTo(promptless)
        assertThat(pairing.runless).isEqualTo(runless)
    }

    @Test
    fun `the prompt of a message sent from here stays above its own run when a run no prompt started follows it`() = runBlocking<Unit> {
        seed(withResults = false, running = false)
        val rig = rig()
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.awaitUntil(60_000) { !state.isLoading && state.items.isNotEmpty() }
        rig.awaitSettled()
        // Sent from here: the account starts a run on it and the transcript gets the copy.
        val text = "Bennett here: pause the tape offloads until the VPS is under 4 GB."
        val run = rig.conversations.sendFollowUp(agentId, text).getOrThrow()
        try {
            rig.awaitUntil(30_000) { state.items.any { it is UserMessage && it.text == text } }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("the sent message never showed: run=${run.id} items=${state.items.takeLast(6).map { it.id }}", t)
        }
        // The run ends, and a turn no prompt started follows it — a worker's report the transcript never carried — so
        // that by position the newest prompt would sit under the wrong run.
        val endedAt = rig.now + 40_000L
        server.runs[run.id] = server.runs.getValue(run.id).copy(status = "FINISHED", durationMs = 40_000L, updatedAt = LongProject.iso(endedAt), result = null)
        server.logs[run.id] = listOf("status" to """{"runId":"${run.id}","status":"RUNNING"}""", "result" to """{"runId":"${run.id}","status":"FINISHED","text":"","durationMs":40000}""")
        val injected = RunDto(id = "run-injected-after", agentId = agentId, status = "FINISHED", createdAt = LongProject.iso(endedAt + 5_000L), updatedAt = LongProject.iso(endedAt + 35_000L), durationMs = 30_000L, result = "Noted the worker's report; nothing for Bennett in this one.")
        server.runs[injected.id] = injected
        server.logs[injected.id] = listOf("status" to """{"runId":"${injected.id}","status":"RUNNING"}""", "assistant" to """{"text":"Noted the worker's report; nothing for Bennett in this one."}""", "result" to """{"runId":"${injected.id}","status":"FINISHED","text":"","durationMs":30000}""")
        server.agents[agentId] = server.agents.getValue(agentId).copy(status = "IDLE", latestRunId = injected.id, updatedAt = LongProject.iso(endedAt + 35_000L))
        rig.now = endedAt + 60_000L
        rig.conversations.reload(agentId)
        try {
            rig.awaitUntil(30_000) { !state.isLoading && state.items.any { it.id.startsWith("run-${injected.id}") } }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("the injected run never showed: loading=${state.isLoading} error=${state.error} transcriptError=${state.transcriptError} items=${state.items.takeLast(12).map { it.id }} diagnostics=${rig.conversations.loadDiagnostics(agentId)?.let { "runs=${it.runsLoaded} " + it.pairing?.text }}", t)
        }
        rig.awaitSettled()
        val items = state.items
        // The message once, the transcript's copy or the echo, and directly above its own run's footer — not the injected run's.
        val copies = items.filterIsInstance<UserMessage>().filter { it.text == text }
        assertThat(copies).hasSize(1)
        val promptAt = items.indexOf(copies.single())
        val ownFooter = items.indexOfFirst { it is RunFooter && it.runId == run.id }
        val injectedFooter = items.indexOfFirst { it is RunFooter && it.runId == injected.id }
        assertWithMessage("items after the prompt: ${items.drop(promptAt).map { it.id }} pairing=${rig.conversations.loadDiagnostics(agentId)!!.pairing?.text}").that(ownFooter).isGreaterThan(promptAt)
        assertThat(injectedFooter).isGreaterThan(ownFooter)
        assertThat(items.subList(promptAt + 1, ownFooter).none { it is UserMessage || it is RunFooter }).isTrue()
        // Every prompt on screen once, in the transcript's order; the pairing knows the run by this device's own word for it.
        val shown = items.filter { it is UserMessage || it is SystemNotification }.map { it.id.substringBefore('#') }
        assertThat(shown).containsNoDuplicates()
        val pairing = rig.conversations.loadDiagnostics(agentId)!!.pairing!!
        assertThat(pairing.echo + pairing.result).isAtLeast(1)
        assertThat(pairing.promptless).isAtLeast(1)
    }
}

