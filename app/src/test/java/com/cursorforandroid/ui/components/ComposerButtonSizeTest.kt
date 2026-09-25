package com.cursorforandroid.ui.components

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's round buttons measured as rendered: the microphone beside Send or Stop is the same disc, the same
 * touch target and on the same centre line as the main button, in every state of the send slot and of a dictation,
 * with a glyph no larger to the eye than the arrow's. Run on a phone and on a tablet ([PhoneComposerButtonSizeTest],
 * [TabletComposerButtonSizeTest]).
 */
abstract class ComposerButtonSizeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeCapture : AudioCapture {
        @Volatile var started = false
        @Volatile var stopped = false
        @Volatile var cancelled = false
        override fun start() { started = true }
        override fun level() = 0.5f
        override fun stop(): RecordedClip { stopped = true; return RecordedClip(byteArrayOf(1), "audio/webm", 1_500) }
        override fun cancel() { cancelled = true }
    }

    private val capture = FakeCapture()
    private val sent = CopyOnWriteArrayList<String>()
    private lateinit var voice: VoiceInput
    private var density = 1f

    @Composable
    private fun Host(value: MutableState<String>, running: MutableState<Boolean>) {
        val scope = rememberCoroutineScope()
        voice = remember { VoiceInput(scope, { _, _, _ -> Transcription("words", null) }, { capture }) }
        density = LocalDensity.current.density
        CursorTheme(mode = ThemeMode.Dark) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                ComposerBox(
                    value = value.value,
                    onValueChange = { value.value = it },
                    placeholder = "Follow up…",
                    onSend = { sent += value.value },
                    isRunning = running.value,
                    onStop = {},
                    plusMenu = ComposerMenuActions(onPickMedia = {}),
                    modelLabel = "Claude Fable 5.1",
                    onModel = {},
                    voice = voice,
                )
            }
        }
    }

    private val text = mutableStateOf("")
    private val running = mutableStateOf(false)

    private fun show() {
        compose.setContent { Host(text, running) }
        compose.waitForIdle()
    }

    private fun dp(px: Float) = px / density

    private val mainSlot = hasTestTag("composer-main")
    private val micBeside: SemanticsMatcher = hasTestTag("voice-mic") and !hasAnyAncestor(mainSlot)

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onNode(matcher, useUnmergedTree = true)

    private fun disc(matcher: SemanticsMatcher): Rect = node(matcher).fetchSemanticsNode().boundsInRoot

    /** The layer that takes the tap: the 40dp box the disc sits in the middle of. */
    private fun touch(matcher: SemanticsMatcher): Rect =
        node(hasClickAction() and (hasAnyAncestor(matcher) or matcher)).fetchSemanticsNode().boundsInRoot

    private fun micBesideShown() = compose.onAllNodes(micBeside, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assertRoundButton(bounds: Rect) {
        assertThat(dp(bounds.width)).isWithin(0.5f).of(CursorDimens.roundButton.value)
        assertThat(dp(bounds.height)).isWithin(0.5f).of(CursorDimens.roundButton.value)
    }

    /** The main disc, and the mic beside it when there is one: equal discs on one centre line, spaced as designed. */
    private fun assertPair(): Rect {
        val main = disc(mainSlot)
        assertRoundButton(main)
        if (micBesideShown()) {
            val mic = disc(micBeside)
            assertThat(mic.width).isWithin(0.5f).of(main.width)
            assertThat(mic.height).isWithin(0.5f).of(main.height)
            assertThat(mic.center.y).isWithin(0.5f).of(main.center.y)
            assertThat(dp(main.left - mic.right)).isWithin(0.5f).of(CursorDimens.roundButtonGap.value)
        }
        return main
    }

    private fun setState(state: VoiceState, levels: List<Float> = emptyList()) {
        compose.runOnIdle { voice.show(state, levels, elapsedMillis = 3_000L) }
        compose.waitForIdle()
    }

    @Test
    fun `the microphone beside Send is the same disc, touch target and centre line as Send`() {
        text.value = "Fix the login redirect"
        show()
        assertPair()
        val mic = touch(micBeside)
        val send = touch(mainSlot)
        assertThat(dp(mic.width)).isWithin(0.5f).of(CursorDimens.roundButtonTouch.value)
        assertThat(dp(mic.height)).isWithin(0.5f).of(CursorDimens.roundButtonTouch.value)
        assertThat(mic.width).isWithin(0.5f).of(send.width)
        assertThat(mic.height).isWithin(0.5f).of(send.height)
        assertThat(mic.center.y).isWithin(0.5f).of(send.center.y)
        // Neither target reaches into the other's, so the mic keeps every dp of its own.
        assertThat(mic.right).isAtMost(send.left + 0.5f)
    }

    @Test
    fun `the microphone beside Stop is the same disc and touch target as Stop`() {
        running.value = true
        show()
        assertPair()
        val mic = touch(micBeside)
        val stop = touch(mainSlot)
        assertThat(mic.width).isWithin(0.5f).of(stop.width)
        assertThat(mic.height).isWithin(0.5f).of(stop.height)
        assertThat(mic.right).isAtMost(stop.left + 0.5f)
    }

    @Test
    fun `no state of the send slot or of a dictation changes a disc's size or place`() {
        show()
        val idle = assertPair() // the main button is the mic
        assertThat(dp(touch(mainSlot).width)).isWithin(0.5f).of(CursorDimens.roundButtonTouch.value)

        setState(VoiceState.Recording(0L), levels = List(48) { 1f })
        assertThat(assertPair()).isEqualTo(idle)
        setState(VoiceState.Transcribing)
        assertThat(assertPair()).isEqualTo(idle)
        setState(VoiceState.Idle)

        compose.runOnIdle { text.value = "Fix the login redirect" }
        compose.waitForIdle()
        assertThat(assertPair()).isEqualTo(idle) // Send, the mic beside it
        val besideSend = disc(micBeside)
        setState(VoiceState.Recording(0L), levels = List(48) { 1f })
        assertThat(assertPair()).isEqualTo(idle)
        assertThat(disc(micBeside)).isEqualTo(besideSend)
        setState(VoiceState.Transcribing)
        assertThat(assertPair()).isEqualTo(idle)
        assertThat(disc(micBeside)).isEqualTo(besideSend)
        setState(VoiceState.Idle)

        compose.runOnIdle { text.value = ""; running.value = true }
        compose.waitForIdle()
        assertThat(assertPair()).isEqualTo(idle) // Stop, the mic beside it
        assertThat(disc(micBeside)).isEqualTo(besideSend)
    }

    @Test
    fun `a tap just right of the microphone records rather than sending`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        text.value = "Fix the login redirect"
        show()
        val mic = disc(micBeside)
        val x = mic.right + 5f * density
        compose.onRoot().performTouchInput { click(Offset(x, mic.center.y)) }
        compose.waitUntil(5_000) { capture.started }
        assertThat(sent).isEmpty()
    }

    private val cancel = hasTestTag("voice-cancel")

    /** Recording, the status's cancel is a round button too: the same disc, clear of the mic's touch target. */
    private fun assertCancelClear() {
        val next = if (micBesideShown()) micBeside else mainSlot
        val x = disc(cancel)
        assertThat(x.width).isWithin(0.5f).of(disc(next).width)
        assertThat(x.center.y).isWithin(0.5f).of(disc(next).center.y)
        assertThat(dp(disc(next).left - x.right)).isWithin(0.5f).of(CursorDimens.roundButtonGap.value)
        assertThat(touch(cancel).width).isWithin(0.5f).of(touch(next).width)
        assertThat(touch(cancel).right).isAtMost(touch(next).left + 0.5f)
    }

    @Test
    fun `recording, the cancel beside the microphone keeps its own disc and touch target`() {
        show()
        setState(VoiceState.Recording(0L))
        assertCancelClear()
        setState(VoiceState.Idle)
        compose.runOnIdle { text.value = "Fix the login redirect" }
        compose.waitForIdle()
        setState(VoiceState.Recording(0L))
        assertCancelClear()
    }

    @Test
    fun `recording, a tap just right of cancel cancels rather than transcribing`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        compose.onNode(mainSlot).performTouchInput { click(center) }
        compose.waitUntil(5_000) { voice.state is VoiceState.Recording }
        val x = disc(cancel)
        compose.onRoot().performTouchInput { click(Offset(x.right + 5f * density, x.center.y)) }
        compose.waitUntil(5_000) { voice.state == VoiceState.Idle }
        assertThat(capture.cancelled).isTrue()
        assertThat(capture.stopped).isFalse()
    }

    /** The window as laid out, drawn straight from the views: there is no frame for `captureToImage` to wait on here. */
    private fun window(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** The glyph's ink within its disc, relative to the disc: where the pixels part from the disc's own fill. */
    private fun ink(matcher: SemanticsMatcher): Rect {
        val disc = node(matcher).fetchSemanticsNode().boundsInWindow
        val pixels = window()
        val cx = disc.center.x
        val cy = disc.center.y
        val fill = pixels.getPixel(cx.toInt(), (disc.top + 2 * density).toInt())
        val radius = disc.width / 2f - 1.5f * density
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -1f; var bottom = -1f
        for (y in disc.top.toInt()..disc.bottom.toInt()) for (x in disc.left.toInt()..disc.right.toInt()) {
            if ((x + 0.5f - cx) * (x + 0.5f - cx) + (y + 0.5f - cy) * (y + 0.5f - cy) > radius * radius) continue
            val p = pixels.getPixel(x, y)
            val d = maxOf(
                abs(AndroidColor.red(p) - AndroidColor.red(fill)),
                abs(AndroidColor.green(p) - AndroidColor.green(fill)),
                abs(AndroidColor.blue(p) - AndroidColor.blue(fill)),
            )
            if (d > 50) {
                left = minOf(left, x.toFloat()); right = maxOf(right, x + 1f); top = minOf(top, y.toFloat()); bottom = maxOf(bottom, y + 1f)
            }
        }
        assertThat(right).isGreaterThan(0f)
        return Rect(left - disc.left, top - disc.top, right - disc.left, bottom - disc.top)
    }

    @Test
    fun `the microphone glyph is optically the arrow's size and centred in its disc`() {
        text.value = "Fix the login redirect"
        show()
        val mic = ink(micBeside)
        val arrow = ink(mainSlot)
        val discSize = disc(micBeside).width
        // A tall narrow mark carries less ink than the arrow's square, so it may stand a little taller; never more.
        assertThat(dp(mic.height)).isAtMost(dp(arrow.height) + 1.5f)
        assertThat(dp(mic.width)).isAtMost(dp(arrow.width))
        assertThat(dp(abs(mic.center.x - discSize / 2))).isAtMost(0.75f)
        assertThat(dp(abs(mic.center.y - discSize / 2))).isAtMost(0.75f)
        assertThat(dp(abs(arrow.center.y - discSize / 2))).isAtMost(0.75f)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PhoneComposerButtonSizeTest : ComposerButtonSizeTest()

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-night-320dpi")
class TabletComposerButtonSizeTest : ComposerButtonSizeTest()
