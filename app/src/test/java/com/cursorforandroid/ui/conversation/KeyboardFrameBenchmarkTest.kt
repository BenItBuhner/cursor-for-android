package com.cursorforandroid.ui.conversation

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.keyboardInsetPadding
import com.cursorforandroid.ui.components.scrollEdgeFade
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What one frame of the keyboard's animation costs the UI thread on a transcript of real rows — the app's own
 * [TimelineItemView] over markdown replies — with the list arranged as the chat arranges it (bottom-anchored, under
 * a docked composer), before and after this branch's list changes: the default prefetch and offscreen dissolve
 * against [TranscriptPrefetchStrategy] and the painted fade. The keyboard's inset is stepped through the values of
 * a show and a hide, and each step is timed from the inset landing to the frame being laid out and recorded.
 *
 * Robolectric runs composition, measure, layout and draw recording on the JVM and nothing on a GPU, so these are
 * UI-thread costs only — the offscreen layer's allocation and re-render per frame is not in them — and the absolute
 * numbers are the JVM's, not a phone's. Nor does the lazy layout's prefetcher get to run here: it measures the time
 * left in a frame against the real clock while Robolectric's frames run on a virtual one, so the row the hide
 * uncovers is composed on its frame in both arrangements and shows as the same spike in both series
 * (TranscriptPrefetchStrategyTest checks what is asked for instead). The shape is what the test asserts: every frame
 * lays out, and the hide is not worse with the rows prefetched. The numbers are printed for the report.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class KeyboardFrameBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Frames(val label: String, val millis: List<Double>) {
        private val sorted = millis.sorted()
        val median get() = sorted[sorted.size / 2]
        val p90 get() = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)]
        val max get() = sorted.last()
        val total get() = millis.sum()
        override fun toString() = "$label: n=${millis.size} median=${"%.1f".format(median)}ms p90=${"%.1f".format(p90)}ms max=${"%.1f".format(max)}ms total=${"%.0f".format(total)}ms"
    }

    @Composable
    private fun Transcript(items: List<TimelineItem>, prefetch: Boolean, paintedFade: Boolean) {
        val colors = CursorTheme.colors
        val state = if (prefetch) rememberLazyListState(prefetchStrategy = remember { TranscriptPrefetchStrategy() }) else rememberLazyListState()
        Column(Modifier.fillMaxSize().background(colors.canvas)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = state,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .scrollEdgeFade(state, reverseLayout = true, surface = if (paintedFade) colors.canvas else Color.Unspecified),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(items.asReversed(), key = { it.id }, contentType = { it::class }) { item -> TimelineItemView(item, Modifier.fillMaxWidth()) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).keyboardInsetPadding().testTag("composer")) {
                Box(Modifier.fillMaxWidth().height(96.dp).background(colors.elevated))
            }
        }
    }

    private fun dispatchInsets(ime: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NavigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()
        val target = composeView()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content)))
    }

    /** One frame: the inset lands, and the frame it causes is composed, measured, laid out and recorded. */
    private fun frame(ime: Int): Double {
        val started = System.nanoTime()
        dispatchInsets(ime)
        compose.waitForIdle()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun showFrames(): List<Int> = (1..18).map { step ->
        val t = step / 18f
        (Keyboard * (1f - (1f - t) * (1f - t))).toInt()
    }

    /** Which arrangement is on screen; changing it builds the transcript afresh, so nothing is carried over. */
    private var variant by mutableStateOf<Pair<Boolean, Boolean>?>(null)

    /** A show then a hide, each frame timed; the first pass of each variant is a warm-up and is thrown away. */
    private fun measure(prefetch: Boolean, paintedFade: Boolean): Pair<Frames, Frames> {
        variant = prefetch to paintedFade
        compose.waitForIdle()
        var show = emptyList<Double>()
        var hide = emptyList<Double>()
        repeat(2) {
            frame(0)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            show = showFrames().map(::frame)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            hide = showFrames().asReversed().drop(1).map(::frame) + frame(0)
        }
        val label = if (prefetch) "after (prefetch + painted fade)" else "before (default prefetch + offscreen dissolve)"
        return Frames("$label show", show) to Frames("$label hide", hide)
    }

    private fun transcript(turns: Int): List<TimelineItem> = buildList {
        repeat(turns) { turn ->
            add(UserMessage("u$turn", "Turn $turn: tighten the composer's inset handling and make the transcript follow the keyboard without dropping frames."))
            add(
                AssistantMessage(
                    "a$turn",
                    """
                    ## Turn $turn

                    The composer now rests on `WindowInsets.navigationBars.union(WindowInsets.ime)`, so it sits on whichever is
                    higher on every frame the keyboard moves. A few things follow from that:

                    - The transcript is bottom-anchored, so its newest turn keeps its place against the composer.
                    - The fade at the top edge is painted in the canvas colour rather than composited offscreen.
                    - The turns past the top edge are prefetched while the keyboard is still up.

                    ```kotlin
                    fun Modifier.keyboardInsetPadding(): Modifier =
                        windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    ```

                    | Frame | Before | After |
                    |------:|-------:|------:|
                    | show  | layout | layout |
                    | hide  | compose + layout | layout |
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `keyboard frames on a long transcript, before and after`() {
        val items = transcript(turns = 30)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                variant?.let { (prefetch, paintedFade) -> key(prefetch, paintedFade) { Transcript(items, prefetch, paintedFade) } }
            }
        }
        // Interleaved, and the first round thrown away, so neither arrangement gets the JIT's warm-up or its benefit.
        measure(prefetch = false, paintedFade = false)
        measure(prefetch = true, paintedFade = true)
        val (beforeShow, beforeHide) = measure(prefetch = false, paintedFade = false)
        val (afterShow, afterHide) = measure(prefetch = true, paintedFade = true)
        println("BENCHMARK keyboard-frames turns=30 rows=${items.size} keyboard=${Keyboard}px steps=18")
        for (frames in listOf(beforeShow, beforeHide, afterShow, afterHide)) {
            println("BENCHMARK $frames")
            println("BENCHMARK   frames: ${frames.millis.joinToString(" ") { "%.1f".format(it) }}")
        }

        // Every frame laid out with the composer where the insets put it.
        assertThat(compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.height).isGreaterThan(0f)
        // The hide uncovers rows: with them prefetched it is not slower than composing them on the frame.
        assertThat(afterHide.median).isAtMost(beforeHide.median * 1.5)
    }

    private companion object {
        const val NavigationBar = 126
        const val Keyboard = 900
    }
}
