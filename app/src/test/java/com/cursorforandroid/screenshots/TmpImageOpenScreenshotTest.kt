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
import com.cursorforandroid.domain.ToolPayload
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

/**
 * A picture the agent read in `/tmp`, tapped in a chat (Bennett's 2026-09-22 frames): the media viewer on the
 * picture the read carried; and, when only the agent's machine holds it, what stops the read in the app's compact
 * notice with the request refused — the machine asleep with Wake and Retry, and the `not_found` that v0.3.70 showed
 * full screen as "Cursor changed a private endpoint", named as the missing file it is. Written to `screenshots/`
 * and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TmpImageOpenScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val readPath = "/aiserver.v1.BackgroundComposerService/ReadBinaryFile"
    private val listPath = "/aiserver.v1.BackgroundComposerService/ListWorkspaceFiles"

    private class Machine(private val refusal: (String) -> ConnectRpcException?, private val listRefusal: ConnectRpcException? = null) : WorkspaceFilesApi, DiffDetailsApi {
        override suspend fun listFiles(agentId: String): WorkspaceTree = listRefusal?.let { throw it } ?: WorkspaceTree(listOf("reel.py", "spec.md"))
        override suspend fun readFile(agentId: String, path: String): ByteArray = throw refusal(path) ?: IOException("unused")
        override suspend fun diffDetails(agentId: String) = AgentDiff(null, null, emptyList())
    }

    private fun loader(machine: Machine): MediaLoader {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = AgentFileRepository(
            WorkspaceRepository(machine, machine, capabilities = { Capabilities.EXTENDED }),
            repository = { _, _, _ -> Result.failure(IOException("unused")) },
            agent = { agentOn(ViewerFixtures.AGENT) },
        )
        return MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }), files = { files })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(loader: MediaLoader, call: ToolCall, until: String, ready: (MediaViewerState) -> Boolean = { true }) {
        val state = MediaViewerState(null)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    MediaViewerHost(state, loader, autoHideControlsMillis = null) {
                        val media = remember(loader) { MarkdownMediaContext(ViewerFixtures.AGENT, loader) }
                        CompositionLocalProvider(LocalTranscriptControls provides TranscriptControls(onOpenFile = {}), LocalMarkdownMedia provides media) {
                            Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp)) { ToolCallLine(call) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("file-link").performClick()
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            state.phase == MediaViewerState.Phase.Open && compose.onAllNodes(hasTestTag(until)).fetchSemanticsNodes().isNotEmpty() && ready(state)
        }
    }

    private fun readOf(path: String, payload: ToolPayload? = null) =
        ToolCall("c-read", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, path.substringAfterLast('/'), detail = path, payload = payload)

    /** The read carried the picture: the viewer opens on it, nothing asked of the machine. */
    @Test
    fun tmpPictureTheReadCarried() {
        val picture = ViewerFixtures.png("reel_frames_s.png", 960, 540, 0xFF2E4A62.toInt())
        // The quick third-size decode stands in first; the frame is the full one, whichever lands by the capture.
        open(loader(Machine({ null })), readOf("/tmp/reel_frames_s.jpg", ToolPayload.ReadMedia("/tmp/reel_frames_s.jpg", picture, "image/png")), until = "viewer-image-0") {
            it.currentDecodeSize.width == 960
        }
        capture("190_tmp_image_viewer_carried")
    }

    /** Only the machine holds it and the machine is asleep: said so, with the request, Wake and Retry. */
    @Test
    fun tmpPictureMachineAsleep() {
        val asleep = ConnectRpcException(400, "failed_precondition", "pod is not running", path = listPath)
        open(loader(Machine({ asleep }, listRefusal = asleep)), readOf("/tmp/v02_mid.png"), until = "viewer-error")
        capture("191_tmp_image_machine_asleep")
    }

    /** The machine answered `not_found` on HTTP 404: the missing file it is, with the request and Retry. */
    @Test
    fun tmpPictureNotOnTheMachine() {
        open(loader(Machine({ ConnectRpcException(404, "not_found", "File not found", path = readPath) })), readOf("/tmp/reel_frames_s.jpg"), until = "viewer-error")
        capture("192_tmp_image_missing_file")
    }
}
