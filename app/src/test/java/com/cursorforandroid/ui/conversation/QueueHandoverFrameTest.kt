package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer

/**
 * A queued message the run takes, handed from the card to the transcript one 16 ms frame at a time on a held clock:
 * the chat's foot as the conversation screen lays it out — the transcript's bottom-anchored list over the dock, the
 * device's queue card on the dock — with the screen's [QueueHandover] between the two publications the handover is
 * read from: the repository's queue, which drops the row once the server has the message, and the transcript's
 * presented frame, whose placement names the rows its bubbles were sent from ([QueuePlacement.filedQueueIds]). Each
 * publication is made at a frame the test picks, in either order, so the frames are the same on every run. The list
 * is taken to its newest row as the screen takes it: gliding with the card's fold when a card handed over in the frame
 * the rows came in ([TranscriptScroll.settleToNewest]), at once otherwise.
 *
 * The card's words leave it in the frame that files the bubble; in no frame are they on the card and in the bubble
 * both, and in every frame they are on one of the card, the delivery's flying copy or the bubble. The card folds away
 * as the transcript glides its bubble in, so the anchor bubble above them moves one way only, a little each frame, and
 * the card's height comes down the same way. Without the handover, the same publications move the anchor down with the
 * fold and then jump it back up. With `QUEUE_HANDOVER_RUNS` set, each case runs that many times; with
 * `QUEUE_HANDOVER_FILM_DIR`, each frame of the first run is written there as a PNG.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueHandoverFrameTest(private val run: Int) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "run {0}")
        fun runs(): List<Array<Any>> = (1..(System.getenv("QUEUE_HANDOVER_RUNS")?.toIntOrNull() ?: 1)).map { arrayOf(it) }

        private const val Anchor = "Profile the cold start and tell me where the time goes."
        private const val Delivered = "Also check the release build, not just debug"
        private const val Behind = "Then write up what changed for the release notes"
        private const val Transcript = "handover-transcript"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val motion = SendMotion(animatorsEnabled = { true })
    private val flights = QueueFlights()
    private val film = System.getenv("QUEUE_HANDOVER_FILM_DIR")?.takeIf { run == 1 }?.let(::File)

    /** The repository's queue as last published: the device's rows, the head's send out. */
    private var repoQueue by mutableStateOf(listOf(QueuedFollowUp("q-1", Delivered, queuedAtMillis = 0L, isSending = true), QueuedFollowUp("q-2", Behind, queuedAtMillis = 0L)))

    /** The transcript as the presenter last published it, with the placement it was decided with. */
    private var presented by mutableStateOf(
        Frame(
            // Enough turns to overflow the list, which then keeps its newest row on the dock, as a chat does.
            (0 until 12).map { UserMessage("u-e$it", "Earlier turn $it: set up a baseline trace on the Pixel 7 profile and note where the time goes.") } +
                UserMessage("u-1", Anchor),
            QueuePlacement.NONE,
        ),
    )

    /** The repository's placement now: ahead of [presented], as the chat's own state is ahead of its presentation. */
    private var filedNow: Set<String> = emptySet()

    private var gated = true

    private class Frame(val items: List<TimelineItem>, val placement: QueuePlacement)

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxWidth().height(720.dp).background(CursorTheme.colors.canvas)) {
                            val frame = presented
                            val handover = remember { QueueHandover() }
                            val device = repoQueue
                            // As ConversationScreen stands the device's cards; ungated, as it did before.
                            val queue = if (gated) remember(device, frame) { handover.standing(device, frame.placement.filedQueueIds) { filedNow } } else device
                            SideEffect { handover.composed(queue) }
                            val listState = rememberLazyListState()
                            val scroll = rememberTranscriptScroll(listState, "handover")
                            val scope = rememberCoroutineScope()
                            // As the screen takes a following list back to its newest row, which the keyed anchoring
                            // leaves past the edge: with the card's fold when a card handed over in this frame.
                            val handoversBefore = flights.handovers
                            LaunchedEffect(frame.items.size, frame.items.lastOrNull()?.id) {
                                snapshotFlow { scroll.isJumping }.first { !it }
                                if (gated && flights.handovers != handoversBefore) scroll.settleToNewest(scope) else listState.requestScrollToItem(0)
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(
                                    state = listState,
                                    reverseLayout = true,
                                    userScrollEnabled = false,
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag(Transcript),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(frame.items.asReversed(), key = { it.id }) { TimelineItemView(it) }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                QueueDeliveries(
                                    flights = flights,
                                    rows = LinkedHashMap<String, String>().apply { queue.forEach { put(it.id, it.previewText) } },
                                    transcript = remember(frame) { frame.items.mapTo(HashSet()) { it.id } },
                                    scrolledAway = { false },
                                )
                                if (queue.isNotEmpty()) {
                                    QueueStack(
                                        keys = queue.map { "device:${it.id}" },
                                        stacked = true,
                                        onStackedChange = {},
                                        modifier = Modifier.padding(bottom = 4.dp),
                                        animate = { true },
                                    ) { index, face ->
                                        QueuedFollowUpCard(queue[index], index + 1, queue.size, emptyMap(), {}, {}, {}, flights, face)
                                    }
                                }
                                // The composer's box, standing still under the card.
                                Box(Modifier.fillMaxWidth().height(96.dp))
                            }
                        }
                    }
                }
            }
        }
        repeat(40) { step() }
    }

    private fun step() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    /** One frame as drawn: whether the card, the delivery's copy and the bubble show the message, and where the anchor bubble stands. */
    private data class Shot(val card: Boolean, val copy: Boolean, val bubble: Boolean, val anchorTop: Float, val stackHeight: Float)

    private fun bounds(text: String, under: String): Rect? =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    private fun shot(): Shot {
        val list = compose.onAllNodes(hasTestTag(Transcript), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow
        val bubble = bounds(Delivered, Transcript)?.takeIf { it.height > 0f && it.top >= list.top && it.bottom <= list.bottom }
        val stack = compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInWindow
        return Shot(
            card = bounds(Delivered, QueueStackTag) != null,
            copy = motion.flights.any { it.text == Delivered },
            bubble = bubble != null,
            anchorTop = checkNotNull(bounds(Anchor, Transcript)) { "the anchor bubble is off the list" }.top,
            stackHeight = stack?.height ?: 0f,
        )
    }

    /**
     * Frames on from the settled chat, [changes] made on the UI thread at the frames they are keyed by: each frame's
     * shot, taken after its changes have been composed, laid out and drawn.
     */
    private fun filmHandover(frames: Int = 40, changes: Map<Int, () -> Unit>): List<Shot> {
        show()
        val out = mutableListOf(shot())
        film?.let { File(it, "f_%04d.png".format(0)).also(::draw) }
        for (f in 1..frames) {
            changes[f]?.let { compose.runOnUiThread(it) }
            step()
            out += shot()
            film?.let { File(it, "f_%04d.png".format(f)).also(::draw) }
        }
        println("QueueHandoverFrame run $run: " + log(out))
        return out
    }

    private fun draw(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The repository drops the row (the server took the message); its placement now files it. */
    private val queueDrops: () -> Unit = {
        filedNow = setOf("q-1")
        repoQueue = repoQueue.filterNot { it.id == "q-1" }
    }

    /** The presenter publishes the frame that files the bubble, the row it was sent from named in its placement. */
    private val bubbleFiled: () -> Unit = {
        filedNow = setOf("q-1")
        presented = Frame(presented.items + UserMessage("local-1", Delivered), QueuePlacement(filedQueueIds = setOf("q-1")))
    }

    private fun log(shots: List<Shot>) = shots.withIndex().joinToString(" ") { (i, s) ->
        "$i:${if (s.card) "C" else "-"}${if (s.copy) "F" else "-"}${if (s.bubble) "B" else "-"}@${s.anchorTop.toInt()}/${s.stackHeight.toInt()}"
    }

    /** Frame to frame moves of [value] over [shots], those over half a pixel. */
    private fun moves(shots: List<Shot>, value: (Shot) -> Float): List<Float> =
        shots.zipWithNext { a, b -> value(b) - value(a) }.filter { kotlin.math.abs(it) > 0.5f }

    /**
     * The card's words leave it in the frame [filed] (the one the bubble is filed in) and are shown in every frame, by
     * the card, the copy or the bubble, never by the card and the bubble both; the anchor above moves one way, no frame
     * taking more than a share of the whole move, and the card comes down the same way.
     */
    private fun assertOneSmoothMovement(shots: List<Shot>, filed: Int) {
        val log = log(shots)
        shots.forEachIndexed { i, s ->
            assertWithMessage("frame $i shows the message on the card and as the bubble: $log").that(s.card && s.bubble).isFalse()
            assertWithMessage("frame $i shows the message nowhere: $log").that(s.card || s.copy || s.bubble).isTrue()
        }
        assertWithMessage("the card's words leave it in the frame the bubble is filed: $log").that(shots.indexOfFirst { !it.card }).isEqualTo(filed)
        assertWithMessage("the bubble shows by the end: $log").that(shots.last().bubble).isTrue()
        val anchor = moves(shots) { it.anchorTop }
        assertWithMessage("the anchor moved: $log").that(anchor).isNotEmpty()
        val net = shots.last().anchorTop - shots.first().anchorTop
        // A pixel back is the window bounds' rounding, not a move.
        assertWithMessage("the anchor moves one way only: $anchor; $log").that(anchor.all { it * kotlin.math.sign(net) >= -1f }).isTrue()
        val total = kotlin.math.abs(net)
        assertWithMessage("no frame moves the anchor by more than half of its whole move ($total px): $anchor; $log").that(anchor.maxOf { kotlin.math.abs(it) }).isAtMost(total / 2f)
        val deck = moves(shots) { it.stackHeight }
        assertWithMessage("the card folds over several frames: $deck; $log").that(deck.size).isAtLeast(4)
        assertWithMessage("the card only comes down: $deck; $log").that(deck.all { it < 0f }).isTrue()
        val drop = shots.first().stackHeight - shots.last().stackHeight
        assertWithMessage("no frame drops the card by more than half of its fold ($drop px): $deck; $log").that(deck.maxOf { -it }).isAtMost(drop / 2f)
    }

    @Test
    fun `the queue dropping the row first, the card stands until the frame that files the bubble`() {
        assertOneSmoothMovement(filmHandover(changes = mapOf(3 to queueDrops, 9 to bubbleFiled)), filed = 9)
    }

    @Test
    fun `the bubble filed first, the card goes in that frame and the queue's later drop moves nothing`() {
        assertOneSmoothMovement(filmHandover(changes = mapOf(3 to bubbleFiled, 9 to queueDrops)), filed = 3)
    }

    @Test
    fun `both in one frame, one movement`() {
        assertOneSmoothMovement(filmHandover(changes = mapOf(3 to { queueDrops(); bubbleFiled() })), filed = 3)
    }

    @Test
    fun `the bubble filed the frame after the queue's drop, still one movement`() {
        assertOneSmoothMovement(filmHandover(changes = mapOf(3 to queueDrops, 4 to bubbleFiled)), filed = 4)
    }

    @Test
    fun `without the handover the same publications move the transcript down with the fold, then jump it back up`() {
        gated = false
        val shots = filmHandover(changes = mapOf(3 to queueDrops, 9 to bubbleFiled))
        val anchor = moves(shots) { it.anchorTop }
        val log = log(shots)
        assertWithMessage("the fold moves the transcript down: $anchor; $log").that(anchor.max()).isGreaterThan(2f)
        assertWithMessage("filing the bubble jumps it back up: $anchor; $log").that(anchor.min()).isLessThan(-20f)
        assertThat(shots.indexOfFirst { !it.card }).isEqualTo(3)
    }
}
