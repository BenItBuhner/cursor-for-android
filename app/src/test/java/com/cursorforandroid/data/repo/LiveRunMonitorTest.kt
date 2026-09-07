package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives [LiveRunHub] + [RunMonitor] (and the refactored [ConversationRepository]) against a scriptable backend.
 * Robolectric only because [SessionManager] needs a Context for its stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveRunMonitorTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    // After the fake agents' createdAt, so read markers written at "now" are newer than the row's updatedAt.
    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var prefs: PreferencesStore
    private lateinit var attachments: AttachmentStore
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var monitor: RunMonitor
    private val finished = CopyOnWriteArrayList<TrackedRun>()
    private var finishedJob: Job? = null

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments)
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, scope = scope)
        monitor = RunMonitor(agents, hub, runStartedAt = { _, _ -> 1_000L }, refreshIntervalMs = 600_000, nowProvider = { now })
        finishedJob = scope.launch { monitor.finished.collect { finished += it } }
    }

    @After
    fun tearDown() {
        monitor.stop()
        finishedJob?.cancel()
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun tool(id: String, name: String, status: String, path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    private fun running() = monitor.state.value.running

    @Test
    fun `tracks running agents, shares one stream with the conversation and reports finishes once`() = runBlocking {
        api.addRunningAgent("bc-1", "Update quick action pills interaction and styling", "run-1")
        api.addRunningAgent("bc-2", "Second agent", "run-2")
        agents.refresh()
        monitor.start()
        awaitUntil { monitor.state.value.hasReconciled && running().size == 2 }
        assertThat(running().map { it.title }).containsExactly("Update quick action pills interaction and styling", "Second agent")
        assertThat(running().first().phase).isEqualTo(LivePhase.Starting)

        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", tool("e1", "edit_file", "running", "app/src/Composer.kt"))
        awaitUntil { running().firstOrNull { it.agentId == "bc-1" }?.digest?.activity?.label == "Editing Composer.kt" }
        assertThat(running().first { it.agentId == "bc-1" }.phase).isEqualTo(LivePhase.Running)

        // A conversation screen opens the same agent: it must ride the monitor's stream, not open a second one.
        val conversations = ConversationRepository(session, agents, prefs, hub, attachments)
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ToolActivity } }
        assertThat(streamer.connections.count { it == "run-1" }).isEqualTo(1)
        assertThat(conversations.state("bc-1").value.isStreaming).isTrue()

        now += 185_000
        val git = RunGitDto(listOf(RunGitBranchDto("github.com/acme/app", "cursor/pills-1a2b", "https://github.com/acme/app/pull/7")))
        streamer.emit("run-1", tool("e1", "edit_file", "completed", "app/src/Composer.kt"))
        streamer.emit("run-1", RunStreamEvent.Assistant("Restyled the pills."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Restyled the pills.", 185_000, git))
        streamer.emit("run-1", RunStreamEvent.Done)

        awaitUntil { finished.size == 1 && running().none { it.agentId == "bc-1" } }
        val done = finished.single()
        assertThat(done.status).isEqualTo(RunStatus.FINISHED)
        assertThat(done.phase).isEqualTo(LivePhase.Finished)
        assertThat(done.durationMs).isEqualTo(185_000L)
        assertThat(done.prUrl).isEqualTo("https://github.com/acme/app/pull/7")
        assertThat(done.branch).isEqualTo("cursor/pills-1a2b")
        assertThat(done.digest.filesEdited).isEqualTo(1)
        assertThat(done.summary).isEqualTo("Restyled the pills.")
        assertThat(done.statsLine()).isEqualTo("1 File \u00B7 Worked 3m 5s")

        // The agent row, the conversation and the read marker all reflect the finish.
        val row = agents.agent("bc-1")!!
        assertThat(row.runStatus).isEqualTo(RunStatus.FINISHED)
        assertThat(row.isRunning).isFalse()
        assertThat(row.updatedAtMillis).isEqualTo(now)
        assertThat(row.prUrl).isEqualTo("https://github.com/acme/app/pull/7")
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter }
        assertThat(conversations.state("bc-1").value.isStreaming).isFalse()
        val finishedAt = now
        awaitUntil { prefs.localAgentState.first().readMarkers["bc-1"] == finishedAt }
        // A run the hub followed to its end is replayed from memory, not from a second connection.
        val replayed = hub.replay("bc-1", "run-1")
        assertThat(replayed.hasTrace).isTrue()
        assertThat(replayed.items.filterIsInstance<ToolActivity>().single().calls.single().status).isEqualTo("completed")
        assertThat(streamer.connections.count { it == "run-1" }).isEqualTo(1)

        // The second stream closes without a result: the hub polls the run until it is terminal.
        val pollsBefore = api.getRunCalls
        streamer.emit("run-2", RunStreamEvent.Status("run-2", RunStatus.RUNNING))
        streamer.emit("run-2", RunStreamEvent.Done)
        awaitUntil { api.getRunCalls > pollsBefore }
        assertThat(finished).hasSize(1)
        api.runs["run-2"] = api.runs.getValue("run-2").copy(status = "ERROR", result = "Build failed", durationMs = 9_000)
        awaitUntil { finished.size == 2 }
        val failed = finished.last()
        assertThat(failed.agentId).isEqualTo("bc-2")
        assertThat(failed.status).isEqualTo(RunStatus.ERROR)
        assertThat(failed.summary).isEqualTo("Build failed")
        awaitUntil { monitor.state.value.isIdle }
        // Each finish is reported exactly once even though the reconcile pass also observes the terminal row.
        delay(100)
        assertThat(finished).hasSize(2)
    }

    @Test
    fun `stopping is reflected immediately and reverted when the cancel fails`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        monitor.start()
        awaitUntil { running().size == 1 }
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Assistant("Working"))
        awaitUntil { running().single().phase == LivePhase.Running }

        monitor.markStopping("bc-1")
        assertThat(running().single().phase).isEqualTo(LivePhase.Stopping)
        streamer.emit("run-1", RunStreamEvent.Assistant(" on it"))
        awaitUntil { running().single().summary == "Working on it" }
        assertThat(running().single().phase).isEqualTo(LivePhase.Stopping)

        monitor.clearStopping("bc-1")
        assertThat(running().single().phase).isEqualTo(LivePhase.Running)

        // A cancel that goes through drops the agent from the running set without a "finished" report.
        agents.cancelRun("bc-1", "run-1")
        awaitUntil { running().isEmpty() }
        delay(100)
        assertThat(finished).isEmpty()
    }

    @Test
    fun `tracks at most eight agents like the iOS Live Activity but counts every running one`() = runBlocking {
        repeat(10) { api.addRunningAgent("bc-$it", "Agent $it", "run-$it") }
        agents.refresh()
        monitor.start()
        awaitUntil { monitor.state.value.hasReconciled && running().size == 8 }
        delay(100)
        assertThat(running()).hasSize(8)
        assertThat(streamer.connections.distinct()).hasSize(8)
        // The cap bounds the open streams only: the headline count must not plateau at eight.
        with(monitor.state.value) {
            assertThat(runningCount).isEqualTo(10)
            assertThat(totalRunning).isEqualTo(10)
            assertThat(untrackedCount).isEqualTo(2)
        }

        // A tracked run finishing hands its slot to one of the untracked agents; the count drops by exactly one.
        val finishedId = running().first().agentId
        val finishedRun = running().first().runId
        streamer.emit(finishedRun, RunStreamEvent.Result(finishedRun, RunStatus.FINISHED, "Done", 1_000, null))
        streamer.emit(finishedRun, RunStreamEvent.Done)
        awaitUntil { monitor.state.value.runningCount == 9 && running().size == 8 && running().none { it.agentId == finishedId } }
        assertThat(monitor.state.value.untrackedCount).isEqualTo(1)
        assertThat(streamer.connections.distinct()).hasSize(9)
    }

    /** The retained event log of a finished run, as the server would replay it on a fresh connection. */
    private suspend fun retainStream(runId: String, reply: String, git: RunGitDto? = null) {
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Thinking("Reading the code first."))
        streamer.emit(runId, tool("$runId-c1", "read_file", "running", "app/src/Composer.kt"))
        streamer.emit(runId, tool("$runId-c1", "read_file", "completed", "app/src/Composer.kt"))
        streamer.emit(runId, tool("$runId-c2", "edit_file", "completed", "app/src/Composer.kt"))
        streamer.emit(runId, RunStreamEvent.Assistant(reply))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 42_000, git))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    private suspend fun expireStream(runId: String) {
        streamer.emit(runId, RunStreamEvent.Error("stream_expired", "This run's live stream has expired."))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    @Test
    fun `replay rebuilds a finished run from its retained stream and leaves the agent row alone`() = runBlocking {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Add a README", "Added it."))
        agents.refresh()
        val rowBefore = agents.agent("bc-1")!!
        retainStream("run-1", "Added it.", RunGitDto(listOf(RunGitBranchDto("github.com/acme/app", "cursor/readme-1a2b", null))))

        val snapshot = hub.replay("bc-1", "run-1")
        assertThat(snapshot.hasTrace).isTrue()
        assertThat(snapshot.expired).isFalse()
        assertThat(snapshot.items.map { it::class.simpleName }).containsExactly("ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        // Replayed events arrive in a burst, so no thinking duration is invented for them.
        assertThat(snapshot.items.filterIsInstance<ThinkingBlock>().single().durationSeconds).isNull()
        assertThat(snapshot.items.filterIsInstance<ToolActivity>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat((snapshot.items.last() as RunFooter).branches.single().branch).isEqualTo("cursor/readme-1a2b")
        // No polling, no patch: the row still says what the list said, not "updated just now".
        assertThat(api.getRunCalls).isEqualTo(0)
        assertThat(agents.agent("bc-1")).isEqualTo(rowBefore)

        // The second look is answered from memory.
        val again = hub.replay("bc-1", "run-1")
        assertThat(again.items).isEqualTo(snapshot.items)
        assertThat(streamer.connections).containsExactly("run-1")
        assertThat(hub.current("bc-1", "run-1")?.finished).isTrue()
    }

    @Test
    fun `a replay through an entry once followed live reads history too and leaves the agent row alone`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        // A screen follows the run for a moment and leaves; the hub lets go of the stream after its grace period.
        val follower = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { streamer.connections.size == 1 }
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Reading the code first."))
        awaitUntil { hub.current("bc-1", "run-1")?.eventCount == 2 }
        follower.cancel()
        delay(200)

        // The run finishes unobserved; its retained log is complete. The row is whatever the last list fetch said.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Done.", durationMs = 42_000)
        val rowBefore = agents.agent("bc-1")!!
        streamer.emit("run-1", RunStreamEvent.Assistant("Done."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", 42_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)

        val snapshot = hub.replay("bc-1", "run-1")
        assertThat(snapshot.hasTrace).isTrue()
        assertThat(snapshot.items.map { it::class.simpleName }).containsExactly("ThinkingBlock", "AssistantMessage", "RunFooter").inOrder()
        assertThat(streamer.connections).containsExactly("run-1", "run-1").inOrder()
        // Reading history is not news about the agent: no poll, no patch, no "updated just now".
        assertThat(api.getRunCalls).isEqualTo(0)
        assertThat(agents.agent("bc-1")).isEqualTo(rowBefore)
    }

    @Test
    fun `replay reports an expired log instead of polling around it`() = runBlocking {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Add a README", "Added it."))
        agents.refresh()
        expireStream("run-1")

        val snapshot = hub.replay("bc-1", "run-1")
        assertThat(snapshot.expired).isTrue()
        assertThat(snapshot.finished).isFalse()
        assertThat(snapshot.hasTrace).isFalse()
        assertThat(snapshot.items).isEmpty()
        delay(150)
        assertThat(api.getRunCalls).isEqualTo(0)

        // Expired stays expired: nobody reconnects for it.
        assertThat(hub.replay("bc-1", "run-1").expired).isTrue()
        assertThat(streamer.connections).containsExactly("run-1").inOrder()
    }

    @Test
    fun `conversation replays finished runs, keeps text past the retention cutoff and streams follow-ups`() = runBlocking {
        api.addFinishedAgent(
            "bc-1", "Agent",
            Triple("run-1", "Prompt 1", "Reply 1"),
            Triple("run-2", "Prompt 2", "Reply 2"),
            Triple("run-3", "Prompt 3", "Reply 3"),
            Triple("run-4", "Prompt 4", "Reply 4"),
            Triple("run-5", "Prompt 5", "Reply 5"),
        )
        agents.refresh()
        val rowBefore = agents.agent("bc-1")!!
        // Only the newest run's log is still retained; the three before it have expired. The oldest would answer
        // "expired" too, but the conversation must never ask: an expired newer run already settles that. (run-4 is
        // always asked — nothing newer than it has expired — while run-3 and run-2 may be skipped, depending on how
        // quickly run-4 answers.)
        retainStream("run-5", "Reply 5")
        listOf("run-4", "run-3", "run-2", "run-1").forEach { expireStream(it) }

        val conversations = ConversationRepository(session, agents, prefs, hub, attachments)
        conversations.attach("bc-1")
        awaitUntil { conversations.state("bc-1").value.items.any { it is ToolActivity } }
        awaitUntil { "run-4" in streamer.connections }
        delay(300)

        val state = conversations.state("bc-1").value
        assertThat(state.isLoading).isFalse()
        assertThat(state.isStreaming).isFalse()
        assertThat(state.runStatus).isEqualTo(RunStatus.FINISHED)
        val types = state.items.map { it::class.simpleName }
        // Four text-only turns from the transcript, then the replayed trace of run-5 in place of its reply.
        assertThat(types.take(16)).isEqualTo(List(4) { listOf("DateHeader", "UserMessage", "AssistantMessage", "RunFooter") }.flatten())
        assertThat(types.drop(16)).containsExactly("DateHeader", "UserMessage", "ThinkingBlock", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat((state.items[17] as UserMessage).text).isEqualTo("Prompt 5")
        assertThat((state.items[20] as AssistantMessage).markdown).isEqualTo("Reply 5")
        assertThat((state.items[21] as RunFooter).durationMs).isEqualTo(42_000L)
        assertThat((state.items[2] as AssistantMessage).id).isEqualTo("run-1-a")
        assertThat(streamer.connections.count { it == "run-5" }).isEqualTo(1)
        assertThat(streamer.connections.count { it == "run-4" }).isEqualTo(1)
        assertThat(streamer.connections).doesNotContain("run-1")
        // Reading history changed nothing about the agent: the detail load is the only thing that touched the row.
        assertThat(agents.agent("bc-1")!!.updatedAtMillis).isEqualTo(rowBefore.updatedAtMillis)

        // A follow-up shows its prompt at once and streams the new run in place, trace included.
        val result = conversations.sendFollowUp("bc-1", "Prompt 6")
        assertThat(result.isSuccess).isTrue()
        awaitUntil { conversations.state("bc-1").value.isStreaming }
        val pending = conversations.state("bc-1").value
        assertThat(pending.items.map { it::class.simpleName }.takeLast(2)).containsExactly("DateHeader", "UserMessage").inOrder()
        assertThat((pending.items.last() as UserMessage).text).isEqualTo("Prompt 6")
        val runId = pending.activeRunId!!
        assertThat(runId).startsWith("run-followup-")
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, tool("f1", "edit_file", "running", "app/src/Sidebar.kt"))
        awaitUntil { conversations.state("bc-1").value.items.filterIsInstance<ToolActivity>().any { it.isRunning } }
        streamer.emit(runId, tool("f1", "edit_file", "completed", "app/src/Sidebar.kt"))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply 6"))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply 6", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter && !conversations.state("bc-1").value.isStreaming }
        val done = conversations.state("bc-1").value
        assertThat(done.items.map { it::class.simpleName }.takeLast(5)).containsExactly("DateHeader", "UserMessage", "ToolActivity", "AssistantMessage", "RunFooter").inOrder()
        assertThat((done.items.last() as RunFooter).runId).isEqualTo(runId)
        // Reloading rebuilds history from the server; every trace survives without a single reconnection.
        val connectionsBefore = streamer.connections.size
        conversations.reload("bc-1")
        delay(400)
        val reloaded = conversations.state("bc-1").value
        assertThat(reloaded.isLoading).isFalse()
        assertThat(reloaded.items.map { it::class.simpleName }).isEqualTo(done.items.map { it::class.simpleName })
        assertThat(streamer.connections.size).isEqualTo(connectionsBefore)
    }

    @Test
    fun `a follow-up on an agent without a transcript still lands after its history`() = runBlocking {
        api.addFinishedAgent("bc-1", "Agent", Triple("run-1", "Prompt 1", "Reply 1"))
        api.transcripts.remove("bc-1")
        agents.refresh()
        expireStream("run-1")
        val conversations = ConversationRepository(session, agents, prefs, hub, attachments)
        conversations.attach("bc-1")
        awaitUntil { !conversations.state("bc-1").value.isLoading && "run-1" in streamer.connections }
        delay(100)
        // Without a transcript the run is shown from its record: a header, its result and the footer.
        assertThat(conversations.state("bc-1").value.items.map { it.id }).containsExactly("hdr-run-1", "res-run-1", "run-run-1").inOrder()

        assertThat(conversations.sendFollowUp("bc-1", "Prompt 2").isSuccess).isTrue()
        val runId = conversations.state("bc-1").value.activeRunId!!
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, tool("f1", "read_file", "completed", "README.md"))
        streamer.emit(runId, RunStreamEvent.Assistant("Reply 2"))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply 2", 5_000, null))
        streamer.emit(runId, RunStreamEvent.Done)
        awaitUntil { conversations.state("bc-1").value.items.lastOrNull() is RunFooter && !conversations.state("bc-1").value.isStreaming }
        val items = conversations.state("bc-1").value.items
        assertThat(items.map { it::class.simpleName }).containsExactly(
            "DateHeader", "AssistantMessage", "RunFooter",
            "DateHeader", "UserMessage", "ToolActivity", "AssistantMessage", "RunFooter",
        ).inOrder()
        assertThat((items[4] as UserMessage).text).isEqualTo("Prompt 2")
        assertThat((items[7] as RunFooter).runId).isEqualTo(runId)
    }

    @Test
    fun `hub releases the stream after the last subscriber leaves and replays on resubscribe`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        val first = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { streamer.connections.size == 1 }
        streamer.emit("run-1", RunStreamEvent.Assistant("Hello"))
        awaitUntil { hub.current("bc-1", "run-1")?.items?.isNotEmpty() == true }

        val second = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        delay(100)
        assertThat(streamer.connections).hasSize(1)

        first.cancel()
        second.cancel()
        delay(200)
        val third = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { streamer.connections.size == 2 }
        third.cancel()
    }
}
