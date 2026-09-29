package com.cursorforandroid.ui.scale

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.fixtures.ScaleFleet
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.runBlocking

/**
 * One scale scenario's world: the [ScaleFleet] in the fake server, the app's graph over it (the fakes in the demo's
 * seat: no disk, no network), the real phone shell on screen with the clock held, and the [ScaleMeter] watching it.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, ExperimentalMaterial3Api::class)
class ScaleRig(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    val size: ScaleFleet.Size,
    now: Long,
    replay: Int = 256,
) {
    val api = FakeCursorApi()
    val streamer = FakeRunStreamer(replay)
    val fleet = ScaleFleet(size, now)
    val meter = ScaleMeter(compose)
    lateinit var graph: AppGraph
        private set
    private var deepLink by mutableStateOf<String?>(null)

    val vm: AgentsViewModel get() = ViewModelProvider(compose.activity, AgentsViewModel.Factory(graph))[AgentsViewModel::class.java]

    fun phase(scenario: String) = meter.begin(ScaleMeter.Phase(scenario, size.name))

    fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The node [matcher] finds now, found by its id from here on: a list stays itself whatever scrolls out of it. */
    fun pinned(matcher: SemanticsMatcher): SemanticsMatcher {
        val id = compose.onAllNodes(matcher).fetchSemanticsNodes().first().id
        return SemanticsMatcher("node #$id") { it.id == id }
    }

    /**
     * The fleet in the server with the Big coordinator's [bigTurns] turns (and a live one with [live]), the graph
     * signed in to it and the list the app restores on a cold start: every page read and placed. How long that took.
     */
    fun start(bigTurns: Int, live: Boolean = false): Long {
        AppClock.nowMillis = { fleet.at }
        fleet.install(api)
        fleet.installBigTranscript(api, streamer, bigTurns, live)
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        val started = System.nanoTime()
        runBlocking { graph.agents.refresh() }
        runBlocking { while (graph.agents.state.value.hasMore) graph.agents.loadMore() }
        fleet.place(graph)
        return (System.nanoTime() - started) / 1_000_000
    }

    /** The phone shell on screen at home, the list loaded, the clock then held and the screen settled; how long home took. */
    fun showShell(): Long {
        val started = System.nanoTime()
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(meter.recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                        isDemo = true,
                        wide = false,
                        deepLinkAgentId = deepLink,
                        onDeepLinkConsumed = { deepLink = null },
                    )
                }
            }
        }
        compose.waitUntil(30_000) { shown(hasContentDescription("Open sidebar")) }
        val homeMs = (System.nanoTime() - started) / 1_000_000
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        meter.settle(null, quiet = 10, max = 240)
        return homeMs
    }

    /**
     * Frames until the sidebar has slid all the way open, the frames it took. The slide recomposes nothing, so a
     * settle ends before it does, and a tap while it moves is the drawer's (it stops the slide) rather than the row's.
     */
    fun sidebarOpened(phase: ScaleMeter.Phase?, maxMillis: Long = 10_000): Int {
        val pane = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigation menu")
        val left = { compose.onAllNodes(pane).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left }
        return framesUntil(phase, maxMillis) { left()?.let { it >= 0f } == true }.first
    }

    /** Frames until the node [matcher] finds is on screen and has held still for [stillFrames] frames; the frames it took. */
    fun framesUntilStill(phase: ScaleMeter.Phase?, matcher: SemanticsMatcher, maxMillis: Long = 20_000, stillFrames: Int = 3): Int {
        var last: Rect? = null
        var still = 0
        return framesUntil(phase, maxMillis) {
            val bounds = compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            still = if (bounds != null && bounds == last) still + 1 else 0
            last = bounds
            still >= stillFrames
        }.first
    }

    /**
     * [pages] pages of [agentId]'s older turns paged into its [transcript], each until the older turns are in and the
     * screen has settled, the fleet ticking under it every [FRAMES_PER_TICK] frames. A page is "Older messages"
     * tapped, or (`scroll`) the one the transcript asks for itself when the unmeasured scroll back to its top comes
     * near it, of which only the frames after the scroll are measured. Each page's frames, wall time and way in go into
     * [phase]'s extras; how many pages there were.
     */
    fun loadOlder(phase: ScaleMeter.Phase, agentId: String, transcript: SemanticsMatcher, pages: Int, also: () -> Unit = {}): Int {
        val button = hasTestTag("load-older")
        val state = { graph.conversations.state(agentId).value }
        var loaded = 0
        while (loaded < pages && state().hasOlder) {
            check(shown(transcript)) { "the transcript left the screen after $loaded older pages" }
            val before = state().items.size
            val wall0 = phase.wall.sum()
            if (!shown(button)) toTop(transcript)
            val way = when {
                state().isLoadingOlder || state().items.size > before -> "scroll"
                shown(button) -> {
                    // The button's own action, not a touch at its middle: scrolled to the top under a held clock, the
                    // row can stand under the header, which takes the tap and leaves the conversation.
                    meter.measured(phase) { compose.onAllNodes(hasClickAction() and hasAnyAncestor(button)).onFirst().performSemanticsAction(SemanticsActions.OnClick) }
                    "tap"
                }
                else -> {
                    phase.extra["olderStop"] = "no-button,hasOlder=${state().hasOlder}"
                    break
                }
            }
            var sinceTick = 0
            val (frames, realMs) = framesUntil(phase, maxMillis = 20_000) {
                if (++sinceTick >= FRAMES_PER_TICK) {
                    sinceTick = 0
                    fleet.deliver(api, graph, fleet.tick())
                    also()
                    phase.ticks++
                }
                state().let { !it.isLoadingOlder && it.items.size > before }
            }
            val settled = meter.settle(phase, quiet = 5, max = 240)
            if (state().items.size <= before) {
                phase.extra["olderStop"] = "no-load,${frames}f/${realMs}ms,hasOlder=${state().hasOlder}"
                break
            }
            loaded++
            phase.extra["older$loaded"] = "${frames + settled}f/${realMs}ms/${ScaleMeter.Phase.f(phase.wall.sum() - wall0)}mainMs/+${state().items.size - before}items/$way"
        }
        phase.extra["olderPages"] = loaded
        phase.extra["items"] = state().items.size
        return loaded
    }

    /**
     * [list] back to its first item, unmeasured: a scroll to an item off screen lays the list out a frame at a time,
     * which a held clock never gives it.
     */
    fun toTop(list: SemanticsMatcher) {
        compose.mainClock.autoAdvance = true
        try {
            compose.onNode(list).performScrollToIndex(0)
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = false
        }
    }

    /** Opens [agentId] the way a notification or a link does. */
    fun openByLink(agentId: String) {
        deepLink = agentId
    }

    /**
     * Frames at a phone's pace (a sleep of [paceMs] before each) until [done], at most [maxMillis] of real time;
     * the frames it took and the real time it took.
     */
    fun framesUntil(phase: ScaleMeter.Phase?, maxMillis: Long = 60_000, paceMs: Long = 4, done: () -> Boolean): Pair<Int, Long> {
        val started = System.nanoTime()
        var frames = 0
        while (!done() && (System.nanoTime() - started) / 1_000_000 < maxMillis) {
            Thread.sleep(paceMs)
            meter.frame(phase)
            frames++
        }
        return frames to (System.nanoTime() - started) / 1_000_000
    }

    /**
     * One second of the fleet: the tick handed to the app (and [also], e.g. a step of a live run), then a second of
     * frames, paced until what the app publishes off the main thread has landed ([changed]; by default, the first
     * frame that recomposes anything).
     */
    fun tick(phase: ScaleMeter.Phase?, also: () -> Unit = {}, changed: (() -> Boolean)? = null) {
        val state = vm.uiState.value
        val scopes0 = meter.recompositions.scopes
        val t = fleet.tick()
        val t0 = System.nanoTime()
        fleet.deliver(api, graph, t)
        also()
        val deliverMs = (System.nanoTime() - t0) / 1e6
        val landed = changed ?: { vm.uiState.value !== state && meter.recompositions.scopes > scopes0 }
        if (phase == null) {
            meter.frames(null, FRAMES_PER_TICK, changed = landed)
            return
        }
        val scopes = phase.scopes
        val alloc = phase.allocated
        meter.frames(phase, FRAMES_PER_TICK, changed = landed)
        phase.ticks++
        phase.deliverMs += deliverMs
        phase.tickScopes += phase.scopes - scopes
        phase.tickAlloc += phase.allocated - alloc
    }

    /**
     * A finger's drags over [target] toward its end ([down]: content moving up) or its start, [DRAG_STEP] pixels a
     * frame, lifted and put down again every [STROKE_FRAMES] frames, the fleet ticking every [FRAMES_PER_TICK] frames
     * (with [also] on each tick), until the list can go no further or [maxFrames] have run; the frames it took.
     */
    fun drag(target: SemanticsMatcher, phase: ScaleMeter.Phase, down: Boolean, maxFrames: Int = MAX_DRAG_FRAMES, also: () -> Unit = {}): Int {
        val node = compose.onNode(target)
        val sign = if (down) -1f else 1f
        var frames = 0
        var sinceTick = 0
        while (frames < maxFrames && !atEnd(target, down)) {
            node.performTouchInput { down(Offset(centerX, centerY - sign * height * 0.3f)); moveBy(Offset(0f, sign * (viewConfiguration.touchSlop + 1f))) }
            repeat(STROKE_FRAMES) {
                node.performTouchInput { moveBy(Offset(0f, sign * DRAG_STEP)) }
                if (++sinceTick >= FRAMES_PER_TICK) {
                    sinceTick = 0
                    fleet.deliver(api, graph, fleet.tick())
                    also()
                    phase.ticks++
                }
                meter.frame(phase)
                frames++
            }
            node.performTouchInput { advanceEventTime(200); up() }
            meter.frame(phase)
            frames++
        }
        return frames
    }

    private fun atEnd(target: SemanticsMatcher, down: Boolean): Boolean {
        val range = compose.onAllNodes(target).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return true
        val forward = down != range.reverseScrolling
        return if (forward) range.value() >= range.maxValue() - 1f else range.value() <= 0.5f
    }

    /** The fake server's request counts, to see what a stretch asked of the network. */
    data class Counters(val listAgents: Int, val listV0: Int, val getRun: Int, val listRuns: Int, val conversation: Int, val getAgent: Int, val streams: Int) {
        operator fun minus(o: Counters) = Counters(listAgents - o.listAgents, listV0 - o.listV0, getRun - o.getRun, listRuns - o.listRuns, conversation - o.conversation, getAgent - o.getAgent, streams - o.streams)
        fun into(phase: ScaleMeter.Phase) {
            phase.extra["reqListAgents"] = listAgents
            phase.extra["reqListV0"] = listV0
            phase.extra["reqGetRun"] = getRun
            phase.extra["reqListRuns"] = listRuns
            phase.extra["reqGetAgent"] = getAgent
            phase.extra["reqConversation"] = conversation
            phase.extra["streams"] = streams
        }
    }

    fun counters() = Counters(api.listAgentsCalls, api.listAgentsV0Calls, api.getRunCalls, api.listRunsCalls, api.conversationCalls, api.getAgentCalls, streamer.connections.size)

    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    companion object {
        const val FRAMES_PER_TICK = 60
        const val STROKE_FRAMES = 10
        const val DRAG_STEP = 60f
        const val MAX_DRAG_FRAMES = 600
    }
}
