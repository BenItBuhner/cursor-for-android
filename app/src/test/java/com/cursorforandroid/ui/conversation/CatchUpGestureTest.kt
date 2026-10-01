package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pull to catch up through the transcript's own scroll ([readerScrolling], [readerBackdrop]) with a real drag: a
 * chat that overflows the screen, followed at its newest row, and one short enough to fit — whose list cannot scroll
 * at all, which the platform's scrollable would otherwise never hand to the overscroll, and which is sized to its
 * rows, so the finger can as well be on the empty space below them. A drag short of the threshold, one the old
 * threshold would have taken, springs back with nothing asked. As on a phone with animations off: Android draws no
 * stretch ([CatchUpGestureStretchTest] has one, and the pull never draws it).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
open class CatchUpGestureTest {

    @get:Rule
    val compose = createComposeRule()

    protected var pulls = 0
        private set
    protected lateinit var pull: CatchUpPull
        private set
    protected lateinit var host: View
        private set

    protected open fun transcript(rows: Int) {
        compose.setContent {
            host = LocalView.current
            val density = LocalDensity.current
            pull = androidx.compose.runtime.remember { catchUpPullFor(density) }
            val list = rememberLazyListState()
            val scroll = rememberTranscriptScroll(list, key = "chat")
            val reader = rememberReaderScroll(scroll, pull = pull, canCatchUp = { true }, onCatchUp = { pulls++ })
            Box(Modifier.fillMaxSize().testTag("area")) {
                Box(Modifier.matchParentSize().readerBackdrop(reader))
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).readerScrolling(reader).catchUpLift(pull).testTag("transcript"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it }) { Text("Row $it", Modifier.fillMaxWidth().height(56.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pullUp() = compose.onNodeWithTag("transcript").performTouchInput { swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 900) }

    @Test
    fun `a chat that overflows the screen, at its newest row, is caught up by a pull past it`() {
        transcript(rows = 60)
        pullUp()
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
        assertThat(pull.holding).isFalse()
    }

    @Test
    fun `a chat short enough to fit the screen is caught up by the same pull`() {
        transcript(rows = 4)
        pullUp()
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `a chat short enough to fit the screen is pulled from the space below its newest row too`() {
        transcript(rows = 4)
        val rows = compose.onNodeWithTag("transcript").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("area").performTouchInput {
            assertThat(bottom).isGreaterThan(rows.bottom + CatchUpPullThreshold.toPx() * 2)
            swipeUp(startY = bottom - 4f, endY = rows.bottom + 4f, durationMillis = 900)
        }
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
    }

    @Test
    fun `a drag short of the threshold lifts the transcript and lets it go with nothing asked`() {
        transcript(rows = 60)
        compose.onNodeWithTag("transcript").performTouchInput {
            down(Offset(centerX, bottom - 4f))
            val step = (viewConfiguration.touchSlop + CatchUpPullThreshold.toPx() * 0.85f) / 20f
            repeat(20) { moveBy(Offset(0f, -step)) }
        }
        compose.waitForIdle()
        assertThat(pull.holding).isTrue()
        assertThat(pull.lift).isGreaterThan(0f)
        assertThat(pull.armed).isFalse()
        compose.onNodeWithTag("transcript").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(0)
        assertThat(pull.holding).isFalse()
    }

    @Test
    fun `a drag down a short chat never pulls`() {
        transcript(rows = 4)
        compose.onNodeWithTag("transcript").performTouchInput { swipeDown(startY = top + 4f, endY = bottom - 4f, durationMillis = 900) }
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(0)
    }
}

/**
 * [CatchUpGestureTest] on a phone with animations on, as a reader's is: Android's stretch can be drawn
 * ([StretchingEdgeEffect]). It still is past the oldest message, but never under the pull at the newest: the
 * transcript is lifted there instead.
 */
@Config(shadows = [StretchingEdgeEffect::class])
class CatchUpGestureStretchTest : CatchUpGestureTest() {

    @Before
    fun noStretchYet() = StretchingEdgeEffect.reset()

    /** The platform's overscroll takes its size from the frame it draws in, and Robolectric draws none of its own. */
    override fun transcript(rows: Int) {
        super.transcript(rows)
        compose.runOnIdle { host.draw(Canvas(Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888))) }
    }

    @Test
    fun `under the finger the pull follows it past the newest row and no stretch is drawn at that edge`() {
        transcript(rows = 60)
        val step = CatchUpPullThreshold.value / 5f
        compose.onNodeWithTag("transcript").performTouchInput {
            down(Offset(centerX, bottom - 4f))
            repeat(4) { moveBy(Offset(0f, -step)) }
        }
        compose.waitForIdle()
        val pulled = pull.distance
        assertThat(pull.holding).isTrue()
        assertThat(pull.lift).isGreaterThan(0f)

        compose.onNodeWithTag("transcript").performTouchInput { repeat(4) { moveBy(Offset(0f, -step)) } }
        compose.waitForIdle()
        assertThat(pull.distance).isWithin(0.5f).of(pulled + 4 * step)
        assertThat(pull.armed).isTrue()

        compose.onNodeWithTag("transcript").performTouchInput { up() }
        compose.waitForIdle()
        assertThat(pulls).isEqualTo(1)
        assertThat(StretchingEdgeEffect.deepest).isEqualTo(0f)
        assertThat(StretchingEdgeEffect.absorbed).isEqualTo(0)
    }

    @Test
    fun `past the oldest message the stretch is still Android's own`() {
        transcript(rows = 4)
        compose.onNodeWithTag("transcript").performTouchInput {
            down(Offset(centerX, top + 4f))
            repeat(6) { moveBy(Offset(0f, 30f)) }
        }
        compose.waitForIdle()
        assertThat(StretchingEdgeEffect.deepest).isGreaterThan(0f)
        assertThat(pull.holding).isFalse()
        compose.onNodeWithTag("transcript").performTouchInput { up() }
    }
}
