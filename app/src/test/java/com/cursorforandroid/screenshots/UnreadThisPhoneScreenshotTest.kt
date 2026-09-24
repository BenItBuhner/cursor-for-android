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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * Settings › "Unread only for chats from this phone": the sidebar over one account state — most of it started on the
 * laptop, one chat launched from this phone, two opened here — with the switch off (every chat this phone has no read
 * marker for is unread, the Project wears the badge, the folded Yesterday group its dot) and on (only the chats this
 * phone touched can be), composed by the real organizer from the same agents; then the Settings row itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class UnreadThisPhoneScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null, appearance: ProjectAppearance? = null, parent: String? = null) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - (ageMinutes + 40) * 60_000L,
        updatedAtMillis = NOW - ageMinutes * 60_000L,
        latestRunId = null,
        repoUrl = "https://github.com/acme/$id",
        startingRef = "main",
        branches = if (branch != null) listOf(GitBranch("github.com/acme/$id", branch, null)) else emptyList(),
        isProject = appearance != null,
        projectAppearance = appearance,
        parent = parent?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) },
    )

    private val agents = listOf(
        agent("billing", "Cesium billing launch", 50, appearance = ProjectAppearance("rocket", "purple")),
        agent("billing-w1", "Stripe webhook handler", 55, parent = "billing", branch = "cursor/webhooks"),
        agent("billing-w2", "Usage events aggregation", 58, parent = "billing", branch = "cursor/usage"),
        agent("shipyard", "Shipyard", 65, running = true, appearance = ProjectAppearance("logo-github", "blue")),
        agent("strategy", "Cesium Revenue Strategy", 26 * 60, running = true),
        agent("notes", "Release notes for 0.3.62", 12, branch = "cursor/release-notes"),
        agent("webhooks", "Refactor billing webhooks", 19, branch = "cursor/billing-webhooks"),
        agent("flaky", "Fix the flaky upload test", 27, branch = "cursor/flaky-upload"),
        agent("codex", "Codex-Poly-Bot Scaling", 34, running = true),
        agent("audit", "Dependency audit", 48, branch = "cursor/audit"),
        agent("onboarding", "Onboarding copy pass", 2 * 60),
        agent("release", "Latest release process", 28 * 60, branch = "cursor/release"),
        agent("deps", "Weekly dependency bump", 30 * 60, branch = "cursor/deps"),
    )

    /**
     * What this phone holds: "Release notes" launched here (its marker from the launch, the run finished since), "Fix
     * the flaky upload test" opened here once and moved on since from the laptop, "Onboarding copy pass" opened here
     * and read, Shipyard opened here; everything else it has never touched. The same for both frames but the switch.
     */
    private fun local(switchOn: Boolean): LocalAgentState {
        val at = agents.associate { it.id to it.listedAtMillis }
        return LocalAgentState(
            pinnedIds = setOf("strategy"),
            readMarkers = mapOf("notes" to at.getValue("notes") - 5 * 60_000L, "flaky" to at.getValue("flaky") - 60 * 60_000L, "onboarding" to at.getValue("onboarding"), "shipyard" to at.getValue("shipyard")),
            launchedHereIds = setOf("notes"),
            touchedHereIds = setOf("notes", "flaky", "onboarding", "shipyard"),
            unreadOnlyTouchedHere = switchOn,
        )
    }

    private fun showSidebar(switchOn: Boolean) {
        val local = local(switchOn)
        val sections = AgentListOrganizer.organize(agents, ListPreferences(), local, nowMillis = NOW, zone = ZoneOffset.UTC)
        compose.setContent {
            Scene {
                Sidebar(
                    state = AgentListUiState(sections = sections, hasLoaded = true, prefs = ListPreferences(), local = local, collapsedSections = setOf("date:Yesterday"), nowMillis = NOW),
                    user = USER,
                    isDemo = false,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = {},
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = {},
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        onNewProject = {},
                    ),
                    extendedMode = true,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Onboarding copy pass").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sidebarSwitchOff() {
        showSidebar(switchOn = false)
        compose.onNodeWithTag(YESTERDAY_DOT, useUnmergedTree = true).assertExists()
        capture("147_sidebar_unread_switch_off")
    }

    @Test
    fun sidebarSwitchOn() {
        showSidebar(switchOn = true)
        compose.onAllNodes(hasTestTag(YESTERDAY_DOT), useUnmergedTree = true).fetchSemanticsNodes().let { check(it.isEmpty()) { "The folded group of untouched chats still carries a dot." } }
        capture("148_sidebar_unread_switch_on")
    }

    @Test
    fun settingsRow() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        compose.setContent {
            Scene { SettingsScreen(graph = graph, user = USER, isDemo = false, onOpenSidebar = null, onBack = {}) }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.UNREAD_THIS_PHONE)).fetchSemanticsNodes().isNotEmpty() }
        // Below the New chat page picker: the Chats group is brought up to the top.
        compose.scrollSettingsGroupToTop(SettingsCopy.GROUP_CHATS)
        capture("149_settings_unread_this_phone")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
        const val NOW = 1_736_949_600_000L
        /** The dot a folded group's header carries while a row it hides is unread (see `SidebarSectionHeader`). */
        const val YESTERDAY_DOT = "section-unread-date:Yesterday"
        val USER = CursorUser("Cursor for Android (Pixel 9)", "bennett@example.com", "Bennett", "Buhner", 1L)
    }
}
