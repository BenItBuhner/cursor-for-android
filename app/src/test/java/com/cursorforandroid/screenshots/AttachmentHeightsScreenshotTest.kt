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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pictures and files side by side, all one height: the composer's row with a pasted picture beside an archive's chip
 * (the frame Bennett sent: the picture stood taller than "Burrow diagnostics- samsung SM-S948U.zip"), the same row
 * with a recording, a document going up and one that failed, and a sent prompt's row in the transcript — on a phone
 * and on a tablet, dark and light.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AttachmentHeightsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun swatch(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun pasted(): PendingAttachment = PendingAttachment.of(PromptImage(swatch(120, 240, AndroidColor.rgb(52, 120, 246)), "image/png"), id = "img-1")

    private val diagnostics = PendingFile("f-zip", PromptFile(ByteArray(102 * 1024), "Burrow diagnostics- samsung SM-S948U.zip", "application/zip"))
    private val spec = PendingFile("f-pdf", PromptFile(ByteArray(2_400 * 1024), "Q3-billing-spec.pdf", "application/pdf"))
    private val trace = PendingFile("f-har", PromptFile(ByteArray(48 * 1024), "network-trace.har", "application/json"))
    private val recording: PendingFile
        get() = PendingFile(
            "f-mp4",
            PromptFile(ByteArray(11_600 * 1024), "checkout-flow.mp4", "video/mp4"),
            thumbnail = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.rgb(46, 92, 60)) }.asImageBitmap(),
            durationMs = 12_000,
        )

    /** A sent prompt with a picture, the archive and a document: more than two, so the transcript's row. */
    private fun sentPrompt(): UserMessage {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, "attachments/bc-heights/run-1").apply { mkdirs() }
        val still = File(dir, "f0-still.png").apply { writeBytes(swatch(720, 1600, AndroidColor.rgb(52, 120, 246))) }
        val zip = File(dir, "f1-diagnostics.zip").apply { writeBytes(ByteArray(102 * 1024)) }
        val pdf = File(dir, "f2-spec.pdf").apply { writeBytes(ByteArray(2_400 * 1024)) }
        return UserMessage(
            "u1",
            "The diagnostics from the phone, and the spec.",
            timestampMillis = 1_736_949_600_000,
            attachments = listOf(
                MessageAttachment(still.path, 720, 1600),
                MessageAttachment.file(zip.path, "Burrow diagnostics- samsung SM-S948U.zip", "application/zip", zip.length()),
                MessageAttachment.file(pdf.path, "Q3-billing-spec.pdf", "application/pdf", pdf.length()),
            ),
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun capture(mode: ThemeMode, name: String) {
        val prompt = sentPrompt()
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    Column(
                        Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).padding(16.dp).testTag("scene"),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pane = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
                        Column(pane) { TimelineItemView(prompt) }
                        ComposerBox(
                            value = "Guess who installed the latest version. Still not opening; the diagnostics are attached.",
                            onValueChange = {},
                            placeholder = "Follow up…",
                            onSend = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                            attachments = listOf(pasted()),
                            onRemoveAttachment = {},
                            files = listOf(diagnostics),
                            onRemoveFile = {},
                            fileUploads = mapOf(diagnostics.id to FileUploadState.DONE),
                            modelLabel = "Claude Opus 5.5",
                            onModel = {},
                            modifier = pane,
                        )
                        ComposerBox(
                            value = "The recording, the spec and the trace.",
                            onValueChange = {},
                            placeholder = "Follow up…",
                            onSend = {},
                            canSend = false,
                            plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                            files = listOf(recording, spec, trace),
                            onRemoveFile = {},
                            fileUploads = mapOf(recording.id to FileUploadState.DONE, spec.id to FileUploadState(progress = 0.62f), trace.id to FileUploadState(failed = true)),
                            onRetryFile = {},
                            sendHint = "Uploading 1 of 3…",
                            modelLabel = "Claude Opus 5.5",
                            onModel = {},
                            modifier = pane,
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun phoneDark() = capture(ThemeMode.Dark, "934_attachment_heights_phone_dark")

    @Test
    fun phoneLight() = capture(ThemeMode.Light, "935_attachment_heights_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun tabletDark() = capture(ThemeMode.Dark, "936_attachment_heights_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun tabletLight() = capture(ThemeMode.Light, "937_attachment_heights_tablet_light")
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
