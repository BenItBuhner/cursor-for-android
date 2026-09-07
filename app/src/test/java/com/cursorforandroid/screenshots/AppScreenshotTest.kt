package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.ui.CursorRoot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Drives the real app (demo backend) through Robolectric's native renderer and writes PNGs to `screenshots/`.
 * Run with `./gradlew :app:recordRoborazziDebug --tests '*AppScreenshotTest*'`; CI verifies against the committed
 * PNGs with `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    // The demo data is generated relative to "now" and the UI renders "Today at 2:00 PM"-style stamps, so the clock,
    // zone and locale are pinned to keep every pixel reproducible across machines and times of day.
    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(graph: AppGraph) {
        var pending by remember { mutableStateOf<String?>(null) }
        val mode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
        CursorTheme(mode = if (mode == ThemeMode.System) ThemeMode.Dark else mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                CursorRoot(graph = graph, deepLinkAgentId = pending, onDeepLinkConsumed = { pending = null })
            }
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollListTo(text: String, timeoutMillis: Long = 30_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
        waitForText(text, timeoutMillis)
    }

    private fun enterDemo(graph: AppGraph) {
        waitForText("Try the demo")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        waitForText("Ask Cursor to build, fix bugs, explore", 30_000)
        waitForText("Claude Fable 5.1 1M Max", 30_000)
        scrollListTo("Cesium Revenue Strategy")
    }

    @Test
    fun phoneWalkthrough() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }

        waitForText("Sign in")
        compose.onNodeWithText("key_", substring = true).performTextInput("key_demo_1234567890abcdef")
        capture("01_sign_in")

        // Home: composer on top, recent chats below.
        enterDemo(graph)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ask Cursor to build, fix bugs, explore"))
        compose.waitForIdle()
        capture("02_home")

        // Sidebar drawer.
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("New Chat")
        capture("03_sidebar")

        // Chats filter sheet from the filter icon next to "Chats".
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        waitForText("Grouping")
        capture("04_chats_filter")
        compose.onNodeWithText("Status").performClick()
        waitForText("Archived")
        capture("05_status_filter")
        Espresso.pressBack()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
        Espresso.pressBack() // close the drawer
        compose.waitForIdle()

        // Conversation with a live-streamed run.
        scrollListTo("Cesium Revenue Strategy")
        compose.onAllNodesWithText("Cesium Revenue Strategy").onFirst().performClick()
        waitForText("Follow up")
        val cesiumId = graph.agents.state.value.agents.first { it.name == "Cesium Revenue Strategy" }.id
        compose.waitUntil(90_000) { graph.conversations.state(cesiumId).value.items.any { it is RunFooter } }
        compose.waitForIdle()
        capture("06_conversation")

        // A finished reply that embeds a screenshot and a recording by their /opt/cursor/artifacts paths: the image
        // is fetched through the (demo) artifact download endpoint, the video shows its poster card.
        Espresso.pressBack()
        compose.waitForIdle()
        scrollListTo("Android mobile experience")
        compose.onAllNodesWithText("Android mobile experience").onFirst().performClick()
        waitForText("Follow up")
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Sidebar drawer mid-gesture")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Video: predictive_back_demo.mp4")).fetchSemanticsNodes().isNotEmpty() }
        // The reply is taller than the viewport; line its first paragraph up with the top so the figure is in frame.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Predictive back was missing", substring = true))
        compose.waitForIdle()
        capture("11_conversation_media")

        // Settings + light theme.
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Light").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Light }
        compose.waitForIdle()
        capture("07_settings_light")
        compose.onNodeWithContentDescription("Back").performClick()
        scrollListTo("Ask Cursor to build, fix bugs, explore")
        capture("08_home_light")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletTwoPane() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }
        enterDemo(graph)
        capture("09_tablet_home")
        compose.onAllNodesWithText("Revenue Scaling Pipeline Research").onFirst().performClick()
        waitForText("Worked", 30_000)
        capture("10_tablet_conversation")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC; demo ages are whole minutes, so rendered times land on exact minutes. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
