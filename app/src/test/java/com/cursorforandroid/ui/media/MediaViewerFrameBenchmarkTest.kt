package com.cursorforandroid.ui.media

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.theme.CursorTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What one frame of the viewer costs the UI thread, over a transcript of real rows — the app's own
 * [TimelineItemView] over markdown replies with figures — under it: the open transform out of a thumbnail, the
 * close back into it, a pinch, and the swipe from one page to the next, stepped frame by frame on the test clock
 * (which ticks at 16 ms; a phone at 120 Hz has 8.33 ms for each) and each frame timed from the clock moving to
 * the frame being composed, laid out and recorded.
 *
 * Robolectric runs composition, measure, layout and draw recording on the JVM and nothing on a GPU, so these are
 * UI-thread costs only and the absolute numbers are the JVM's, not a phone's. What the test asserts is what a
 * phone would feel: no row of the transcript recomposes while the viewer moves — the transform is a layer drawn
 * over it, not a re-layout of it — and the frames of the transform are cheap and even, the median inside a
 * 120 Hz frame's budget even here. The numbers are printed for the report.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class MediaViewerFrameBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Frames(val label: String, val millis: List<Double>) {
        private val sorted = millis.sorted()
        val median get() = sorted[sorted.size / 2]
        val p90 get() = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        val max get() = sorted.last()
        override fun toString() = "$label: n=${millis.size} median=${"%.2f".format(median)}ms p90=${"%.2f".format(p90)}ms max=${"%.2f".format(max)}ms"
    }

    /** How many times any transcript row's body ran; the viewer must leave this alone while it moves. */
    private var rowCompositions = 0

    private val state = MediaViewerState(null)

    private fun transcript(first: String, second: String, third: String): List<TimelineItem> = buildList {
        repeat(4) { turn ->
            add(UserMessage("u$turn", "Turn $turn: show me the screens and the recording of the drawer, and keep the gallery smooth at 120 Hz."))
            add(
                AssistantMessage(
                    "a$turn",
                    """
                    ## Turn $turn

                    The viewer is a layer of the window now, so a figure grows out of its thumbnail rather than jumping
                    into a dialog. Three figures from this turn, then the notes:

                    ![First figure $turn]($first)

                    - Pinch and double tap zoom; the pan hands the swipe to the pager only at the edge.
                    - Back and a downward drag scrub the same transform the open played.

                    ![Second figure $turn]($second)

                    ```kotlin
                    fun Modifier.thumbnailSlot(slot: ThumbnailSlot): Modifier
                    ```

                    ![Third figure $turn]($third)
                    """.trimIndent(),
                ),
            )
        }
    }

    @Composable
    private fun Row(item: TimelineItem) {
        SideEffect { rowCompositions++ }
        TimelineItemView(item, Modifier.fillMaxWidth())
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun settle(condition: () -> Boolean) = compose.waitUntil(60_000) {
        compose.waitForIdle()
        condition()
    }

    /** Frames a JVM garbage collection fell into, by series and index: a pause of the JVM's, not a cost of the frame's. */
    private val gcFrames = mutableListOf<String>()
    private var series = ""
    private var frameIndex = 0

    /** The heap in use; a frame across which it drops by megabytes had a collection in it (the management beans are not on Android's classpath). */
    private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** One frame of the test clock: it moves, and whatever it moves is composed, measured, laid out and recorded. */
    private fun frame(): Double = timed { compose.mainClock.advanceTimeByFrame() }

    private fun frames(label: String, count: Int): Frames = Frames(label, List(count) { frame() })

    /** The UI-thread cost of [block] and the frame it causes. */
    private fun timed(block: () -> Unit): Double {
        val heapBefore = heapUsed()
        val started = System.nanoTime()
        block()
        compose.waitForIdle()
        val millis = (System.nanoTime() - started) / 1_000_000.0
        if (heapUsed() < heapBefore - GcDropBytes) gcFrames += "$series#$frameIndex (${"%.2f".format(millis)}ms)"
        frameIndex++
        return millis
    }

    private fun <T> series(name: String, block: () -> T): T {
        series = name
        frameIndex = 0
        return block()
    }

    @Test
    fun `the viewer's frames over a real transcript`() {
        val first = ViewerFixtures.png("bench-first.png", 1080, 1920, 0xFF1E2A3A.toInt())
        val second = ViewerFixtures.png("bench-second.png", 1600, 900, 0xFF3A1E2A.toInt())
        val third = ViewerFixtures.png("bench-third.png", 900, 900, 0xFF2A3A1E.toInt())
        val items = transcript(first, second, third)
        val entries = ConversationMedia.of(items)
        val loader = ViewerFixtures.loader()
        compose.setContent {
            ViewerScene(state, loader, entries) {
                Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.forEach { Row(it) }
                }
            }
        }
        settle { compose.onAllNodes(hasContentDescription("First figure 0")).fetchSemanticsNodes().size == 1 && compose.onAllNodes(hasContentDescription("Second figure 0")).fetchSemanticsNodes().size == 1 }
        // A warm-up pass of the whole loop, thrown away, so the JIT's first sight of the code is not in the numbers.
        measure(warmUp = true)
        gcFrames.clear()
        val result = measure(warmUp = false)

        println("BENCHMARK media-viewer test-clock frames of ${ClockFrameMillis}ms, budget ${"%.2f".format(Budget120HzMillis)}ms (120 Hz); rows=${items.size} figures=${entries.size} (Robolectric JVM, UI-thread cost only)")
        for (frames in result.series) {
            println("BENCHMARK $frames")
            println("BENCHMARK   frames: ${frames.millis.joinToString(" ") { "%.2f".format(it) }}")
        }
        println("BENCHMARK transcript row compositions during open+close transforms: ${result.rowCompositionsDuringTransforms}")
        println("BENCHMARK frames with a JVM garbage collection in them: ${if (gcFrames.isEmpty()) "none" else gcFrames.joinToString()}")

        // The transform is drawn over the transcript, not through it: not one row recomposes while it runs.
        assertThat(result.rowCompositionsDuringTransforms).isEqualTo(0)
        // Cheap and even: the transform's median frame fits inside a 120 Hz frame even on the JVM.
        assertThat(result.open.median).isLessThan(Budget120HzMillis)
        assertThat(result.close.median).isLessThan(Budget120HzMillis)
    }

    private class Result(val open: Frames, val close: Frames, val pinch: Frames, val swipe: Frames, val rowCompositionsDuringTransforms: Int) {
        val series get() = listOf(open, close, pinch, swipe)
    }

    private fun measure(warmUp: Boolean): Result {
        val label = if (warmUp) "warm-up " else ""
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val rowsBefore = rowCompositions

        // Open: out of the first figure of the first reply.
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasContentDescription("First figure 0"))[0].performClick()
        compose.mainClock.advanceTimeByFrame()
        val open = series("open") { frames("${label}open transform", TransformFrames) }
        compose.mainClock.autoAdvance = true
        settle { state.phase == MediaViewerState.Phase.Open && exists("viewer-image-0") }
        val rowsAfterOpen = rowCompositions

        // Pinch: two fingers spreading from 200 px apart to 600 over 24 frames, each frame timed as the fingers move.
        // (The injection moves the clock by the frame's length itself.)
        compose.mainClock.autoAdvance = false
        val page = compose.onNodeWithTag("viewer-page-0")
        page.performTouchInput {
            down(0, center - Offset(100f, 0f))
            down(1, center + Offset(100f, 0f))
        }
        val pinch = series("pinch") { Frames(
            "${label}pinch zoom (fingers moving)",
            List(24) {
                timed {
                    page.performTouchInput {
                        updatePointerBy(0, Offset(-200f / 24f, 0f))
                        updatePointerBy(1, Offset(200f / 24f, 0f))
                        move(ClockFrameMillis)
                    }
                }
            },
        ) }
        page.performTouchInput {
            up(0)
            up(1)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // Back to the fit for the swipe: the pager only takes it at the edge of a zoomed picture.
        page.performTouchInput { doubleClick() }
        compose.waitForIdle()
        settle { state.zoomScale == 1f }

        // Swipe: a finger dragging the page 800 px left over 16 frames, then the fling settling on the next page.
        compose.mainClock.autoAdvance = false
        val pager = compose.onNodeWithTag("viewer-pager")
        pager.performTouchInput { down(center) }
        val dragging = series("swipe") { List(16) { timed { pager.performTouchInput { moveBy(Offset(-50f, 0f), ClockFrameMillis) } } } }
        pager.performTouchInput { up() }
        val settling = series("fling") { List(24) { frame() } }
        val swipe = Frames("${label}page swipe (16 drag frames, then the fling)", dragging + settling)
        compose.mainClock.autoAdvance = true
        settle { state.currentIndex == 1 }

        // Close: back into the second figure's thumbnail.
        val rowsBeforeClose = rowCompositions
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("viewer-close").performClick()
        compose.mainClock.advanceTimeByFrame()
        val close = series("close") { frames("${label}close transform", TransformFrames) }
        compose.mainClock.autoAdvance = true
        settle { !state.isOpen }
        val rowsAfterClose = rowCompositions

        return Result(open, close, pinch, swipe, (rowsAfterOpen - rowsBefore) + (rowsAfterClose - rowsBeforeClose))
    }

    private companion object {
        /** The test clock's frame; `advanceTimeBy` rounds up to it anyway. */
        const val ClockFrameMillis = 16L
        /** What one frame may cost a 120 Hz phone. */
        const val Budget120HzMillis = 1000.0 / 120.0
        const val GcDropBytes = 4L * 1024 * 1024
        /** The 300 ms transform in 16 ms frames, and a few frames of what follows its landing. */
        const val TransformFrames = 22
    }
}
