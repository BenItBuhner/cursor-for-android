package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerPhase
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * A queued message steered into the running turn, filmed a 16 ms frame at a time on a held clock: the chat's foot as
 * the conversation screen lays it out and stands its cards, the reader's steer ("Steering…"), the account taking it
 * ("Steered"), and the transcript filing its bubble as the card folds away. Runs only with `STEER_FILM_DIR` set, each
 * frame written there as a PNG with a CSV of where the card and the anchor bubble stood (the steer demo's frames); the
 * account's steering needs a signed-in Extended account, which the whole screen's demo backend is not.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SteerFilmTest {

    private companion object {
        const val Anchor = "Fix the double disk read, then profile again."
        const val Steered = "Also check the release build, not just debug"
        const val Behind = "Then write up what changed for the release notes"
        const val Transcript = "steer-transcript"
        const val FollowupId = "f-steer"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = System.getenv("STEER_FILM_DIR")?.let(::File)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val flights = QueueFlights()

    private var device by mutableStateOf(listOf(QueuedFollowUp("q-1", Steered, queuedAtMillis = 0L), QueuedFollowUp("q-2", Behind, queuedAtMillis = 0L)))

    private var presented by mutableStateOf(
        Frame(
            (0 until 10).map { UserMessage("u-e$it", "Earlier turn $it: set up a baseline trace on the Pixel 7 profile and note where the time goes.") } +
                UserMessage("u-1", Anchor),
            QueuePlacement.NONE,
        ),
    )

    private class Frame(val items: List<TimelineItem>, val placement: QueuePlacement)

    @Before
    fun setUp() {
        assumeTrue(dir != null)
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                            val frame = presented
                            val handover = remember { QueueHandover() }
                            val queue = remember(device, frame) { handover.standing(SteeredCards.standing(device, frame.placement), frame.placement.filedQueueIds) { emptySet() } }
                            SideEffect { handover.composed(queue) }
                            val listState = rememberLazyListState()
                            val scroll = rememberTranscriptScroll(listState, "steer")
                            val scope = rememberCoroutineScope()
                            val handoversBefore = flights.handovers
                            LaunchedEffect(frame.items.size, frame.items.lastOrNull()?.id) {
                                snapshotFlow { scroll.isJumping }.first { !it }
                                if (flights.handovers != handoversBefore) scroll.settleToNewest(scope) else listState.requestScrollToItem(0)
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
                                        QueuedFollowUpCard(queue[index], index + 1, queue.size, emptyMap(), {}, {}, {}, flights, face, steers = true)
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(96.dp).background(CursorTheme.colors.elevated))
                            }
                        }
                    }
                }
            }
        }
        repeat(60) { step() }
    }

    private fun step() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun draw(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun where(): String {
        val stack = compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val anchor = compose.onAllNodes(hasText(Anchor) and hasAnyAncestor(hasTestTag(Transcript)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        return "${stack?.top},${stack?.height},${anchor?.top}"
    }

    /** The reader steers the head card at frame 8, the account takes it at 40, and the transcript files it at 72. */
    @Test
    fun steer() {
        show()
        val out = File(dir, "steer").apply { deleteRecursively(); mkdirs() }
        val changes = mapOf<Int, () -> Unit>(
            8 to {
                device = device.map { if (it.id == "q-1") it.copy(steer = SteerPhase.STEERING, steerFollowupId = FollowupId) else it }
                presented = Frame(presented.items, QueuePlacement(waiting = listOf(PendingFollowup(FollowupId, Steered))))
            },
            40 to { device = device.map { if (it.id == "q-1") it.copy(steer = SteerPhase.STEERED) else it } },
            72 to { presented = Frame(presented.items + UserMessage("u-steered", Steered), QueuePlacement.NONE) },
        )
        val log = StringBuilder("frame,stackTop,stackHeight,anchorTop\n")
        for (f in 0..150) {
            changes[f]?.let { compose.runOnUiThread(it) }
            step()
            log.append(f).append(',').append(where()).append('\n')
            draw(File(out, "f_%04d.png".format(f)))
        }
        File(dir, "steer.csv").writeText(log.toString())
    }
}
