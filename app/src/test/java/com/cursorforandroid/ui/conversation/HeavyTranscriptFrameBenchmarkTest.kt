package com.cursorforandroid.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StretchSteps
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * The conversation screen under the load Bennett's phone lags on (2026-09-25): a long chat whose one finished turn
 * holds a stretch of 400 tool calls between multi-100 KB thoughts, and a live turn that streams a 240 KB thought a
 * few hundred characters at a time, then fires tool calls six at a frame with the stretch open, then writes its
 * reply a word at a time. The real screen over the whole pipeline (the fakes in the demo's seat), driven a frame at
 * a time with the clock held, each frame measured: its wall time, the main thread's CPU time and allocations, the
 * scopes recomposed in the window, the steps of open stretches composed (see [TranscriptPerf.Session.stepComposed]).
 *
 * Robolectric composes, measures and lays out on the JVM and draws nothing on a GPU: the numbers are the main
 * thread's costs on this machine, not a phone's frame times. The assertions hold what does not depend on the
 * machine: how much each phase composes. Every number is printed as a `BENCHMARK heavy` line.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HeavyTranscriptFrameBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4_096)
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-heavy-perf"
    private val turns = 32
    private val hugeTurn = turns - 1
    private val live = "run-$turns"

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    private class Recompositions : CompositionObserver, RecomposeScopeObserver {
        var scopes = 0
        private val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposeScope, Boolean>())
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) { scopes++ }
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }
    }

    private val recompositions = Recompositions()

    /** The main thread's CPU time and allocations, through the JVM's management beans (the test compiles against the Android SDK). */
    private object MainThread {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val cpu = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()
        private val alloc = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes") }.getOrNull()
        fun cpuNanos(): Long = runCatching { cpu?.invoke(bean) as? Long }.getOrNull() ?: 0L
        fun allocatedBytes(): Long = runCatching { alloc?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    private class Phase(val label: String) {
        val wall = mutableListOf<Double>()
        val cpu = mutableListOf<Double>()
        var allocated = 0L
        var scopes = 0
        var steps = 0
        var rows = 0
        override fun toString() = "$label: frames=${wall.size} " +
            "wall median=${f(wall.pct(0.5))} p95=${f(wall.pct(0.95))} max=${f(wall.maxOrNull() ?: 0.0)} total=${f(wall.sum())}ms | " +
            "cpu median=${f(cpu.pct(0.5))} p95=${f(cpu.pct(0.95))} max=${f(cpu.maxOrNull() ?: 0.0)} total=${f(cpu.sum())}ms | " +
            "alloc=${f(allocated / 1048576.0)}MB scopes=$scopes rowsComposed=$rows stepsComposed=$steps"
        companion object {
            fun f(v: Double) = "%.2f".format(v)
            fun List<Double>.pct(p: Double): Double = if (isEmpty()) 0.0 else sorted()[(size * p).toInt().coerceAtMost(size - 1)]
        }
    }

    private fun perf() = TranscriptPerf.sessionOrNull(agentId)?.snapshot()

    /** One frame with the clock held: the main looper's pending work, a frame of the clock, and whatever that recomposes, measured into [phase]. */
    private fun frame(phase: Phase) = measured(phase) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** [action] measured into [phase] as a frame of its own: a tap is dispatched, composed and laid out before the next frame. */
    private fun measured(phase: Phase, action: () -> Unit) {
        val scopes = recompositions.scopes
        val steps = perf()?.stepCompositions ?: 0
        val rows = perf()?.rowCompositions ?: 0
        val alloc0 = MainThread.allocatedBytes()
        val cpu0 = MainThread.cpuNanos()
        val t0 = System.nanoTime()
        action()
        phase.wall += (System.nanoTime() - t0) / 1e6
        phase.cpu += (MainThread.cpuNanos() - cpu0) / 1e6
        phase.allocated += MainThread.allocatedBytes() - alloc0
        phase.scopes += recompositions.scopes - scopes
        phase.steps += (perf()?.stepCompositions ?: 0) - steps
        phase.rows += (perf()?.rowCompositions ?: 0) - rows
    }

    /** Frames until the presentations in flight have landed and nothing moves. */
    private fun settle(phase: Phase? = null, frames: Int = 30) = repeat(frames) {
        Thread.sleep(2)
        if (phase != null) frame(phase) else frame(Phase("unmeasured"))
    }

    private fun paragraphs(prefix: String, chars: Int): String = buildString {
        var n = 0
        while (length < chars) {
            if (isNotEmpty()) append("\n\n")
            append("$prefix ${++n}: weighing whether the change to the module keeps the reader's scroll where it was, ")
            append("what the list measures on a frame, and which of the rows still have to be laid out again. ".repeat(3))
        }
    }

    private fun call(runId: String, n: Int, status: String = "completed") = RunStreamEvent.ToolCall(
        if (n % 3 == 0) {
            SseToolCallDto(
                callId = "$runId-c$n",
                name = "grep",
                status = status,
                args = buildJsonObject { put("pattern", JsonPrimitive("fun render$n")) },
                result = if (status == "completed") buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("app/src/File$n.kt:12: fun render$n()\n".repeat(20))) }) } else null,
            )
        } else {
            SseToolCallDto(
                callId = "$runId-c$n",
                name = "read_file",
                status = status,
                args = buildJsonObject { put("path", JsonPrimitive("app/src/main/java/com/example/module$n/File$n.kt")) },
                result = if (status == "completed") buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("line of code\n".repeat(160))); put("path", JsonPrimitive("app/src/File$n.kt")) }) } else null,
            )
        },
    )

    private fun seed() = runBlocking {
        val prompts = Array(turns) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten the ${it + 1}th module and list the files touched.", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Heavy chat", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        for (i in 1 until turns) {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            if (i == hugeTurn) {
                streamer.emit(runId, RunStreamEvent.Thinking(paragraphs("Opening thought", 200_000)))
                for (n in 1..HUGE_CALLS) {
                    streamer.emit(runId, call(runId, n))
                    if (n % 25 == 0) streamer.emit(runId, RunStreamEvent.Thinking(paragraphs("Between calls $n", 4_000)))
                }
                streamer.emit(runId, RunStreamEvent.Thinking(paragraphs("Closing thought", 120_000)))
            } else {
                streamer.emit(runId, RunStreamEvent.Thinking("Turn $i: reading the module before changing it."))
                repeat(6) { n -> streamer.emit(runId, call(runId, n + 1)) }
            }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i\n\nTightened the module.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper\n\n```kotlin\nval x = $i\n```"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
    }

    private fun graph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
    }

    private fun jumpToLatest() {
        compose.onAllNodes(hasContentDescription("Scroll to latest")).fetchSemanticsNodes().firstOrNull() ?: return
        compose.onNode(hasContentDescription("Scroll to latest")).performClick()
        settle(frames = 60)
    }

    /** [BURST_CALLS] calls into the live run from call [from] on, six a frame: each lands running and is done a frame later. */
    private fun burst(phase: Phase, from: Int) {
        var n = from
        while (n < from + BURST_CALLS) {
            emit(*(n until n + 6).map { call(live, it, status = "running") }.toTypedArray())
            if (n > from) emit(*(n - 6 until n).map { call(live, it) }.toTypedArray())
            n += 6
            Thread.sleep(STREAM_PACE_MS)
            frame(phase)
        }
        emit(*(n - 6 until n).map { call(live, it) }.toTypedArray())
        settle(phase, frames = 10)
    }

    private fun emit(vararg events: RunStreamEvent) = runBlocking { events.forEach { streamer.emit(live, it) } }

    /** The whole scenario, each phase measured, in order. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun scenario(): Phases {
        seed()
        val graph = graph()
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { ConversationScreen(graph, agentId, onBack = {}) }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Prompt $turns", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(120_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle(frames = 60)
        val transcript = compose.onNodeWithTag("transcript")

        // The huge stretch opened: its summary names its 267 reads.
        val hugeSummary = hasText("267 files", substring = true)
        transcript.performScrollToNode(hugeSummary)
        settle(frames = 20)
        val expand = Phase("expand the 400-call stretch")
        measured(expand) { compose.onAllNodes(hugeSummary).onFirst().performClick() }
        repeat(40) { frame(expand) }

        // Read down through it, a finger's drag a frame.
        val scroll = Phase("scroll through the open stretch")
        transcript.performTouchInput { down(Offset(centerX, centerY + 300f)); moveBy(Offset(0f, -(viewConfiguration.touchSlop + 1f))) }
        repeat(120) {
            transcript.performTouchInput { moveBy(Offset(0f, -60f)) }
            frame(scroll)
        }
        transcript.performTouchInput { advanceEventTime(200); up() }
        settle(frames = 30)

        // Back up to its header the way a finger would: performScrollToNode walks the semantics of every step between.
        val back = Phase("scroll back up through it")
        transcript.performTouchInput { down(Offset(centerX, centerY - 300f)); moveBy(Offset(0f, viewConfiguration.touchSlop + 1f)) }
        var dragged = 0
        while (dragged++ < 240 && (dragged <= 120 || compose.onAllNodes(hugeSummary).fetchSemanticsNodes().isEmpty())) {
            transcript.performTouchInput { moveBy(Offset(0f, 60f)) }
            frame(back)
        }
        transcript.performTouchInput { advanceEventTime(200); up() }
        settle(frames = 10)
        val collapse = Phase("collapse it")
        measured(collapse) { compose.onAllNodes(hugeSummary).onFirst().performClick() }
        repeat(30) { frame(collapse) }

        // The live turn: a 240 KB thought streamed ~800 characters at a time, open while it streams.
        jumpToLatest()
        val thought = paragraphs("Live thought", THOUGHT_CHARS)
        val think = Phase("stream a 240 KB thought")
        var at = 0
        while (at < thought.length) {
            val next = minOf(thought.length, at + THOUGHT_DELTA)
            emit(RunStreamEvent.Thinking(thought.substring(at, next)))
            at = next
            Thread.sleep(STREAM_PACE_MS)
            frame(think)
        }
        settle(think, frames = 10)

        // Tool calls land six a frame, each running then done a frame later, with the live stretch open and followed.
        emit(call(live, 1))
        settle(frames = 20)
        val working = hasText("Working") and hasAnyAncestor(hasTestTag("stretch"))
        var waited = 0
        while (compose.onAllNodes(working).fetchSemanticsNodes().isEmpty() && waited++ < 300) settle(frames = 1)
        compose.onAllNodes(working).onFirst().performClick()
        settle(frames = 20)
        jumpToLatest()
        val burst = Phase("240 tool calls, 6 a frame, stretch open")
        burst(burst, from = 2)
        // The same again, the code paths it runs now warm: the steady state of a long, heavy run.
        val again = Phase("240 more, 6 a frame")
        burst(again, from = 2 + BURST_CALLS)

        // The reply, a word a frame.
        val reply = Phase("reply streamed a word a frame")
        repeat(REPLY_WORDS) { i ->
            emit(RunStreamEvent.Assistant(if (i % 25 == 24) "word$i.\n\n" else "word$i "))
            Thread.sleep(STREAM_PACE_MS)
            frame(reply)
        }
        settle(reply, frames = 10)
        return Phases(expand, scroll, back, collapse, think, burst, again, reply)
    }

    private class Phases(
        val expand: Phase, val scroll: Phase, val back: Phase, val collapse: Phase,
        val think: Phase, val burst: Phase, val again: Phase, val reply: Phase,
    ) {
        val all get() = listOf(expand, scroll, back, collapse, think, burst, again, reply)
    }

    /**
     * The scenario once, unmeasured, so the one measured runs on code the JIT has compiled whichever way the screen
     * lays its text out: without it the phases measure how warm the previous ones left text layout as much as the
     * screen (a huge thought laid out whole a few hundred times warms it far more than the same thought in pieces).
     */
    @Test
    fun `1 warm-up pass`() {
        scenario()
    }

    @Test
    fun `2 a long chat with a 400-call stretch, huge thoughts and a heavy live stream, frame by frame`() {
        val phases = scenario()
        phases.all.forEach { println("BENCHMARK heavy $it") }
        println("BENCHMARK heavy perf ${perf()?.render()?.replace("\n", " || ")}")
        // Opening the stretch composes the steps on screen, not its 400 (417 composed, 30 MB allocated through 0.3.93).
        assertWithMessage("${phases.expand}").that(phases.expand.steps).isAtMost(2 * StretchSteps.CHUNK)
        assertWithMessage("${phases.expand}").that(phases.expand.allocated).isLessThan(12L shl 20)
        // A thought streamed open lays out its newest piece, not the whole of it on every delta (184 MB through 0.3.93).
        assertWithMessage("${phases.think}").that(phases.think.allocated).isLessThan(80L shl 20)
        // Tool calls landing in the open stretch recompose its steps, not the rows around it.
        assertWithMessage("${phases.burst}").that(phases.burst.rows).isAtMost(BURST_ROWS)
        assertWithMessage("${phases.again}").that(phases.again.rows).isAtMost(BURST_ROWS)
    }

    private companion object {
        const val HUGE_CALLS = 400
        const val THOUGHT_CHARS = 240_000
        const val THOUGHT_DELTA = 800
        const val BURST_CALLS = 240
        const val REPLY_WORDS = 200
        /** Rows recomposed while a burst lands: the live stretch's line and the rows it moves, a handful, whatever the burst's size. */
        const val BURST_ROWS = 16
        /** Wall time between two streamed frames, so the pipeline behind the screen publishes at a phone's pace rather than the test's. */
        const val STREAM_PACE_MS = 14L
    }
}
