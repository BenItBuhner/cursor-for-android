package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
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
import com.cursorforandroid.ui.projects.AppearanceSheet
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
 * The Project icon catalog where Projects appear: a sidebar whose Projects group wears brand and product marks
 * (Notion, GitHub, Rust, Figma, Linear, Python) beside line icons, in the ten tones, with a coordinator working in
 * its own colour and another unread; and the icon-and-colour picker itself, open on a brand mark with the grid
 * tinted to the chosen tone. Written to `screenshots/` beside the walkthrough; CI compares them pixel for pixel
 * (`verifyRoborazziDebug`), and `recordRoborazziDebug` re-records them on purpose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectIconsScreenshotTest {

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
    private fun Scene(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun agent(id: String, name: String, appearance: ProjectAppearance? = null, running: Boolean = false, parent: AgentParent? = null, branch: String? = null) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - 7_200_000L,
        updatedAtMillis = NOW - 900_000L,
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

    private fun project(id: String, name: String, icon: String, colorId: String, indicator: AgentIndicator = AgentIndicator.Read, workers: Int = 0): AgentRow {
        val parent = AgentParent(id, AgentParentKind.PROJECT_WORKER)
        val children = (1..workers).map { row(agent("$id-w$it", "Worker $it", parent = parent, branch = "cursor/$id-$it")) }
        return row(agent(id, name, ProjectAppearance(icon, colorId), running = indicator == AgentIndicator.Running), indicator = indicator, children = children)
    }

    private val sidebarState = AgentListUiState(
        sections = listOf(
            AgentSection(
                AgentListOrganizer.PROJECTS_KEY, "Projects",
                listOf(
                    project("notion", "Knowledge base sync", "logo-notion", "default", workers = 4),
                    project("shipyard", "Shipyard", "logo-github", "purple", indicator = AgentIndicator.Running, workers = 31),
                    project("rustlings", "Rustlings port", "file-type-rust", "orange", workers = 3),
                    project("design", "Design system", "logo-figma", "magenta", indicator = AgentIndicator.Unread, workers = 8),
                    project("linear", "Linear triage", "logo-linear", "blue", workers = 2),
                    project("py", "Data pipeline", "logo-python", "yellow", workers = 6),
                    project("slack", "Slack digest bot", "logo-slack", "green", workers = 1),
                    project("revenue", "Revenue Scaling Pipeline", "rocket", "brand", indicator = AgentIndicator.Running, workers = 115),
                    project("murmur", "Murmur", "target", "cyan", workers = 6),
                    project("android", "Cursor for Android", "cursor-logo", "red", workers = 17),
                ),
            ),
            AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", listOf(row(agent("pin", "Codex-Poly-Bot Scaling", branch = "cursor/scaling"), pinned = true))),
            AgentSection(
                "date:Today", "Today",
                listOf(
                    row(agent("t1", "New chat creation issue"), indicator = AgentIndicator.Unread),
                    row(agent("t2", "User interface polish", branch = "cursor/polish")),
                    row(agent("t3", "Model picker stability")),
                ),
            ),
        ),
        hasLoaded = true,
        prefs = ListPreferences(),
        nowMillis = NOW,
    )

    @Test
    fun sidebarWithBrandIcons() {
        compose.setContent {
            Scene {
                Sidebar(
                    state = sidebarState,
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
                    ),
                    extendedMode = true,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cursor for Android").fetchSemanticsNodes().isNotEmpty() }
        capture("54_sidebar_project_icons")
    }

    @Test
    fun iconAndColourPicker() {
        compose.setContent {
            Scene {
                AppearanceSheet(current = ProjectAppearance("logo-notion", "purple"), onPick = {}, onDismiss = {})
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Icon and colour").fetchSemanticsNodes().isNotEmpty() }
        // The grid opens on its first section, the brand marks, where the chosen Notion cell sits marked.
        compose.onNodeWithContentDescription("Icons").performScrollToNode(hasText("Brands & tools"))
        compose.onNodeWithContentDescription("Icon Notion").assertIsDisplayed()
        capture("55_project_icon_picker")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; ages read "15m" against it. */
        const val NOW = 1_736_949_600_000L
    }
}
