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
 * message stretches it and arms it at the threshold, and a release armed pulls once; a drag back relaxes it before
 * the list scrolls, whether or not the platform's stretch takes the drag too; a fling
 * reaching the edge never pulls, a chat that cannot be caught up stretches without pulling, and every delta still
 * reaches the platform's own stretch. As the sidebar's pull to refresh, it is a `PullToRefreshState`: its fraction is
 * the finger's up to the threshold and Material's tension past it, left where the finger let go, and the finger's
 * alone to move while it holds.
 */
@OptIn(ExperimentalFoundationApi::class)
class CatchUpPullTest {

    /**
     * The platform's effect, noting what it was given. Without [stretches] it passes every delta through, as Android
     * does with animations off; with it, as Android's stretch does, it holds what the list left over as a [stretch],
     * and while one is drawn — the finger's, or one still springing back — every delta goes to it before the list is
     * offered the rest: all of a drag deepening it, a drag back as far as it goes.
     */
    private class Platform : OverscrollEffect {
        val scrolls = mutableListOf<Offset>()
        var flings = 0
        var stretches = false
        var stretch = 0f
            private set
        override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
            scrolls += delta
            val taken = when {
                !stretches || stretch == 0f -> 0f
                delta.y < 0f -> delta.y
                else -> minOf(stretch, delta.y)
            }
            stretch -= taken
            val offered = delta.y - taken
            val consumed = performScroll(delta.copy(y = offered))
            if (stretches && offered < 0f) stretch += consumed.y - offered
            return consumed.copy(y = consumed.y + taken)
        }
        override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
            flings++
            performFling(velocity)
        }
        override val isInProgress: Boolean get() = false
        override val effectModifier: Modifier get() = Modifier
    }

    private val platform = Platform()
    private val pull = CatchUpPull(thresholdPx = 100f)
    private var enabled = true
    private var pulls = 0
    private var atNewest = true
    private val effect = CatchUpOverscroll(platform, pull, atNewest = { atNewest }, enabled = { enabled }, onPulled = { pulls++ })

    /** What the list scrolled, drag by drag. */
    private val listScrolls = mutableListOf<Float>()

    /** The list at its newest edge: a finger moving up (a negative delta, in screen terms) is left over whole. */
    private fun drag(dy: Float, source: NestedScrollSource = NestedScrollSource.UserInput, listTakes: Boolean = false) {
        atNewest = !listTakes
        effect.applyToScroll(Offset(0f, dy), source) { available -> (if (listTakes || available.y > 0f) available else Offset.Zero).also { listScrolls += it.y } }
    }

    private fun release() = runBlocking { effect.applyToFling(Velocity.Zero) { it } }

    @Test
    fun `a drag past the newest message arms at the threshold and a release pulls once`() {
        drag(-40f)
        assertThat(pull.holding).isTrue()
        assertThat(pull.armed).isFalse()
        assertThat(pull.distanceFraction).isWithin(0.001f).of(0.4f)
        drag(-70f)
        assertThat(pull.armed).isTrue()
        assertThat(pull.distanceFraction).isWithin(0.001f).of(catchUpTension(1.1f))
        assertThat(effect.isInProgress).isTrue()
        release()
        assertThat(pulls).isEqualTo(1)
        assertThat(pull.holding).isFalse()
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(pull.awaiting).isTrue()
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
        assertThat(pull.awaiting).isFalse()
    }

    @Test
    fun `taken back, the pull is the finger's first - the list scrolls only once it is home`() {
        drag(-120f)
        listScrolls.clear()
        drag(80f)
        assertThat(pull.distance).isEqualTo(40f)
        drag(60f)
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(listScrolls).containsExactly(0f, 20f).inOrder()
        // Every delta still reached the platform.
        assertThat(platform.scrolls).containsExactly(Offset(0f, -120f), Offset(0f, 80f), Offset(0f, 60f)).inOrder()
    }

    @Test
    fun `with the platform's stretch taking the drag back too, the list still scrolls only once the pull is home`() {
        platform.stretches = true
        drag(-120f)
        listScrolls.clear()
        drag(80f)
        assertThat(pull.distance).isEqualTo(40f)
        drag(60f)
        assertThat(pull.distance).isEqualTo(0f)
        assertThat(listScrolls).containsExactly(0f, 20f).inOrder()
    }

    @Test
    fun `with the platform's stretch drawn, the drag deepening it is the pull's all the same, the stretch alongside`() {
        platform.stretches = true
        drag(-40f)
        drag(-70f)
        assertThat(platform.stretch).isEqualTo(110f)
        assertThat(pull.distance).isEqualTo(110f)
        assertThat(pull.armed).isTrue()
        release()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `pulled again while the last pull's stretch still springs back, the whole drag is the pull's`() {
        platform.stretches = true
        drag(-60f)
        release()
        assertThat(pulls).isEqualTo(0)
        drag(-120f)
        assertThat(pull.distance).isEqualTo(120f)
        release()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `a drag that reaches the newest row on its way is the pull's only past it`() {
        var room = 30f
        atNewest = false
        effect.applyToScroll(Offset(0f, -50f), NestedScrollSource.UserInput) { available ->
            available.copy(y = maxOf(available.y, -room)).also { room += it.y }
        }
        assertThat(pull.distance).isEqualTo(20f)
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
    fun `the fraction is the finger's to the threshold, then slows as the sidebar's does, never past twice it`() {
        assertThat(catchUpTension(-0.2f)).isEqualTo(0f)
        assertThat(catchUpTension(0.5f)).isEqualTo(0.5f)
        assertThat(catchUpTension(1f)).isEqualTo(1f)
        assertThat(catchUpTension(2f)).isEqualTo(1.75f)
        assertThat(catchUpTension(3f)).isEqualTo(2f)
        assertThat(catchUpTension(9f)).isEqualTo(2f)
        drag(-50f)
        assertThat(pull.distanceFraction).isEqualTo(0.5f)
        drag(-250f)
        assertThat(pull.distanceFraction).isEqualTo(2f)
    }

    @Test
    fun `let go, the indicator stands where the finger left it, and only the finger moves it while it holds`() {
        assertThat(pull.isAnimating).isTrue()
        drag(-60f)
        assertThat(pull.isAnimating).isFalse()
        release()
        assertThat(pull.distanceFraction).isWithin(0.001f).of(0.6f)
        assertThat(pull.isAnimating).isTrue()
        drag(-20f)
        assertThat(pull.distanceFraction).isWithin(0.001f).of(0.2f)
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
