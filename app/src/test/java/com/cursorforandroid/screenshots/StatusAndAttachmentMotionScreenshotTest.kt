package com.cursorforandroid.screenshots

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerAnchor
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.RollingText
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.conversation.CaptionFadeMillis
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.TranscriptControls
import com.cursorforandroid.ui.conversation.captionFade
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The run's status caption and a send's attachments, caught mid-motion on a stopped clock: the caption halfway onto the
 * transcript and halfway off it, halfway through rolling from "Working…" to "Reconnecting…"; and a send's picture tiles
 * and file card on their way from the composer's row into the bubble, with its text and alone. Same device qualifiers
 * as [AppScreenshotTest]; written to `screenshots/`, which CI compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StatusAndAttachmentMotionScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun frames(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    private fun capture(name: String) = compose.onNodeWithTag(FRAME).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(content: @Composable () -> Unit) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                Box(Modifier.testTag(FRAME).background(CursorTheme.colors.canvas)) { content() }
            }
        }
    }

    // ---- The run's status caption ----

    private var working by mutableStateOf(false)
    private var caption by mutableStateOf("Working…")

    private fun showTranscript() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Scene {
                LazyColumn(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item("prompt") { TimelineItemView(UserMessage("u-1", "Why does the release build drop the splash screen?")) }
                    item("reply") { TimelineItemView(AssistantMessage("a-1", "The theme's `windowSplashScreenAnimatedIcon` is stripped by R8; keeping it fixes the launch.")) }
                    if (working) {
                        item("working") {
                            Box(captionFade()) { RollingText(caption, style = CursorTheme.typography.base) }
                        }
                    }
                }
            }
        }
        frames(32)
    }

    /**
     * Halfway on, then halfway off. The list fades a removed row out from the layer it was last drawn into, and on a
     * stopped clock only a capture draws, so the row fades out as the first capture left it.
     */
    @Test
    fun captionFadingInAndOut() {
        showTranscript()
        compose.runOnUiThread { working = true }
        frames(CaptionFadeMillis / 2L)
        capture("660_working_caption_fading_in")
        frames(CaptionFadeMillis.toLong())
        compose.runOnUiThread { working = false }
        frames(CaptionFadeMillis / 2L)
        capture("661_working_caption_fading_out")
    }

    @Test
    fun captionRolling() {
        working = true
        showTranscript()
        frames(RollingText.HoldMillis + 64)
        compose.runOnUiThread { caption = "Reconnecting…" }
        frames(RollingText.RollMillis / 2L)
        capture("662_working_caption_rolling")
    }

    // ---- A send's attachments ----

    private val anchor = ComposerAnchor()
    private var composerText by mutableStateOf("Match the header to these, and follow the spec.")
    private val images = mutableStateListOf<PendingAttachment>()
    private val files = mutableStateListOf<PendingFile>()
    private val messages = mutableStateListOf<UserMessage>(UserMessage("u-1", "Start with the settings screen."))

    private fun swatch(width: Int, height: Int, color: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** What the composer holds and what the bubble will list, in the prompt's order: two pictures, then a PDF. */
    private fun attach(): List<MessageAttachment> {
        val dir = File(compose.activity.cacheDir, "sent").apply { mkdirs() }
        val shots = listOf(swatch(160, 120, AndroidColor.rgb(214, 108, 52)), swatch(120, 200, AndroidColor.rgb(52, 120, 246)))
        val sent = shots.mapIndexed { index, shot ->
            images += PendingAttachment("img-$index", PromptImage(png(shot), "image/png"), shot.asImageBitmap())
            val file = File(dir, "img-$index.png").apply { writeBytes(png(shot)) }
            MessageAttachment(file.path, shot.width, shot.height)
        }
        val spec = PromptFile(ByteArray(2_400 * 1024), "Q3-header-spec.pdf", "application/pdf")
        files += PendingFile("f-1", spec)
        val specCopy = File(dir, "Q3-header-spec.pdf").apply { writeBytes(ByteArray(16)) }
        return sent + MessageAttachment(specCopy.path, 0, 0, name = spec.name, mimeType = spec.mimeType, sizeBytes = spec.sizeBytes.toLong())
    }

    private fun showComposer(motion: SendMotion) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Scene {
                Box(Modifier.fillMaxWidth().height(560.dp)) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (message in messages) TimelineItemView(message)
                            Spacer(Modifier.weight(1f))
                            ComposerBox(
                                value = composerText,
                                onValueChange = {},
                                placeholder = "Follow up…",
                                onSend = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                                attachments = images.toList(),
                                onRemoveAttachment = {},
                                files = files.toList(),
                                onRemoveFile = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.fillMaxWidth(),
                                anchor = anchor,
                            )
                        }
                    }
                }
            }
        }
        frames(64)
    }

    /** The tap, as the chat's screen makes it, and the flight caught [into] its way to the bubble. */
    private fun sendAndCatch(motion: SendMotion, sent: List<MessageAttachment>, into: Float): SendFlight {
        val text = composerText.trim()
        val before = messages.mapTo(HashSet()) { it.id }
        var flight: SendFlight? = null
        compose.runOnUiThread {
            flight = motion.depart(anchor.takeoff(), text, before)
            composerText = ""
            images.clear()
            files.clear()
        }
        frames(16)
        compose.runOnUiThread { messages += UserMessage("u-2", text.ifEmpty { "See the attached files." }, attachments = sent, isPending = true) }
        frames(48)
        val f = checkNotNull(flight)
        assertThat(f.takeoff.attachments).hasSize(3)
        assertThat(f.phase).isEqualTo(SendFlight.Phase.Flying)
        frames((SendMotion.FlightMillis * into).toLong())
        return f
    }

    @Test
    fun attachmentsLiftOffWithTheText() {
        val motion = SendMotion(animatorsEnabled = { true })
        val sent = attach()
        showComposer(motion)
        sendAndCatch(motion, sent, into = 0.12f)
        capture("663_send_attachments_lifting_off")
    }

    @Test
    fun attachmentsMidFlight() {
        val motion = SendMotion(animatorsEnabled = { true })
        val sent = attach()
        showComposer(motion)
        sendAndCatch(motion, sent, into = 0.4f)
        capture("664_send_attachments_mid_flight")
    }

    @Test
    fun attachmentsAloneMidFlight() {
        val motion = SendMotion(animatorsEnabled = { true })
        composerText = ""
        val sent = attach()
        showComposer(motion)
        sendAndCatch(motion, sent, into = 0.4f)
        capture("665_send_attachments_alone_mid_flight")
    }

    private companion object {
        const val FRAME = "motion_frame"
    }
}
