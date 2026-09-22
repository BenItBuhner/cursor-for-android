package com.cursorforandroid.ui.files

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tool call's file is a tap target of its own: the path opens the file — the full-file viewer for text, the
 * media viewer out of the path itself for a picture, a recording or a sound — while the rest of the row still opens
 * the call's details; and a search's hits each open their file at the line of the hit.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ToolCallFileTapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<FileOpenRequest>()
    private val viewer = MediaViewerState(null)

    private val read = ToolCall(
        "c-read", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "AgentFilesApi.kt", detail = "/workspace/app/src/AgentFilesApi.kt",
        payload = ToolPayload.FileContent("/workspace/app/src/AgentFilesApi.kt", "package x\n", ToolPayload.FileContent.Kind.Read, totalLines = 1),
    )
    private val shot = ToolCall("c-shot", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "45_panel.png", detail = "/workspace/screenshots/45_panel.png")
    private val grep = ToolCall(
        "c-grep", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "decodeBytes in workspace", detail = "decodeBytes in /workspace",
        payload = ToolPayload.FileHits(listOf(ToolPayload.FileHits.Hit("/workspace/app/src/AgentFilesApi.kt", 124, "fun decodeBytes"))),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Rows(vararg calls: ToolCall) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides null,
                LocalTranscriptControls provides TranscriptControls(onOpenFile = { opened += it }),
                LocalMediaViewer provides viewer,
                LocalMarkdownMedia provides MarkdownMediaContext("bc-taps", ViewerFixtures.loader()),
            ) {
                Column { calls.forEach { ToolCallLine(it) } }
            }
        }
    }

    @Test
    fun `a read's path opens the full-file viewer on it, and the verb still opens the call`() {
        compose.setContent { Rows(read) }
        compose.onNodeWithTag("file-link").performClick()
        assertThat(opened).containsExactly(FileOpenRequest("/workspace/app/src/AgentFilesApi.kt", "c-read"))

        // The row's verb is the call's own toggle: it opens the file's card, and opens no file.
        compose.onNodeWithText("Read").performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(8f, 0f)) }
        compose.waitForIdle()
        assertThat(compose.onAllNodes(hasTestTag("file-card")).fetchSemanticsNodes()).hasSize(1)
        assertThat(opened).hasSize(1)

        // The card's header opens the whole file too.
        compose.onNodeWithTag("payload-open").performClick()
        assertThat(opened).hasSize(2)
    }

    @Test
    fun `a picture's path opens the media viewer out of the path`() {
        compose.setContent { Rows(shot) }
        compose.onNodeWithTag("file-link").performClick()
        compose.waitForIdle()
        val session = viewer.session!!
        assertThat(opened).isEmpty()
        assertThat(session.agentId).isEqualTo("bc-taps")
        assertThat(session.entries.single()).isEqualTo(MediaEntry("/workspace/screenshots/45_panel.png", MediaEntry.Kind.Image, fileName = "45_panel.png", mimeType = "image/png"))
        assertThat(session.origin).isNotNull()
    }

    @Test
    fun `a search's hits open their file at the line of the hit`() {
        compose.setContent { Rows(grep) }
        // Only the hits open files: the search's own words name a pattern, not a file.
        assertThat(compose.onAllNodesWithTag("file-link").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Grepped").performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(8f, 0f)) }
        compose.waitForIdle()
        compose.onAllNodes(hasText("AgentFilesApi.kt:124")).fetchSemanticsNodes().let { assertThat(it).hasSize(1) }
        compose.onNodeWithTag("file-hit").performClick()
        assertThat(opened).containsExactly(FileOpenRequest("/workspace/app/src/AgentFilesApi.kt", null, 124))
    }
}
