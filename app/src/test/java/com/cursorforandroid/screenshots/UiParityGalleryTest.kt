package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.navigation.RailState
import com.cursorforandroid.ui.navigation.WidthClass
import com.cursorforandroid.ui.navigation.WindowPosture
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
 * The parity gallery, the compact set for review: the demo Project's coordinator chat on six viewports — phone
 * portrait, Fold cover, Fold inner portrait and landscape, Tab portrait and landscape — crossed with the sidebar
 * showing and hidden and the panel's states — closed, the Project tab, a document tab — one frame each, named
 * `<viewport>-<rail>-<panel>.png` under `app/build/ui-parity/`. Not a CI check (its name keeps it out of the
 * screenshot job); run by hand with `./gradlew :app:recordRoborazziDebug --tests '*UiParityGalleryTest*'`.
 *
 * A compact window has no rail: its `expanded` is the drawer open over the chat (panel closed, since the drawer
 * covers it), its `hidden` the three panel states. Wide windows cross the sidebar showing and hidden by all three.
 * The All Files and side chat states stay reachable through [panelState] for a fuller set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class UiParityGalleryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "build/ui-parity").normalize().also { it.mkdirs() }

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

    private lateinit var graph: AppGraph
    private var widthClass: WidthClass = WidthClass.Compact
    /** Handed to the shell once it is up, as a notification's link is: the coordinator's chat opens over Home. */
    private var deepLink by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Shell(width: Int, height: Int) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    windowWidthDp = width,
                    windowHeightDp = height,
                    deepLinkAgentId = deepLink,
                    onDeepLinkConsumed = { deepLink = null },
                )
            }
        }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun described(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    /** Boots the demo shell at [width] × [height] on the coordinator's chat, with the rail in [rail] for this width class. */
    private fun launch(width: Int, height: Int, rail: RailState, sidebarWidthDp: Int? = null) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        widthClass = WidthClass.of(width)
        runBlocking {
            graph.session.enterDemo()
            if (widthClass != WidthClass.Compact) graph.prefs.setRailState(widthClass.name, rail.name)
            // The sidebar's width as if dragged there earlier: the shell reads it back for this width class.
            if (sidebarWidthDp != null && widthClass != WidthClass.Compact) graph.prefs.setSidebarWidthDp(widthClass.name, sidebarWidthDp)
            // The chat is open in every frame, so its Project reads as read whatever the demo's rows do meanwhile.
            graph.prefs.markRead(DemoData.PROJECT_ID, FIXED_NOW + 365L * 24 * 60 * 60_000L)
        }
        compose.setContent { Shell(width, height) }
        compose.waitUntil(60_000) { onScreen("Ask Cursor to build") }
        compose.waitUntil(60_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        deepLink = DemoData.PROJECT_ID
        compose.waitUntil(60_000) { onScreen("Follow up") }
        compose.waitUntil(60_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        // The coordinator's transcript settles once its worker cards and the trace are in.
        compose.waitUntil(60_000) { graph.conversations.state(DemoData.PROJECT_ID).value.items.count { it is ActivityGroup } >= 2 }
        compose.waitUntil(60_000) { onScreen("PR #215 (usage aggregation) is") }
        settle()
    }

    private fun setRail(rail: RailState) {
        if (widthClass == WidthClass.Compact) return
        runBlocking { graph.prefs.setRailState(widthClass.name, rail.name) }
        // The shell keeps the reader's own choice for the composition; the saved one reaches it only after a fresh
        // composition, so the toggles are used: the sidebar's hides it, the header's brings it back.
        if (currentRail() != rail) {
            when (currentRail()) {
                RailState.Expanded -> compose.onNodeWithContentDescription("Toggle sidebar").performClick()
                RailState.Hidden -> compose.onNodeWithContentDescription("Open sidebar").performClick()
            }
            settle()
        }
        compose.waitUntil(20_000) { currentRail() == rail }
        settle()
    }

    private fun currentRail(): RailState = if (described("Toggle sidebar")) RailState.Expanded else RailState.Hidden

    private fun openPanel() {
        if (!tagged("conversation-panel")) {
            compose.onNodeWithContentDescription("Open panel").performClick()
            compose.waitUntil(20_000) { tagged("conversation-panel") }
        }
        settle()
    }

    private fun closePanel() {
        if (tagged("conversation-panel")) {
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.waitUntil(20_000) { !tagged("conversation-panel") }
        }
        settle()
    }

    /** Brings the tab into the strip's view first: a tab scrolled off the end of a narrow pane takes no tap. */
    private fun selectTab(key: String) {
        compose.onNodeWithTag("panel-tab-$key").performScrollTo().performClick()
        settle()
    }

    private fun panelState(panel: String) {
        when (panel) {
            "closed" -> closePanel()
            "project" -> {
                openPanel()
                selectTab("project")
                if (compose.onAllNodes(hasContentDescription("All Files") and isSelected()).fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithContentDescription("All Files").performClick()
                    settle()
                }
                compose.waitUntil(30_000) { onScreen("Shipping") }
            }
            "allfiles" -> {
                openPanel()
                selectTab("project")
                if (compose.onAllNodes(hasContentDescription("All Files") and isSelected()).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithContentDescription("All Files").performClick()
                    settle()
                }
                // Both trees listed (either root's files may sit below the fold on a short window), then back to the top.
                compose.waitUntil(30_000) { tagged("all-files-tab") }
                compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("all-files-tab").performScrollToNode(hasText("notes.md")) }.isSuccess }
                compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("all-files-tab").performScrollToNode(hasText("preferences.md")) }.isSuccess }
                compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("all-files-tab").performScrollToNode(hasTestTag("recents-row")) }.isSuccess }
                compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Thumbnail of ", substring = true)).fetchSemanticsNodes().size >= 3 }
                compose.onNodeWithTag("all-files-tab").performScrollToIndex(0)
            }
            "doc" -> {
                openPanel()
                val key = "doc:${com.cursorforandroid.data.demo.DemoStores.PROJECT_STORE_ID}:docs/private-edition-feasibility.md"
                if (tagged("panel-tab-$key")) {
                    selectTab(key)
                } else {
                    selectTab("project")
                    if (compose.onAllNodes(hasContentDescription("All Files") and isSelected()).fetchSemanticsNodes().isEmpty()) {
                        compose.onNodeWithContentDescription("All Files").performClick()
                        settle()
                    }
                    compose.waitUntil(30_000) { described("Folder docs") }
                    compose.onNodeWithContentDescription("Folder docs").performScrollTo().performClick()
                    compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("all-files-tab").performScrollToNode(hasContentDescription("File private-edition-feasibility.md")) }.isSuccess }
                    compose.onNodeWithContentDescription("File private-edition-feasibility.md").performClick()
                }
                compose.waitUntil(30_000) { onScreen("Private Edition") }
            }
            "sidechat" -> {
                openPanel()
                val key = "side:bc-demo-0021"
                if (tagged("panel-tab-$key")) {
                    selectTab(key)
                } else {
                    // The side chats are in the chat's sections, the panel's other surface: the Agents pill above the
                    // composer opens it (the panel, a sheet on a phone, covers the header's menu).
                    closePanel()
                    compose.onNodeWithTag("pill-agents").performClick()
                    compose.waitUntil(20_000) { tagged("panel-sections") }
                    compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-SideChats"))
                    compose.onNodeWithTag("section-SideChats").performClick()
                    compose.waitUntil(30_000) { tagged("side-chat") }
                    compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("side-chat"))
                    compose.onAllNodesWithTag("side-chat")[0].performClick()
                }
                compose.waitUntil(60_000) { onScreen("Three tiers, one line of promise each") }
            }
        }
        settle()
    }

    private fun capture(name: String) {
        settle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun wideGallery(viewport: String, width: Int, height: Int) {
        launch(width, height, RailState.Expanded)
        for (rail in listOf(RailState.Expanded, RailState.Hidden)) {
            setRail(rail)
            for (panel in PANELS) {
                panelState(panel)
                capture("$viewport-${rail.slug}-$panel")
            }
            closePanel()
        }
    }

    private fun compactGallery(viewport: String, width: Int, height: Int) {
        launch(width, height, RailState.Hidden)
        for (panel in PANELS) {
            panelState(panel)
            capture("$viewport-hidden-$panel")
        }
        closePanel()
        // The drawer open over the chat — the compact window's "expanded" rail — pulled in from the start edge, as a
        // phone's chat has a back button rather than a sidebar button in its header.
        compose.onRoot().performTouchInput { swipeRight(startX = 2f, endX = width * 0.9f) }
        compose.waitUntil(20_000) { described("New chat") }
        compose.waitUntil(30_000) { onScreen("Projects") }
        capture("$viewport-expanded-closed")
        Espresso.pressBack()
        settle()
    }

    private val RailState.slug: String get() = when (this) { RailState.Expanded -> "expanded"; RailState.Hidden -> "hidden" }

    @Test
    fun phonePortrait() = compactGallery("phone-portrait", 411, 914)

    @Test
    @Config(sdk = [35], qualifiers = "w409dp-h955dp-night-420dpi")
    fun foldCover() = compactGallery("fold-cover", 409, 955)

    @Test
    @Config(sdk = [35], qualifiers = "w856dp-h949dp-night-360dpi")
    fun foldInnerPortrait() = wideGallery("fold-inner-portrait", 856, 949)

    @Test
    @Config(sdk = [35], qualifiers = "w949dp-h856dp-land-night-360dpi")
    fun foldInnerLandscape() = wideGallery("fold-inner-landscape", 949, 856)

    @Test
    @Config(sdk = [35], qualifiers = "w936dp-h1497dp-night-280dpi")
    fun tabPortrait() = wideGallery("tab-portrait", 936, 1497)

    @Test
    @Config(sdk = [35], qualifiers = "w1497dp-h936dp-land-night-280dpi")
    fun tabLandscape() = wideGallery("tab-landscape", 1497, 936)

    /** The sidebar dragged to its narrowest, the Project tab open beside it as a pane. */
    @Test
    @Config(sdk = [35], qualifiers = "w1497dp-h936dp-land-night-280dpi")
    fun tabLandscapeSidebarNarrow() {
        launch(1497, 936, RailState.Expanded, sidebarWidthDp = WindowPosture.SIDEBAR_MIN_DP)
        compose.waitUntil(20_000) { compose.onAllNodes(hasStateDescription("${WindowPosture.SIDEBAR_MIN_DP} dp")).fetchSemanticsNodes().isNotEmpty() }
        panelState("project")
        capture("tab-landscape-narrow-project")
    }

    /** The sidebar dragged to its widest, the Project tab still a pane beside it (1497 − 400 − 360 leaves 737 for the chat). */
    @Test
    @Config(sdk = [35], qualifiers = "w1497dp-h936dp-land-night-280dpi")
    fun tabLandscapeSidebarWide() {
        launch(1497, 936, RailState.Expanded, sidebarWidthDp = WindowPosture.SIDEBAR_MAX_DP)
        compose.waitUntil(20_000) { compose.onAllNodes(hasStateDescription("${WindowPosture.SIDEBAR_MAX_DP} dp")).fetchSemanticsNodes().isNotEmpty() }
        panelState("project")
        capture("tab-landscape-wide-project")
    }

    private companion object {
        val PANELS = listOf("closed", "project", "doc")
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
