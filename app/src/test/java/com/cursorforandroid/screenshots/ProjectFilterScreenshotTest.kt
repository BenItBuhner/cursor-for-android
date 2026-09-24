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
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarTail
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Chats filters and the Projects group, through [AgentListOrganizer] as the sidebar is: the same account once
 * with every filter on, and once with merged pull requests excluded. The chats whose pull requests were merged go;
 * the three Projects stay — the one whose coordinator's own pull request is merged included — with their counts,
 * and the filter glyph reads accented. Written to `screenshots/` beside the walkthrough; CI compares them pixel for
 * pixel (`verifyRoborazziDebug`), and `recordRoborazziDebug` re-records them on purpose.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectFilterScreenshotTest {

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

    private fun agent(
        id: String,
        name: String,
        ageMinutes: Long,
        repo: String = "techlitnow/cesium",
        running: Boolean = false,
        pr: Int? = null,
        appearance: ProjectAppearance? = null,
        parent: AgentParent? = null,
        source: AgentSource = AgentSource.WEBSITE,
    ) = Agent(
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
        repoUrl = "https://github.com/$repo",
        startingRef = "main",
        branches = if (pr != null) listOf(GitBranch("github.com/$repo", "cursor/${id.removePrefix("bc-")}", "https://github.com/$repo/pull/$pr")) else emptyList(),
        source = source,
        isProject = appearance != null,
        projectAppearance = appearance,
        parent = parent,
    )

    private fun worker(project: String) = AgentParent(project, AgentParentKind.PROJECT_WORKER)

    /**
     * Bennett's account in miniature: three Projects — the billing launch, whose coordinator's own pull request is
     * merged; Shipyard, at work with an open one; the design system, with none — and the chats of the account's own,
     * three of them with merged pull requests. Every pull request's state as GitHub last reported it is in [local].
     */
    private val agents = listOf(
        agent("bc-billing", "Cesium billing launch", 65, pr = 214, appearance = ProjectAppearance("rocket", "purple")),
        agent("bc-usage", "Usage events aggregation", 80, pr = 215, parent = worker("bc-billing")),
        agent("bc-stripe", "Stripe webhook handler", 95, pr = 218, parent = worker("bc-billing")),
        agent("bc-pricing", "Pricing page copy", 110, parent = AgentParent("bc-billing", AgentParentKind.SIDE_CHAT)),
        agent("bc-shipyard", "Shipyard", 12, repo = "bennett/shipyard", running = true, pr = 40, appearance = ProjectAppearance("logo-github", "blue")),
        agent("bc-ship-1", "Release notes generator", 14, repo = "bennett/shipyard", pr = 38, parent = worker("bc-shipyard")),
        agent("bc-ship-2", "Signed APK pipeline", 30, repo = "bennett/shipyard", running = true, pr = 41, parent = worker("bc-shipyard")),
        agent("bc-ship-3", "Changelog backfill", 55, repo = "bennett/shipyard", pr = 36, parent = worker("bc-shipyard")),
        agent("bc-design", "Design system", 3 * 60, repo = "bennett/visual-engine", appearance = ProjectAppearance("logo-figma", "magenta")),
        agent("bc-tokens", "Colour tokens from Figma", 3 * 60 + 20, repo = "bennett/visual-engine", parent = worker("bc-design")),
        agent("bc-type", "Type scale audit", 4 * 60, repo = "bennett/visual-engine", parent = worker("bc-design")),
        agent("bc-codex", "Codex-Poly-Bot Scaling", 34, repo = "bennett/codex-poly-bot", running = true, source = AgentSource.EDITOR),
        agent("bc-revenue", "Revenue Scaling Pipeline Research", 2 * 60, pr = 209),
        agent("bc-release", "Latest release process", 3 * 60, repo = "bennett/cursor-for-android", pr = 3, source = AgentSource.API),
        agent("bc-cli", "Cli exploration", 19, repo = "bennett/cursor-for-android", source = AgentSource.CLI),
        agent("bc-onboarding", "Onboarding copy pass", 5 * 60, pr = 201),
        agent("bc-deps", "Weekly dependency bump", 7 * 60, repo = "bennett/cursor-for-android", pr = 1, source = AgentSource.AUTOMATIONS),
        agent("bc-strategy", "Cesium Revenue Strategy", 26 * 60, running = true, source = AgentSource.SLACK),
    )

    private val local = LocalAgentState(
        pinnedIds = setOf("bc-strategy"),
        readMarkers = agents.associate { it.id to it.updatedAtMillis } - "bc-design" - "bc-revenue",
        pullRequests = mapOf(
            "https://github.com/techlitnow/cesium/pull/214" to PullRequestState.Merged,
            "https://github.com/techlitnow/cesium/pull/215" to PullRequestState.Merged,
            "https://github.com/techlitnow/cesium/pull/218" to PullRequestState.Open,
            "https://github.com/bennett/shipyard/pull/40" to PullRequestState.Open,
            "https://github.com/bennett/shipyard/pull/38" to PullRequestState.Merged,
            "https://github.com/bennett/shipyard/pull/41" to PullRequestState.Draft,
            "https://github.com/bennett/shipyard/pull/36" to PullRequestState.Merged,
            "https://github.com/techlitnow/cesium/pull/209" to PullRequestState.Open,
            "https://github.com/bennett/cursor-for-android/pull/3" to PullRequestState.Merged,
            "https://github.com/techlitnow/cesium/pull/201" to PullRequestState.Merged,
            "https://github.com/bennett/cursor-for-android/pull/1" to PullRequestState.Merged,
        ),
    )

    private fun state(prefs: ListPreferences) = AgentListUiState(
        sections = AgentListOrganizer.organize(agents, prefs, local, nowMillis = NOW),
        allAgents = agents,
        prefs = prefs,
        local = local,
        hasLoaded = true,
        nowMillis = NOW,
    )

    private fun show(state: AgentListUiState) {
        compose.setContent {
            Scene {
                Sidebar(
                    state = state,
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cesium Revenue Strategy").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun sidebarWithEveryFilterOn() {
        val state = state(ListPreferences())
        assertThat(state.sections.map { it.title }).containsExactly("Projects", "Pinned", "Today").inOrder()
        assertThat(state.sections.last().rows.map { it.agent.name })
            .containsExactly("Cli exploration", "Codex-Poly-Bot Scaling", "Revenue Scaling Pipeline Research", "Latest release process", "Onboarding copy pass", "Weekly dependency bump").inOrder()
        show(state)
        capture("56_sidebar_filter_all")
    }

    @Test
    fun sidebarWithMergedPullRequestsExcluded() {
        val prefs = ListPreferences(git = GitFilter.entries.toSet() - GitFilter.Merged)
        val state = state(prefs)
        // The chats whose pull requests were merged are gone; every Project stands, its tree whole.
        val projects = state.sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        assertThat(projects.map { it.agent.name }).containsExactly("Shipyard", "Cesium billing launch", "Design system").inOrder()
        assertThat(projects.map { it.descendants().size }).containsExactly(3, 3, 2).inOrder()
        assertThat(state.sections.filterNot { it.key == AgentListOrganizer.PROJECTS_KEY }.flatMap { it.rows }.map { it.agent.name })
            .containsExactly("Cesium Revenue Strategy", "Cli exploration", "Codex-Poly-Bot Scaling", "Revenue Scaling Pipeline Research").inOrder()
        show(state)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Latest release process").fetchSemanticsNodes().isEmpty() }
        capture("57_sidebar_filter_projects_kept")
    }

    /**
     * The account after one page: the rows loaded are the three Projects above and their chats, and the root
     * registry knows six more Projects whose rows no page holds yet — their records' names and looks, and the
     * account's count of their members. Every one of the nine renders, the six as stand-ins the fetch by id will
     * replace, in the Projects group's order (the loaded ones by activity, the stand-ins after them by name).
     */
    @Test
    fun sidebarWithProjectsBeyondTheLoadedPages() {
        val seen = NOW - 20 * 60_000L
        val registry = listOf(
            KnownRoot("bc-billing", "Cesium billing launch", ProjectAppearance("rocket", "purple"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-shipyard", "Shipyard", ProjectAppearance("logo-github", "blue"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-design", "Design system", ProjectAppearance("logo-figma", "magenta"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-mobile", "Cursor for Android", ProjectAppearance("phone", "green"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-notion", "Notion knowledge base", ProjectAppearance("logo-notion", "gray"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-infra", "Infra cost review", ProjectAppearance("gauge", "orange"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-linear", "Linear triage", ProjectAppearance("logo-linear", "indigo"), signal = LineageSignal.MEMBERSHIP, lastSeenMillis = seen),
            KnownRoot("bc-rust", "Rust rewrite spike", ProjectAppearance("logo-rust", "red"), signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
            KnownRoot("bc-old", "Q3 launch retro", ProjectAppearance("flag", "yellow"), archived = true, signal = LineageSignal.ACCOUNT_RECORD, lastSeenMillis = seen),
        )
        val counts = mapOf("bc-billing" to 3, "bc-shipyard" to 3, "bc-design" to 2, "bc-mobile" to 5, "bc-notion" to 1, "bc-infra" to 2, "bc-linear" to 4, "bc-rust" to 2, "bc-old" to 6)
        val prefs = ListPreferences()
        val sections = AgentListOrganizer.organize(agents, prefs, local, nowMillis = NOW, knownRoots = registry, memberCounts = counts)
        val projects = sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        // Eight Projects render — the archived one waits behind the Archived filter — the loaded three first.
        assertThat(projects.map { it.agent.name })
            .containsExactly("Shipyard", "Cesium billing launch", "Design system", "Cursor for Android", "Infra cost review", "Linear triage", "Notion knowledge base", "Rust rewrite spike").inOrder()
        assertThat(projects.filter { it.isStandIn }.map { it.agent.id }).containsExactly("bc-mobile", "bc-infra", "bc-linear", "bc-notion", "bc-rust").inOrder()
        assertThat(projects.none { it.isPlaceholder }).isTrue()
        assertThat(projects.map { it.shownCount }).containsExactly(3, 3, 2, 5, 2, 4, 1, 2).inOrder()
        assertThat(projects.all { it.agent.isProjectRoot && it.agent.projectAppearance != null }).isTrue()
        val archivedToo = AgentListOrganizer.organize(agents, ListPreferences(statuses = StatusFilter.entries.toSet()), local, nowMillis = NOW, knownRoots = registry, memberCounts = counts)
            .first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows
        assertThat(archivedToo.map { it.agent.name }).contains("Q3 launch retro")
        // Every one of the eight listed, not the first five.
        show(AgentListUiState(sections = sections, allAgents = agents, prefs = prefs, local = local, hasLoaded = true, nowMillis = NOW, shortenLongGroups = false))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Rust rewrite spike").fetchSemanticsNodes().isNotEmpty() }
        capture("58_sidebar_all_projects")
    }

    /**
     * The sidebar's one loading row: a pull's indicator is let go once the first page and the status scan have
     * landed, and while the older pages and the rows fetched by id are still on the wire the list ends in
     * "Loading more…" — the same row a page the reader scrolled to shows, never two rows at once.
     */
    @Test
    fun sidebarLoadingMore() {
        val state = state(ListPreferences()).copy(tail = SidebarTail.Loading(listOf("list page 2")))
        show(state)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading more\u2026").fetchSemanticsNodes().isNotEmpty() }
        capture("125_sidebar_loading_more")
    }

    /** A page that failed: the server's words in the tail, and Retry — nothing spins for a page that is not being fetched. */
    @Test
    fun sidebarLoadMoreFailed() {
        val state = state(ListPreferences()).copy(tail = SidebarTail.Failed("Cursor is rate limiting requests. Try again in 30 s."))
        show(state)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        capture("126_sidebar_load_more_failed")
    }

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; ages read "15m" against it. */
        const val NOW = 1_736_949_600_000L
    }
}
