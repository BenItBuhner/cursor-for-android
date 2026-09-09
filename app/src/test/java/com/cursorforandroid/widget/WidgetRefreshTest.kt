package com.cursorforandroid.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.appGraph
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a widget tick is allowed to cost (WIDGET-01) and how long the app follows the list for widgets that are no
 * longer there (WIDGET-02). The clock is pinned so the five-minute staleness window can be crossed on demand.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetRefreshTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_788_900_000_000L

    private val plenty = WidgetRefreshBudget(connected = true, batteryLow = false)
    private val offline = WidgetRefreshBudget(connected = false, batteryLow = false)
    private val lowBattery = WidgetRefreshBudget(connected = true, batteryLow = true)

    private lateinit var realPlacedWidgets: suspend (Context) -> Boolean

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
        realPlacedWidgets = WidgetSync.placedWidgets
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        WidgetSync.placedWidgets = realPlacedWidgets
        WidgetSync.stop()
    }

    /** A signed-in graph whose list is loaded, as a widget in a process that has been up for a while would find it. */
    private suspend fun loadedGraph(): AppGraph {
        val graph = app.appGraph
        graph.session.enterDemo()
        WidgetData.prepare(graph, plenty)
        assertThat(graph.agents.state.value.hasLoaded).isTrue()
        return graph
    }

    /** Waits for [graph]'s refresh count to leave [from]; prepare's revalidation is deliberately not awaited. */
    private suspend fun awaitRefresh(graph: AppGraph, from: Long): Boolean =
        withTimeoutOrNull(5_000) {
            while (graph.agents.refreshCompleted.value == from) delay(10)
            true
        } == true

    @Test
    fun `a stale list is revalidated when there is a connection and charge to spare`() = runBlocking {
        val graph = loadedGraph()
        val before = graph.agents.refreshCompleted.value
        now += 6 * 60_000

        WidgetData.prepare(graph, plenty)

        assertThat(awaitRefresh(graph, before)).isTrue()
    }

    @Test
    fun `a widget tick without a connection asks for nothing`() = runBlocking {
        val graph = loadedGraph()
        val before = graph.agents.refreshCompleted.value
        now += 6 * 60_000

        WidgetData.prepare(graph, offline)

        // Nothing to wait for, so the count is given every chance to move before it is read.
        delay(300)
        assertThat(graph.agents.refreshCompleted.value).isEqualTo(before)
    }

    @Test
    fun `a widget tick on a low battery asks for nothing`() = runBlocking {
        val graph = loadedGraph()
        val before = graph.agents.refreshCompleted.value
        now += 6 * 60_000

        WidgetData.prepare(graph, lowBattery)

        delay(300)
        assertThat(graph.agents.refreshCompleted.value).isEqualTo(before)
    }

    @Test
    fun `a low battery still gets the first page, because the alternative is a widget that never fills in`() = runBlocking {
        val graph = app.appGraph
        graph.session.enterDemo()

        WidgetData.prepare(graph, lowBattery)

        assertThat(graph.agents.state.value.hasLoaded).isTrue()
        assertThat(graph.agents.state.value.agents).isNotEmpty()
    }

    @Test
    fun `an offline first render leaves the widget with nothing rather than waiting on the network`() = runBlocking {
        val graph = app.appGraph
        graph.session.enterDemo()

        WidgetData.prepare(graph, offline)

        assertThat(graph.agents.state.value.hasLoaded).isFalse()
    }

    @Test
    fun `removing the last widget stops the app following the list, and adding one starts it again`() = runBlocking {
        val graph = loadedGraph()
        WidgetSync.follow(app, graph)
        assertThat(WidgetSync.isFollowing()).isTrue()

        ChatsWidgetReceiver().onDisabled(app)
        assertThat(WidgetSync.isFollowing()).isFalse()

        WidgetSync.follow(app, graph)
        assertThat(WidgetSync.isFollowing()).isTrue()
    }

    @Test
    fun `following twice is following once`() = runBlocking {
        val graph = loadedGraph()
        WidgetSync.follow(app, graph)
        WidgetSync.follow(app, graph)
        assertThat(WidgetSync.isFollowing()).isTrue()

        WidgetSync.stop()
        assertThat(WidgetSync.isFollowing()).isFalse()
    }

    private suspend fun awaitFollowing(): Boolean =
        withTimeoutOrNull(5_000) {
            while (!WidgetSync.isFollowing()) delay(10)
            true
        } == true

    /**
     * Start-up asks the launcher for the placed widgets, which suspends. A last-widget removal landing while that
     * question is open used to be overtaken by its own stale answer, and the collector it installed then had
     * nothing left to take it down for the rest of the process.
     */
    @Test
    fun `a removal while the start-up check is in flight leaves the app unfollowed`() = runBlocking {
        val graph = loadedGraph()
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        WidgetSync.placedWidgets = {
            asked.complete(Unit)
            answer.await()
        }

        WidgetSync.start(app, graph)
        asked.await()
        ChatsWidgetReceiver().onDisabled(app)
        answer.complete(true)

        // Nothing to wait for, so start-up is given every chance to follow on that answer before it is read.
        delay(300)
        assertThat(WidgetSync.isFollowing()).isFalse()
    }

    @Test
    fun `a start-up check that finds a widget follows the list`() = runBlocking {
        val graph = loadedGraph()
        WidgetSync.placedWidgets = { true }

        WidgetSync.start(app, graph)

        assertThat(awaitFollowing()).isTrue()
    }

    /** The token says which widget set an answer describes, not that following is over: a new widget still counts. */
    @Test
    fun `a widget placed after the last one was removed is followed again`() = runBlocking {
        val graph = loadedGraph()
        WidgetSync.placedWidgets = { true }
        ChatsWidgetReceiver().onDisabled(app)

        WidgetSync.start(app, graph)

        assertThat(awaitFollowing()).isTrue()
    }
}
