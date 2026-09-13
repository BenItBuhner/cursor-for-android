package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.HeadlessPage
import com.cursorforandroid.data.api.HeadlessStep
import com.cursorforandroid.data.api.HeadlessToolCall
import com.cursorforandroid.data.api.HeadlessToolResult
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolPayload
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
import java.io.File
import java.time.Instant

/**
 * Bennett's bug: a long-running chat opens with its text but never its tool calls, above all after a restart. The
 * scenario is synthetic but proportioned like the real thing — dozens of runs, hundreds of tool calls, each carrying
 * the payload the stream delivers (a file's text, a diff) — followed by a restart after which the server's logs have
 * expired, so the disk is the only place the tool calls can come from.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptRehydrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_800_000_000_000L
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var attachments: AttachmentStore
    private lateinit var agents: AgentRepository
    private lateinit var cache: ConversationCache
    private lateinit var traces: TraceCache
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, streamer, isDemo = true))
        cacheRoot = folder.newFolder("cache")
        val disk = JsonDiskCache(cacheRoot, dispatcher = Dispatchers.Unconfined)
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

    /** A hub is per process: a restart starts with an empty one, and everything it had followed is gone with it. */
    private fun hub() = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)

    private fun repository(hub: LiveRunHub, record: ConversationRecordApi? = null, capabilities: Capabilities = Capabilities.DOCUMENTED) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
        record = record, capabilities = { capabilities },
    )

    /** The account's record of the same chat: per turn its prompt, one read with its result, and the reply. */
    private class FakeRecord(runs: Int, callsPerRun: Int) : ConversationRecordApi {
        val steps: List<HeadlessStep> = (1..runs).flatMap { t ->
            listOf(HeadlessStep(userMessage = "Prompt $t")) +
                (1..callsPerRun).flatMap { n ->
                    listOf(
                        HeadlessStep(toolCall = HeadlessToolCall("rec-$t-$n", "read_file", buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) })),
                        HeadlessStep(toolResult = HeadlessToolResult("rec-$t-$n", buildJsonObject { put("content", JsonPrimitive("recorded $t/$n")) })),
                    )
                } +
                listOf(HeadlessStep(text = "Reply $t"))
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
            calls.incrementAndGet()
            return HeadlessPage(steps.drop(startIndex).take(limit), startIndex, steps.size)
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun readCall(runId: String, n: Int, contentChars: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject {
                put(
                    "success",
                    buildJsonObject {
                        put("content", JsonPrimitive("x".repeat(contentChars)))
                        put("totalLines", JsonPrimitive(400))
                        put("path", JsonPrimitive("app/src/File$n.kt"))
                    },
                )
            },
        ),
    )

    /**
     * [runs] finished runs on one agent, oldest first, each with [callsPerRun] file reads whose payloads are
     * [contentChars] long, each logged whole on the fake server so the first open replays every one of them.
     */
    private suspend fun seedLongConversation(agentId: String, runs: Int, callsPerRun: Int, contentChars: Int) {
        val turns = Array(runs) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Long chat", *turns, firstRunAt = Instant.ofEpochMilli(now - runs * 3_600_000L).toString())
        agents.refresh()
        turns.forEachIndexed { index, (runId, _, reply) ->
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn ${index + 1}."))
            repeat(callsPerRun) { n -> streamer.emit(runId, readCall(runId, n + 1, contentChars)) }
            streamer.emit(runId, RunStreamEvent.Assistant(reply))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    /** The server's logs are gone: every connection is answered with `410 stream_expired`. */
    private suspend fun expireEverything(runs: Int) {
        repeat(runs) { i ->
            val runId = "run-${i + 1}"
            streamer.reset(runId)
            streamer.emit(runId, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    private fun ConversationState.toolCalls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }

    /** Scrolls the chat up to its first turn: a window at a time, each waiting for its traces before the next is asked for. */
    private suspend fun pageToTheStart(conversations: ConversationRepository, agentId: String, callsPerRun: Int) {
        while (true) {
            awaitUntil(60_000) { !conversations.state(agentId).value.isLoadingOlder }
            val state = conversations.state(agentId).value
            awaitUntil(60_000) { conversations.state(agentId).value.toolCalls().size == conversations.state(agentId).value.items.count { it is UserMessage } * callsPerRun }
            if (!state.hasOlder) return
            conversations.loadOlder(agentId)
            awaitUntil(60_000) { conversations.state(agentId).value.items.count { it is UserMessage } > state.items.count { it is UserMessage } }
        }
    }

    @Test
    fun `every tool call of a long chat survives a restart once the server's logs have expired`() = runBlocking<Unit> {
        val runs = 40
        val callsPerRun = 10
        seedLongConversation("bc-long", runs, callsPerRun, contentChars = 30_000)

        // First visit: the chat opens on its newest window and is scrolled up to its first turn; every run is
        // replayed from its retained log and lands on screen with its payloads.
        val first = repository(hub())
        first.attach("bc-long")
        awaitUntil(60_000) { !first.state("bc-long").value.isLoading && first.state("bc-long").value.items.isNotEmpty() }
        pageToTheStart(first, "bc-long", callsPerRun)
        val seen = first.state("bc-long").value
        assertThat(seen.hasOlder).isFalse()
        assertThat(seen.toolCalls()).hasSize(runs * callsPerRun)
        assertThat(seen.toolCalls().all { (it.payload as? ToolPayload.FileContent)?.content?.length == 30_000 }).isTrue()
        // The disk has every one of them before the process goes.
        awaitUntil(60_000) { traces.runIds("bc-long").size == runs }
        first.detach("bc-long")

        // Restart: a new process, an empty hub, and logs the server no longer has.
        expireEverything(runs)
        val next = repository(hub())
        next.attach("bc-long")
        awaitUntil(60_000) { !next.state("bc-long").value.isLoading && next.state("bc-long").value.items.isNotEmpty() }
        pageToTheStart(next, "bc-long", callsPerRun)

        val rehydrated = next.state("bc-long").value
        assertThat(rehydrated.items.filterIsInstance<ActivityGroup>()).hasSize(runs)
        assertThat(rehydrated.toolCalls().map { it.callId }.toSet()).hasSize(runs * callsPerRun)
        // The rich content rides on the calls: the file text is there to open, not just the row.
        assertThat(rehydrated.toolCalls().count { (it.payload as? ToolPayload.FileContent)?.content?.length == 30_000 }).isEqualTo(runs * callsPerRun)
        // And nothing was asked of the expired logs: the disk answered for every run.
        assertThat(streamer.connections.count()).isEqualTo(runs)
    }

    /**
     * A chat this device never saw whose logs have all expired: in default mode its turns keep their text; in
     * Extended mode the account's own transcript gives the window its tool calls and payloads, which are then kept on
     * disk like any replayed trace.
     */
    @Test
    fun `in Extended mode the account's transcript fills the turns whose logs are gone, and default mode never asks it`() = runBlocking<Unit> {
        val runs = 25
        val callsPerRun = 3
        seedLongConversation("bc-old", runs, callsPerRun, contentChars = 100)
        expireEverything(runs)
        val record = FakeRecord(runs, callsPerRun)

        // Default mode: the documented endpoints only, so the expired turns are text.
        val documented = repository(hub(), record = record, capabilities = Capabilities.DOCUMENTED)
        documented.attach("bc-old")
        awaitUntil(60_000) { !documented.state("bc-old").value.isLoading && documented.state("bc-old").value.items.count { it is UserMessage } == 10 }
        delay(300)
        assertThat(documented.state("bc-old").value.toolCalls()).isEmpty()
        assertThat(record.calls.get()).isEqualTo(0)
        documented.detach("bc-old")

        // Extended mode: the window's turns come back whole from the account.
        val extended = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        extended.attach("bc-old")
        awaitUntil(60_000) { extended.state("bc-old").value.toolCalls().size == 10 * callsPerRun }
        val filled = extended.state("bc-old").value
        assertThat(filled.items.filterIsInstance<UserMessage>().map { it.text }).containsExactly(*(16..25).map { "Prompt $it" }.toTypedArray()).inOrder()
        // Each turn's calls are its own: the record's turns paired with the runs by position.
        val lastGroup = filled.items.filterIsInstance<ActivityGroup>().last()
        assertThat(lastGroup.calls.map { it.callId }).containsExactly("rec-25-1", "rec-25-2", "rec-25-3").inOrder()
        assertThat((lastGroup.calls.first().payload as ToolPayload.FileContent).content).isEqualTo("recorded 25/1")
        assertThat(record.calls.get()).isGreaterThan(0)
        // Kept: the next open needs neither the log nor the account.
        awaitUntil { traces.runIds("bc-old").size == 10 }
        val asked = record.calls.get()
        extended.detach("bc-old")
        val again = repository(hub(), record = record, capabilities = Capabilities.EXTENDED)
        again.attach("bc-old")
        awaitUntil(60_000) { again.state("bc-old").value.toolCalls().size == 10 * callsPerRun }
        delay(200)
        assertThat(record.calls.get()).isEqualTo(asked)
    }
}
