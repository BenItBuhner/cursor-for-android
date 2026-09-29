package com.cursorforandroid.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scale guard for the indicators in a Project with many running workers. Sixty real [RunningGlyph]s are fed through
 * a lazy list while the test clock advances one frame at a time. The stable claims are structural: animation does not
 * recompose an idle row, and one shared frame-clock continuation serves every composed glyph.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SharedAnimationTickerBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private object MainThread {
        private val bean: Any? = runCatching {
            Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        }.getOrNull()
        private val cpu = runCatching {
            Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
        }.getOrNull()
        private val alloc = runCatching {
            Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        }.getOrNull()

        fun cpuNanos(): Long = runCatching { cpu?.invoke(bean) as? Long }.getOrNull() ?: 0L
        fun allocatedBytes(): Long = runCatching { alloc?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    private data class Cost(
        val frames: Int,
        val scopes: Int,
        val callbacks: Int,
        val cpuNanos: Long,
        val allocatedBytes: Long,
    ) {
        override fun toString(): String =
            "frames=$frames recompositions=$scopes callbacks=$callbacks callbacks/frame=${"%.2f".format(callbacks.toDouble() / frames)} " +
                "cpu/frame=${"%.3f".format(cpuNanos / frames / 1e6)}ms alloc/frame=${allocatedBytes / frames}B"
    }

    private val callbacks = AtomicInteger()

    @After
    fun tearDown() {
        SharedAnimationTickerTestHooks.onFrame = null
        RecomposeCounter.uninstall()
    }

    private fun measure(frames: Int, beforeFrame: ((Int) -> Unit)? = null): Cost {
        val scopes = RecomposeCounter.count("RunningGlyph")
        val callbackCount = callbacks.get()
        val cpu = MainThread.cpuNanos()
        val allocated = MainThread.allocatedBytes()
        repeat(frames) { frame ->
            beforeFrame?.invoke(frame)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        return Cost(
            frames = frames,
            scopes = RecomposeCounter.count("RunningGlyph") - scopes,
            callbacks = callbacks.get() - callbackCount,
            cpuNanos = MainThread.cpuNanos() - cpu,
            allocatedBytes = MainThread.allocatedBytes() - allocated,
        )
    }

    @Test
    fun `sixty running rows share one draw-only animation ticker`() {
        SharedAnimationTickerTestHooks.onFrame = { callbacks.incrementAndGet() }
        RecomposeCounter.install()
        compose.mainClock.autoAdvance = false
        val shown = mutableStateOf(true)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                if (shown.value) {
                    LazyColumn(Modifier.fillMaxSize().testTag(LIST)) {
                        items((0 until ROWS).toList(), key = { it }) {
                            Box(Modifier.height(ROW_HEIGHT)) {
                                RunningGlyph()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        measure(10)

        val idle = measure(IDLE_FRAMES)
        val list = compose.onNodeWithTag(LIST)
        list.performTouchInput {
            down(Offset(centerX, centerY + 250f))
            moveBy(Offset(0f, -(viewConfiguration.touchSlop + 1f)))
        }
        val scrolling = measure(SCROLL_FRAMES) {
            list.performTouchInput { moveBy(Offset(0f, -8f)) }
        }
        list.performTouchInput {
            advanceEventTime(200)
            up()
        }
        shown.value = false
        compose.waitForIdle()
        measure(1) // let the frame already requested by the last reader return and observe the detach
        val detached = measure(10)

        println("SCALE glyphs rows=$ROWS idle {$idle} scrolling {$scrolling} detached {$detached}")
        assertWithMessage("idle $idle").that(idle.scopes).isEqualTo(0)
        assertWithMessage("idle $idle").that(idle.callbacks).isAtMost(IDLE_FRAMES + 1)
        assertWithMessage("scrolling $scrolling").that(scrolling.callbacks).isAtMost(SCROLL_FRAMES * 3)
        assertWithMessage("detached $detached").that(detached.callbacks).isEqualTo(0)
    }

    private companion object {
        const val ROWS = 60
        val ROW_HEIGHT = 20.dp
        const val IDLE_FRAMES = 125 // two seconds at the test clock's 16 ms frames
        const val SCROLL_FRAMES = 60
        const val LIST = "scale-running-rows"
    }
}
