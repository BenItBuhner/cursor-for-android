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
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
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
 * The sidebar's group headers with their chevrons: every group open, the Projects header wearing the plus beside its
 * chevron as a pair; then Pinned and Today folded closed, their headers carrying the count of the rows they hide and,
 * for Today, the dot that says one of them is unread. Written to `screenshots/` beside the walkthrough; CI compares
 * them pixel for pixel (`verifyRoborazziDebug`), and `recordRoborazziDebug` re-records them on purpose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarSectionsScreenshotTest {

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
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null, appearance: ProjectAppearance? = null, parent: AgentParent? = null) = Agent(
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
        parent = parent,
    )

    private fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read, pinned: Boolean = false, children: List<AgentRow> = emptyList()) =
        AgentRow(agent = agent, indicator = indicator, isPinned = pinned, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false, children = children)

    private fun project(id: String, name: String, icon: String, colorId: String, workers: Int, indicator: AgentIndicator = AgentIndicator.Read): AgentRow {
        val parent = AgentParent(id, AgentParentKind.PROJECT_WORKER)
        val children = (1..workers).map { row(agent("$id-w$it", "Worker $it", 30, parent = parent, branch = "cursor/$id-$it")) }
        return row(agent(id, name, 65, running = indicator == AgentIndicator.Running, appearance = ProjectAppearance(icon, colorId)), indicator = indicator, children = children)
    }

    private val sections = listOf(
        AgentSection(
            AgentListOrganizer.PROJECTS_KEY, "Projects",
            listOf(
                project("billing", "Cesium billing launch", "rocket", "purple", workers = 3),
                project("shipyard", "Shipyard", "logo-github", "blue", workers = 4, indicator = AgentIndicator.Running),
            ),
        ),
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

    private fun show(collapsed: Set<String>) {
        compose.setContent {
            Scene {
                Sidebar(
                    state = AgentListUiState(sections = sections, hasLoaded = true, prefs = ListPreferences(), collapsedSections = collapsed, nowMillis = NOW),
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
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Weekly dependency bump").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun everyGroupOpen() {
        show(collapsed = emptySet())
        capture("88_sidebar_section_chevrons")
    }

    @Test
    fun pinnedAndTodayFolded() {
        show(collapsed = setOf(AgentListOrganizer.PINNED_KEY, "date:Today"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cli exploration").fetchSemanticsNodes().isEmpty() }
        capture("89_sidebar_section_collapsed")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; ages read "15m" against it. */
        const val NOW = 1_736_949_600_000L
    }
}
