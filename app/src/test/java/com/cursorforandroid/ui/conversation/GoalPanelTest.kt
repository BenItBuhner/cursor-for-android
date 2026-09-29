package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.ui.conversation.GoalPanelHarness.Companion.COMPOSER
import com.cursorforandroid.ui.conversation.GoalPanelHarness.Companion.DOCK
import com.cursorforandroid.ui.conversation.GoalPanelHarness.Companion.HEADER
import com.cursorforandroid.ui.conversation.GoalPanelHarness.Companion.T0
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The goal strip opened onto a very long objective, in the chat's dock: it grows to the page left above the queue and
 * the composer, to 8dp under the header as an expanded composer does, and never past it; what does not fit scrolls
 * inside it, fading at the edges it scrolls past; it opens and closes on the queue stack's spring; it follows the
 * keyboard; and closed, it is the one-line strip it always was.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class GoalPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density get() = compose.activity.resources.displayMetrics.density
    private fun px(dp: Float) = dp * density

    private fun show(harness: GoalPanelHarness, goal: Goal = GoalPanelHarness.LongGoal, animate: Boolean = true) {
        harnessRef = harness
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { harness.Content(goal = goal, animate = { animate }) } }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val strip get() = bounds("goal-strip")
    private val scrollRange get() = compose.onNodeWithTag("goal-strip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)

    private var harnessRef: GoalPanelHarness? = null
    private fun keyboardTop(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - px(harnessRef!!.keyboard.value)

    private fun tapStrip() = compose.onNodeWithTag("goal-strip").performClick()

    /** The opened strip's room: from 8dp under the header down to the queued row over the composer. */
    private fun assertMaximized(label: String) {
        val header = bounds(HEADER)
        val queue = bounds("account-queue")
        val composer = bounds(COMPOSER)
        val s = strip
        assertWithMessage("$label: 8dp under the header, as an expanded composer stops").that(s.top - header.bottom).isWithin(px(1.5f)).of(px(8f))
        assertWithMessage("$label: stops above the queued row").that(s.bottom + px(4f)).isWithin(px(1.5f)).of(queue.top)
        assertWithMessage("$label: the queue sits on the composer").that(queue.bottom).isAtMost(composer.top + 1f)
        assertWithMessage("$label: the composer stays whole above the keyboard").that(composer.bottom).isAtMost(keyboardTop())
        assertWithMessage("$label: the composer keeps its collapsed height").that(composer.height).isLessThan(px(140f))
        val range = checkNotNull(scrollRange) { "$label: the opened strip scrolls" }
        assertWithMessage("$label: the rest of the objective scrolls inside it").that(range.maxValue()).isGreaterThan(px(40f))
    }

    @Test
    fun `opened, a very long goal fills the page above the queue and the composer and never runs past it`() {
        val harness = GoalPanelHarness()
        show(harness)
        val collapsed = strip
        val composer = bounds(COMPOSER)
        tapStrip()
        settle()
        assertMaximized("phone")
        assertThat(strip.height).isGreaterThan(collapsed.height * 5)
        assertWithMessage("the composer did not move").that(bounds(COMPOSER)).isEqualTo(composer)
        assertWithMessage("inside the window").that(strip.top).isAtLeast(0f)
    }

    @Test
    fun `its content scrolls inside the panel, to the last detail and back`() {
        val harness = GoalPanelHarness(open = true)
        show(harness)
        val last = "From the goal Cursor keeps on the chat."
        // Semantics bounds are clipped to the panel's viewport: a line wholly inside keeps its own height.
        fun lastLineInside(): Boolean {
            val node = compose.onNodeWithText(last, useUnmergedTree = true).fetchSemanticsNode()
            return node.boundsInRoot.height >= node.size.height - 1f
        }
        assertThat(lastLineInside()).isFalse()
        repeat(6) { compose.onNodeWithTag("goal-strip").performTouchInput { swipeUp() } }
        settle()
        assertThat(scrollRange!!.value()).isEqualTo(scrollRange!!.maxValue())
        assertThat(lastLineInside()).isTrue()
        assertWithMessage("still open: a drag scrolls, it does not toggle").that(harness.open).isTrue()
        repeat(6) { compose.onNodeWithTag("goal-strip").performTouchInput { swipeDown() } }
        settle()
        assertThat(scrollRange!!.value()).isEqualTo(0f)
    }

    @Test
    fun `the panel fades at the edges it scrolls past instead of cutting off`() {
        // Twice the objective, so its own full-brightness lines cross both edges wherever the panel is scrolled.
        val longer = GoalPanelHarness.LongGoal.copy(objective = GoalPanelHarness.LongObjective + " " + GoalPanelHarness.LongObjective)
        val harness = GoalPanelHarness()
        show(harness, goal = longer)
        assertWithMessage("closed, nothing fades").that(edges()).isEqualTo(false to false)
        tapStrip()
        settle()
        assertWithMessage("opened at the top, only the bottom fades").that(edges()).isEqualTo(false to true)
        compose.onNodeWithTag("goal-strip").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollRange!!.maxValue() / 3) }
        settle()
        assertWithMessage("scrolled into the middle, both edges fade").that(edges()).isEqualTo(true to true)
    }

    @Test
    fun `it opens and closes on the queue's spring, and turns back smoothly mid-way`() {
        val harness = GoalPanelHarness()
        show(harness)
        val collapsed = strip.height
        compose.mainClock.autoAdvance = false
        tapStrip()
        val heights = mutableListOf(collapsed)
        repeat(5) { compose.mainClock.advanceTimeByFrame(); heights += strip.height }
        val room = keyboardTop() - px(10f) - bounds("account-queue").height - px(4f) - bounds(COMPOSER).height - bounds(HEADER).bottom - px(12f)
        assertWithMessage("growing over several frames, not in one jump").that(heights.last()).isIn(Range.open(collapsed, room))
        assertThat(heights.zipWithNext().all { (a, b) -> b >= a }).isTrue()
        // Closed mid-way: it turns back from where it stands, carrying on up a little before it comes down, never snapping.
        tapStrip()
        val turning = mutableListOf<Float>()
        repeat(40) { compose.mainClock.advanceTimeByFrame(); turning += strip.height }
        assertWithMessage("it keeps its momentum a moment before turning back, as a spring does").that(turning.first()).isAtLeast(heights.last())
        val peak = turning.indexOf(turning.max())
        assertWithMessage("then comes down without a stall").that(turning.subList(peak, peak + 8).zipWithNext().all { (a, b) -> b < a }).isTrue()
        val closing = (listOf(heights.last()) + turning).zipWithNext { a, b -> abs(b - a) }.max()
        assertWithMessage("no frame of the way back snaps").that(closing).isLessThan(heights.last() - collapsed)
        compose.mainClock.autoAdvance = true
        settle()
        assertThat(strip.height).isWithin(1f).of(collapsed)
    }

    @Test
    fun `with animations off it opens and closes at once`() {
        val harness = GoalPanelHarness()
        show(harness, animate = false)
        val collapsed = strip.height
        compose.mainClock.autoAdvance = false
        tapStrip()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertMaximized("animations off")
        tapStrip()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertThat(strip.height).isWithin(1f).of(collapsed)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `it follows the keyboard up and down`() {
        val harness = GoalPanelHarness(open = true)
        show(harness)
        assertMaximized("keyboard down")
        val tall = strip.height
        harness.keyboard = 290.dp
        settle()
        assertMaximized("keyboard up")
        assertThat(tall - strip.height).isWithin(px(2f)).of(px(290f))
        harness.keyboard = 0.dp
        settle()
        assertThat(strip.height).isWithin(1f).of(tall)
    }

    @Test
    fun `closed, the strip is the one line it always was, and a short goal opens only as tall as it needs`() {
        val short = Goal("Ship the light theme", GoalStatus.ACTIVE, accruingSinceMillis = T0)
        val harness = GoalPanelHarness()
        show(harness, goal = short)
        val closed = strip.height
        assertWithMessage("one line of objective under the label").that(closed).isIn(Range.closed(px(40f), px(48f)))
        assertWithMessage("docked on the queued row").that(strip.bottom + px(4f)).isWithin(1f).of(bounds("account-queue").top)
        tapStrip()
        settle()
        assertThat(scrollRange?.maxValue() ?: 0f).isEqualTo(0f)
        assertWithMessage("no taller than its content").that(strip.top - bounds(HEADER).bottom).isGreaterThan(px(300f))
        tapStrip()
        settle()
        assertThat(strip.height).isWithin(1f).of(closed)
    }

    @Test
    fun `the composer still expands under a closed goal as it did, and the two take turns at the page`() {
        val harness = GoalPanelHarness()
        show(harness)
        val goalHeight = strip.height
        val collapsedComposer = bounds(COMPOSER)
        compose.runOnIdle { harness.expansion.expand() }
        settle()
        assertWithMessage("the closed strip keeps its line").that(strip.height).isWithin(1f).of(goalHeight)
        assertWithMessage("the expanded composer leaves the strip 8dp under the header").that(strip.top - bounds(HEADER).bottom).isWithin(px(1.5f)).of(px(8f))
        assertWithMessage("on the queued row, which sits on the composer").that(bounds("account-queue").bottom).isWithin(px(4.5f)).of(bounds(COMPOSER).top)
        assertThat(bounds(COMPOSER).height).isGreaterThan(collapsedComposer.height * 3)
        // Opening the goal lets the composer back down; expanding the composer closes the goal.
        tapStrip()
        settle()
        assertThat(harness.expansion.expanded).isFalse()
        assertMaximized("goal opened over an expanded composer")
        compose.runOnIdle { harness.expansion.expand() }
        settle()
        assertThat(harness.open).isFalse()
        assertThat(strip.height).isWithin(1f).of(goalHeight)
        assertWithMessage("the dock never runs past the window").that(bounds(DOCK).bottom).isAtMost(keyboardTop() + 1f)
    }

    /** Whether the opened strip's top and bottom edges are faded, read off its pixels (see ComposerScrollFadeTest). */
    private fun edges(): Pair<Boolean, Boolean> {
        val root = compose.activity.window.decorView
        val window = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(window)) }
        val box = compose.onNodeWithTag("goal-strip").fetchSemanticsNode().boundsInWindow
        // In from the hairline border, and from the rounded corners' curve at the sides.
        val border = px(2f).roundToInt()
        val side = px(28f).roundToInt()
        val bitmap = Bitmap.createBitmap(window, box.left.roundToInt() + side, box.top.roundToInt() + border, box.width.roundToInt() - 2 * side, box.height.roundToInt() - 2 * border)
        fun luminance(x: Int, y: Int): Float {
            val c = bitmap.getPixel(x, y)
            return 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
        }
        fun brightest(rows: IntRange) = rows.maxOf { y -> (0 until bitmap.width).maxOf { x -> luminance(x, y) } }
        val all = 0 until bitmap.height
        val background = all.minOf { y -> (0 until bitmap.width).minOf { x -> luminance(x, y) } }
        val full = brightest(all) - background
        val inked = all.filter { y -> (0 until bitmap.width).any { x -> luminance(x, y) - background > 0.15f * full } }.toSet()
        // A line of text keeps one brightness across its interior rows (its two outermost rows are anti-aliasing),
        // whatever its colour; under the fade it dims toward the edge. Judged on the line nearest each edge, within
        // the fade's depth.
        val depth = px(28f).toInt()
        val lines = inked.sorted().fold(mutableListOf<MutableList<Int>>()) { runs, y ->
            if (runs.lastOrNull()?.last() == y - 1) runs.last() += y else runs += mutableListOf(y)
            runs
        }
        fun isFaded(band: IntRange): Boolean {
            val crossing = lines.filter { run -> run.any { it in band } }
            // A sliver of a line cut by the edge has no interior to judge; the next line in does.
            val interior = (if (band.first == 0) crossing else crossing.asReversed())
                .map { run -> run.drop(2).dropLast(2).filter { it in band } }
                .firstOrNull { it.size >= 3 } ?: return false
            val rows = interior.map { y -> brightest(y..y) - background }
            return rows.min() / rows.max() < 0.85f
        }
        // The objective's lines, in the text's full brightness, cross whichever edge the panel is scrolled past; the
        // label above them is dimmer by design, so the top is only judged once the panel has scrolled off it.
        val atTop = (scrollRange?.value() ?: 0f) == 0f
        val top = if (atTop) false else isFaded(0 until depth)
        return top to isFaded(bitmap.height - depth until bitmap.height)
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
