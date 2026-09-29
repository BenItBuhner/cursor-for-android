package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * A sent message's bubble coming up from its sending fade, sampled off the drawn window frame by frame on a held clock:
 * how bright the bubble's words are, as a share of the way from the page to the words at full strength. No step between
 * two frames jumps from the sending look to the sent one — when the bubble is filed where it stands, when the server's
 * copy takes the sending bubble's place in the same frame (a new key in the list, a row composed afresh), when the copy
 * comes in halfway through the fade, and when the bubble is where a queued message the run took landed — filed after
 * the flight, or while it was still in the air, when the bubble waits at its sending look until the copy is gone.
 * Without the screen's fades the swap is the snap Bennett saw, and a fade run under the copy lands it at full strength;
 * the sampling is shown to catch both. A bubble scrolled off and
 * back, or recomposed, does not fade again; with animations off nothing fades.
 *
 * With `SENT_FADE_DEMO_DIR` set, [demoBefore] and [demoAfter] write their frames there as PNGs (the demo's strip and clip).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SentFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var items by mutableStateOf<List<TimelineItem>>(emptyList())
    private var fades: SentFades? = null
    private var animators = true
    private val listState = LazyListState()

    @Before
    fun setUp() {
        // Native graphics look java.nio's buffers up once, on whichever thread first needs them: made here, first.
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    /** The transcript's list as the screen lays it out — keyed by the items' ids — with the screen's fades or without. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(initial: List<TimelineItem>, withFades: Boolean = true) {
        items = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val sent = if (withFades) rememberSentFades("chat", animatorsEnabled = { animators }) else null
            sent?.look(remember(items) { items.filterIsInstance<UserMessage>() })
            fades = sent
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls(), LocalSentFades provides sent) {
                    Box(Modifier.testTag(FRAME).fillMaxWidth().height(360.dp).background(CursorTheme.colors.canvas)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(TranscriptItemSpacing),
                        ) {
                            items(items, key = { it.id }) { TimelineItemView(it) }
                        }
                    }
                }
            }
        }
        frames(64)
    }

    private fun frame() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun frames(millis: Long) = repeat((millis / 16).toInt()) { frame() }

    private fun window(): Bitmap {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** Where the bubble saying [text] draws its words now, in window pixels. */
    private fun textBox(text: String): Rect = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInWindow }.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }

    /** The darkest and the brightest grey level under the words of the bubble saying [text], drawn now: its page and its words. */
    private fun levels(text: String): Pair<Float, Float> {
        val bitmap = window()
        val box = textBox(text)
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(bitmap.width)) {
                val c = bitmap.getPixel(x, y)
                val grey = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3f
                if (grey < lo) lo = grey
                if (grey > hi) hi = grey
            }
        }
        return lo to hi
    }

    /**
     * The bubble saying [text] on the frame before the first change and on each of the next [count] frames, each of
     * [changes] made on the UI thread before the frame of its index: its opacity, as a share of the way from the page
     * to its words once settled at full strength.
     */
    private fun sample(text: String, count: Int, changes: Map<Int, () -> Unit>): List<Float> {
        val raw = ArrayList<Float>()
        raw += levels(text).second
        for (i in 0 until count) {
            changes[i]?.let(compose::runOnUiThread)
            frame()
            raw += levels(text).second
        }
        frames(480)
        val (page, full) = levels(text)
        return raw.map { ((it - page) / (full - page)).coerceIn(0f, 1.2f) }
    }

    private fun assertSmoothFromSendingToSent(alphas: List<Float>) {
        assertThat(alphas.first()).isWithin(0.1f).of(PendingMessageAlpha)
        assertThat(alphas.last()).isWithin(0.04f).of(1f)
        val steps = alphas.zipWithNext { a, b -> b - a }
        // FastOutSlowIn over 240 ms moves at most about a sixth of the way in a 16 ms frame; a snap moves all of it.
        assertThat(steps.max()).isAtMost(MAX_STEP)
        assertThat(steps.min()).isAtLeast(-0.03f)
        // And it is a fade: several frames of it between the two looks.
        assertThat(alphas.count { it in 0.6f..0.94f }).isAtLeast(4)
    }

    private val prompt = "Ship the fix behind the flag and tell me when CI is green."
    private val sending = UserMessage("local-1", prompt, isPending = true)
    private val filed = UserMessage("local-1", prompt)
    private val serverCopy = UserMessage("run-1-msg", prompt)
    private val earlier = UserMessage("u-0", "Put the new onboarding behind a flag.")
    private val reply = AssistantMessage("a-0", "Done: the flag defaults off in release builds.")

    @Test
    fun `a sending bubble filed where it stands fades up to full strength`() {
        show(listOf(earlier, reply, sending))
        assertSmoothFromSendingToSent(sample(prompt, 24, mapOf(0 to { items = listOf(earlier, reply, filed) })))
    }

    @Test
    fun `the server's copy taking the sending bubble's place in the same frame carries the fade, not a snap`() {
        show(listOf(earlier, reply, sending))
        assertSmoothFromSendingToSent(sample(prompt, 24, mapOf(0 to { items = listOf(earlier, reply, serverCopy) })))
        assertThat(fades!!.fading("run-1-msg")).isFalse()
    }

    @Test
    fun `the server's copy arriving halfway through the fade carries on from where it was`() {
        show(listOf(earlier, reply, sending))
        val alphas = sample(
            prompt,
            24,
            mapOf(
                0 to { items = listOf(earlier, reply, filed) },
                7 to { items = listOf(earlier, reply, serverCopy) },
            ),
        )
        assertSmoothFromSendingToSent(alphas)
    }

    @Test
    fun `without the screen's fades the swap for the server's copy snaps, which the sampling catches`() {
        show(listOf(earlier, reply, sending), withFades = false)
        val alphas = sample(prompt, 24, mapOf(0 to { items = listOf(earlier, reply, serverCopy) }))
        assertThat(alphas.zipWithNext { a, b -> b - a }.max()).isGreaterThan(0.4f)
    }

    @Test
    fun `a sent bubble scrolled off and back, or recomposed, is drawn whole with no fade again`() {
        val filler = (1..12).map { UserMessage("f-$it", "Filler message number $it, which takes up a line or two of the list.") }
        show(listOf(sending) + filler)
        compose.runOnUiThread { items = listOf(filed) + filler }
        frames(480)
        assertThat(fades!!.fading("local-1")).isFalse()
        val (page, full) = levels(prompt)
        // Off and back: the list rebuilds the row (and forgets its item animations, as requestScrollToItem does).
        compose.runOnUiThread { listState.requestScrollToItem(10) }
        frames(64)
        assertThat(compose.onAllNodesWithText(prompt, useUnmergedTree = true).fetchSemanticsNodes()).isEmpty()
        compose.runOnUiThread { listState.requestScrollToItem(0) }
        repeat(8) { i ->
            // And a new list of the same bubbles every other frame: a look that changes nothing.
            if (i % 2 == 1) compose.runOnUiThread { items = items.toList() }
            frame()
            val (p, hi) = levels(prompt)
            assertThat(p).isWithin(2f).of(page)
            assertThat(hi).isWithin(2f).of(full)
        }
        assertThat(fades!!.fading("local-1")).isFalse()
    }

    @Test
    fun `with animations off the filed bubble is at full strength at once`() {
        animators = false
        show(listOf(earlier, reply, sending))
        val alphas = sample(prompt, 12, mapOf(0 to { items = listOf(earlier, reply, serverCopy) }))
        assertThat(fades!!.fading("run-1-msg")).isFalse()
        // The sending look, the frame that hears of the change, then whole.
        assertThat(alphas.drop(2).min()).isAtLeast(0.97f)
    }

    // ---- A queued message the run took, landing on its bubble ----

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val queued = "Then reseed the fixtures"

    private fun deliverQueued() {
        scene.queue += QueuedFollowUp("q-1", queued, queuedAtMillis = 0L)
        scene.show(motion, withSentFades = true)
        scene.deliverSending("q-1", "local-q")
    }

    @Test
    fun `a queued message's bubble fades up from the look its flight landed at, over the server's copy too`() {
        deliverQueued()
        val flight = checkNotNull(motion.flight)
        scene.frames(SendMotion.FlightMillis + 160L)
        assertThat(flight.targetId).isEqualTo("local-q")
        assertThat(motion.flights).isEmpty()
        // Landed at its sending look, filed where it stands, then the server's copy 96 ms into the fade: one fade.
        val alphas = sample(
            queued,
            24,
            mapOf(
                0 to { scene.replace("local-q", UserMessage("local-q", queued)) },
                6 to { scene.replace("local-q", UserMessage("run-q-msg", queued)) },
            ),
        )
        assertSmoothFromSendingToSent(alphas)
    }

    /**
     * The queued message filed as [confirmed] 48 ms into its flight — the account's answer beating the copy to the
     * bubble, as it mostly does — then on until the flight is done: every frame of it, the bubble under the copy and
     * the look the copy heads for are still the sending look.
     */
    private fun confirmMidFlight(confirmed: UserMessage) {
        deliverQueued()
        val flight = checkNotNull(motion.flight)
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(checkNotNull(flight.target).look.fade).isWithin(0.001f).of(PendingMessageAlpha)
        scene.replace("local-q", confirmed)
        var left = 60
        while (motion.flights.isNotEmpty() && left-- > 0) {
            frame()
            flight.target?.let { assertThat(it.look.fade).isWithin(0.001f).of(PendingMessageAlpha) }
            assertThat(scene.sentFades!!.alpha(confirmed)).isWithin(0.001f).of(PendingMessageAlpha)
        }
        assertThat(motion.flights).isEmpty()
    }

    @Test
    fun `a queued message filed mid-flight lands at its sending look, then fades up to full strength, not snaps`() {
        confirmMidFlight(UserMessage("local-q", queued))
        assertSmoothFromSendingToSent(sample(queued, 24, emptyMap()))
        assertThat(scene.sentFades!!.fading("local-q")).isFalse()
    }

    @Test
    fun `the server's copy taking the queued bubble's place mid-flight waits out the flight too, then fades up`() {
        confirmMidFlight(UserMessage("run-q-msg", queued))
        assertSmoothFromSendingToSent(sample(queued, 24, emptyMap()))
        assertThat(scene.sentFades!!.fading("run-q-msg")).isFalse()
    }

    @Test
    fun `a fade that does not wait out the flight is spent under the copy, which the sampling catches`() {
        scene.sentFadesSeeFlights = false
        deliverQueued()
        scene.frames(48)
        scene.replace("local-q", UserMessage("local-q", queued))
        scene.frames(SendMotion.FlightMillis.toLong())
        assertThat(motion.flights).isEmpty()
        // The copy gone, the bubble it leaves is at full strength already: the sending look was never seen.
        assertThat(sample(queued, 4, emptyMap()).first()).isAtLeast(0.9f)
    }

    // ---- The demo's frames ----

    @Test
    fun demoBefore() = demo("before", withFades = false)

    @Test
    fun demoAfter() = demo("after", withFades = true)

    /** The sending bubble swapped for the server's copy, every frame from a moment before to well after, into `SENT_FADE_DEMO_DIR`/[name]. */
    private fun demo(name: String, withFades: Boolean) {
        val out = System.getenv("SENT_FADE_DEMO_DIR")?.let { File(it, name).apply { mkdirs() } } ?: return
        show(listOf(earlier, reply, sending), withFades)
        var index = 0
        fun write() = window().let { b -> File(out, "frame_%03d.png".format(index++)).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        repeat(8) { write(); frame() }
        compose.runOnUiThread { items = listOf(earlier, reply, serverCopy) }
        repeat(28) { frame(); write() }
    }

    @Test
    fun demoFlightBefore() = demoFlight("flight-before", seesFlights = false)

    @Test
    fun demoFlightAfter() = demoFlight("flight-after", seesFlights = true)

    /**
     * A queued message the run took, filed 48 ms into its flight, every 16 ms frame from half a second before the run
     * takes it to a second after it lands, into `SENT_FADE_DEMO_DIR`/[name]: with fades run under the copy, or waiting it out.
     */
    private fun demoFlight(name: String, seesFlights: Boolean) {
        val out = System.getenv("SENT_FADE_DEMO_DIR")?.let { File(it, name).apply { mkdirs() } } ?: return
        scene.sentFadesSeeFlights = seesFlights
        scene.queue += listOf(
            QueuedFollowUp("q-1", queued, queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then open the PR as a draft", queuedAtMillis = 0L),
        )
        scene.show(motion, withSentFades = true)
        var index = 0
        fun write() = scene.drawTo(File(out, "frame_%03d.png".format(index++)))
        repeat(30) { write(); frame() }
        compose.runOnUiThread {
            scene.queue.removeAll { it.id == "q-1" }
            scene.messages += UserMessage("local-q", queued, isPending = true)
        }
        repeat(3) { write(); frame() }
        compose.runOnUiThread { scene.messages[scene.messages.indexOfFirst { it.id == "local-q" }] = UserMessage("local-q", queued) }
        repeat(90) { write(); frame() }
    }

    private companion object {
        const val FRAME = "sent_fade_frame"
        const val MAX_STEP = 0.22f
    }
}
