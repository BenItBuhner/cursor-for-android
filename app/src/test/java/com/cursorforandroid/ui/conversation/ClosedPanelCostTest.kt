package com.cursorforandroid.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
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
import com.cursorforandroid.ui.panel.PanelSectionId
import com.cursorforandroid.ui.panel.PanelViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * A closed side panel costs the chat nothing: nothing follows the panel's state while it is shut, so the tool calls
 * landing do not fold into it and a change to what only the panel reads does not recompose the chat's screen. Opened,
 * its first frame already shows what landed while it was shut. The real conversation screen over the demo graph: a
 * 32-turn chat with a live run.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ClosedPanelCostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4_096)
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-panel-cost"
    private val turns = 32
    private val live = "run-$turns"

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
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

    private fun call(runId: String, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/main/java/com/example/module$n/File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("line\n".repeat(40))); put("path", JsonPrimitive("app/src/File$n.kt")) }) },
        ),
    )

    private fun frame(): Double {
        val t0 = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    /** Frames until the screen has recomposed nothing for 30 in a row, the work off the main thread given time to land. */
    private fun settle(recompositions: Recompositions) {
        var still = 0
        var total = 0
        while (still < 30 && total < 600) {
            val scopes = recompositions.scopes
            Thread.sleep(10)
            frame()
            if (recompositions.scopes == scopes) still++ else still = 0
            total++
        }
        assertThat(still).isAtLeast(30)
    }

    private fun emit(vararg events: RunStreamEvent) = runBlocking { events.forEach { streamer.emit(live, it) } }

    @Test
    fun `a closed panel follows nothing, and opens on what landed while it was shut`() {
        AppClock.nowMillis = { now }
        val prompts = Array(turns) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Panel cost", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        runBlocking {
            for (i in 1 until turns) {
                val runId = "run-$i"
                streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
                repeat(12) { n -> streamer.emit(runId, call(runId, n + 1)) }
                streamer.emit(runId, RunStreamEvent.Assistant("Reply $i"))
                streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
                streamer.emit(runId, RunStreamEvent.Done)
            }
            streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        val recompositions = Recompositions()
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
        frames(60)

        val panel = ViewModelProvider(compose.activity, PanelViewModel.Factory(graph, agentId))["panel-$agentId", PanelViewModel::class.java]
        val folded = panel.state.value

        // Tool calls land in the transcript; nothing reads them off it for the panel.
        repeat(40) { n ->
            emit(call(live, 100 + n))
            Thread.sleep(4)
            frame()
        }
        frames(10)
        Thread.sleep(300)
        frames(2)
        compose.waitUntil(10_000) { graph.conversations.state(agentId).value.items.toString().contains("File139.kt") }
        assertThat(panel.state.value).isSameInstanceAs(folded)

        // What only the panel reads changes; the chat's screen, once the calls have settled, does not recompose for it.
        settle(recompositions)
        val before = recompositions.scopes
        compose.runOnIdle {
            panel.setSectionExpanded(PanelSectionId.Changes, true)
            panel.loadArtifacts()
        }
        frames(20)
        Thread.sleep(200)
        frames(5)
        println("CLOSED_PANEL root scopes on panel-only changes: ${recompositions.scopes - before}")
        assertThat(recompositions.scopes - before).isEqualTo(0)

        // Opened, the first frame reads the panel's state as it stands, the calls landed while shut among its files.
        compose.onNodeWithContentDescription("Open panel").performClick()
        val first = frame()
        val touched = panel.state.value.content.touched.map { it.path }
        assertThat(touched.any { it.endsWith("File139.kt") }).isTrue()
        assertThat(panel.state.value.expandedSections[PanelSectionId.Changes]).isTrue()
        val opening = listOf(first) + List(29) { frame() }
        println("CLOSED_PANEL open first=${"%.1f".format(opening[0])}ms second=${"%.1f".format(opening[1])}ms max=${"%.1f".format(opening.max())}ms total30=${"%.1f".format(opening.sum())}ms")

        // Open, it follows the transcript again.
        emit(call(live, 200))
        compose.waitUntil(10_000) {
            frame()
            panel.state.value.content.touched.any { it.path.endsWith("File200.kt") }
        }
    }
}
