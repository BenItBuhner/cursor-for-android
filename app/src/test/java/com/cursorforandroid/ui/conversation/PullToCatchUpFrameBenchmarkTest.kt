package com.cursorforandroid.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * What a frame of the pull to catch up costs, beside the two things it should feel like: the chat's own scroll and
 * the sidebar's pull to refresh. The real conversation screen over a 40-turn chat (the whole pipeline, the fakes in
 * the demo's seat) and the real sidebar sit side by side, and three strokes are driven through them a frame at a
 * time, interleaved pass by pass: the chat pulled up past its newest message and back, the chat scrolled down and
 * back, the sidebar pulled down past its threshold and back. Every stroke crosses its threshold both ways, so the
 * arrow's fade is in all of them.
 *
 * Counted per frame, beside its time: the scopes recomposed, anywhere in the window (a [CompositionObserver] on the
 * root composition, which its subcompositions — the transcript's rows among them — inherit), and the transcript's
 * rows composed (see [TranscriptPerf]). The pull is drawn by the indicator's layer, and the transcript's stretch by
 * the platform's: no frame of it recomposes a row, and it recomposes on no more frames than the sidebar's own pull.
 * Its frames are held to the chat's scroll, frame for frame, with the budget widened by what the machine cannot
 * repeat of its own measurement (see KeyboardFrameBenchmarkTest). Then an armed pull is let go and the frames of the
 * catch-up are counted to the word: the spring, the spin, the answer and the spring home recompose no row either.
 *
 * Robolectric composes, measures, lays out and records on the JVM and draws nothing on a GPU: these are the main
 * thread's costs, the JVM's numbers rather than a phone's. Every number is printed as a `BENCHMARK pull` line.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w900dp-h914dp-night-420dpi")
class PullToCatchUpFrameBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
    private val agentId = "bc-pull-perf"
    private val turns = 40

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    /**
     * Every scope recomposed in the window. A scope invalidated by a derived state whose value did not change (the
     * arrow's fade reads one) is handed to the composition and skipped there: counted by its own observer, only the
     * scopes that ran are.
     */
    private class Recompositions : CompositionObserver, RecomposeScopeObserver {
        var scopes = 0
        private val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposeScope, Boolean>())

        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }

        override fun onEndComposition(composition: Composition) = Unit

        override fun onBeginScopeComposition(scope: RecomposeScope) {
            scopes++
        }

        override fun onEndScopeComposition(scope: RecomposeScope) = Unit

        override fun onScopeDisposed(scope: RecomposeScope) {
            observed.remove(scope)
        }
    }

    private val recompositions = Recompositions()

    /** One stroke's frames: each one's time, and the scopes it recomposed. */
    private class Stroke(val label: String) {
        val millis = mutableListOf<Double>()
        val scopes = mutableListOf<Int>()
        var rowsComposed = 0
        val recomposedFrames get() = scopes.count { it > 0 }
        val median get() = millis.median()
        override fun toString() =
            "$label: frames=${millis.size} median=${"%.2f".format(median)}ms p90=${"%.2f".format(millis.p90())}ms max=${"%.2f".format(millis.max())}ms " +
                "recomposedFrames=$recomposedFrames scopes=${scopes.sum()} rowsComposed=$rowsComposed"
    }

    private fun readCall(runId: String, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("x".repeat(2_000))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    private fun seed() = runBlocking {
        val prompts = Array(turns) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten the ${it + 1}th module and list the files touched.", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Long chat", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        for (i in 1..turns) {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $i: reading the module before changing it."))
            repeat(3) { n -> streamer.emit(runId, readCall(runId, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i\n\nTightened the module.\n\n- `File1.kt`: split the class\n- `File2.kt`: moved the helper\n\n```kotlin\nval x = $i\n```"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    /** The graph with the fakes in the demo's seat: nothing on disk, no network, the whole pipeline. */
    private fun graph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
    }

    private fun agent(n: Int) = Agent(
        id = "side-$n",
        name = "Sidebar chat $n",
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/side-$n",
        createdAtMillis = now - (n + 40) * 60_000L,
        updatedAtMillis = now - n * 60_000L,
        latestRunId = null,
        repoUrl = "https://github.com/acme/side-$n",
        startingRef = "main",
    )

    private val sidebarState = AgentListUiState(
        sections = listOf(
            AgentSection("date:Today", "Today", (1..30).map { AgentRow(agent = agent(it), indicator = AgentIndicator.Read, isPinned = false, isUnread = false, launchedFromThisDevice = false) }),
        ),
        hasLoaded = true,
        prefs = ListPreferences(),
        nowMillis = now,
    )

    private fun rowsComposed(): Int = TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.rowCompositions ?: 0

    /** One frame, with the clock held: [input] lands, and the frame it causes is composed, laid out and recorded. */
    private fun frame(target: SemanticsNodeInteraction, into: Stroke, input: TouchInjectionScope.() -> Unit) {
        val scopes = recompositions.scopes
        val started = System.nanoTime()
        target.performTouchInput(input)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        into.millis += (System.nanoTime() - started) / 1_000_000.0
        into.scopes += recompositions.scopes - scopes
    }

    /** Frames with nothing moving, the main looper's clock with them: what the finger let go of comes to rest. */
    private fun rest(frames: Int = 90) = repeat(frames) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.waitForIdle()
    }

    /**
     * A stroke: down at [start], past the touch slop in [direction] (+1 down the screen, -1 up it), then [STEPS] frames
     * of [STEP] px that way and as many back, held still before it is let go so nothing flings.
     */
    private fun stroke(target: SemanticsNodeInteraction, label: String, start: TouchInjectionScope.() -> Offset, direction: Float): Stroke {
        val stroke = Stroke(label)
        val rows = rowsComposed()
        target.performTouchInput { down(start()); moveBy(Offset(0f, direction * (viewConfiguration.touchSlop + 1f))) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        repeat(STEPS) { frame(target, stroke) { moveBy(Offset(0f, direction * STEP)) } }
        repeat(STEPS) { frame(target, stroke) { moveBy(Offset(0f, -direction * STEP)) } }
        target.performTouchInput { advanceEventTime(200); up() }
        stroke.rowsComposed = rowsComposed() - rows
        rest()
        return stroke
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the pull to catch up, frame by frame, beside the chat's scroll and the sidebar's pull to refresh`() {
        seed()
        val graph = graph()
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Row {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("sidebar")) {
                            Sidebar(
                                state = sidebarState,
                                user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
                                isDemo = false,
                                selectedAgentId = null,
                                selectedDestination = null,
                                onQueryChange = {},
                                callbacks = SidebarCallbacks(
                                    onNewChat = {},
                                    onSettings = {},
                                    onCustomize = {},
                                    onToggleSidebar = {},
                                    onRefresh = {},
                                    rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                                    onNewProject = {},
                                ),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) { ConversationScreen(graph, agentId, onBack = {}) }
                    }
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Prompt $turns", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Sidebar chat 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        val transcript = compose.onNodeWithTag("transcript")
        val sidebar = compose.onNodeWithTag("sidebar")
        fun pull() = stroke(transcript, "chat pull up", { Offset(centerX, bottom - 60f) }, direction = -1f)
        // The scroll comes back to within its touch slop of the newest row: the rest of the way home is unmeasured, so the
        // list reaching its end (the jump button going) is not the next pull's.
        fun scroll() = stroke(transcript, "chat scroll", { Offset(centerX, centerY) }, direction = 1f).also {
            transcript.performTouchInput { down(Offset(centerX, centerY)); moveBy(Offset(0f, -(viewConfiguration.touchSlop + 24f))); advanceEventTime(200); up() }
            rest()
        }
        fun refresh() = stroke(sidebar, "sidebar pull down", { Offset(centerX, top + 400f) }, direction = 1f)

        // The first round thrown away, for the JIT; then the three strokes pass by pass, each beside the others in time.
        pull(); scroll(); refresh()
        val passes = (1..PASSES).map { Triple(pull(), scroll(), refresh()) }
        val pulls = passes.map { it.first }
        val scrolls = passes.map { it.second }
        val refreshes = passes.map { it.third }

        // Let go armed: the spring to the threshold, the spin through the catch-up, the answer, the spring home, the word.
        val settle = Stroke("armed pull let go, to the word")
        val rowsBeforeSettle = rowsComposed()
        transcript.performTouchInput { down(Offset(centerX, bottom - 60f)); moveBy(Offset(0f, -(viewConfiguration.touchSlop + 1f))) }
        repeat(STEPS) { frame(transcript, settle) { moveBy(Offset(0f, -STEP)) } }
        frame(transcript, settle) { up() }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && compose.onAllNodes(hasText("Up to date")).fetchSemanticsNodes().isEmpty()) {
            val scopes = recompositions.scopes
            val started = System.nanoTime()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
            settle.millis += (System.nanoTime() - started) / 1_000_000.0
            settle.scopes += recompositions.scopes - scopes
            Thread.sleep(2)
        }
        settle.rowsComposed = rowsComposed() - rowsBeforeSettle
        val told = compose.onAllNodes(hasText("Up to date")).fetchSemanticsNodes().isNotEmpty()

        val pairRatios = passes.map { (pull, scroll, _) -> pull.median / scroll.median }
        val sidebarRatios = passes.map { (pull, _, refresh) -> pull.median / refresh.median }
        val noise = scrolls.map { it.median }.zipWithNext { a, b -> maxOf(a, b) / minOf(a, b) }.median()
        val budget = BUDGET * maxOf(1.0, noise)
        println("BENCHMARK pull turns=$turns steps=$STEPS×${STEP}px each way passes=$PASSES")
        passes.forEachIndexed { i, (pull, scroll, refresh) -> listOf(pull, scroll, refresh).forEach { println("BENCHMARK pull pass ${i + 1} $it") } }
        println("BENCHMARK pull pooled chat pull up: median=${"%.2f".format(pulls.flatMap { it.millis }.median())}ms p90=${"%.2f".format(pulls.flatMap { it.millis }.p90())}ms")
        println("BENCHMARK pull pooled chat scroll: median=${"%.2f".format(scrolls.flatMap { it.millis }.median())}ms p90=${"%.2f".format(scrolls.flatMap { it.millis }.p90())}ms")
        println("BENCHMARK pull pooled sidebar pull down: median=${"%.2f".format(refreshes.flatMap { it.millis }.median())}ms p90=${"%.2f".format(refreshes.flatMap { it.millis }.p90())}ms")
        println("BENCHMARK pull chat pull / chat scroll per pass: ${pairRatios.joinToString(" ") { "%.2f".format(it) }} median=${"%.2f".format(pairRatios.median())} (budget ${"%.2f".format(budget)}×, scroll pass-to-pass noise ${"%.2f".format(noise)})")
        println("BENCHMARK pull chat pull / sidebar pull per pass: ${sidebarRatios.joinToString(" ") { "%.2f".format(it) }} median=${"%.2f".format(sidebarRatios.median())}")
        println("BENCHMARK pull $settle told=$told")

        // No frame of the pull recomposes a row of the transcript, and it recomposes no more than the sidebar's: the
        // arrow's fade as it crosses the threshold, each way.
        assertWithMessage("rows composed by the pulls: ${pulls.map { it.rowsComposed }}").that(pulls.sumOf { it.rowsComposed }).isEqualTo(0)
        pulls.zip(refreshes).forEach { (pull, refresh) ->
            assertWithMessage("$pull\n$refresh").that(pull.recomposedFrames).isAtMost(refresh.recomposedFrames)
            assertWithMessage("$pull\n$refresh").that(pull.scopes.sum()).isAtMost(refresh.scopes.sum())
        }
        // Frame for frame, the pull costs no more than the chat's own scroll.
        assertWithMessage("chat pull / chat scroll per pass: $pairRatios").that(pairRatios.median()).isAtMost(budget)
        // And let go armed, it is answered and told with no row recomposed on the way.
        assertThat(told).isTrue()
        assertWithMessage(settle.toString()).that(settle.rowsComposed).isEqualTo(0)
    }

    private companion object {
        /** Frames each way in a stroke, and the finger's travel per frame: 40 × 12 px carries both pulls past their thresholds. */
        const val STEPS = 40
        const val STEP = 12f
        const val PASSES = 5
        /** The pull's median frame may be this many times the scroll's, on a machine that repeats its own measurement. */
        const val BUDGET = 1.5

        fun List<Double>.median(): Double = sorted()[size / 2]
        fun List<Double>.p90(): Double = sorted()[(size * 0.9).toInt().coerceAtMost(lastIndex)]
    }
}
