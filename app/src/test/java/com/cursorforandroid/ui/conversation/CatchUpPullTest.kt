package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The pull to catch up read off the reader's drag ([CatchUpOverscroll]): the drag past the newest message lifts the
 * transcript against a rubber band and arms it only at the threshold — exactly where the lift reaches [CatchUpPull.liftPx]
 * — and a release armed pulls once, a release short of it never; nothing of the pull, nor a fling's leftover at the
 * newest edge, ever reaches the platform's stretch, while the drag past the oldest message still does; a drag back
 * relaxes the pull before the list scrolls; a fling reaching the edge never pulls; a chat that cannot be caught up
 * stretches the platform's way, and one being answered is moved without arming and keeps its held gap.
 */
@OptIn(ExperimentalFoundationApi::class)
class CatchUpPullTest {

    /**
     * The platform's effect, as Android's stretch is: it holds what the list leaves over as a stretch, and while one
     * is drawn it takes a drag deepening it, and a drag back as far as it goes, before the list is offered the rest.
     * Noted: every stretch it was handed, and the fling velocity left over for it to absorb.
     */
    private class Platform : OverscrollEffect {
        var stretch = 0f
            private set
        val stretched = mutableListOf<Float>()
        val absorbed = mutableListOf<Velocity>()
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            val taken = when {
                stretch > 0f -> if (delta.y > 0f) delta.y else maxOf(-stretch, delta.y)
                stretch < 0f -> if (delta.y < 0f) delta.y else minOf(-stretch, delta.y)
                else -> 0f
            }
            stretch += taken
            val offered = delta.y - taken
            val consumed = performScroll(delta.copy(y = offered))
            val over = offered - consumed.y
            if (source == NestedScrollSource.UserInput && over != 0f) {
                stretch += over
                stretched += over
            }
            return delta
        }
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            val over = velocity - performFling(velocity)
            if (over != Velocity.Zero) absorbed += over
        }
        override val isInProgress: Boolean get() = false
        override val effectModifier: Modifier get() = Modifier
    }

    private val platform = Platform()
    private val pull = CatchUpPull(thresholdPx = 200f, liftPx = 104f, heldPx = 80f)
    private var enabled = true
    private var pulls = 0
    private val effect = CatchUpOverscroll(platform, pull, enabled = { enabled }, onPulled = { pulls++ })

    /** What the list scrolled, drag by drag. */
    private val listScrolls = mutableListOf<Float>()

    /** How much farther the list could scroll toward the newest row (a finger moving up is a negative delta). */
    private var room = 0f

    /** How much farther toward the oldest row. */
    private var above = 1_000f

    private fun drag(dy: Float, source: NestedScrollSource = NestedScrollSource.UserInput) {
        effect.applyToScroll(Offset(0f, dy), source) { available ->
            val y = if (available.y < 0f) maxOf(available.y, -room) else minOf(available.y, above)
            room += y
            above -= y
            listScrolls += y
            Offset(0f, y)
        }
    }

    private fun release(velocity: Float = 0f, flingRoom: Float = 0f): Velocity? {
        var offered: Velocity? = null
        runBlocking {
            effect.applyToFling(Velocity(0f, velocity)) { v ->
                offered = v
                // The list flings as far as it has room and says how much it took, as Compose's fling does.
                val taken = kotlin.math.abs(v.y).coerceAtMost(flingRoom)
                Velocity(0f, if (v.y < 0f) -taken else taken)
            }
        }
        return offered
    }

    @Test
    fun `the threshold is far past the old one, and the lift is exactly the threshold's there`() {
        drag(-88f)
        assertThat(pull.armed).isFalse()
        drag(-(pull.thresholdPx - 88f - 1f))
        assertThat(pull.armed).isFalse()
        assertThat(pull.distanceFraction).isLessThan(1f)
        drag(-1f)
        assertThat(pull.armed).isTrue()
        assertThat(pull.lift).isWithin(0.01f).of(pull.liftPx)
        assertThat(pull.distanceFraction).isWithin(0.0001f).of(1f)
    }

    @Test
    fun `a release short of the threshold asks for nothing, however close it came`() {
        drag(-(pull.thresholdPx * 0.97f))
        assertThat(pull.holding).isTrue()
        assertThat(pull.armed).isFalse()
        release()
        assertThat(pulls).isEqualTo(0)
        assertThat(pull.pulls).isEqualTo(0)
        assertThat(pull.awaiting).isFalse()
        assertThat(pull.holding).isFalse()
    }

    @Test
    fun `a drag past the threshold arms and a release pulls once, held for the answer`() {
        drag(-120f)
        drag(-120f)
        assertThat(pull.armed).isTrue()
        assertThat(effect.isInProgress).isTrue()
        release()
        assertThat(pulls).isEqualTo(1)
        assertThat(pull.holding).isFalse()
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(pull.awaiting).isTrue()
        // Let go, the transcript stands where the finger left it until the spring takes it.
        assertThat(pull.lift).isWithin(0.01f).of(catchUpRubberBand(240f, pull.reachPx))
    }

    @Test
    fun `the transcript follows the finger against a rubber band - less and less of it, never past its reach`() {
        val steps = (1..12).map { catchUpRubberBand(it * 50f, pull.reachPx) }
        assertThat(catchUpRubberBand(0f, pull.reachPx)).isEqualTo(0f)
        assertThat(catchUpRubberBand(-20f, pull.reachPx)).isEqualTo(0f)
        // At first nearly glued to the finger.
        assertThat(catchUpRubberBand(1f, pull.reachPx)).isWithin(0.01f).of(CatchUpGive)
        // Every 50 px of finger lifts it less than the last, and it never reaches its reach.
        val gains = steps.zipWithNext { a, b -> b - a }
        gains.zipWithNext { a, b -> assertThat(b).isLessThan(a) }
        assertThat(catchUpRubberBand(1_000_000f, pull.reachPx)).isLessThan(pull.reachPx)
        // And the other way round.
        listOf(10f, 60f, 104f, 150f).forEach { lift ->
            assertThat(catchUpRubberBand(catchUpFingerFor(lift, pull.reachPx), pull.reachPx)).isWithin(0.01f).of(lift)
        }
        drag(-300f)
        assertThat(pull.lift).isWithin(0.01f).of(catchUpRubberBand(300f, pull.reachPx))
        assertThat(pull.lift).isLessThan(300f * CatchUpGive)
    }

    @Test
    fun `nothing of the pull reaches the platform's stretch, nor a fling's leftover at the newest edge`() {
        drag(-150f)
        drag(-150f)
        assertThat(platform.stretched).isEmpty()
        assertThat(platform.stretch).isEqualTo(0f)
        val offered = release(velocity = -4_000f)
        assertThat(pulls).isEqualTo(1)
        assertThat(offered).isEqualTo(Velocity(0f, -4_000f))
        assertThat(platform.absorbed).isEmpty()

        // A fling from up the list that reaches the newest row stops there: no stretch at that edge either.
        room = 300f
        release(velocity = -6_000f, flingRoom = 2_000f)
        assertThat(platform.absorbed).isEmpty()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `the drag past the oldest message is still the platform's stretch`() {
        above = 0f
        drag(40f)
        assertThat(platform.stretched).containsExactly(40f)
        assertThat(pull.holding).isFalse()
        release(velocity = 3_000f)
        assertThat(platform.absorbed).containsExactly(Velocity(0f, 3_000f))
    }

    @Test
    fun `a drag back before the release disarms it, and taken back the list scrolls only once the pull is home`() {
        drag(-240f)
        assertThat(pull.armed).isTrue()
        listScrolls.clear()
        drag(100f)
        assertThat(pull.armed).isFalse()
        assertThat(pull.distance).isEqualTo(140f)
        drag(160f)
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(listScrolls).containsExactly(20f)
        release()
        assertThat(pulls).isEqualTo(0)
    }

    @Test
    fun `a drag that reaches the newest row on its way is the pull's only past it, and back up the list it scrolls again`() {
        room = 30f
        drag(-50f)
        assertThat(pull.distance).isEqualTo(20f)
        drag(100f)
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(room).isEqualTo(80f)
        // Up again in the same gesture, the list scrolls back to its newest row before the pull takes any of it.
        drag(-90f)
        assertThat(room).isEqualTo(0f)
        assertThat(pull.distance).isEqualTo(10f)
    }

    @Test
    fun `a drag the list takes, or a fling reaching the edge, never pulls`() {
        room = 1_000f
        drag(-150f)
        assertThat(pull.holding).isFalse()
        room = 0f
        drag(-150f, source = NestedScrollSource.SideEffect)
        assertThat(pull.holding).isFalse()
        release(velocity = -5_000f)
        assertThat(pulls).isEqualTo(0)
    }

    @Test
    fun `only an armed release counts as a pull`() {
        drag(-150f)
        release()
        assertThat(pull.pulls).isEqualTo(0)
        drag(-250f)
        release()
        assertThat(pull.pulls).isEqualTo(1)
    }

    @Test
    fun `a chat that cannot be caught up stretches the platform's way, and a pull under way is not cut short`() {
        enabled = false
        drag(-150f)
        assertThat(pull.holding).isFalse()
        assertThat(platform.stretched).containsExactly(-150f)
        release()
        assertThat(pulls).isEqualTo(0)
        enabled = true
        platform.stretched.clear()
        val fresh = CatchUpOverscroll(Platform(), pull, enabled = { enabled }, onPulled = { pulls++ })
        fresh.applyToScroll(Offset(0f, -120f), NestedScrollSource.UserInput) { Offset.Zero }
        // Asked once, at the pull's first pixel: a chat that becomes busy under the finger does not drop the pull.
        enabled = false
        fresh.applyToScroll(Offset(0f, -120f), NestedScrollSource.UserInput) { Offset.Zero }
        assertThat(pull.armed).isTrue()
        runBlocking { fresh.applyToFling(Velocity.Zero) { it } }
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `over a pull being answered the drag moves the transcript from its held gap, never arms, and never takes the gap away`() {
        runBlocking { pull.snapTo(pull.heldPx / pull.liftPx) }
        pull.held = true
        enabled = false
        drag(-400f)
        assertThat(pull.holding).isTrue()
        assertThat(pull.lift).isGreaterThan(pull.liftPx)
        assertThat(pull.armed).isFalse()
        // Back down past where it took hold: the gap stays, and the list scrolls under it.
        listScrolls.clear()
        drag(600f)
        assertThat(pull.lift).isWithin(0.01f).of(pull.heldPx)
        assertThat(listScrolls.sum()).isGreaterThan(0f)
        release()
        assertThat(pulls).isEqualTo(0)
        assertThat(pull.awaiting).isFalse()
    }

    @Test
    fun `taken up again while it springs home, the pull carries on from where the transcript stands`() {
        drag(-150f)
        val left = pull.lift
        release()
        drag(-1f)
        assertThat(pull.distance).isWithin(0.01f).of(151f)
        assertThat(pull.lift).isWithin(0.01f).of(catchUpRubberBand(151f, pull.reachPx))
        assertThat(pull.lift).isGreaterThan(left)
    }
}
