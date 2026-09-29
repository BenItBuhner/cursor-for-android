package com.cursorforandroid.ui.scale

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.fixtures.ScaleFleet
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The Big coordinator at [TURNS] turns with a turn of its own under way, a step a second (a tool call or a couple of
 * hundred characters of its reply), inside a whole fleet that moves every second: opened (tap to first rows, to
 * settled), thirty seconds of it streaming, then its older turns paged in ("Older messages", a few times) and its
 * transcript dragged up through them and back down while it streams. The real phone shell over the real repositories, the fakes in the demo's seat, a frame at a time
 * with the clock held. Every number is a `SCALE stream-*` line (see [ScaleMeter]); the assertions are generous guards
 * on counts alone.
 *
 * `SCALE_FLEETS=S50,S200,S500` measures S500 as well (the default is S50 and S200).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StreamingCoordinatorScaleBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig
    private var step = 0

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.tearDown()
        TranscriptPerf.clearAll()
    }

    @Test
    fun `0 warm-up on S50, unmeasured`() {
        scenario(ScaleFleet.Size.S50, idleTicks = 5, turns = WARM_UP_TURNS)
    }

    @Test
    fun `1 S50`() = measure(ScaleFleet.Size.S50)

    @Test
    fun `2 S200`() = measure(ScaleFleet.Size.S200)

    @Test
    fun `3 S500`() = measure(ScaleFleet.Size.S500)

    private fun measure(size: ScaleFleet.Size) {
        assumeTrue("$size is not in SCALE_FLEETS", size in ScaleFleet.Size.enabled())
        val r = scenario(size, idleTicks = IDLE_TICKS, turns = TURNS)
        r.all.forEach { println(it.line()) }
        assertWithMessage(r.idle.line()).that(r.idle.tickScopes.sorted()[r.idle.tickScopes.size / 2]).isAtMost(MAX_SCOPES_PER_TICK)
        assertWithMessage(r.scroll.line()).that(r.scroll.scopes / r.scroll.frames.coerceAtLeast(1)).isAtMost(MAX_SCOPES_PER_SCROLL_FRAME)
    }

    private class Result(val open: ScaleMeter.Phase, val idle: ScaleMeter.Phase, val older: ScaleMeter.Phase, val scroll: ScaleMeter.Phase) {
        val all get() = listOf(open, idle, older, scroll)
    }

    private fun scenario(size: ScaleFleet.Size, idleTicks: Int, turns: Int): Result {
        rig = ScaleRig(compose, size, now, replay = LIVE_REPLAY)
        val meter = rig.meter
        val big = rig.fleet.big
        val loadMs = rig.start(bigTurns = turns, live = true)
        rig.showShell()
        val stream = { rig.fleet.streamStep(rig.streamer, step++) }
        val conversation = { rig.graph.conversations.state(big.id).value }
        val rows = { TranscriptPerf.sessionOrNull(big.id)?.snapshot()?.rowCompositions ?: 0 }

        // Opened as a link or a notification opens it: to the first rows, to settled and following the live turn.
        val open = rig.phase("stream-open")
        val before = rig.counters()
        val tapped = System.nanoTime()
        meter.measured(open) { rig.openByLink(big.id) }
        val firstContent = { conversation().items.isNotEmpty() && rig.shown(hasTestTag("transcript")) }
        val (firstFrames, firstRealMs) = rig.framesUntil(open, maxMillis = 30_000, done = firstContent)
        check(firstContent()) { "the coordinator's transcript did not show within 30 s: ${describe(conversation())}" }
        val firstMainMs = open.wall.sum()
        val loaded = { conversation().let { !it.isLoading && it.isStreaming && it.traceStatus.pending == 0 } }
        rig.framesUntil(open, maxMillis = SETTLE_MAX_MS, done = loaded)
        open.extra["loaded"] = loaded()
        open.extra["state"] = describe(conversation())
        open.extra["quietFrames"] = meter.settle(open, quiet = 10, max = 600)
        meter.end(open)
        (rig.counters() - before).into(open)
        open.extra["firstFrames"] = firstFrames + 1
        open.extra["firstRealMs"] = firstRealMs
        open.extra["firstMainMs"] = ScaleMeter.Phase.f(firstMainMs)
        open.extra["settledRealMs"] = (System.nanoTime() - tapped) / 1_000_000
        open.extra["settledMainMs"] = ScaleMeter.Phase.f(open.wall.sum())
        open.extra["items"] = conversation().items.size
        open.extra["streaming"] = conversation().isStreaming
        open.extra["rowsComposed"] = rows()
        open.extra["loadMs"] = loadMs

        // Thirty seconds of the coordinator streaming a step a second while the fleet ticks.
        val idle = rig.phase("stream-idle")
        val rows0 = rows()
        repeat(idleTicks) { rig.tick(idle, also = stream) }
        meter.end(idle, heap = true)
        idle.extra["rowsComposed"] = rows() - rows0
        idle.extra["items"] = conversation().items.size
        idle.top = meter.attributed { repeat(ATTRIBUTION_TICKS) { rig.tick(null, also = stream) } }.second

        // Older messages a page at a time, then down through them to the live turn and back up, streaming.
        val transcript = hasTestTag("transcript")
        val older = rig.phase("stream-older")
        rig.loadOlder(older, big.id, transcript, pages = OLDER_PAGES, also = stream)
        meter.end(older)
        val scroll = rig.phase("stream-scroll")
        val rows1 = rows()
        scroll.extra["dragFramesDown"] = rig.drag(transcript, scroll, down = true, maxFrames = DRAG_FRAMES, also = stream)
        scroll.extra["dragFramesUp"] = rig.drag(transcript, scroll, down = false, maxFrames = DRAG_FRAMES, also = stream)
        meter.end(scroll)
        scroll.extra["rowsComposed"] = rows() - rows1
        scroll.top = meter.attributed { rig.drag(transcript, ScaleMeter.Phase("unreported", size.name), down = true, maxFrames = ATTRIBUTION_DRAG_FRAMES, also = stream) }.second
        return Result(open, idle, older, scroll)
    }

    private fun describe(state: ConversationState): String =
        "items:${state.items.size},loading:${state.isLoading},pending:${state.traceStatus.pending},older:${state.hasOlder},streaming:${state.isStreaming}"

    private companion object {
        /** How long the open waits for the window's traces to land before it reports what stands. */
        const val SETTLE_MAX_MS = 5_000L
        /** The 5 000-turn variant of the Big coordinator ([BigProject.TURNS] is the 2 000-turn one). */
        const val TURNS = 5_000
        const val WARM_UP_TURNS = 200
        const val IDLE_TICKS = 30
        const val ATTRIBUTION_TICKS = 5
        const val DRAG_FRAMES = 330
        const val OLDER_PAGES = 10
        const val ATTRIBUTION_DRAG_FRAMES = 120
        /** Events of the live turn kept for a reconnect to replay: more than the scenario streams. */
        const val LIVE_REPLAY = 4_096
        const val MAX_SCOPES_PER_TICK = 4_000
        const val MAX_SCOPES_PER_SCROLL_FRAME = 400
    }
}
