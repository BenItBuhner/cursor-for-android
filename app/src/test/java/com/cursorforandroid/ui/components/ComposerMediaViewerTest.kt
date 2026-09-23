package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.media.ViewerScene
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The composer's media in the app's viewer, the way Bennett pages through them: opened on one tile and swiped to
 * the others, none of which was ever opened on its own. Every page shows its picture or plays its recording; the
 * "no longer on this device" notice is for a copy that really is gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerMediaViewerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = MediaViewerState(null)

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val pasted = PendingAttachment.of(PromptImage(png(720, 1600, AndroidColor.rgb(52, 120, 246)), "image/png"), id = "img-pasted")
    private val picked = PendingFile.of(PromptFile(png(1200, 900, AndroidColor.rgb(214, 108, 52)), "IMG_0412.png", "image/png"), id = "f-picked")
    private val recording = PendingFile(
        "f-recording",
        PromptFile(ByteArray(64 * 1024) { 1 }, "checkout-flow.mp4", "video/mp4"),
        thumbnail = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(46, 92, 60)) }.asImageBitmap(),
        durationMs = 12_000,
    )

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(30_000) {
        compose.waitForIdle()
        condition()
    }

    private fun copyOf(page: Int): File = File(Uri.parse(state.session!!.entries[page].src).path!!)

    private fun errorTitle(): String? = compose.onAllNodesWithTag("viewer-error-title", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun composer() {
        compose.setContent {
            ViewerScene(state, ViewerFixtures.loader(), entries = emptyList(), playerFactory = { FakeVideoPlayer() }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                    ComposerAttachments(images = listOf(pasted), onRemoveImage = {}, files = listOf(picked, recording), onRemoveFile = {}, surface = Color.Black, agentId = "bc-composer")
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openFirstTile() {
        compose.onAllNodesWithTag("media-tile")[0].performClick()
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
    }

    private fun swipeTo(page: Int) {
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        settle { state.currentIndex == page }
    }

    /**
     * Opened on the pasted picture, then swiped to the picked one and on to the recording, neither opened before: the
     * picture shows and the recording plays, each from a copy that is on disk by the time its page is reached.
     */
    @Test
    fun `swiping to attachments never opened shows each of them`() {
        composer()
        openFirstTile()
        // Every page's copy is there from the open on, not only the tapped one's.
        assertThat((0..2).map { copyOf(it).isFile }).containsExactly(true, true, true)

        swipeTo(1)
        settle { exists("viewer-image-1") || exists("viewer-error") }
        assertThat(errorTitle()).isNull()
        assertThat(exists("viewer-image-1")).isTrue()

        swipeTo(2)
        settle { exists("viewer-video-surface") || exists("viewer-error") }
        assertThat(errorTitle()).isNull()
        assertThat(exists("viewer-video-surface")).isTrue()
    }

    /** The tiles' copies are written as soon as the row is shown, before any tap: nothing waits on the viewer's open. */
    @Test
    fun `every tile's copy is written while the row is shown`() {
        composer()
        val dir = File(compose.activity.cacheDir, "composer-media")
        settle { (dir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0) == 3 }
    }

    /** A copy that really is gone — the cache cleared under the viewer — says so on its page, and the others still show. */
    @Test
    fun `a copy that is really gone says it is no longer on the device`() {
        composer()
        openFirstTile()
        // Gone after the open, before its page was reached: the recording's page is two swipes away and not yet composed.
        assertThat(copyOf(2).delete()).isTrue()

        swipeTo(1)
        settle { exists("viewer-image-1") }
        swipeTo(2)
        settle { exists("viewer-error") }
        assertThat(errorTitle()).isEqualTo(MediaLoader.NOT_ON_DEVICE)
        assertThat(exists("viewer-video-surface")).isFalse()
    }
}
