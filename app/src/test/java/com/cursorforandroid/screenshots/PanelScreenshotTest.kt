package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.rememberLightboxState
import com.cursorforandroid.ui.panel.ConversationPanel
import com.cursorforandroid.ui.panel.DesktopScreen
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
 * The conversation panel, section by section, in the states the documented API leaves them in: the Changes list
 * with a patch open, the Files browser at the repository's root, the pull request verbatim, the media and usage
 * sections, the file viewer, and every degraded state — loading, unsupported host, a failed read, and the
 * Extended-mode placeholders. Written to `screenshots/`; CI compares them pixel for pixel.
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

    @Test
    fun changes() {
        compose.setContent { Panel(PanelFixtures.loaded()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.PendingQuestion)
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
        toggle(PanelSectionId.PendingQuestion)
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
        toggle(PanelSectionId.PendingQuestion)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.PullRequest)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("pull-request")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("41_panel_pull_request")
    }

    @Test
    fun mediaArtifactsUsage() {
        compose.setContent { Panel(PanelFixtures.loaded(generatedImage())) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.PendingQuestion)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Media)
        toggle(PanelSectionId.Artifacts)
        toggle(PanelSectionId.Usage)
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("The new toggle")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("settings-dark.png")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("42_panel_media_usage")
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
        toggle(PanelSectionId.Queue)
        toggle(PanelSectionId.Project)
        toggle(PanelSectionId.Remote)
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
        toggle(PanelSectionId.PendingQuestion)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Files)
        compose.onNodeWithTag("panel-sections").performScrollToNode(hasTestTag("files-tab-Workspace"))
        compose.onNodeWithTag("files-tab-Workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("workspace-dir")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("45_panel_workspace_files")
    }

    /** Extended mode: the Remote section of a cloud chat, offering its VM desktop. */
    @Test
    fun remoteDesktop() {
        compose.setContent { Panel(PanelFixtures.extended()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.PendingQuestion)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Remote)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("desktop-view")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("46_panel_remote_desktop")
    }

    /** Default mode: a Remote Control chat's machine, its state from the fleet endpoint and the documented floors. */
    @Test
    fun remoteControl() {
        compose.setContent { Panel(PanelFixtures.remoteControl()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.PendingQuestion)
        toggle(PanelSectionId.Changes)
        toggle(PanelSectionId.Remote)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("machine-status")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("47_panel_remote_control")
    }

    /** Extended mode: the Changes section reading the branch's diff before a pull request exists. */
    @Test
    fun branchDiff() {
        compose.setContent { Panel(PanelFixtures.extended()) }
        toggle(PanelSectionId.Header)
        toggle(PanelSectionId.PendingQuestion)
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("branch-diff-file")).fetchSemanticsNodes().isNotEmpty() }
        scrollToTop()
        capture("48_panel_branch_diff")
    }

    /** The desktop screen's chrome over the (empty, under Robolectric) noVNC canvas, in control. */
    @Test
    fun desktopScreen() {
        val session = DesktopSession("bc-demo", "wss://t-p-6080.c.cursorvm.com:443/websockify?network_token=x", viewOnly = false, port = 6080)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize().testTag("scene")) {
                    DesktopScreen(session, agentName = PanelFixtures.agent.name, onViewOnlyChange = {}, onReconnect = {}, onClose = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("desktop-connection")).fetchSemanticsNodes().isNotEmpty() }
        capture("49_panel_desktop_screen")
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
