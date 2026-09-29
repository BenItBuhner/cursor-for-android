package com.cursorforandroid.ui.projects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.data.repo.RootScanRecord
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Primaries spinner over a minute of an open Project whose coordinator is at work, at 300–900 ms a round trip:
 * the poll re-reads the memberships whenever the coordinator's record moves, and says nothing about it on screen;
 * a read asked for by hand shows the spinner until it is answered. The rig's own [FaultRig.projects] at
 * production's 20 s interval, its view drawn by the real [projectSection], frame by frame at wall speed.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectSpinnerPollTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val projectId = "bc-proj-spin"
    private val start = 1_800_000_000_000L
    private val jobs = mutableListOf<Job>()
    private val emissions = AtomicInteger()
    private val frameDir = System.getenv("PROJECT_SPINNER_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)

    private class Recompositions : CompositionObserver {
        var root: Composition? = null
        var rootScopes = 0
        var itemScopes = 0
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            if (composition === root) rootScopes += invalidationMap.size else itemScopes += invalidationMap.size
        }
        override fun onEndComposition(composition: Composition) = Unit
    }

    private val recompositions = Recompositions()

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"), extended = true, projectPollMs = 20_000L)
        fun created(ms: Long) = Instant.ofEpochMilli(ms).toString()
        server.addIdleAgent(projectId, "Spinner Project", "run-$projectId", createdAt = created(start - 3_600_000L))
        server.composers[projectId] = Composer(projectId, "Spinner Project", start - 3_600_000L, project = true)
        server.workers[projectId] = (1..12).map { "$projectId-w$it" to "MANAGER_SPAWN_KIND_CREATED" }
        (1..12).forEach { w ->
            val wid = "$projectId-w$w"
            val at = start - 3_600_000L - w * 60_000L
            server.addIdleAgent(wid, "Worker $w", "run-$wid", createdAt = created(at))
            server.composers[wid] = Composer(wid, "Worker $w", at, manager = projectId)
        }
        repeat(20) { i ->
            val id = "bc-own-${i + 1}"
            val at = start - i * 6 * 3_600_000L - 30_000L
            server.addIdleAgent(id, "Own ${i + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Own ${i + 1}", at)
        }
        server.pageSize = 50
        rig.now = start + 60_000L
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        compose.mainClock.autoAdvance = true
        rig.close()
        server.close()
    }

    private class Window(val frames: Int, val spinnerFrames: Int, val spinnerMs: Long, val requests: Map<Route, Int>, val emissions: Int, val rootScopes: Int, val itemScopes: Int) {
        override fun toString() = "frames=$frames spinnerFrames=$spinnerFrames spinnerMs=$spinnerMs emissions=$emissions rootScopes=$rootScopes itemScopes=$itemScopes requests=${requests.values.sum()} $requests"
    }

    private fun spinnerShown() = compose.onAllNodes(hasTestTag("project-syncing")).fetchSemanticsNodes().isNotEmpty()

    private var captured = 0

    private fun capture(label: String) {
        val dir = frameDir ?: return
        File(dir, label).mkdirs()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height / 2, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File(dir, "$label/%05d.jpg".format(captured++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /**
     * Frames at wall speed for [ms] (or until [until]): each one idles the main looper and advances the frame clock
     * by the wall time gone since the last, then notes whether the spinner is on screen.
     */
    private fun frames(label: String, ms: Long, until: () -> Boolean = { false }): Window {
        val from = server.seen.size
        val emitted = emissions.get()
        val root = recompositions.rootScopes
        val items = recompositions.itemScopes
        val began = System.nanoTime()
        var last = began
        var frames = 0
        var spinner = 0
        var spinnerMs = 0L
        captured = 0
        while ((System.nanoTime() - began) / 1_000_000 < ms && !until()) {
            val target = began + (frames + 1) * FRAME_NS
            val ahead = (target - System.nanoTime()) / 1_000_000
            if (ahead > 0) Thread.sleep(ahead)
            val nowNs = System.nanoTime()
            val stepMs = ((nowNs - last) / 1_000_000).coerceAtLeast(16)
            last = nowNs
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            compose.mainClock.advanceTimeBy(stepMs)
            compose.waitForIdle()
            frames++
            if (spinnerShown()) {
                spinner++
                spinnerMs += stepMs
            }
            capture(label)
        }
        val requests = server.seen.drop(from).groupingBy { it.route }.eachCount().toSortedMap()
        return Window(frames, spinner, spinnerMs, requests, emissions.get() - emitted, recompositions.rootScopes - root, recompositions.itemScopes - items)
            .also { println("SPINNER[$label] wall=${ms}ms $it") }
    }

    @Test
    fun `background polls refresh the primaries silently, a refresh by hand shows the spinner`() = runBlocking<Unit> {
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        rig.awaitUntil(30_000) { rig.projects.syncRecords()[projectId] != null && !rig.projects.syncingLineage.value }
        jobs += rig.scope.launch { while (true) { delay(100); rig.now += 100 } }
        // The coordinator at work: its record moves every few seconds, so every tick finds the memberships due.
        jobs += rig.scope.launch {
            while (true) {
                delay(5_000)
                server.composers[projectId] = server.composers.getValue(projectId).copy(activityMs = rig.now)
            }
        }
        jobs += rig.scope.launch { rig.projects.view(projectId).distinctUntilChanged().collect { emissions.incrementAndGet() } }
        val noActions = ProjectActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        compose.setContent {
            val composition = currentComposer.composition
            remember(composition) { recompositions.root = composition; composition.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                val flow = remember { rig.projects.view(projectId) }
                val state by flow.collectAsState(ProjectViewState(projectId))
                LazyColumn(Modifier.fillMaxSize()) { projectSection(state, LocalAgentState(), busy = false, actions = noActions, nowMillis = start) }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Worker 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        rig.projects.attach(projectId)

        val poll = frames("poll", POLL_WINDOW_MS)
        // The memberships were read on the poll — the record kept moving — and the section said nothing of it.
        assertThat(poll.requests[Route.Workers] ?: 0).isAtLeast(2)
        assertThat(poll.spinnerFrames).isEqualTo(0)

        val byHand = rig.scope.launch { rig.projects.refreshView(projectId) }
        val refresh = frames("by-hand", 10_000) { byHand.isCompleted }
        frames("by-hand-settle", 500)
        assertThat(refresh.spinnerFrames).isGreaterThan(0)
        assertThat(spinnerShown()).isFalse()
        rig.projects.detach(projectId)
    }

    private companion object {
        const val POLL_WINDOW_MS = 60_000L
        const val FRAME_NS = 16_666_667L
    }
}
