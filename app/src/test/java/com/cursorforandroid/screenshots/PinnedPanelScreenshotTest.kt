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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
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
 * The conversation panel pinned beside a chat in the running shell, under a 24dp status bar, at the narrowest and the
 * widest it drags to on each wide window. It comes up as a restart brings it back: left open for the window's size
 * class at the width it was dragged to. The chat keeps its least, 320dp, and the rail gives way before it does,
 * narrowing to 200dp and then stepping aside.
 *
 * - `507`/`508`: a 1280dp tablet on its side, the panel at 280dp and at 640dp; the rail keeps its 278dp either way.
 * - `509`/`510`: the tablet upright, 800dp: at 280dp the rail narrows to 200dp; at the panel's widest there, 480dp,
 *   the rail steps aside and its button comes to the chat's header.
 * - `511`/`512`: an 840dp foldable with the rail open: the panel at 280dp beside a 240dp rail, and at 320dp, the widest
 *   it goes with the rail still beside the chat, at 200dp.
 * - `513`/`514`: the foldable with the rail put away: the panel at 280dp and at its widest there, 520dp.
 * - `515`: the foldable with the panel at the 400dp it opens at, the rail asked for and come over the chat.
 * - `516`: the tablet in the light theme, the panel at 400dp.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TABLET)
class PinnedPanelScreenshotTest {

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

    /** The Revenue chat, its panel left pinned open for [widthClass] at [panelWidth]dp (null: never dragged). */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(widthClass: PaneWidthClass, panelWidth: Int?, mode: ThemeMode = ThemeMode.Dark) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
            graph.prefs.setPanelOpen(widthClass, true)
            panelWidth?.let { graph.prefs.setPanelWidthDp(it) }
        }
        compose.setContent {
            CursorTheme(mode = mode) {
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
        deepLink = REVENUE_ID
        compose.waitUntil(30_000) { exists(hasTestTag("chat-header")) }
        waitForRevenueChat(graph)
        waitForPanel()
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

    private fun described(description: String) = exists(hasContentDescription(description))

    /** The sidebar's list, told from anything else that scrolls by the section headers only it has. */
    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    /** The rail at its top with the Projects group in it: the group lands last (see [AppScreenshotTest]). */
    private fun waitForRail() {
        compose.waitUntil(30_000) { exists(sidebarList) }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
        compose.waitForIdle()
    }

    /** The finished run's trace spliced in, by the frame's word (see [HeaderClearanceScreenshotTest]). */
    private fun waitForRevenueChat(graph: AppGraph) {
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(REVENUE_ID).value
            state.items.any { it is ActivityGroup } && state.traceStatus.pending == 0 && !onScreen("Loading the activity") && onScreen("1 thought")
        }
        compose.waitForIdle()
    }

    private val inPanel: SemanticsMatcher = hasAnyAncestor(hasTestTag("conversation-panel"))

    /** The panel standing open with its Overview read in. */
    private fun waitForPanel() {
        compose.waitUntil(30_000) { exists(inPanel and hasText("Overview")) }
        compose.waitUntil(30_000) { !exists(inPanel and hasText("Loading", substring = true)) }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun pinned(widthClass: PaneWidthClass, panelWidth: Int?, name: String, rail: Boolean = true, mode: ThemeMode = ThemeMode.Dark) {
        show(widthClass, panelWidth, mode)
        if (rail) waitForRail() else compose.waitUntil(10_000) { described("Open sidebar") }
        capture(name)
    }

    @Test
    fun tabletNarrowest() = pinned(PaneWidthClass.Expanded, 280, "507_pinned_panel_tablet_min")

    @Test
    fun tabletWidest() = pinned(PaneWidthClass.Expanded, 640, "508_pinned_panel_tablet_max")

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletUprightNarrowest() = pinned(PaneWidthClass.Medium, 280, "509_pinned_panel_tablet_portrait_min")

    @Test
    @Config(qualifiers = TABLET_PORTRAIT)
    fun tabletUprightWidest() = pinned(PaneWidthClass.Medium, 480, "510_pinned_panel_tablet_portrait_max", rail = false)

    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldableRailOpenNarrowest() = pinned(PaneWidthClass.Expanded, 280, "511_pinned_panel_foldable_rail_open_min")

    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldableRailOpenWidest() = pinned(PaneWidthClass.Expanded, 320, "512_pinned_panel_foldable_rail_open_max")

    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldableRailClosedNarrowest() {
        show(PaneWidthClass.Expanded, 280)
        waitForRail()
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { described("Open sidebar") }
        capture("513_pinned_panel_foldable_rail_closed_min")
    }

    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldableRailClosedWidest() = pinned(PaneWidthClass.Expanded, 520, "514_pinned_panel_foldable_rail_closed_max", rail = false)

    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldableRailOverThePanel() {
        show(PaneWidthClass.Expanded, panelWidth = null)
        compose.waitUntil(10_000) { described("Open sidebar") }
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(10_000) { described("Close navigation menu") }
        waitForRail()
        capture("515_pinned_panel_foldable_flyout")
    }

    @Test
    fun tabletLight() = pinned(PaneWidthClass.Expanded, panelWidth = null, name = "516_pinned_panel_tablet_light", mode = ThemeMode.Light)

    private companion object {
        const val STATUS_BAR_DP = 24
        const val REVENUE_ID = "bc-demo-0002"

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}

private const val TABLET = "w1280dp-h800dp-night-320dpi"
private const val TABLET_PORTRAIT = "w800dp-h1280dp-night-320dpi"
private const val FOLDABLE = "w840dp-h700dp-night-320dpi"
