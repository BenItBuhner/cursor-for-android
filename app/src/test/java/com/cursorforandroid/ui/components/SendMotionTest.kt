package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The send's flight, frame by frame on the test clock: the text lifted off the composer at the tap, held over it
 * until the message's bubble is laid out, flown there in [SendMotion.FlightMillis] with the bubble undrawn until the
 * hand-over, and gone; faded where it stood when no bubble comes; not there at all with animations off. The bubbles
 * it flies over are not recomposed for any of its frames.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SendMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Bubble(val id: String, val text: String)

    private val anchor = ComposerAnchor()
    private var composerText by mutableStateOf("Ship it")
    private val bubbles = mutableStateListOf(Bubble("m-1", "Earlier"))
    private var bubbleCompositions = 0

    @Composable
    private fun Harness(motion: SendMotion) {
        CursorTheme(mode = ThemeMode.Dark) {
            SendMotionHost(motion) {
                Column(Modifier.fillMaxSize()) {
                    for (bubble in bubbles) BubbleView(motion, bubble)
                    Box(Modifier.padding(16.dp).width(300.dp).onPlaced { anchor.surface = it }) {
                        var layout: TextLayoutResult? = null
                        SideEffect { anchor.layout = { layout } }
                        BasicText(
                            composerText,
                            Modifier.padding(12.dp).onPlaced { anchor.field = it }.sendSource(motion, anchor),
                            onTextLayout = { layout = it },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun BubbleView(motion: SendMotion, bubble: Bubble) {
        SideEffect { bubbleCompositions++ }
        Box(Modifier.padding(8.dp).sendTarget(motion, bubble.id, bubble.text, SendTargetPart.Surface)) {
            BasicText(bubble.text, Modifier.padding(10.dp).sendTarget(motion, bubble.id, bubble.text, SendTargetPart.Text))
        }
    }

    private fun show(motion: SendMotion) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Harness(motion) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun frames(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    /** The tap, as the screens make it: the text lifted off, the composer emptied, the bubble staged a frame on. */
    private fun send(motion: SendMotion, text: String = "Ship it", id: String = "m-2"): SendFlight? {
        val before = bubbles.mapTo(HashSet()) { it.id }
        var flight: SendFlight? = null
        compose.runOnUiThread {
            flight = motion.depart(anchor.takeoff(), text, before)
            composerText = ""
        }
        frames(16)
        compose.runOnUiThread { bubbles += Bubble(id, text) }
        compose.waitForIdle()
        return flight
    }

    @Test
    fun `the text flies into its bubble, which is drawn only from the hand-over, and the flight is gone after`() {
        val motion = SendMotion(animatorsEnabled = { true })
        show(motion)
        val flight = checkNotNull(send(motion))
        assertThat(motion.flight).isSameInstanceAs(flight)

        frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetPlaced).isTrue()
        // Midway: the bubble is the flight's, undrawn; the one before it is drawn as ever.
        frames(SendMotion.FlightMillis / 3L)
        assertThat(flight.progress.value).isIn(com.google.common.collect.Range.open(0f, SendMotion.HandOff))
        assertThat(motion.hides("m-2", "Ship it")).isTrue()
        assertThat(motion.hides("m-1", "Earlier")).isFalse()
        // The emptied composer's placeholder is held back until the text has left it.
        assertThat(motion.placeholderAlpha(anchor)).isLessThan(1f)

        frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        assertThat(flight.progress.value).isEqualTo(1f)
        assertThat(motion.hides("m-2", "Ship it")).isFalse()
        assertThat(motion.placeholderAlpha(anchor)).isEqualTo(1f)
    }

    @Test
    fun `the bubbles are not recomposed for any frame of the flight`() {
        val motion = SendMotion(animatorsEnabled = { true })
        show(motion)
        send(motion)
        frames(48)
        val settled = bubbleCompositions
        frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        assertThat(bubbleCompositions).isEqualTo(settled)
    }

    @Test
    fun `an earlier message saying the same is not the flight's bubble, and with no bubble the text fades where it stood`() {
        val motion = SendMotion(animatorsEnabled = { true })
        bubbles.clear()
        bubbles += Bubble("m-1", "Ship it")
        show(motion)
        var flight: SendFlight? = null
        compose.runOnUiThread {
            flight = motion.depart(anchor.takeoff(), "Ship it", excluded = setOf("m-1"))
            composerText = ""
        }
        compose.waitForIdle()
        frames(SendMotion.HoldMillis / 2)
        assertThat(flight!!.phase).isEqualTo(SendFlight.Phase.Holding)
        assertThat(flight!!.targetPlaced).isFalse()
        assertThat(motion.hides("m-1", "Ship it")).isFalse()
        assertThat(motion.placeholderAlpha(anchor)).isEqualTo(0f)

        frames(SendMotion.HoldMillis / 2 + 48)
        assertThat(flight!!.phase).isEqualTo(SendFlight.Phase.Fading)
        frames(SendMotion.FadeMillis.toLong() + 48)
        assertThat(motion.flight).isNull()
        assertThat(flight!!.progress.value).isEqualTo(0f)
    }

    @Test
    fun `nothing flies with animations off, from an empty composer, or from one not laid out`() {
        show(SendMotion(animatorsEnabled = { true }))
        val off = SendMotion(animatorsEnabled = { false })
        compose.runOnUiThread {
            assertThat(off.depart(anchor.takeoff(), "Ship it")).isNull()
            assertThat(off.flight).isNull()
            val on = SendMotion(animatorsEnabled = { true })
            assertThat(on.depart(anchor.takeoff(), "   ")).isNull()
            assertThat(on.depart(null, "Ship it")).isNull()
            assertThat(ComposerAnchor().takeoff()).isNull()
        }
    }

    @Test
    fun `the composer's own copy of the lifted text is undrawn until it empties`() {
        val motion = SendMotion(animatorsEnabled = { true })
        show(motion)
        compose.runOnUiThread { motion.depart(anchor.takeoff(), "Ship it") }
        // A composer that empties a frame late (New Chat packs its launch first) would otherwise draw the text twice.
        assertThat(motion.fieldAlpha(anchor)).isEqualTo(0f)
        compose.runOnUiThread { composerText = "Next" }
        frames(32)
        assertThat(motion.fieldAlpha(anchor)).isEqualTo(1f)
    }

    @Test
    fun `a New Chat flight is bound to the chat it started, whose composer glides in from it, and a refused send takes it down`() {
        val motion = SendMotion(animatorsEnabled = { true })
        show(motion)
        compose.runOnUiThread {
            val flight = motion.depart(anchor.takeoff(), "Ship it", holdMillis = SendMotion.LaunchHoldMillis)
            assertThat(motion.arrivalFor("bc-new")).isNull()
            motion.bind(flight, "bc-new")
            assertThat(motion.arrivalFor("bc-new")).isEqualTo(flight!!.takeoff.composer)
            assertThat(motion.arrivalFor("bc-other")).isNull()

            val refused = motion.depart(anchor.takeoff(), "Ship it again")
            motion.cancel(refused)
            assertThat(motion.flight).isNull()
            // A stale flight is not cancelled by name.
            motion.cancel(flight)
            assertThat(motion.flight).isNull()
        }
    }
}
