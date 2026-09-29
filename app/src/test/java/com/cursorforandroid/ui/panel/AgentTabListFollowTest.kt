package com.cursorforandroid.ui.panel

import android.content.Context
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Another chat's tab follows the agents its transcript names, not the whole list: a change to a chat it does not
 * name recomposes nothing in it, a change to a worker it names reaches the rows about that worker. The demo's
 * Project coordinator, its worker rows unfolded.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentTabListFollowTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an unrelated chat changing recomposes nothing, a named worker changing reaches its rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        val coordinator = graph.agents.agent(DemoData.PROJECT_ID)!!
        val tab = PanelTab.Agent(coordinator.id)
        val state = PanelState("bc-demo-0021", tabs = PanelTabsState(listOf(tab), tab.key), tabAgents = mapOf(coordinator.id to coordinator))
        val recompositions = Recompositions()
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalPanelGraph provides graph) { AgentTab(tab, state, PanelActions.None) }
            }
        }
        compose.waitUntil(30_000) { shown("1 search · 2 agents") }
        compose.onNode(hasText("1 search · 2 agents", substring = true)).performClick()
        compose.waitForIdle()

        val before = recompositions.scopes
        runBlocking { graph.agents.rename("bc-demo-0003", "Renamed elsewhere").getOrThrow() }
        compose.waitForIdle()
        println("AGENT_TAB scopes on an unrelated rename: ${recompositions.scopes - before}")
        assertThat(recompositions.scopes - before).isEqualTo(0)

        // A worker the transcript names: the rows that read it follow it.
        val linked = recompositions.scopes
        runBlocking { graph.agents.rename("bc-demo-0020", "Stripe webhooks, renamed").getOrThrow() }
        compose.waitForIdle()
        println("AGENT_TAB scopes on a named worker's rename: ${recompositions.scopes - linked}")
        assertThat(recompositions.scopes - linked).isGreaterThan(0)
    }
}
