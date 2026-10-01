package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

/**
 * The queue card's height, one 16 ms frame at a time on a held clock: what the transcript over the dock follows. A row
 * the reader takes off (removed, or taken back to edit) closes up over a spring rather than in the frame it went, as a
 * send opens one; a row the run takes folds away as its copy lifts off into the bubble. The third send
 * stacking the list into a deck settles the card's height without dipping below where it comes to rest.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueDeckHeightTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private val dp get() = compose.activity.resources.displayMetrics.density

    private fun queued(vararg texts: String) = texts.mapIndexed { i, text -> QueuedFollowUp("q-${i + 1}", text, queuedAtMillis = 0L) }

    private fun stackHeight(): Float =
        compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow.height

    /** The card's height at each of the next [count] frames, [before] first. */
    private fun heights(count: Int, onFrame: () -> Unit = {}, change: () -> Unit): List<Float> {
        val out = mutableListOf(stackHeight())
        compose.runOnUiThread(change)
        repeat(count) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            out += stackHeight()
            onFrame()
        }
        println("QueueDeckHeight: ${out.joinToString(" ") { it.toInt().toString() }}")
        return out
    }

    /** From [heights]' first to its last, never back the other way (a pixel's rounding aside), and no frame's step over 8 dp. */
    private fun assertSmooth(heights: List<Float>, closing: Boolean) {
        val steps = heights.zipWithNext { a, b -> b - a }
        val log = heights.joinToString(" ") { it.toInt().toString() }
        steps.forEachIndexed { i, step ->
            assertWithMessage("frame ${i + 1} moved ${step / dp} dp: $log").that(kotlin.math.abs(step)).isAtMost(8 * dp)
            if (closing) assertWithMessage("frame ${i + 1} grew by $step px: $log").that(step).isAtMost(1f)
            else assertWithMessage("frame ${i + 1} shrank by ${-step} px: $log").that(step).isAtLeast(-1f)
        }
    }

    @Test
    fun `a row the reader removes closes the card up over frames, rather than in the frame it went`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures")
        scene.show(motion)
        val heights = heights(40) {
            // As the card's trash glyph does: nothing flies from a row the reader took off.
            scene.flights.dismiss("q-1")
            scene.queue.removeAll { it.id == "q-1" }
        }
        assertThat(heights.last()).isLessThan(heights.first() - 30 * dp)
        assertSmooth(heights, closing = true)
        assertThat(motion.flight).isNull()
    }

    @Test
    fun `a row taken back to edit from an opened long queue closes the card up over frames too`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures", "Then rerun the flaky suite", "Then write it up")
        scene.stacked = false
        scene.show(motion)
        val heights = heights(40) {
            scene.flights.dismiss("q-1")
            scene.queue.removeAll { it.id == "q-1" }
        }
        assertThat(heights.last()).isLessThan(heights.first() - 30 * dp)
        assertSmooth(heights, closing = true)
    }

    /**
     * A row the run takes lifts off into its bubble, filed in the same frame, and folds away over [QueueExitMillis] on
     * the fold's curve, the one the transcript glides its bubble in on, so the two move as one rather than the card
     * giving its room up in a frame.
     */
    @Test
    fun `a delivery folds the row away over frames, the copy lifting off the leaving row`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures")
        scene.show(motion)
        val leaving = checkNotNull(scene.flights.anchor("q-1").surface).boundsInWindow()
        var flight: SendFlight? = null
        val heights = heights(40, onFrame = { flight = flight ?: motion.flight }) {
            scene.queue.removeAll { it.id == "q-1" }
            scene.messages += UserMessage("u-2", "Run the migration first")
        }
        val one = heights.last()
        assertThat(one).isLessThan(heights.first() - 30 * dp)
        val drop = heights.first() - one
        val steps = heights.zipWithNext { a, b -> a - b }
        assertWithMessage(heights.joinToString(" ") { it.toInt().toString() }).that(steps.count { it > 1f }).isAtLeast(4)
        assertThat(steps.max()).isAtMost(drop / 2)
        assertSmooth(heights, closing = true)
        assertThat(checkNotNull(flight).takeoff.composer).isEqualTo(leaving)
        scene.frames(SendMotion.FlightMillis + 400L)
        assertThat(motion.flight).isNull()
        assertThat(stackHeight()).isEqualTo(one)
    }

    @Test
    fun `a send into a one-row list opens the card over frames, as before`() {
        scene.queue += queued("Run the migration first")
        scene.composerText = "Then reseed the fixtures"
        scene.show(motion)
        val heights = heights(40) {
            motion.depart(scene.anchor.takeoff(), "Then reseed the fixtures", excluded = setOf("q-1"), landing = SendLanding.Queue)
            scene.queue += QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 0L)
            scene.composerText = ""
        }
        assertThat(heights.last()).isGreaterThan(heights.first() + 30 * dp)
        // The cards' own spring still carries the card, a touch past its rest and back.
        heights.zipWithNext { a, b -> assertThat(kotlin.math.abs(b - a)).isAtMost(8 * dp) }
    }

    @Test
    fun `the third send stacks the list into a deck without the card dipping below where it rests`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures")
        val text = "Then rerun the flaky suite on the emulator matrix"
        scene.composerText = text
        scene.show(motion)
        val heights = heights(60) {
            motion.depart(scene.anchor.takeoff(), text, excluded = setOf("q-1", "q-2"), landing = SendLanding.Queue)
            scene.queue += QueuedFollowUp("q-3", text, queuedAtMillis = 0L)
            scene.composerText = ""
        }
        val rest = heights.last()
        assertThat(rest).isLessThan(heights.first())
        assertSmooth(heights, closing = true)
        assertWithMessage(heights.joinToString(" ") { it.toInt().toString() }).that(heights.min()).isAtLeast(rest - 1f)
    }
}
