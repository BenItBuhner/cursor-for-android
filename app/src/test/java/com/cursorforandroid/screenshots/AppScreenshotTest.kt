package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real app (demo backend) through Robolectric's native graphics pipeline and writes PNGs to
 * `screenshots/`. Run with `./gradlew :app:recordRoborazziDebug --tests '*AppScreenshotTest*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun App(graph: AppGraph, deepLink: String? = null) {
        var pending by androidx.compose.runtime.remember { mutableStateOf(deepLink) }
        val mode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
        CursorTheme(mode = if (mode == ThemeMode.System) ThemeMode.Dark else mode) {
            CursorRoot(graph = graph, deepLinkAgentId = pending, onDeepLinkConsumed = { pending = null })
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for the agent list to load, then scrolls the list until [text] is visible. */
    private fun scrollListTo(text: String, timeoutMillis: Long = 30_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(timeoutMillis) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true)) }.isSuccess
        }
        waitForText(text, timeoutMillis)
    }

    private fun waitForDescription(description: String, timeoutMillis: Long = 20_000) {
        compose.waitUntil(timeoutMillis) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun phoneWalkthrough() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }

        // 1. Sign-in
        waitForText("Sign in to Cursor")
        compose.onNodeWithText("key_", substring = true).performTextInput("key_demo_1234567890abcdef")
        capture("01_sign_in")

        // 2. Home (sidebar as the phone home screen) via demo mode
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        scrollListTo("Cesium Revenue Strategy")
        capture("02_home_agents")

        // 3. Customize sheet
        compose.onNodeWithContentDescription("Customize").performClick()
        waitForText("Agent Metadata")
        capture("03_customize_sheet")
        compose.onNodeWithText("Status").performClick()
        waitForText("Archived")
        capture("04_customize_status_filter")
        Espresso.pressBack()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()

        // 4. Conversation with a live-streamed run (demo script replays the Cesium transcript)
        compose.onNodeWithText("Cesium Revenue Strategy").performClick()
        waitForDescription("More")
        val cesiumId = graph.agents.state.value.agents.first { it.name == "Cesium Revenue Strategy" }.id
        compose.waitUntil(90_000) { graph.conversations.state(cesiumId).value.items.any { it is RunFooter } }
        compose.waitForIdle()
        capture("05_conversation_streamed")

        // 5. Sidebar drawer over the conversation, opened with the edge-swipe gesture
        openDrawer()
        capture("06_sidebar_drawer")

        // 6. New Agent composer
        compose.onNodeWithText("New Agent").performClick()
        waitForText("Ask Cursor to build, fix bugs, explore")
        compose.onNodeWithText("Ask Cursor to build, fix bugs, explore").performTextInput("Add predictive back to the settings screen and open a PR.")
        waitForText("Claude Fable 5.1 1M Max")
        capture("07_new_agent")
        compose.onNodeWithText("Claude Fable 5.1 1M Max").performClick()
        waitForText("Model")
        capture("08_model_picker")
        Espresso.pressBack()
        compose.waitForIdle()

        // 7. Inbox
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Inbox")
        compose.onNodeWithText("Inbox").performClick()
        waitForText("Needs attention")
        capture("09_inbox")

        // 8. Light theme via Settings
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo mode")
        compose.onNodeWithText("Demo mode").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Light").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Light }
        compose.waitForIdle()
        capture("10_settings_light")
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Inbox")
        capture("11_home_light")
    }

    private fun openDrawer() {
        compose.onRoot().performTouchInput {
            swipeRight(startX = 4f, endX = width * 0.85f, durationMillis = 400)
        }
        waitForText("Inbox")
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletTwoPane() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        compose.setContent { App(graph) }
        waitForText("Sign in to Cursor")
        compose.onNodeWithText("Try the demo").performClick()
        compose.waitUntil(20_000) { graph.session.state.value is SessionState.SignedIn }
        scrollListTo("Revenue Scaling Pipeline Research")
        compose.onNodeWithText("Revenue Scaling Pipeline Research").performClick()
        waitForText("Worked", 30_000)
        capture("12_tablet_two_pane")
    }
}
