package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's live chats going dead after a steer or a queued follow-up: the agent ran on, on the server, and the screen
 * stopped where it was. Over HTTP/2 the run's stream is held open and its keep-alives keep coming whether or not the
 * run's events still reach the connection; a connection the server's side has let go of (the turn handed a steered
 * message, a queued one taken) said nothing more, and nothing in the app noticed — the keep-alives counted as a stream
 * alive. The run is active by its record and no event has come for a while: the stream is taken up again from the
 * last event it applied, quietly, and the turn goes on drawing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StreamAfterSteerTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val now = 1_800_000_000_000L
    private val agentId = "bc-steered-live"
    private val run = "run-steered-live"

    @After
    fun tearDown() {
        rig?.let { it.steering.detach(agentId); it.conversations.detach(agentId); it.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun assistant(text: String) = "assistant" to """{"text":${quote(text)}}"""
    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** One chat, its one turn under way and streamed live over HTTP/2, the account calling it running. */
    private fun chat() {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 200L
        server.queueLagMs = 500L
        server.runs[run] = RunDto(id = run, agentId = agentId, status = "RUNNING", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L))
        server.logs[run] = listOf("status" to """{"runId":"$run","status":"RUNNING"}""", assistant("Reading the repository. "))
        server.agents[agentId] = AgentDto(id = agentId, name = "Steered", status = "ACTIVE", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L), latestRunId = run, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Steered", status = "RUNNING")
        server.transcripts[agentId] = listOf(V0ConversationMessageDto("$run-u", "user_message", "Fix the flaky test"))
        server.composers[agentId] = FaultServer.Composer(agentId, "Steered", activityMs = now - 1_000L, running = true)
    }

    private fun openRig(): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE, queuePollMs = 1_000L, http2 = true, stallTimeoutMs = STALL_MS).also {
            it.now = now
            rig = it
        }

    private val state get() = rig!!.conversations.state(agentId).value

    /** Everything the chat shows the agent saying, in order. */
    private fun said(): String = state.items.filterIsInstance<AssistantMessage>().joinToString("") { it.markdown }

    private fun explain(label: String) {
        val r = rig ?: return
        val s = state
        val d = r.conversations.loadDiagnostics(agentId)
        println("== $label: run=${s.runStatus} streaming=${s.isStreaming} reconnecting=${s.isReconnecting} active=${s.activeRunId} said=\"${said()}\"")
        println("   hub=${r.hub.current(agentId, s.activeRunId ?: run)?.let { "events=${it.eventCount} finished=${it.finished} reconnecting=${it.reconnecting}" }} ${r.hub.stats()}")
        println("   live=${d?.liveRunId} following=${d?.following} stream=${d?.liveStream} open=${server.liveRunOpen.get()}")
        println("   streams=${server.requests(Route.Stream).map { "${it.path.substringAfter("/runs/").substringBefore('/')}@${it.lastEventId}" }}")
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { explain("TIMEOUT waiting for $label"); throw t }
    }

    private suspend fun FaultRig.open() {
        agents.refresh()
        awaitUntilOr(15_000, "the account's word") { agents.runningScan.value.accountWord[agentId]?.running == true }
        conversations.attach(agentId)
        steering.attach(agentId)
        awaitUntilOr(30_000, "the turn followed") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == run } && said().contains("Reading the repository.") }
        awaitUntilOr(15_000, "the queue read") { steering.state(agentId).value.isQueueAvailable }
    }

    /** What the composer does mid-turn in Extended mode (see `ConversationViewModel.queueOnAccount`). */
    private suspend fun FaultRig.queueOnAccount(text: String): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.stageFollowUp(agentId, text, show = false)
        conversations.sendStagedVia(agentId, staged, followupId = followupId) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, followupId = followupId)).getOrThrow()
        }.getOrThrow()
        return followupId
    }

    /** A waiting card's up arrow mid-turn: the message steered into the turn under way through the account. */
    private suspend fun FaultRig.steerFromCard(text: String) {
        val id = followUps.enqueue(agentId, text).id
        val steered = followUps.steerNow(agentId, id)
        assertWithMessage("the steer: ${steered.exceptionOrNull()}").that(steered.isSuccess).isTrue()
    }

    /** The agent says [words] on the server; the chat must draw them while the turn is still under way. */
    private suspend fun FaultRig.agentSays(words: String, label: String, timeoutMs: Long = 15_000) {
        server.appendRunEvents(run, listOf(assistant(words)))
        awaitUntilOr(timeoutMs, label) { said().contains(words.trim()) }
        assertWithMessage("$label: the turn still streams").that(state.isStreaming).isTrue()
    }

    /** The turn ends on the server; the chat settles on its end — not stuck streaming, and every word drawn once. */
    private suspend fun FaultRig.turnEnds(vararg words: String) {
        server.endTurn(agentId)
        // The list's next poll, which brings the account's word that the chat is idle.
        agents.refresh()
        awaitUntilOr(20_000, "the turn's end") { !state.isStreaming && state.runStatus?.isActive != true }
        val text = said()
        words.forEach { w -> assertWithMessage("\"$w\" drawn once in \"$text\"").that(text.split(w.trim()).size - 1).isEqualTo(1) }
    }

    @Test
    fun `steered mid-stream - the turn keeps drawing what the agent says after the steer`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        rig.steerFromCard("Use the newer API instead")
        rig.agentSays("Switching to the newer API. ", "words after the steer")
        rig.agentSays("Tests pass now. ", "more words after the steer")
        rig.turnEnds("Reading the repository.", "Switching to the newer API.", "Tests pass now.")
    }

    @Test
    fun `steered mid-stream with the server letting go of the open connection - the stream is taken up again from its last event`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        val streamsBefore = server.requests(Route.Stream).size
        val frames = CopyOnWriteArrayList<ConversationState>()
        val recorder = rig.scope.launch { rig.conversations.state(agentId).collect { frames += it } }
        rig.steerFromCard("Use the newer API instead")
        // The connection stays up on its keep-alives, but the run's events no longer reach it.
        server.strandLiveStreams(run)
        rig.agentSays("Switching to the newer API. ", "words after the steer, on a stranded connection")
        recorder.cancel()
        // Taken up again from the last event it had, not replayed from the first.
        val resumed = server.requests(Route.Stream).drop(streamsBefore)
        assertWithMessage("streams since the steer: $resumed").that(resumed.map { it.lastEventId }).contains("$run#2")
        assertWithMessage("streams since the steer: $resumed").that(resumed.map { it.lastEventId }).doesNotContain(null)
        // Quietly: the turn never read as reconnecting, nor stopped streaming, while the stall was got over.
        assertThat(frames.none { it.isReconnecting }).isTrue()
        assertThat(frames.all { it.isStreaming }).isTrue()
        assertThat(state.error).isNull()
        rig.agentSays("Tests pass now. ", "more words after the steer")
        rig.turnEnds("Reading the repository.", "Switching to the newer API.", "Tests pass now.")
    }

    @Test
    fun `queued mid-stream - the turn keeps drawing, and the run the queued message starts is followed`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        rig.queueOnAccount("Then update the changelog")
        rig.awaitUntilOr(10_000, "the account to hold it") { server.pending[agentId].orEmpty().any { it.consumedAtMs == null } }
        server.strandLiveStreams(run)
        rig.agentSays("Still on the flaky test. ", "words after the queued follow-up")
        server.endTurn(agentId)
        val next = server.deliverNext(agentId)!!
        server.appendRunEvents(next.id, listOf("status" to """{"runId":"${next.id}","status":"RUNNING"}""", assistant("Updating the changelog. ")))
        rig.awaitUntilOr(30_000, "the queued message's run followed") { state.activeRunId == next.id && state.isStreaming && said().contains("Updating the changelog.") }
        server.appendRunEvents(next.id, listOf(assistant("Changelog updated. ")))
        rig.awaitUntilOr(15_000, "words of the queued message's run") { said().contains("Changelog updated.") }
    }

    @Test
    fun `steered while a queued message waits - the turn keeps drawing, then the queued message's run is followed`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        rig.queueOnAccount("Then update the changelog")
        rig.awaitUntilOr(10_000, "the account to hold it") { server.pending[agentId].orEmpty().any { it.consumedAtMs == null } }
        rig.steerFromCard("Use the newer API instead")
        server.strandLiveStreams(run)
        rig.agentSays("Switching to the newer API. ", "words after the steer, a message still queued")
        rig.agentSays("Tests pass now. ", "more words after the steer, a message still queued")
        server.endTurn(agentId)
        val next = server.deliverNext(agentId)!!
        server.appendRunEvents(next.id, listOf("status" to """{"runId":"${next.id}","status":"RUNNING"}""", assistant("Updating the changelog. ")))
        rig.awaitUntilOr(30_000, "the queued message's run followed") { state.activeRunId == next.id && state.isStreaming && said().contains("Updating the changelog.") }
    }

    @Test
    fun `network blips around a steer - the stream comes back each time and nothing is drawn twice`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        // The connection drops mid-turn and the next two attempts meet a dead network.
        server.resetNextConnections(2)
        server.dropLiveStreams(run)
        rig.agentSays("First blip survived. ", "words after the first blip")
        rig.steerFromCard("Use the newer API instead")
        server.resetNextConnections(1)
        server.dropLiveStreams(run)
        rig.agentSays("Switching to the newer API. ", "words after the steer and the second blip")
        // A blip that leaves a stranded connection behind it rather than a dropped one.
        server.strandLiveStreams(run)
        rig.agentSays("Tests pass now. ", "words after a stranded connection")
        rig.turnEnds("Reading the repository.", "First blip survived.", "Switching to the newer API.", "Tests pass now.")
    }

    @Test
    fun `a steer the server answers by ending the turn and starting the next - the next run is followed and drawn`() = runBlocking<Unit> {
        chat()
        val rig = openRig()
        rig.open()
        rig.steering.steer(agentId, "Stop and use the newer API").getOrThrow()
        // The server ends the turn the steer went into and carries on with a run of its own; the old connection is
        // left stranded rather than closed.
        server.strandLiveStreams(run)
        val next = "run-after-steer"
        val at = iso(now + 1_000L)
        server.runs[run] = server.runs.getValue(run).copy(status = "FINISHED", updatedAt = at, durationMs = 61_000L)
        server.appendRunEvents(run, listOf("result" to """{"runId":"$run","status":"FINISHED","text":"","durationMs":61000}"""))
        server.logs[next] = listOf("status" to """{"runId":"$next","status":"RUNNING"}""", assistant("Using the newer API now. "))
        server.runs[next] = RunDto(id = next, agentId = agentId, status = "RUNNING", createdAt = at, updatedAt = at)
        server.agents[agentId] = server.agents.getValue(agentId).copy(latestRunId = next, updatedAt = at)
        rig.awaitUntilOr(30_000, "the next run followed") { state.activeRunId == next && state.isStreaming && said().contains("Using the newer API now.") }
        server.appendRunEvents(next, listOf(assistant("Done switching. ")))
        rig.awaitUntilOr(15_000, "words of the next run") { said().contains("Done switching.") }
    }

    private companion object {
        /** The stall window here: production's 30 s would make each case half a minute long. */
        const val STALL_MS = 1_500L
    }
}
