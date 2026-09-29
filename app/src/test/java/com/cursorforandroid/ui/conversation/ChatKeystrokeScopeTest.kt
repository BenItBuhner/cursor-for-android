package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeScopes
import com.cursorforandroid.util.threadAllocatedBytes
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
 * A keystroke in the follow-up composer recomposes the composer and nothing else on the chat screen: not the screen's
 * dock and side-panel content, not the transcript's lazy layout, however long the chat. The composer alone, with a
 * hoisted string, costs 4 scopes a keystroke; the screen cost 7 (and re-derived an O(items) id set) while it read the
 * draft at its own level.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ChatKeystrokeScopeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-keystroke-scope"
    private val rec = RecomposeScopes()
    private var data: CompositionData? = null

    @Before fun setUp() { AppClock.nowMillis = { now } }

    @After fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    @Test
    fun `a keystroke recomposes the composer alone`() {
        val turns = Array(24) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten module ${it + 1} and list the files.", "Reply ${it + 1}\n\n- `File.kt`: split\n\n```kotlin\nval x = $it\n```") }
        open(turns, newest = "Prompt 24")
        assertComposerAlone(measure("24 turns"))
    }

    @Test
    fun `a keystroke recomposes the composer alone on a chat of thousands of items`() {
        val runs = 560
        val turns = Array(runs) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten module ${it + 1}.", "Reply ${it + 1}") }
        for (i in 1..runs) runBlocking {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            repeat(2) { n ->
                streamer.emit(
                    runId,
                    RunStreamEvent.ToolCall(
                        SseToolCallDto(
                            callId = "$runId-c$n",
                            name = "read_file",
                            status = "completed",
                            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
                            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")) }) },
                        ),
                    ),
                )
            }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        val graph = open(turns, newest = "Prompt $runs")
        var pages = 0
        while (pages < 80) {
            val state = graph.conversations.state(agentId).value
            if (!state.hasOlder && !state.isLoadingOlder) break
            val before = state.items.size
            graph.conversations.loadOlder(agentId)
            compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { s -> !s.isLoadingOlder && (s.items.size > before || !s.hasOlder) } }
            compose.waitForIdle()
            pages++
        }
        compose.waitUntil(120_000) { graph.conversations.state(agentId).value.traceStatus.pending == 0 }
        compose.waitForIdle()
        val viewModel = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java]
        val items = viewModel.presented.value.items.size
        assertThat(items).isAtLeast(2_000)
        assertComposerAlone(measure("$items items"))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(turns: Array<Triple<String, String, String>>, newest: String): AppGraph {
        api.addFinishedAgent(agentId, "Keystroke chat", *turns, firstRunAt = Instant.ofEpochMilli(now - turns.size * 3_600_000L).toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            SideEffect { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SendMotionHost { ConversationScreen(graph, agentId, onBack = {}) }
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(newest, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return graph
    }

    private class Measured(val scopesPerKeystroke: Double, val byName: Map<String, Int>, val rowsComposed: Int)

    private fun measure(label: String, keystrokes: Int = 60): Measured {
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer")))
        field.performClick()
        repeat(20) { field.performTextInput("w"); compose.waitForIdle() }
        rec.observeAll(data!!)
        rec.reset()
        val rows0 = rowsComposed()
        var allocated = 0L
        repeat(keystrokes) { i ->
            val before = threadAllocatedBytes()
            field.performTextInput(if (i % 6 == 5) " " else "a")
            compose.waitForIdle()
            allocated += threadAllocatedBytes() - before
        }
        val measured = Measured(rec.scopes / keystrokes.toDouble(), HashMap(rec.byName), rowsComposed() - rows0)
        println(
            "keystroke [$label] scopes/keystroke=${"%.1f".format(measured.scopesPerKeystroke)} alloc/keystroke=${allocated / keystrokes / 1024}KB " +
                "rowsComposed=${measured.rowsComposed}\n" + rec.top(),
        )
        return measured
    }

    private fun assertComposerAlone(measured: Measured) {
        assertThat(measured.rowsComposed).isEqualTo(0)
        assertThat(measured.byName.keys.filter { "LazyLayout" in it }).isEmpty()
        // The composer's own 4, with one to spare.
        assertThat(measured.scopesPerKeystroke).isAtMost(5.0)
    }

    private fun rowsComposed(): Int = TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.rowCompositions ?: 0
}
