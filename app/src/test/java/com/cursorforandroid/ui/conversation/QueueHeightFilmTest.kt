package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant

/**
 * The queue card's height on the whole chat screen, filmed a 16 ms frame at a time on a held clock: a run under way
 * with two follow-ups queued; the run ends and the device's queue sends the first (the transcript over the dock
 * following the card as it closes up); and, from two queued, a third send stacking the list into a deck. Runs only
 * with `QUEUE_HEIGHT_FILM_DIR` set, each frame written there as a PNG (the queue-card-height demo's frames).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueHeightFilmTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = System.getenv("QUEUE_HEIGHT_FILM_DIR")?.let(::File)
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val agentId = "bc-queue-height"
    private val runId = "run-queue-height-1"
    private val startedAt = "2026-04-13T18:30:00.000Z"
    private val motion = SendMotion(animatorsEnabled = { true })
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        assumeTrue(dir != null)
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
        AppClock.nowMillis = { Instant.parse(startedAt).toEpochMilli() + 90_000L }
        api.addRunningAgent(agentId, "Speed up the cold start", runId, createdAt = startedAt)
        val earlier = listOf(
            "Set up a baseline trace on the Pixel 7 profile." to "Baseline recorded: 1.9 s to first frame, 2.4 s to the agent list. The trace is saved under traces/baseline.perfetto.",
            "Which startup work can wait until after the first frame?" to "The widget refresh, the notification channel setup and the model list prefetch. None of them is read before the list is on screen.",
            "Defer those three." to "Deferred behind the first frame with a single idle handler. They now run 180 ms after the list shows, off the critical path.",
        ).flatMapIndexed { i, (q, a) -> listOf(V0ConversationMessageDto("e-$i-u", "user_message", q), V0ConversationMessageDto("e-$i-a", "assistant_message", a)) }
        api.transcripts[agentId] = earlier + listOf(
            V0ConversationMessageDto("t-1-u", "user_message", "Profile the cold start and tell me where the time goes."),
            V0ConversationMessageDto("t-1-a", "assistant_message", "Most of the 1.9 s is spent before the first frame: the DI graph builds every repository eagerly (620 ms), the font cache is warmed on the main thread (310 ms), and the agent list is read from disk twice."),
            V0ConversationMessageDto("t-2-u", "user_message", "Make the graph lazy and move the font warm-up off the main thread."),
            V0ConversationMessageDto("t-2-a", "assistant_message", "Done. Repositories are now created on first use, and the font cache warms on a background dispatcher. Cold start is down to 1.2 s on the Pixel 7 profile; the double disk read is next."),
            V0ConversationMessageDto("$runId-u", "user_message", Anchor),
        )
        runBlocking {
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Tracing both reads of the agent list back to their callers."))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, streamer, isDemo = true),
        )
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private companion object {
        const val Anchor = "Fix the double disk read, then profile again."
    }

    private val queue get() = graph.followUps.state(agentId).value.queue

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SendMotionHost(motion) { ConversationScreen(graph, agentId, onBack = {}) }
                }
            }
        }
        pumpUntil("the chat and its queue card") {
            graph.conversations.state(agentId).value.runStatus == RunStatus.RUNNING &&
                compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        frames(1_500)
    }

    private fun step() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    private fun frames(millis: Long) = repeat((millis / 16).toInt()) { step() }

    /** Frames on, letting the repositories' own threads work between them, until [condition]. */
    private fun pumpUntil(what: String, condition: () -> Boolean) {
        repeat(3_000) {
            if (condition()) return
            step()
            Thread.sleep(5)
        }
        error("never saw $what")
    }

    private fun draw(): Bitmap {
        val root = compose.activity.window.decorView
        return try {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { compose.runOnUiThread { root.draw(Canvas(it)) } }
        } catch (_: IllegalArgumentException) {
            // A software canvas cannot draw a graphics layer's render node (a transcript row animating away); PixelCopy
            // renders the window through the hardware path instead.
            System.setProperty("robolectric.pixelCopyRenderMode", "hardware")
            val copy = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            var result = -1
            compose.runOnUiThread { PixelCopy.request(compose.activity.window, copy, { result = it }, Handler(Looper.getMainLooper())) }
            shadowOf(Looper.getMainLooper()).idle()
            check(result == PixelCopy.SUCCESS) { "PixelCopy failed: $result" }
            copy
        }
    }

    /**
     * Filmed as [name]: [start], frames held until [changed], the last [lead] of them kept, then [after] ms more. The
     * repositories' threads make the change at a wall-clock moment; the frames before it are the same picture.
     */
    private fun film(name: String, lead: Int = 12, after: Long = 1_200, start: () -> Unit, changed: () -> Boolean) {
        val out = File(dir, name).apply { deleteRecursively(); mkdirs() }
        val held = ArrayDeque<Pair<Bitmap, String>>()
        fun shot() = draw() to where()
        repeat(lead) {
            held.addLast(shot())
            step()
        }
        start()
        repeat(3_000) {
            if (changed()) return@repeat
            held.addLast(shot())
            if (held.size > lead) held.removeFirst()
            step()
            Thread.sleep(5)
        }
        check(changed()) { "$name: the change never came" }
        var n = 0
        val log = StringBuilder("frame,stackTop,stackHeight,anchorTop\n")
        fun write(frame: Pair<Bitmap, String>) {
            log.append(n).append(',').append(frame.second).append('\n')
            File(out, "f_%04d.png".format(n++)).outputStream().use { frame.first.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        held.forEach(::write)
        repeat((after / 16).toInt()) {
            write(shot())
            step()
        }
        File(dir, "$name.csv").writeText(log.toString())
    }

    /** The card's top and height and the anchored bubble's top, in window px: what the transcript over the dock does. */
    private fun where(): String {
        val stack = compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val anchor = compose.onAllNodes(hasText(Anchor), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        return "${stack?.top},${stack?.height},${anchor?.top}"
    }

    /** The run ends with its answer in the transcript; the queue's send is held at the server until [film] lets it through. */
    private fun endRunHoldingTheSend(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        api.createRunGate = gate
        api.runs[runId] = api.runs.getValue(runId).copy(status = "FINISHED")
        runBlocking {
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "The list is read once now; cold start is 1.05 s.", 5_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        pumpUntil("the queue's send at the server") { api.runRequests.isNotEmpty() }
        frames(1_500)
        return gate
    }

    @Test
    fun deliveryFromTwoQueued() {
        graph.followUps.enqueue(agentId, "Also check the release build, not just debug")
        graph.followUps.enqueue(agentId, "Then write up what changed for the release notes")
        open()
        val gate = endRunHoldingTheSend()
        film("delivery", start = { gate.complete(Unit) }, changed = { queue.size == 1 })
    }

    @Test
    fun removeFromTwoQueued() {
        graph.followUps.enqueue(agentId, "Also check the release build, not just debug")
        graph.followUps.enqueue(agentId, "Then write up what changed for the release notes")
        open()
        film("remove", start = {
            val trash = compose.onAllNodes(hasContentDescription("Remove queued follow-up"), useUnmergedTree = true)
            val top = trash.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInWindow.top }.index
            trash[top].performClick()
        }, changed = { queue.size == 1 })
        check(queue.single().text.startsWith("Then write up")) { "the top row should have gone: ${queue.map { it.text }}" }
    }

    @Test
    fun thirdSendFormsDeck() {
        graph.followUps.enqueue(agentId, "Also check the release build, not just debug")
        graph.followUps.enqueue(agentId, "Then write up what changed for the release notes")
        graph.followUps.setDraftText(agentId, "And attach the before and after traces to the PR")
        open()
        film("deck", start = { compose.onNode(hasContentDescription("Send"), useUnmergedTree = true).performClick() }, changed = { queue.size == 3 })
    }
}
