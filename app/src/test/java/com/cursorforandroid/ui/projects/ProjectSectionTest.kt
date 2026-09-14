package com.cursorforandroid.ui.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.ContextState
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectContext
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's Project section body — the Project's one surface: what it lists, every action it offers in Extended
 * mode and where each goes, and the named states it shows otherwise.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun agent(id: String, name: String, parent: AgentParent? = null, running: Boolean = false, isProject: Boolean = false, pending: Boolean = false, branch: String? = null) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - 3_600_000L,
        updatedAtMillis = now - 600_000L,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = if (branch != null) listOf(GitBranch("github.com/acme/app", branch, null)) else emptyList(),
        isProject = isProject,
        projectAppearance = if (isProject) ProjectAppearance("rocket", "purple") else null,
        parent = parent,
        hasPendingInteraction = pending,
    )

    private val root = agent("bc-p", "Cesium billing launch", isProject = true)
    private val workerParent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)
    private val workers = listOf(
        ProjectWorker(agent("bc-w1", "Stripe webhook handler", workerParent, running = true, branch = "cursor/stripe"), WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED)),
        ProjectWorker(agent("bc-w2", "Usage events aggregation", workerParent, pending = true), WorkerMembership("bc-w2", "bc-p", WorkerSpawnKind.ADOPTED)),
    )
    private val subagent = agent("bc-sub", "Migration checker", AgentParent("bc-p", AgentParentKind.SUBAGENT))

    private fun state(actionsAvailable: Boolean, context: ContextState = ContextState.Idle, subagents: List<Agent> = emptyList(), notice: String? = null) = ProjectViewState(
        projectId = "bc-p",
        root = root,
        workers = workers,
        subagents = subagents,
        hasSynced = true,
        lineageNotice = notice,
        context = context,
        actionsAvailable = actionsAvailable,
    )

    private var current by mutableStateOf(ProjectViewState(projectId = "bc-p"))
    private var busy by mutableStateOf(false)

    private fun show(state: ProjectViewState, isBusy: Boolean = false, actions: ProjectActions = actions()) {
        current = state
        busy = isBusy
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ProjectSectionBody(state = current, local = LocalAgentState(), busy = busy, actions = actions, nowMillis = now)
            }
        }
    }

    private val tapped = mutableListOf<String>()

    private fun actions() = ProjectActions(
        onOpenAgent = { tapped += "open:${it.id}" },
        onSteer = { tapped += "steer:${it.id}" },
        onPause = { tapped += "pause:$it" },
        onResume = { tapped += "resume:$it" },
        onStop = { tapped += "stop:$it" },
        onRelease = { tapped += "release:$it" },
        onMove = { tapped += "move:${it.id}" },
        onNewWorker = { tapped += "new-worker" },
        onAdopt = { tapped += "adopt" },
        onEditAppearance = { tapped += "appearance" },
        onLoadContext = { tapped += "context:$it" },
        onContextUp = { tapped += "context-up" },
        onOpenContextFile = { tapped += "file:${it.relativePath}" },
        onRefresh = { tapped += "refresh" },
    )

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `in Extended mode the section sums the Project up and offers every action, each reaching its hand`() {
        show(state(actionsAvailable = true, subagents = listOf(subagent)))

        // The summary: how many primaries, when the Project last moved, who is working and who is waiting.
        compose.onNodeWithText("2 primaries \u00B7 updated 10m").assertIsDisplayed()
        compose.onNodeWithText("1 working").assertIsDisplayed()
        compose.onNodeWithText("1 needs input").assertIsDisplayed()
        compose.onNodeWithTag("project-appearance").performClick()
        assertThat(tapped).contains("appearance")

        compose.onNodeWithText("Primaries \u00B7 2").assertIsDisplayed()
        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        compose.onNodeWithText("Created \u00B7 cursor/stripe \u00B7 10m").assertIsDisplayed()
        compose.onNodeWithText("Usage events aggregation").assertIsDisplayed()
        compose.onNodeWithText("Needs input").assertIsDisplayed()
        compose.onNodeWithText("Adopted \u00B7 app \u00B7 10m").assertIsDisplayed()
        compose.onNodeWithTag("project-new-primary").performClick()
        compose.onNodeWithTag("project-adopt").performClick()
        assertThat(tapped).containsAtLeast("new-worker", "adopt").inOrder()

        compose.onNodeWithText("Subagents \u00B7 1").assertIsDisplayed()
        compose.onNodeWithText("Migration checker").performClick()
        assertThat(tapped).contains("open:bc-sub")

        compose.onNodeWithTag("project-context-open").performClick()
        assertThat(tapped).contains("context:")
        compose.onNodeWithTag("project-refresh").performClick()
        assertThat(tapped).contains("refresh")

        // The primary's menu: steer and pause for a running one, stop, move and release.
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").performClick()
        compose.onNodeWithText("Steer\u2026").assertExists()
        compose.onNodeWithText("Pause").assertExists()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Move under another Project\u2026").assertExists()
        compose.onNodeWithText("Release from the Project").performClick()
        assertThat(tapped).contains("release:bc-w1")
        compose.onNodeWithContentDescription("Actions for Usage events aggregation").performClick()
        compose.onNodeWithText("Steer\u2026").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()
        assertThat(tapped).contains("resume:bc-w2")

        // Rows open their chats; the coordinator's own side chats are not here — they are the panel's Side chats section.
        compose.onNodeWithText("Stripe webhook handler").performClick()
        assertThat(tapped).contains("open:bc-w1")
        assertThat(shown("Side chat")).isFalse()
        assertThat(compose.onAllNodesWithTag("project-primary").fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun `without Extended mode the actions are gone and the reason is named, and a primary can still be opened and stopped`() {
        show(state(actionsAvailable = false, context = ContextState.Unavailable(ProjectRepository.NEEDS_EXTENDED_MODE), notice = ProjectRepository.NEEDS_EXTENDED_MODE))

        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        compose.onNodeWithText("New primary").assertDoesNotExist()
        compose.onNodeWithText("Adopt a chat").assertDoesNotExist()
        assertThat(compose.onAllNodesWithTag("project-appearance").fetchSemanticsNodes()).isEmpty()
        compose.onAllNodes(hasText(ProjectRepository.NEEDS_EXTENDED_MODE)).onFirst().assertExists()
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").performClick()
        compose.onNodeWithText("Open and message").assertExists()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Steer\u2026").assertDoesNotExist()
        compose.onNodeWithText("Release from the Project").assertDoesNotExist()
        // Refreshing needs no account.
        compose.onNodeWithTag("project-refresh").assertExists()
    }

    @Test
    fun `the shared context opens folder by folder and a file, and an empty store is a named state`() {
        val context = ContextState.Loaded(ProjectContext("st-1", listOf(ContextEntry("docs", isDirectory = true), ContextEntry("notes.md", isDirectory = false, sizeBytes = 2048L)), relativePath = ""))
        show(state(actionsAvailable = true, context = context))

        compose.onNodeWithText("2.0 KB").assertIsDisplayed()
        compose.onNodeWithText("docs").performClick()
        compose.onNodeWithText("notes.md").performClick()
        assertThat(tapped).containsAtLeast("context:docs", "file:notes.md")

        current = state(actionsAvailable = true, context = ContextState.Loaded(ProjectContext("st-1", emptyList(), relativePath = "docs")))
        compose.waitForIdle()
        compose.onNodeWithText("This folder is empty.").assertIsDisplayed()
        compose.onNodeWithText("docs").performClick()
        assertThat(tapped).contains("context-up")
    }

    @Test
    fun `while an action is under way the hands are held, and a Project still syncing says so`() {
        show(state(actionsAvailable = true), isBusy = true)
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Edit icon and colour").assertIsNotEnabled()

        busy = false
        current = ProjectViewState(projectId = "bc-p", root = root, hasSynced = false)
        compose.waitForIdle()
        compose.onNodeWithText("Loading\u2026").assertIsDisplayed()
        compose.onNodeWithText("0 primaries \u00B7 updated 10m").assertIsDisplayed()
    }
}
