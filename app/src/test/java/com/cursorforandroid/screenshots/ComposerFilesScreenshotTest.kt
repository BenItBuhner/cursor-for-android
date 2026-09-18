package com.cursorforandroid.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.FileUploadState
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Files of any type on a prompt (Extended mode): the composer's chips at rest — a gallery picture with its thumbnail,
 * a video, documents — and mid-upload, the "+" menu's two pickers in each mode, and the transcript's cards for the
 * files a prompt carried. Same device qualifiers as [AppScreenshotTest]; written to `screenshots/`, which CI
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
    private val trace = PendingFile("f3", PromptFile(ByteArray(48 * 1024), "network-trace.har", "application/json"))

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

    @Test
    fun composerFileChips() {
        compose.setContent {
            Scene {
                ComposerBox(
                    value = "Read the spec and the trace, then fix the checkout regression shown in the recording.",
                    onValueChange = {},
                    placeholder = "Follow up…",
                    onSend = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                    attachments = listOf(screenshot()),
                    onRemoveAttachment = {},
                    files = listOf(photo(), recording, spec, trace),
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
    private fun captureMenu(actions: ComposerMenuActions, name: String) {
        compose.setContent {
            Scene {
                ComposerBox(
                    value = "",
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
