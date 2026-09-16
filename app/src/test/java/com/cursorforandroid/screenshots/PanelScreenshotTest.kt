package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.ArtifactRepository
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.DesktopTrace
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.panel.ConversationPanel
import com.cursorforandroid.ui.panel.DesktopScreen
import com.cursorforandroid.ui.panel.DesktopState
import com.cursorforandroid.ui.panel.FileView
import com.cursorforandroid.ui.panel.PanelActions
import com.cursorforandroid.ui.panel.PanelFixtures
import com.cursorforandroid.ui.panel.PanelSectionId
import com.cursorforandroid.ui.panel.PanelState
import com.cursorforandroid.ui.panel.RemoteLoad
import com.cursorforandroid.ui.panel.WorkspaceBrowserState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The conversation panel, section by section, in the states the documented API leaves them in: the panel as it
 * opens, the Changes list with a patch open, the Files browser at the repository's root, the pull request verbatim,
 * the artifacts and usage sections, the file viewer, and every degraded state — loading, unsupported host, a failed
 * read — plus the Extended-mode sections (workspace, branch diff, side chats, the run's controls). Written to
 * `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PanelScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { PanelFixtures.NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    /** A swatch standing in for a generated image, written through the same on-device path a real one takes. */
    private fun generatedImage(): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(0x1E, 0x2A, 0x3A))
        val paint = Paint().apply { isAntiAlias = true }
        paint.color = AndroidColor.rgb(0xF5, 0x8A, 0x3E)
        canvas.drawRoundRect(40f, 60f, 200f, 140f, 40f, 40f, paint)
        paint.color = AndroidColor.rgb(0xFA, 0xFA, 0xFA)
        canvas.drawCircle(160f, 100f, 32f, paint)
        val file = File(context.filesDir, "generated/bc-demo/call-img.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    /** Artifacts resolve to the demo's bundled assets, so the media grid has real pixels and a real poster. */
    private class AssetApi : FakeCursorApi() {
        override suspend fun artifactUrl(id: String, path: String) = DownloadArtifactResponseDto(
            url = MediaLoader.ASSET_PREFIX + if (path.endsWith(".mp4")) "demo/predictive_back_demo.mp4" else "demo/predictive_back_drawer.png",
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Panel(state: PanelState) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = remember { MediaLoader(context, OkHttpClient(), ArtifactRepository(api = { AssetApi() })) }
        val lightbox = rememberLightboxState("bc-demo")
        val media = remember(loader, lightbox) { MarkdownMediaContext("bc-demo", loader, lightbox) }
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalMarkdownMedia provides media) {
                // The panel as it sits on a phone: over the chat's canvas, the width the host gives it.
                Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag("scene")) {
                    Box(Modifier.align(Alignment.CenterEnd).width(363.dp).fillMaxHeight().background(CursorTheme.colors.sidebar)) {
                        ConversationPanel(state, PanelActions.None, onClose = {})
                    }
                }
            }
        }
    }

    private fun toggle(section: PanelSectionId) {
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-${section.name}"))
        compose.onNodeWithTag("section-${section.name}").performClick()
        compose.waitForIdle()
    }

    private fun scrollToTop() {
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("section-Header"))
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** Default mode, as the panel opens on a finished chat with a pull request: the sections it has something for, nothing else. */
    @Test
    fun decluttered() {
        compose.setContent { Panel(PanelFixtures.loaded()) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("changed-file")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("62_panel_decluttered")
    }

    @Test
    fun changes() {
        compose.setContent { Panel(PanelFixtures.loaded()) }
        toggle(PanelSectionId.Header)
        // The first file open onto its patch.
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("changed-file"))
        compose.onAllNodesWithTag("changed-file")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("diff-block")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("39_panel_changes")
    }

    @Test
    fun files() {
        compose.setContent { Panel(PanelFixtures.loaded()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Files)
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("files-tab-Repository"))
        compose.onNodeWithTag("files-tab-Repository").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("repo-file")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("40_panel_files")
    }

    @Test
    fun pullRequest() {
        compose.setContent { Panel(PanelFixtures.loaded()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.PullRequest)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("pull-request")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("41_panel_pull_request")
    }

    /** The Artifacts section: the gallery of what the chat produced, the files under it, and the usage below. */
    @Test
    fun artifactsUsage() {
        compose.setContent { Panel(PanelFixtures.loaded(generatedImage())) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Artifacts)
        toggle(PanelSectionId.Usage)
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("The new toggle")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("settings-dark.png")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("42_panel_artifacts_usage")
    }

    @Test
    fun degradedStates() {
        val state = PanelFixtures.loaded().copy(
            agent = PanelFixtures.agent.copy(branches = listOf(PanelFixtures.agent.branches.single().copy(prUrl = "https://origin.cursor.com/bennett/cursor-for-android/pulls/97"))),
            pullRequest = RemoteLoad.Unsupported("This pull request is on Origin. Reading it needs an Origin access token, which this app cannot mint yet.", "https://origin.cursor.com/bennett/cursor-for-android/pulls/97"),
            artifacts = RemoteLoad.Failed("GitHub's anonymous rate limit for this network is used up."),
            usage = RemoteLoad.Loading,
        )
        compose.setContent { Panel(state) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.PullRequest)
        toggle(PanelSectionId.Artifacts)
        toggle(PanelSectionId.Usage)
        scrollToTop()
        capture("43_panel_degraded_states")
    }

    /** Extended mode: the Files › Workspace tab one level into the agent's live VM, a directory and a file side by side. */
    @Test
    fun workspaceFiles() {
        val state = PanelFixtures.extended().copy(workspace = WorkspaceBrowserState(path = "app", tree = RemoteLoad.Loaded(PanelFixtures.workspaceTree)))
        compose.setContent { Panel(state) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Files)
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("files-tab-Workspace"))
        compose.onNodeWithTag("files-tab-Workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("workspace-dir")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("49_panel_workspace_files")
    }

    /** Default mode: a Remote Control chat, its machine's state from the fleet endpoint among the Overview's facts. */
    @Test
    fun remoteControl() {
        compose.setContent { Panel(PanelFixtures.remoteControl()) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("machine-fact")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("50_panel_remote_control")
    }

    /** Extended mode: the Changes section reading the branch's diff before a pull request exists. */
    @Test
    fun branchDiff() {
        compose.setContent { Panel(PanelFixtures.extended()) }
        toggle(PanelSectionId.Header)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("branch-diff-file")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("52_panel_branch_diff")
    }

    /**
     * Every section closed, every header with a hint of a different length: the hints and chevrons line up against
     * the end edge, or the frame shows the drift.
     */
    @Test
    fun headersAligned() {
        compose.setContent { Panel(PanelFixtures.withSideChats().copy(expandedSections = PanelSectionId.entries.associateWith { false })) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("section-SideChats")).fetchSemanticsNodes().isNotEmpty() }
        capture("66_panel_headers_aligned")
    }

    /** Extended mode: the Side chats section of an ordinary chat, its two side chats and the offer of a new one. */
    @Test
    fun sideChats() {
        compose.setContent { Panel(PanelFixtures.withSideChats()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.SideChats)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("new-side-chat")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("64_panel_side_chats")
    }

    /** The desktop screen's chrome over the (empty, under Robolectric) noVNC canvas, in control, on its first step. */
    @Test
    fun desktopScreen() {
        val trace = DesktopTrace("bc-demo")
            .begin(DesktopTrace.GET_MACHINE, PanelFixtures.NOW).end(DesktopTrace.GET_MACHINE, PanelFixtures.NOW + 412, "pod pod-7f3a in us-east1 (desktop ticket)")
            .begin("probe t-9-pod-7f3a-26058.us-east1.cursorvm.com", PanelFixtures.NOW + 412).end("probe t-9-pod-7f3a-26058.us-east1.cursorvm.com", PanelFixtures.NOW + 1_090, "handshake accepted")
            .withEndpoint("t-9-pod-7f3a-26058.us-east1.cursorvm.com", 26058)
        val session = DesktopSession("bc-demo", "wss://t-9-pod-7f3a-26058.us-east1.cursorvm.com:443/websockify?network_token=x", viewOnly = false, port = 26058, trace = trace)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().testTag("scene")) {
                    DesktopScreen(DesktopState.Open(session), agentName = PanelFixtures.agent.name, onViewOnlyChange = {}, onRetry = {}, onFail = {}, onShare = {}, onClose = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("desktop-connection")).fetchSemanticsNodes().isNotEmpty() }
        capture("53_panel_desktop_screen")
    }

    /**
     * The viewer's two other states side by side: the steps while the machine is found and probed (left), and a
     * failure at the socket handshake with the step, the reason, a retry and the diagnostics to share (right).
     */
    @Test
    fun desktopViewerStates() {
        val opening = DesktopTrace("bc-demo")
            .begin(DesktopTrace.GET_MACHINE, PanelFixtures.NOW).end(DesktopTrace.GET_MACHINE, PanelFixtures.NOW + 412, "pod pod-7f3a in us-east1 (desktop ticket)")
            .begin("probe t-9-pod-7f3a-26058.us-east1.cursorvm.com", PanelFixtures.NOW + 412)
        val failed = opening
            .end("probe t-9-pod-7f3a-26058.us-east1.cursorvm.com", PanelFixtures.NOW + 1_090, "handshake accepted")
            .withEndpoint("t-9-pod-7f3a-26058.us-east1.cursorvm.com", 26058)
            .begin(DesktopTrace.PAGE, PanelFixtures.NOW + 1_090).end(DesktopTrace.PAGE, PanelFixtures.NOW + 1_402, "loaded")
            .begin(DesktopTrace.SCRIPT, PanelFixtures.NOW + 1_402).end(DesktopTrace.SCRIPT, PanelFixtures.NOW + 1_688, "noVNC loaded")
            .begin(DesktopTrace.SOCKET, PanelFixtures.NOW + 1_688).end(DesktopTrace.SOCKET, PanelFixtures.NOW + 11_688, "The socket handshake did not complete within 10 seconds: the socket closed before the RFB handshake finished, or dropped", failed = true)
        val failure = DesktopFailure.Unreachable("The socket handshake did not complete within 10 seconds: the socket closed before the RFB handshake finished, or dropped. The VM may have stopped, or the ticket may be refused.", failed)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Row(Modifier.fillMaxSize().testTag("scene")) {
                    DesktopScreen(DesktopState.Opening(opening), agentName = PanelFixtures.agent.name, onViewOnlyChange = {}, onRetry = {}, onFail = {}, onShare = {}, onClose = {}, modifier = Modifier.weight(1f).fillMaxHeight())
                    DesktopScreen(DesktopState.Failed(failure), agentName = PanelFixtures.agent.name, onViewOnlyChange = {}, onRetry = {}, onFail = {}, onShare = {}, onClose = {}, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("desktop-share-diagnostics")).fetchSemanticsNodes().isNotEmpty() }
        capture("75_desktop_viewer_states")
    }

    /** Extended mode on a running chat: the Overview's run controls and the question the agent is waiting on, answerable. */
    @Test
    fun runningExtended() {
        compose.setContent { Panel(PanelFixtures.extendedRunning()) }
        toggle(PanelSectionId.Changes)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("send-answer")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("45_panel_running_extended")
    }

    @Test
    fun fileViewer() {
        val code = """
            package com.cursorforandroid.ui.settings

            import androidx.compose.runtime.Composable
            import com.cursorforandroid.ui.components.CursorToggle

            /** The theme toggle of the Appearance section; the widget reads the same preference. */
            @Composable
            fun ThemeToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
                CursorToggle(checked = checked, onCheckedChange = onChange)
            }
        """.trimIndent()
        val file = RepoFile("app/src/main/java/com/cursorforandroid/ui/settings/ThemeToggle.kt", code.toByteArray(), code.length.toLong(), sha = "abc", downloadUrl = "https://raw.githubusercontent.com/x")
        compose.setContent { Panel(PanelFixtures.loaded().copy(browser = PanelFixtures.loaded().browser.copy(file = FileView.Repository(file)))) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("fun ThemeToggle", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("44_panel_file_viewer")
    }
}
