package com.cursorforandroid.screenshots

import android.content.Context
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.ArtifactPaths
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.ui.components.AudioChip
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.LocalVideoPlayerFactory
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerHost
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.panel.FileView
import com.cursorforandroid.ui.panel.FileViewerScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Files of the agent's machine in the media viewer and the panel: an SVG the agent saved, which the viewer used to
 * answer with the decoder's "…not encoded as a valid image format" and now draws; a screenshot of the workspace in
 * the panel's file viewer, opening the viewer out of itself; and a sound in the viewer's player. Written to
 * `screenshots/` beside the walkthrough and compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FileMediaScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val state = MediaViewerState(null)
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** The agent's diagram: boxes, arrows and labels, as an agent writes one to `/opt/cursor/artifacts/`. */
    private val diagram = """<svg xmlns="http://www.w3.org/2000/svg" width="720" height="440" viewBox="0 0 720 440">
        |<rect width="720" height="440" fill="#f7f7f5"/>
        |<g font-family="sans-serif" font-size="22" text-anchor="middle">
        |<rect x="40" y="60" width="190" height="90" rx="12" fill="#dbe7ff" stroke="#3b6fd8" stroke-width="3"/><text x="135" y="112" fill="#1d3f8a">Composer</text>
        |<rect x="265" y="60" width="190" height="90" rx="12" fill="#e7f6e7" stroke="#3a9a3a" stroke-width="3"/><text x="360" y="112" fill="#1f5e1f">MediaLoader</text>
        |<rect x="490" y="60" width="190" height="90" rx="12" fill="#fdebd8" stroke="#d27b1f" stroke-width="3"/><text x="585" y="112" fill="#7a430c">Viewer</text>
        |<rect x="265" y="280" width="190" height="90" rx="12" fill="#efe3fb" stroke="#8a4fd0" stroke-width="3"/><text x="360" y="332" fill="#4e2585">FileBytes</text>
        |<path d="M230 105 H262 M455 105 H487" stroke="#555" stroke-width="3" fill="none"/>
        |<path d="M360 150 V277" stroke="#555" stroke-width="3" fill="none" stroke-dasharray="8 6"/>
        |<text x="360" y="410" font-size="18" fill="#666">sniff · unwrap · decode</text>
        |</g></svg>
        |""".trimMargin().toByteArray()

    private fun artifactLoader(svg: ByteArray): MediaLoader {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "image/svg+xml").setBody(Buffer().write(svg))
        }
        val url = server.url("/signed/architecture.svg").toString()
        val api = object : FakeCursorApi() {
            override suspend fun artifactUrl(id: String, path: String) = DownloadArtifactResponseDto(url = url)
        }
        return MediaLoader(ApplicationProvider.getApplicationContext(), OkHttpClient(), ArtifactRepository(api = { api }))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(loader: MediaLoader, entries: List<MediaEntry>, content: @Composable () -> Unit) {
        val media = MarkdownMediaContext(AGENT, loader, entries = { entries })
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalVideoPlayerFactory provides { player }) {
                MediaViewerHost(state, loader, autoHideControlsMillis = null) {
                    CompositionLocalProvider(LocalMarkdownMedia provides media) { content() }
                }
            }
        }
    }

    private var player = FakeVideoPlayer()

    /** The SVG the agent saved, opened in the viewer: the diagram itself, where the decoder's refusal was printed. */
    @Test
    fun vmSvgInViewer() {
        val src = ArtifactPaths.VM_ROOT + "architecture.svg"
        val entries = listOf(MediaEntry(src, MediaEntry.Kind.Image, "Where the bytes go before a decoder sees them", fileName = "architecture.svg"))
        compose.setContent {
            Scene(artifactLoader(diagram), entries) {
                val colors = CursorTheme.colors
                Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Here is the pipeline as it stands:", style = CursorTheme.typography.message, color = colors.textPrimary)
                    ImageBlock(src, "Where the bytes go before a decoder sees them", heightCap = 200.dp)
                }
            }
        }
        settle { compose.onAllNodes(hasContentDescription("Where the bytes go before a decoder sees them")).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasContentDescription("Where the bytes go before a decoder sees them"))[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") && exists("viewer-top-bar") }
        capture("150_vm_svg_in_viewer")
    }

    /** A screenshot in the agent's workspace, in the panel's file viewer: drawn, a tap away from the viewer. */
    @Test
    fun panelWorkspaceImage() {
        val bytes = CoordinatorFixtures::class.java.classLoader!!.getResourceAsStream("fixtures/coordinator/tab-landscape-icon-only-project.png")!!.readBytes()
        val loader = MediaLoader(ApplicationProvider.getApplicationContext(), OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }))
        val file = RepoFile("screenshots/tab-landscape-icon-only-project.png", bytes, bytes.size.toLong())
        compose.setContent {
            Scene(loader, emptyList()) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                    FileViewerScreen(FileView.Workspace(file), onBack = {}, onOpenUrl = {})
                }
            }
        }
        settle { compose.onAllNodes(hasContentDescription("tab-landscape-icon-only-project.png")).fetchSemanticsNodes().size == 1 }
        capture("151_panel_workspace_image")
    }

    /** A sound, opened out of its chip: the card, the controls under it at a minute in, at one and a half times. */
    @Test
    fun audioPlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sound = File(context.filesDir, "shots/standup-notes.m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(64)) }
        val src = "file://${sound.absolutePath}"
        player = FakeVideoPlayer(durationMs = 204_000L)
        val loader = MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { FakeCursorApi() }))
        val entries = listOf(MediaEntry(src, MediaEntry.Kind.Audio, caption = "Stand-up notes, recorded on the VM", fileName = "standup-notes.m4a"))
        compose.setContent {
            Scene(loader, entries) {
                val colors = CursorTheme.colors
                Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("I recorded the stand-up summary:", style = CursorTheme.typography.message, color = colors.textPrimary)
                    AudioChip(src, "standup-notes.m4a", subtitle = "M4A audio \u00B7 1.9 MB")
                }
            }
        }
        settle { exists("audio-chip") }
        compose.onNodeWithTag("audio-chip").performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-speed") }
        compose.onNodeWithTag("viewer-speed").performClick()
        compose.runOnUiThread {
            player.seekTo(64_000L)
            player.pause()
        }
        settle { exists("viewer-play") }
        capture("152_audio_player")
    }

    private companion object {
        const val AGENT = "bc-files-shots"
    }
}
