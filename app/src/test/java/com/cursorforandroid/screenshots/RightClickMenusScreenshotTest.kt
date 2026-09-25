package com.cursorforandroid.screenshots

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.GitBranch
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentRowItem
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.home.ProjectShortcutGrid
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The long-press menus opened by a mouse's right button instead: the same menu, hanging from the pointer rather than
 * from the row, on a sidebar chat, a reply mid-paragraph, a tool call's line and a Project shortcut on the New Chat
 * page. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RightClickMenusScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun setUp() {
        AppClock.nowMillis = { NOW }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun scene(content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.rightClickAt(x: Float, y: Float) {
        performMouseInput { rightClick(Offset(x, y)) }
        compose.waitForIdle()
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean = false, branch: String? = null) = Agent(
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
    )

    private fun row(agent: Agent, indicator: AgentIndicator = AgentIndicator.Read) =
        AgentRow(agent = agent, indicator = indicator, isPinned = false, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false, children = emptyList())

    private val actions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})

    @Test
    fun sidebarChat() {
        val rows = listOf(
            row(agent("cli", "Cli exploration", 19, branch = "cursor/cli")),
            row(agent("revenue", "Revenue Scaling Pipeline Research", 2 * 60, branch = "cursor/revenue"), indicator = AgentIndicator.Unread),
            row(agent("codex", "Codex-Poly-Bot Scaling", 34, running = true), indicator = AgentIndicator.Running),
            row(agent("release", "Latest release process", 28 * 60, branch = "cursor/release")),
        )
        scene {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                rows.forEach { AgentRowItem(it, selected = false, prefs = ListPreferences(), actions = actions, nowMillis = NOW) }
            }
        }
        compose.onNodeWithText("Revenue Scaling Pipeline Research").rightClickAt(260f, 20f)
        capture("596_right_click_sidebar_chat")
    }

    @Test
    fun reply() {
        scene {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                TimelineItemView(UserMessage("u1", "The checkout total is a cent off after the coupon step. Find where it came in.", timestampMillis = NOW))
                TimelineItemView(AssistantMessage("a1", "The coupon is applied after tax in `applyDiscount`, and the total is rounded twice: once per line and again on the sum.\n\n```kotlin\nval total = lines.sumOf { round(it.price) }\n```"))
            }
        }
        compose.onNodeWithText("The coupon is applied", substring = true).rightClickAt(420f, 60f)
        capture("597_right_click_reply")
    }

    @Test
    fun toolCall() {
        val calls = listOf(
            ToolCall("c1", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "Checkout.kt", detail = "/workspace/app/Checkout.kt"),
            ToolCall("c2", "run_terminal_cmd", ToolKind.Shell, ToolCall.STATUS_COMPLETED, "./gradlew test", detail = "./gradlew test", output = "3 tests failed"),
            ToolCall("c3", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "applyDiscount"),
        )
        scene {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) { calls.forEach { ToolCallLine(it) } }
        }
        compose.onAllNodesWithText("./gradlew test", substring = true).onFirst().rightClickAt(60f, 20f)
        capture("598_right_click_tool_call")
    }

    @Test
    fun projectShortcut() {
        scene {
            ProjectShortcutGrid(NewChatHomeFixtures.list().projectRows, Modifier.padding(16.dp).padding(top = 24.dp), onOpen = {}, actions = actions.copy(onEditProject = {}), onReorder = {})
        }
        compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).onFirst().rightClickAt(200f, 90f)
        capture("599_right_click_project_shortcut")
    }

    private companion object {
        const val NOW = 1_736_949_600_000L
    }
}
