package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.TranscriptRowView
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A coordinator's update citing its workers by id (see [CoordinatorFixtures], `agent_links_message.json`): each
 * `[label](bc-…)`, the `#desktop` link and the cursor.com/agents URL drawn as links in the link colour, where the
 * labels used to stand as plain text; the store path beside them a link as before, the relative spec link text.
 * Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class AgentLinksScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val markdown = CoordinatorFixtures.json("agent_links_message.json").getValue("markdown").jsonPrimitive.content

    private fun turn(): List<TranscriptRow> {
        val items: List<TimelineItem> = listOf(
            UserMessage("u1", "Who is on my navigation report?", timestampMillis = 1_789_350_000_000L),
            ActivityGroup(
                "g1",
                listOf(ToolCall("c1", ToolNames.USER_MESSAGE_TOOL, ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage(markdown))),
            ),
            RunFooter("run-1", "run-1", RunStatus.FINISHED, 14_000, emptyList()),
        )
        return TranscriptRows.of(CoordinatorTranscript.present(items, coordinatorMode = true), coordinatorMode = true)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
    @Test
    fun coordinatorAgentLinks() {
        val rows = turn()
        compose.setContent {
            val media = remember { MarkdownMediaContext("bc-bae107cb-2562-40b2-b814-4f8eca874668", ViewerFixtures.loader(), onOpenStorePath = {}, onOpenAgentLink = {}) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(
                    LocalRippleConfiguration provides null,
                    LocalMarkdownMedia provides media,
                    LocalTranscriptControls provides TranscriptControls(coordinatorMode = true),
                ) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows.forEach { TranscriptRowView(it) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("coordinator-message")).assertCountEquals(1)
        captureScreenRoboImage(File(outDir, "430_coordinator_agent_links.png").path, RoborazziOptions())
    }
}
