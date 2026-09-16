package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RecordState
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.TurnTiming
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one rule about a turn's end: the app never says it. A run record that reads cancelled while the account runs
 * on is stale, not the turn's end; a stream that will not open is a connection to keep trying, not a finish; a
 * duration the app timed itself is not the run's. Only the server — a record the account agrees with, the account's
 * own word, a stream's terminal event — ends a turn here. And a chat on one of the user's machines, whose run list
 * and stream never answer, still shows the account's running state and streams from the record it has.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveStatusTruthTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The account's record of a chat whose newest turn grows step by step, as the machine works on it. */
    private class GrowingRecord : ConversationRecordApi {
        private val steps = java.util.concurrent.CopyOnWriteArrayList<HeadlessStep>()
        @Volatile var turnCount = 0
        val calls = java.util.concurrent.atomic.AtomicInteger()

        fun prompt(text: String) { steps += HeadlessStep(userMessage = text); turnCount++ }
        fun read(callId: String, path: String, content: String) {
            steps += HeadlessStep(toolCall = HeadlessToolCall(callId, "read_file", buildJsonObject { put("path", JsonPrimitive(path)) }))
            steps += HeadlessStep(toolResult = HeadlessToolResult(callId, buildJsonObject { put("content", JsonPrimitive(content)) }))
        }
        fun text(t: String) { steps += HeadlessStep(text = t) }

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            calls.incrementAndGet()
            val all = steps.toList()
            return HeadlessPage(all.drop(startIndex).take(limit), startIndex, all.size)
        }

        override suspend fun state(agentId: String): RecordState =
            RecordState(turnCount, List(turnCount) { TurnTiming(null, null) }, pendingToolCalls = 1, isRootProject = false, numPriorInteractionUpdates = steps.size.toLong(), rewindEpoch = 0L)
    }

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli()
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache
    private val machineBusy = AtomicBoolean(false)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"))
        attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        cache = ConversationCache(disk.child("conversations"))
        traces = TraceCache(disk.child("traces"))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(hub: LiveRunHub, record: ConversationRecordApi? = null, capabilities: Capabilities = Capabilities.DOCUMENTED) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { capabilities },
        machineBusy = { agent: Agent -> machineBusy.get().takeIf { agent.id == agentId } },
    )

    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private val agentId = "bc-machine"

    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()

    private fun ConversationState.footers() = items.filterIsInstance<RunFooter>()
    private fun ConversationState.notices() = items.filterIsInstance<NoticeCard>()
    private fun ConversationState.calls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }

    /**
     * Bennett's Remote Control chat: `env.type: machine`, the account running it, and nothing else answering — no run
     * in the list, no run record, no stream. The transcript shows the account's running state and the turn's steps
     * as the record grows, never a cancelled or finished state the app made up; when a run finally appears it is
     * followed.
     */
    @Test
    fun `a machine agent with no run record and no stream shows the account's running state and streams from the record`() = runBlocking<Unit> {
        api.agents[agentId] = com.cursorforandroid.data.api.dto.AgentDto(id = agentId, name = "Codex-Poly-Bot Scaling", status = "ACTIVE", createdAt = iso(now - 3_600_000), updatedAt = iso(now), latestRunId = null, env = AgentEnvDto(type = "machine", name = "bennett"))
        api.v0[agentId] = com.cursorforandroid.data.api.dto.V0AgentDto(id = agentId, name = "Codex-Poly-Bot Scaling", status = "RUNNING")
        api.transcripts[agentId] = listOf(com.cursorforandroid.data.api.dto.V0ConversationMessageDto("m1", "user_message", "Scale the recorders."))
        machineBusy.set(true)
        agents.refresh()
        agents.applyAccountSnapshots(listOf(ComposerSnapshot(agentId, name = "Codex-Poly-Bot Scaling", archived = false, status = RunStatus.RUNNING)))
        val record = GrowingRecord().apply { prompt("Scale the recorders."); read("c1", "recorders/run.py", "def run(): ...") }

        val conversations = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        conversations.attach(agentId)
        awaitUntil { !conversations.state(agentId).value.isLoading && conversations.state(agentId).value.items.isNotEmpty() }
        awaitUntil { conversations.state(agentId).value.runStatus == RunStatus.RUNNING }
        val opened = conversations.state(agentId).value
        assertThat(opened.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly("Scale the recorders.")
        assertThat(opened.calls().map { it.callId }).containsExactly("c1")
        assertThat(opened.footers()).isEmpty()
        assertThat(opened.notices()).isEmpty()
        assertThat(streamer.connections).isEmpty()

        // The machine works on: the record grows, and so does the transcript, from the record alone.
        record.read("c2", "recorders/offload.py", "def offload(): ...")
        record.text("Freed 1.5 GB and revived all six recorders.")
        awaitUntil(30_000) { conversations.state(agentId).value.calls().map { it.callId } == listOf("c1", "c2") }
        val grown = conversations.state(agentId).value
        assertThat(grown.runStatus).isEqualTo(RunStatus.RUNNING)
        assertThat(grown.footers()).isEmpty()
        assertThat(grown.notices()).isEmpty()

        // A run appears in the agent's record at last: it is followed, and its stream takes over.
        val runId = "run-late"
        api.runs[runId] = RunDto(id = runId, agentId = agentId, status = "RUNNING", createdAt = iso(now), updatedAt = iso(now))
        api.agents[agentId] = api.agents.getValue(agentId).copy(latestRunId = runId)
        awaitUntil(30_000) { conversations.state(agentId).value.isStreaming && conversations.state(agentId).value.activeRunId == runId }
        awaitUntil { runId in streamer.connections }
        assertThat(streamer.connections.distinct()).containsExactly(runId)
        val diagnostics = conversations.loadDiagnostics(agentId)!!
        assertThat(diagnostics.status!!.shown).isEqualTo("RUNNING")
    }

    /**
     * The stream opens for a run, then the record says the run is cancelled while the account runs on with newer
     * activity: the record is stale and does not end the turn — no "Run cancelled", no footer, the chat still
     * running and its stream still followed. Once the account agrees the run is over, the record's word stands, and
     * the footer carries no duration the app timed.
     */
    @Test
    fun `a cancelled run record older than the account's activity does not end the turn, and no duration is invented`() = runBlocking<Unit> {
        api.addRunningAgent(agentId, "Codex-Poly-Bot Scaling", "run-1", createdAt = iso(now - 120_000))
        api.transcripts[agentId] = listOf(com.cursorforandroid.data.api.dto.V0ConversationMessageDto("m1", "user_message", "Scale the recorders."))
        api.agents[agentId] = api.agents.getValue(agentId).copy(updatedAt = iso(now))
        agents.refresh()
        val conversations = repository(hub())
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.isStreaming && "run-1" in streamer.connections }
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.ToolCall(SseToolCallDto("run-1-c1", "read_file", "completed", buildJsonObject { put("path", JsonPrimitive("a.py")) })))
        awaitUntil { conversations.state(agentId).value.calls().any { it.callId == "run-1-c1" } }

        // The connection drops; the record read next says cancelled, written a minute before the row's last activity.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "CANCELLED", updatedAt = iso(now - 60_000))
        streamer.emit("run-1", RunStreamEvent.Error("stream_unavailable", "The connection dropped.", resumeFrom = null))
        streamer.emit("run-1", RunStreamEvent.Done)
        delay(600)
        val kept = conversations.state(agentId).value
        assertThat(kept.notices()).isEmpty()
        assertThat(kept.footers()).isEmpty()
        assertThat(kept.runStatus?.isActive).isTrue()
        assertThat(kept.isStreaming).isTrue()
        assertThat(streamer.connections.count { it == "run-1" }).isAtLeast(2)

        // The account agrees the run is over: the row goes idle. The record's word now stands — without a duration.
        api.v0[agentId] = api.v0.getValue(agentId).copy(status = "CANCELLED")
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "IDLE")
        agents.refresh()
        agents.patch(agentId) { it.copy(runStatus = RunStatus.CANCELLED, lifecycle = com.cursorforandroid.domain.AgentLifecycle.IDLE) }
        awaitUntil(30_000) { conversations.state(agentId).value.footers().isNotEmpty() }
        val ended = conversations.state(agentId).value
        assertThat(ended.footers().single().status).isEqualTo(RunStatus.CANCELLED)
        assertThat(ended.footers().single().durationMs).isNull()
        assertThat(ended.isStreaming).isFalse()
    }

    /**
     * A steer's cancel: the server ends run A for the follow-up and starts run B. The row still names A for a moment
     * and runs on. The turn A is shown as the server ended it, the chat stays running rather than dead, and B is
     * found by re-reading the agent's record and followed.
     */
    @Test
    fun `a run the server cancelled while the account runs on is followed by the next run, not by a dead chat`() = runBlocking<Unit> {
        api.addRunningAgent(agentId, "Codex-Poly-Bot Scaling", "run-a", createdAt = iso(now - 120_000))
        api.transcripts[agentId] = listOf(com.cursorforandroid.data.api.dto.V0ConversationMessageDto("m1", "user_message", "Get us there."))
        api.agents[agentId] = api.agents.getValue(agentId).copy(updatedAt = iso(now - 90_000))
        agents.refresh()
        // Extended mode: the account list's running set says the chat runs, whatever the run records say.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot(agentId, name = "Codex-Poly-Bot Scaling", archived = false, status = RunStatus.RUNNING)))
        val conversations = repository(hub())
        conversations.attach(agentId)
        awaitUntil { conversations.state(agentId).value.isStreaming && "run-a" in streamer.connections }

        // The server cancels A (a newer record than the row's activity) and, a beat later, names B as the latest run.
        api.runs["run-a"] = api.runs.getValue("run-a").copy(status = "CANCELLED", updatedAt = iso(now), durationMs = 30_000)
        streamer.emit("run-a", RunStreamEvent.Result("run-a", RunStatus.CANCELLED, null, 30_000, null))
        streamer.emit("run-a", RunStreamEvent.Done)
        awaitUntil { conversations.state(agentId).value.footers().any { it.runId == "run-a" } }
        val cancelled = conversations.state(agentId).value
        assertThat(cancelled.footers().single().status).isEqualTo(RunStatus.CANCELLED)
        assertThat(cancelled.footers().single().durationMs).isEqualTo(30_000L)
        // Not dead: the account still runs the chat.
        assertThat(cancelled.runStatus).isEqualTo(RunStatus.RUNNING)

        api.runs["run-b"] = RunDto(id = "run-b", agentId = agentId, status = "RUNNING", createdAt = iso(now + 1_000), updatedAt = iso(now + 1_000))
        api.agents[agentId] = api.agents.getValue(agentId).copy(latestRunId = "run-b", updatedAt = iso(now + 1_000))
        awaitUntil(30_000) { conversations.state(agentId).value.isStreaming && conversations.state(agentId).value.activeRunId == "run-b" }
        streamer.emit("run-b", RunStreamEvent.Status("run-b", RunStatus.RUNNING))
        streamer.emit("run-b", RunStreamEvent.Assistant("On it."))
        awaitUntil { conversations.state(agentId).value.items.filterIsInstance<com.cursorforandroid.domain.AssistantMessage>().any { it.markdown.contains("On it.") } }
    }
}
