package com.cursorforandroid.ui.panel

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.LocalVideoPlayerFactory
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerHost
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
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The panel's file viewer on a file of the agent's workspace or its repository: a picture, an SVG, a picture kept
 * as base64, a recording or a sound is the transcript's figure for it and opens the media viewer out of itself;
 * a pointer to Git LFS, a PDF or anything else is a row that names it and hands it on — never "Couldn't decode
 * this image", "This is a binary file" or "SVG files show in the browser" with nothing to do.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FileViewerMediaTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val viewer = MediaViewerState(null)
    private val loader by lazy { ViewerFixtures.loader() }
    private val opened = mutableListOf<String>()
    private var view by mutableStateOf<FileView?>(null)

    private val png: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(0xFF2A4A6A.toInt())
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(file: FileView) {
        view = file
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalVideoPlayerFactory provides { FakeVideoPlayer() }) {
                    MediaViewerHost(viewer, loader, autoHideControlsMillis = null) {
                        CompositionLocalProvider(LocalMarkdownMedia provides MarkdownMediaContext("bc-files", loader)) {
                            view?.let { FileViewerScreen(it, onBack = {}, onOpenUrl = { url -> opened += url }) }
                        }
                    }
                }
            }
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    @Test
    fun `a workspace picture is drawn and opens the media viewer out of itself`() {
        show(FileView.Workspace(RepoFile("screenshots/45_panel.png", png, png.size.toLong())))
        settle { compose.onAllNodes(hasContentDescription("45_panel.png")).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasText("Couldn't decode this image")).assertCountEquals(0)

        compose.onAllNodes(hasContentDescription("45_panel.png"))[0].performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        val session = viewer.session!!
        assertThat(session.origin).isNotNull()
        assertThat(session.entries.single().kind).isEqualTo(MediaEntry.Kind.Image)
        assertThat(viewer.currentSrc).startsWith("file://")
        assertThat(viewer.currentSrc).endsWith("45_panel.png")
    }

    @Test
    fun `an SVG and a picture kept as base64 are pictures too`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\"><rect width=\"80\" height=\"40\" fill=\"#fa0\"/></svg>".toByteArray()
        show(FileView.Workspace(RepoFile("docs/diagram.svg", svg, svg.size.toLong())))
        settle { compose.onAllNodes(hasContentDescription("diagram.svg")).fetchSemanticsNodes().size == 1 }

        val encoded = Base64.getMimeEncoder().encode(png)
        view = FileView.Repository(RepoFile("screenshots/encoded.png", encoded, encoded.size.toLong()))
        settle { compose.onAllNodes(hasContentDescription("encoded.png")).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun `a sound opens the player out of its chip`() {
        val mp3 = "ID3".toByteArray() + ByteArray(128)
        show(FileView.Workspace(RepoFile("recordings/standup.mp3", mp3, mp3.size.toLong())))
        settle { exists("audio-chip") }
        compose.onNodeWithTag("audio-chip").performClick()
        settle { viewer.phase == MediaViewerState.Phase.Open && exists("viewer-audio-card") }
        assertThat(viewer.current!!.kind).isEqualTo(MediaEntry.Kind.Audio)
        assertThat(viewer.session!!.origin).isNotNull()
    }

    @Test
    fun `a Git LFS pointer is named and opens the host's page`() {
        val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 2400000\n".toByteArray()
        show(FileView.Repository(RepoFile("screenshots/big.png", pointer, pointer.size.toLong(), downloadUrl = "https://github.com/acme/app/raw/main/screenshots/big.png")))
        settle { exists("file-problem") }
        compose.onNodeWithText("Stored with Git LFS").assertExists()
        compose.onNodeWithText("Open in browser").performClick()
        assertThat(opened).containsExactly("https://github.com/acme/app/raw/main/screenshots/big.png")
    }

    @Test
    fun `a PDF is named with its size and handed to another app or shared`() {
        val pdf = "%PDF-1.7\n1 0 obj\n".toByteArray()
        show(FileView.Workspace(RepoFile("docs/spec.pdf", pdf, 2_400_000L)))
        settle { exists("file-problem") }
        compose.onNodeWithText("PDF document \u00B7 2.3 MB").assertExists()
        compose.onNodeWithText("Open with\u2026").assertExists()
        compose.onNodeWithText("Share").assertExists()
    }

    @Test
    fun `text is text, a text file named like a picture included`() {
        val text = "404: Not Found\n".toByteArray()
        show(FileView.Workspace(RepoFile("shot.png", text, text.size.toLong())))
        settle { exists("text-file") }
        compose.onNodeWithText("404: Not Found").assertExists()
    }
}
