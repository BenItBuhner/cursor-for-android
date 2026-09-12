package com.cursorforandroid.ui.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.cursorforandroid.domain.SideChatAvailability
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

/** The Project view's body: what it lists, what it offers in Extended mode, and the named states it shows otherwise. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectScreenTest {

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
    private val sideChat = agent("bc-s", "Pricing page copy", AgentParent("bc-p", AgentParentKind.SIDE_CHAT))

    private fun state(actionsAvailable: Boolean, context: ContextState = ContextState.Idle, sideChats: SideChatAvailability = SideChatAvailability.UNKNOWN, notice: String? = null) = ProjectViewState(
        projectId = "bc-p",
        root = root,
        workers = workers,
        sideChats = listOf(sideChat),
        hasSynced = true,
        lineageNotice = notice,
        sideChatAvailability = sideChats,
        context = context,
        actionsAvailable = actionsAvailable,
    )

    private fun show(state: ProjectViewState, actions: ProjectActions = actions()) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ProjectBody(state = state, local = LocalAgentState(), busy = false, actions = actions, nowMillis = now)
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
        onNewSideChat = { tapped += "new-side" },
        onEditAppearance = { tapped += "appearance" },
        onLoadContext = { tapped += "context:$it" },
        onContextUp = { tapped += "context-up" },
        onOpenContextFile = { tapped += "file:${it.relativePath}" },
    )

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `in Extended mode the view lists the coordinator, the primaries with their status, the side chats and every action`() {
        show(state(actionsAvailable = true))

        // The hero and the coordinator's row both name the Project.
        assertThat(compose.onAllNodes(hasText("Cesium billing launch")).fetchSemanticsNodes()).hasSize(2)
        compose.onNodeWithText("Plans the work and delegates it", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 working").assertIsDisplayed()
        compose.onNodeWithText("1 needs input").assertIsDisplayed()
        compose.onNodeWithText("Primaries \u00B7 2").assertIsDisplayed()
        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        compose.onNodeWithText("Created \u00B7 cursor/stripe \u00B7 10m").assertIsDisplayed()
        compose.onNodeWithText("Usage events aggregation").assertIsDisplayed()
        compose.onNodeWithText("Needs input").assertIsDisplayed()
        compose.onNodeWithText("Adopted \u00B7 app \u00B7 10m").assertIsDisplayed()
        scrollTo("New primary")
        compose.onNodeWithText("New primary").assertIsDisplayed()
        compose.onNodeWithText("Adopt a chat").assertIsDisplayed()
        scrollTo("New side chat")
        compose.onNodeWithText("Pricing page copy").assertIsDisplayed()
        compose.onNodeWithText("New side chat").assertIsDisplayed()
        scrollTo("Show shared context")
        compose.onNodeWithText("Show shared context").performClick()
        assertThat(tapped).contains("context:")

        // The primary's menu: steer and pause for a running one, stop, move and release.
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").performClick()
        compose.onNodeWithText("Steer\u2026").assertExists()
        compose.onNodeWithText("Pause").assertExists()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Release from the Project").performClick()
        assertThat(tapped).contains("release:bc-w1")
        compose.onNodeWithContentDescription("Actions for Usage events aggregation").performClick()
        compose.onNodeWithText("Steer\u2026").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()
        assertThat(tapped).contains("resume:bc-w2")

        // Rows open their chats.
        compose.onNodeWithText("Stripe webhook handler").performClick()
        assertThat(tapped).contains("open:bc-w1")
    }

    @Test
    fun `without Extended mode the actions are gone and the reason is named, and a primary can still be opened and stopped`() {
        show(state(actionsAvailable = false, context = ContextState.Unavailable(ProjectRepository.NEEDS_EXTENDED_MODE), notice = ProjectRepository.NEEDS_EXTENDED_MODE))

        compose.onNodeWithText("Stripe webhook handler").assertIsDisplayed()
        compose.onNodeWithText("New primary").assertDoesNotExist()
        compose.onNodeWithText("Adopt a chat").assertDoesNotExist()
        compose.onNodeWithText("New side chat").assertDoesNotExist()
        compose.onAllNodes(hasText(ProjectRepository.NEEDS_EXTENDED_MODE)).onFirst().assertExists()
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").performClick()
        compose.onNodeWithText("Open and message").assertExists()
        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Steer\u2026").assertDoesNotExist()
        compose.onNodeWithText("Release from the Project").assertDoesNotExist()
    }

    @Test
    fun `side chats Cursor does not offer yet read as coming to Cursor, and the context shows its files`() {
        val context = ContextState.Loaded(ProjectContext("st-1", listOf(ContextEntry("docs", isDirectory = true), ContextEntry("notes.md", isDirectory = false, sizeBytes = 2048L)), relativePath = ""))
        show(state(actionsAvailable = true, context = context, sideChats = SideChatAvailability.COMING_TO_CURSOR))

        scrollTo(SIDE_CHATS_COMING)
        compose.onNodeWithText(SIDE_CHATS_COMING).assertIsDisplayed()
        compose.onNodeWithText("New side chat").assertDoesNotExist()
        scrollTo("notes.md")
        compose.onNodeWithText("2.0 KB").assertIsDisplayed()
        compose.onNodeWithText("docs").performClick()
        compose.onNodeWithText("notes.md").performClick()
        assertThat(tapped).containsAtLeast("context:docs", "file:notes.md")
    }

    @Test
    fun `a Project still loading, with no primaries, says so`() {
        show(ProjectViewState(projectId = "bc-p", root = null, hasSynced = false))

        compose.onNodeWithText("Loading the Project\u2026").assertIsDisplayed()
        compose.onNodeWithText("Loading\u2026").assertIsDisplayed()
    }
}
