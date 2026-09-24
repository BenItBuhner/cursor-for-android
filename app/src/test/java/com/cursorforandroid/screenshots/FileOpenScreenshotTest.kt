package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.FakeVm
import com.cursorforandroid.data.repo.WorkspaceRepository
import com.cursorforandroid.data.repo.agentOn
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CarriedFile
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.files.FullFileResolver
import com.cursorforandroid.ui.files.FullFileScreen
import com.cursorforandroid.ui.media.MediaViewerHost
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A tool call's file, opened: the rows with their paths marked as links (a read, an edit, a search's hits), and
 * the full-file viewer an edit opens in Extended mode — the file as it is now, the lines the edit changed marked
 * and in view — and the part a ranged read carried in default mode, saying the whole file needs Extended mode.
 * Written to `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FileOpenScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val path = "/workspace/app/src/main/java/com/cursorforandroid/data/api/AgentFilesApi.kt"

    private val source = """package com.cursorforandroid.data.api

import java.util.Base64

internal object Bytes {
    /** Proto3 JSON spells `bytes` as standard base64; the URL-safe alphabet is accepted too, padding or not. */
    fun decodeBytes(encoded: String?): ByteArray {
        val text = encoded?.trim().orEmpty()
        if (text.isEmpty()) return ByteArray(0)
        return runCatching { Base64.getDecoder().decode(text) }
            .recoverCatching { Base64.getUrlDecoder().decode(text) }
            .recoverCatching { Base64.getMimeDecoder().decode(text) }
            .getOrElse { text.toByteArray(Charsets.UTF_8) }
    }

    /** The bytes a read carried, a wrapper taken off. */
    fun unwrapped(raw: ByteArray, name: String): ByteArray = when (val read = FileBytes.of(raw, name)) {
        is FileBytes.LfsPointer -> raw
        is FileBytes.Plain -> read.bytes
    }

    fun describe(bytes: ByteArray): String = FileFormat.sniff(bytes)?.label ?: "text"
}
"""

    private val edit = ToolPayload.FileDiff(
        path,
        "@@ -14,3 +14,9 @@\n     }\n \n+    /** The bytes a read carried, a wrapper taken off. */\n+    fun unwrapped(raw: ByteArray, name: String): ByteArray = when (val read = FileBytes.of(raw, name)) {\n+        is FileBytes.LfsPointer -> raw\n+        is FileBytes.Plain -> read.bytes\n+    }\n+\n     fun describe(bytes: ByteArray): String = FileFormat.sniff(bytes)?.label ?: \"text\"\n }\n",
        linesAdded = 6,
        linesRemoved = 0,
    )

    private val items: List<TimelineItem> = listOf(
        UserMessage("u1", "Where does the workspace read decode its bytes?"),
        ActivityGroup(
            "g1",
            listOf(
                ToolCall("c1", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "AgentFilesApi.kt L1-14", detail = path, payload = ToolPayload.FileContent(path, source.lines().take(14).joinToString("\n"), ToolPayload.FileContent.Kind.Read, totalLines = 23)),
                ThinkingBlock("decodeBytes falls back to the text's own bytes; a wrapper needs taking off first."),
                ToolCall("c2", "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "AgentFilesApi.kt", detail = path, linesAdded = 6, linesRemoved = 0, payload = edit),
                ToolCall(
                    "c3", "grep", ToolKind.Grep, ToolCall.STATUS_COMPLETED, "decodeBytes in src", detail = "decodeBytes in /workspace/app/src",
                    payload = ToolPayload.FileHits(
                        listOf(
                            ToolPayload.FileHits.Hit(path, 7, "fun decodeBytes(encoded: String?): ByteArray {"),
                            ToolPayload.FileHits.Hit("/workspace/app/src/test/java/com/cursorforandroid/data/api/AgentFilesApiTest.kt", 41, "decodeBytes(\"aGk=\")"),
                        ),
                    ),
                ),
                ToolCall("c4", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "45_panel_workspace_files.png", detail = "/workspace/screenshots/45_panel_workspace_files.png"),
            ),
        ),
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) { content() }
        }
    }

    private fun resolver(extended: Boolean): FullFileResolver {
        val vm = FakeVm(mapOf("app/src/main/java/com/cursorforandroid/data/api/AgentFilesApi.kt" to source.toByteArray()))
        val files = AgentFileRepository(
            WorkspaceRepository(vm, vm, capabilities = { if (extended) Capabilities.EXTENDED else Capabilities.DOCUMENTED }),
            { _, _, _ -> Result.failure(java.io.IOException("unused")) },
            agent = { agentOn(it, repoUrl = "https://github.com/BenItBuhner/cursor-for-android", branch = "main") },
        )
        return FullFileResolver(files, { extended }) { _, name -> "file:///cache/$name" }
    }

    /** The group opened: each file a link of its own, the search's hits under it. */
    @Test
    fun toolCallFileLinks() {
        val state = MediaViewerState(null)
        compose.setContent {
            Themed {
                MediaViewerHost(state, ViewerFixtures.loader(), autoHideControlsMillis = null) {
                    CompositionLocalProvider(
                        LocalTranscriptControls provides TranscriptControls(onOpenFile = {}),
                        LocalMarkdownMedia provides MarkdownMediaContext("bc-files", ViewerFixtures.loader()),
                    ) {
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items.forEach { TimelineItemView(it) }
                            Text("The decode now takes the wrapper off before the bytes reach it.", style = CursorTheme.typography.message, color = CursorTheme.colors.textPrimary)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Edited", substring = true).performTouchInput { click(centerLeft + Offset(8f, 0f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Grepped").performTouchInput { click(centerLeft + Offset(8f, 0f)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("file-hits")).fetchSemanticsNodes().isNotEmpty() }
        capture("153_tool_call_file_links")
    }

    /** The edit, opened in Extended mode: the file as it is now, the six lines it added marked and in view. */
    @Test
    fun fullFileViewerEdit() {
        val request = FileOpenRequest(path, "c2")
        val resolver = resolver(extended = true)
        compose.setContent {
            Themed { FullFileScreen(request, load = { resolver.resolve("bc-files", request, CarriedFile.of(items, path, "c2")) }, onClose = {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("full-file-marked")).fetchSemanticsNodes().size == 6 }
        capture("154_full_file_viewer_edit")
    }

    /** The ranged read, opened in default mode: its fourteen lines numbered as the file's, and the plain word on the rest. */
    @Test
    fun fullFileViewerDefaultMode() {
        val request = FileOpenRequest(path, "c1")
        val resolver = resolver(extended = false)
        compose.setContent {
            Themed { FullFileScreen(request, load = { resolver.resolve("bc-files", request, CarriedFile.of(items, path, "c1")) }, onClose = {}) }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("full-file-notice")).fetchSemanticsNodes().isNotEmpty() }
        capture("155_full_file_viewer_default_mode")
    }
}
