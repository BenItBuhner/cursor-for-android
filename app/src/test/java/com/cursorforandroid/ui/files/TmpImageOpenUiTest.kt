package com.cursorforandroid.ui.files

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CursorServer
import com.cursorforandroid.data.api.CursorServerFiles
import com.cursorforandroid.data.api.CursorServerReadException
import com.cursorforandroid.data.api.DiffDetailsApi
import com.cursorforandroid.data.api.WorkspaceFilesApi
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.data.repo.WorkspaceRepository
import com.cursorforandroid.data.repo.agentOn
import com.cursorforandroid.domain.AgentDiff
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.domain.WorkspaceTree
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.media.ViewerScene
import com.cursorforandroid.ui.panel.ConversationPanel
import com.cursorforandroid.ui.panel.PanelActions
import com.cursorforandroid.ui.panel.PanelFixtures
import com.cursorforandroid.ui.panel.PanelSectionId
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * A picture the agent read outside its repository, tapped in a chat (Bennett's 2026-09-22 frames of
 * `/tmp/reel_frames_s.jpg` and `/tmp/v02_mid.png`, then a full screen of "Cursor changed a private endpoint…"): it
 * opens in the media viewer — from the row's name, from the path in the row's open card, from the panel's Files
 * list — on the picture the read carried when it did, else on the file the agent's machine holds at that path; and
 * what stops it is said in the app's compact notice, with the request that was refused and a way on.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TmpImageOpenUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewer = MediaViewerState(null)
    private val opened = mutableListOf<FileOpenRequest>()
    private val picture = ViewerFixtures.png("v02_mid.png", 240, 160, 0xFF335577.toInt())
    private val pictureBytes = File(URI(picture)).readBytes()
    private val listPath = "/aiserver.v1.BackgroundComposerService/ListWorkspaceFiles"

    /** The agent's machine: asleep until woken, holding [files] by the path the read names. */
    private class Machine(var awake: Boolean, private val files: Map<String, ByteArray>) : WorkspaceFilesApi, DiffDetailsApi {
        val reads = mutableListOf<String>()
        override suspend fun listFiles(agentId: String): WorkspaceTree {
            if (!awake) throw ConnectRpcException(400, "failed_precondition", "pod is not running", path = "/aiserver.v1.BackgroundComposerService/ListWorkspaceFiles")
            return WorkspaceTree(listOf("app/src/Main.kt"))
        }
        override suspend fun readFile(agentId: String, path: String): ByteArray {
            reads += path
            if (!awake) throw ConnectRpcException(400, "failed_precondition", "pod is not running", path = "/aiserver.v1.BackgroundComposerService/ReadBinaryFile")
            return files[path] ?: throw ConnectRpcException(404, "not_found", "File not found", path = "/aiserver.v1.BackgroundComposerService/ReadBinaryFile")
        }
        override suspend fun diffDetails(agentId: String) = AgentDiff(null, null, emptyList())
    }

    private var wakes = 0
    private val drafted = mutableListOf<String>()

    /** A cursor-server backed by the machine's own state: asleep until the machine is, serving its files by path. */
    private class Server(private val machine: Machine, private val files: Map<String, ByteArray>) : CursorServerFiles {
        val reads = mutableListOf<String>()
        override suspend fun server(agentId: String, commit: String, connectionToken: String): CursorServer {
            if (!machine.awake) throw ConnectRpcException(400, "failed_precondition", "pod is not running", path = "/aiserver.v1.BackgroundComposerService/GetCursorServerUrl")
            return CursorServer("pod", 443, connectionToken, emptyList())
        }
        override suspend fun read(server: CursorServer, path: String): ByteArray {
            reads += path
            return files[path] ?: throw CursorServerReadException(404, "GET https://pod:443/vscode-remote-resource?path=$path → HTTP 404 \"File not found\"", "no such file")
        }
    }

    private fun loader(machine: Machine, capabilities: Capabilities = Capabilities.EXTENDED, server: CursorServerFiles? = Server(machine, mapOf("/tmp/v02_mid.png" to pictureBytes, "/tmp/reel_frames_s.jpg" to pictureBytes))): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = AgentFileRepository(
            WorkspaceRepository(machine, machine, capabilities = { capabilities }),
            repository = { _, _, _ -> Result.failure(IOException("not asked")) },
            agent = { agentOn(ViewerFixtures.AGENT) },
            wakeMachine = { wakes++; machine.awake = true; true },
            cursorServer = server,
            mintToken = { "minted" },
            // The fake machine is up the moment it is woken; a delay here would wait on the test looper's clock.
            wakePollMs = 0,
        )
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }), files = { files })
    }

    private fun readOf(path: String, payload: ToolPayload? = null) =
        ToolCall("c-read", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, path.substringAfterLast('/'), detail = path, payload = payload)

    private fun rows(loader: MediaLoader, vararg calls: ToolCall) = compose.setContent {
        ViewerScene(viewer, loader, entries = emptyList()) {
            CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(onOpenFile = { opened += it }, onAskToCopyFile = { drafted += it })) {
                Column { calls.forEach { ToolCallLine(it) } }
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    @Test
    fun `a tmp picture the read carried opens in the viewer from its row, and nothing is asked of the machine`() {
        val machine = Machine(awake = true, files = emptyMap())
        rows(loader(machine), readOf("/tmp/v02_mid.png", ToolPayload.ReadMedia("/tmp/v02_mid.png", picture, "image/png")))
        compose.onNodeWithTag("file-link").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        assertThat(viewer.session!!.entries.single().src).isEqualTo(picture)
        assertThat(machine.reads).isEmpty()
        assertThat(opened).isEmpty()
        assertThat(exists("full-file")).isFalse()
    }

    @Test
    fun `a tmp picture only the machine holds opens from the path in the row's open card, read off the cursor-server`() {
        val machine = Machine(awake = true, files = emptyMap())
        val server = Server(machine, mapOf("/tmp/v02_mid.png" to pictureBytes))
        rows(loader(machine, server = server), readOf("/tmp/v02_mid.png"))
        // The verb opens the card, which shows the path whole: that path is a link too.
        compose.onNodeWithText("Read").performTouchInput { click(centerLeft + Offset(8f, 0f)) }
        settle { compose.onAllNodesWithTag("file-link").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag("file-link")[1].performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        assertThat(server.reads).contains("/tmp/v02_mid.png")
        assertThat(server.reads.toSet()).containsExactly("/tmp/v02_mid.png")
        assertThat(machine.reads).isEmpty()
        assertThat(opened).isEmpty()
    }

    @Test
    fun `the machine asleep is the compact notice with the request refused, and Wake reads the picture`() {
        val machine = Machine(awake = false, files = emptyMap())
        rows(loader(machine, server = Server(machine, mapOf("/tmp/reel_frames_s.jpg" to pictureBytes))), readOf("/tmp/reel_frames_s.jpg"))
        compose.onNodeWithTag("file-link").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-error") }
        assertThat(compose.onNodeWithTag("viewer-error-title").fetchSemanticsNode().config.toString()).contains("The agent's machine is asleep")
        compose.onNodeWithText("Asked: POST $listPath → HTTP 400 failed_precondition", substring = true).assertExists()
        assertThat(compose.onAllNodes(hasText("changed a private endpoint", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(exists("viewer-retry")).isTrue()
        compose.onNodeWithTag("viewer-wake").performClick()
        settle { exists("viewer-image-0") }
        assertThat(wakes).isEqualTo(1)
    }

    @Test
    fun `a file the machine does not have says so with the request, and Retry asks again`() {
        val machine = Machine(awake = true, files = emptyMap())
        val server = Server(machine, emptyMap())
        rows(loader(machine, server = server), readOf("/tmp/reel_frames_s.jpg"))
        compose.onNodeWithTag("file-link").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-error") }
        assertThat(compose.onNodeWithTag("viewer-error-title").fetchSemanticsNode().config.toString()).contains("The agent's machine has no such file")
        compose.onNodeWithText("vscode-remote-resource?path=/tmp/reel_frames_s.jpg → HTTP 404", substring = true).assertExists()
        val asked = server.reads.size
        compose.onNodeWithTag("viewer-retry").performClick()
        settle { server.reads.size > asked && exists("viewer-error") }
    }

    @Test
    fun `a picture with no source offers to ask the agent to copy it into the workspace, which drafts a follow-up`() {
        val machine = Machine(awake = true, files = emptyMap())
        // No cursor-server: nothing this chat carries has the picture, so only the copy ask is left.
        rows(loader(machine, server = null), readOf("/tmp/reel_frames_s.jpg"))
        compose.onNodeWithTag("file-link").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-error") }
        assertThat(compose.onNodeWithTag("viewer-error-title").fetchSemanticsNode().config.toString()).contains("outside the agent's workspace")
        assertThat(exists("viewer-ask-copy")).isTrue()
        compose.onNodeWithTag("viewer-ask-copy").performClick()
        settle { drafted.isNotEmpty() }
        assertThat(drafted).containsExactly("/tmp/reel_frames_s.jpg")
    }

    @Test
    fun `default mode says Extended mode reads the agent's machine, never that the repository cannot be browsed`() {
        val machine = Machine(awake = true, files = mapOf("/tmp/v02_mid.png" to pictureBytes))
        rows(loader(machine, Capabilities.DOCUMENTED), readOf("/tmp/v02_mid.png"))
        compose.onNodeWithTag("file-link").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-error") }
        assertThat(compose.onNodeWithTag("viewer-error-title").fetchSemanticsNode().config.toString()).contains("On the agent's machine")
        compose.onNodeWithText("reading it needs Extended mode", substring = true).assertExists()
        assertThat(compose.onAllNodes(hasText("cannot be browsed", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(machine.reads).isEmpty()
    }

    @Test
    fun `the panel's Files row for a tmp picture opens the media viewer on the read's picture, not the text viewer`() {
        val machine = Machine(awake = true, files = emptyMap())
        val touchedPaths = mutableListOf<String>()
        val state = PanelFixtures.loaded().copy(
            content = TranscriptContent(listOf(TranscriptContent.TouchedFile("/tmp/v02_mid.png", setOf(TranscriptContent.Touch.Read), carried = picture)), emptyList(), emptyList(), emptyList(), pendingQuestion = null),
        )
        val actions = object : PanelActions by PanelActions.None {
            override fun openTouched(path: String) { touchedPaths += path }
        }
        compose.setContent {
            ViewerScene(viewer, loader(machine), entries = emptyList()) { ConversationPanel(state, actions, onClose = {}) }
        }
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-${PanelSectionId.Files.name}"))
        compose.onNodeWithTag("section-${PanelSectionId.Files.name}").performClick()
        settle { exists("touched-file") }
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("touched-file"))
        compose.onNodeWithTag("touched-file").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        assertThat(viewer.session!!.entries.single().src).isEqualTo(picture)
        assertThat(touchedPaths).isEmpty()
        assertThat(machine.reads).isEmpty()
    }

    @Test
    fun `bytes the full-file viewer reads that are a picture go to the media viewer`() {
        var handed: MediaEntry? = null
        compose.setContent {
            FullFileScreen(
                FileOpenRequest("/tmp/frame", "c-read"),
                load = { FullFile.Media(picture, MediaEntry.Kind.Image, com.cursorforandroid.domain.FileFormat.PNG) },
                onClose = {},
                onOpenMedia = { handed = it },
            )
        }
        settle { handed != null }
        assertThat(handed).isEqualTo(MediaEntry(picture, MediaEntry.Kind.Image, fileName = "frame", mimeType = "image/png"))
    }
}
