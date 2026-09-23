package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.DraftRow
import com.cursorforandroid.ui.agents.DraftRowItem
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.FileUploadState
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.conversation.AccountQueueRows
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.customize.CustomizeSheet
import com.cursorforandroid.ui.projects.ProjectActions
import com.cursorforandroid.ui.projects.ProjectSectionBody
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Every popup menu on the one surface, open where it opens in the app and in both themes: the composer's "+" menu
 * and its Skills and MCP Servers pages and the `/` popover, above the composer docked at the bottom as a
 * conversation has it; the sidebar's long-press menus for a chat and a draft; a Project primary's actions; Copy
 * message; the queue's reorder menu; an attachment tile's menu; and the Customize sheet's pickers. The chat's "More"
 * menu is [ChatHeaderScreenshotTest]'s, over the whole chat screen. Each host is drawn on its own, so a frame changes
 * only with its menu and what anchors it. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PopupMenusScreenshotTest {

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
    private fun Scene(mode: ThemeMode, content: @Composable BoxScope.() -> Unit) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    /** A short exchange at the top of the page, so a menu is seen over the transcript as well as the bare canvas. */
    @Composable
    private fun Transcript() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
            TimelineItemView(UserMessage("u1", "The checkout total is a cent off after the coupon step. Find where it came in.", timestampMillis = NOW))
            TimelineItemView(AssistantMessage("a1", "The coupon is applied after tax in `applyDiscount`, and the total is rounded twice: once per line and again on the sum."))
        }
    }

    /** The composer where a conversation docks it: along the bottom, [CursorDimens.composerGutter] in from the sides. */
    @Composable
    private fun BoxScope.DockedComposer(
        actions: ComposerMenuActions = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
        files: List<PendingFile> = emptyList(),
        uploads: Map<String, FileUploadState> = emptyMap(),
        above: @Composable () -> Unit = {},
    ) {
        var value by remember { mutableStateOf("") }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = CursorDimens.composerGutter)
                .padding(bottom = CursorDimens.composerBottomGap),
        ) {
            above()
            ComposerBox(
                value = value,
                onValueChange = { value = it },
                placeholder = "Follow up…",
                onSend = {},
                plusMenu = actions,
                files = files,
                onRemoveFile = {},
                fileUploads = uploads,
                onRetryFile = {},
                modelLabel = "Claude Fable 5.1",
                onModel = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun composer(mode: ThemeMode, actions: ComposerMenuActions = ComposerMenuActions(onPickMedia = {}, onPickFiles = {})) {
        compose.setContent {
            Scene(mode) {
                Transcript()
                DockedComposer(actions)
            }
        }
        compose.waitForIdle()
    }

    private fun plusMenu(mode: ThemeMode, name: String) {
        composer(mode)
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        capture(name)
    }

    @Test
    fun plusMenuDark() = plusMenu(ThemeMode.Dark, "241_popup_plus_menu_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun plusMenuLight() = plusMenu(ThemeMode.Light, "242_popup_plus_menu_light")

    private fun plusSkills(mode: ThemeMode, name: String) {
        composer(mode)
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.onNodeWithText("Skills").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Search or type a skill name")).fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun plusSkillsDark() = plusSkills(ThemeMode.Dark, "243_popup_plus_skills_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun plusSkillsLight() = plusSkills(ThemeMode.Light, "244_popup_plus_skills_light")

    private val servers = listOf(
        McpServer("mcp-1", "Linear", url = "https://mcp.linear.app/sse"),
        McpServer("mcp-2", "Sentry", url = "https://mcp.sentry.dev/mcp", enabled = false),
    )

    private fun plusMcp(mode: ThemeMode, name: String) {
        composer(mode, ComposerMenuActions(onPickMedia = {}, onPickFiles = {}, mcpServers = servers))
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.onNodeWithText("MCP Servers").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Sentry")).fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun plusMcpDark() = plusMcp(ThemeMode.Dark, "245_popup_plus_mcp_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun plusMcpLight() = plusMcp(ThemeMode.Light, "246_popup_plus_mcp_light")

    private fun slash(mode: ThemeMode, name: String) {
        composer(mode)
        compose.onNode(hasSetTextAction()).performTextInput("/")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Slash commands").fetchSemanticsNodes().isNotEmpty() }
        capture(name)
    }

    @Test
    fun slashDark() = slash(ThemeMode.Dark, "247_popup_slash_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun slashLight() = slash(ThemeMode.Light, "248_popup_slash_light")

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null, parent: AgentParent? = null, isProject: Boolean = false) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - (ageMinutes + 40) * 60_000L,
        updatedAtMillis = NOW - ageMinutes * 60_000L,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        branches = if (branch != null) listOf(GitBranch("github.com/acme/app", branch, null)) else emptyList(),
        isProject = isProject,
        projectAppearance = if (isProject) ProjectAppearance("rocket", "purple") else null,
        parent = parent,
    )

    private fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read, pinned: Boolean = false) =
        AgentRow(agent = agent, indicator = indicator, isPinned = pinned, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false, children = emptyList())

    private val rows = listOf(
        row(agent("cli", "Cli exploration", 19, branch = "cursor/cli")),
        row(agent("revenue", "Revenue Scaling Pipeline Research", 2 * 60, branch = "cursor/revenue"), indicator = AgentIndicator.Unread),
        row(agent("codex", "Codex-Poly-Bot Scaling", 34, running = true), indicator = AgentIndicator.Running),
        row(agent("release", "Latest release process", 28 * 60, branch = "cursor/release")),
    )

    private fun agentRow(mode: ThemeMode, name: String) {
        compose.setContent {
            Scene(mode) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    rows.forEach { row ->
                        AgentRowItem(row, selected = false, prefs = ListPreferences(), actions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}), nowMillis = NOW)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Revenue Scaling Pipeline Research").performTouchInput { longClick() }
        capture(name)
    }

    @Test
    fun agentRowDark() = agentRow(ThemeMode.Dark, "249_popup_agent_row_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun agentRowLight() = agentRow(ThemeMode.Light, "250_popup_agent_row_light")

    private fun draftRow(mode: ThemeMode, name: String) {
        val drafts = listOf(
            DraftRow("draft-1", "Rewrite the sign-in screen's copy for the browser login", "cursor-for-android", NOW - 3 * 60_000L, failed = false),
            DraftRow("draft-2", "Bump Robolectric to 4.17 and re-record the frames", "codex-poly-bot", NOW - 55 * 60_000L, failed = false),
        )
        compose.setContent {
            Scene(mode) {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                    drafts.forEach { DraftRowItem(it, prefs = ListPreferences(), onOpen = {}, onDelete = {}, nowMillis = NOW) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Rewrite the sign-in screen's copy for the browser login").performTouchInput { longClick() }
        capture(name)
    }

    @Test
    fun draftRowDark() = draftRow(ThemeMode.Dark, "251_popup_draft_row_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun draftRowLight() = draftRow(ThemeMode.Light, "252_popup_draft_row_light")

    private fun projectWorker(mode: ThemeMode, name: String) {
        val parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)
        val state = ProjectViewState(
            projectId = "bc-p",
            root = agent("bc-p", "Cesium billing launch", 60, isProject = true),
            workers = listOf(
                ProjectWorker(agent("bc-w1", "Stripe webhook handler", 10, running = true, branch = "cursor/stripe", parent = parent), WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED)),
                ProjectWorker(agent("bc-w2", "Usage events aggregation", 10, parent = parent), WorkerMembership("bc-w2", "bc-p", WorkerSpawnKind.ADOPTED)),
            ),
            hasSynced = true,
            actionsAvailable = true,
        )
        val actions = ProjectActions(
            onOpenAgent = {}, onSteer = {}, onPause = {}, onResume = {}, onStop = {}, onRelease = {}, onMove = {},
            onNewWorker = {}, onAdopt = {}, onEditAppearance = {}, onLoadContext = {}, onContextUp = {}, onOpenContextFile = {}, onRefresh = {},
        )
        compose.setContent {
            Scene(mode) {
                ProjectSectionBody(state = state, local = LocalAgentState(), busy = false, actions = actions, nowMillis = NOW, modifier = Modifier.padding(top = 24.dp))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for Stripe webhook handler").performClick()
        capture(name)
    }

    @Test
    fun projectWorkerDark() = projectWorker(ThemeMode.Dark, "253_popup_project_worker_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun projectWorkerLight() = projectWorker(ThemeMode.Light, "254_popup_project_worker_light")

    private fun copyMessage(mode: ThemeMode, name: String) {
        composer(mode)
        compose.onNodeWithText("The checkout total is a cent off after the coupon step. Find where it came in.").performTouchInput { longClick() }
        capture(name)
    }

    @Test
    fun copyMessageDark() = copyMessage(ThemeMode.Dark, "255_popup_copy_message_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun copyMessageLight() = copyMessage(ThemeMode.Light, "256_popup_copy_message_light")

    private fun queueReorder(mode: ThemeMode, name: String) {
        val queue = listOf(
            PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS),
            PendingFollowup("fu-2", "And a changelog line under Unreleased", 2_000L, AgentSource.API),
        )
        compose.setContent {
            Scene(mode) {
                Transcript()
                DockedComposer(
                    above = {
                        AccountQueueRows(
                            queue = queue,
                            inFlightIds = emptySet(),
                            onSendNow = {},
                            onRemove = {},
                            onUpdate = { _, _ -> },
                            onEditing = { _, _ -> },
                            onSteerNow = {},
                            onMove = { _, _ -> },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Reorder queued follow-up").onFirst().performClick()
        capture(name)
    }

    @Test
    fun queueReorderDark() = queueReorder(ThemeMode.Dark, "257_popup_queue_reorder_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun queueReorderLight() = queueReorder(ThemeMode.Light, "258_popup_queue_reorder_light")

    /** A picture from the gallery, in Extended mode: the bytes of a PNG, decodable for the tile's thumbnail. */
    private fun photo(): PendingFile {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(214, 108, 52)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return PendingFile.of(PromptFile(bytes, "IMG_20260917_074100.png", "image/png"), id = "f0")
    }

    private fun mediaTile(mode: ThemeMode, name: String) {
        val spec = PendingFile("f1", PromptFile(ByteArray(2_400 * 1024), "Q3-billing-spec.pdf", "application/pdf"))
        compose.setContent {
            Scene(mode) {
                Transcript()
                DockedComposer(files = listOf(photo(), spec), uploads = mapOf("f0" to FileUploadState(failed = true), "f1" to FileUploadState.DONE))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("media-tile").performTouchInput { longClick() }
        capture(name)
    }

    @Test
    fun mediaTileDark() = mediaTile(ThemeMode.Dark, "259_popup_media_tile_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun mediaTileLight() = mediaTile(ThemeMode.Light, "260_popup_media_tile_light")

    /**
     * The rule's own `waitUntil` runs on the frame clock, which the disk-backed list preferences do not; this waits
     * on the wall while keeping the main looper and the composition moving.
     */
    private fun awaitOnScreen(condition: () -> Boolean) {
        repeat(500) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition was still not satisfied after 10s")
    }

    private fun customizePicker(mode: ThemeMode, name: String) {
        val api = FakeCursorApi()
        api.addIdleAgent(id = "bc-1", name = "Cli exploration", runId = "run-1", repo = "https://github.com/acme/app")
        api.addIdleAgent(id = "bc-2", name = "Latest release process", runId = "run-2", repo = "https://github.com/acme/site")
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Context>(), demo = CursorBackend(api, FakeRunStreamer(), isDemo = true))
        runBlocking { graph.session.enterDemo() }
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            Scene(mode) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        awaitOnScreen { compose.onAllNodes(hasText("Group by")).fetchSemanticsNodes().isNotEmpty() && viewModel.uiState.value.repoSlugs.size == 2 }
        compose.onNodeWithText("Group by").performClick()
        capture(name)
    }

    @Test
    fun customizePickerDark() = customizePicker(ThemeMode.Dark, "261_popup_customize_picker_dark")

    @Test
    @Config(qualifiers = LIGHT)
    fun customizePickerLight() = customizePicker(ThemeMode.Light, "262_popup_customize_picker_light")

    private companion object {
        const val LIGHT = "w411dp-h914dp-notnight-420dpi"

        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock; ages read against it. */
        const val NOW = 1_736_949_600_000L
    }
}
