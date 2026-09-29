package com.cursorforandroid.ui.projects

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * A Big Project open in the panel while its workers stream (Bennett's report, 2026-09-28: a Project with many running
 * agents lags on a Galaxy S26 Ultra when opened and scrolled). A fleet of 200 or 500 agents, a Project of 120 or 150 of
 * them, 40 or 60 running, each running one moving once a second: the real [projectPanelItems] over the whole pipeline
 * (the fakes in the demo's seat), driven a frame at a time with the frame clock held and the wall clock moving a
 * frame's worth, each frame measured. Counted: the Project view's derivations and where they ran, the rows built, the
 * scopes recomposed and the [WorkerRow] bodies run — per publication of a worker and per frame of a scroll — and the
 * frames from opening the panel to its first primary.
 *
 * Robolectric composes and lays out on the JVM and draws nothing on a GPU: the times are this machine's main thread,
 * not a phone's frames. The assertions hold what does not depend on the machine. Every number is a `SCALE project` line.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectFleetBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val start = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()

    @Volatile
    private var clockMs = start

    private class Fleet(val label: String, val total: Int, val children: Int, val running: Int) {
        fun isRunning(i: Int) = (i * running) % children < running
    }

    @Before
    fun setUp() {
        clockMs = start
        AppClock.nowMillis = { clockMs }
        RecomposeCounter.install()
        RecomposeCounter.reset()
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        RecomposeCounter.uninstall()
    }

    private class Recompositions : CompositionObserver, RecomposeScopeObserver {
        @Volatile var scopes = 0
        private val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposeScope, Boolean>())
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) { scopes++ }
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }
    }

    /** The main thread's CPU time and allocations, through the JVM's management beans (the test compiles against the Android SDK). */
    private object MainThread {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val cpu = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()
        private val alloc = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes") }.getOrNull()
        fun cpuNanos(): Long = runCatching { cpu?.invoke(bean) as? Long }.getOrNull() ?: 0L
        fun allocatedBytes(): Long = runCatching { alloc?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    /** The Project view's derivations, on the main thread and off it, told by the repository's probe. */
    private class Derivations {
        val onMain = AtomicLong()
        val onMainNanos = AtomicLong()
        val offMain = AtomicLong()
        fun record(nanos: Long) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onMain.incrementAndGet()
                onMainNanos.addAndGet(nanos)
            } else {
                offMain.incrementAndGet()
            }
        }
    }

    private class Phase(val fleet: String, val label: String) {
        val wall = mutableListOf<Double>()
        val cpu = mutableListOf<Double>()
        var allocated = 0L
        var scopes = 0
        var workerRows = 0
        var rowsBuilt = 0L
        var mainDerivations = 0L
        var mainDerivationNanos = 0L
        var offMainDerivations = 0L
        var patches = 0
        override fun toString() = "SCALE project fleet=$fleet phase=$label frames=${wall.size} patches=$patches " +
            "wall median=${f(wall.pct(0.5))} p95=${f(wall.pct(0.95))} max=${f(wall.maxOrNull() ?: 0.0)} total=${f(wall.sum())}ms | " +
            "cpu median=${f(cpu.pct(0.5))} p95=${f(cpu.pct(0.95))} total=${f(cpu.sum())}ms | " +
            "main/frame incl. view=${f(if (wall.isEmpty()) 0.0 else (cpu.sum() + mainDerivationNanos / 1e6) / wall.size)}ms | alloc=${f(allocated / 1048576.0)}MB | " +
            "scopes=$scopes (${per(scopes)}/patch) workerRowBodies=$workerRows (${per(workerRows)}/patch) rowsBuilt=$rowsBuilt | " +
            "viewDerivations main=$mainDerivations (${f(mainDerivationNanos / 1e6)}ms, ${f(if (mainDerivations == 0L) 0.0 else mainDerivationNanos / 1e6 / mainDerivations)}ms each) offMain=$offMainDerivations"
        private fun per(n: Int) = if (patches == 0) "-" else f(n.toDouble() / patches)
        companion object {
            fun f(v: Double) = "%.2f".format(v)
            fun List<Double>.pct(p: Double): Double = if (isEmpty()) 0.0 else sorted()[(size * p).toInt().coerceAtMost(size - 1)]
        }
    }

    private lateinit var graph: AppGraph
    private val recompositions = Recompositions()
    private val derivations = Derivations()

    /** The panel's own view model, taken from the composition that made it. */
    private var viewModel: ProjectViewModel? = null

    private fun rowsBuilt(): Long = viewModel?.rowsBuilt ?: 0L

    /** Everything counted, at one moment. */
    private data class Counts(val scopes: Int, val workerRows: Int, val rowsBuilt: Long, val onMain: Long, val onMainNanos: Long, val offMain: Long)

    private fun counts() = Counts(
        recompositions.scopes, RecomposeCounter.count("WorkerRow"), rowsBuilt(),
        derivations.onMain.get(), derivations.onMainNanos.get(), derivations.offMain.get(),
    )

    /**
     * [block] as [phase]: what it counted, whatever thread counted it (the view is derived off the main thread, between
     * frames), and the main thread's time and allocations over the frames it [measured].
     */
    private fun phase(phase: Phase, block: (Phase) -> Unit): Phase {
        val before = counts()
        block(phase)
        val after = counts()
        phase.scopes = after.scopes - before.scopes
        phase.workerRows = after.workerRows - before.workerRows
        phase.rowsBuilt = after.rowsBuilt - before.rowsBuilt
        phase.mainDerivations = after.onMain - before.onMain
        phase.mainDerivationNanos = after.onMainNanos - before.onMainNanos
        phase.offMainDerivations = after.offMain - before.offMain
        return phase
    }

    /** [action]'s main-thread time and allocations, as one frame of [phase]. */
    private fun measured(phase: Phase, action: () -> Unit) {
        val alloc0 = MainThread.allocatedBytes()
        val cpu0 = MainThread.cpuNanos()
        val t0 = System.nanoTime()
        action()
        phase.wall += (System.nanoTime() - t0) / 1e6
        phase.cpu += (MainThread.cpuNanos() - cpu0) / 1e6
        phase.allocated += MainThread.allocatedBytes() - alloc0
    }

    /** One frame: the wall clock a frame on, the main looper's pending work, a frame of the held frame clock, and what that recomposes. */
    private fun frame() {
        clockMs += FRAME_MS
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settle(frames: Int) = repeat(frames) {
        Thread.sleep(2)
        frame()
    }

    private fun seed(fleet: Fleet): AppGraph {
        val api = FakeCursorApi()
        val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
        api.addIdleAgent(ROOT, "Big Project", "run-root", createdAt = iso(start - 7_200_000L))
        repeat(fleet.children) { i ->
            val at = iso(start - 3_600_000L - i * 1_000L)
            if (fleet.isRunning(i)) api.addRunningAgent(worker(i), "Worker $i", "run-w$i", createdAt = at) else api.addIdleAgent(worker(i), "Worker $i", "run-w$i", createdAt = at)
        }
        repeat(fleet.total - fleet.children - 1) { api.addIdleAgent("bc-o$it", "Other $it", "run-o$it", createdAt = iso(start - 60_000L - it * 60_000L)) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, FakeRunStreamer(replay = 64), isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        runBlocking { while (graph.agents.state.value.hasMore) graph.agents.loadMore() }
        graph.agents.patch(ROOT) { it.copy(isProject = true) }
        graph.agents.applyLineage(ROOT, (0 until fleet.children).associate { worker(it) to AgentParentKind.PROJECT_WORKER }, LineageSignal.ACTION)
        val list = graph.agents.state.value.agents
        assertThat(list).hasSize(fleet.total)
        assertThat(list.count { it.parent?.id == ROOT }).isEqualTo(fleet.children)
        assertThat(list.count { it.parent?.id == ROOT && it.isRunning }).isEqualTo(fleet.running)
        graph.projects.onViewDerived = derivations::record
        return graph
    }

    /** Each running worker moves once a second, spread over the second's frames: the patches due on frame [n]. */
    private fun due(fleet: Fleet, n: Int): List<String> {
        val running = (0 until fleet.children).filter(fleet::isRunning)
        val slot = n % FRAMES_PER_SECOND
        return running.filterIndexed { index, _ -> index * FRAMES_PER_SECOND / running.size == slot }.map(::worker)
    }

    private fun tick(phase: Phase, fleet: Fleet, n: Int) {
        for (id in due(fleet, n)) {
            graph.agents.patch(id) { it.copy(updatedAtMillis = clockMs, summary = "Step ${n / FRAMES_PER_SECOND} of $id") }
            phase.patches++
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun scenario(fleet: Fleet, print: Boolean): List<Phase> {
        graph = seed(fleet)
        compose.mainClock.autoAdvance = false

        // Open: from the panel's first composition to the first frame that shows a primary.
        val open = phase(Phase(fleet.label, "open")) {
            measured(it) {
                compose.setContent {
                    val root = currentComposer.composition
                    remember(root) { root.observe(recompositions) }
                    CursorTheme(mode = ThemeMode.Dark) {
                        CompositionLocalProvider(LocalRippleConfiguration provides null) {
                            val items = projectPanelItems(graph, ROOT, onOpenAgent = {}, onNotify = {})
                            viewModel = viewModel(key = "project-panel-$ROOT", factory = ProjectViewModel.Factory(graph, ROOT))
                            LazyColumn(Modifier.fillMaxSize().testTag(LIST)) { items() }
                        }
                    }
                }
            }
            var waited = 0
            while (compose.onAllNodes(hasTestTag("project-primary")).fetchSemanticsNodes().isEmpty() && waited++ < 240) {
                Thread.sleep(2)
                measured(it) { frame() }
            }
        }
        assertThat(compose.onAllNodes(hasTestTag("project-primary")).fetchSemanticsNodes()).isNotEmpty()
        settle(60)

        // Ticking: every running worker moves once a second, the panel at rest.
        var n = 0
        val ticking = phase(Phase(fleet.label, "tick")) {
            repeat(TICK_SECONDS * FRAMES_PER_SECOND) { _ ->
                tick(it, fleet, n++)
                Thread.sleep(STREAM_PACE_MS)
                measured(it) { frame() }
            }
            settle(10)
        }

        // Scrolling the primaries, a finger's drag a frame, while they keep moving.
        val list = compose.onNodeWithTag(LIST)
        val scroll = phase(Phase(fleet.label, "scroll")) {
            list.performTouchInput { down(Offset(centerX, centerY + 300f)); moveBy(Offset(0f, -(viewConfiguration.touchSlop + 1f))) }
            repeat(SCROLL_FRAMES) { _ ->
                tick(it, fleet, n++)
                Thread.sleep(STREAM_PACE_MS)
                measured(it) {
                    list.performTouchInput { moveBy(Offset(0f, -SCROLL_STEP_PX)) }
                    frame()
                }
            }
            list.performTouchInput { advanceEventTime(200); up() }
            settle(10)
        }

        val phases = listOf(open, ticking, scroll)
        if (print) {
            phases.forEach { println(it) }
            println("SCALE project fleet=${fleet.label} phase=open framesToFirstPrimary=${open.wall.size - 1} openWall=${Phase.f(open.wall.sum())}ms")
        }
        return phases
    }

    private fun assertBudgets(phases: List<Phase>) {
        val (open, ticking, scroll) = phases
        // The view is derived off the main thread: once on it for the panel's first frame, never while the workers move.
        assertWithMessage("$open").that(open.mainDerivations).isAtMost(1)
        assertWithMessage("$ticking").that(ticking.mainDerivations).isEqualTo(0)
        assertWithMessage("$scroll").that(scroll.mainDerivations).isEqualTo(0)
        // A worker's move recomposes its own row, if it is composed at all, not every row on screen.
        assertWithMessage("$ticking").that(ticking.workerRows).isAtMost(ticking.patches + ROW_SLACK)
        // Nor the list's item scopes around the rows: a move leaves the rows' shape as it was, so the list is not laid
        // out again and no other row's item recomposes (about 21 scopes a move while it was).
        assertWithMessage("$ticking").that(ticking.scopes).isAtMost(ticking.patches * SCOPES_PER_MOVE)
        // A worker's move rebuilds its own row; the rest are kept.
        assertWithMessage("$ticking").that(ticking.rowsBuilt).isAtMost(ticking.patches.toLong() + ROW_SLACK)
        // The first primary is on the panel's first frames, not a round trip later.
        assertWithMessage("$open").that(open.wall.size - 1).isAtMost(2)
    }

    /** One pass unmeasured, so the measured ones run on code the JIT has compiled. */
    @Test
    fun `1 warm-up pass`() {
        scenario(Fleet("warm-up", total = 200, children = 120, running = 40), print = false)
    }

    @Test
    fun `2 a fleet of 200, a Project of 120 with 40 running`() {
        assertBudgets(scenario(Fleet("S200", total = 200, children = 120, running = 40), print = true))
    }

    @Test
    fun `3 a fleet of 500, a Project of 150 with 60 running`() {
        assertBudgets(scenario(Fleet("S500", total = 500, children = 150, running = 60), print = true))
    }

    private companion object {
        const val ROOT = "bc-big-project"
        const val LIST = "project-list"
        const val FRAME_MS = 16L
        const val FRAMES_PER_SECOND = 60
        const val TICK_SECONDS = 3
        const val SCROLL_FRAMES = 90
        const val SCROLL_STEP_PX = 40f
        /** Wall time between two streamed frames, so the pipeline behind the panel publishes at a phone's pace rather than the test's. */
        const val STREAM_PACE_MS = 14L
        /** Rows over one per move: a row that scrolls in, a line whose run ends. */
        const val ROW_SLACK = 8
        /** Scopes recomposed per move at most, on average: the moved row's own, when it is on screen, and what it draws. */
        const val SCOPES_PER_MOVE = 2
        fun worker(i: Int) = "bc-w$i"
    }
}
