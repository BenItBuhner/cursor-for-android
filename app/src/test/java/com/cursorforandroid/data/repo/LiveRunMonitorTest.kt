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
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolActivity
import com.cursorforandroid.domain.TrackedRun
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
