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
 * The pull to catch up read off the platform's overscroll ([CatchUpOverscroll]): the reader's drag past the newest
 * message stretches it and arms it at the threshold, and a release armed pulls once; a drag back relaxes it, a fling
 * reaching the edge never pulls, a chat that cannot be caught up stretches without pulling, and every
 * delta still reaches the platform's own stretch. What it reveals goes with the finger, the answer keeps the whole tab
 * out, and the transcript is lifted only as far as it must be to clear it.
 */
@OptIn(ExperimentalFoundationApi::class)
class CatchUpPullTest {

    /** The platform's effect, passing every delta through as Android's stretch does and noting what it was given. */
    private class Platform : OverscrollEffect {
        val scrolls = mutableListOf<Offset>()
        var flings = 0
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            scrolls += delta
            return performScroll(delta)
        }
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            flings++
            performFling(velocity)
        }
        override val isInProgress: Boolean get() = false
        override val effectModifier: Modifier get() = Modifier
    }

    private val platform = Platform()
    private val pull = CatchUpPull(thresholdPx = 100f, maxRevealPx = 50f)
    private var enabled = true
    private var pulls = 0
    private val effect = CatchUpOverscroll(platform, pull, enabled = { enabled }, onPulled = { pulls++ })

    /** The list at its newest edge: a finger moving up (a negative delta, in screen terms) is left over whole. */
    private fun drag(dy: Float, source: NestedScrollSource = NestedScrollSource.UserInput, listTakes: Boolean = false) {
        effect.applyToScroll(Offset(0f, dy), source) { available -> if (listTakes || available.y > 0f) available else Offset.Zero }
    }

    private fun release() = runBlocking { effect.applyToFling(Velocity.Zero) { it } }

    @Test
    fun `a drag past the newest message arms at the threshold and a release pulls once`() {
        drag(-40f)
        assertThat(pull.holding).isTrue()
        assertThat(pull.armed).isFalse()
        assertThat(pull.progress).isWithin(0.001f).of(0.4f)
        assertThat(pull.revealPx).isGreaterThan(0f)
        drag(-70f)
        assertThat(pull.armed).isTrue()
        assertThat(pull.revealPx).isLessThan(50f)
        assertThat(effect.isInProgress).isTrue()
        release()
        assertThat(pulls).isEqualTo(1)
        assertThat(pull.holding).isFalse()
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(pull.revealPx).isEqualTo(0f)
        // The platform had every delta, and its release: the stretch and its spring back are Android's own.
        assertThat(platform.scrolls).containsExactly(Offset(0f, -40f), Offset(0f, -70f)).inOrder()
        assertThat(platform.flings).isEqualTo(1)
    }

    @Test
    fun `a drag back before the release disarms it`() {
        drag(-120f)
        assertThat(pull.armed).isTrue()
        drag(60f)
        assertThat(pull.armed).isFalse()
        assertThat(pull.distance).isEqualTo(60f)
        release()
        assertThat(pulls).isEqualTo(0)
    }

    @Test
    fun `a drag the list takes, or a fling reaching the edge, never pulls`() {
        drag(-150f, listTakes = true)
        assertThat(pull.holding).isFalse()
        drag(-150f, source = NestedScrollSource.SideEffect)
        assertThat(pull.holding).isFalse()
        release()
        assertThat(pulls).isEqualTo(0)
        assertThat(platform.scrolls).hasSize(2)
    }

    @Test
    fun `the reveal goes with the finger at first, then slows short of its cap`() {
        drag(-5f)
        assertThat(pull.revealPx).isWithin(0.3f).of(5f)
        drag(-45f)
        assertThat(pull.revealPx).isLessThan(pull.distance)
        drag(-400f)
        assertThat(pull.revealPx).isWithin(0.1f).of(50f)
    }

    @Test
    fun `only an armed release counts as a pull`() {
        drag(-60f)
        release()
        assertThat(pull.pulls).isEqualTo(0)
        drag(-120f)
        release()
        assertThat(pull.pulls).isEqualTo(1)
    }

    @Test
    fun `the answer keeps the whole tab out, and nothing is out without one`() {
        pull.tabPx = 34f
        drag(-20f)
        assertThat(pull.liftPx(CatchUpStatus.Idle)).isEqualTo(pull.revealPx)
        release()
        assertThat(pull.liftPx(CatchUpStatus.Checking)).isEqualTo(34f)
        assertThat(pull.liftPx(CatchUpStatus.Done(newMessages = 1, changed = true))).isEqualTo(34f)
        assertThat(pull.liftPx(CatchUpStatus.Idle)).isEqualTo(0f)
    }

    @Test
    fun `a transcript that fills the screen is lifted by the whole reveal, one short enough to fit only by what its room does not clear`() {
        assertThat(catchUpListLift(revealPx = 40f, roomPx = 0)).isEqualTo(40f)
        assertThat(catchUpListLift(revealPx = 40f, roomPx = 15)).isEqualTo(25f)
        assertThat(catchUpListLift(revealPx = 40f, roomPx = 300)).isEqualTo(0f)
        assertThat(catchUpListLift(revealPx = 0f, roomPx = -5)).isEqualTo(0f)
    }

    @Test
    fun `a chat that cannot be caught up stretches without pulling, and a pull under way is not cut short`() {
        enabled = false
        drag(-150f)
        assertThat(pull.holding).isFalse()
        release()
        assertThat(pulls).isEqualTo(0)
        enabled = true
        drag(-60f)
        // Asked once, at the pull's first pixel: a chat that becomes busy under the finger does not drop the pull.
        enabled = false
        drag(-60f)
        assertThat(pull.armed).isTrue()
        release()
        assertThat(pulls).isEqualTo(1)
    }
}
