package com.cursorforandroid.ui.components

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The end of a dictation, frame by frame: once the words are in (or the transcription is cancelled) the footer's
 * "Transcribing…" only fades, the footer's exit and the status's own crossfade taking it down together, and no frame
 * draws it back up before it is gone.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DictationEndFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val words = CompletableDeferred<String>()
    private lateinit var voice: VoiceInput

    private val capture = object : AudioCapture {
        override fun start() {}
        override fun level() = 0.5f
        override fun stop() = RecordedClip(byteArrayOf(1), "audio/webm", 1_500)
        override fun cancel() {}
    }

    private val transcription = object : TranscriptionApi {
        override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?) = Transcription(words.await(), 300)
    }

    private fun transcribingNodes() = compose.onAllNodesWithText("Transcribing…", useUnmergedTree = true).fetchSemanticsNodes()

    /** How far the pixels in [area] are from its top-left pixel, the footer's own fill there. */
    private fun ink(area: Rect): Long {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        // Drawn directly: captureToImage waits for a redraw the held clock never lets happen.
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val fill = bitmap.getPixel(area.left.toInt(), area.top.toInt())
        var sum = 0L
        for (x in area.left.toInt() until area.right.toInt()) {
            for (y in area.top.toInt() until area.bottom.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                sum += abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(fill)) +
                    abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(fill)) +
                    abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(fill))
            }
        }
        return sum
    }

    /** Records, stops, and holds the clock on a settled "Transcribing…"; [end] then ends the dictation. */
    private fun dictationEnding(text: String, end: () -> Unit): String {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val value = mutableStateOf(text)
        compose.setContent {
            val scope = rememberCoroutineScope()
            voice = remember { VoiceInput(scope, transcription, { capture }, io = Dispatchers.Unconfined) }
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = value.value, onValueChange = { value.value = it }, placeholder = "Follow up…", onSend = {}, voice = voice)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.waitUntil(5_000) { voice.state is VoiceState.Recording }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(5_000) { transcribingNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)

        // The words' own box: with no pill or model chip in this footer, nothing that comes in after the status lands here.
        val area = transcribingNodes().single().boundsInRoot
        val shown = ink(area)
        assertThat(shown).isGreaterThan(0L)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread(end)
        var last = shown
        repeat(Frames) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val now = ink(area)
            assertWithMessage("frame $frame after the dictation ended draws \"Transcribing…\" back up ($last -> $now)").that(now).isAtMost(last)
            last = now
        }
        assertWithMessage("\"Transcribing…\" still drawn ${Frames} frames after the dictation ended").that(last).isEqualTo(0L)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(transcribingNodes()).isEmpty()
        return value.value
    }

    @Test
    fun `the words arriving fade Transcribing out and never back in`() {
        val text = dictationEnding("") { words.complete("login") }
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(text).isEqualTo("login")
    }

    @Test
    fun `the words arriving after typed text fade Transcribing out and never back in`() {
        val text = dictationEnding("Fix the bug") { words.complete("login") }
        assertThat(text).isEqualTo("Fix the bug login")
    }

    @Test
    fun `cancelling a transcription fades Transcribing out and never back in`() {
        val text = dictationEnding("") { voice.cancel() }
        assertThat(voice.state).isEqualTo(VoiceState.Idle)
        assertThat(text).isEmpty()
    }

    private companion object {
        /** Past the footer's 133ms exit and the status's own crossfade, with the frames a transition takes to start. */
        const val Frames = 24
    }
}
