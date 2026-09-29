package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A subscriber that asks the [LiveRunHub] for sampled snapshots (the live notification's monitor, a follow-up waiting
 * on a run's end) hears the words streaming into a step at most once per period, and a step change, a status and the
 * finish at once; a run only such subscribers watch is rebuilt at that pace, not per token; and nothing a paced run
 * sat on is lost — not to a subscriber that wants every event arriving, not to a connection that drops.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveRunHubSamplingTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 1_024)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hub: LiveRunHub

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        val session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        val agents = AgentRepository(session, prefs, AttachmentStore(context))
        hub = LiveRunHub(session, agents, nowProvider = { 1_800_000_000_000L }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }

    private fun LiveRunHub.Snapshot.reply(): String = items.filterIsInstance<AssistantMessage>().joinToString("") { it.markdown }

    /** [count] tokens of a reply, [spacingMs] apart: "t0 t1 t2 …". */
    private suspend fun stream(count: Int, spacingMs: Long, from: Int = 0) {
        for (i in from until from + count) {
            streamer.emit("run-1", RunStreamEvent.Assistant("t$i "))
            delay(spacingMs)
        }
    }

    private fun reply(count: Int, from: Int = 0) = (from until from + count).joinToString("") { "t$it " }

    @Test
    fun `a sampled subscriber alone hears one snapshot per period, the run is rebuilt at that pace, and its finish comes at once`() = runBlocking {
        val seen = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 300).collect { seen += it } }
        awaitUntil { streamer.connections.isNotEmpty() }
        val builtBefore = hub.published.get()

        stream(count = 60, spacingMs = 20)
        // The last period's latest goes out when it closes: the whole reply, nothing lost to the pacing.
        awaitUntil { seen.lastOrNull()?.reply() == reply(60) }
        // 1.2 s of tokens at 300 ms: a handful of snapshots, not sixty.
        assertThat(seen.size).isAtMost(10)
        assertThat(hub.published.get() - builtBefore).isAtMost(10)

        val resultAt = System.nanoTime()
        streamer.emit("run-1", RunStreamEvent.Assistant("done"))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, reply(60) + "done", 1_000, null))
        awaitUntil { seen.lastOrNull()?.finished == true }
        // Not held back to the end of a period (300 ms) — and it carries the reply's last token.
        assertThat((System.nanoTime() - resultAt) / 1_000_000).isLessThan(250)
        assertThat(seen.last().reply()).isEqualTo(reply(60) + "done")
        subscription.cancel()
    }

    @Test
    fun `a subscriber that wants every event keeps its pace, and a sampled one beside it still hears one per period`() = runBlocking {
        val eager = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        val sampled = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        val screen = scope.launch { hub.snapshots("bc-1", "run-1").collect { eager += it } }
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 300).collect { sampled += it } }
        awaitUntil { streamer.connections.isNotEmpty() }
        val builtBefore = hub.published.get()

        stream(count = 60, spacingMs = 20)
        awaitUntil { eager.lastOrNull()?.reply() == reply(60) && sampled.lastOrNull()?.reply() == reply(60) }
        // The screen's run is rebuilt per event, as it always was.
        assertThat(hub.published.get() - builtBefore).isAtLeast(55)
        assertThat(eager.size).isAtLeast(30)
        assertThat(sampled.size).isAtMost(10)
        screen.cancel()
        notification.cancel()
    }

    @Test
    fun `a subscriber that wants every event, arriving on a paced run, is sent what the run sat on at once`() = runBlocking {
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 3_000).collect { } }
        // The connection's first event (the status) goes out at once, and so does the reply's start, a step; the words
        // after it, inside the period, are held back.
        awaitUntil { hub.current("bc-1", "run-1")?.eventCount == 1 }
        stream(count = 6, spacingMs = 10)
        delay(100)
        assertThat(hub.current("bc-1", "run-1")!!.reply()).isEqualTo(reply(1))

        val openedAt = System.nanoTime()
        val screen = MutableStateFlow<LiveRunHub.Snapshot?>(null)
        val chat = scope.launch { hub.snapshots("bc-1", "run-1").collect { screen.value = it } }
        awaitUntil { screen.value?.reply() == reply(6) }
        // Well before the period (3 s) would have let them out.
        assertThat((System.nanoTime() - openedAt) / 1_000_000).isLessThan(1_000)
        chat.cancel()
        notification.cancel()
    }

    @Test
    fun `an unwatched subscriber does not make a paced run publish per event`() = runBlocking {
        val sampled = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        // A chat kept current with no screen on it, beside the notification's monitor.
        val held = scope.launch { hub.snapshots("bc-1", "run-1", watched = MutableStateFlow(false)).collect { } }
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 300).collect { sampled += it } }
        awaitUntil { streamer.connections.isNotEmpty() }
        val builtBefore = hub.published.get()
        stream(count = 60, spacingMs = 20)
        awaitUntil { sampled.lastOrNull()?.reply() == reply(60) }
        assertThat(hub.published.get() - builtBefore).isAtMost(10)
        held.cancel()
        notification.cancel()
    }

    @Test
    fun `a connection that drops goes out with everything a paced run applied before it`() = runBlocking {
        // The first connection carries the status and four tokens, then the server loses it; the next few are lost at
        // once, so the hub stays on its way back long enough to be seen there.
        streamer.dropNextConnection("run-1", "upstream_error", "Run stream failed", afterEvents = 5)
        repeat(6) { streamer.dropNextConnection("run-1", "upstream_error", "Run stream failed", afterEvents = 0) }
        // The notification first: a pass that starts with only an unwatched subscriber is looked in on, not paced.
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 60_000).collect { } }
        awaitUntil { hub.current("bc-1", "run-1")?.eventCount == 1 }
        // Unwatched, so it sets no pace, and hears every publication as it is made.
        val published = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        val observer = scope.launch { hub.snapshots("bc-1", "run-1", watched = MutableStateFlow(false)).collect { published += it } }
        awaitUntil { published.isNotEmpty() }
        stream(count = 4, spacingMs = 0)
        awaitUntil { published.any { it.reconnecting } }
        assertThat(published.first { it.reconnecting }.reply()).isEqualTo(reply(4))
        observer.cancel()
        notification.cancel()
    }

    private fun tool(id: String, status: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = "read_file", status = status, args = buildJsonObject { put("path", JsonPrimitive("src/$id.kt")) }),
    )

    private fun LiveRunHub.Snapshot.calls() = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }

    @Test
    fun `a step change mid-period reaches a sampled subscriber at once, with the words before it`() = runBlocking {
        val seen = MutableStateFlow<LiveRunHub.Snapshot?>(null)
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 3_000).collect { seen.value = it } }
        awaitUntil { streamer.connections.isNotEmpty() }
        stream(count = 10, spacingMs = 10)
        delay(100)
        // The reply's start went out; the words after it are the period's.
        assertThat(seen.value!!.reply()).isEqualTo(reply(1))

        val startedAt = System.nanoTime()
        streamer.emit("run-1", tool("read", "running"))
        awaitUntil { seen.value!!.calls().any { it.isRunning } }
        assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(1_000)
        assertThat(seen.value!!.reply()).isEqualTo(reply(10))

        val endedAt = System.nanoTime()
        streamer.emit("run-1", tool("read", "completed"))
        awaitUntil { seen.value!!.calls().none { it.isRunning } }
        streamer.emit("run-1", RunStreamEvent.Thinking("Next."))
        awaitUntil { seen.value!!.stepChanges >= 4 }
        assertThat((System.nanoTime() - endedAt) / 1_000_000).isLessThan(1_000)
        notification.cancel()
    }

    @Test
    fun `a status change and a run ending in error reach a sampled subscriber at once, the last words with them`() = runBlocking {
        val seen = MutableStateFlow<LiveRunHub.Snapshot?>(null)
        val notification = scope.launch { hub.snapshots("bc-1", "run-1", sampleMs = 60_000).collect { seen.value = it } }
        awaitUntil { streamer.connections.isNotEmpty() }
        stream(count = 5, spacingMs = 10)

        val statusAt = System.nanoTime()
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.CREATING))
        awaitUntil { seen.value?.status == RunStatus.CREATING }
        assertThat((System.nanoTime() - statusAt) / 1_000_000).isLessThan(1_000)
        assertThat(seen.value!!.reply()).isEqualTo(reply(5))

        stream(count = 5, spacingMs = 10, from = 5)
        val failedAt = System.nanoTime()
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.ERROR, null, 1_000, null))
        awaitUntil { seen.value?.finished == true }
        assertThat((System.nanoTime() - failedAt) / 1_000_000).isLessThan(1_000)
        assertThat(seen.value!!.status).isEqualTo(RunStatus.ERROR)
        assertThat(seen.value!!.reply()).isEqualTo(reply(10))
        notification.cancel()
    }
}
