package com.cursorforandroid.ui.projects

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * The panel's Project section recomposes for what it shows, not for every change to the agent list: the real
 * [projectPanelItems] over the demo graph, a Project of [WORKERS] primaries among [OTHERS] unrelated chats.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectSectionRecompositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()

    /**
     * The scopes invalidated for a recomposition, however often each has been before: in the section's own composition
     * ([rootScopes]) and in the list items' ([itemScopes], one entry per item recomposition).
     */
    private class Recompositions : CompositionObserver {
        var root: Composition? = null
        var rootScopes = 0
        val itemScopes = mutableListOf<Int>()
        val scopes get() = rootScopes + itemScopes.sum()
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            if (invalidationMap.isEmpty()) return
            if (composition === root) rootScopes += invalidationMap.size else itemScopes += invalidationMap.size
        }
        override fun onEndComposition(composition: Composition) = Unit
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun frames(n: Int = 10) = repeat(n) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun project(workerAgeMillis: Long): AppGraph {
        AppClock.nowMillis = { now }
        val api = FakeCursorApi()
        val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
        api.addIdleAgent(ROOT, "Test Project", "run-root", createdAt = iso(now - 7_200_000L))
        repeat(WORKERS) { api.addIdleAgent("bc-w$it", "Worker $it", "run-w$it", createdAt = iso(now - workerAgeMillis - it * 1_000L)) }
        repeat(OTHERS) { api.addIdleAgent("bc-o$it", "Other $it", "run-o$it", createdAt = iso(now - 60_000L - it * 60_000L)) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, FakeRunStreamer(replay = 64), isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        runBlocking { while (graph.agents.state.value.hasMore) graph.agents.loadMore() }
        graph.agents.patch(ROOT) { it.copy(isProject = true) }
        graph.agents.applyLineage(ROOT, (0 until WORKERS).associate { "bc-w$it" to AgentParentKind.PROJECT_WORKER }, LineageSignal.ACTION)
        return graph
    }

    private fun show(graph: AppGraph): Recompositions {
        val recompositions = Recompositions()
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { recompositions.root = root; root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                val items = projectPanelItems(graph, ROOT, onOpenAgent = {}, onNotify = {})
                LazyColumn(Modifier.fillMaxSize()) { items() }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Worker 0")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        frames(30)
        return recompositions
    }

    @Test
    fun `an unrelated chat's change recomposes nothing, a primary's shows`() {
        val graph = project(workerAgeMillis = 3_600_000L)
        val recompositions = show(graph)

        // The view is derived off the main thread, so what a change does lands a moment later: time for it to arrive
        // before the frames are counted, and for a change the section shows, frames until it has.
        fun scopesFor(shown: String? = null, change: () -> Unit): Int {
            val before = recompositions.scopes
            change()
            Thread.sleep(100)
            frames()
            if (shown != null) repeat(100) { if (compose.onAllNodes(hasText(shown)).fetchSemanticsNodes().isEmpty()) { Thread.sleep(20); frames(1) } }
            return recompositions.scopes - before
        }
        assertThat(scopesFor { graph.agents.patch("bc-o5") { it.copy(name = "Other 5 renamed") } }).isEqualTo(0)
        assertThat(scopesFor { graph.agents.patch("bc-o6") { it.copy(name = "Other 6 renamed") } }).isEqualTo(0)

        assertThat(scopesFor(shown = "Worker 1 renamed") { graph.agents.patch("bc-w1") { it.copy(name = "Worker 1 renamed") } }).isGreaterThan(0)
        assertThat(compose.onAllNodes(hasText("Worker 1 renamed")).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `a minute on, the ages move by themselves and the section does not recompose`() {
        val graph = project(workerAgeMillis = 5 * 60_000L)
        val recompositions = show(graph)
        val ages = { age: String ->
            compose.onAllNodes(hasText(age, substring = true)).fetchSemanticsNodes().count { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == age || it.text.endsWith(" $age") }
            }
        }
        val shownAtFive = ages("5m")
        assertThat(shownAtFive).isGreaterThan(0)

        val rootBefore = recompositions.rootScopes
        val itemsBefore = recompositions.itemScopes.size
        AppClock.nowMillis = { now + 60_000L }
        compose.mainClock.advanceTimeBy(60_000L)
        frames()

        assertThat(ages("6m")).isEqualTo(shownAtFive)
        assertThat(ages("5m")).isEqualTo(0)
        assertThat(recompositions.rootScopes - rootBefore).isEqualTo(0)
        // One scope per line that draws an age — each primary's on screen and the summary's — and nothing around them.
        val tick = recompositions.itemScopes.drop(itemsBefore)
        assertThat(tick.toSet()).containsExactly(1)
        assertThat(tick.sum()).isEqualTo(shownAtFive + 1)
    }

    private companion object {
        const val ROOT = "bc-test-root"
        const val WORKERS = 30
        const val OTHERS = 300
    }
}
