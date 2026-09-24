package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMediaPreviews
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.FileUploadState
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.OutgoingStatus
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.media.FakeVideoPlayer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.MediaViewerState
import com.cursorforandroid.ui.media.UpgradeFadeMillis
import com.cursorforandroid.ui.media.ViewerFixtures
import com.cursorforandroid.ui.media.ViewerScene
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Files of any type on a prompt (Extended mode): the composer's chips at rest — a gallery picture with its thumbnail,
 * a video, documents — and mid-upload, the attachment row overflowing and scrolled with its fades, the "+" menu's two
 * pickers in each mode, and the transcript's cards for the files a prompt carried, in a row once there are more than two. Same device qualifiers as [AppScreenshotTest]; written to `screenshots/`, which CI
 * compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerFilesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private val spec = PendingFile("f1", PromptFile(ByteArray(2_400 * 1024), "Q3-billing-spec.pdf", "application/pdf"))
    private val recording = PendingFile("f2", PromptFile(ByteArray(11_600 * 1024), "checkout-flow.mp4", "video/mp4"))
    /** The same recording as the gallery hands it over: with its poster frame and its length. */
    private val recordingWithPoster: PendingFile get() = PendingFile("f2", recording.file, thumbnail = swatchBitmap(160, 90, AndroidColor.rgb(46, 92, 60)), durationMs = 12_000)
    private val trace = PendingFile("f3", PromptFile(ByteArray(48 * 1024), "network-trace.har", "application/json"))

    private fun swatchBitmap(width: Int, height: Int, color: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

    /** A swatch standing in for a picture: the bytes of a PNG, decodable for a thumbnail. */
    private fun swatch(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** A screenshot-sized swatch in the image strip, as it would be after a paste. */
    private fun screenshot(): PendingAttachment = PendingAttachment.of(PromptImage(swatch(120, 240, AndroidColor.rgb(52, 120, 246)), "image/png"), id = "img-1")

    /** A picture from the gallery, in Extended mode: a real file, its chip wearing its thumbnail. */
    private fun photo(): PendingFile = PendingFile.of(PromptFile(swatch(160, 120, AndroidColor.rgb(214, 108, 52)), "IMG_20260917_074100.png", "image/png"), id = "f0")

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                Column(
                    Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }

    /**
     * A mixed row — two pictures (one pasted, one picked), a recording, a PDF: every picture and recording as its
     * own tile, whichever way it arrived, the recording with its poster, play glyph and length; the PDF as the chip
     * with its name, kind and size.
     */
    @Test
    fun composerFileChips() {
        compose.setContent {
            Scene {
                ComposerBox(
                    value = "Stitch the picture into the green screen in the recording; the spec says where.",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    attachments = listOf(screenshot()),
                    onRemoveAttachment = {},
                    files = listOf(photo(), recordingWithPoster, spec),
                    onRemoveFile = {},
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "78_composer_file_chips.png").path, RoborazziOptions())
    }

    /** The composer over the app's viewer, [still] the row's first tile, ahead of a picked picture, a recording and a PDF. */
    private fun composerOverViewer(state: MediaViewerState, still: PendingAttachment) {
        compose.setContent {
            ViewerScene(state, ViewerFixtures.loader(), entries = emptyList(), playerFactory = { FakeVideoPlayer() }) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                    ComposerBox(
                        value = "Stitch the picture into the green screen in the recording.",
                        onValueChange = {},
                        placeholder = "Follow up…",
                        onSend = {},
                        plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                        attachments = listOf(still),
                        onRemoveAttachment = {},
                        files = listOf(photo(), recordingWithPoster, spec),
                        onRemoveFile = {},
                        modelLabel = "Claude Fable 5.1",
                        onModel = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun srcOf(still: PendingAttachment): String =
        ComposerMediaPreviews(File(compose.activity.cacheDir, "composer-media")).src(still.id, still.image.mimeType)

    /**
     * A press on the first tile, held (the clock stopped, so it is not a long press) until the page's screen-sized
     * decode the press started has landed, then let go: the open grows out of that picture, the same way every run.
     * Waits drain the main looper, which the open's hop back from writing the copies goes through.
     */
    private fun pressAndOpen(state: MediaViewerState, still: PendingAttachment) {
        val src = srcOf(still)
        val tile = compose.onAllNodesWithTag("media-tile")[0]
        compose.mainClock.autoAdvance = false
        tile.performTouchInput { down(center) }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.preloaded(src)?.isDone == true }
        tile.performTouchInput { up() }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Opening }
    }

    /**
     * A picture with the detail a screenshot has — a title bar, rows of small text, hairline rules, a chart — where a
     * thumbnail grown to the screen shows as blur. The bytes of a PNG, the size of a phone's screenshot.
     */
    private fun uiShot(): ByteArray {
        val width = 1080
        val height = 2340
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(AndroidColor.rgb(246, 247, 249))
        paint.color = AndroidColor.rgb(31, 111, 235)
        canvas.drawRect(0f, 0f, width.toFloat(), 220f, paint)
        paint.color = AndroidColor.WHITE
        paint.textSize = 56f
        canvas.drawText("Checkout · Order summary", 48f, 150f, paint)
        for (row in 0 until 12) {
            val top = 260f + row * 140f
            paint.color = AndroidColor.rgb(28, 32, 38)
            paint.textSize = 40f
            canvas.drawText("Line item ${row + 1} — Pro plan, annual seat", 48f, top + 60f, paint)
            paint.color = AndroidColor.rgb(98, 106, 118)
            paint.textSize = 30f
            canvas.drawText("SKU CUR-${1000 + row * 37} · qty ${row % 3 + 1} · \$${(row + 3) * 12}.00 · renews Sep 23", 48f, top + 108f, paint)
            paint.color = AndroidColor.rgb(210, 214, 222)
            canvas.drawRect(48f, top + 132f, width - 48f, top + 134f, paint)
        }
        val chartTop = 1980f
        paint.color = AndroidColor.rgb(222, 226, 232)
        for (line in 0..6) canvas.drawRect(48f, chartTop + line * 50f, width - 48f, chartTop + line * 50f + 1f, paint)
        paint.color = AndroidColor.rgb(31, 111, 235)
        paint.strokeWidth = 3f
        var x = 48f
        var y = chartTop + 250f
        for (step in 1..24) {
            val nx = 48f + step * (width - 96f) / 24
            val ny = chartTop + 250f - (step * 9 % 220) - step * 3
            canvas.drawLine(x, y, nx, ny, paint)
            x = nx
            y = ny
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * A tap on a tile opens the picture in the app's viewer, out of the tile: the page grown from the tile's box with
     * the scrim coming in behind it (a frame in, and halfway), the page open, and — on dismiss — halfway back into the
     * tile. The same transform the transcript's pictures ride (see MediaViewerScreenshotTest).
     */
    @Test
    fun composerMediaOpensInViewer() {
        val state = MediaViewerState(null)
        val still = PendingAttachment.of(PromptImage(swatch(720, 1600, AndroidColor.rgb(52, 120, 246)), "image/png"), id = "img-open")
        composerOverViewer(state, still)
        // Frozen a frame in, then halfway.
        pressAndOpen(state, still)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "117_composer_media_open_start.png").path, RoborazziOptions())
        compose.mainClock.advanceTimeBy(90)
        compose.waitForIdle()
        check(state.phase == MediaViewerState.Phase.Opening) { "the transform should still be running, was ${state.phase}" }
        compose.onRoot().captureRoboImage(File(outDir, "118_composer_media_open_mid.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Open }
        compose.waitUntil(10_000) { compose.waitForIdle(); compose.onAllNodesWithTag("viewer-image-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "119_composer_media_open.png").path, RoborazziOptions())
        // Dismissed: halfway back into the tile it came from. The close is picked up by the host's effect on the
        // next frame, so the clock is stopped once the transform has begun.
        compose.mainClock.autoAdvance = false
        state.close()
        var frames = 0
        while (state.phase != MediaViewerState.Phase.Closing && frames++ < 10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.mainClock.advanceTimeBy(90)
        compose.waitForIdle()
        check(state.phase == MediaViewerState.Phase.Closing) { "the close should still be running, was ${state.phase}" }
        compose.onRoot().captureRoboImage(File(outDir, "120_composer_media_close_mid.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Closed }
    }

    /**
     * The open of a screenshot-like picture, sharp all the way: a press started the page's screen-sized decode, and
     * the transform grows that picture out of the tile — three frames in, six (the picture near half the screen,
     * where a tile-sized thumbnail was blur), and the page open on the same pixels.
     */
    @Test
    fun composerMediaOpensSharp() {
        val state = MediaViewerState(null)
        val shot = PendingAttachment.of(PromptImage(uiShot(), "image/png"), id = "img-shot")
        composerOverViewer(state, shot)
        pressAndOpen(state, shot)
        check(state.session!!.seen === state.preloaded(srcOf(shot))!!.bitmap) { "the open should start on the press's decode" }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "215_composer_media_sharp_open_frame3.png").path, RoborazziOptions())
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        check(state.phase == MediaViewerState.Phase.Opening) { "the transform should still be running, was ${state.phase}" }
        compose.onRoot().captureRoboImage(File(outDir, "216_composer_media_sharp_open_frame6.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Open }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "217_composer_media_sharp_open.png").path, RoborazziOptions())
    }

    /**
     * The tap beating the decode: the open starts on the tile's own picture — decoded at half the screen for these
     * frames — and the screen-sized one, held here until two frames in, fades in over it inside the transform.
     */
    @Test
    fun composerMediaUpgradesMidOpen() {
        val state = MediaViewerState(null)
        val shot = PendingAttachment.of(PromptImage(uiShot(), "image/png"), id = "img-shot-late")
        composerOverViewer(state, shot)
        val src = srcOf(shot)
        compose.waitUntil(10_000) { compose.waitForIdle(); File(Uri.parse(src).path!!).isFile }
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { state.preload(MediaEntry(src, MediaEntry.Kind.Image), "bc-1") { gate.await() } }
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithTag("media-tile")[0].performClick()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Opening }
        check(state.session!!.seen === shot.thumbnail) { "the open should start on the tile's picture" }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "218_composer_media_open_on_tile_picture.png").path, RoborazziOptions())
        gate.complete(Unit)
        compose.waitUntil(10_000) { compose.waitForIdle(); state.preloaded(src)?.isDone == true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(UpgradeFadeMillis / 2L)
        compose.waitForIdle()
        check(state.phase == MediaViewerState.Phase.Opening) { "the fade should land inside the transform, was ${state.phase}" }
        compose.onRoot().captureRoboImage(File(outDir, "219_composer_media_upgrade_mid_open.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.waitForIdle(); state.phase == MediaViewerState.Phase.Open }
    }

    /**
     * The files going up the moment they were attached, the user still typing: the first is up and reads as a plain
     * chip, the second fills, the third failed and offers a retry; the send is held, the footer saying why.
     */
    @Test
    fun composerFileChipsUploading() {
        compose.setContent {
            Scene {
                ComposerBox(
                    value = "Read the spec and the trace, then fix the checkout regression shown in the reco",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    canSend = false,
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    files = listOf(spec, recording, trace),
                    onRemoveFile = {},
                    fileUploads = mapOf("f1" to FileUploadState.DONE, "f2" to FileUploadState(progress = 0.62f), "f3" to FileUploadState(progress = 0f, failed = true)),
                    onRetryFile = {},
                    sendHint = "Uploading 2 of 3…",
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "79_composer_file_uploading.png").path, RoborazziOptions())
    }

    /** The "+" menu open over the composer; the menu is a popup window, so the whole screen is what carries it. */
    private fun captureMenu(actions: ComposerMenuActions, name: String, value: String = "") {
        compose.setContent {
            Scene {
                ComposerBox(
                    value = value,
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = actions,
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    /** Extended mode: Images and videos (the gallery, as real files) and Files (the document picker). */
    @Test
    fun composerMenuExtended() = captureMenu(ComposerMenuActions(onPickMedia = {}, onPickFiles = {}), "80_composer_plus_menu_extended")

    /** The default mode: Images alone, the image-only picker behind the documented `prompt.images[]`. */
    @Test
    fun composerMenuDefault() = captureMenu(ComposerMenuActions(onPickMedia = {}), "82_composer_plus_menu_default")

    /** A draft wearing the Multitask pill: the pill stays in the footer, and the menu over it has no Multitask row. */
    @Test
    fun composerMenuOverMultitaskPill() = captureMenu(
        ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
        "550_composer_plus_menu_multitask_pill",
        value = "/multitask Fan the flaky suites out to subagents",
    )

    /**
     * More attached than fits: one row, scrolling sideways — a picture beside the chips — its end dissolving into the
     * composer where there is more past it, the start a hard edge; and the same row at its end, the fade moved to the start.
     */
    @Test
    fun composerAttachmentRow() {
        val notes = PendingFile("f4", PromptFile(ByteArray(900), "release-notes.md", "text/markdown"))
        compose.setContent {
            Scene {
                ComposerBox(
                    value = "Take this image and stitch it into the green screen in the recording; the spec says where.",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    attachments = listOf(screenshot()),
                    onRemoveAttachment = {},
                    files = listOf(photo(), recording, spec, trace, notes),
                    onRemoveFile = {},
                    fileUploads = mapOf("f0" to FileUploadState.DONE, "f2" to FileUploadState(progress = 0.62f), "f1" to FileUploadState.DONE, "f3" to FileUploadState(failed = true), "f4" to FileUploadState.DONE),
                    onRetryFile = {},
                    sendHint = "Uploading 2 of 5…",
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "93_composer_attachment_row.png").path, RoborazziOptions())
        compose.onNodeWithTag("attachment-row").performScrollToIndex(5)
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "94_composer_attachment_row_end.png").path, RoborazziOptions())
    }

    /** A prompt that carried more than two: its pictures and cards in the one row, dissolving where there is more. */
    @Test
    fun transcriptAttachmentRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "attachments/bc-demo/run-2").apply { mkdirs() }
        val before = File(dir, "f0-before.png").apply { writeBytes(swatch(720, 1600, AndroidColor.rgb(52, 120, 246))) }
        val after = File(dir, "f1-after.png").apply { writeBytes(swatch(1600, 900, AndroidColor.rgb(214, 108, 52))) }
        val pdf = File(dir, "f2-Q3-billing-spec.pdf").apply { writeBytes(ByteArray(2_400 * 1024)) }
        val har = File(dir, "f3-network-trace.har").apply { writeBytes(ByteArray(48 * 1024)) }
        val scene = listOf(
            UserMessage(
                "u2",
                "Before and after, the spec and the trace: find where the regression came in.",
                timestampMillis = 1_736_949_600_000,
                attachments = listOf(
                    MessageAttachment(before.path, 720, 1600),
                    MessageAttachment(after.path, 1600, 900),
                    MessageAttachment.file(pdf.path, "Q3-billing-spec.pdf", "application/pdf", pdf.length()),
                    MessageAttachment.file(har.path, "network-trace.har", "application/json", har.length()),
                ),
            ),
            AssistantMessage("a2", "The after screenshot's total is what the HAR's `/checkout/confirm` response carries; the spec's rounding rule is what changed."),
        )
        compose.setContent { Scene { scene.forEach { TimelineItemView(it) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "95_transcript_attachment_row.png").path, RoborazziOptions())
    }

    /**
     * Send tapped with a file still going up: the composer is empty from that frame — the message is the transcript's,
     * its bubble carrying the picture, the file and the upload's progress — and a new draft can begin at once.
     */
    @Test
    fun composerClearsOnSend() {
        val (bubble, reply) = outgoingScene()
        compose.setContent {
            Scene {
                CompositionLocalProvider(
                    LocalTranscriptControls provides TranscriptControls(
                        outgoing = mapOf(bubble.id to OutgoingStatus.Uploading(done = 1, total = 2, progress = 0.62f)),
                        onRetryOutgoing = {},
                        onEditOutgoing = {},
                    ),
                ) {
                    TimelineItemView(reply)
                    TimelineItemView(bubble)
                }
                ComposerBox(
                    value = "",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "115_composer_clears_on_send.png").path, RoborazziOptions())
    }

    /**
     * The send did not get through: the bubble says why and offers Retry and Edit — the message is never back in the
     * composer unasked, and never lost — while a new draft, its own chips, is being written below.
     */
    @Test
    fun outgoingFailedRetryEdit() {
        val (bubble, reply) = outgoingScene()
        compose.setContent {
            Scene {
                CompositionLocalProvider(
                    LocalTranscriptControls provides TranscriptControls(
                        outgoing = mapOf(bubble.id to OutgoingStatus.Failed("Couldn't upload network-trace.har: Storage is unavailable right now.")),
                        onRetryOutgoing = {},
                        onEditOutgoing = {},
                    ),
                ) {
                    TimelineItemView(reply)
                    TimelineItemView(bubble)
                }
                ComposerBox(
                    value = "Meanwhile, compare the before and after",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    canSend = true,
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    files = listOf(spec),
                    onRemoveFile = {},
                    fileUploads = mapOf("f1" to FileUploadState.DONE),
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "116_outgoing_failed_retry_edit.png").path, RoborazziOptions())
    }

    /** A pending prompt with a picture and a file, and the reply before it, as the transcript holds them ahead of the server. */
    private fun outgoingScene(): Pair<UserMessage, AssistantMessage> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "attachments/bc-demo/local-9").apply { mkdirs() }
        val still = File(dir, "img-0.png").apply { writeBytes(swatch(720, 1600, AndroidColor.rgb(52, 120, 246))) }
        val har = File(dir, "f0-network-trace.har").apply { writeBytes(ByteArray(48 * 1024)) }
        val bubble = UserMessage(
            "local-9",
            "Stitch the image into the green screen in the recording and send the trace along.",
            timestampMillis = 1_736_949_700_000,
            attachments = listOf(MessageAttachment(still.path, 720, 1600), MessageAttachment.file(har.path, "network-trace.har", "application/json", har.length())),
            isPending = true,
        )
        val reply = AssistantMessage("a0", "Reading the spec first; the HAR shows the `/checkout/confirm` call returning 500 after the coupon step.")
        return bubble to reply
    }

    @Test
    fun transcriptFileCards() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "attachments/bc-demo/run-1").apply { mkdirs() }
        val pdf = File(dir, "f0-Q3-billing-spec.pdf").apply { writeBytes(ByteArray(2_400 * 1024)) }
        val har = File(dir, "f1-network-trace.har").apply { writeBytes(ByteArray(48 * 1024)) }
        val scene = listOf(
            UserMessage(
                "u1",
                "Read the spec and the trace, then fix the checkout regression.",
                timestampMillis = 1_736_949_600_000,
                attachments = listOf(
                    MessageAttachment.file(pdf.path, "Q3-billing-spec.pdf", "application/pdf", pdf.length()),
                    MessageAttachment.file(har.path, "network-trace.har", "application/json", har.length()),
                ),
            ),
            AssistantMessage("a1", "Reading the spec first; the HAR shows the `/checkout/confirm` call returning 500 after the coupon step."),
        )
        compose.setContent { Scene { scene.forEach { TimelineItemView(it) } } }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "81_transcript_file_cards.png").path, RoborazziOptions())
    }
}
