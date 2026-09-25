package com.cursorforandroid.screenshots

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
 * The chat's name in its header, beside the back arrow, under a 24dp status bar:
 *
 * - `670`: a phone, the demo's chat with a pull request — the name on one line between back and the right-side buttons.
 * - `671`: a phone, the Project's coordinator chat — the Project's icon in its colour before the name.
 * - `672`: a phone, a chat renamed past the width the buttons leave — the name ellipsized short of them.
 * - `673`: a 1000dp tablet in the running shell with the rail open — no back arrow, so no name.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HeaderTitleScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

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

    /** The chat a notification hands the running shell, once it is up (see [HeaderClearanceScreenshotTest]). */
    private var deepLink by mutableStateOf<String?>(null)

    private fun demoGraph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        return graph
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showChat(agentId: String): AppGraph {
        val graph = demoGraph()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        dispatchStatusBar()
        return graph
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showShell(agentId: String): AppGraph {
        val graph = demoGraph()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = true,
                        deepLinkAgentId = deepLink,
                        onDeepLinkConsumed = { deepLink = null },
                    )
                }
            }
        }
        dispatchStatusBar()
        compose.waitUntil(30_000) { onScreen("Ask Cursor to build, fix bugs, explore") }
        compose.waitForIdle()
        deepLink = agentId
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("chat-header")).fetchSemanticsNodes().isNotEmpty() }
        return graph
    }

    private fun dispatchStatusBar() {
        val px = (STATUS_BAR_DP * compose.activity.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** The sidebar's list, told from anything else that scrolls by the section headers only it has. */
    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    /** The finished run's trace spliced in, by the frame's word (see [AppScreenshotTest.tabletTwoPane]). */
    private fun waitForRevenueChat(graph: AppGraph) {
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(REVENUE_ID).value
            state.items.any { it is ActivityGroup } && state.traceStatus.pending == 0 && !onScreen("Loading the activity") && onScreen("1 thought")
        }
        compose.waitForIdle()
    }

    /** The coordinator's word to the user on screen and both turns' stretches drawn (see [ChatHeaderScreenshotTest]). */
    private fun waitForProjectChat(graph: AppGraph) {
        compose.waitUntil(60_000) { onScreen("PR #215 (usage aggregation) is") }
        compose.waitUntil(60_000) { graph.conversations.state(DemoData.PROJECT_ID).value.let { state -> state.items.count { it is ActivityGroup } >= 2 && state.traceStatus.pending == 0 } }
        compose.waitUntil(30_000) { !onScreen("Loading the activity") }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun phoneChat() {
        val graph = showChat(REVENUE_ID)
        waitForRevenueChat(graph)
        capture("670_header_title_phone_chat")
    }

    @Test
    fun phoneProjectChat() {
        val graph = showChat(DemoData.PROJECT_ID)
        waitForProjectChat(graph)
        capture("671_header_title_phone_project")
    }

    @Test
    fun phoneLongTitle() {
        val graph = showChat(REVENUE_ID)
        waitForRevenueChat(graph)
        runBlocking { graph.agents.rename(REVENUE_ID, LONG_TITLE).getOrThrow() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(LONG_TITLE)).fetchSemanticsNodes().isNotEmpty() }
        capture("672_header_title_phone_long")
    }

    @Test
    @Config(qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletRailNoTitle() {
        val graph = showShell(REVENUE_ID)
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
        waitForRevenueChat(graph)
        capture("673_header_title_tablet_hidden")
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        const val REVENUE_ID = "bc-demo-0002"
        const val LONG_TITLE = "Revenue Scaling Pipeline Research across every regional billing ledger and the quarterly forecast"

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
