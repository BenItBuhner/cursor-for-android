package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The jump button's glide ([TranscriptScroll.jumpToBottom]) frame by frame, on a following list of [ROWS] rows of mixed
 * heights: a short eased scroll however far up the reader is — never a snap, never the whole distance.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class JumpToBottomGlideTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val list = LazyListState()
    private val scroll = TranscriptScroll(list, mutableStateOf(true))
    private val rows = mutableStateListOf<String>().apply { repeat(ROWS) { add("row-$it") } }
    private var composed = 0
    private lateinit var scope: CoroutineScope

    private fun heightOf(key: String) = 24 + (key.hashCode().mod(9)) * 37

    private fun show() {
        compose.setContent {
            scope = rememberCoroutineScope()
            // Newest first, as a following transcript is declared.
            LazyColumn(Modifier.fillMaxWidth().height(700.dp).testTag("list"), state = list, reverseLayout = true) {
                items(rows, key = { it }) { key ->
                    SideEffect { composed++ }
                    Box(Modifier.fillMaxWidth().height(heightOf(key).dp))
                }
            }
        }
        compose.waitForIdle()
    }

    private class Frame(val index: Int, val offset: Int, val composed: Int, val distance: Float?)

    private fun frame(): Frame = Frame(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, composed, TranscriptScroll.distanceToNewest(list.layoutInfo))

    /** Taps the jump, then draws frames until it is over (at most a second's worth), each one's place recorded. */
    private fun glide(during: (frame: Int) -> Unit = {}): Pair<List<Frame>, Job> {
        compose.mainClock.autoAdvance = false
        lateinit var job: Job
        compose.runOnIdle { job = scope.launch { scroll.jumpToBottom() } }
        val frames = mutableListOf<Frame>()
        for (n in 1..60) {
            compose.mainClock.advanceTimeByFrame()
            composed = 0
            frames += frame()
            if (!job.isActive) break
            during(n)
        }
        return frames to job
    }

    private fun scrollUpTo(index: Int, offset: Int = 0) {
        compose.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    private val screen get() = list.layoutInfo.viewportSize.height.toFloat()

    /** How far (px) the list is scrolled up from the newest row's bottom, off the rows' known heights. */
    private fun fromNewest(index: Int, offset: Int): Float =
        with(compose.density) { (0 until index).sumOf { heightOf(rows[it]).dp.roundToPx() } + offset }.toFloat()

    @Test
    fun `from thousands of rows up, the jump lands a few screens above the newest row and glides the rest`() {
        show()
        scrollUpTo(2_400)
        val (frames, job) = glide()
        assertThat(job.isCompleted).isTrue()
        val trace = frames.map { "${it.index}/${it.offset}" }
        // The last frame is the newest row, at its bottom.
        assertWithMessage("frames: $trace").that(frames.last().let { it.index to it.offset }).isEqualTo(0 to 0)
        val millis = frames.size * FRAME_MS
        assertWithMessage("the glide's length, ${millis}ms: $trace").that(millis).isIn(TranscriptScroll.GLIDE_MIN_MILLIS..TranscriptScroll.GLIDE_MAX_MILLIS + 2 * FRAME_MS)
        // The first frame is already within the landing's reach: the rows between were never laid out.
        assertWithMessage("first frame: $trace").that(frames.first().index).isLessThan(60)
        // Every frame from there nearer the newest row than the one before, and moving on most of them.
        val distances = frames.map { it.index * 1_000_000L + it.offset }
        assertWithMessage("never back up: $trace").that(distances.zipWithNext().all { (a, b) -> b <= a }).isTrue()
        assertWithMessage("frames moving: $trace").that(distances.zipWithNext().count { (a, b) -> b < a }).isAtLeast(frames.size / 2)
        // No frame composes more than a couple of screens of rows.
        assertWithMessage("rows composed per frame: ${frames.map { it.composed }}").that(frames.maxOf { it.composed }).isLessThan(60)
    }

    @Test
    fun `most of a screen up, the glide covers the whole way, easing in and out`() = glidesTheWholeWay(screens = 0.8f)

    /** Past where the rows on screen say, short of a jump: the distance is measured before the glide, not guessed. */
    @Test
    fun `two and a half screens up, the glide covers the whole way without a lurch`() = glidesTheWholeWay(screens = 2.5f)

    private fun glidesTheWholeWay(screens: Float) {
        show()
        compose.runOnIdle { kotlinx.coroutines.runBlocking { list.scrollBy(screen * screens) } }
        compose.waitForIdle()
        assertThat(TranscriptScroll.distanceToNewest(list.layoutInfo)).isNull()
        val from = fromNewest(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        val (frames, _) = glide()
        val left = frames.map { fromNewest(it.index, it.offset) }
        val trace = left.map { it.toInt() }
        assertThat(left.last()).isEqualTo(0f)
        val millis = frames.size * FRAME_MS
        assertWithMessage("the glide's length: $trace").that(millis).isIn(TranscriptScroll.GLIDE_MIN_MILLIS..TranscriptScroll.GLIDE_MAX_MILLIS + 2 * FRAME_MS)
        val steps = (listOf(from) + left).zipWithNext { a, b -> a - b }
        assertWithMessage("never back up: $trace").that(steps.all { it >= 0f }).isTrue()
        // Soft at both ends: the fastest frame is well inside the glide, and the first and last are slower than it.
        val fastest = steps.indices.maxBy { steps[it] }
        assertWithMessage("fastest frame $fastest of ${steps.size}: $trace").that(fastest).isIn(1 until steps.size / 2)
        assertWithMessage("the landing: $trace").that(steps.last()).isLessThan(steps[fastest] / 8)
        // Slowing steadily from there: no frame faster than the one before it, as a guess corrected mid-glide would be.
        val slowing = steps.drop(fastest)
        assertWithMessage("steps after the fastest: ${slowing.map { it.toInt() }}").that(slowing.zipWithNext().all { (a, b) -> b <= a + 1f }).isTrue()
    }

    @Test
    fun `rows landing under the glide are glided to as well`() {
        show()
        scrollUpTo(900)
        val (frames, _) = glide { n -> if (n % 4 == 0) rows.add(0, "streamed-$n") }
        assertThat(rows.first()).startsWith("streamed-")
        assertWithMessage("frames: ${frames.map { "${it.index}/${it.offset}" }}").that(frames.last().let { it.index to it.offset }).isEqualTo(0 to 0)
        assertThat(list.layoutInfo.visibleItemsInfo.first().key).isEqualTo(rows.first())
    }

    @Test
    fun `the reader's drag takes over from the glide where it has got to`() {
        show()
        scrollUpTo(1_200)
        lateinit var at: Frame
        val (_, job) = glide { n ->
            if (n == 12) {
                at = frame()
                compose.onNodeWithTag("list").performTouchInput { swipeDown(durationMillis = 200) }
            }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(job.isCancelled).isTrue()
        assertThat(scroll.isJumping).isFalse()
        // Not taken on to the bottom, and the drag (toward older rows) moved it back up from where the glide was.
        assertThat(list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset).isNotEqualTo(0 to 0)
        assertThat(list.firstVisibleItemIndex * 1_000_000L + list.firstVisibleItemScrollOffset).isGreaterThan(at.index * 1_000_000L + at.offset)
    }

    @Test
    fun `the glide's length stays within bounds however far`() {
        assertThat(TranscriptScroll.glideMillis(0f)).isEqualTo(TranscriptScroll.GLIDE_MIN_MILLIS)
        assertThat(TranscriptScroll.glideMillis(1f)).isEqualTo(TranscriptScroll.GLIDE_MIN_MILLIS)
        assertThat(TranscriptScroll.glideMillis(TranscriptScroll.LANDING_SCREENS)).isEqualTo(TranscriptScroll.GLIDE_MAX_MILLIS)
        assertThat(TranscriptScroll.glideMillis(40f)).isEqualTo(TranscriptScroll.GLIDE_MAX_MILLIS)
        assertThat(TranscriptScroll.GLIDE_MAX_MILLIS).isAtMost(450)
    }

    private companion object {
        const val ROWS = 3_000
        const val FRAME_MS = 16
    }
}
