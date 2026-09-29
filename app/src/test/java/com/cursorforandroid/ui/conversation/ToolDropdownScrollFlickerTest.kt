package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.RollingText
import com.cursorforandroid.ui.theme.CursorDimens
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
 * An open tool group's rows while the reader scrolls, on the real screen over the fakes (Bennett's recording of
 * 2026-09-27): a finished turn's stretch opened, the list back at the bottom and following, then a scroll up off the
 * bottom and one back down to it. Each sets off a switch of the list's order (see [TranscriptScroll]), and neither
 * may take anything off the screen: every step row on screen, and the working caption, is drawn as it was at rest on
 * every frame, never fading back in from nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ToolDropdownScrollFlickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-27T01:00:00Z").toEpochMilli()
    private val agentId = "bc-dropdown-flicker"
    private val live = "run-$TURNS"
    private lateinit var graph: AppGraph
    /** How many files the oldest turn reads, when not as many as the others (10 + its turn). */
    private var firstTurnReads: Int? = null

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
        val prompts = Array(TURNS) { Triple("run-${it + 1}", "Prompt ${it + 1}: read the modules and say what changed.", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Dropdown flicker", *prompts, firstRunAt = Instant.ofEpochMilli(now - TURNS * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        for (turn in 1 until TURNS) {
            val runId = "run-$turn"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Turn $turn: reading the modules before saying anything."))
            repeat(firstTurnReads?.takeIf { turn == 1 } ?: (10 + turn)) { n -> streamer.emit(runId, read(turn, n + 1)) }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $turn"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $turn", turn * 60_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
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
        compose.waitUntil(20_000) { runCatching { summary(STRETCH) }.isSuccess }
        compose.waitForIdle()
    }

    // --- what is on screen ------------------------------------------------------------------------------------------

    private val transcript: SemanticsMatcher get() = hasScrollToIndexAction()

    private fun listBounds(): Rect = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot

    private fun summary(text: String): SemanticsNode =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasText(text, substring = false), useUnmergedTree = true).fetchSemanticsNodes().single()

    private fun following(): Boolean = compose.onAllNodes(hasContentDescription("Scroll to latest")).fetchSemanticsNodes().isEmpty()

    /**
     * What must stay drawn, by name: the open stretch's steps (its tool calls' lines) wholly inside the viewport, clear
     * of the bands at its edges, where the list dissolves its rows while there is more transcript past them, and above
     * its bottom fifth, where the catch-up word and the jump button come up over the rows once it is scrolled up. Rows
     * the list composed off screen and did not place keep the bounds they last had, and are not drawn anywhere.
     */
    private fun watched(): Map<String, Rect> {
        val list = listBounds()
        val band = with(compose.density) { CursorDimens.scrollFade.toPx() }
        return compose.onAllNodes(hasAnyAncestor(transcript) and hasText("Turn${TURNS - 1}File", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.layoutInfo.isPlaced }
            .associate { node -> node.config[SemanticsProperties.Text].joinToString(" ") to node.boundsInRoot }
            .filterValues { it.height > 0f && it.top >= list.top + band && it.bottom <= list.bottom - maxOf(band, list.height / 5) }
    }

    private fun screen(): Bitmap {
        val file = File.createTempFile("dropdown-flicker-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
    }

    /** The lit pixels inside [area]: light text on the dark canvas, so a row faded toward nothing has few. */
    private fun ink(bitmap: Bitmap, area: Rect, lit: Int = LIT): Int {
        val left = area.left.toInt().coerceIn(0, bitmap.width)
        val right = area.right.toInt().coerceIn(left, bitmap.width)
        val top = area.top.toInt().coerceIn(0, bitmap.height)
        val bottom = area.bottom.toInt().coerceIn(top, bitmap.height)
        if (right <= left || bottom <= top) return 0
        val pixels = IntArray((right - left) * (bottom - top))
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.count { p -> maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > lit }
    }

    private class Dip(val name: String, val frame: Int, val ratio: Float)

    /**
     * Runs [scroll] on a stopped clock and draws [FRAMES] frames after it, each watched row's ink measured against
     * [rest] — what it had at rest — on every one. Returns the worst frame of each row.
     */
    private fun watch(label: String, rest: Map<String, Int>, scroll: () -> Unit): List<Dip> {
        val worst = mutableMapOf<String, Dip>()
        scroll()
        for (frame in 1..FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val bitmap = screen()
            for ((name, area) in watched()) {
                val base = rest[name] ?: continue
                val ratio = ink(bitmap, area).toFloat() / base
                if (ratio < (worst[name]?.ratio ?: Float.MAX_VALUE)) worst[name] = Dip(name, frame, ratio)
            }
            keep(bitmap, "$label +${frame * 16}ms")
        }
        return worst.values.sortedBy { it.ratio }
    }

    private fun scrollBy(dy: Float) {
        compose.onNode(transcript).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
    }

    @Test
    fun `an open tool group's rows stay drawn through the scroll's switches of order, both ways`() {
        open()
        compose.onNode(SemanticsMatcher("node") { it.id == summary(STRETCH).id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        // Back at the bottom: following, the open stretch's steps above the newest turn.
        repeat(6) { compose.onNode(transcript).performTouchInput { swipeUp(startY = bottom - 200f, endY = top + 40f, durationMillis = 120) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(following()).isTrue()

        compose.mainClock.autoAdvance = false
        val atRest = screen()
        val rest = watched().mapValues { (_, area) -> ink(atRest, area) }.filterValues { it > 0 }
        assertWithMessage("step rows on screen at rest: ${rest.keys}").that(rest.size).isAtLeast(4)

        keep(atRest, "at rest, following")
        // Up off the bottom: the list pins what is on screen from the scroll's first pixel.
        val up = watch("up", rest) { scrollBy(-SCROLL_PX) }
        val pinned = following()
        // Back down to the bottom: the scroll comes to rest there and the list follows again.
        val down = watch("down", rest) { scrollBy(SCROLL_PX * 4) }
        assertThat(pinned).isFalse()
        assertThat(following()).isTrue()

        for ((way, dips) in listOf("scrolling up" to up, "scrolling back down" to down)) {
            assertWithMessage("$way, rows watched").that(dips).isNotEmpty()
            for (dip in dips) {
                assertWithMessage("$way: \"${dip.name}\" drawn at ${"%.2f".format(dip.ratio)} of its ink at rest on frame ${dip.frame}; all: ${dips.map { "${it.name.take(18)}=${"%.2f".format(it.ratio)}@${it.frame}" }}")
                    .that(dip.ratio).isAtLeast(STEP_FLOOR)
            }
        }
    }

    /**
     * The jump button from the top of the transcript, the oldest turn's 150 reads open under it: the list jumps to a
     * few screens above the newest row and glides the rest (see [TranscriptScroll.jumpToBottom]). The jump re-anchors
     * the list as the order's switches do, so the open group's rows it glides onto must come in drawn, never fading in
     * from nothing.
     */
    @Test
    fun `an open tool group's rows stay drawn as the jump button glides onto them`() {
        firstTurnReads = 150
        open()
        compose.onNode(SemanticsMatcher("node") { it.id == summary("150 files").id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        repeat(40) { if (runCatching { summary(STRETCH) }.isFailure) { scrollBy(SCROLL_PX * 4); compose.waitForIdle() } }
        compose.onNode(SemanticsMatcher("node") { it.id == summary(STRETCH).id }, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        repeat(6) { compose.onNode(transcript).performTouchInput { swipeUp(startY = bottom - 200f, endY = top + 40f, durationMillis = 120) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(following()).isTrue()

        compose.mainClock.autoAdvance = false
        val atRest = screen()
        val rest = watched().mapValues { (_, area) -> ink(atRest, area) }.filterValues { it > 0 }
        assertWithMessage("step rows on screen at rest: ${rest.keys}").that(rest.size).isAtLeast(4)

        // Up to the top of the transcript, screens away, and let it settle pinned there.
        repeat(60) { scrollBy(-SCROLL_PX * 8) }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        assertThat(following()).isFalse()
        assertWithMessage("scrolled off the open group").that(watched()).isEmpty()

        val glide = watch("jump", rest) { compose.onNode(hasContentDescription("Scroll to latest")).performClick() }
        assertThat(following()).isTrue()
        assertWithMessage("rows watched on the way in").that(glide).isNotEmpty()
        for (dip in glide) {
            assertWithMessage("\"${dip.name}\" drawn at ${"%.2f".format(dip.ratio)} of its ink at rest on frame ${dip.frame}; all: ${glide.map { "${it.name.take(18)}=${"%.2f".format(it.ratio)}@${it.frame}" }}")
                .that(dip.ratio).isAtLeast(STEP_FLOOR)
        }
    }

    /** A dropdown tapped open still has its steps fade in, once the rows below have mostly slid past, and then stay whole. */
    @Test
    fun `a tapped dropdown's steps still fade in behind the rows sliding past, then stay drawn`() {
        open()
        compose.mainClock.autoAdvance = false
        compose.onNode(SemanticsMatcher("node") { it.id == summary(STRETCH).id }, useUnmergedTree = true).performClick()
        val frames = (1..40).map { compose.mainClock.advanceTimeByFrame(); screen() to watched() }
        val (last, lastRows) = frames.last()
        val whole = lastRows.mapValues { (_, area) -> ink(last, area) }.filterValues { it > 0 }
        assertWithMessage("steps on screen once open: ${whole.keys}").that(whole.size).isAtLeast(4)
        val drawn = frames.map { (bitmap, rows) ->
            whole.keys.filter { it in rows }.map { ink(bitmap, rows.getValue(it)).toFloat() / whole.getValue(it) }.average().takeIf { !it.isNaN() } ?: 0.0
        }
        // Rows sliding down pass through where the steps will be, so their places are not empty before the steps come.
        val trace = drawn.map { "%.2f".format(it) }
        val whole0 = drawn.indexOfFirst { it >= 0.95 }
        assertWithMessage("the steps not whole before the rows have slid past: $trace").that(whole0).isAtLeast(12)
        assertWithMessage("the steps fading in on the way: $trace").that(drawn.subList(8, whole0).any { it in 0.3..0.9 }).isTrue()
        assertWithMessage("the steps whole once faded in: $trace").that(drawn.drop(whole0).minOrNull()).isAtLeast(0.95)
    }

    /**
     * The working caption under the newest turn, in a list asked to scroll to a place, as each switch of the
     * transcript's order asks: it stays drawn, rather than fading in from nothing again. Its shimmer off, so each
     * frame's ink is the caption's alone.
     */
    @Test
    fun `the working caption stays drawn when its list is asked to scroll to a place`() {
        val list = LazyListState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val fadeIn = rememberCaptionFadeIn(true)
                LazyColumn(Modifier.fillMaxWidth().height(400.dp), state = list) {
                    items(4, key = { "above-$it" }) { BasicText("Row $it", Modifier.height(40.dp)) }
                    item("working") { Box(captionFade(fadeIn).testTag(WORKING_CAPTION_TAG)) { RollingText("Working…", shimmer = false) } }
                    items(20, key = { "below-$it" }) { BasicText("Row ${it + 4}", Modifier.height(40.dp)) }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        fun caption() = compose.onNode(hasTestTag(WORKING_CAPTION_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rest = ink(screen(), caption(), lit = CAPTION_LIT)
        assertThat(rest).isGreaterThan(0)

        compose.runOnUiThread { list.requestScrollToItem(1) }
        val drawn = (1..12).map { frame ->
            compose.mainClock.advanceTimeByFrame()
            frame to ink(screen(), caption(), lit = CAPTION_LIT).toFloat() / rest
        }
        assertThat(list.firstVisibleItemIndex).isEqualTo(1)
        for ((frame, ratio) in drawn) {
            assertWithMessage("the caption drawn at ${"%.2f".format(ratio)} of its ink at rest on frame $frame; all: ${drawn.map { "%.2f".format(it.second) }}")
                .that(ratio).isAtLeast(STEP_FLOOR)
        }
    }

    private val frameDir = System.getenv("DROPDOWN_FLICKER_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private var framesKept = 0

    /** When `DROPDOWN_FLICKER_FRAMES` names a directory, [bitmap]'s transcript as the next numbered frame there, [label] beside it. */
    private fun keep(bitmap: Bitmap, label: String) {
        val dir = frameDir ?: return
        dir.mkdirs()
        val list = listBounds()
        val crop = Bitmap.createBitmap(bitmap, 0, list.top.toInt(), bitmap.width, (list.bottom - list.top).toInt())
        val n = framesKept++
        File(dir, "frame-%03d.png".format(n)).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "labels.txt").appendText("%03d %s\n".format(n, label))
    }

    private companion object {
        const val TURNS = 4
        const val STRETCH = "${10 + TURNS - 1} files"
        const val SCROLL_PX = 160f
        const val FRAMES = 48
        const val LIT = 90
        const val STEP_FLOOR = 0.8f
        const val CAPTION_LIT = 40
    }
}
