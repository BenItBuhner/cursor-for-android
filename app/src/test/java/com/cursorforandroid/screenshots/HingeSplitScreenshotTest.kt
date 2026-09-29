package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Rect
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
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.panel.Hinge
import com.cursorforandroid.ui.panel.LocalHinge
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.panel.WindowHinge
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
 * The running shell on a Fold's inner screen, 841 x 701dp, held half open like a book with its crease down the middle
 * (420.5dp), the fold reported as `window-testing`'s [FoldingFeature] would have it: nothing stands across the crease.
 *
 * - `815`: the panel shut, the rail out: the rail fills the pane before the crease, the chat the pane past it.
 * - `816`: the rail put away: the chat stays in the pane past the crease, the pane before it left for the rail.
 * - `817`: the panel open, last dragged to 280dp with the rail out: it is the pane past the crease, and the chat the
 *   pane before it, the rail making way.
 * - `818`: the same Fold lying flat, one screen with no fold to keep off: laid out as it always has been.
 * - `819`: a tablet with no fold at all, the panel shut and the rail out: laid out as it always has been.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = FOLD_INNER)
class HingeSplitScreenshotTest {

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

    /** The fold down the middle of this window, [state] as the device is held; null for a device without one. */
    private fun crease(state: FoldingFeature.State?): WindowHinge {
        state ?: return WindowHinge()
        val metrics = compose.activity.resources.displayMetrics
        val bounds = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val fold = FoldingFeature(bounds, center = bounds.width() / 2, size = 0, state = state, orientation = FoldingFeature.Orientation.VERTICAL)
        return WindowHinge(Hinge.of(TestWindowLayoutInfo(listOf(fold)), metrics.density))
    }

    /**
     * The Revenue chat on a window folded as [fold] has it, the panel left [panelOpen] for the Expanded size class and
     * dragged to [panelWidth]dp of this window (null: never dragged).
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(fold: FoldingFeature.State?, panelOpen: Boolean, panelWidth: Int? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
            graph.prefs.setPanelOpen(PaneWidthClass.Expanded, panelOpen)
            panelWidth?.let { graph.prefs.setPanelWidthFraction(it / context.resources.configuration.screenWidthDp.toFloat()) }
        }
        val hinge = crease(fold)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalHinge provides hinge) {
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
        if (panelOpen) waitForPanel()
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

    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    private fun waitForRail() {
        compose.waitUntil(30_000) { exists(sidebarList) }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
        compose.waitForIdle()
    }

    private fun waitForRevenueChat(graph: AppGraph) {
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(REVENUE_ID).value
            state.items.any { it is ActivityGroup } && state.traceStatus.pending == 0 && !onScreen("Loading the activity") && onScreen("1 thought")
        }
        compose.waitForIdle()
    }

    private val inPanel: SemanticsMatcher = hasAnyAncestor(hasTestTag("conversation-panel"))

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

    @Test
    fun railOpen() {
        show(FoldingFeature.State.HALF_OPENED, panelOpen = false)
        waitForRail()
        capture("815_hinge_fold_rail_open")
    }

    @Test
    fun railClosed() {
        show(FoldingFeature.State.HALF_OPENED, panelOpen = false)
        waitForRail()
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { described("Open sidebar") }
        capture("816_hinge_fold_rail_closed")
    }

    @Test
    fun panelOpen() {
        show(FoldingFeature.State.HALF_OPENED, panelOpen = true, panelWidth = 280)
        compose.waitUntil(10_000) { described("Open sidebar") }
        capture("817_hinge_fold_panel_open")
    }

    @Test
    fun flat() {
        show(FoldingFeature.State.FLAT, panelOpen = false)
        waitForRail()
        capture("818_hinge_fold_flat")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletWithoutFold() {
        show(fold = null, panelOpen = false)
        waitForRail()
        capture("819_hinge_tablet_no_fold")
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        const val REVENUE_ID = "bc-demo-0002"

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}

private const val FOLD_INNER = "w841dp-h701dp-night-320dpi"
private const val TABLET = "w1280dp-h800dp-night-320dpi"
