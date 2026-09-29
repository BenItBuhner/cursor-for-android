package com.cursorforandroid.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.conversation.PendingMessageAlpha
import com.cursorforandroid.ui.conversation.QueueMotionScene
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * A queued message the run took, on its way into its bubble while the account files it, on a stopped clock: landed
 * unfiled at its sending look; filed with the copy still in the air, which heads for the sending look; at the
 * hand-over, the bubble drawn at its sending look under the copy leaving it; partway up its fade once the copy is gone,
 * and done; and the server's copy taking the bubble's place mid-flight, partway up the same fade. Same device
 * qualifiers as [AppScreenshotTest]; written to `screenshots/`, which CI compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PendingMessageFadeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val queued = "Then reseed the fixtures and rerun the flaky suite"

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun capture(name: String) = compose.onNodeWithTag(QueueMotionScene.Frame).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())

    /** The run taking the queued row: its flight to the sending bubble `local-q` under way. */
    private fun deliver(): SendFlight {
        scene.queue += listOf(
            QueuedFollowUp("q-1", queued, queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then open the PR as a draft", queuedAtMillis = 0L),
        )
        scene.show(motion, withSentFades = true)
        scene.deliverSending("q-1", "local-q")
        val flight = checkNotNull(motion.flight)
        while (flight.phase != SendFlight.Phase.Flying) scene.frames(16)
        return flight
    }

    /** Filed as [confirmed] with the flight in the air, then held exactly [into] its way (see QueueMotionScreenshotTest). */
    private fun confirmAndCatch(confirmed: UserMessage, into: Float) {
        val flight = deliver()
        scene.replace("local-q", confirmed)
        scene.frame()
        MainScope().launch { flight.progress.snapTo(into) }
        scene.frame()
        scene.frames(800)
        assertThat(flight.progress.value).isEqualTo(into)
        assertThat(scene.sentFades!!.alpha(confirmed)).isEqualTo(PendingMessageAlpha)
    }

    /** Filed as [confirmed] 48 ms into the flight, then on until it is done and [intoFade] ms into the fade. */
    private fun confirmAndLand(confirmed: UserMessage, intoFade: Long) {
        deliver()
        scene.frames(48)
        scene.replace("local-q", confirmed)
        while (motion.flights.isNotEmpty()) scene.frames(16)
        scene.frames(intoFade)
    }

    @Test
    fun landedSending() {
        deliver()
        while (motion.flights.isNotEmpty()) scene.frames(16)
        scene.frames(800)
        capture("910_pending_fade_landed_sending")
    }

    @Test
    fun confirmedMidFlight() {
        confirmAndCatch(UserMessage("local-q", queued), into = 0.4f)
        capture("911_pending_fade_confirmed_mid_flight")
    }

    @Test
    fun confirmedAtHandOff() {
        confirmAndCatch(UserMessage("local-q", queued), into = 0.92f)
        capture("912_pending_fade_confirmed_at_hand_off")
    }

    @Test
    fun fadingUp() {
        confirmAndLand(UserMessage("local-q", queued), intoFade = 96)
        capture("913_pending_fade_fading_up")
    }

    @Test
    fun faded() {
        confirmAndLand(UserMessage("local-q", queued), intoFade = 480)
        capture("914_pending_fade_faded")
    }

    @Test
    fun serverCopyMidFlightFadingUp() {
        confirmAndLand(UserMessage("run-q-msg", queued), intoFade = 96)
        capture("915_pending_fade_server_copy_fading_up")
    }
}
