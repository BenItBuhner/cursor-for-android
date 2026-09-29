package com.cursorforandroid.ui.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * A window resized live under the running app — a DeX or freeform window's edge dragged, a split-screen divider moved —
 * a dp at a time, taken in place as the activity takes every size change: within one width class nothing of the shell
 * is composed again, not the sidebar's rows, not the chat's header, composer or transcript, not the panel pinned beside
 * it. The panes follow the window at layout, which is all a step costs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1240dp-h900dp-land-night-mdpi")
class LiveResizeRecompositionTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private var activity: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        AppClock.nowMillis = { NOW }
        RecomposeCounter.install()
    }

    @After
    fun tearDown() {
        RecomposeCounter.uninstall()
        activity?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** The app on the demo with a chat opened by its link, as a notification opens it, and everything it loads settled. */
    private fun launchOnChat() {
        runBlocking { app.appGraph.session.enterDemo() }
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { activity = it }
        compose.waitUntil(30_000) { exists(hasText(HOME_PLACEHOLDER, substring = true)) }
        launched.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://cursor.com/agents/$CHAT_ID")).setClass(app, MainActivity::class.java))
        compose.waitUntil(30_000) { exists(hasTestTag("chat-header")) && exists(hasText(CHAT_PLACEHOLDER, substring = true)) }
        settle()
    }

    private fun openPanel() {
        compose.onNodeWithContentDescription(OPEN_PANEL).performClick()
        compose.waitUntil(10_000) { displayed(hasTestTag(PANEL)) && exists(hasContentDescription(HIDE_PANEL)) }
        settle()
    }

    private fun settle() = repeat(4) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun displayed(matcher: SemanticsMatcher) = runCatching { compose.onAllNodes(matcher).onFirst().assertIsDisplayed() }.isSuccess

    private class Sweep(val counts: Map<String, Int>, val stepMillis: List<Double>) {
        operator fun get(name: String): Int = counts.getValue(name)

        override fun toString(): String {
            val sorted = stepMillis.sorted()
            val mean = stepMillis.average()
            return "${counts.entries.joinToString(" ") { "${it.key}=${it.value}" }} | step ms mean=${"%.1f".format(mean)} " +
                "p50=${"%.1f".format(sorted[sorted.size / 2])} p90=${"%.1f".format(sorted[sorted.size * 9 / 10])} max=${"%.1f".format(sorted.last())}"
        }
    }

    /**
     * The window taken from [from] to [to] dp wide a dp at a time, [height] tall, each width a configuration change the
     * activity takes in place and one frame on the pinned clock; the composables of the app run over the whole sweep,
     * and the wall time each step took.
     */
    private fun sweep(from: Int, to: Int, height: Int, orientation: String): Sweep {
        compose.mainClock.autoAdvance = false
        val step = if (to < from) -1 else 1
        val widths = generateSequence(from + step) { it + step }.takeWhile { it != to + step }.toList()
        // A first step off the sweep's start, uncounted: what lands once whatever width the window came from is left.
        resize(from, height, orientation)
        RecomposeCounter.reset()
        val millis = widths.map { width ->
            val start = System.nanoTime()
            resize(width, height, orientation)
            (System.nanoTime() - start) / 100_000 / 10.0
        }
        val counts = SCOPES.associateWith(RecomposeCounter::count)
        val top = RecomposeCounter.top(25)
        compose.mainClock.autoAdvance = true
        settle()
        return Sweep(counts, millis).also { println("LIVE-RESIZE $from→$to: $it\n  top: $top") }
    }

    private fun resize(width: Int, height: Int, orientation: String) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-$orientation-night-mdpi")
        activity!!.configurationChange()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertNothingRecomposed(sweep: Sweep, vararg names: String) {
        names.forEach { assertWithMessage("$it over the sweep ($sweep)").that(sweep[it]).isEqualTo(0) }
    }

    @Test
    fun `a wide window resized live with the panel pinned open recomposes nothing of the shell within its width class`() {
        launchOnChat()
        openPanel()
        val sweep = sweep(from = 1240, to = 1040, height = 900, orientation = "land")
        assertThat(displayed(hasTestTag(PANEL))).isTrue()
        assertNothingRecomposed(sweep, *WIDE_SHELL)
    }

    @Test
    fun `a wide window resized live with the panel shut recomposes nothing of the shell within its width class`() {
        launchOnChat()
        val sweep = sweep(from = 1240, to = 1040, height = 900, orientation = "land")
        assertNothingRecomposed(sweep, *WIDE_SHELL)
    }

    @Test
    @Config(qualifiers = "w590dp-h900dp-port-night-mdpi")
    fun `a phone window resized live recomposes nothing of the chat`() {
        launchOnChat()
        val sweep = sweep(from = 590, to = 390, height = 900, orientation = "port")
        assertNothingRecomposed(sweep, APP_SHELL, SIDEBAR, ROW, CHAT, CHAT_HEADER, COMPOSER, TRANSCRIPT_ROW, PANEL_HOST)
    }

    private companion object {
        val NOW = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
        // Qualified: the chats widget has an AgentRowItem of its own, whose previews are composed off screen.
        const val APP_NAV_HOST = "ui.navigation.AppNavHost"
        const val APP_SHELL = "ui.navigation.AppShell"
        const val WIDE_PANES = "ui.navigation.WidePanes"
        const val SIDEBAR = "ui.agents.Sidebar"
        const val ROW = "ui.agents.AgentRowItem"
        const val CHAT = "ui.conversation.ConversationScreen"
        const val CHAT_HEADER = "ui.components.ChatHeader"
        const val COMPOSER = "ui.components.ComposerBox"
        const val TRANSCRIPT_ROW = "ui.conversation.TranscriptRowView"
        const val PANEL_HOST = "ui.panel.SidePanelHost"
        val SCOPES = listOf(
            APP_NAV_HOST, APP_SHELL, WIDE_PANES, "ui.navigation.SidebarRail", SIDEBAR, ROW, CHAT, CHAT_HEADER, COMPOSER, TRANSCRIPT_ROW,
            "ui.panel.SidePanel", PANEL_HOST, "ui.components.PaneResizeEdge", "ui.navigation.CursorNavHost", "ui.home.HomeScreen",
        )
        val WIDE_SHELL = arrayOf(APP_SHELL, WIDE_PANES, SIDEBAR, ROW, CHAT, CHAT_HEADER, COMPOSER, TRANSCRIPT_ROW, PANEL_HOST)
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val CHAT_PLACEHOLDER = "Follow up"
        const val CHAT_ID = "bc-demo-0004"
        const val PANEL = "conversation-panel"
        const val OPEN_PANEL = "Open panel"
        const val HIDE_PANEL = "Hide panel"
    }
}
