package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarTags
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.settings.WhatsNewCopy
import com.cursorforandroid.ui.settings.WhatsNewScreen
import com.cursorforandroid.ui.settings.WhatsNewTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * What's new for the installed version: the page (the version and its release date in the header, the curated notes
 * in the transcript's markdown, the release page and Done in the footer) and the two ways in — the row beneath
 * "Check for updates" in Settings and the card in the sidebar's slot above the account footer. The notes are a
 * release's own (see [WhatsNewFixtures]); the page and the card carry that release's version, while the Settings
 * frame is titled with [SCREENSHOT_APP_VERSION] like the other Settings frames, so it reads as one version with the
 * row above it. None of them depends on the build's own version. Same device qualifiers as [AppScreenshotTest].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WhatsNewScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun setUp() {
        // The release date and the updater's status line carry dates; the clock, zone and locale are pinned so they read the same everywhere.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    /** A graph on a device running [version], with that version's notes already on its disk, unread; nothing here reaches GitHub. */
    private fun graph(version: String): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notes = WhatsNewFixtures.repository(PreferencesStore(context), folder.newFolder(), versionName = version, notes = WhatsNewFixtures.notes(version))
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, releaseNotes = notes, appVersion = version)
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun Scene(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The page: header, notes, footer. */
    @Test
    fun page() {
        page(ThemeMode.Dark, "90_whats_new_page")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun pageLight() {
        page(ThemeMode.Light, "164_whats_new_page_light")
    }

    private fun page(mode: ThemeMode, frame: String) {
        val graph = graph(WhatsNewFixtures.VERSION)
        compose.setContent { Scene(mode) { WhatsNewScreen(graph = graph, onBack = {}) } }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(WhatsNewTags.NOTES)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(WhatsNewCopy.title(WhatsNewFixtures.VERSION)).assertIsDisplayed()
        compose.onNodeWithText("Released Jan 14").assertIsDisplayed()
        compose.onNodeWithText("Goals").assertIsDisplayed()
        compose.onNodeWithTag(WhatsNewTags.DONE).assertIsDisplayed()
        capture(frame)
    }

    /** Settings, at the Version and updates card: the row directly beneath the version and its Check for updates. */
    @Test
    fun settingsRow() {
        val graph = graph(SCREENSHOT_APP_VERSION)
        compose.setContent { Scene { SettingsScreen(graph = graph, user = USER, isDemo = false, onOpenSidebar = null, onBack = {}) } }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.WHATS_NEW_ROW)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(WhatsNewCopy.title(SCREENSHOT_APP_VERSION)).assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsDisplayed()
        capture("91_whats_new_settings_row")
    }

    /** The sidebar with the card in the slot above the account footer, where "Update available" otherwise sits. */
    @Test
    fun sidebarCard() {
        compose.setContent {
            Scene {
                Sidebar(
                    state = AgentListUiState(sections = SECTIONS, hasLoaded = true, prefs = ListPreferences(), nowMillis = FIXED_NOW),
                    user = USER,
                    isDemo = false,
                    selectedAgentId = "codex",
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = {},
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, null, { _, _ -> }, {}),
                    ),
                    whatsNewHint = WhatsNewCopy.title(WhatsNewFixtures.VERSION),
                )
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Weekly dependency bump").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(SidebarTags.WHATS_NEW_HINT).assertIsDisplayed()
        capture("92_whats_new_sidebar_card")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; the release reads "Jan 14" and the rows' ages "15m" against it. */
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)

        fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null) = Agent(
            id = id,
            name = name,
            lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
            runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
            envType = EnvType.CLOUD,
            envName = null,
            url = "https://cursor.com/agents/$id",
            createdAtMillis = FIXED_NOW - (ageMinutes + 40) * 60_000L,
            updatedAtMillis = FIXED_NOW - ageMinutes * 60_000L,
            latestRunId = null,
            repoUrl = "https://github.com/acme/$id",
            startingRef = "main",
            branches = if (branch != null) listOf(GitBranch("github.com/acme/$id", branch, null)) else emptyList(),
        )

        fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read, pinned: Boolean = false) =
            AgentRow(agent = agent, indicator = indicator, isPinned = pinned, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false)

        val SECTIONS = listOf(
            AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", listOf(row(agent("strategy", "Cesium Revenue Strategy", 26 * 60, running = true), indicator = AgentIndicator.Running, pinned = true))),
            AgentSection(
                "date:Today", "Today",
                listOf(
                    row(agent("cli", "Cli exploration", 19, branch = "cursor/cli")),
                    row(agent("codex", "Codex-Poly-Bot Scaling", 34, running = true), indicator = AgentIndicator.Running),
                    row(agent("revenue", "Revenue Scaling Pipeline Research", 2 * 60, branch = "cursor/revenue"), indicator = AgentIndicator.Unread),
                ),
            ),
            AgentSection(
                "date:Yesterday", "Yesterday",
                listOf(
                    row(agent("release", "Latest release process", 28 * 60, branch = "cursor/release")),
                    row(agent("onboarding", "Onboarding copy pass", 30 * 60)),
                ),
            ),
            AgentSection("date:Last 7 Days", "Last 7 Days", listOf(row(agent("deps", "Weekly dependency bump", 3 * 24 * 60, branch = "cursor/deps")))),
        )
    }
}
