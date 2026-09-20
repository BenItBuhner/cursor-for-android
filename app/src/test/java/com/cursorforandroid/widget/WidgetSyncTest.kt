package com.cursorforandroid.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The update pipeline between the app and its widgets (WIDGET-03): every change the app learns of reaches the
 * widgets within a second, a burst of changes is one render, a list that never stops changing is still rendered,
 * and a render that fails is tried again. The launcher and Glance are replaced by seams — what is pinned here is
 * when a render is asked for, not what it draws.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetSyncTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private lateinit var realPlacedWidgets: suspend (Context) -> Boolean
    private lateinit var realRenderer: suspend (Context) -> Unit

    /** Wall-clock times of every render asked for. */
    private val renders: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        realPlacedWidgets = WidgetSync.placedWidgets
        realRenderer = WidgetSync.renderer
        WidgetSync.placedWidgets = { true }
        WidgetSync.renderer = { renders += System.currentTimeMillis() }
    }

    @After
    fun tearDown() {
        WidgetSync.stop()
        WidgetSync.placedWidgets = realPlacedWidgets
        WidgetSync.renderer = realRenderer
    }

    /** A signed-in graph whose list is loaded and followed, as a process with a placed widget holds it. */
    private suspend fun followedGraph(): AppGraph {
        val graph = app.appGraph
        graph.session.enterDemo()
        WidgetData.prepare(graph, WidgetRefreshBudget(connected = true, batteryLow = false))
        assertThat(graph.agents.state.value.agents).isNotEmpty()
        WidgetSync.follow(app, graph)
        // The follower drops the state as it stands; give its subscription a moment to be in place before changing it.
        delay(100)
        renders.clear()
        return graph
    }

    private suspend fun awaitRenders(count: Int, withinMs: Long): Boolean =
        withTimeoutOrNull(withinMs) {
            while (renders.size < count) delay(10)
            true
        } == true

    @Test
    fun `a change to a row reaches the widgets within a second`() = runBlocking {
        val graph = followedGraph()
        val agent = graph.agents.state.value.agents.first()
        val changedAt = System.currentTimeMillis()

        graph.agents.patch(agent.id) { it.copy(name = "${it.name} (renamed)") }

        assertThat(awaitRenders(1, withinMs = 1_000)).isTrue()
        assertThat(renders.first() - changedAt).isLessThan(1_000)
    }

    @Test
    fun `a run ending re-renders the widgets, as the hub reports it`() = runBlocking {
        val graph = followedGraph()
        val running = graph.agents.state.value.agents.first { it.isRunning }

        // What LiveRunHub.finish patches onto the row when a stream delivers its result.
        graph.agents.patch(running.id) { it.copy(runStatus = RunStatus.FINISHED, lifecycle = AgentLifecycle.IDLE) }

        assertThat(awaitRenders(1, withinMs = 1_000)).isTrue()
        assertThat(WidgetData.snapshot(graph).agents.first { it.id == running.id }.isRunning).isFalse()
    }

    @Test
    fun `a burst of changes is one render`() = runBlocking {
        val graph = followedGraph()
        val agent = graph.agents.state.value.agents.first()

        repeat(5) { i ->
            graph.agents.patch(agent.id) { it.copy(name = "burst $i") }
            delay(20)
        }

        assertThat(awaitRenders(1, withinMs = 1_000)).isTrue()
        // Nothing else was changed, so nothing else may be rendered — the burst settled into the one above.
        delay(WidgetSync.SETTLE_MS * 2)
        assertThat(renders).hasSize(1)
    }

    @Test
    fun `a list that never stops changing is still rendered every so often`() = runBlocking {
        val graph = followedGraph()
        val agent = graph.agents.state.value.agents.first()

        // Changes every 100 ms for two seconds: a debounce that waits for quiet would never render during this.
        val end = System.currentTimeMillis() + 2_000
        var i = 0
        while (System.currentTimeMillis() < end) {
            graph.agents.patch(agent.id) { it.copy(name = "tick ${i++}") }
            delay(100)
        }

        assertThat(renders.size).isAtLeast(3)
        // And no two renders are further apart than the settle window plus a generous margin for the machine.
        val gaps = renders.zipWithNext { a, b -> b - a }
        assertThat(gaps.maxOrNull() ?: 0L).isLessThan(1_000)
    }

    @Test
    fun `a render that fails is tried once more`() = runBlocking {
        var attempts = 0
        WidgetSync.renderer = {
            attempts++
            if (attempts == 1) error("the launcher would not answer")
            renders += System.currentTimeMillis()
        }
        val graph = followedGraph()
        val agent = graph.agents.state.value.agents.first()

        graph.agents.patch(agent.id) { it.copy(name = "after a failure") }

        assertThat(awaitRenders(1, withinMs = WidgetSync.SETTLE_MS + WidgetSync.RETRY_MS + 2_000)).isTrue()
        assertThat(attempts).isEqualTo(2)
    }

    @Test
    fun `nothing is rendered once the last widget is gone`() = runBlocking {
        val graph = followedGraph()
        val agent = graph.agents.state.value.agents.first()
        WidgetSync.stop()

        graph.agents.patch(agent.id) { it.copy(name = "unheard") }

        delay(WidgetSync.SETTLE_MS * 3)
        assertThat(renders).isEmpty()
    }

    @Test
    fun `the refresh flag is spent after its time to live`() {
        val now = 1_788_900_000_000L
        assertThat(WidgetRefresh.isRefreshing(null, now)).isFalse()
        assertThat(WidgetRefresh.isRefreshing(now - 1_000, now)).isTrue()
        assertThat(WidgetRefresh.isRefreshing(now - WidgetRefresh.FLAG_TTL_MS + 1, now)).isTrue()
        assertThat(WidgetRefresh.isRefreshing(now - WidgetRefresh.FLAG_TTL_MS, now)).isFalse()
        // A clock that went backwards (a flag written in the future) is not a refresh either.
        assertThat(WidgetRefresh.isRefreshing(now + 5_000, now)).isFalse()
    }
}
