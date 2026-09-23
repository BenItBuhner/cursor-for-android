package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.CONNECTION_DROPPED
import com.cursorforandroid.data.api.DeviceNetwork
import com.cursorforandroid.data.api.LOOKUP_FAILED_ONLINE
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.UserMessage
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
import java.time.Instant

/**
 * Opening a chat (`ConversationRepository.attach`, `loadOlder`, the replayed traces) under a phone's weather, over
 * the app's real HTTP and SSE clients against [FaultServer]. The invariants:
 *
 *  - what loaded stays: a page, a transcript or a trace that could not be read takes nothing already shown down;
 *  - what could not be read is said under the transcript in the server's words (or the connection's), with the
 *    way to ask again, and the next read that goes through clears it;
 *  - a replay cut mid-stream resumes where it stopped and the trace comes out whole, nothing twice; one refused
 *    is tried again with a growing pause, and one that keeps failing reads as failed with a Retry.
 *
 * Round trips take 300–900 ms, as they do on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptFaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    /** A chat of twenty-five finished turns: more than the run list's first page, so the older ones are paged. */
    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        server.addIdleAgent("bc-1", "Agent", newest)
        val messages = ArrayList<V0ConversationMessageDto>()
        repeat(TURNS) { i ->
            val runId = runId(i)
            val at = Instant.parse("2026-04-13T18:00:00Z").plusSeconds(600L * i).toString()
            server.runs[runId] = RunDto(id = runId, agentId = "bc-1", status = "FINISHED", createdAt = at, updatedAt = at, durationMs = 30_000, result = "Reply $i")
            messages += V0ConversationMessageDto("$runId-u", "user_message", "Prompt $i")
            messages += V0ConversationMessageDto("$runId-a", "assistant_message", "Reply $i")
        }
        server.transcripts["bc-1"] = messages
        // The newest run's log is retained; the others have expired, as they have on a chat of any age.
        server.retain(newest, "Reply ${TURNS - 1}")
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private val state get() = rig.conversations.state("bc-1").value
    private fun footers() = state.items.count { it is RunFooter }
    private fun prompts() = state.items.filterIsInstance<UserMessage>().map { it.text }

    private suspend fun open() {
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
    }

    private suspend fun awaitSettled() = rig.awaitUntil { !state.isLoading && state.traceStatus.pending == 0 && state.items.isNotEmpty() }

    /** The requests for the newest run's stream — the one retained log; the others answer `410` at once. */
    private fun newestStreams() = server.requests(Route.Stream).filter { it.path.contains(newest) }

    @Test
    fun `over a slow connection the newest window opens whole, its one retained trace replayed, and older turns a scroll away`() = runBlocking<Unit> {
        open()
        awaitSettled()
        assertThat(state.error).isNull()
        assertThat(state.transcriptError).isNull()
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(prompts().first()).isEqualTo("Prompt ${TURNS - WINDOW}")
        assertThat(state.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-24-c1", "run-24-c2").inOrder()
        assertThat(state.hasOlder).isTrue()
        // The older run records were paged in behind the first page: two pages of the list, not one.
        rig.awaitUntil { server.requests(Route.ListRuns).size >= 2 }
    }

    @Test
    fun `a transcript that cannot be read leaves the turns' runs standing, says so in the server's words, and the next read clears it`() = runBlocking<Unit> {
        server.outage(Route.Conversation, Fault.Status(503, "unavailable", "The transcript service is briefly unavailable."))
        open()
        rig.awaitUntil { !state.isLoading }
        // The runs rendered on their own — each turn a footer, the retained one its trace — and the failure is named.
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(state.transcriptError).isEqualTo("The transcript service is briefly unavailable.")
        assertThat(state.error).isNull()
        // Three attempts at the transcript, no more.
        assertThat(server.requests(Route.Conversation)).hasSize(3)

        server.clear(Route.Conversation)
        rig.conversations.reload("bc-1")
        rig.awaitUntil { !state.isLoading && state.transcriptError == null }
        awaitSettled()
        assertThat(prompts()).hasSize(WINDOW)
        assertThat(footers()).isEqualTo(WINDOW)
    }

    @Test
    fun `a transcript cut half-way is said as the connection dropping, with the runs standing`() = runBlocking<Unit> {
        server.script(Route.Conversation, Fault.TruncatedBody)
        open()
        rig.awaitUntil { !state.isLoading }
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(state.transcriptError).isEqualTo(CONNECTION_DROPPED)
    }

    @Test
    fun `a transcript silent past the read timeout is said as such, the runs on screen the whole while`() = runBlocking<Unit> {
        server.outage(Route.Conversation, Fault.Silence())
        open()
        // The run page answers in under a second; the transcript's silence takes three attempts of three seconds.
        rig.awaitUntil { footers() == WINDOW }
        assertThat(state.isLoading).isTrue()
        rig.awaitUntil { !state.isLoading }
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(state.transcriptError).isEqualTo("Cursor took too long to respond.")
    }

    @Test
    fun `a run list that cannot be read leaves the transcript's text standing and says so`() = runBlocking<Unit> {
        server.outage(Route.ListRuns, Fault.Status(503, "unavailable", "The run service is briefly unavailable."))
        open()
        rig.awaitUntil { !state.isLoading }
        assertThat(prompts()).isNotEmpty()
        assertThat(footers()).isEqualTo(0)
        assertThat(state.transcriptError).isEqualTo("The run service is briefly unavailable.")

        server.clear(Route.ListRuns)
        rig.conversations.reload("bc-1")
        rig.awaitUntil { !state.isLoading && state.transcriptError == null && footers() == WINDOW }
    }

    @Test
    fun `an older page that cannot be read keeps the window as it was, says so, and the next scroll up brings it`() = runBlocking<Unit> {
        // The list is read in pages of ten: the window's own page comes first, and the pages behind it cannot be read.
        server.pageSize = 10
        server.script(Route.ListRuns, Fault.Pass)
        server.outage(Route.ListRuns, Fault.Status(503, "unavailable", "Try again later."))
        open()
        awaitSettled()
        // The paging behind the first page met the outage and stopped, its cursor kept; nothing on screen says so
        // yet — the reader has not asked for the older turns.
        rig.awaitUntil { server.requests(Route.ListRuns).size >= 4 }
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(state.transcriptError).isNull()

        rig.conversations.loadOlder("bc-1")
        rig.awaitUntil { state.isLoadingOlder }
        rig.awaitUntil { !state.isLoadingOlder }
        // The window widened onto the transcript's older prompts, which stand without their runs; the page that
        // could not be read is said, and the turns before it are still a scroll away.
        assertThat(state.transcriptError).isEqualTo("Try again later.")
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(prompts()).hasSize(2 * WINDOW)
        assertThat(state.hasOlder).isTrue()

        server.clear(Route.ListRuns)
        rig.conversations.loadOlder("bc-1")
        rig.awaitUntil { state.isLoadingOlder }
        rig.awaitUntil { !state.isLoadingOlder && state.transcriptError == null }
        rig.awaitUntil { state.traceStatus.pending == 0 }
        assertThat(prompts()).hasSize(TURNS)
        assertThat(footers()).isAtLeast(2 * WINDOW)
        assertThat(state.hasOlder).isFalse()
    }

    @Test
    fun `a replay cut mid-stream resumes where it stopped, and the trace comes out whole`() = runBlocking<Unit> {
        server.script(Route.Stream, Fault.StreamCut(events = 2), path = newest)
        open()
        awaitSettled()
        val group = state.items.filterIsInstance<ActivityGroup>().single()
        assertThat(group.calls.map { it.callId }).containsExactly("run-24-c1", "run-24-c2").inOrder()
        val streams = newestStreams()
        assertThat(streams).hasSize(2)
        assertThat(streams[1].lastEventId).isEqualTo("run-24#2")
        assertThat(state.traceStatus.failed).isEqualTo(0)
    }

    @Test
    fun `a replay refused once is asked for again after a pause, and lands`() = runBlocking<Unit> {
        server.script(Route.Stream, Fault.Status(503, "unavailable", "Try again later."), path = newest)
        open()
        awaitSettled()
        assertThat(state.items.filterIsInstance<ActivityGroup>()).hasSize(1)
        val streams = newestStreams()
        assertThat(streams).hasSize(2)
        assertWithMessage("the reconnect backed off").that(streams[1].atMillis - streams[0].atMillis).isAtLeast(1_000L)
    }

    @Test
    fun `a replay that keeps failing reads as failed with a retry, and the retry brings it`() = runBlocking<Unit> {
        server.outage(Route.Stream, Fault.Status(503, "unavailable", "Try again later."), path = newest)
        open()
        rig.awaitUntil { !state.isLoading && state.traceStatus.failed == 1 }
        assertThat(footers()).isEqualTo(WINDOW)
        assertThat(state.items.filterIsInstance<ActivityGroup>()).isEmpty()
        // The connection, and the streamer's own bounded reconnects: then the trace reads as failed, no sooner.
        assertThat(newestStreams()).hasSize(3)

        server.clear(Route.Stream)
        rig.conversations.retryTraces("bc-1")
        rig.awaitUntil { state.traceStatus.failed == 0 && state.traceStatus.pending == 0 }
        assertThat(state.items.filterIsInstance<ActivityGroup>()).hasSize(1)
    }

    @Test
    fun `a host that does not resolve with the phone online is said as the lookup that failed, not as being offline, with the way to ask again`() = runBlocking<Unit> {
        val offline = FaultRig("http://cursor-for-android.invalid/", folder.newFolder("offline"))
        try {
            DeviceNetwork.install { true }
            offline.conversations.attach("bc-1")
            offline.awaitUntil { !offline.conversations.state("bc-1").value.isLoading }
            assertThat(offline.conversations.state("bc-1").value.error).isEqualTo(LOOKUP_FAILED_ONLINE)
        } finally {
            DeviceNetwork.install { null }
            offline.close()
        }
    }

    private companion object {
        const val TURNS = 25
        /** The newest runs a chat opens on (`ConversationRepository.WINDOW_RUNS`). */
        const val WINDOW = 10
        fun runId(i: Int) = "run-%02d".format(i)
        val newest = runId(TURNS - 1)
    }
}
