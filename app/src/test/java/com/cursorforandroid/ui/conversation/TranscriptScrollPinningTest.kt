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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
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
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
 * The transcript's scroll while a run streams, on the real screen over the fakes: [TURNS] turns of reads, the newest
 * under way, and the burst Bennett reads through (2026-09-22) — 50 thinking lines and 5 tool calls into the live
 * stretch. The list follows the newest line only while the reader is at the bottom; scrolled up, or with a dropdown
 * just opened, what is on screen holds still to the pixel, whatever streams below it or inside the stretch being read.
 *
 * Every finished turn n reads 10 + n files, so each stretch's line ("13 files · 1 thought") names its turn; the live
 * one reads "Working · 40 files · 1 thought" before the burst. Collapsed, the turns are taller than the screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TranscriptScrollPinningTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-22T12:00:00Z").toEpochMilli()
    private val agentId = "bc-scroll-pin"
    private var turns = TURNS
    private val live get() = "run-$turns"
    private lateinit var graph: AppGraph
    private var liveCalls = LIVE_CALLS

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun file(turn: Int, n: Int) = "Turn${turn}File$n.kt"

    private fun read(turn: Int, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "run-$turn-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/${file(turn, n)}")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")); put("path", JsonPrimitive("app/src/${file(turn, n)}")) }) },
        ),
    )

    private fun seed() = runBlocking {
        val prompts = Array(turns) { Triple("run-${it + 1}", "Prompt ${it + 1}: read the modules and say what changed.", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Scroll pinning", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        for (turn in 1 until turns) {
            val runId = "run-$turn"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $turn: reading the modules before saying anything."))
            repeat(10 + turn) { n -> streamer.emit(runId, read(turn, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $turn"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $turn", turn * 60_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        streamer.emit(live, RunStreamEvent.Thinking("Turn $turns: reading the modules before saying anything."))
        repeat(LIVE_CALLS) { n -> streamer.emit(live, read(turns, n + 1)) }
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
        compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.isStreaming && it.traceStatus.pending == 0 } }
        // The live run's seeded story whole before anything is measured: a slow runner is still delivering it here.
        compose.waitUntil(60_000) { liveSteps().count { it is ToolCall } == LIVE_CALLS }
        awaitLiveSummary()
        compose.waitUntil(20_000) { runCatching { summary(stretchOf(turns - 1)) }.isSuccess }
        compose.waitForIdle()
    }

    // --- the stream -------------------------------------------------------------------------------------------------

    private fun emit(event: RunStreamEvent) = runBlocking { streamer.emit(live, event) }

    private fun lineText(step: Int, line: Int) = "Step $step line $line: checking the next module."

    /** One thinking line into the live stretch — the open thought grows by it, or a new one starts after a tool call — drawn. */
    private fun thinkingLine(step: Int, line: Int) {
        emit(RunStreamEvent.Thinking("${lineText(step, line)}\n"))
        awaitDrawn(lineText(step, line))
    }

    /** The next tool call into the live stretch, drawn. */
    private fun toolCall(): String {
        liveCalls++
        emit(read(turns, liveCalls))
        awaitDrawn(file(turns, liveCalls))
        awaitLiveSummary()
        return file(turns, liveCalls)
    }

    /** The burst: [STEPS] times [LINES] thinking lines, then a tool call; [after] runs once the screen has drawn each event. */
    private fun burst(after: (event: Int, text: String) -> Unit) {
        var event = 0
        for (step in 1..STEPS) {
            for (line in 1..LINES) {
                thinkingLine(step, line)
                after(++event, lineText(step, line))
            }
            after(++event, toolCall())
        }
    }

    /** The live stretch is one item of the list, so its newest line is composed whether or not it is on screen. */
    private fun awaitDrawn(text: String) {
        compose.waitUntil(20_000) { nodeWithText(text) != null }
        compose.waitForIdle()
    }

    /** The live stretch's line has caught up with the stream: the rows on screen are the newest the screen was given. */
    private fun awaitLiveSummary() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(liveLine(), substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The live stretch's counts as the stream has them now, which is what its line will say once drawn. */
    private fun liveLine(): String {
        val turn = liveSteps()
        return "${turn.count { it is ToolCall }} files · ${turn.count { it is ThinkingBlock && it.text.isNotBlank() }} thought"
    }

    private fun liveSteps() = graph.conversations.state(agentId).value.items.let { items ->
        items.subList(items.indexOfLast { it is UserMessage } + 1, items.size).filterIsInstance<ActivityGroup>().flatMap { it.steps }
    }

    /** A finished turn's stretch line, which starts with its count of files. */
    private fun stretchOf(turn: Int) = "${10 + turn} files"

    // --- what is on screen ------------------------------------------------------------------------------------------

    private val transcript: SemanticsMatcher get() = hasScrollToIndexAction()

    private fun listBounds(): Rect = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot

    private data class Seen(val id: Int, val text: String, val top: Float, val height: Int)

    private fun SemanticsNode.text(): String = config[SemanticsProperties.Text].joinToString(" ")

    private fun texts(): List<SemanticsNode> =
        compose.onAllNodes(hasAnyAncestor(transcript) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()

    /** The transcript's text nodes wholly or partly on screen, top first, each by its node id with its unclipped top. */
    private fun visibleTexts(): List<Seen> {
        val list = listBounds()
        return texts()
            .filter { node -> node.boundsInRoot.let { it.height > 0f && it.bottom > list.top && it.top < list.bottom } }
            .map { Seen(it.id, it.text(), it.positionInRoot.y, it.size.height) }
            .sortedBy { it.top }
    }

    private fun nodeWithText(text: String): SemanticsNode? =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()

    private fun top(text: String): Float = checkNotNull(nodeWithText(text)) { "\"$text\" is not composed" }.positionInRoot.y

    /** The newest [text] is wholly inside the transcript's viewport. */
    private fun assertOnScreen(text: String, event: Int) {
        val list = listBounds()
        val node = checkNotNull(nodeWithText(text)) { "event $event: \"$text\" is not composed" }
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        val where = "event $event: \"$text\" at $top..$bottom, the list's viewport ${list.top}..${list.bottom}"
        assertWithMessage(where).that(bottom).isAtMost(list.bottom + 0.5f)
        assertWithMessage(where).that(top).isAtLeast(list.top - 0.5f)
    }

    /** Every text node in [before] stands exactly where it stood. */
    private fun assertUnmoved(before: List<Seen>, event: Int, what: String) {
        val now = texts().associateBy { it.id }
        for (seen in before) {
            val node = now[seen.id] ?: error("event $event ($what): \"${seen.text.take(40)}\" is gone; on screen ${visibleTexts().map { "${it.text.take(16)}@${it.top}" }}")
            assertWithMessage("event $event ($what): \"${seen.text.take(40)}\" moved").that(node.positionInRoot.y).isEqualTo(seen.top)
        }
    }

    // --- the reader's hands ---------------------------------------------------------------------------------------

    /** The text node that is exactly [text]: a stretch's verb ("Working", "13 files"). */
    private fun summary(text: String): SemanticsNode =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = false), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun tap(node: SemanticsNode) {
        compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    /** A scroll by [dy] px, as TalkBack or a scroll wheel would ask for one: positive brings newer rows up. */
    private fun scrollBy(dy: Float) {
        compose.onNode(transcript).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
        compose.waitForIdle()
    }

    /** Scrolls until the node that is exactly [text] has its top at [y]. */
    private fun scrollTopTo(text: String, y: Float) = scrollBy(summary(text).positionInRoot.y - y)

    /** A finger drawn down the list, clear of the jump button at the bottom's centre. */
    private fun swipeToOlder() {
        compose.onNode(transcript).performTouchInput { swipe(Offset(left + 80f, top + 40f), Offset(left + 80f, bottom - 200f), durationMillis = 600) }
        compose.waitForIdle()
    }

    private fun flingToNewest() {
        repeat(6) { compose.onNode(transcript).performTouchInput { swipeUp(startY = bottom - 200f, endY = top + 40f, durationMillis = 120) } }
        compose.waitForIdle()
    }

    private fun scrollToText(text: String) {
        compose.onNode(transcript).performScrollToNode(hasText(text, substring = false))
        compose.waitForIdle()
    }

    /** The unclipped top of the node with [id], which a later lookup by text could confuse with a copy of its text opened below it. */
    private fun topOf(id: Int): Float = texts().single { it.id == id }.positionInRoot.y

    private fun following(): Boolean = compose.onAllNodes(hasContentDescription("Scroll to latest")).fetchSemanticsNodes().isEmpty()

    // --- the pins ---------------------------------------------------------------------------------------------------

    @Test
    fun `at the bottom, every streamed line and tool call is on screen as it lands`() {
        open()
        // The live stretch open and the reader back down at its end: its lines are what they follow.
        tap(summary("Working"))
        flingToNewest()
        assertThat(following()).isTrue()

        burst { event, text -> assertOnScreen(text, event) }
        assertThat(following()).isTrue()
    }

    @Test
    fun `scrolled up between an open stretch and the live one, the burst moves nothing on screen`() {
        open()
        val older = stretchOf(TURNS - 1)
        tap(summary(older))
        scrollToText("Working")
        tap(summary("Working"))
        // The older stretch's line past the top edge, the live one's on screen with its end below the bottom edge.
        scrollTopTo("Working", listBounds().top + listBounds().height * 0.62f)
        val list = listBounds()
        assertWithMessage("the older stretch reaches above the viewport").that(summary(older).positionInRoot.y).isLessThan(list.top)
        assertWithMessage("the live stretch starts on screen").that(summary("Working").positionInRoot.y).isLessThan(list.bottom)
        assertWithMessage("the live stretch ends below the viewport").that(top(file(TURNS, liveCalls))).isGreaterThan(list.bottom)
        assertThat(following()).isFalse()
        val before = visibleTexts()
        val first = before.first()

        framesOf("Scrolled up while the run streams: 50 thinking lines and 5 tool calls land below, nothing on screen moves") { frame ->
            frame("before the burst", first.top)
            burst { event, text ->
                assertUnmoved(before, event, text)
                assertThat(visibleTexts().first().let { it.id to it.top }).isEqualTo(first.id to first.top)
                if (event % 11 == 0) frame("+$event events", first.top)
            }
        }
        assertThat(following()).isFalse()
    }

    @Test
    fun `scrolled up inside the live stretch, its growth below the viewport moves nothing`() {
        open()
        tap(summary("Working"))
        // Inside the open live stretch: its line above the top edge, its newest call below the bottom one.
        scrollTopTo("Working", listBounds().top - 300f)
        val list = listBounds()
        assertWithMessage("the live stretch's line is above the viewport").that(summary("Working").positionInRoot.y).isLessThan(list.top)
        assertWithMessage("the live stretch ends below the viewport").that(top(file(TURNS, liveCalls))).isGreaterThan(list.bottom)
        val before = visibleTexts()

        burst { event, text -> assertUnmoved(before, event, text) }
    }

    @Test
    fun `back at the bottom the list follows again, by a swipe or by the jump button`() {
        open()
        tap(summary("Working"))
        flingToNewest()
        swipeToOlder()
        assertThat(following()).isFalse()
        val pinned = visibleTexts()
        thinkingLine(1, 1)
        toolCall()
        assertUnmoved(pinned, 2, "pinned")

        // A fling back down to the newest line: following again.
        flingToNewest()
        assertThat(following()).isTrue()
        for (line in 1..LINES) {
            thinkingLine(2, line)
            assertOnScreen(lineText(2, line), line)
        }
        assertOnScreen(toolCall(), LINES + 1)

        // Up again, then the jump button: following again.
        swipeToOlder()
        assertThat(following()).isFalse()
        compose.onNode(hasContentDescription("Scroll to latest")).performClick()
        compose.waitForIdle()
        assertThat(following()).isTrue()
        thinkingLine(3, 1)
        assertOnScreen(lineText(3, 1), 1)
        assertOnScreen(toolCall(), 2)
    }

    @Test
    fun `a flick off the bottom keeps its momentum through the switch it sets off`() {
        open()
        assertThat(following()).isTrue()
        val list = listBounds()
        val top = visibleTexts().first { it.top >= list.top }
        compose.onNode(transcript).performTouchInput { swipe(Offset(left + 80f, centerY - 150f), Offset(left + 80f, centerY + 150f), durationMillis = 60) }
        compose.waitForIdle()
        assertThat(following()).isFalse()
        // The finger went 300 px; the fling took the rows on well past that, or off the screen altogether.
        val travelled = texts().firstOrNull { it.id == top.id }?.let { it.positionInRoot.y - top.top } ?: Float.MAX_VALUE
        assertThat(travelled).isGreaterThan(600f)
    }

    @Test
    fun `a tapped dropdown opens and closes under the finger, at the bottom and scrolled up`() {
        open()
        // At the bottom: the live stretch's line is the newest row; opened, it stays where it was tapped and its steps
        // open below it, past the bottom edge, the list no longer following.
        val atBottom = summary("Working").positionInRoot.y
        tap(summary("Working"))
        assertThat(summary("Working").positionInRoot.y).isEqualTo(atBottom)
        assertThat(following()).isFalse()

        // Scrolled up: an older stretch opened, a call inside it opened and closed, the stretch closed, the tapped row
        // under the finger every time.
        val row = stretchOf(TURNS - 2)
        scrollTopTo(row, listBounds().top + listBounds().height * 0.3f)
        val tapped = summary(row).positionInRoot.y
        tap(summary(row))
        assertThat(summary(row).positionInRoot.y).isEqualTo(tapped)
        val call = checkNotNull(nodeWithText(file(TURNS - 2, 2)))
        val callTop = call.positionInRoot.y
        tap(call)
        assertThat(topOf(call.id)).isEqualTo(callTop)
        assertThat(nodeWithText("val x = 2")).isNotNull()
        tap(call)
        assertThat(topOf(call.id)).isEqualTo(callTop)
        tap(summary(row))
        assertThat(summary(row).positionInRoot.y).isEqualTo(tapped)
    }

    @Test
    fun `pinned at the top, an older page landing above leaves the rows being read where they are`() {
        // Fourteen turns, the window the newest ten; the run list's second page, and so the older turns, held back.
        turns = 14
        api.pageSize = 10
        val page = CompletableDeferred<Unit>()
        api.runsLaterPagesGate = page
        open()
        compose.onNode(transcript).performScrollToNode(hasTestTag("load-older") or hasTestTag("loading-older"))
        compose.waitForIdle()
        graph.conversations.loadOlder(agentId)
        compose.waitUntil(10_000) { graph.conversations.state(agentId).value.isLoadingOlder }
        compose.waitForIdle()
        assertThat(following()).isFalse()
        val before = visibleTexts().filterNot { it.text.startsWith("Loading") || it.text == "Older messages" }
        assertThat(before.first().text).startsWith("Prompt 5")

        page.complete(Unit)
        compose.waitUntil(30_000) { graph.conversations.state(agentId).value.let { s -> !s.isLoadingOlder && s.items.count { it is UserMessage } == turns && s.traceStatus.pending == 0 } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("load-older") or hasTestTag("loading-older") or hasTestTag("trace-status")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertUnmoved(before, 1, "older page landed")
    }

    // --- frames -----------------------------------------------------------------------------------------------------

    /**
     * Captures frames of the screen while [block] runs and, when `SCROLL_PIN_FRAMES` names a file, writes them there as
     * one strip, a guide line across each at the first visible row's top. Nothing is captured otherwise. The frames
     * are Roborazzi's, so the strip is drawn under `recordRoborazziDebug`.
     */
    private fun framesOf(title: String, block: (frame: (label: String, guide: Float) -> Unit) -> Unit) {
        val out = System.getenv("SCROLL_PIN_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
        val frames = mutableListOf<Pair<String, Bitmap>>()
        var guideY = 0f
        block { label, guide ->
            guideY = guide
            if (out != null) {
                val file = File.createTempFile("scroll-pin-frame", ".png").apply { deleteOnExit() }
                captureScreenRoboImage(file.path, RoborazziOptions())
                frames += label to checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured: run under recordRoborazziDebug" }
            }
        }
        if (out == null || frames.isEmpty()) return
        val scale = 0.5f
        val w = (frames[0].second.width * scale).toInt()
        val h = (frames[0].second.height * scale).toInt()
        val gap = 16
        val header = 72
        val caption = 48
        val strip = Bitmap.createBitmap(frames.size * (w + gap) + gap, header + h + caption + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(0xFF101010.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt(); textSize = 28f }
        val guide = Paint().apply { color = 0xFFFF4D4D.toInt(); strokeWidth = 2f }
        canvas.drawText(title, gap.toFloat(), 46f, text)
        frames.forEachIndexed { i, (label, bitmap) ->
            val x = gap + i * (w + gap)
            canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, w, h, true), x.toFloat(), header.toFloat(), null)
            val y = header + guideY * scale
            canvas.drawLine(x.toFloat(), y, (x + w).toFloat(), y, guide)
            canvas.drawText(label, x.toFloat(), (header + h + 36).toFloat(), text)
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { strip.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TURNS = 8
        const val LIVE_CALLS = 40
        const val STEPS = 5
        const val LINES = 10
    }
}
