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
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.navigation.AppShell
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
 * A chat's header against the transcript's column in the running shell, under a 24dp status bar: where the controls
 * stand in the margin beside the column the header keeps no band and the transcript runs up to the status bar; where
 * they reach the column the band is kept as it always was.
 *
 * - `470`/`471`: a 1280dp tablet with the rail open (Bennett's Pixel Tablet), dark and light — clear of the column.
 * - `472`: a 1000dp tablet with the rail open, where the column reaches the panel and menu buttons — the band stays.
 * - `473`: the inner screen of a Pixel Fold with the rail open — the band stays; `474`: the rail collapsed, in the
 *   Project's chat, whose header has no pull request button — clear.
 * - `475`: a phone on its side with the rail collapsed — clear, the Project's chat longer than the window, so its
 *   older rows dissolve into the status bar's edge.
 * - `476`: the phone — the band stays, the frame the slim header (#283) has always drawn.
 *
 * Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HeaderClearanceScreenshotTest {

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

    /** The chat a notification hands the running shell, once it is up (see [ProjectNavigationTest]). */
    private var deepLink by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode, wide: Boolean, agentId: String): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = wide,
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

    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    /** The sidebar's list, told from anything else that scrolls by the section headers only it has. */
    private val sidebarList: SemanticsMatcher = hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    /** The rail at its top with the Projects group in it: the group lands last (see [AppScreenshotTest]). */
    private fun waitForRail() {
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
    }

    private fun collapseRail() {
        compose.onNodeWithContentDescription("Toggle sidebar").performClick()
        compose.waitUntil(10_000) { described("Open sidebar") }
        compose.waitForIdle()
    }

    /** The finished run's trace spliced in, by the frame's word (see [AppScreenshotTest.tabletTwoPane]). */
    private fun waitForRevenueChat(graph: AppGraph) {
        val id = REVENUE_ID
        compose.waitUntil(60_000) {
            val state = graph.conversations.state(id).value
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

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun tabletClear(mode: ThemeMode, name: String) {
        val graph = show(mode, wide = true, agentId = REVENUE_ID)
        waitForRail()
        waitForRevenueChat(graph)
        capture(name)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun tabletClearDark() = tabletClear(ThemeMode.Dark, "470_header_clear_tablet_dark")

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun tabletClearLight() = tabletClear(ThemeMode.Light, "471_header_clear_tablet_light")

    @Test
    @Config(qualifiers = "w1000dp-h720dp-night-320dpi")
    fun tabletRailReachesTheControls() {
        val graph = show(ThemeMode.Dark, wide = true, agentId = REVENUE_ID)
        waitForRail()
        waitForRevenueChat(graph)
        capture("472_header_band_tablet_rail")
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp-night-420dpi")
    fun foldableRailOpen() {
        val graph = show(ThemeMode.Dark, wide = true, agentId = REVENUE_ID)
        waitForRail()
        waitForRevenueChat(graph)
        capture("473_header_band_foldable")
    }

    @Test
    @Config(qualifiers = "w841dp-h701dp-night-420dpi")
    fun foldableRailCollapsed() {
        val graph = show(ThemeMode.Dark, wide = true, agentId = DemoData.PROJECT_ID)
        waitForRail()
        waitForProjectChat(graph)
        collapseRail()
        capture("474_header_clear_foldable_collapsed")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-night-420dpi")
    fun phoneOnItsSide() {
        val graph = show(ThemeMode.Dark, wide = true, agentId = DemoData.PROJECT_ID)
        waitForRail()
        waitForProjectChat(graph)
        collapseRail()
        capture("475_header_clear_landscape_phone")
    }

    @Test
    fun phone() {
        val graph = show(ThemeMode.Dark, wide = false, agentId = REVENUE_ID)
        waitForRevenueChat(graph)
        capture("476_header_band_phone")
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        const val REVENUE_ID = "bc-demo-0002"

        /** Wednesday 2025-01-15 14:00 UTC, as in [AppScreenshotTest]. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
