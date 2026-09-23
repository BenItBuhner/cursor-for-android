package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarShortLists
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The twelve Projects of the sidebar that asked for it: cut to their first five with a quiet "Show 7 more" under
 * them (its dot saying an unread Project is among the seven), then listed in full with "Show less" at the end; in
 * the dark theme and the light. Written to `screenshots/` beside the walkthrough; CI compares them pixel for pixel
 * (`verifyRoborazziDebug`), and `recordRoborazziDebug` re-records them on purpose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarShowMoreScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(mode: ThemeMode, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean, appearance: ProjectAppearance? = null, isProject: Boolean = appearance != null) = Agent(
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
        branches = emptyList(),
        isProject = isProject,
        projectAppearance = appearance,
    )

    private fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read, pinned: Boolean = false, memberCount: Int? = null) =
        AgentRow(agent = agent, indicator = indicator, isPinned = pinned, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false, memberCount = memberCount)

    private fun project(id: String, name: String, icon: String?, colorId: String, members: Int, indicator: AgentIndicator = AgentIndicator.Read) = row(
        agent(id, name, 65, running = indicator == AgentIndicator.Running, appearance = icon?.let { ProjectAppearance(it, colorId) }, isProject = true),
        indicator = indicator,
        memberCount = members,
    )

    private fun chat(id: String, name: String, ageMinutes: Long, running: Boolean = false) =
        row(agent(id, name, ageMinutes, running = running), indicator = if (running) AgentIndicator.Running else AgentIndicator.Read)

    private val sections = listOf(
        AgentSection(
            AgentListOrganizer.PROJECTS_KEY, "Projects",
            listOf(
                project("zenium", "Zenium", "browser", "orange", 28, AgentIndicator.Unread),
                project("polymarket", "Polymarket Bot Scaling & Research", "chart-pyramid", "blue", 123, AgentIndicator.Running),
                project("revenue", "Revenue Scaling Pipeline", "currency-dollar", "green", 276, AgentIndicator.Unread),
                project("shopify", "Shopify Competitor", "shopping-basket", "blue", 15, AgentIndicator.Unread),
                project("noetic", "Noetic", null, "default", 79, AgentIndicator.Running),
                project("sshit", "SSHit", "terminal", "default", 35, AgentIndicator.Unread),
                project("android", "Cursor for Android", "cube", "default", 62, AgentIndicator.Unread),
                project("murmur", "Murmur", "target", "magenta", 18, AgentIndicator.Unread),
                project("shipyard", "Shipyard", "logo-github", "blue", 42),
                project("cesium", "Cesium", "robot", "purple", 5),
                project("meter", "Codex Meter", "chart-bars", "default", 25),
                project("bounty", "Job & Bounty Research", "briefcase", "red", 8),
            ),
        ),
        AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", listOf(row(agent("strategy", "Cesium Revenue Strategy", 26 * 60, running = false), pinned = true))),
        AgentSection(
            "date:Today", "Today",
            listOf(
                chat("bunny", "Space bunny capabilities assessment", 19, running = true),
                chat("trek", "Star Trek TNG bridge recreation", 34, running = true),
                chat("cats", "Cats JS animation", 2 * 60, running = true),
            ),
        ),
        AgentSection("date:Last 30 Days", "Last 30 Days", (1..30).map { chat("month-$it", "Last month's chat $it", (2 + it) * 24 * 60L) }),
        AgentSection("date:Older", "Older", (1..106).map { chat("older-$it", "Older chat $it", (40 + it) * 24 * 60L) }),
    )

    private fun show(mode: ThemeMode, listedInFull: Boolean) {
        val shortLists = SidebarShortLists().apply { if (listedInFull) expand(AgentListOrganizer.PROJECTS_KEY) }
        compose.setContent {
            Scene(mode) {
                Sidebar(
                    state = AgentListUiState(
                        sections = sections,
                        hasLoaded = true,
                        prefs = ListPreferences(),
                        collapsedSections = setOf(AgentListOrganizer.PINNED_KEY, "date:Last 30 Days", "date:Older"),
                        nowMillis = NOW,
                    ),
                    user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
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
                    shortLists = shortLists,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(if (listedInFull) "Show less" else "Show 7 more").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun shortenedDark() {
        show(ThemeMode.Dark, listedInFull = false)
        capture("205_sidebar_projects_shortened")
    }

    @Test
    fun listedInFullDark() {
        show(ThemeMode.Dark, listedInFull = true)
        capture("206_sidebar_projects_listed_in_full")
    }

    @Test
    fun shortenedLight() {
        show(ThemeMode.Light, listedInFull = false)
        capture("207_sidebar_projects_shortened_light")
    }

    @Test
    fun listedInFullLight() {
        show(ThemeMode.Light, listedInFull = true)
        capture("208_sidebar_projects_listed_in_full_light")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
        const val NOW = 1_736_949_600_000L
    }
}
