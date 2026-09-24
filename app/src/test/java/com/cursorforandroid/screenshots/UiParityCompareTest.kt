package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.Subscriptions
import com.cursorforandroid.ui.conversation.ConversationPills
import com.cursorforandroid.ui.conversation.ConversationPillsState
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.navigation.RailState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The parity comparison frames: the shell rendered at the reference captures' own size — a 1855 × 1154 window at
 * mdpi, so one dp is one pixel and one pixel is one of the web's CSS pixels — region by region, for the side-by-side
 * images the spec's compare step stitches next to the reference crops. Written to `app/build/ui-parity/compare/`;
 * not a CI check (its name keeps it out of the screenshot job). Run with
 * `./gradlew :app:recordRoborazziDebug --tests '*UiParityCompareTest*'`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1855dp-h1154dp-land-night-mdpi")
class UiParityCompareTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "build/ui-parity/compare").normalize().also { it.mkdirs() }

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
    private var deepLink by mutableStateOf<String?>(null)

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun launch(rail: RailState) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        runBlocking {
            graph.session.enterDemo()
            graph.prefs.setRailState("Expanded", rail.name)
            // The web's Project panel is 800px wide on this window.
            graph.prefs.setPanelWidthDp(800)
            graph.prefs.markRead(DemoData.PROJECT_ID, FIXED_NOW + 365L * 24 * 60 * 60_000L)
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Bennett", "Buhner", null),
                        isDemo = true,
                        windowWidthDp = 1855,
                        windowHeightDp = 1154,
                        deepLinkAgentId = deepLink,
                        onDeepLinkConsumed = { deepLink = null },
                    )
                }
            }
        }
        compose.waitUntil(60_000) { onScreen("Ask Cursor to build") }
        compose.waitUntil(60_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        deepLink = DemoData.PROJECT_ID
        compose.waitUntil(60_000) { onScreen("Follow up") }
        compose.waitUntil(60_000) { graph.conversations.state(DemoData.PROJECT_ID).value.items.count { it is ActivityGroup } >= 2 }
        compose.waitUntil(60_000) { onScreen("PR #215 (usage aggregation) is") }
        settle()
    }

    private fun captureNode(tag: String, name: String) {
        settle()
        compose.onNodeWithTag(tag).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun captureScreen(name: String) {
        settle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** 01: the sidebar, the content header and the pills over the composer, with the panel closed. */
    @Test
    fun chatWithSidebar() {
        launch(RailState.Expanded)
        // The sidebar is the app's own (main's), untagged: its region is the window's first 278 dp of the screen capture.
        captureScreen("render-01-chat")
        captureNode("cursor-header", "render-header")
        captureNode("composer-column", "render-composer")
    }

    /** 02, 03, 04: the panel as a pane on the Project's notes, in All Files, and on a document. */
    @Test
    fun panelSurfaces() {
        launch(RailState.Hidden)
        compose.onNodeWithContentDescription("Open panel").performClick()
        compose.waitUntil(30_000) { tagged("panel-pane") }
        compose.waitUntil(30_000) { onScreen("Shipping") }
        captureScreen("render-02-panel-notes")
        captureNode("panel-pane", "render-panel-notes")

        compose.onNodeWithContentDescription("All Files").performClick()
        settle()
        compose.waitUntil(30_000) { tagged("all-files-tab") }
        compose.waitUntil(30_000) { runCatching { compose.onNodeWithTag("all-files-tab").performScrollToNode(hasText("preferences.md")) }.isSuccess }
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Thumbnail of ", substring = true)).fetchSemanticsNodes().size >= 3 }
        captureScreen("render-03-panel-all-files")
        captureNode("panel-pane", "render-panel-all-files")

        compose.onNodeWithContentDescription("Folder docs").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("File private-edition-feasibility.md")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("File private-edition-feasibility.md").performScrollTo().performClick()
        compose.waitUntil(30_000) { onScreen("Private Edition") }
        captureScreen("render-04-panel-document")
        captureNode("panel-pane", "render-panel-document")
    }

    /** 06: a worker chat's pills — Changes with its counts and Open Desktop — over the composer's column. */
    @Test
    fun workerPills() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.width(760.dp).background(CursorTheme.colors.canvas).padding(vertical = 12.dp).testTag("scene")) {
                        ConversationPills(
                            ConversationPillsState(
                                changes = ConversationPillsState.ChangesSummary(3167, 139, 9),
                                canOpenDesktop = true,
                            ),
                            onAgents = {},
                            onChanges = {},
                            onOpenDesktop = {},
                        )
                    }
                }
            }
        }
        captureNode("scene", "render-worker-pills")
    }

    /** 01: a coordinator's pills — Agents with a worker needing input, Listening 3. */
    @Test
    fun coordinatorPills() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.width(760.dp).background(CursorTheme.colors.canvas).padding(vertical = 12.dp).testTag("scene2")) {
                        ConversationPills(
                            ConversationPillsState(
                                agents = ConversationPillsState.AgentsSummary(total = 3, working = 0, needsInput = 1),
                                listening = Subscriptions.Listening(listOf("GitHub PR", "GitHub CI", "Timer")),
                            ),
                            onAgents = {},
                            onChanges = {},
                            onOpenDesktop = {},
                        )
                    }
                }
            }
        }
        captureNode("scene2", "render-coordinator-pills")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
