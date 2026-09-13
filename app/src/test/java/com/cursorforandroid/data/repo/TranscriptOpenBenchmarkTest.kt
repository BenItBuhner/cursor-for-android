package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.domain.ActivityGroup
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

/**
 * How long opening a long chat takes after a restart, on the same synthetic chat the rehydration test uses — sixty
 * runs, six hundred tool calls with 30 000-character payloads — with the server's logs expired, so the disk is the
 * only source. Two moments are timed from `attach`: the first items on screen, and the window whole with its tool
 * calls. The numbers are printed for the report; the assertions only hold the shape (everything came back, within
 * a bound loose enough for CI).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptOpenBenchmarkTest {

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

    private fun repository(hub: LiveRunHub) = ConversationRepository(
        session, agents, prefs, hub, attachments, cache, traces,
        isForeground = { true }, prefetchLimit = 0, prefetchSpacingMs = 0, scope = scope,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 60_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }

    private fun readCall(runId: String, n: Int, contentChars: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(contentChars))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    private suspend fun seed(agentId: String, runs: Int, callsPerRun: Int, contentChars: Int) {
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

    private suspend fun expire(runs: Int) {
        repeat(runs) { i ->
            val runId = "run-${i + 1}"
            streamer.reset(runId)
            streamer.emit(runId, RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    private fun ConversationState.toolCalls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }

    @Test
    fun `opening a long chat after a restart`() = runBlocking<Unit> {
        val runs = 60
        val callsPerRun = 10
        seed("bc-bench", runs, callsPerRun, contentChars = 30_000)

        // First visit, scrolled to the top so every run is replayed and on disk.
        val first = repository(hub())
        first.attach("bc-bench")
        awaitUntil { !first.state("bc-bench").value.isLoading && first.state("bc-bench").value.items.isNotEmpty() }
        while (true) {
            awaitUntil { !first.state("bc-bench").value.isLoadingOlder }
            val state = first.state("bc-bench").value
            awaitUntil { first.state("bc-bench").value.toolCalls().size == first.state("bc-bench").value.items.count { it is UserMessage } * callsPerRun }
            if (!state.hasOlder) break
            first.loadOlder("bc-bench")
            awaitUntil { first.state("bc-bench").value.items.count { it is UserMessage } > state.items.count { it is UserMessage } }
        }
        awaitUntil { traces.runIds("bc-bench").size == runs }
        first.detach("bc-bench")
        expire(runs)

        // Restart: an empty hub, and a chat whose window has to come from disk.
        val next = repository(hub())
        val started = System.nanoTime()
        next.attach("bc-bench")
        awaitUntil { next.state("bc-bench").value.items.isNotEmpty() }
        val firstItemsMs = (System.nanoTime() - started) / 1_000_000
        val window = next.state("bc-bench").value.items.count { it is UserMessage }
        awaitUntil { next.state("bc-bench").value.toolCalls().size >= window * callsPerRun }
        val windowWholeMs = (System.nanoTime() - started) / 1_000_000
        awaitUntil { !next.state("bc-bench").value.isLoading }
        val settledMs = (System.nanoTime() - started) / 1_000_000
        val recovered = next.state("bc-bench").value.toolCalls().size

        println("BENCHMARK open-after-restart: runs=$runs toolCalls=${runs * callsPerRun} window=$window turns; first items ${firstItemsMs} ms; window whole (tool calls + payloads) ${windowWholeMs} ms; network settled ${settledMs} ms; tool calls on screen $recovered")
        assertThat(recovered).isEqualTo(window * callsPerRun)
        assertThat(windowWholeMs).isLessThan(20_000)
    }
}
