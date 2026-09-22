package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The conversation screen over a 300-turn chat, composed for real under Robolectric, with the main thread's cost
 * measured: the test thread is the main looper here, so the CPU time it spends inside `waitForIdle` is what the
 * screen's compositions, layouts and the work they do cost the main thread — the number a device's frame times
 * are made of, without the device. Counted with it: how many rows were composed (first or again), what the chat
 * looked like when the first content and the newest page came, and what a burst of live deltas and a scroll up to
 * older turns cost. Every number is printed as a `BENCH screen` line; the assertions hold the shape alone.
 *
 * The chat is the plain 300-turn one of `TranscriptPerfBenchmarkTest` — twelve reads of 40 000 characters a turn,
 * the newest turn under way — served by the fakes through the demo seat of the graph (no disk, no network) so the
 * whole pipeline runs: the repository, the replays, the view model and the screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TranscriptScreenBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()
    private val agentId = "bc-screen-perf"
    private val runs = 300
    private val callsPerRun = 12
    private val payloadChars = 40_000

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    private fun readCall(runId: String, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(payloadChars))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    private fun seed() = runBlocking {
        val turns = Array(runs) { Triple("run-${it + 1}", "Prompt ${it + 1}: please refactor the ${it + 1}th module and explain the change with a list of the files touched.", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Long chat", *turns, firstRunAt = Instant.ofEpochMilli(now - runs * 3_600_000L).toString())
        val live = "run-$runs"
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        for (i in 1 until runs) {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $i: reading the module before changing it."))
            repeat(callsPerRun) { n -> streamer.emit(runId, readCall(runId, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i\n\nRefactored the module.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper\n\n```kotlin\nval x = $i\n```"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Working on the newest turn."))
        streamer.emit(live, readCall(live, 1))
    }

    /** The graph with the fakes in the demo's seat: the demo keeps nothing on disk and asks no network, and the fakes answer as the server would. */
    private fun graph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
    }

    private class MainCost(var cpuNanos: Long = 0L, var wallNanos: Long = 0L) {
        val cpuMs: Double get() = cpuNanos / 1e6
        val wallMs: Double get() = wallNanos / 1e6
        override fun toString() = "mainCpu=${"%.1f".format(cpuMs)}ms wall=${"%.1f".format(wallMs)}ms"
    }

    /** The current thread's CPU time (`ThreadMXBean.getCurrentThreadCpuTime`, through reflection: the test compiles against the Android SDK and runs on a JVM). */
    private object MainCpu {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val method = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()

        fun nanos(): Long = runCatching { method?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    /** Runs [block] on the main thread and adds what it cost the main thread to [cost]. */
    private inline fun <T> onMain(cost: MainCost, block: () -> T): T {
        val cpu0 = MainCpu.nanos()
        val t0 = System.nanoTime()
        val result = block()
        cost.wallNanos += System.nanoTime() - t0
        cost.cpuNanos += MainCpu.nanos() - cpu0
        return result
    }

    /** The transcript's newest item: its accessibility actions index the items top-down (see `readerScrolling`). */
    private fun newestIndex(): Int = compose.onAllNodes(hasScrollToIndexAction()).onFirst().fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount - 1

    private fun rowsComposed(): Int = TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.rowCompositions ?: 0

    private fun report(metric: String, value: Any?) = println("BENCH screen | $metric | $value")

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the conversation screen over a 300-turn chat - open, live deltas, scroll to older turns`() {
        seed()
        val graph = graph()
        runBlocking { graph.session.enterDemo() }
        assertThat(graph.session.state.value).isInstanceOf(SessionState.SignedIn::class.java)
        runBlocking { graph.agents.refresh() }

        val open = MainCost()
        val opened = System.nanoTime()
        onMain(open) {
            compose.setContent {
                CursorTheme(mode = ThemeMode.Dark) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        ConversationScreen(graph, agentId, onBack = {})
                    }
                }
            }
        }
        // First content: the newest prompt on screen.
        onMain(open) { compose.waitUntil(60_000) { compose.onAllNodes(hasText("Prompt $runs", substring = true)).fetchSemanticsNodes().isNotEmpty() } }
        val firstContentMs = (System.nanoTime() - opened) / 1_000_000
        // The newest page whole: every finished turn of the window with its trace, the run followed.
        onMain(open) {
            compose.waitUntil(120_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.isStreaming && it.traceStatus.pending == 0 } }
            compose.waitForIdle()
        }
        val wholeMs = (System.nanoTime() - opened) / 1_000_000
        report("open.firstContentMs", firstContentMs)
        report("open.newestPageWholeMs", wholeMs)
        report("open.main", open)
        report("open.rowsComposed", rowsComposed())
        report("open.perf", TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.render()?.replace("\n", " || "))

        // The reader scrolls to the top and every older page is asked for, one after another, while the list sits
        // at its end: each page that lands is a publication inserting above the rows on screen — what recomposing
        // costs the main thread for a publication that changes nothing visible.
        val paging = MainCost()
        val composedBeforePaging = rowsComposed()
        var pages = 0
        val pagingStarted = System.nanoTime()
        val olderRow = hasTestTag("load-older") or hasTestTag("loading-older")
        runCatching { onMain(paging) { compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(olderRow); compose.waitForIdle() } }
        while (pages < 60) {
            val state = graph.conversations.state(agentId).value
            if (!state.hasOlder && !state.isLoadingOlder) break
            val before = state.items.size
            graph.conversations.loadOlder(agentId)
            onMain(paging) {
                compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { s -> !s.isLoadingOlder && (s.items.size > before || !s.hasOlder) } }
                compose.waitForIdle()
            }
            pages++
        }
        onMain(paging) {
            compose.waitUntil(120_000) { graph.conversations.state(agentId).value.traceStatus.pending == 0 }
            compose.waitForIdle()
        }
        val pagingMs = (System.nanoTime() - pagingStarted) / 1_000_000
        val whole = graph.conversations.state(agentId).value
        report("paging.pages", pages)
        report("paging.wallMs", pagingMs)
        report("paging.main", paging)
        report("paging.rowsComposed", rowsComposed() - composedBeforePaging)
        report("paging.items", "items=${whole.items.size} prompts=${whole.items.count { it is com.cursorforandroid.domain.UserMessage }}")
        assertThat(whole.items.count { it is com.cursorforandroid.domain.UserMessage }).isEqualTo(runs)

        // Back at the newest turn, a burst of live deltas on the whole 300-turn chat: the reply of the newest turn
        // written a word at a time, a tool call between — every delta a publication over the whole transcript.
        onMain(paging) { compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(newestIndex()); compose.waitForIdle() }
        val burst = MainCost()
        val composedBeforeBurst = rowsComposed()
        val live = "run-$runs"
        val burstStarted = System.nanoTime()
        repeat(60) { i ->
            runBlocking {
                streamer.emit(live, RunStreamEvent.Assistant("word $i "))
                if (i % 10 == 9) streamer.emit(live, readCall(live, 2 + i / 10))
            }
            Thread.sleep(20)
            onMain(burst) { compose.waitForIdle() }
        }
        onMain(burst) {
            compose.waitUntil(30_000) { graph.conversations.state(agentId).value.items.any { it is com.cursorforandroid.domain.AssistantMessage && it.markdown.contains("word 59") } }
            compose.waitForIdle()
        }
        report("burst.wallMs", (System.nanoTime() - burstStarted) / 1_000_000)
        report("burst.main", burst)
        report("burst.rowsComposed", rowsComposed() - composedBeforeBurst)

        // A scroll through the whole chat, half a screen at a time, from the newest row to the oldest: the same rows
        // composed on both sides of the change, so the cost is the rows' own.
        val scroll = MainCost()
        val composedBeforeScroll = rowsComposed()
        val newest = newestIndex()
        var index = newest
        var steps = 0
        val scrollStarted = System.nanoTime()
        while (steps < 2_000 && index >= 0) {
            val ok = runCatching { onMain(scroll) { compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(index); compose.waitForIdle() } }.isSuccess
            if (!ok) break
            index -= 4
            steps++
        }
        val scrollMs = (System.nanoTime() - scrollStarted) / 1_000_000
        report("scroll.steps", "$steps steps of 4 rows, ${newest - index - 4} rows deep")
        report("scroll.wallMs", scrollMs)
        report("scroll.main", scroll)
        report("scroll.rowsComposed", rowsComposed() - composedBeforeScroll)
        report("scroll.mainPerStepMs", "%.2f".format(scroll.cpuMs / maxOf(steps, 1)))
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        report("heapMb", "%.1f".format((runtime.totalMemory() - runtime.freeMemory()) / 1048576.0))
        report("perf", TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.render()?.replace("\n", " || "))
        assertThat(whole.items.count { it is com.cursorforandroid.domain.UserMessage }).isEqualTo(runs)
    }
}
