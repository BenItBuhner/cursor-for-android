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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
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
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.SourceFilter
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

    // The demo data is generated relative to "now" and the lists render "4m" / "3h"-style ages from it, so the clock,
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
        val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
        CursorTheme(mode = if (mode == ThemeMode.System) ThemeMode.Dark else mode, oledBlack = oledBlack) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                CursorRoot(graph = graph, deepLinkAgentId = pending, onDeepLinkConsumed = { pending = null })
            }
        }
    }

    /**
     * Builds the app graph, decides the session, then composes the app. The session is decided first on purpose: the
     * test rule runs effects on an unconfined dispatcher, so `CursorRoot`'s `LaunchedEffect { restoreIfNeeded() }`
     * would flip the session to signed-out on the IO worker it returns from — while the first composition is still being
     * applied — and about one run in five Compose never saw that write: the sign-in screen stayed blank and the run's
     * first `waitForText` timed out. With the session already decided, the sign-in screen is composed on the first pass
     * (as it is in a process whose session was decided before its activity) and `restoreIfNeeded()` is a no-op.
     */
    private fun launchApp(): AppGraph {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking { graph.session.restoreIfNeeded() }
        compose.setContent { App(graph) }
        return graph
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
        // Repositories and models load in parallel; both selectors must have settled before a capture. The model chip
        // reads the model's name alone (its variant's parameters live in the picker); the repo chip is matched exactly
        // because the slug also occurs inside a workspace name in the list.
        waitForText("Claude Fable 5.1", 30_000)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("codex-poly-bot").fetchSemanticsNodes().isNotEmpty() }
        scrollListTo("Cesium Revenue Strategy")
    }

    @Test
    fun phoneWalkthrough() {
        val graph = launchApp()

        // The account sign-in is the one primary action; the pasted-key field sits folded behind "Use an API key instead".
        waitForText("Continue with Cursor")
        capture("01_sign_in")

        // Home: composer on top, recent chats below.
        enterDemo(graph)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ask Cursor to build, fix bugs, explore"))
        compose.waitForIdle()
        capture("02_home")

        // The composer's "+" menu (Multitask / Files / Skills / MCP Servers) and its Skills page.
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        waitForText("Orchestrate multiple subagents in parallel")
        capture("12_composer_menu")
        compose.onNodeWithText("Skills").performClick()
        waitForText("/autopilot")
        capture("13_composer_skills")
        Espresso.pressBack() // dismiss the popup
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("/autopilot")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()

        // Sidebar drawer: the header's "+" is the new-chat button.
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("New chat")).fetchSemanticsNodes().isNotEmpty() }
        capture("03_sidebar")

        // Chats filter sheet from the header's filter icon.
        compose.onNodeWithContentDescription("Filter and group chats").performClick()
        waitForText("Grouping")
        capture("04_chats_filter")
        compose.onNodeWithText("Status").performClick()
        waitForText("Archived")
        capture("05_status_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        // Source: where each chat was started (the account's word), as on cursor.com/agents; Environment: where it runs.
        compose.onNodeWithText("Source").performClick()
        waitForText("Grok Bot")
        capture("24_source_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        compose.onNodeWithText("Environment").performClick()
        waitForText("Team pool")
        capture("25_environment_filter")
        Espresso.pressBack()
        waitForText("Grouping")
        // The Source filter at work: only the chats started from Slack, the CLI and Grok Bot are left in the sidebar.
        runBlocking { graph.prefs.updateListPreferences { it.copy(sources = setOf(SourceFilter.Slack, SourceFilter.Cli, SourceFilter.GrokBot)) } }
        waitForText("CLI +2")
        Espresso.pressBack() // dismiss the sheet
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Codex-Poly-Bot Scaling").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Zen browser flawless parity").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("26_sidebar_source_filtered")
        runBlocking { graph.prefs.updateListPreferences { it.copy(sources = SourceFilter.entries.toSet()) } }
        waitForText("Codex-Poly-Bot Scaling")
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
        // The finished run's trace is replayed a beat after the transcript; wait for it too, so what is captured is
        // the settled list rather than whichever of the trace and the media happened to land first.
        val mobileId = graph.agents.state.value.agents.first { it.name == "Android mobile experience" }.id
        compose.waitUntil(30_000) { graph.conversations.state(mobileId).value.items.any { it is ActivityGroup } }
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

        // Last, because opening it lets its live run finish, which would reorder the home list captured above. Back
        // in the dark theme: a chat with a goal on it. The turns Cursor started by itself — the goal picked up again,
        // a subagent reporting back — reach the transcript as user messages made of markup; each is one row that
        // opens onto the objective or the report. The live run plays out first, and the earlier turns' traces are
        // replayed alongside it.
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Dark").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Dark }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").performClick()
        scrollListTo("Hyper-realistic human limbs")
        compose.onAllNodesWithText("Hyper-realistic human limbs").onFirst().performClick()
        waitForText("Follow up")
        val limbsId = graph.agents.state.value.agents.first { it.name == "Hyper-realistic human limbs" }.id
        compose.waitUntil(90_000) {
            val items = graph.conversations.state(limbsId).value.items
            items.count { it is RunFooter } == 4 && items.count { it is ActivityGroup } == 4
        }
        compose.waitForIdle()
        compose.onNodeWithText("Goal continued").performClick()
        waitForText("In the Verity photoreal engine")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Goal continued"))
        compose.waitForIdle()
        capture("23_conversation_notifications")
        // Leave the chat, so nothing of it is still streaming when the next test brings up its own app.
        Espresso.pressBack()
        compose.waitForIdle()
    }

    @Test
    fun oledBlack() {
        val graph = launchApp()
        enterDemo(graph)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        waitForText("Demo User")
        compose.onNodeWithText("Demo User").performClick()
        waitForText("Appearance")
        compose.onNodeWithText("Cursor Dark").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Dark }
        // The preference is one thing, the screen having recomposed from it another: wait for the copy that only the
        // dark theme shows, or a slow runner captures "Match system" still checked.
        waitForText("True-black surfaces instead of Cursor Dark's charcoal.")
        capture("20_settings_dark")
        compose.onNodeWithText("OLED black").performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.oledBlack.first() } }
        // The switch in the OLED row (its row merges the label into its semantics) reads on once the screen has caught up.
        compose.waitUntil(10_000) {
            compose.onAllNodes(isOn() and hasAnyAncestor(hasText("OLED black", substring = true))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        capture("21_settings_oled")
        compose.onNodeWithContentDescription("Back").performClick()
        scrollListTo("Ask Cursor to build, fix bugs, explore")
        capture("22_home_oled")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletTwoPane() {
        val graph = launchApp()
        enterDemo(graph)
        capture("09_tablet_home")
        compose.onAllNodesWithText("Revenue Scaling Pipeline Research").onFirst().performClick()
        waitForText("Worked", 30_000)
        // The finished run's thinking / tool / subagent trace is replayed from its retained stream a beat after the
        // transcript; capture once it has been spliced in.
        val revenueId = graph.agents.state.value.agents.first { it.name == "Revenue Scaling Pipeline Research" }.id
        compose.waitUntil(30_000) { graph.conversations.state(revenueId).value.items.any { it is ActivityGroup } }
        compose.waitForIdle()
        capture("10_tablet_conversation")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC; demo ages are whole minutes, so rendered times land on exact minutes. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
