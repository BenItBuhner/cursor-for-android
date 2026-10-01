package com.cursorforandroid.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PendingAttachment
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.conversation.QueueMotionScene
import com.cursorforandroid.ui.conversation.QueueStackHandleTag
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
 * The send's flight with the queue card at either end, caught mid-motion on a stopped clock: a message sent while the
 * agent works on its way from the composer into its row, with pictures and a spec shrinking onto the row's tiles; and
 * a queued row the run took lifting off the card, then on its way into its bubble. Then the same on the account's card
 * (Extended mode, what a phone signed in runs), whose rows draw their tiles from this device's copies of what a message
 * queued from here carries, and the account's word on it (plain tiles, the file's name) for one queued elsewhere. Then
 * a long queue as a deck (720 on): at rest on the account's card, held halfway open, opened into the list, the front
 * card lifting off as the next comes forward, the third send stacking the list, and a send sinking into a deck that
 * stands. Same device qualifiers as [AppScreenshotTest]; written to `screenshots/`, which CI compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueMotionScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun capture(name: String) = compose.onNodeWithTag(QueueMotionScene.Frame).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())

    /**
     * On to the flight's takeoff, then held exactly [into] its way: the frames its tween lands on depend on what ran in
     * the JVM before it, so the tween is stopped and the flight pinned there rather than caught on the nearest frame.
     */
    private fun catchAt(flight: SendFlight, into: Float) {
        while (flight.phase != SendFlight.Phase.Flying) scene.frames(16)
        MainScope().launch { flight.progress.snapTo(into) }
        scene.frame()
        // The queue's springs settle under the pinned flight, so the rows stand where the same frames always leave them.
        scene.frames(800)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.progress.value).isEqualTo(into)
    }

    /** The stack's every change of place held [fraction] of its way, wherever the clock stands. */
    private fun holdStackAt(fraction: Float) {
        scene.stackSpec = tween(durationMillis = 1_000_000, easing = Easing { t -> if (t >= 1f) 1f else fraction })
    }

    private fun queued(vararg texts: String) = texts.mapIndexed { i, text -> QueuedFollowUp("q-${i + 1}", text, queuedAtMillis = 0L) }

    /** The tap while the agent works, and the flight caught [into] its way to the row. */
    private fun sendAndCatch(into: Float): SendFlight {
        val flight = checkNotNull(scene.sendQueued(motion, "q-2"))
        catchAt(flight, into)
        assertThat(flight.targetId).isEqualTo("q-2")
        return flight
    }

    @Test
    fun queuedSendMidFlight() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        scene.composerText = "Then reseed the fixtures and rerun the flaky suite"
        scene.show(motion)
        sendAndCatch(into = 0.15f)
        capture("690_queue_send_mid_flight")
    }

    @Test
    fun queuedSendAttachmentsMidFlight() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        scene.composerText = "Match the header to these, and follow the spec"
        scene.attach()
        scene.show(motion)
        sendAndCatch(into = 0.25f)
        capture("691_queue_send_attachments_mid_flight")
    }

    /** A queued row the run took, [into] its flight to the bubble filed with it. */
    private fun deliverAndCatch(into: Float) {
        scene.queue += listOf(
            QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 0L),
        )
        scene.show(motion)
        scene.deliver("q-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        catchAt(flight, into)
        assertThat(flight.targetId).isEqualTo("u-2")
    }

    @Test
    fun deliveryLiftingOff() {
        deliverAndCatch(into = 0.05f)
        capture("692_queue_delivery_lifting_off")
    }

    @Test
    fun deliveryMidFlight() {
        deliverAndCatch(into = 0.25f)
        capture("693_queue_delivery_mid_flight")
    }

    /**
     * Extended mode, what a phone signed in runs: the send onto the account's card, its two pictures and its spec
     * shrinking onto the row's own tiles (staged here, drawn from this device's copies).
     */
    private fun sendOnAccount(): SendFlight {
        scene.onAccount = true
        scene.account += PendingFollowup("a-0", "Run the migration first")
        scene.composerText = "Match the header to these, and follow the spec"
        scene.attach()
        scene.show(motion)
        return checkNotNull(scene.sendQueued(motion, "a-1", onAccount = true))
    }

    @Test
    fun accountSendAttachmentsMidFlight() {
        val flight = sendOnAccount()
        catchAt(flight, 0.25f)
        assertThat(flight.targetId).isEqualTo("a-1")
        capture("695_account_queue_send_attachments_mid_flight")
    }

    /** The row at rest once the account's list names the message in names and a count: its tiles still this device's pictures. */
    @Test
    fun accountRowTilesOnceListed() {
        sendOnAccount()
        scene.frames(SendMotion.FlightMillis + 200L)
        scene.accountTakes("a-1")
        scene.awaitTilePreviews()
        assertThat(motion.flights).isEmpty()
        // The row's ring crossfades back to its glyphs once the account lists it.
        scene.frames(300L)
        capture("696_account_queue_row_tiles")
    }

    @Test
    fun accountDeliveryWithAttachmentsMidFlight() {
        sendOnAccount()
        scene.frames(SendMotion.FlightMillis + 200L)
        scene.accountTakes("a-1")
        scene.awaitTilePreviews()
        scene.deliver("a-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        catchAt(flight, 0.25f)
        assertThat(flight.targetId).isEqualTo("u-2")
        capture("697_account_queue_delivery_attachments_mid_flight")
    }

    /** A message queued from another device: the account's word on what it carries, as plain tiles and the file's name. */
    @Test
    fun accountRowQueuedElsewhere() {
        scene.onAccount = true
        scene.account += PendingFollowup(
            "a-1",
            "Match the header to these, and follow the spec",
            files = listOf(PendingAttachment("Q3-header-spec.pdf", "application/pdf")),
            imageCount = 2,
        )
        scene.show(motion)
        capture("698_account_queue_row_queued_elsewhere")
    }

    @Test
    fun deliveryWithAttachmentsMidFlight() {
        scene.composerText = "Match the header to these, and follow the spec"
        val sent = scene.attach()
        scene.show(motion)
        scene.sendQueued(motion, "q-1")
        scene.frames(SendMotion.FlightMillis + 200L)
        assertThat(motion.flight).isNull()
        scene.deliver("q-1", bubble = "u-2", attachments = sent)
        catchAt(checkNotNull(motion.flight), 0.25f)
        capture("694_queue_delivery_attachments_mid_flight")
    }

    /** Extended mode's deck at rest: three queued on the account, the next in front with its pictures and spec as tiles. */
    @Test
    fun accountDeckAtRest() {
        scene.onAccount = true
        scene.account += PendingFollowup(
            "a-1",
            "Match the header to these, and follow the spec",
            files = listOf(PendingAttachment("Q3-header-spec.pdf", "application/pdf")),
            imageCount = 2,
        )
        scene.account += PendingFollowup("a-2", "Then rerun the flaky suite on the emulator matrix")
        scene.account += PendingFollowup("a-3", "Then write up what changed for the release notes")
        scene.show(motion)
        capture("720_queue_stack_account_deck")
    }

    private val long = arrayOf(
        "Run the migration first",
        "Then reseed the fixtures",
        "Then rerun the flaky suite on the emulator matrix",
        "Then write up what changed for the release notes",
    )

    /** The line over the deck tapped, every card held halfway from the deck to the list. */
    @Test
    fun deckOpeningMidway() {
        scene.queue += queued(*long)
        holdStackAt(0.5f)
        scene.show(motion)
        compose.onNodeWithTag(QueueStackHandleTag, useUnmergedTree = true).performClick()
        scene.frames(64)
        assertThat(scene.stacked).isFalse()
        capture("721_queue_stack_opening_midway")
    }

    @Test
    fun deckOpened() {
        scene.queue += queued(*long)
        scene.show(motion)
        compose.onNodeWithTag(QueueStackHandleTag, useUnmergedTree = true).performClick()
        scene.frames(900)
        capture("722_queue_stack_opened")
    }

    /** The run takes the front card: it lifts off into its bubble, the next held halfway to the front. */
    @Test
    fun deliveryFromDeckMidFlight() {
        scene.queue += queued(*long)
        holdStackAt(0.5f)
        scene.show(motion)
        scene.deliver("q-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        catchAt(flight, 0.25f)
        assertThat(flight.targetId).isEqualTo("u-2")
        capture("723_queue_stack_delivery_mid_flight")
    }

    /** A third send stacks the list: the copy on its way to the back of the deck as the two before it close up. */
    @Test
    fun thirdSendFormsDeck() {
        scene.queue += queued(*long.take(2).toTypedArray())
        scene.composerText = long[2]
        holdStackAt(0.5f)
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-3"))
        catchAt(flight, 0.5f)
        assertThat(flight.targetId).isEqualTo("q-3")
        capture("724_queue_stack_forming")
    }

    /** A send into a deck that stands: its copy sinking into the back, the front and the peeks where they were. */
    @Test
    fun sendIntoDeck() {
        scene.queue += queued(*long.take(3).toTypedArray())
        scene.composerText = long[3]
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-4"))
        catchAt(flight, 0.5f)
        assertThat(flight.targetId).isEqualTo("q-4")
        capture("725_queue_stack_send_into_deck")
    }
}
