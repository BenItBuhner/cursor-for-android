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
import com.cursorforandroid.domain.NoticeCard
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * How the [LiveRunHub] answers a stream that stops before the run does: the run record decides whether the run is
 * over, and while it is not, the connection comes back — resumed from the last event, or from the start when the
 * server rejected that position — for as long as anyone is subscribed. Robolectric only because [SessionManager]
 * needs a Context for its stores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveRunHubTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        agents = AgentRepository(session, prefs, AttachmentStore(context))
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun tool(id: String, name: String, status: String, path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    /** What the hub knows right now; null until a subscriber has brought the run in. */
    private fun snapshot() = hub.current("bc-1", "run-1")

    private fun current() = snapshot()!!

    private fun connections() = streamer.connections.count { it == "run-1" }

    @Test
    fun `a stream error while the run is going is a dropped connection that is resumed, not part of the run`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Reading the code."))
        streamer.emit("run-1", tool("c1", "read_file", "running", "README.md"))
        // The server loses the worker's stream after those three events.
        streamer.dropNextConnection("run-1", "upstream_error", "Run stream failed", afterEvents = 3)
        val seen = CopyOnWriteArrayList<LiveRunHub.Snapshot>()
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { seen += it } }

        awaitUntil { snapshot()?.reconnecting == true }
        val dropped = current()
        // The trace stops where the connection did — no error card, and the read that was in progress still is,
        // because as far as anyone knows it is — and the run is not over.
        assertThat(dropped.items.map { it::class.simpleName }).containsExactly("ActivityGroup")
        assertThat(dropped.items.filterIsInstance<ActivityGroup>().single().isRunning).isTrue()
        assertThat(dropped.finished).isFalse()
        assertThat(dropped.status).isEqualTo(RunStatus.RUNNING)

        // The record says the run is still going, so the hub comes back, continuing after the last event it saw.
        awaitUntil { connections() == 2 }
        assertThat(streamer.resumes.last()).isEqualTo("run-1#3")
        assertThat(api.getRunCalls).isEqualTo(1)

        streamer.emit("run-1", tool("c1", "read_file", "completed", "README.md"))
        awaitUntil { snapshot()?.reconnecting == false }
        val resumed = current()
        // One thought, one tool call: the story continued rather than starting over.
        val work = resumed.items.filterIsInstance<ActivityGroup>().single()
        assertThat(work.thoughts).hasSize(1)
        assertThat(work.calls.single().status).isEqualTo("completed")

        streamer.emit("run-1", RunStreamEvent.Assistant("Done."))
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Done.", 30_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { snapshot()?.finished == true }
        assertThat(current().items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        assertThat(current().items.none { it is NoticeCard }).isTrue()
        assertThat(current().reconnecting).isFalse()
        assertThat(seen.any { it.reconnecting }).isTrue()
        assertThat(connections()).isEqualTo(2)
        subscription.cancel()
    }

    @Test
    fun `a stream error for a run that already ended is settled from the run record at once`() = runBlocking {
        api.addIdleAgent("bc-1", "Agent", "run-1", result = "All done.")
        // The connection delivers part of the story and then the server loses it; the record has the ending.
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", tool("c1", "edit_file", "running", "app/src/A.kt"))
        streamer.dropNextConnection("run-1", "stream_unavailable", "Run stream is no longer available", afterEvents = 2)
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }

        awaitUntil { snapshot()?.finished == true }
        val snapshot = current()
        assertThat(snapshot.status).isEqualTo(RunStatus.FINISHED)
        assertThat(snapshot.result?.text).isEqualTo("All done.")
        assertThat(snapshot.result?.durationMs).isEqualTo(65_000L)
        assertThat(snapshot.reconnecting).isFalse()
        assertThat(snapshot.items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage", "RunFooter").inOrder()
        // The edit the stream never reported the end of is not left spinning under a "Worked" footer.
        val tools = snapshot.items.filterIsInstance<ActivityGroup>().single()
        assertThat(tools.isRunning).isFalse()
        assertThat(tools.calls.single().status).isEqualTo("completed")
        assertThat((snapshot.items[1] as AssistantMessage).markdown).isEqualTo("All done.")
        assertThat((snapshot.items.last() as RunFooter).durationMs).isEqualTo(65_000L)
        // Nothing to reconnect to, and no waiting around for a poll: one connection, one read of the record.
        assertThat(connections()).isEqualTo(1)
        assertThat(api.getRunCalls).isEqualTo(1)
        subscription.cancel()
    }

    @Test
    fun `a stream that is not there yet is asked again, more patiently each time, until it is`() = runBlocking {
        // A follow-up on an idle agent: the run exists, its machine is still waking up, and the stream endpoint
        // answers with an in-band error until the worker is attached.
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "CREATING")
        repeat(3) { streamer.dropNextConnection("run-1", "stream_unavailable", "Run stream is no longer available") }
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }

        awaitUntil { connections() == 4 }
        // Nothing had arrived, so there was nothing to resume from; each attempt started clean.
        assertThat(streamer.resumes.filterNotNull()).isEmpty()
        assertThat(current().reconnecting).isTrue()
        assertThat(current().items).isEmpty()
        assertThat(api.getRunCalls).isEqualTo(3)

        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Assistant("Hello"))
        awaitUntil { snapshot()?.let { !it.reconnecting && it.items.isNotEmpty() } == true }
        assertThat(current().status).isEqualTo(RunStatus.RUNNING)
        assertThat((current().items.single() as AssistantMessage).markdown).isEqualTo("Hello")
        assertThat(connections()).isEqualTo(4)
        subscription.cancel()
    }

    @Test
    fun `a run that fails after its stream broke is explained by the stream's error`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        // The worker went away: the stream says why, then the record reports the run as failed with no result text.
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "ERROR", durationMs = 9_000)
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.dropNextConnection("run-1", "upstream_error", "Worker disconnected", afterEvents = 1)
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }

        awaitUntil { snapshot()?.finished == true }
        assertThat(current().status).isEqualTo(RunStatus.ERROR)
        val notice = current().items.filterIsInstance<NoticeCard>().single()
        assertThat(notice.title).isEqualTo("Run failed")
        assertThat(notice.subtitle).isEqualTo("Worker disconnected")
        assertThat((current().items.last() as RunFooter).durationMs).isEqualTo(9_000L)
        subscription.cancel()
    }

    @Test
    fun `an expired log is polled, never reconnected to, until the record ends the run`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }

        awaitUntil { api.getRunCalls >= 4 }
        assertThat(current().expired).isTrue()
        assertThat(current().finished).isFalse()
        assertThat(current().reconnecting).isFalse()
        assertThat(connections()).isEqualTo(1)

        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "FINISHED", result = "Done.", durationMs = 4_000)
        awaitUntil { snapshot()?.finished == true }
        assertThat(current().status).isEqualTo(RunStatus.FINISHED)
        assertThat((current().items.first() as AssistantMessage).markdown).isEqualTo("Done.")
        assertThat(connections()).isEqualTo(1)
        subscription.cancel()
    }

    @Test
    fun `a record whose status this build cannot read settles the run instead of polling forever`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        api.runs["run-1"] = api.runs.getValue("run-1").copy(status = "HIBERNATING")
        streamer.emit("run-1", RunStreamEvent.Assistant("Half a reply."))
        streamer.emit("run-1", RunStreamEvent.Error(RunStreamEvent.Error.STREAM_EXPIRED, "This run's live stream has expired."))
        streamer.emit("run-1", RunStreamEvent.Done)
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }

        awaitUntil { snapshot()?.finished == true }
        assertThat(current().status).isEqualTo(RunStatus.UNKNOWN)
        // What did arrive stands; the run is simply over as far as this build can tell.
        assertThat((current().items.first() as AssistantMessage).markdown).isEqualTo("Half a reply.")
        val polls = api.getRunCalls
        delay(300)
        assertThat(api.getRunCalls).isEqualTo(polls)
        subscription.cancel()
    }

    @Test
    fun `a rejected resume position starts the story over without ever showing it twice`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Looking."))
        streamer.emit("run-1", RunStreamEvent.Assistant("Hello"))
        streamer.dropNextConnection("run-1", RunStreamEvent.Error.INVALID_LAST_EVENT_ID, "Unknown event id", afterEvents = 3)
        val sizes = CopyOnWriteArrayList<Int>()
        val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { sizes += it.items.size } }

        awaitUntil { connections() == 2 && snapshot()?.reconnecting == false }
        assertThat(streamer.resumes).containsExactly(null, null).inOrder()
        // Replayed from the first event, the story is what it was — once — and nothing shorter was published meanwhile.
        assertThat(current().items.map { it::class.simpleName }).containsExactly("ActivityGroup", "AssistantMessage").inOrder()
        assertThat((current().items[1] as AssistantMessage).markdown).isEqualTo("Hello")
        assertThat(sizes.drop(sizes.indexOfFirst { it == 2 })).doesNotContain(1)

        streamer.emit("run-1", RunStreamEvent.Assistant(" world"))
        awaitUntil { (snapshot()?.items?.lastOrNull() as? AssistantMessage)?.markdown == "Hello world" }
        assertThat(current().items).hasSize(2)
        subscription.cancel()
    }

    @Test
    fun `resubscribing after the stream was released rebuilds the trace without collapsing it on screen`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Looking."))
        streamer.emit("run-1", tool("c1", "read_file", "running", "README.md"))
        streamer.emit("run-1", tool("c1", "read_file", "completed", "README.md"))
        streamer.emit("run-1", RunStreamEvent.Assistant("Hi"))
        val first = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { snapshot()?.items?.size == 2 }
        first.cancel()
        delay(150)

        // A fresh connection replays the run from the start; the reader keeps seeing the two items throughout.
        val sizes = CopyOnWriteArrayList<Int>()
        val second = scope.launch { hub.snapshots("bc-1", "run-1").collect { sizes += it.items.size } }
        awaitUntil { connections() == 2 }
        streamer.emit("run-1", RunStreamEvent.Assistant(" there"))
        awaitUntil { (snapshot()?.items?.lastOrNull() as? AssistantMessage)?.markdown == "Hi there" }
        assertThat(sizes).isNotEmpty()
        assertThat(sizes.toSet()).containsExactly(2)
        assertThat(current().items.filterIsInstance<ActivityGroup>().single().thoughts).hasSize(1)
        second.cancel()
    }

    /**
     * Nothing is published until a pass that started over has caught up with what the last one had applied, so the
     * trace never collapses and grows back. A replay that is shorter than that — a log the server trimmed — must
     * not leave the trace and "Reconnecting…" frozen for the rest of the run.
     */
    @Test
    fun `a replay shorter than the pass before it stops holding the trace still`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        listOf("One ", "two ", "three ", "four").forEach { streamer.emit("run-1", RunStreamEvent.Assistant(it)) }
        val first = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { (snapshot()?.items?.lastOrNull() as? AssistantMessage)?.markdown == "One two three four" }
        first.cancel()
        delay(150)

        // The retained log has been trimmed: the fresh connection replays less than the last pass had applied.
        streamer.reset("run-1")
        streamer.emit("run-1", RunStreamEvent.Assistant("One "))
        val second = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        awaitUntil { connections() == 2 }
        delay(150)
        assertThat((current().items.last() as AssistantMessage).markdown).isEqualTo("One two three four")

        // Past the bound the rebuilt story is published, short as it is, rather than never.
        now += 30_000
        streamer.emit("run-1", RunStreamEvent.Assistant("again"))
        awaitUntil { (snapshot()?.items?.lastOrNull() as? AssistantMessage)?.markdown == "One again" }
        assertThat(current().reconnecting).isFalse()
        second.cancel()
    }

    /**
     * Cancelling a coroutine does not stop it: a released pass can still be applying events when the next
     * subscriber restarts the stream. Whatever the scheduling, it must not write into the accumulator that
     * replaced its own — the trace would read as the agent saying everything twice.
     */
    @Test
    fun `resubscribing while the released pass is still winding down never doubles the trace`() = runBlocking {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Assistant("Hello"))

        repeat(8) {
            val subscription = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
            awaitUntil { snapshot()?.items?.isNotEmpty() == true }
            subscription.cancel()
            delay(55)
        }

        val settled = scope.launch { hub.snapshots("bc-1", "run-1").collect { } }
        streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, "Hello", 1_000, null))
        streamer.emit("run-1", RunStreamEvent.Done)
        awaitUntil { snapshot()?.finished == true }

        val replies = current().items.filterIsInstance<AssistantMessage>()
        assertThat(replies.map { it.markdown }).containsExactly("Hello")
        settled.cancel()
    }
}
