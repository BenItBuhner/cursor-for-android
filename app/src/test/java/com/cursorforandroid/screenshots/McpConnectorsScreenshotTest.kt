package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.ConnectorStatus
import com.cursorforandroid.domain.ConnectorTransport
import com.cursorforandroid.domain.McpConnector
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.ConnectorMenu
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.theme.CursorDimens
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
 * The "+" menu's MCP page in Extended mode: the account's connectors, as the MCP dropdown on cursor.com/agents lists
 * them — logo, name, state, "Connect" for one waiting on a sign-in, and the account-wide switch, dimmed where the team
 * decides — and the page when the list cannot be read. The SDK mode's page of inline servers is
 * [PopupMenusScreenshotTest]'s (245, 246). Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class McpConnectorsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private val connectors = listOf(
        McpConnector(1, "Figma", url = "https://mcp.figma.com/mcp", enabled = true, status = ConnectorStatus.Error, error = "Server did not respond"),
        McpConnector(2, "GitHub", url = "https://api.githubcopilot.com/mcp/", enabled = true, status = ConnectorStatus.Connected),
        McpConnector(3, "Linear", url = "https://mcp.linear.app/mcp", enabled = true, status = ConnectorStatus.NeedsAuth, authUrl = "https://linear.app/oauth/authorize"),
        McpConnector(4, "Notion", url = "https://mcp.notion.com/mcp"),
        McpConnector(5, "Sentry", url = "https://mcp.sentry.dev/mcp", enabled = true, team = true, required = true, status = ConnectorStatus.Connected),
        McpConnector(6, "playwright", transport = ConnectorTransport.Stdio, enabled = true),
        McpConnector(7, "Slack", url = "https://mcp.slack.com/mcp", team = true, blockedByAdmin = true),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(mode: ThemeMode, menu: ConnectorMenu, waitFor: String) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                            TimelineItemView(UserMessage("u1", "Open an issue in Linear for the rounding bug, and link the Sentry event.", timestampMillis = NOW))
                            TimelineItemView(AssistantMessage("a1", "Linear needs you to sign in first; Sentry is connected."))
                        }
                        var value by remember { mutableStateOf("") }
                        ComposerBox(
                            value = value,
                            onValueChange = { value = it },
                            placeholder = "Follow up…",
                            onSend = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}, connectors = menu),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = CursorDimens.composerGutter)
                                .padding(bottom = CursorDimens.composerBottomGap),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.onNodeWithText("MCP Servers").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(waitFor)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun connectorsDark() {
        open(ThemeMode.Dark, ConnectorMenu(connectors), waitFor = "Needs sign-in")
        capture("560_popup_plus_connectors_dark")
    }

    @Test
    @Config(qualifiers = LIGHT)
    fun connectorsLight() {
        open(ThemeMode.Light, ConnectorMenu(connectors), waitFor = "Needs sign-in")
        capture("561_popup_plus_connectors_light")
    }

    @Test
    fun connectorsUnavailable() {
        open(ThemeMode.Dark, ConnectorMenu(emptyList(), error = "Sign in again to use Extended mode."), waitFor = "Manage on cursor.com")
        capture("562_popup_plus_connectors_error_dark")
    }

    private companion object {
        const val LIGHT = "w411dp-h914dp-notnight-420dpi"
        const val NOW = 1_736_949_600_000L
    }
}
