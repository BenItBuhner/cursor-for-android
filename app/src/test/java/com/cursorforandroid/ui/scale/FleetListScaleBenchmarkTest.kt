package com.cursorforandroid.ui.scale

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.fixtures.ScaleFleet
import com.cursorforandroid.ui.navigation.AppShell
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * The phone shell's agent list over a whole fleet ([ScaleFleet]) that moves every second: the sidebar opened from
 * home (tap to first rows, to settled), thirty seconds of the fleet ticking under the open list, the list dragged top
 * to bottom and back with every Project and the Big Project's children listed while it ticks, and a minute in the
 * background while the fleet goes on, then back. The real [AppShell] over the real repositories, the fakes in the
 * demo's seat, driven a frame at a time with the clock held. Every number is a `SCALE list-*` line (see
 * [ScaleMeter]); the assertions are generous guards on counts alone.
 *
 * `SCALE_FLEETS=S50,S200,S500` measures S500 as well (the default is S50 and S200).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class FleetListScaleBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.tearDown()
    }

    @Test
    fun `0 warm-up on S50, unmeasured`() {
        scenario(ScaleFleet.Size.S50, idleTicks = 5)
    }

    @Test
    fun `1 S50`() = measure(ScaleFleet.Size.S50)

    @Test
    fun `2 S200`() = measure(ScaleFleet.Size.S200)

    @Test
    fun `3 S500`() = measure(ScaleFleet.Size.S500)

    private fun measure(size: ScaleFleet.Size) {
        assumeTrue("$size is not in SCALE_FLEETS", size in ScaleFleet.Size.enabled())
        val r = scenario(size, idleTicks = IDLE_TICKS)
        r.all.forEach { println(it.line()) }
        // Guards on counts, far above what the fleets measure today: a regression that recomposes the list many
        // times over per tick, or composes the sidebar whole on every frame of a drag, trips them; a slow machine does not.
        assertWithMessage(r.open.line()).that(r.openFirstFrames).isAtMost(MAX_OPEN_FRAMES)
        assertWithMessage(r.idle.line()).that(r.idle.tickScopes.sorted()[r.idle.tickScopes.size / 2]).isAtMost(MAX_SCOPES_PER_TICK)
        assertWithMessage(r.scroll.line()).that(r.scroll.scopes / r.scroll.frames.coerceAtLeast(1)).isAtMost(MAX_SCOPES_PER_SCROLL_FRAME)
        assertWithMessage(r.idle.line()).that(r.idle.top.map { it.first }).containsNoneOf("agents.ChatRowMenu", "agents.ChatOverflowMenu")
        assertWithMessage(r.idle.line()).that(r.idle.extra.getValue("organizerPasses") as Int)
            .isAtMost(r.idle.extra.getValue("agentListChanges") as Int)
    }

    private class Result(val open: ScaleMeter.Phase, val openFirstFrames: Int, val idle: ScaleMeter.Phase, val scroll: ScaleMeter.Phase, val back: ScaleMeter.Phase) {
        val all get() = listOf(open, idle, scroll, back)
    }

    private val sidebarList = hasScrollToNodeAction() and hasAnyDescendant(hasText("Projects"))

    private fun scenario(size: ScaleFleet.Size, idleTicks: Int): Result {
        rig = ScaleRig(compose, size, now)
        val meter = rig.meter
        val loadMs = rig.start(bigTurns = 40)
        val homeMs = rig.showShell()
        val baseline = rig.counters()

        // Tap to first rows, to settled.
        val open = rig.phase("list-open")
        meter.measured(open) { compose.onNode(hasContentDescription("Open sidebar")).performClick() }
        var firstFrames = 1
        while (!rig.shown(sidebarList) && firstFrames < 240) { meter.frame(open); firstFrames++ }
        val firstMs = open.wall.sum()
        val slideFrames = rig.sidebarOpened(open)
        val settleFrames = slideFrames + meter.settle(open, quiet = 10, max = 240)
        meter.end(open)
        open.extra["firstFrames"] = firstFrames
        open.extra["firstMs"] = ScaleMeter.Phase.f(firstMs)
        open.extra["slideFrames"] = slideFrames
        open.extra["settledFrames"] = firstFrames + settleFrames
        open.extra["settledMs"] = ScaleMeter.Phase.f(open.wall.sum())
        open.extra["homeMs"] = homeMs
        open.extra["loadMs"] = loadMs
        open.extra["rows"] = rig.graph.agents.state.value.agents.size
        open.extra["sections"] = rig.vm.uiState.value.sections.joinToString("|") { "${it.key}:${it.rows.size}" }
        val list = rig.pinned(sidebarList)

        // Thirty seconds of the fleet ticking under the open list.
        var organizerBefore = rig.vm.organizerPasses
        var listChangesBefore = rig.vm.agentListChanges
        val idle = rig.phase("list-idle")
        repeat(idleTicks) { rig.tick(idle) }
        meter.end(idle, heap = true)
        (rig.counters() - baseline).into(idle)
        idle.extra["organizerPasses"] = rig.vm.organizerPasses - organizerBefore
        idle.extra["agentListChanges"] = rig.vm.agentListChanges - listChangesBefore
        idle.top = meter.attributed { repeat(ATTRIBUTION_TICKS) { rig.tick(null) } }.second

        // Top to bottom and back, ticking, every Project and the Big Project's children listed.
        val listed = listInFull(list)
        organizerBefore = rig.vm.organizerPasses
        listChangesBefore = rig.vm.agentListChanges
        val scroll = rig.phase("list-scroll")
        scroll.extra["listed"] = listed
        val downFrames = rig.drag(list, scroll, down = true)
        val upFrames = rig.drag(list, scroll, down = false)
        meter.end(scroll)
        scroll.extra["organizerPasses"] = rig.vm.organizerPasses - organizerBefore
        scroll.extra["agentListChanges"] = rig.vm.agentListChanges - listChangesBefore
        scroll.top = meter.attributed { rig.drag(list, ScaleMeter.Phase("unreported", size.name), down = true, maxFrames = ATTRIBUTION_DRAG_FRAMES) }.second
        scroll.extra["dragFramesDown"] = downFrames
        scroll.extra["dragFramesUp"] = upFrames
        scroll.extra["listRows"] = rig.vm.uiState.value.sections.sumOf { it.rows.size }

        // A minute in the background while the fleet goes on, then back.
        val back = rig.phase("list-return")
        val before = rig.counters()
        organizerBefore = rig.vm.organizerPasses
        listChangesBefore = rig.vm.agentListChanges
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        repeat(BACKGROUND_TICKS) {
            rig.fleet.deliver(rig.api, rig.graph, rig.fleet.tick())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val returnFrames = meter.settle(back, quiet = 10, max = 240)
        meter.end(back)
        (rig.counters() - before).into(back)
        back.extra["organizerPasses"] = rig.vm.organizerPasses - organizerBefore
        back.extra["agentListChanges"] = rig.vm.agentListChanges - listChangesBefore
        back.extra["settledFrames"] = returnFrames
        back.extra["settledMs"] = ScaleMeter.Phase.f(back.wall.sum())
        return Result(open, firstFrames, idle, scroll, back)
    }

    /** Every Project listed and the Big Project's children opened under it, as a reader going through them would. */
    private fun listInFull(list: SemanticsMatcher): String {
        val more = hasTestTag("section-more-${AgentListOrganizer.PROJECTS_KEY}")
        val listedAll = rig.shown(more)
        if (listedAll) {
            compose.onAllNodes(more).onFirst().performClick()
            rig.meter.settle(null, quiet = 5, max = 120)
        }
        val toggle = hasContentDescription("Show chats under ${rig.fleet.big.name}")
        runCatching { compose.onNode(list).performScrollToNode(toggle) }
        val opened = rig.shown(toggle)
        if (opened) {
            compose.onAllNodes(toggle).onFirst().performClick()
            rig.meter.settle(null, quiet = 5, max = 120)
        }
        runCatching { compose.onNode(list).performScrollToNode(hasText("Projects")) }
        rig.meter.settle(null, quiet = 5, max = 120)
        return "showMore=$listedAll,bigOpened=$opened"
    }

    private companion object {
        const val IDLE_TICKS = 30
        const val ATTRIBUTION_TICKS = 5
        const val BACKGROUND_TICKS = 60
        const val ATTRIBUTION_DRAG_FRAMES = 120
        const val MAX_SCOPES_PER_TICK = 40
        const val MAX_OPEN_FRAMES = 60
        const val MAX_SCOPES_PER_SCROLL_FRAME = 4
    }
}
