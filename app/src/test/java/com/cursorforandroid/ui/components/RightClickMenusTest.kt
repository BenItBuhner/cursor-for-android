package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.ProjectWorker
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SystemNotifications
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.domain.WorkerMembership
import com.cursorforandroid.domain.WorkerSpawnKind
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.agents.DraftRow
import com.cursorforandroid.ui.agents.DraftRowItem
import com.cursorforandroid.ui.conversation.AccountQueueRows
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.ThoughtText
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.ProjectShortcutGrid
import com.cursorforandroid.ui.home.RecentChatRow
import com.cursorforandroid.ui.projects.ProjectActions
import com.cursorforandroid.ui.projects.projectSection
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.ExperimentalComposeUiApi

/**
 * A mouse's right button (a trackpad's two-finger click) on every surface a long press opens a menu on: the same
 * menu opens, at the pointer, and the primary action (open the chat, expand the step, follow the link) does not run.
 * Touch keeps its long press, and Shift+F10 / the Menu key open the focused row's menu.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RightClickMenusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var clipboard: ClipboardManager
    private lateinit var inputMode: InputModeManager
    private val opened = mutableListOf<String>()
    private val links = mutableListOf<String>()

    @Before
    fun setUp() {
        AppClock.nowMillis = { NOW }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            clipboard = LocalClipboardManager.current
            inputMode = LocalInputModeManager.current
            CursorTheme(mode = ThemeMode.Dark) {
                val uris = object : UriHandler {
                    override fun openUri(uri: String) {
                        links += uri
                    }
                }
                CompositionLocalProvider(
                    LocalRippleConfiguration provides null,
                    LocalTranscriptControls provides TranscriptControls(),
                    LocalUriHandler provides uris,
                ) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Right-clicks [node] at [at] (node-local; its centre by default) and returns where that is on screen. */
    private fun rightClick(node: SemanticsNodeInteraction, at: Offset? = null): Offset {
        val semantics = node.fetchSemanticsNode()
        val point = at ?: semantics.size.let { Offset(it.width / 2f, it.height / 2f) }
        node.performMouseInput { rightClick(point) }
        compose.waitForIdle()
        return semantics.positionOnScreen + point
    }

    /**
     * The menu's row [label] starts just past the pointer (the menu's inset and gap), not beside the row it was opened
     * on. Screen positions: a popup's own window coordinates start at its corner.
     */
    private fun assertOpensAt(label: String, pointer: Offset) {
        val item = compose.onNodeWithText(label).fetchSemanticsNode().positionOnScreen
        val slack = 16 * compose.density.density
        assertThat(item.x - pointer.x).isIn(com.google.common.collect.Range.closed(0f, slack))
        assertThat(item.y - pointer.y).isIn(com.google.common.collect.Range.closed(0f, slack))
    }

    private fun agent(id: String, name: String, isProject: Boolean = false, parent: AgentParent? = null, running: Boolean = false) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        latestRunId = "run-$id",
        createdAtMillis = NOW - 3_600_000L,
        updatedAtMillis = NOW - 60_000L,
        url = "https://cursor.com/agents/$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        isProject = isProject,
        projectAppearance = if (isProject) ProjectAppearance("rocket", "purple") else null,
        parent = parent,
    )

    private fun row(agent: Agent) =
        AgentRow(agent = agent, indicator = AgentIndicator.Read, isPinned = false, isUnread = false, launchedFromThisDevice = false, children = emptyList())

    private val actions = AgentRowActions({ opened += it.agent.id }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, onEditProject = {})

    private fun sidebar() = show {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            AgentRowItem(row(agent("bc-chat", "Cli exploration")), selected = false, prefs = ListPreferences(), actions = actions, nowMillis = NOW)
            AgentRowItem(row(agent("bc-project", "Revenue Scaling Pipeline", isProject = true)), selected = false, prefs = ListPreferences(), actions = actions, nowMillis = NOW)
        }
    }

    @Test
    fun `right-clicking a sidebar chat opens its menu at the pointer and does not open the chat`() {
        sidebar()
        val node = compose.onNodeWithText("Cli exploration")
        val pointer = rightClick(node, Offset(40f, 10f))
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        assertOpensAt("Pin", pointer)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `right-clicking a Project in the sidebar opens the Project's menu`() {
        sidebar()
        rightClick(compose.onNodeWithText("Revenue Scaling Pipeline"))
        compose.onNodeWithText("Edit Project").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `touch keeps its long press and its tap, and a left click still opens the chat`() {
        sidebar()
        compose.onNodeWithText("Cli exploration").performTouchInput { longClick() }
        compose.onNodeWithText("Archive").assertIsDisplayed()
        assertThat(opened).isEmpty()
        compose.onNodeWithText("Archive").performClick()
        compose.waitUntil(5_000) { !shown("Archive") }
        compose.onNodeWithText("Cli exploration").performMouseInput { click() }
        compose.waitForIdle()
        assertThat(opened).containsExactly("bc-chat")
        assertThat(shown("Archive")).isFalse()
    }

    @Test
    fun `Shift+F10 and the Menu key open the focused row's menu`() {
        sidebar()
        // A row takes focus as it does with a keyboard attached: out of touch mode.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        val node = compose.onNodeWithText("Cli exploration")
        node.requestFocus()
        node.assertIsFocused()
        node.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Archive").performClick()
        compose.waitUntil(5_000) { !shown("Archive") }
        node.requestFocus()
        node.performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `right-clicking a draft opens its menu, not the draft`() {
        val drafts = mutableListOf<String>()
        show {
            DraftRowItem(DraftRow("draft-1", "Rewrite the sign-in copy", "app", NOW - 60_000L, failed = false), prefs = ListPreferences(), onOpen = { drafts += it.id }, onDelete = {}, nowMillis = NOW)
        }
        val pointer = rightClick(compose.onNodeWithText("Rewrite the sign-in copy"), Offset(60f, 12f))
        assertOpensAt("Delete draft", pointer)
        assertThat(drafts).isEmpty()
    }

    @Test
    fun `right-clicking a recent chat on the New Chat page opens its menu`() {
        show { RecentChatRow(row(agent("bc-recent", "Latest release process")), onClick = { opened += "bc-recent" }, actions = actions, nowMillis = NOW) }
        rightClick(compose.onNodeWithText("Latest release process"))
        compose.onNodeWithText("Archive").assertIsDisplayed()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `right-clicking a Project shortcut opens its menu at the pointer without opening or arranging`() {
        show {
            ProjectShortcutGrid(NewChatHomeFixtures.list().projectRows, Modifier.padding(16.dp), onOpen = { opened += it.agent.id }, actions = actions, onReorder = {})
        }
        val shortcut = compose.onAllNodes(hasTestTag(com.cursorforandroid.ui.home.NewChatHomeTags.PROJECT_SHORTCUT)).onFirst()
        val pointer = rightClick(shortcut)
        compose.onNodeWithText("Edit Project").assertIsDisplayed()
        assertOpensAt("Edit Project", pointer)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `right-clicking a Project's worker opens the menu its button opens`() {
        val parent = AgentParent("bc-p", AgentParentKind.PROJECT_WORKER)
        val state = ProjectViewState(
            projectId = "bc-p",
            root = agent("bc-p", "Cesium billing launch", isProject = true),
            workers = listOf(ProjectWorker(agent("bc-w1", "Stripe webhook handler", parent = parent, running = true), WorkerMembership("bc-w1", "bc-p", WorkerSpawnKind.CREATED))),
            hasSynced = true,
            actionsAvailable = true,
        )
        val projectActions = ProjectActions(
            onOpenAgent = { opened += it.id }, onSteer = {}, onPause = {}, onResume = {}, onStop = {}, onRelease = {}, onMove = {},
            onNewWorker = {}, onAdopt = {}, onEditAppearance = {}, onLoadContext = {}, onContextUp = {}, onOpenContextFile = {}, onRefresh = {},
        )
        show { LazyColumn { projectSection(state = state, local = LocalAgentState(), busy = false, actions = projectActions, nowMillis = NOW) } }
        val pointer = rightClick(compose.onNodeWithText("Stripe webhook handler"), Offset(30f, 10f))
        compose.onNodeWithText("Open and message").assertIsDisplayed()
        assertOpensAt("Open and message", pointer)
        assertThat(opened).isEmpty()
    }

    @Test
    fun `right-clicking a queued message opens its reorder menu`() {
        val sent = mutableListOf<String>()
        show {
            AccountQueueRows(
                queue = listOf(
                    PendingFollowup("fu-1", "Then add a test for the light theme", 1_000L, AgentSource.GLASS),
                    PendingFollowup("fu-2", "And a changelog line", 2_000L, AgentSource.API),
                ),
                inFlightIds = emptySet(),
                onSendNow = { sent += it.id },
                onRemove = {},
                onUpdate = { _, _ -> },
                onEditing = { _, _ -> },
                onMove = { _, _ -> },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        rightClick(compose.onNodeWithText("Then add a test for the light theme"))
        compose.onNodeWithText("Move down").assertIsDisplayed()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `right-clicking an attached picture opens its menu, not the viewer`() {
        show {
            Box(Modifier.width(400.dp)) {
                ComposerAttachments(
                    images = listOf(PendingAttachment("img-1", PromptImage(ByteArray(64) { 7 }, "image/png"), thumbnail = ImageBitmap(4, 4))),
                    onRemoveImage = {},
                    files = emptyList(),
                    onRemoveFile = {},
                    surface = CursorTheme.colors.elevated,
                    agentId = "bc-1",
                )
            }
        }
        val pointer = rightClick(compose.onNodeWithTag("media-tile"))
        compose.onNodeWithText("Remove").assertIsDisplayed()
        assertOpensAt("Open", pointer)
    }

    @Test
    fun `right-clicking a reply offers Copy message at the pointer and copies the markdown`() {
        show { Column(Modifier.padding(16.dp)) { TimelineItemView(AssistantMessage("a1", "Done, **README** added.")) } }
        val pointer = rightClick(compose.onNodeWithText("Done, README added."), Offset(30f, 8f))
        assertOpensAt("Copy message", pointer)
        compose.onNodeWithText("Copy message").performClick()
        compose.waitUntil(5_000) { !shown("Copy message") }
        assertThat(clipboard.getText()?.text).isEqualTo("Done, **README** added.")
    }

    @Test
    fun `right-clicking a prompt offers Copy message`() {
        show { Column(Modifier.padding(16.dp)) { TimelineItemView(UserMessage("u1", "Add a README")) } }
        rightClick(compose.onNodeWithText("Add a README"))
        compose.onNodeWithText("Copy message").assertIsDisplayed()
    }

    @Test
    fun `right-clicking a link or a code block in a reply opens the message's menu and follows nothing`() {
        val markdown = "See [the docs](https://example.com/docs).\n\n```kotlin\nval answer = 42\n```"
        show { Column(Modifier.padding(16.dp)) { TimelineItemView(AssistantMessage("a1", markdown)) } }
        rightClick(compose.onNodeWithText("the docs", substring = true), Offset(30f, 8f))
        compose.onNodeWithText("Copy message").assertIsDisplayed()
        assertThat(links).isEmpty()
        compose.onNodeWithText("Copy message").performClick()
        compose.waitUntil(5_000) { !shown("Copy message") }
        assertThat(clipboard.getText()?.text).isEqualTo(markdown)

        rightClick(compose.onNodeWithText("val answer = 42", substring = true, useUnmergedTree = true))
        compose.onNodeWithText("Copy message").assertIsDisplayed()
        assertThat(links).isEmpty()
    }

    @Test
    fun `right-clicking a notification that opens on a tap opens its menu and leaves it closed`() {
        val injected = "<system_notification>\nThe following task has finished.\n\n<task>\nkind: subagent\nstatus: success\ntitle: Contacts and clipping\ndetail: This is the last output of the subagent:\n\nThe clipping is gone.\n</task>\n</system_notification>"
        show { Column(Modifier.padding(16.dp)) { TimelineItemView(SystemNotifications.parse("n1", injected)!!.items.single()) } }
        rightClick(compose.onNodeWithText("Contacts and clipping"))
        compose.onNodeWithText("Copy message").assertIsDisplayed()
        assertThat(shown("The clipping is gone.")).isFalse()
    }

    @Test
    fun `right-clicking a tool call copies its line and does not expand it`() {
        val call = ToolCall("c-sh", "run_terminal_cmd", ToolKind.Shell, ToolCall.STATUS_COMPLETED, "./gradlew test", detail = "./gradlew test", output = "3 tests failed")
        show { Column(Modifier.padding(16.dp)) { ToolCallLine(call) } }
        val pointer = rightClick(compose.onNodeWithText(call.action), Offset(40f, 8f))
        assertOpensAt("Copy step", pointer)
        assertThat(shown("3 tests failed")).isFalse()
        compose.onNodeWithText("Copy step").performClick()
        compose.waitUntil(5_000) { !shown("Copy step") }
        assertThat(clipboard.getText()?.text).isEqualTo("${call.action} ./gradlew test")
        // Touch is as it was: a step has no press and hold.
        compose.onNodeWithText(call.action).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(shown("Copy step")).isFalse()
    }

    @Test
    fun `right-clicking a thought copies it`() {
        val thought = ThinkingBlock("Reading the test first.")
        show { Column(Modifier.padding(16.dp)) { ThoughtText(thought.text) } }
        rightClick(compose.onNodeWithText("Reading the test first."))
        compose.onNodeWithText("Copy thought").performClick()
        compose.waitUntil(5_000) { !shown("Copy thought") }
        assertThat(clipboard.getText()?.text).isEqualTo("Reading the test first.")
    }

    private companion object {
        const val NOW = 1_736_949_600_000L
    }
}
