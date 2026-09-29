package com.cursorforandroid.ui.scale

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * The Big Project ([BigProject]'s 2 000-turn coordinator, [ScaleFleet.Size.bigChildren] children) inside a whole
 * fleet that moves every second: opened from the sidebar (tap to the first rows of its transcript, to settled), its
 * panel opened on the Project section listing every child, thirty seconds of the fleet ticking with the panel open,
 * then the children list dragged to its end and back, the coordinator's older turns paged in ("Older messages", a few
 * times) and its transcript dragged up through them and back, ticking. The real phone shell over the real repositories, the fakes in the demo's seat, a frame at a
 * time with the clock held. Every number is a `SCALE big-*` line (see [ScaleMeter]); the assertions are generous
 * guards on counts alone.
 *
 * `SCALE_FLEETS=S50,S200,S500` measures S500 as well (the default is S50 and S200).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class BigProjectScaleBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig

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
        val r = scenario(size, idleTicks = IDLE_TICKS, turns = BigProject.TURNS)
        r.all.forEach { println(it.line()) }
        assertWithMessage(r.panel.line()).that(r.primaries).isGreaterThan(0)
        assertWithMessage(r.idle.line()).that(r.idle.tickScopes.sorted()[r.idle.tickScopes.size / 2]).isAtMost(MAX_SCOPES_PER_TICK)
        assertWithMessage(r.panelScroll.line()).that(r.panelScroll.scopes / r.panelScroll.frames.coerceAtLeast(1)).isAtMost(MAX_SCOPES_PER_SCROLL_FRAME)
        assertWithMessage(r.transcriptScroll.line()).that(r.transcriptScroll.scopes / r.transcriptScroll.frames.coerceAtLeast(1)).isAtMost(MAX_SCOPES_PER_SCROLL_FRAME)
    }

    private class Result(
        val open: ScaleMeter.Phase,
        val panel: ScaleMeter.Phase,
        val primaries: Int,
        val idle: ScaleMeter.Phase,
        val panelScroll: ScaleMeter.Phase,
        val older: ScaleMeter.Phase,
        val transcriptScroll: ScaleMeter.Phase,
    ) {
        val all get() = listOf(open, panel, idle, panelScroll, older, transcriptScroll)
    }

    private val sidebarList = hasScrollToNodeAction() and hasAnyDescendant(hasText("Projects"))

    private fun scenario(size: ScaleFleet.Size, idleTicks: Int, turns: Int): Result {
        rig = ScaleRig(compose, size, now)
        val meter = rig.meter
        val big = rig.fleet.big
        val loadMs = rig.start(bigTurns = turns)
        rig.showShell()
        compose.onNode(hasContentDescription("Open sidebar")).performClick()
        rig.framesUntil(null) { rig.shown(sidebarList) }
        rig.sidebarOpened(null)
        meter.settle(null, quiet = 10, max = 240)
        val list = rig.pinned(sidebarList)
        val bigRow = hasText(big.name) and hasAnyAncestor(list)
        compose.onNode(list).performScrollToNode(bigRow)
        meter.settle(null, quiet = 5, max = 60)

        // Tap to the transcript's first rows, to settled.
        val open = rig.phase("big-open")
        val before = rig.counters()
        val tapped = System.nanoTime()
        meter.measured(open) { compose.onAllNodes(bigRow).onFirst().performClick() }
        val conversation = { rig.graph.conversations.state(big.id).value }
        val firstContent = { conversation().items.isNotEmpty() && rig.shown(hasTestTag("transcript")) }
        val (firstFrames, firstRealMs) = rig.framesUntil(open, maxMillis = 30_000, done = firstContent)
        check(firstContent()) { "the coordinator's transcript did not show within 30 s: ${describe(conversation())}" }
        val firstMainMs = open.wall.sum()
        val loaded = { conversation().let { !it.isLoading && it.traceStatus.pending == 0 } }
        rig.framesUntil(open, maxMillis = SETTLE_MAX_MS, done = loaded)
        open.extra["loaded"] = loaded()
        open.extra["state"] = describe(conversation())
        val quietFrames = meter.settle(open, quiet = 10, max = 600)
        meter.end(open)
        (rig.counters() - before).into(open)
        open.extra["firstFrames"] = firstFrames + 1
        open.extra["firstRealMs"] = firstRealMs
        open.extra["firstMainMs"] = ScaleMeter.Phase.f(firstMainMs)
        open.extra["settledFrames"] = open.frames
        open.extra["settledRealMs"] = (System.nanoTime() - tapped) / 1_000_000
        open.extra["settledMainMs"] = ScaleMeter.Phase.f(open.wall.sum())
        open.extra["quietFrames"] = quietFrames
        open.extra["traceLine"] = traceLine()
        open.extra["items"] = conversation().items.size
        open.extra["hasOlder"] = conversation().hasOlder
        open.extra["rowsComposed"] = TranscriptPerf.sessionOrNull(big.id)?.snapshot()?.rowCompositions ?: 0
        open.extra["loadMs"] = loadMs

        // The panel, on the Project section listing every child.
        rig.framesUntilStill(null, hasContentDescription("Open panel"))
        val panel = rig.phase("big-panel-open")
        meter.measured(panel) { compose.onNode(hasContentDescription("Open panel")).performClick() }
        val slideFrames = rig.framesUntilStill(panel, hasTestTag("panel-tab-details"))
        meter.measured(panel) { compose.onNode(hasTestTag("panel-tab-details")).performClick() }
        rig.framesUntil(panel, maxMillis = 10_000) { rig.shown(hasTestTag("panel-sections")) }
        check(rig.shown(hasTestTag("panel-sections"))) { "the panel's Details tab did not open" }
        val (primaryFrames, primaryRealMs) = rig.framesUntil(panel, maxMillis = 30_000) { rig.shown(hasTestTag("project-primary")) }
        panel.extra["slideFrames"] = slideFrames
        meter.settle(panel, quiet = 10, max = 240)
        meter.end(panel)
        val primaries = compose.onAllNodes(hasTestTag("project-primary")).fetchSemanticsNodes().size
        panel.extra["primaryFrames"] = primaryFrames
        panel.extra["primaryRealMs"] = primaryRealMs
        panel.extra["primariesOnScreen"] = primaries
        panel.extra["settledMainMs"] = ScaleMeter.Phase.f(panel.wall.sum())
        panel.extra["primariesLabel"] = compose.onAllNodes(hasText("Primaries", substring = true)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.replace(' ', '_') ?: "-"

        // Thirty seconds of the fleet ticking with the coordinator and its panel open.
        val idle = rig.phase("big-idle")
        val idleBefore = rig.counters()
        repeat(idleTicks) { rig.tick(idle) }
        meter.end(idle, heap = true)
        (rig.counters() - idleBefore).into(idle)
        idle.top = meter.attributed { repeat(ATTRIBUTION_TICKS) { rig.tick(null) } }.second

        // The children list to its end and back, ticking.
        val sections = rig.pinned(hasTestTag("panel-sections"))
        val panelScroll = rig.phase("big-panel-scroll")
        panelScroll.extra["dragFramesDown"] = rig.drag(sections, panelScroll, down = true)
        panelScroll.extra["dragFramesUp"] = rig.drag(sections, panelScroll, down = false)
        meter.end(panelScroll)
        panelScroll.top = meter.attributed { rig.drag(sections, ScaleMeter.Phase("unreported", size.name), down = true, maxFrames = ATTRIBUTION_DRAG_FRAMES) }.second

        // The panel shut, the coordinator's older turns paged in, then its transcript down to the newest and back up, ticking.
        compose.onNode(hasContentDescription("Close panel")).performClick()
        rig.framesUntil(null, maxMillis = 10_000) { !rig.shown(hasTestTag("panel-sections")) }
        meter.settle(null, quiet = 10, max = 240)
        val transcript = hasTestTag("transcript")
        // The open shows the newest turns; the older ones are a tap on "Older messages" away, a page at a time.
        val older = rig.phase("big-transcript-older")
        rig.loadOlder(older, big.id, transcript, pages = OLDER_PAGES)
        meter.end(older)
        val transcriptScroll = rig.phase("big-transcript-scroll")
        val composed0 = TranscriptPerf.sessionOrNull(big.id)?.snapshot()?.rowCompositions ?: 0
        transcriptScroll.extra["dragFramesDown"] = rig.drag(transcript, transcriptScroll, down = true, maxFrames = TRANSCRIPT_DRAG_FRAMES)
        transcriptScroll.extra["dragFramesUp"] = rig.drag(transcript, transcriptScroll, down = false, maxFrames = TRANSCRIPT_DRAG_FRAMES)
        meter.end(transcriptScroll)
        transcriptScroll.extra["rowsComposed"] = (TranscriptPerf.sessionOrNull(big.id)?.snapshot()?.rowCompositions ?: 0) - composed0
        transcriptScroll.top = meter.attributed { rig.drag(transcript, ScaleMeter.Phase("unreported", size.name), down = true, maxFrames = ATTRIBUTION_DRAG_FRAMES) }.second
        return Result(open, panel, primaries, idle, panelScroll, older, transcriptScroll)
    }

    /** What the transcript's trace status line says, if it is on screen. */
    private fun traceLine(): String = compose.onAllNodes(hasTestTag("trace-status"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.let { node -> (node.children + node).firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text } } }
        ?.replace(' ', '_') ?: "-"

    private fun describe(state: ConversationState): String =
        "items:${state.items.size},loading:${state.isLoading},pending:${state.traceStatus.pending},older:${state.hasOlder},streaming:${state.isStreaming}"

    private companion object {
        /** How long the open waits for the window's traces to land before it reports what stands. */
        const val SETTLE_MAX_MS = 5_000L
        const val WARM_UP_TURNS = 200
        const val IDLE_TICKS = 30
        const val ATTRIBUTION_TICKS = 5
        const val ATTRIBUTION_DRAG_FRAMES = 120
        const val TRANSCRIPT_DRAG_FRAMES = 330
        const val OLDER_PAGES = 10
        const val MAX_SCOPES_PER_TICK = 4_000
        const val MAX_SCOPES_PER_SCROLL_FRAME = 400
    }
}
