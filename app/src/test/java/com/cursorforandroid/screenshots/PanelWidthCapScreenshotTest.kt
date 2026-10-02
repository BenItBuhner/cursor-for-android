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
import androidx.compose.ui.test.hasAnyAncestor
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
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
 * The pinned panel's read tabs on a desktop-sized window, where the layout's half-and-half split leaves the panel
 * wider than the chat's column beside it: their text keeps to that column, from the panel's start.
 *
 * - `820`: a 1920dp DeX monitor, the Project's chat with its notes: the panel about 820dp, its lines 640dp at most.
 * - `821`: the monitor with a chat's Details: the same column.
 * - `822`/`823`: the 1280dp tablet on its side with the same two, the panel 501dp beside the rail: nothing to cap.
 * - `824`: the monitor with the panel dragged to 960dp: a width the reader asked for, and the Details run the whole of it.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = DESKTOP)
class PanelWidthCapScreenshotTest {

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

    private var deepLink by mutableStateOf<String?>(null)

    /** [agentId] in the running shell, its panel left pinned open, dragged to [panelWidth]dp of this window (null: never dragged). */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(agentId: String, panelWidth: Int? = null): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        val window = compose.activity.resources.configuration.screenWidthDp.toFloat()
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
            graph.prefs.setPanelOpen(PaneWidthClass.Expanded, true)
            panelWidth?.let { graph.prefs.setPanelWidthFraction(it / window) }
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
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
        compose.waitUntil(30_000) { exists(hasTestTag("chat-header")) }
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

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun onScreen(text: String) = exists(hasText(text, substring = true))

    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    private val inPanel: SemanticsMatcher = hasAnyAncestor(hasTestTag("conversation-panel"))

    private fun waitForRail() {
        compose.waitUntil(30_000) { exists(sidebarList) }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
        compose.waitForIdle()
    }

    /** The Revenue chat's finished trace spliced in and its Details read in beside it. */
    private fun waitForDetails(graph: AppGraph) {
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(REVENUE_ID).value
            state.items.any { it is ActivityGroup } && state.traceStatus.pending == 0 && !onScreen("Loading the activity") && onScreen("1 thought")
        }
        compose.waitUntil(30_000) { exists(inPanel and hasText("Overview")) }
        compose.waitUntil(30_000) { !exists(inPanel and hasText("Loading", substring = true)) }
        compose.waitForIdle()
    }

    /** The Project's chat with both turns drawn and the Project's notes read in beside it. */
    private fun waitForNotes(graph: AppGraph) {
        compose.waitUntil(60_000) { onScreen("PR #215 (usage aggregation) is") }
        compose.waitUntil(60_000) { graph.conversations.state(DemoData.PROJECT_ID).value.let { state -> state.items.count { it is ActivityGroup } >= 2 && state.traceStatus.pending == 0 } }
        compose.waitUntil(30_000) { !onScreen("Loading the activity") }
        compose.waitUntil(30_000) { exists(hasTestTag("project-notes-tab")) && exists(inPanel and hasText("Deferred features", substring = true)) }
        compose.waitUntil(30_000) { !exists(inPanel and hasText("Reading", substring = true)) }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun notes(name: String) {
        val graph = show(DemoData.PROJECT_ID)
        waitForRail()
        waitForNotes(graph)
        capture(name)
    }

    private fun details(name: String, panelWidth: Int? = null) {
        val graph = show(REVENUE_ID, panelWidth)
        waitForRail()
        waitForDetails(graph)
        capture(name)
    }

    @Test
    fun desktopProjectNotes() = notes("820_panel_cap_desktop_project_notes")

    @Test
    fun desktopDetails() = details("821_panel_cap_desktop_details")

    @Test
    @Config(qualifiers = TABLET)
    fun tabletProjectNotes() = notes("822_panel_cap_tablet_project_notes")

    @Test
    @Config(qualifiers = TABLET)
    fun tabletDetails() = details("823_panel_cap_tablet_details")

    @Test
    fun desktopDraggedWide() = details("824_panel_cap_desktop_dragged_wide", panelWidth = 960)

    private companion object {
        const val STATUS_BAR_DP = 24
        const val REVENUE_ID = "bc-demo-0002"

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}

private const val DESKTOP = "w1920dp-h1080dp-night-160dpi"
private const val TABLET = "w1280dp-h800dp-night-160dpi"
