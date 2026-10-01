package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The working caption handing its place to the reply's first thought, on the real screen over the fakes, a frame at
 * a time on a stopped clock (the launch video's follow-up, 2026-09-28): a follow-up sent, "Working…" under it, then
 * the run's first thought. The caption fades out where it stood and what takes its place comes in there after it:
 * no frame has the caption's own ink and the newcomer's own ink in the caption's slot at once. In a short chat, read
 * from the top, nothing moves either: the thinking line stands exactly where the caption stood, and the message
 * above it stays put.
 *
 * The list keeps a removed row drawn while it fades but no longer in the semantics tree, so the caption is told apart
 * by its pixels: those lit by the caption at rest and not by what settles there, and the other way round.
 *
 * With `WORKING_HANDOFF_FRAMES` set, every frame of the short chat is written there as a PNG, `labels.txt` beside them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WorkingCaptionHandoffTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    // Just past the follow-up the fake starts (2026-04-14T09:00:01Z).
    private val now = java.time.Instant.parse("2026-04-14T09:00:05Z").toEpochMilli()
    private val agentId = "bc-working-handoff"
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        AppClock.nowMillis = System::currentTimeMillis
        if (::graph.isInitialized) graph.followUps.resetAll()
    }

    /** A finished chat of [turns] turns, an hour apart and ending before the follow-up, open on the screen. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(turns: Int) {
        val prompts = Array(turns) { n ->
            val reply = if (n == turns - 1) LAST_REPLY else "Turn ${n + 1}: ${"The release theme strips the icon, and the rules keep it. ".repeat(4).trim()}"
            Triple("run-${n + 1}", if (n == turns - 1) LAST_PROMPT else "Prompt ${n + 1}: why is the splash screen blank?", reply)
        }
        api.addFinishedAgent(agentId, "Working handoff", *prompts, firstRunAt = java.time.Instant.parse("2026-04-14T08:00:00Z").minusSeconds(3600L * (turns - 1)).toString())
        runBlocking {
            // Each finished turn's log, as the run stream replays it, so the chat reads as free for a follow-up.
            for ((runId, _, reply) in prompts) {
                streamer.emit(runId, RunStreamEvent.Assistant(reply))
                streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 60_000L, null))
                streamer.emit(runId, RunStreamEvent.Done)
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("working-handoff-keys", Context.MODE_PRIVATE) }, real = CursorBackend(api, streamer, isDemo = false))
        runBlocking {
            graph.session.signIn("key_abc").getOrThrow()
            graph.agents.refresh()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
        compose.waitUntil(20_000) { nodes(hasText(LAST_REPLY)).isNotEmpty() }
        compose.waitForIdle()
    }

    /** Types the follow-up and sends it, its run started on the fake; back once the caption stands whole under it. */
    private fun sendFollowUp(): String {
        val inComposer = hasAnyAncestor(hasTestTag("follow-up-composer"))
        compose.onNode(hasSetTextAction() and inComposer).performTextInput(FOLLOW_UP)
        val send = hasContentDescription("Send") and inComposer
        compose.waitUntil(20_000) { compose.onAllNodes(send and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val lastRun = api.agents.getValue(agentId).latestRunId
        compose.onNode(send).performClick()
        // The send goes out on the main thread's queue, which only an idle wait runs here. The fake counts the request
        // before it names the run it starts as the agent's latest, so what is waited for is that run.
        compose.waitUntil(20_000) { compose.waitForIdle(); api.agents.getValue(agentId).latestRunId != lastRun }
        val runId = checkNotNull(api.agents.getValue(agentId).latestRunId)
        runBlocking { streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING)) }
        compose.waitUntil(20_000) { caption() != null && nodes(hasText(FOLLOW_UP)).isNotEmpty() }
        // The send's own motion and the caption's fade in, over.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        return runId
    }

    private val transcript: SemanticsMatcher get() = hasScrollToIndexAction()

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        compose.onAllNodes(hasAnyAncestor(transcript) and matcher, useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private fun caption(): Rect? = nodes(hasTestTag(WORKING_CAPTION_TAG)).firstOrNull()?.boundsInRoot

    private fun thinking(): Rect? = nodes(hasText("Thinking", substring = false)).firstOrNull()?.boundsInRoot

    private fun message(): Rect = nodes(hasText(FOLLOW_UP)).first().boundsInRoot

    private fun listBounds(): Rect = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot

    @OptIn(ExperimentalRoborazziApi::class)
    private fun screen(): Bitmap {
        val file = File.createTempFile("working-handoff-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
    }

    private class Frame(val label: String, val bitmap: Bitmap, val thinking: Rect?, val message: Rect)

    private fun frame(label: String) = Frame(label, screen(), thinking(), message())

    private class Handoff(val slot: Rect, val rest: Frame, val frames: List<Frame>, val settled: Frame)

    /**
     * The follow-up sent and its caption at rest, then the run's first thought and [HANDOFF_FRAMES] frames from the
     * first one it is on screen in, then [TAIL_FRAMES] more to let everything settle.
     */
    private fun handOff(keepFrames: Boolean): Handoff {
        val runId = sendFollowUp()
        compose.mainClock.autoAdvance = false
        val rest = frame("caption at rest")
        val list = listBounds()
        val slot = checkNotNull(caption()) { "no caption under the follow-up" }.let { Rect(list.left, it.top, list.right, it.bottom) }
        val lead = List(LEAD_IN_FRAMES) { compose.mainClock.advanceTimeByFrame(); frame("caption at rest") }

        runBlocking { streamer.emit(runId, RunStreamEvent.Thinking(THOUGHT)) }
        // The thought reaches the screen through work on the main thread and off it: frames go by, each one running
        // the main thread's queue, the caption at rest, until the first the thought is on. A wait that only looked
        // would hold the main thread and could starve it.
        compose.waitUntil(60_000) {
            compose.mainClock.advanceTimeByFrame()
            thinking() != null
        }
        val frames = mutableListOf(frame("+0ms after the thought"))
        for (n in 1 until HANDOFF_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            frames += frame("+${n * 16}ms after the thought")
        }
        val tail = List(TAIL_FRAMES) { compose.mainClock.advanceTimeByFrame(); frame("settled") }
        if (keepFrames) (listOf(rest) + lead + frames + tail).forEach(::keep)
        return Handoff(slot, rest, frames, tail.last())
    }

    private fun brightness(p: Int): Int = maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)

    private fun Bitmap.lit(i: Int, canvas: Int): Boolean = brightness(getPixel(i % width, i / width)) > canvas + LIT_ABOVE_CANVAS

    /** Lit pixels (brighter than the canvas under them) of [bitmap] inside [area], as indices into the bitmap. */
    private fun lit(bitmap: Bitmap, area: Rect, canvas: Int): Set<Int> {
        val left = area.left.toInt().coerceIn(0, bitmap.width)
        val right = area.right.toInt().coerceIn(left, bitmap.width)
        val top = area.top.toInt().coerceIn(0, bitmap.height)
        val bottom = area.bottom.toInt().coerceIn(top, bitmap.height)
        val out = HashSet<Int>()
        for (y in top until bottom) for (x in left until right) if (bitmap.lit(y * bitmap.width + x, canvas)) out += y * bitmap.width + x
        return out
    }

    private fun Set<Int>.dilated(width: Int, by: Int): Set<Int> = flatMapTo(HashSet()) { i ->
        val x = i % width
        val y = i / width
        (-by..by).flatMap { dy -> (-by..by).mapNotNull { dx -> if (x + dx in 0 until width && y + dy >= 0) (y + dy) * width + x + dx else null } }
    }

    /**
     * Never both: on no frame are the caption's own pixels lit (those it lit at rest that what settles in its slot
     * does not) while the newcomer's own are too (those lit once settled that the caption did not light at rest).
     */
    private fun assertNeverOverlapping(handoff: Handoff) {
        val width = handoff.rest.bitmap.width
        val canvas = brightness(handoff.rest.bitmap.getPixel(handoff.slot.right.toInt() - 4, handoff.slot.center.y.toInt()))
        val atRest = lit(handoff.rest.bitmap, handoff.slot, canvas)
        val settled = lit(handoff.settled.bitmap, handoff.slot, canvas)
        val captionOwn = atRest - settled.dilated(width, 2)
        val newcomerOwn = settled - atRest.dilated(width, 2)
        assertWithMessage("pixels only the caption lights: ${captionOwn.size}").that(captionOwn.size).isAtLeast(40)
        assertWithMessage("pixels only its successor lights: ${newcomerOwn.size}").that(newcomerOwn.size).isAtLeast(40)
        fun drawn(frame: Frame, own: Set<Int>): Int = own.count { frame.bitmap.lit(it, canvas) }
        val trace = handoff.frames.map { f -> Triple(f.label, drawn(f, captionOwn), drawn(f, newcomerOwn)) }
        val summary = trace.joinToString { (label, c, n) -> "${label.substringBefore(" after")} caption=$c newcomer=$n" }
        val floor = { own: Set<Int> -> maxOf(3, own.size / 50) }
        assertWithMessage("the caption fades out rather than going at once: $summary").that(trace.first().second).isAtLeast(floor(captionOwn))
        assertWithMessage("its successor is whole once settled: $summary").that(trace.last().third).isAtLeast(newcomerOwn.size * 9 / 10)
        for ((label, c, n) in trace) {
            assertWithMessage("$label: the caption ($c of ${captionOwn.size}) and what takes its place ($n of ${newcomerOwn.size}) drawn in one slot at once; all: $summary")
                .that(c >= floor(captionOwn) && n >= floor(newcomerOwn)).isFalse()
        }
    }

    @Test
    fun `in a short chat the first thinking line comes in where the caption stood, after it, and nothing moves`() {
        open(turns = 1)
        val handoff = handOff(keepFrames = frameDir != null)
        assertNeverOverlapping(handoff)
        val line = checkNotNull(handoff.settled.thinking)
        assertWithMessage("the thinking line ($line) stands in the caption's slot (${handoff.slot})")
            .that(abs(line.top - handoff.slot.top) <= 1f && abs(line.bottom - handoff.slot.bottom) <= 1f).isTrue()
        val still = handoff.rest.message
        for (f in handoff.frames + handoff.settled) {
            assertWithMessage("${f.label}: the follow-up at ${f.message}, at rest at $still").that(f.message).isEqualTo(still)
        }
    }

    @Test
    fun `in a long chat followed at the bottom the caption and what takes its place are never drawn over each other`() {
        open(turns = 8)
        val handoff = handOff(keepFrames = false)
        assertWithMessage("the chat overflows and is followed from the bottom").that(handoff.rest.message.bottom).isGreaterThan(listBounds().center.y)
        assertNeverOverlapping(handoff)
    }

    private val frameDir = System.getenv("WORKING_HANDOFF_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private var framesKept = 0

    private fun keep(frame: Frame) {
        val dir = frameDir ?: return
        dir.mkdirs()
        val n = framesKept++
        File(dir, "frame-%03d.png".format(n)).outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "labels.txt").appendText("%03d %s\n".format(n, frame.label))
    }

    private companion object {
        const val LAST_PROMPT = "Why does the release build drop the splash screen?"
        const val LAST_REPLY = "R8 strips the theme's animated icon; keeping it fixes the launch."
        const val FOLLOW_UP = "Now keep the icon in the debug build too."
        const val THOUGHT = "Checking the debug variant's theme before touching the ProGuard rules."
        const val LEAD_IN_FRAMES = 12
        const val HANDOFF_FRAMES = 40
        const val TAIL_FRAMES = 20
        const val LIT_ABOVE_CANVAS = 8
    }
}
