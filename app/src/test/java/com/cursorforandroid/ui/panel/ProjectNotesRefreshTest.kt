package com.cursorforandroid.ui.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.faults.FaultServer.AgentStore
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.faults.FaultServer.StoreFile
import com.cursorforandroid.data.repo.AgentStoreRepository
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
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

/**
 * The Project tab over a minute of a coordinator at work, at 300–900 ms a round trip: it rewrites `notes.md`, the
 * panel is shut and opened again, the run ends and the notes are written once more. The panel's notes, trees and
 * Recents follow the coordinator's row as the poll keeps it current, and a reopened panel reads them again; a
 * refresh of what is on screen never shows a loading row, and text that did not change is neither read nor parsed
 * again. The real [ContextReads] over the real [AgentStoreRepository] against [FaultServer]'s stores, drawn by the
 * real [ProjectTabContent], frame by frame at wall speed.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectNotesRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private lateinit var reads: ContextReads
    private val projectId = "bc-proj-notes"
    private val runId = "run-bc-proj-notes"
    private val start = 1_800_000_000_000L
    private val projectStore = AgentStore("st-proj-notes", "AGENT_STORE_KIND_CLOUD", sourceId = projectId)
    private val userStore = AgentStore("st-user", "AGENT_STORE_KIND_USER")
    private val jobs = mutableListOf<Job>()
    /** The panel's reads on the main thread, as the view model's scope runs them. */
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shown by mutableStateOf(true)
    private val frameDir = System.getenv("PROJECT_NOTES_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)

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

    /** The coordinator's running notes: a status line at the top, then a dozen workstreams, [shipped] of them done. */
    private fun notes(shipped: Int): String = buildString {
        append("# Notes Project\n\n## Status\n\n**$shipped of 12 workstreams shipped.** The rest are with their workers.\n\n")
        for (w in 1..12) {
            append("## Workstream $w${if (w <= shipped) " — shipped" else ""}\n\n")
            append("What the coordinator knows of this part: what landed, what the worker is on, and what waits on review. ".repeat(3)).append("\n\n")
            repeat(4) { append("- [${if (w <= shipped || it < 2) "x" else " "}] Task $w.$it — [PR #${w * 10 + it}](https://github.com/acme/app/pull/${w * 10 + it})\n") }
            append("\n")
        }
    }

    @Before
    fun setUp() {
        MarkdownCache.clear()
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"), extended = true, projectPollMs = 20_000L)
        server.addRunningAgent(projectId, "Notes Project", runId, createdAt = Instant.ofEpochMilli(start - 3_600_000L).toString())
        server.composers[projectId] = Composer(projectId, "Notes Project", start - 60_000L, running = true, project = true)
        projectStore.files["notes.md"] = StoreFile(notes(3), start - 120_000L)
        projectStore.files["plans/rollout.md"] = StoreFile("# Rollout\n", start - 600_000L)
        projectStore.files["reports/week-1.md"] = StoreFile("# Week 1\n", start - 300_000L)
        userStore.files["scratch/todo.md"] = StoreFile("- [ ] Review\n", start - 900_000L)
        server.agentStores += listOf(projectStore, userStore)
        rig.now = start + 60_000L
        reads = ContextReads(projectId, AgentStoreRepository(api = rig.projectApi, capabilities = { rig.capabilities }, now = { rig.now }), readScope) { projectId }
    }

    @After
    fun tearDown() {
        readScope.cancel()
        jobs.forEach { it.cancel() }
        compose.mainClock.autoAdvance = true
        rig.close()
        server.close()
    }

    private val actions = object : PanelActions by PanelActions.None {
        override fun loadContext(force: Boolean) = reads.load(force)
        override fun refreshContext() = reads.refresh(moved = true)
        override fun toggleFolder(store: AgentStoreRef, path: String) = reads.toggleFolder(store, path)
    }

    private fun show() {
        compose.setContent {
            val composition = currentComposer.composition
            remember(composition) { recompositions.root = composition; composition.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                val list by rig.agents.state.collectAsState()
                val context by reads.state.collectAsState()
                val panel = PanelState(projectId, agent = list.agents.firstOrNull { it.id == projectId }, capabilities = Capabilities.EXTENDED, context = context)
                if (shown) ProjectTabContent(panel, actions)
            }
        }
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun loaderShown() = LoadingRows.any { count(it) > 0 }

    private class Window(val frames: Int, val loaderFrames: Int, val loaderMs: Long, val requests: Map<Route, Int>, val rootScopes: Int, val itemScopes: Int, val parses: Int, val firstSeen: Map<String, Long>) {
        override fun toString() = "frames=$frames loaderFrames=$loaderFrames loaderMs=$loaderMs parses=$parses rootScopes=$rootScopes itemScopes=$itemScopes firstSeen=$firstSeen requests=${requests.values.sum()} $requests"
    }

    private var captured = 0
    /** One frame's pixels, drawn over for each frame: a bitmap a frame is native memory the heap's collector does not see. */
    private var frame: Bitmap? = null

    private fun capture(label: String) {
        val dir = frameDir ?: return
        File(dir, label).mkdirs()
        val root = compose.activity.window.decorView
        val bitmap = frame ?: Bitmap.createBitmap(root.width, root.height / 2, Bitmap.Config.ARGB_8888).also { frame = it }
        bitmap.eraseColor(0)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File(dir, "$label/%05d.jpg".format(captured++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /**
     * Frames at wall speed for [ms]: each one idles the main looper and advances the frame clock by the wall time
     * gone since the last, runs the [script] entries now due, and notes whether a loading row is on screen and when
     * each of [watch] was first seen (ms into the window).
     */
    private fun frames(label: String, ms: Long, script: List<Pair<Long, () -> Unit>> = emptyList(), watch: List<String> = emptyList()): Window {
        val from = server.seen.size
        val root = recompositions.rootScopes
        val items = recompositions.itemScopes
        val perf = TranscriptPerf.opened("notes-refresh")
        val parsed = perf.snapshot().markdownParses
        val pending = script.sortedBy { it.first }.toMutableList()
        val seen = LinkedHashMap<String, Long>()
        val began = System.nanoTime()
        var last = began
        var frames = 0
        var loader = 0
        var loaderMs = 0L
        captured = 0
        while ((System.nanoTime() - began) / 1_000_000 < ms) {
            val target = began + (frames + 1) * FRAME_NS
            val ahead = (target - System.nanoTime()) / 1_000_000
            if (ahead > 0) Thread.sleep(ahead)
            val nowNs = System.nanoTime()
            val stepMs = ((nowNs - last) / 1_000_000).coerceAtLeast(16)
            last = nowNs
            val at = (nowNs - began) / 1_000_000
            while (pending.isNotEmpty() && pending.first().first <= at) pending.removeAt(0).second()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(stepMs))
            compose.mainClock.advanceTimeBy(stepMs)
            compose.waitForIdle()
            frames++
            if (shown && loaderShown()) {
                loader++
                loaderMs += stepMs
            }
            watch.forEach { text -> if (text !in seen && count(text) > 0) seen[text] = at }
            capture(label)
        }
        val requests = server.seen.drop(from).groupingBy { it.route }.eachCount().toSortedMap()
        return Window(frames, loader, loaderMs, requests, recompositions.rootScopes - root, recompositions.itemScopes - items, perf.snapshot().markdownParses - parsed, seen)
            .also { println("NOTES[$label] wall=${ms}ms $it") }
    }

    private fun write(path: String, text: String, store: AgentStore = projectStore) {
        store.files[path] = StoreFile(text, rig.now)
        server.composers[projectId] = server.composers.getValue(projectId).copy(activityMs = rig.now)
    }

    private fun startClock() {
        jobs += rig.scope.launch { while (true) { delay(100); rig.now += 100 } }
        // The coordinator at work: its record moves every few seconds.
        jobs += rig.scope.launch {
            while (true) {
                delay(5_000)
                server.composers[projectId]?.let { server.composers[projectId] = it.copy(activityMs = rig.now) }
            }
        }
    }

    private suspend fun open() {
        rig.agents.refresh()
        rig.awaitUntil(30_000) { rig.agents.agent(projectId)?.looksLikeProject == true }
        show()
    }

    @Test
    fun `the notes follow the coordinator on the poll and on reopen, never behind a loading row`() = runBlocking<Unit> {
        open()
        compose.waitUntil(30_000) { count("3 of 12 workstreams shipped") > 0 }
        compose.mainClock.autoAdvance = false
        startClock()
        rig.projects.attach(projectId)

        val window = frames(
            "poll",
            WINDOW_MS,
            script = listOf(
                6_000L to { write("notes.md", notes(4)) },
                30_000L to { shown = false },
                33_000L to { shown = true },
                40_000L to { write("notes.md", notes(5)) },
                46_000L to {
                    server.finish(projectId, runId, "Done.")
                    server.composers[projectId] = server.composers.getValue(projectId).copy(running = false, activityMs = rig.now)
                },
            ),
            watch = listOf("4 of 12 workstreams shipped", "5 of 12 workstreams shipped"),
        )
        rig.projects.detach(projectId)

        assertThat(window.firstSeen.keys).containsExactly("4 of 12 workstreams shipped", "5 of 12 workstreams shipped").inOrder()
        assertThat(window.loaderFrames).isEqualTo(0)
        // Each new text read once and parsed once; the unchanged one neither.
        assertThat(window.requests[Route.StoreRead]).isEqualTo(2)
        assertThat(window.parses).isEqualTo(2)
    }

    @Test
    fun `all files and recents pick up a new file without their loading rows`() = runBlocking<Unit> {
        reads.state.update { it.copy(allFiles = true) }
        open()
        compose.waitUntil(30_000) { count("week-1.md") > 0 && count("todo.md") > 0 }
        compose.mainClock.autoAdvance = false
        startClock()
        rig.projects.attach(projectId)

        val window = frames(
            "files",
            FILES_WINDOW_MS,
            script = listOf(3_000L to { write("handoff.md", "# Handoff\n") }),
            watch = listOf("handoff.md"),
        )
        rig.projects.detach(projectId)

        assertThat(window.firstSeen.keys).contains("handoff.md")
        assertThat(window.loaderFrames).isEqualTo(0)
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val FILES_WINDOW_MS = 30_000L
        const val FRAME_NS = 16_666_667L
        val LoadingRows = listOf("Reading the Project's notes…", "Listing the stores…", "Finding the newest files…")
    }
}
