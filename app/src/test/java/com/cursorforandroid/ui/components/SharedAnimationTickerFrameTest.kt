package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Fixed-clock visual evidence that [RunningGlyph]'s eight 175 ms steps keep their exact geometry. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w160dp-h96dp-night-160dpi")
class SharedAnimationTickerFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        SharedAnimationTickerTestHooks.onRunningChanged = null
    }

    @Test
    fun `active ticker does not keep an auto advancing test clock busy`() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        SharedAnimationTickerTestHooks.onRunningChanged = { running ->
            if (running) starts.incrementAndGet() else stops.incrementAndGet()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    repeat(60) {
                        RunningGlyph(size = 1.dp)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { starts.get() > 0 && starts.get() == stops.get() }

        assertThat(compose.activity.window.decorView.isAttachedToWindow).isTrue()
    }

    @Test
    fun `standalone spinner keeps its local ticker fallback`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SpinnerRing(size = 32.dp)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertThat(compose.activity.window.decorView.isAttachedToWindow).isTrue()
    }

    @Test
    fun `all eight fixed-clock glyph frames render to a strip`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(
                    Modifier.fillMaxSize().background(CursorTheme.colors.canvas),
                    contentAlignment = Alignment.Center,
                ) {
                    RunningGlyph(size = 32.dp, color = CursorTheme.colors.textPrimary)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val frames = ArrayList<Bitmap>(FRAME_COUNT)
        val hashes = ArrayList<Int>(FRAME_COUNT)
        repeat(FRAME_COUNT) { index ->
            if (index > 0) {
                compose.mainClock.advanceTimeBy(STEP_MS)
                compose.waitForIdle()
            }
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            frames += bitmap
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            hashes += pixels.contentHashCode()
        }

        assertThat(hashes.toSet()).hasSize(FRAME_COUNT)
        val strip = Bitmap.createBitmap(frames.first().width * FRAME_COUNT, frames.first().height, Bitmap.Config.ARGB_8888)
        val stripCanvas = Canvas(strip)
        frames.forEachIndexed { index, bitmap -> stripCanvas.drawBitmap(bitmap, (index * bitmap.width).toFloat(), 0f, null) }
        val output = System.getenv(OUTPUT_ENV)?.let(::File)
            ?: File(System.getProperty("user.dir"), "build/outputs/scale/shared-animation-glyph-strip.png")
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SCALE glyph frames=${hashes.joinToString(",")} strip=${output.absolutePath}")
    }

    private companion object {
        const val FRAME_COUNT = 8
        const val STEP_MS = 175L
        const val OUTPUT_ENV = "SCALE_ANIMATION_STRIP"
    }
}
