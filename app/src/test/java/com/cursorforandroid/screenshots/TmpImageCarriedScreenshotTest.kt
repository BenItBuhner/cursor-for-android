package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.WorkspaceTree
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.ToolCallLine
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.media.MediaViewerHost
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
 * A picture the agent read in `/tmp`, opened in a chat, now that the machine's cursor-server serves it the way
 * Cursor's own app does (Bennett's frame: `ReadBinaryFile → invalid_argument "File path must stay within the
 * workspace."`): the picture in the media viewer, and — when no source has it — the plain notice offering to ask the
 * agent to copy it in. Written to `screenshots/` and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TmpImageCarriedScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val viewer = MediaViewerState(null)
    private val picture = ViewerFixtures.png("reel_frames_s.png", 960, 540, 0xFF2E4A62.toInt())
    private val bytes = File(URI(picture)).readBytes()

    /** A workspace VM that refuses a path outside the workspace, exactly as the pod does. */
    private class Vm : WorkspaceFilesApi, DiffDetailsApi {
        override suspend fun listFiles(agentId: String) = WorkspaceTree(listOf("reel.py", "spec.md"))
        override suspend fun readFile(agentId: String, path: String): ByteArray =
            throw ConnectRpcException(400, "invalid_argument", "File path must stay within the workspace.", path = "/aiserver.v1.BackgroundComposerService/ReadBinaryFile")
        override suspend fun diffDetails(agentId: String) = AgentDiff(null, null, emptyList())
    }

    private class Server(private val files: Map<String, ByteArray>) : CursorServerFiles {
        override suspend fun server(agentId: String, commit: String, connectionToken: String) = CursorServer("pod", 443, connectionToken, emptyList())
        override suspend fun read(server: CursorServer, path: String): ByteArray =
            files[path] ?: throw CursorServerReadException(404, "GET https://pod:443/vscode-remote-resource?path=$path → HTTP 404", "no such file")
    }

    private fun loader(server: CursorServerFiles?): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = AgentFileRepository(
            WorkspaceRepository(Vm(), Vm(), capabilities = { Capabilities.EXTENDED }),
            repository = { _, _, _ -> Result.failure(IOException("not asked")) },
            agent = { agentOn(ViewerFixtures.AGENT) },
            cursorServer = server,
            mintToken = { "minted" },
        )
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }), files = { files })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun readOf(path: String) = ToolCall("c-read", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, path.substringAfterLast('/'), detail = path)

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(server: CursorServerFiles?, until: String) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    MediaViewerHost(viewer, loader(server), autoHideControlsMillis = null) {
                        val media = remember { MarkdownMediaContext(ViewerFixtures.AGENT, loader(server)) }
                        CompositionLocalProvider(
                            LocalTranscriptControls provides TranscriptControls(onOpenFile = {}, onAskToCopyFile = {}),
                            LocalMarkdownMedia provides media,
                        ) {
                            Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp)) { ToolCallLine(readOf("/tmp/reel_frames_s.jpg")) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("file-link").performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            viewer.phase == MediaViewerState.Phase.Open && compose.onAllNodes(hasTestTag(until)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The picture, served off the machine's cursor-server, in the media viewer. */
    @Test
    fun tmpPictureFromCursorServer() {
        open(Server(mapOf("/tmp/reel_frames_s.jpg" to bytes)), until = "viewer-image-0")
        // The full-size decode replaces the quick stand-in before the capture.
        compose.waitUntil(30_000) { compose.waitForIdle(); viewer.currentDecodeSize.width == 960 }
        capture("193_tmp_image_from_cursor_server")
    }

    /** No source has it: the plain notice, with the ask-to-copy action. */
    @Test
    fun tmpPictureOutsideWorkspaceNotice() {
        open(server = null, until = "viewer-error")
        capture("194_tmp_image_outside_workspace")
    }
}
