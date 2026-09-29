package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ServerRetry
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.conversation.LoadNotices
import com.cursorforandroid.ui.conversation.TRANSCRIPT_REFRESH_DETAIL
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's 0.4.12 Project coordinator, Extended mode on the Beta engine with its chats kept live: "Cursor took too
 * long to respond" over a chat that was running fine. The account's state of a chat of four and a half thousand
 * turns is one long answer, and the odd read of it timed out on a healthy connection; the app said so at once, in the
 * words it uses for a failure, and the reader took it as the agent failing. Meanwhile the chat's stream stayed on a
 * run that had said FINISHED without its `result`, the record a few minutes behind, while the account's next run was
 * already CREATING and a message queued behind it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecordRefreshTimeoutTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val project = BigProject.AGENT_ID

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        if (::server.isInitialized) server.close()
    }

    private fun FaultRig.state(id: String): ConversationState = conversations.state(id).value

    /** The coordinator of over two thousand turns, at rest: every run finished, the record whole. */
    private fun bigProject() {
        server = FaultServer(rttMillis = 20L..60L).start()
        val turns = BigProject.turns(now - BigProject.TURNS * BigProject.TURN_SPACING_MS - 60_000L)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = project, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        server.agents[project] = AgentDto(id = project, name = BigProject.AGENT_NAME, status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(turns.last().endedAt), latestRunId = turns.last().runId, url = "https://cursor.com/agents/$project")
        server.v0[project] = V0AgentDto(id = project, name = BigProject.AGENT_NAME, status = "FINISHED")
        server.transcripts[project] = BigProject.v0Transcript(turns)
        server.records[project] = turns.flatMap { it.record }
    }

    /** A whole turn taken on the desktop while the phone holds the chat: what the next good read brings. */
    private fun turnTakenElsewhere(prompt: String, reply: String) {
        val runId = "run-elsewhere-${server.runs.size}"
        server.runs[runId] = RunDto(id = runId, agentId = project, status = "FINISHED", createdAt = BigProject.iso(now - 30_000L), updatedAt = BigProject.iso(now - 10_000L), durationMs = 20_000L, result = reply)
        server.logs[runId] = listOf("status" to """{"runId":"$runId","status":"RUNNING"}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"$reply","durationMs":20000}""")
        server.agents[project] = server.agents.getValue(project).copy(latestRunId = runId, updatedAt = BigProject.iso(now - 10_000L))
        server.transcripts[project] = server.transcripts.getValue(project) + V0ConversationMessageDto("$runId-u", "user_message", prompt) + V0ConversationMessageDto("$runId-a", "assistant_message", reply)
        server.records[project] = server.records.getValue(project) + listOf(
            buildJsonObject { put("humanMessage", buildJsonObject { put("text", prompt) }) },
            buildJsonObject { put("text", reply) },
            buildJsonObject { put("text", ""); put("isMessageDone", true) },
        )
    }

    /** Short timeouts and pauses so a timeout takes seconds here; the shape of what the client meets is production's. */
    private fun bigRig(quietRetryDelaysMs: List<Long>): FaultRig =
        FaultRig(
            server.baseUrl, folder.newFolder("disk-${rigs.size}"), readTimeoutMs = 2_000L, extended = true, engine = TranscriptEngine.BETA,
            recordWaits = ServerRetry.Waits(onScreen = listOf(100L), state = listOf(100L), passes = listOf(300L)), quietRetryDelaysMs = quietRetryDelaysMs,
        ).also {
            it.now = now
            rigs += it
        }

    private suspend fun FaultRig.openSettled(id: String) {
        conversations.attach(id)
        awaitUntil(90_000) {
            val s = state(id)
            val quiet = !s.isLoading && !s.isLoadingOlder && s.items.isNotEmpty() && s.traceStatus.pending == 0
            if (!quiet) return@awaitUntil false
            val items = s.items
            delay(1_000)
            state(id).items == items
        }
    }

    /** Every transcript error the chat publishes from now on: a notice on screen for as long as a frame, too. */
    private fun FaultRig.transcriptErrors(id: String): CopyOnWriteArrayList<String> {
        val seen = CopyOnWriteArrayList<String>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { conversations.state(id).collect { s -> s.transcriptError?.let { seen += it } } }
        return seen
    }

    @Test
    fun `a timeout reading a very large record is retried unseen, and the chat catches up with no notice`() = runBlocking<Unit> {
        bigProject()
        val rig = bigRig(quietRetryDelaysMs = listOf(500L, 1_000L, 2_000L, 4_000L))
        rig.openSettled(project)
        val before = rig.state(project).items.size
        val errors = rig.transcriptErrors(project)
        val prompt = "Next: the fee table for market 300 (#elsewhere)."
        val reply = "Market 300's fee table row is filed."
        turnTakenElsewhere(prompt, reply)

        // The next read of the state meets silence past the read timeout, both attempts of it: a refresh that failed.
        val from = server.requests(Route.RecordState).size
        server.script(Route.RecordState, Fault.Silence(), Fault.Silence())
        rig.conversations.revalidate(project, force = true)
        rig.awaitUntil(60_000) { rig.state(project).items.filterIsInstance<UserMessage>().lastOrNull()?.text == prompt }
        rig.awaitUntil(10_000) { rig.state(project).items.filterIsInstance<AssistantMessage>().lastOrNull()?.markdown == reply }

        val reads = server.requests(Route.RecordState).size - from
        println("   state reads for the refresh: $reads (two timed out); transcript errors published: $errors")
        assertWithMessage("state reads: the two that timed out and the one that answered").that(reads).isAtLeast(3)
        assertWithMessage("a transient timeout on a healthy connection is not the reader's to see").that(errors).isEmpty()
        assertThat(rig.state(project).transcriptError).isNull()
        assertThat(rig.state(project).items.size).isAtLeast(before)
        assertThat(LoadNotices.of(rig.state(project))).isEmpty()
    }

    @Test
    fun `a record that keeps timing out is said after the quiet retries, as the app's copy not refreshing, and clears once it answers`() = runBlocking<Unit> {
        bigProject()
        val quiet = listOf(300L, 600L, 900L)
        val rig = bigRig(quietRetryDelaysMs = quiet)
        rig.openSettled(project)
        val before = rig.state(project).items
        val errors = rig.transcriptErrors(project)

        server.outage(Route.RecordState, Fault.Silence())
        val from = server.requests(Route.RecordState).size
        val started = System.nanoTime()
        rig.conversations.revalidate(project, force = true)
        rig.awaitUntil(90_000) { rig.state(project).transcriptError != null }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        val reads = server.requests(Route.RecordState).size - from
        println("   said after $tookMs ms and $reads state reads: ${rig.state(project).transcriptError}")
        // One refresh for the first failure and one after each quiet pause, every one of them two timed-out attempts.
        assertWithMessage("state reads before the failure was said").that(reads).isAtLeast(2 * (quiet.size + 1))
        assertThat(errors.distinct()).hasSize(1)

        val shown = rig.state(project)
        assertThat(shown.items).isEqualTo(before)
        assertThat(shown.runStatus).isNotEqualTo(RunStatus.ERROR)
        val notice = LoadNotices.of(shown).single()
        assertThat(notice.title).startsWith("Couldn't refresh the transcript: ")
        assertThat(notice.title).contains("took too long")
        assertThat(notice.detail).isEqualTo(TRANSCRIPT_REFRESH_DETAIL)

        // The account answers again: the next quiet read clears the notice, with no Retry tapped.
        server.clear(Route.RecordState)
        rig.awaitUntil(30_000) { rig.state(project).transcriptError == null && !rig.state(project).isLoading }
        assertThat(LoadNotices.of(rig.state(project))).isEmpty()
    }

    @Test
    fun `a stream that said FINISHED with no result, over a record still calling it running, hands the chat to the account's CREATING run`() = runBlocking<Unit> {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 200L
        // The API's keep-alives, as events: the held stream goes on saying something after the run said it ended.
        server.liveRunGenerator = { _, _ -> listOf("heartbeat" to "{}") }
        val agentId = "bc-coordinator"
        val at = now - 60_000L
        val done = "run-done"
        server.runs[done] = RunDto(id = done, agentId = agentId, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Started.")
        server.logs[done] = listOf("assistant" to """{"text":"Started."}""", "result" to """{"runId":"$done","status":"FINISHED","text":"Started.","durationMs":60000}""")
        server.agents[agentId] = AgentDto(id = agentId, name = "Coordinator", status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = done, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Coordinator", status = "FINISHED")
        server.transcripts[agentId] = listOf(V0ConversationMessageDto("$done-u", "user_message", "Start."), V0ConversationMessageDto("$done-a", "assistant_message", "Started."))
        server.composers[agentId] = FaultServer.Composer(agentId, "Coordinator", activityMs = at, project = true)
        server.clock = { now }

        val rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true, terminalGraceMs = 1_000L).also {
            it.now = now
            rigs += it
        }
        val first = server.startTurnElsewhere(agentId, "Dispatch the workers.", "run-first")
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.awaitUntil(20_000) { rig.state(agentId).let { !it.isLoading && it.isStreaming && it.activeRunId == first.id } }

        // The run says it ended, and its `result` never comes; its record goes on calling it running, minutes behind.
        server.appendRunEvents(first.id, listOf(
            "assistant" to """{"text":"Workers dispatched."}""",
            "status" to """{"runId":"${first.id}","status":"FINISHED"}""",
        ))
        // The account's next turn — a worker's report waking the coordinator — is already under way, still CREATING.
        val next = server.startTurnElsewhere(agentId, "Worker report: market 300 filed.", "run-next")
        server.runs[next.id] = next.copy(status = "CREATING")
        server.logs[next.id] = listOf("status" to """{"runId":"${next.id}","status":"CREATING"}""")
        server.touch(agentId)
        rig.agents.refresh()

        rig.awaitUntil(30_000) { rig.state(agentId).activeRunId == next.id }
        val shown = rig.state(agentId)
        println("   followed ${shown.activeRunId} at ${shown.runStatus}; first run's reply shown: ${shown.items.filterIsInstance<AssistantMessage>().map { it.markdown }}")
        assertThat(shown.runStatus?.isActive).isTrue()
        assertThat(shown.items.filterIsInstance<AssistantMessage>().map { it.markdown }).contains("Workers dispatched.")
        assertThat(shown.transcriptError).isNull()
        assertThat(rig.followUps.decide(agentId).busy).isTrue()
    }

    /**
     * Bennett's 0.4.15 coordinator: `live: stream=events:3,status:FINISHED,finished:false,items:0`, the stream held
     * with the API's keep-alive comments and nothing else — no heartbeat events — and the record calling the run
     * running. The grace ran only when another event came, and none did: the chat stayed on the finished run.
     */
    @Test
    fun `a stream that said FINISHED with no items, then only keep-alive comments, hands the chat to the account's next run`() = runBlocking<Unit> {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 200L
        val agentId = "bc-coordinator"
        val at = now - 60_000L
        val done = "run-done"
        server.runs[done] = RunDto(id = done, agentId = agentId, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Started.")
        server.logs[done] = listOf("assistant" to """{"text":"Started."}""", "result" to """{"runId":"$done","status":"FINISHED","text":"Started.","durationMs":60000}""")
        server.agents[agentId] = AgentDto(id = agentId, name = "Coordinator", status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = done, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Coordinator", status = "FINISHED")
        server.transcripts[agentId] = listOf(V0ConversationMessageDto("$done-u", "user_message", "Start."), V0ConversationMessageDto("$done-a", "assistant_message", "Started."))
        server.composers[agentId] = FaultServer.Composer(agentId, "Coordinator", activityMs = at, project = true)
        server.clock = { now }

        val rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true, terminalGraceMs = 1_000L).also {
            it.now = now
            rigs += it
        }
        // A worker's report waking the coordinator: a prompt-only turn, its stream saying nothing but its status.
        val first = server.startTurnElsewhere(agentId, "Worker report: market 200 filed.", "run-first")
        rig.agents.refresh()
        rig.conversations.attach(agentId)
        rig.awaitUntil(20_000) { rig.state(agentId).let { !it.isLoading && it.isStreaming && it.activeRunId == first.id } }

        server.appendRunEvents(first.id, listOf("status" to """{"runId":"${first.id}","status":"FINISHED"}"""))
        val next = server.startTurnElsewhere(agentId, "Worker report: market 300 filed.", "run-next")
        server.runs[next.id] = next.copy(status = "CREATING")
        server.logs[next.id] = listOf("status" to """{"runId":"${next.id}","status":"CREATING"}""")
        server.touch(agentId)
        rig.agents.refresh()

        val said = System.nanoTime()
        rig.awaitUntil(30_000) { rig.state(agentId).activeRunId == next.id }
        println("   handed to ${rig.state(agentId).activeRunId} ${(System.nanoTime() - said) / 1_000_000} ms after the stream said FINISHED; first run's record still ${server.runs.getValue(first.id).status}")
        assertThat(server.runs.getValue(first.id).status).isEqualTo("RUNNING")
        assertThat(rig.state(agentId).runStatus?.isActive).isTrue()
        assertThat(rig.state(agentId).transcriptError).isNull()
    }

    /**
     * Bennett's 0.4.15 dump: `throttle: control 3/3 +3 waiting`, the coordinator's record 7.6 minutes behind its row,
     * `fetched=false loading=true`. A Project's kept-live chats each read their whole conversation state on the
     * control lane, a minute or more apiece on a chat this size, and the chat on screen's refresh waited behind them
     * for as long. With every control permit taken and callers queued behind, the chat on screen still refreshes.
     */
    @Test
    fun `a chat on screen refreshes its record with every control permit taken and callers waiting behind them`() = runBlocking<Unit> {
        bigProject()
        val rig = bigRig(quietRetryDelaysMs = listOf(500L, 1_000L, 2_000L, 4_000L))
        rig.openSettled(project)
        val prompt = "Next: the fee table for market 400 (#saturated)."
        val reply = "Market 400's fee table row is filed."
        turnTakenElsewhere(prompt, reply)

        val throttle = rig.accountRpc.throttle
        val held = CompletableDeferred<Unit>()
        val control = List(6) { rig.scope.async { throttle.call { held.await() } } }
        rig.awaitUntil(5_000) { throttle.describe().startsWith("control 3/3 +3 waiting") }
        val from = server.requests(Route.RecordState).size
        rig.conversations.revalidate(project, force = true)
        rig.awaitUntil(30_000) { rig.state(project).items.filterIsInstance<UserMessage>().lastOrNull()?.text == prompt }
        rig.awaitUntil(10_000) { rig.state(project).items.filterIsInstance<AssistantMessage>().lastOrNull()?.markdown == reply && !rig.state(project).isLoading }
        println("   refreshed with ${throttle.describe()}; state reads ${server.requests(Route.RecordState).size - from}")
        assertThat(throttle.describe()).startsWith("control 3/3 +3 waiting")
        assertThat(rig.conversations.loadDiagnostics(project)!!.fetched).isTrue()
        assertThat(rig.state(project).transcriptError).isNull()
        held.complete(Unit)
        control.awaitAll()
    }

    /**
     * Bennett's 0.4.15 coordinator: `recordProjectMode=false` on a Project's root, its window — turns 6644 to 6704 —
     * all its workers' injected reports, none a prompt sent in Project mode, painted from the disk while the state
     * read never landed. The saved state's word that the chat is a root makes it a coordinator from the first frame.
     */
    @Test
    fun `a Project root whose newest turns are all injected reports reopens from the disk as a coordinator before the account answers`() = runBlocking<Unit> {
        bigProject()
        // Its prompts all its workers' injected reports, none sent in Project mode: only the account's state says root.
        server.records[project] = server.records.getValue(project).map { step ->
            val human = step["humanMessage"] as? JsonObject ?: return@map step
            JsonObject(step + ("humanMessage" to JsonObject(human - "agentMode")))
        }
        server.rootProjects += project
        val disk = folder.newFolder("disk-root")
        val first = FaultRig(server.baseUrl, disk, readTimeoutMs = 2_000L, extended = true, engine = TranscriptEngine.BETA).also { it.now = now; rigs += it }
        first.openSettled(project)
        assertThat(first.state(project).isProjectConversation).isTrue()
        first.close()
        rigs.clear()

        // The next process: nothing the account says about the chat answers — its state, its record, its live
        // stream, each of which carries the state — and the disk is all there is.
        val held = Fault.Held()
        val silent = listOf(Route.RecordState, Route.Record, Route.Live)
        silent.forEach { server.outage(it, held) }
        val second = FaultRig(server.baseUrl, disk, readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA).also { it.now = now; rigs += it }
        second.conversations.attach(project)
        second.awaitUntil(30_000) { second.state(project).items.isNotEmpty() }
        val coordinator = runCatching { second.awaitUntil(10_000) { second.state(project).isProjectConversation } }.isSuccess
        println("   painted from the disk: ${second.state(project).items.size} items, isProjectConversation=$coordinator")
        assertWithMessage("a Project root painted from the disk, as a coordinator").that(coordinator).isTrue()
        assertThat(second.conversations.loadDiagnostics(project)!!.fetched).isFalse()
        held.release()
        silent.forEach { server.clear(it) }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
}
