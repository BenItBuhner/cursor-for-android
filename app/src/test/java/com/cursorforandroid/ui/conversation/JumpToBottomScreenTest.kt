package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The jump button on the real screen over the fakes: from deep in a long conversation — the oldest turn's stretch of
 * [BIG] reads opened, a list of over a thousand items — and from a few screens up while the agent thinks, its reply
 * starting to stream at [TOKENS_PER_SECOND] a moment into the glide. Either way the list glides to the newest row over a
 * few hundred milliseconds, the button goes, and the list then follows what streams in.
 *
 * With `JUMP_GLIDE_FRAMES` naming a directory, every frame drawn is written there (`<scenario>/frame-NNN.png`, the tap
 * marked on the frames it lasts), which is what the demo recordings are made from.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class JumpToBottomScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = BIG + 256)
    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private val agentId = "bc-jump-glide"
    private val live = "run-$TURNS"
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun reads(turn: Int) = if (turn == 1) BIG else 10 + turn

    private fun read(turn: Int, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "run-$turn-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")); put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) }) },
        ),
    )

    private fun seed() = runBlocking {
        val prompts = Array(TURNS) { Triple("run-${it + 1}", "Prompt ${it + 1}: read the modules and say what changed.", "Reply ${it + 1}: $EARLIER") }
        api.addFinishedAgent(agentId, "Jump glide", *prompts, firstRunAt = Instant.ofEpochMilli(now - TURNS * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        for (turn in 1 until TURNS) {
            val runId = "run-$turn"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $turn: reading the modules before saying anything."))
            repeat(reads(turn)) { n -> streamer.emit(runId, read(turn, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $turn: $EARLIER"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $turn", turn * 60_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Turn $TURNS: reading the modules before answering."))
        repeat(12) { n -> streamer.emit(live, read(TURNS, n + 1)) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        seed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        // Read off what the screen was handed, not the repository: the presenter runs off the main thread, so the
        // repository settles ahead of the rows drawn from it, and waitForIdle does not wait for the presenter.
        compose.waitUntil(120_000) { presentation().state.let { !it.isLoading && it.isStreaming && it.traceStatus.pending == 0 } }
        compose.waitUntil(60_000) { runCatching { summary("${reads(TURNS - 1)} files") }.isSuccess }
        compose.waitForIdle()
    }

    // --- the screen ---------------------------------------------------------------------------------------------------

    private val transcript: SemanticsMatcher get() = hasScrollToIndexAction()

    private fun listBounds(): Rect = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot

    private fun summary(text: String): SemanticsNode =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = false), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun nodeWithText(text: String): SemanticsNode? =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()

    private fun jumpShown(): Boolean = compose.onAllNodes(hasContentDescription("Scroll to latest")).fetchSemanticsNodes().isNotEmpty()

    private fun jumpCentre(): Offset = compose.onNode(hasContentDescription("Scroll to latest")).fetchSemanticsNode().boundsInRoot.center

    private fun scrollBy(dy: Float) {
        compose.onNode(transcript).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        compose.waitForIdle()
    }

    /** Whether [text] is wholly inside the transcript's viewport. */
    private fun onScreen(text: String): Boolean {
        val node = nodeWithText(text) ?: return false
        val list = listBounds()
        val top = node.positionInRoot.y
        return top >= list.top - 0.5f && top + node.size.height <= list.bottom + 0.5f
    }

    // --- the stream ---------------------------------------------------------------------------------------------------

    private val tokens = REPLY.chunked(4)
    private val thoughtTokens = THOUGHT.chunked(4)
    private var sent = 0
    private var thought = 0
    private var streamedMs = 0
    /** When the stream moved on from the agent's thinking (in the collapsed live stretch) to its reply, if it has. */
    private var replyFromMs: Int? = null

    private fun presentation() = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java].presented.value

    private fun presented() = presentation().items

    private fun presentedReply(): String = presented().filterIsInstance<AssistantMessage>().lastOrNull()?.markdown.orEmpty()

    private fun presentedThought(): String =
        presented().filterIsInstance<ActivityGroup>().lastOrNull()?.steps?.filterIsInstance<ThinkingBlock>()?.lastOrNull()?.text.orEmpty()

    /**
     * The tokens due by the stream's clock, [TOKENS_PER_SECOND] of them a second, sent and presented: the agent's
     * thinking until [startReply], its reply from then on.
     */
    private fun streamFrame() {
        streamedMs += FRAME_MS
        val replyFrom = replyFromMs
        if (replyFrom == null) {
            val next = (streamedMs * TOKENS_PER_SECOND / 1_000).coerceAtMost(thoughtTokens.size)
            if (next == thought) return
            runBlocking { streamer.emit(live, RunStreamEvent.Thinking(thoughtTokens.subList(thought, next).joinToString(""))) }
            thought = next
            val text = thoughtTokens.take(thought).joinToString("")
            compose.waitUntil(20_000) { presentedThought().endsWith(text.takeLast(24)) }
            return
        }
        val next = ((streamedMs - replyFrom) * TOKENS_PER_SECOND / 1_000).coerceIn(sent, tokens.size)
        if (next == sent) return
        runBlocking { streamer.emit(live, RunStreamEvent.Assistant(tokens.subList(sent, next).joinToString(""))) }
        sent = next
        val text = tokens.take(sent).joinToString("")
        compose.waitUntil(20_000) { presentedReply().endsWith(text.takeLast(24)) }
    }

    /** The agent stops thinking and starts replying: a new row under the live stretch, growing from the next frame. */
    private fun startReply() {
        replyFromMs = streamedMs
    }

    /** The newest line streamed, as it reads on screen: the tail of the reply sent so far. */
    private fun newestWords(): String = tokens.take(sent).joinToString("").trimEnd().substringAfterLast('\n').takeLast(12).trim()

    // --- frames -------------------------------------------------------------------------------------------------------

    private val frameRoot = System.getenv("JUMP_GLIDE_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private var framesKept = 0
    private var tap: Offset? = null
    private var tapFrames = 0

    private fun screen(): Bitmap {
        val file = File.createTempFile("jump-glide-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
    }

    /** Draws the next frame, streaming first when [streaming]; kept under `JUMP_GLIDE_FRAMES/<scenario>` when set. */
    private fun frame(scenario: String, streaming: Boolean) {
        if (streaming) streamFrame()
        compose.mainClock.advanceTimeByFrame()
        val dir = frameRoot?.let { File(it, scenario) } ?: return
        dir.mkdirs()
        val bitmap = screen().copy(Bitmap.Config.ARGB_8888, true)
        tap?.takeIf { tapFrames > 0 }?.let { at ->
            tapFrames--
            Canvas(bitmap).drawCircle(at.x, at.y, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        }
        File(dir, "frame-%03d.png".format(framesKept++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tapJump() {
        tap = jumpCentre()
        tapFrames = 8
        compose.onNode(hasContentDescription("Scroll to latest")).performClick()
    }

    /** Where the transcript's text on screen stands: it changes on every frame the list moves. */
    private fun placement(): List<Pair<Int, Float>> {
        val list = listBounds()
        return compose.onAllNodes(hasAnyAncestor(transcript) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { node -> node.boundsInRoot.let { it.height > 0f && it.bottom > list.top && it.top < list.bottom } }
            .map { it.id to it.positionInRoot.y }
    }

    /** How far (px) the text moved between two placements, or infinity when none of it is on both: a teleport. */
    private fun step(from: List<Pair<Int, Float>>, to: List<Pair<Int, Float>>): Float {
        val before = from.toMap()
        val moves = to.mapNotNull { (id, y) -> before[id]?.let { abs(y - it) } }.sorted()
        return if (moves.isEmpty()) Float.POSITIVE_INFINITY else moves[moves.size / 2]
    }

    /**
     * [landed] and [lastMoved] are frames after the tap; [travelFrames] is how many frames the list took from starting
     * to move to having gone nine tenths of the way it went before landing.
     */
    private class Jump(val landed: Int, val lastMoved: Int, val travelFrames: Int)

    /**
     * Taps the jump button after [HOLD_FRAMES] frames at rest and draws on to [AFTER_FRAMES] past the frame it lands:
     * the list still, the newest row ([newest]) wholly on screen, and the button gone. With [replyAfterTap], the reply
     * starts streaming that many frames into the glide, the way an answer turns up while you are on your way down to it.
     */
    private fun jump(scenario: String, streaming: Boolean, replyAfterTap: Int? = null, newest: () -> String): Jump {
        compose.mainClock.autoAdvance = false
        repeat(HOLD_FRAMES) { frame(scenario, streaming) }
        assertThat(jumpShown()).isTrue()
        tapJump()
        var lastMoved = 0
        var last = placement()
        var landed = -1
        val steps = mutableListOf<Float>()
        for (n in 1..GLIDE_FRAMES) {
            if (n == replyAfterTap) startReply()
            frame(scenario, streaming)
            val now = placement()
            if (now != last) lastMoved = n
            val moved = step(last, now)
            if (landed < 0) steps += moved
            last = now
            if (landed < 0 && moved == 0f && !jumpShown() && newest().let { it.isNotBlank() && onScreen(it) }) landed = n
            if (landed >= 0 && n - landed >= AFTER_FRAMES) break
        }
        assertWithMessage("the list came to rest at the newest row").that(landed).isAtLeast(0)
        val started = steps.indexOfFirst { it > 0f }
        val covered = steps.runningReduce(Float::plus).indexOfFirst { it >= 0.9f * steps.sum() }
        return Jump(landed, lastMoved, covered - started + 1)
    }

    // --- the scenarios ------------------------------------------------------------------------------------------------

    @Test
    fun `from deep in a long conversation, the jump glides to the newest row and the button goes`() {
        open()
        // Into the oldest turn's opened stretch of reads, a thousand rows above the newest one.
        compose.onNode(transcript).performScrollToNode(hasText("$BIG files", substring = false))
        compose.onNode(SemanticsMatcher("node") { it.id == summary("$BIG files").id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        scrollBy(listBounds().height * 1.5f)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(nodeWithText("Turn1File")).isNotNull()

        val jump = jump("long-conversation", streaming = false) { "Working" }
        // Moving for the glide's length, then still: not a snap, and not a crawl over a thousand rows.
        assertWithMessage("ms the list moved for").that(jump.lastMoved * FRAME_MS).isIn(TranscriptScroll.GLIDE_MIN_MILLIS - 2 * FRAME_MS..TranscriptScroll.GLIDE_MAX_MILLIS + 4 * FRAME_MS)
        assertWithMessage("ms the list took over nine tenths of the way").that(jump.travelFrames * FRAME_MS).isAtLeast(TranscriptScroll.GLIDE_MIN_MILLIS / 2)
        assertThat(jumpShown()).isFalse()
        assertThat(onScreen("Working")).isTrue()
    }

    @Test
    fun `while the reply streams in, the jump glides to it and the list then follows every token`() {
        open()
        // A few screens up while the agent thinks below; its reply starts a few frames into the glide.
        repeat(20) { streamFrame() }
        scrollBy(-listBounds().height * 2.5f)
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(jumpShown()).isTrue()

        val jump = jump("streaming", streaming = true, replyAfterTap = REPLY_AFTER_TAP_FRAMES, newest = ::newestWords)
        // Following again: every token from here on lands on screen, and the button stays gone.
        repeat(40) { n ->
            frame("streaming", streaming = true)
            assertWithMessage("token $sent (frame $n after landing): \"${newestWords()}\" on screen").that(onScreen(newestWords())).isTrue()
            assertThat(jumpShown()).isFalse()
        }
        // The reply's row turning up mid-way was eased onto, not snapped to.
        assertWithMessage("ms the list took over nine tenths of the way").that(jump.travelFrames * FRAME_MS).isAtLeast(TranscriptScroll.GLIDE_MIN_MILLIS / 2)
    }

    private companion object {
        const val TURNS = 6
        const val BIG = 800
        const val FRAME_MS = 16
        const val TOKENS_PER_SECOND = 100
        const val HOLD_FRAMES = 24
        const val GLIDE_FRAMES = 90
        const val AFTER_FRAMES = 36
        const val REPLY_AFTER_TAP_FRAMES = 6
        /** What the finished turns replied: nothing the streamed [REPLY] says, so its newest words are only ever its own. */
        const val EARLIER = "Done. The modules build, the suites pass, and nothing in the transcript's scrolling changed in this turn; " +
            "the two files you named only moved a constant between them."
        const val THOUGHT = "The user wants the jump button to glide rather than snap. Checking where the list decides it is following, " +
            "and what the new-row effect does to a scroll already under way, before touching anything."
        val REPLY = """
            I read through the modules you pointed at and traced how the transcript's scroll state moves between its two modes. The list follows the newest row while you are at the bottom, and holds what is on screen once you scroll away from it or open a dropdown.

            The jump button used to hand the whole distance to the lazy list's own animated scroll, which on a long chat either teleported or was cut short by the next streamed row, so it read as a snap. It now glides: from far up it first lands a couple of screens above the newest message, then eases the rest of the way in well under half a second, and rows streaming in meanwhile are part of where it lands.

            Next I will run the pinning and flicker suites again, then record the before and after on a long conversation so the motion can be reviewed at full speed and slowed down.
        """.trimIndent()
    }
}
