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
import androidx.compose.ui.test.hasText
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
 * The composer footer's right-hand group measured as rendered. The bare microphone beside Send or Stop sits evenly
 * between the model chip's chevron and the main disc — its ink as far from each as the chevron's was from the disc
 * before there was a mic — with no disc of its own behind it. The chip's, the mic's and the main button's touch areas
 * meet without overlapping, each at least 40dp, so a tap near the mic never sends; and no state of the send slot or of
 * a dictation moves anything. Run on a phone and on a tablet ([PhoneComposerButtonSizeTest],
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
    private fun Host(value: MutableState<String>, running: MutableState<Boolean>, voiceOn: MutableState<Boolean>) {
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
                    modelLabel = Model,
                    onModel = {},
                    voice = voice.takeIf { voiceOn.value },
                )
            }
        }
    }

    private val text = mutableStateOf("")
    private val running = mutableStateOf(false)
    private val voiceOn = mutableStateOf(true)

    private fun show() {
        compose.setContent { Host(text, running, voiceOn) }
        compose.waitForIdle()
    }

    private fun change(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun dp(px: Float) = px / density

    private val mainSlot = hasTestTag("composer-main")
    private val mic = hasTestTag("voice-mic")
    private val chip = hasClickAction() and hasText(Model)
    private val cancel = hasTestTag("voice-cancel")

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onNode(matcher, useUnmergedTree = true)

    private fun bounds(matcher: SemanticsMatcher): Rect = node(matcher).fetchSemanticsNode().boundsInRoot

    /** The layer that takes the tap: for the round buttons, the 40dp box their disc sits in. */
    private fun touch(matcher: SemanticsMatcher): Rect =
        node(hasClickAction() and (hasAnyAncestor(matcher) or matcher)).fetchSemanticsNode().boundsInRoot

    /** The model chip's own tap area: its words merge into it, so it is found in the merged tree. */
    private fun chipBounds(): Rect = compose.onNode(chip).fetchSemanticsNode().boundsInRoot

    private fun micShown() = compose.onAllNodes(mic, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun setState(state: VoiceState, levels: List<Float> = emptyList()) {
        compose.runOnIdle { voice.show(state, levels, elapsedMillis = 3_000L) }
        compose.waitForIdle()
    }

    /** The window as laid out, drawn straight from the views: there is no frame for `captureToImage` to wait on here. */
    private fun window(): Bitmap {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun distance(a: Int, b: Int) = maxOf(
        abs(AndroidColor.red(a) - AndroidColor.red(b)),
        abs(AndroidColor.green(a) - AndroidColor.green(b)),
        abs(AndroidColor.blue(a) - AndroidColor.blue(b)),
    )

    /** The footer as drawn: the rows the main disc spans, and the composer's own fill beside it. */
    private inner class Footer {
        val pixels = window()
        private val main = node(mainSlot).fetchSemanticsNode().boundsInWindow
        private val offset = main.topLeft - bounds(mainSlot).topLeft
        val fill: Int = pixels.getPixel((main.left + 0.5f * density).toInt(), (main.top + 0.5f * density).toInt())

        /** Where something is drawn between [from] and [to] (root coordinates, left to right): its first and last ink columns. */
        fun ink(from: Float, to: Float): ClosedFloatingPointRange<Float> {
            var left = Float.MAX_VALUE
            var right = -1f
            for (x in (from + offset.x).toInt() until (to + offset.x).toInt()) for (y in main.top.toInt() until main.bottom.toInt()) {
                if (distance(pixels.getPixel(x, y), fill) > InkThreshold) {
                    left = minOf(left, x.toFloat())
                    right = maxOf(right, x + 1f)
                }
            }
            assertThat(right).isGreaterThan(0f)
            return (left - offset.x)..(right - offset.x)
        }

        fun at(x: Float, y: Float): Int = pixels.getPixel((x + offset.x).toInt(), (y + offset.y).toInt())
    }

    /** Chevron ink to the main disc's with no microphone in the row: what the mic's two gaps must each equal. */
    private fun gapWithoutMic(): Float {
        change { voiceOn.value = false }
        assertThat(micShown()).isFalse()
        val footer = Footer()
        val main = bounds(mainSlot)
        val chevron = footer.ink(chipBounds().left, chipBounds().right)
        val disc = footer.ink(chipBounds().right, main.right)
        change { voiceOn.value = true }
        return disc.start - chevron.endInclusive
    }

    /** The mic's ink beside the main button, and its two gaps: to the chevron's ink on its left, to the disc's on its right. */
    private fun micGaps(): Triple<ClosedFloatingPointRange<Float>, Float, Float> {
        val footer = Footer()
        val m = bounds(mic)
        val chevron = footer.ink(chipBounds().left, chipBounds().right)
        val glyph = footer.ink(m.left, m.right)
        val disc = footer.ink(m.right, bounds(mainSlot).right)
        return Triple(glyph, glyph.start - chevron.endInclusive, disc.start - glyph.endInclusive)
    }

    private fun assertEvenlySpaced(without: Float) {
        assertThat(dp(without)).isWithin(1f).of(FooterSpacing.ModelToMain.value)
        val (_, left, right) = micGaps()
        assertThat(dp(left)).isWithin(1f).of(dp(without))
        assertThat(dp(right)).isWithin(1f).of(dp(without))
        assertThat(dp(abs(left - right))).isAtMost(1f)
    }

    /** Chip, mic and main button: touch areas that meet without overlapping, the mic's and main's 40dp square, one centre line. */
    private fun assertTouchesTile() {
        val c = chipBounds()
        val m = touch(mic)
        val main = touch(mainSlot)
        assertThat(dp(m.width)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(dp(m.height)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(dp(main.width)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(dp(main.height)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(c.right).isAtMost(m.left + 0.5f)
        assertThat(m.right).isAtMost(main.left + 0.5f)
        assertThat(m.center.y).isWithin(0.5f).of(main.center.y)
        // The main button still takes every tap on its own disc.
        assertThat(main.left).isAtMost(bounds(mainSlot).left + 0.5f)
    }

    /** Around the mic's glyph, where the old 8 % disc was, only the composer's own fill. */
    private fun assertNoDiscBehindMic() {
        val footer = Footer()
        val (glyph, _, _) = micGaps()
        val cy = bounds(mainSlot).center.y
        val r = CursorDimens.roundButton.value * density / 2
        val cx = (glyph.start + glyph.endInclusive) / 2
        for (probe in listOf(Offset(glyph.start - 1.5f * density, cy), Offset(glyph.endInclusive + 1.5f * density, cy), Offset(cx, cy - r + 1.5f * density), Offset(cx, cy + r - 1.5f * density))) {
            assertThat(distance(footer.at(probe.x, probe.y), footer.fill)).isAtMost(3)
        }
    }

    @Test
    fun `beside Send the bare mic sits evenly between the chevron and Send, each gap the chevron's to Send before the mic`() {
        text.value = "Fix the login redirect"
        show()
        val without = gapWithoutMic()
        assertEvenlySpaced(without)
        assertTouchesTile()
        assertNoDiscBehindMic()
    }

    @Test
    fun `beside Stop the bare mic is spaced and touchable the same way`() {
        running.value = true
        show()
        val without = gapWithoutMic()
        assertEvenlySpaced(without)
        assertTouchesTile()
        assertNoDiscBehindMic()
    }

    @Test
    fun `no state of the send slot or of a dictation moves the main disc or the mic`() {
        show()
        val idle = bounds(mainSlot) // the main button is the mic
        assertThat(dp(idle.width)).isWithin(0.5f).of(CursorDimens.roundButton.value)
        assertThat(dp(idle.height)).isWithin(0.5f).of(CursorDimens.roundButton.value)
        setState(VoiceState.Recording(0L), levels = List(48) { 1f })
        assertThat(bounds(mainSlot)).isEqualTo(idle)
        setState(VoiceState.Transcribing)
        assertThat(bounds(mainSlot)).isEqualTo(idle)
        setState(VoiceState.Idle)

        change { text.value = "Fix the login redirect" }
        assertThat(bounds(mainSlot)).isEqualTo(idle) // Send, the mic beside it
        val besideSend = bounds(mic)
        setState(VoiceState.Recording(0L), levels = List(48) { 1f })
        assertThat(bounds(mainSlot)).isEqualTo(idle)
        assertThat(bounds(mic)).isEqualTo(besideSend)
        setState(VoiceState.Transcribing)
        assertThat(bounds(mainSlot)).isEqualTo(idle)
        assertThat(bounds(mic)).isEqualTo(besideSend)
        setState(VoiceState.Idle)

        change { text.value = ""; running.value = true }
        assertThat(bounds(mainSlot)).isEqualTo(idle) // Stop, the mic beside it
        assertThat(bounds(mic)).isEqualTo(besideSend)
    }

    @Test
    fun `empty and idle the main button is still the white mic disc`() {
        show()
        val main = bounds(mainSlot)
        val footer = Footer()
        // Between the disc's edge and its glyph: the prominent fill, near white on Cursor Dark.
        val p = footer.at(main.left + 2.5f * density, main.center.y)
        assertThat(minOf(AndroidColor.red(p), AndroidColor.green(p), AndroidColor.blue(p))).isAtLeast(200)
        assertThat(dp(touch(mainSlot).width)).isWithin(0.5f).of(CursorDimens.roundButtonTouch.value)
    }

    @Test
    fun `a tap just left of the Send disc records rather than sending`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        text.value = "Fix the login redirect"
        show()
        val send = bounds(mainSlot)
        compose.onRoot().performTouchInput { click(Offset(send.left - 1f * density, send.center.y)) }
        compose.waitUntil(5_000) { capture.started }
        assertThat(sent).isEmpty()
    }

    @Test
    fun `beside the mic, Send takes a tap past its disc's right edge, out over the composer's rounded corner`() {
        text.value = "Fix the login redirect"
        show()
        val send = bounds(mainSlot)
        compose.onRoot().performTouchInput { click(Offset(send.right + 14f * density, send.center.y)) }
        compose.waitUntil(5_000) { sent.isNotEmpty() }
        assertThat(capture.started).isFalse()
    }

    @Test
    fun `a tap just right of the chevron records rather than opening the model picker`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        text.value = "Fix the login redirect"
        show()
        val c = chipBounds()
        compose.onRoot().performTouchInput { click(Offset(c.right + 1f * density, bounds(mainSlot).center.y)) }
        compose.waitUntil(5_000) { capture.started }
    }

    @Test
    fun `the bare mic glyph is optically no larger than the arrow`() {
        text.value = "Fix the login redirect"
        show()
        val footer = Footer()
        val (glyph, _, _) = micGaps()
        val arrow = arrowInk(footer)
        // A tall narrow mark carries less ink than the arrow's square, so it may stand a little taller; never wider.
        assertThat(dp(glyph.endInclusive - glyph.start)).isAtMost(dp(arrow.width) + 0.5f)
    }

    /** The arrow's ink inside the Send disc: where the pixels part from the disc's own fill. */
    private fun arrowInk(footer: Footer): Rect {
        val disc = bounds(mainSlot)
        val fill = footer.at(disc.center.x, disc.top + 2 * density)
        val radius = disc.width / 2f - 1.5f * density
        var left = Float.MAX_VALUE; var right = -1f
        var y = disc.top
        while (y < disc.bottom) {
            var x = disc.left
            while (x < disc.right) {
                val inside = (x - disc.center.x) * (x - disc.center.x) + (y - disc.center.y) * (y - disc.center.y) <= radius * radius
                if (inside && distance(footer.at(x, y), fill) > 50) { left = minOf(left, x); right = maxOf(right, x + 1f) }
                x += 1f
            }
            y += 1f
        }
        assertThat(right).isGreaterThan(0f)
        return Rect(left, disc.top, right, disc.bottom)
    }

    @Test
    fun `recording with an empty composer, cancel is a disc of its own clear of the main mic's touches`() {
        show()
        setState(VoiceState.Recording(0L))
        assertThat(micShown()).isFalse()
        val x = bounds(cancel)
        val main = bounds(mainSlot)
        assertThat(x.width).isWithin(0.5f).of(main.width)
        assertThat(x.center.y).isWithin(0.5f).of(main.center.y)
        assertThat(dp(main.left - x.right)).isWithin(0.5f).of(CursorDimens.roundButtonGap.value)
        assertThat(touch(cancel).right).isAtMost(touch(mainSlot).left + 0.5f)
    }

    @Test
    fun `recording beside Send, cancel sits the chevron's distance from the mic, and the three touch areas tile`() {
        text.value = "Fix the login redirect"
        show()
        val (glyph, gap, _) = micGaps()
        setState(VoiceState.Recording(0L))
        val x = bounds(cancel)
        // Measured from the mic's glyph as it sits idle: recording rings it in red, but it does not move.
        assertThat(dp(glyph.start - x.right)).isWithin(1f).of(dp(gap))
        assertThat(dp(touch(cancel).width)).isAtLeast(CursorDimens.roundButtonTouch.value - 0.5f)
        assertThat(touch(cancel).right).isAtMost(touch(mic).left + 0.5f)
        assertThat(touch(mic).right).isAtMost(touch(mainSlot).left + 0.5f)
    }

    @Test
    fun `recording, a tap just right of cancel cancels rather than transcribing`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show()
        compose.onNode(mainSlot).performTouchInput { click(center) }
        compose.waitUntil(5_000) { voice.state is VoiceState.Recording }
        val x = bounds(cancel)
        compose.onRoot().performTouchInput { click(Offset(x.right + 5f * density, x.center.y)) }
        compose.waitUntil(5_000) { voice.state == VoiceState.Idle }
        assertThat(capture.cancelled).isTrue()
        assertThat(capture.stopped).isFalse()
    }

    @Test
    fun `recording beside Send, a tap just right of cancel cancels rather than stopping the mic`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        text.value = "Fix the login redirect"
        show()
        compose.onNode(mic, useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitUntil(5_000) { voice.state is VoiceState.Recording }
        val x = bounds(cancel)
        compose.onRoot().performTouchInput { click(Offset(x.right + 3f * density, x.center.y)) }
        compose.waitUntil(5_000) { voice.state == VoiceState.Idle }
        assertThat(capture.cancelled).isTrue()
        assertThat(capture.stopped).isFalse()
        assertThat(sent).isEmpty()
    }

    private companion object {
        const val Model = "Claude Fable 5.1"
        /** Past the composer fill's own noise, and low enough to take a glyph's anti-aliased edge as ink. */
        const val InkThreshold = 24
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
