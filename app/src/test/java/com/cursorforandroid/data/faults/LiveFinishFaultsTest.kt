package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A turn ending while the chat is open, over the app's real SSE client against [FaultServer] at a phone's round
 * trip: on the stream's own `result`, on a stream cut by a network switch and resumed, and off the run record when
 * the stream is gone for good. The invariant, whichever way it ends: the frame that ends the stream ends the turn —
 * `isStreaming` false and a terminal status in the same frame, the row idle by then — and no later frame reads
 * active again. What broke it was the hub publishing the finished snapshot before it patched the row: the chat's
 * collector read the row still running and kept the chat at "Working…" for the six seconds its next-run looks took
 * (see `LiveRunHub.finish`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiveFinishFaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    /** One frame the screen would have drawn: whether the chat streams, its status, and whether the row ran at that instant. */
    private data class Frame(val streaming: Boolean, val status: RunStatus?, val rowRunning: Boolean?)

    private val frames = CopyOnWriteArrayList<Frame>()
    private var recorder: Job? = null

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        server.addRunningAgent("bc-1", "Agent", "run-1", prompt = "Ship it")
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig.close()
        server.close()
    }

    private val state get() = rig.conversations.state("bc-1").value

    private suspend fun openAndRecord() {
        rig.agents.refresh()
        assertThat(rig.agents.agent("bc-1")!!.isRunning).isTrue()
        rig.conversations.attach("bc-1")
        recorder = rig.scope.launch { rig.conversations.state("bc-1").collect { frames += Frame(it.isStreaming, it.runStatus, rig.agents.agent("bc-1")?.isRunning) } }
        rig.awaitUntil { state.isStreaming }
    }

    /** The frame that ended the stream — the first non-streaming frame after the first streaming one — once the footer is on screen; asserts the invariants over every frame after it. */
    private suspend fun assertEndedInOneFrame(status: RunStatus = RunStatus.FINISHED) {
        rig.awaitUntil { state.items.lastOrNull() is RunFooter && !state.isStreaming }
        // Long enough for a next-run look and its record read to have landed and been folded in, had they been wrong.
        rig.watch(2_000) {
            val started = frames.indexOfFirst { it.streaming }
            assertWithMessage("frames: $frames").that(started).isAtLeast(0)
            val ended = frames.withIndex().first { (i, frame) -> i > started && !frame.streaming }.index
            val after = frames.drop(ended)
            assertWithMessage("frames from the stream's end: $after").that(after.none { it.status?.isActive == true }).isTrue()
            assertWithMessage("the row at the frame that ended the stream: ${frames[ended]}").that(frames[ended].rowRunning).isFalse()
            assertThat(frames[ended].status).isEqualTo(status)
        }
        assertThat(rig.agents.agent("bc-1")!!.runStatus).isEqualTo(status)
        assertThat(rig.agents.endedStatus("bc-1", "run-1")).isEqualTo(status)
    }

    @Test
    fun `a run finishing on its stream over a slow connection ends the chat in the frame that ends the stream`() = runBlocking<Unit> {
        server.retain("run-1", "Shipped.")
        openAndRecord()
        assertEndedInOneFrame()
        assertThat(state.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
        assertThat(state.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat((state.items.last() as RunFooter).durationMs).isEqualTo(30_000L)
    }

    @Test
    fun `a stream cut by a network switch before the result is resumed, and the finish still comes as one frame`() = runBlocking<Unit> {
        server.retain("run-1", "Shipped.")
        // Cut after the two tool calls: the reply and the result come on the connection resumed from there.
        server.script(Route.Stream, Fault.StreamCut(events = 4), path = "run-1")
        openAndRecord()
        assertEndedInOneFrame()
        val streams = server.requests(Route.Stream).filter { it.path.contains("run-1") }
        assertThat(streams).hasSize(2)
        assertThat(streams[1].lastEventId).isEqualTo("run-1#4")
        // Nothing twice: the resumed connection carried what the cut one had not.
        assertThat(state.items.filterIsInstance<ActivityGroup>().single().calls.map { it.callId }).containsExactly("run-1-c1", "run-1-c2").inOrder()
        assertThat(state.items.filterIsInstance<AssistantMessage>()).hasSize(1)
    }

    @Test
    fun `a stream gone for good is settled off the run record, the chat and the row ending in the same frame`() = runBlocking<Unit> {
        // No retained log: the stream answers 410, and only the record can say how the run ends. It says so a moment later.
        openAndRecord()
        rig.awaitUntil { server.requests(Route.GetRun).isNotEmpty() }
        server.finish("bc-1", "run-1", "Shipped.")
        assertEndedInOneFrame()
        assertThat(state.items.filterIsInstance<AssistantMessage>().single().markdown).isEqualTo("Shipped.")
    }
}
