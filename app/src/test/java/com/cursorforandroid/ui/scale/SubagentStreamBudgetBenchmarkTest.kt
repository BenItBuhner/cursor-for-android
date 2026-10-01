package com.cursorforandroid.ui.scale

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.SseFrame
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.api.StreamThreads
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.fixtures.ScaleFleet
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A Project coordinator's transcript with 128 cloud workers all working at once ([SubagentStretchScene]: seven
 * stretches closed, the eighth open, its rows listed below it as far as the phone's screen reaches), and with five
 * hundred ([SubagentStretchScene.fiveHundred]), inside a whole fleet ([ScaleFleet], S200 and S500) that ticks every
 * second. Every worker's run streams about a hundred events a second (never more) from an HTTP/2 server through the
 * app's own [SseRunStreamer][com.cursorforandroid.data.api.SseRunStreamer] (see [WireStreams]), its
 * [LiveRunHub][com.cursorforandroid.data.repo.LiveRunHub] and
 * [SubagentActivity][com.cursorforandroid.data.repo.SubagentActivity]: thinking, a file read, a step announced, a
 * reply a word at a time. Frames are paced at sixty a second of real time with the clock held (see [ScaleMeter]).
 *
 * `SCALE subagent-streams` reports the frames' wall and main-thread CPU times, the main thread's allocations, the
 * scopes recomposed per frame, the shared dispatcher pool's CPU and allocations (the hub's reading of the streams
 * included: IO and Default share it), how many of the subagents' states were published per second and how many of
 * their derivations reached the main thread, and the streams: one per worker, every one read, counted and not capped;
 * the events each stream's run took in per second against those its server wrote, the most any was ever behind its
 * server and the longest any went without an event,
 * and the threads: those held by the streams' reading (a thread parked in the reader, or the multiplexer's own) at
 * their most, and the process's.
 *
 * `SCALE_FLEETS=S200,S500` measures S500 as well (the default is S50 and S200).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentStreamBudgetBenchmarkTest {

    /**
     * Effects queued and run on the main thread, in its frames, as a phone's main looper runs them. The rule's own
     * dispatcher is unconfined: a collector woken by the hub's IO thread would go on on that thread, and the work a
     * phone does on its main thread would not be measured there.
     */
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.tearDown()
        if (::wire.isInitialized) wire.close()
    }

    @Test
    fun `0 warm-up on S50, unmeasured`() {
        assumeTrue("warm-up for S200", ScaleFleet.Size.S200 in ScaleFleet.Size.enabled())
        scenario(ScaleFleet.Size.S50, frames = WARM_UP_FRAMES)
    }

    @Test
    fun `1 S200`() = measure(ScaleFleet.Size.S200)

    @Test
    fun `2 S500`() = measure(ScaleFleet.Size.S500)

    @Test
    fun `3 S200, 500 workers`() = measure(ScaleFleet.Size.S200, SubagentStretchScene.fiveHundred, MAX_SCOPES_PER_FRAME_FIVE_HUNDRED)

    @Test
    fun `4 S500, 500 workers`() = measure(ScaleFleet.Size.S500, SubagentStretchScene.fiveHundred, MAX_SCOPES_PER_FRAME_FIVE_HUNDRED)

    private fun measure(size: ScaleFleet.Size, layout: SubagentStretchScene.Layout = SubagentStretchScene.standard, maxScopesPerFrame: Double = MAX_SCOPES_PER_FRAME) {
        assumeTrue("$size is not in SCALE_FLEETS", size in ScaleFleet.Size.enabled())
        val r = scenario(size, frames = MEASURED_FRAMES, layout = layout)
        println(r.phase.line())
        val line = r.phase.line()
        val workers = layout.workers.size
        // Every worker has its stream, and every stream is read to its newest event.
        assertWithMessage(line).that(r.streams).isEqualTo(workers)
        assertWithMessage(line).that(r.opened).isEqualTo(workers)
        assertWithMessage(line).that(r.unread).isEqualTo(0)
        assertWithMessage(line).that(r.workingLines).isEqualTo(layout.stretches)
        // Every stream live all along: none fell a second behind what its server wrote, each took in all of it.
        assertWithMessage(line).that(r.stalled).isEqualTo(0)
        assertWithMessage(line).that(r.minDeliveredShare).isAtLeast(MIN_DELIVERED_SHARE)
        assertWithMessage(line).that(r.minEventsPerSec).isAtLeast(MIN_EVENTS_PER_SEC)
        // The streams' reading holds a few threads, however many streams there are.
        assertWithMessage(line).that(r.readerThreadsPeak).isAtMost(MAX_READER_THREADS)
        // One collector per worker's run, shared by its row and its stretch's line.
        assertWithMessage(line).that(r.hubSubscribers).isAtMost(workers)
        // What the subagents' states cost the main thread: derivations never on it, one publication a frame for all.
        assertWithMessage(line).that(r.mainDeliveriesPerSecond).isAtMost(MAX_MAIN_DELIVERIES_PER_SEC)
        assertWithMessage(line).that(r.phase.allocated / 1024.0 / r.phase.frames).isAtMost(MAX_MAIN_ALLOC_KB_PER_FRAME)
        assertWithMessage(line).that(r.phase.scopes.toDouble() / r.phase.frames).isAtMost(maxScopesPerFrame)
    }

    private class Result(
        val phase: ScaleMeter.Phase,
        val streams: Int,
        val opened: Int,
        val unread: Int,
        val stalled: Int,
        val minDeliveredShare: Double,
        val minEventsPerSec: Double,
        val readerThreadsPeak: Int,
        val workingLines: Int,
        val hubSubscribers: Int,
        val mainDeliveriesPerSecond: Double,
    )

    private lateinit var wire: WireStreams

    private fun scenario(size: ScaleFleet.Size, frames: Int, layout: SubagentStretchScene.Layout = SubagentStretchScene.standard): Result {
        val workers = layout.workers
        // What the server writes is what the fake streamer is handed elsewhere (see SubagentStreamEquivalenceTest).
        for (w in 0 until 4) for (k in 0L until WorkerScript.ROUND.toLong()) {
            val (event, data) = WorkerScript.frame(w, k)
            check(SseParser.toEvent(SseFrame(event, null, data)) == WorkerScript.event(w, k)) { "worker $w event $k" }
        }
        wire = WireStreams(workers.map { "run-$it" }, BEAT_MS)
        rig = ScaleRig(compose, size, now, route = wire::streamer)
        SubagentStretchScene.install(rig.api, now, layout)
        rig.start(bigTurns = BIG_TURNS)
        val graph = rig.graph
        val meter = rig.meter

        val mainLooper = Looper.getMainLooper()
        val mainDeliveries = AtomicLong()
        val deliveries = AtomicLong()
        val source: (String) -> Flow<SubagentChild?> = { id ->
            graph.subagentActivity.of(id).onEach {
                deliveries.incrementAndGet()
                if (Looper.myLooper() == mainLooper) mainDeliveries.incrementAndGet()
            }
        }
        compose.setContent { SubagentStretchScene.Screen(graph, source, meter, layout) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        wire.start()
        val watch = StreamWatch(workers.size, wire::written) { w -> graph.liveRuns.current(workers[w], "run-${workers[w]}")?.eventCount ?: 0 }
        try {
            paced(null, WARM_FRAMES)
            val publications = AtomicLong()
            val writes = Snapshot.registerGlobalWriteObserver { written -> if ((written as? State<*>)?.value is SubagentChild) publications.incrementAndGet() }
            val phase = rig.phase("subagent-streams")
            val deliveries0 = deliveries.get()
            val main0 = mainDeliveries.get()
            val written0 = LongArray(workers.size) { wire.written(it) }
            watch.begin()
            val started = System.nanoTime()
            paced(phase, frames)
            meter.end(phase)
            val realSeconds = (System.nanoTime() - started) / 1e9
            val watched = watch.end()
            writes.dispose()
            val writtenPerSec = DoubleArray(workers.size) { (wire.written(it) - written0[it]) / realSeconds }
            val deliveredPerSec = DoubleArray(workers.size) { watched.taken[it] / realSeconds }
            val rates = deliveredPerSec.sorted()
            val share = workers.indices.minOf { deliveredPerSec[it] / writtenPerSec[it].coerceAtLeast(1e-9) }
            val stalled = watched.maxLag.count { it > STALL_EVENTS }
            val mainPerSec = (mainDeliveries.get() - main0) / realSeconds
            val mainCpuPerSec = phase.cpu.sum() / realSeconds
            phase.extra["realSec"] = ScaleMeter.Phase.f(realSeconds)
            phase.extra["fps"] = ScaleMeter.Phase.f(phase.frames / realSeconds)
            phase.extra["mainCpuMsPerRealSec"] = ScaleMeter.Phase.f(mainCpuPerSec)
            phase.extra["mainAllocKBPerFrame"] = (phase.allocated / 1024 / phase.frames.coerceAtLeast(1))
            phase.extra["scopesPerFrame"] = ScaleMeter.Phase.f(phase.scopes.toDouble() / phase.frames.coerceAtLeast(1))
            phase.extra["publicationsPerSec"] = ScaleMeter.Phase.f(publications.get() / realSeconds)
            phase.extra["derivationsPerSec"] = ScaleMeter.Phase.f((deliveries.get() - deliveries0) / realSeconds)
            phase.extra["mainDeliveriesPerSec"] = ScaleMeter.Phase.f(mainPerSec)
            phase.extra["workers"] = workers.size
            phase.extra["eventsPerSec"] = ScaleMeter.Phase.f(writtenPerSec.sum())
            phase.extra["streams"] = wire.streaming()
            phase.extra["opened"] = wire.opened()
            phase.extra["readerThreadsPeak"] = watched.readersPeak
            phase.extra["processThreadsPeak"] = watched.threadsPeak
            phase.extra["appThreadsPeak"] = watched.appThreadsPeak
            phase.extra["eventsPerSecPerStream"] = "min:${ScaleMeter.Phase.f(rates.first())},p50:${ScaleMeter.Phase.f(rates[rates.size / 2])},max:${ScaleMeter.Phase.f(rates.last())}"
            phase.extra["writtenPerSecPerStreamP50"] = ScaleMeter.Phase.f(writtenPerSec.sorted()[workers.size / 2])
            phase.extra["minDeliveredShare"] = ScaleMeter.Phase.f(share)
            phase.extra["maxLagEvents"] = watched.maxLag.max()
            phase.extra["longestSilenceMs"] = watched.longestGapMs.max()
            phase.extra["stalled"] = stalled
            val hub = graph.liveRuns.stats()
            phase.extra["hub"] = hub.replace(' ', ',')
            wire.stop()
            // What every stream said has reached the hub: nothing held back, nothing sampled.
            val unread = settledUnread(workers)
            val lines = SubagentStretchScene.lines(compose)
            phase.extra["stretchesShown"] = lines.size
            phase.extra["rowsShown"] = compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes().size
            phase.extra["unread"] = unread
            return Result(
                phase = phase,
                streams = phase.extra["streams"] as Int,
                opened = wire.opened(),
                unread = unread,
                stalled = stalled,
                minDeliveredShare = share,
                minEventsPerSec = rates.first(),
                readerThreadsPeak = watched.readersPeak,
                workingLines = lines.count { "${layout.perStretch} Working" in it },
                hubSubscribers = Regex("subscribers=(\\d+)").find(hub)!!.groupValues[1].toInt(),
                mainDeliveriesPerSecond = mainPerSec,
            )
        } finally {
            watch.close()
            wire.stop()
        }
    }

    /** Workers whose runs have not yet taken in every event their streams were written, once the streams have had time to. */
    private fun settledUnread(workers: List<String>): Int {
        fun unread() = workers.indices.count { w -> (rig.graph.liveRuns.current(workers[w], "run-${workers[w]}")?.eventCount ?: 0) < wire.written(w) }
        val deadline = System.nanoTime() + 10_000_000_000L
        while (unread() > 0 && System.nanoTime() < deadline) rig.meter.settle(null, quiet = 3, max = 60)
        return unread()
    }

    /** [frames] frames at sixty a second of real time, the fleet ticking once a second. */
    private fun paced(phase: ScaleMeter.Phase?, frames: Int) {
        val t0 = System.nanoTime()
        for (i in 0 until frames) {
            val due = t0 + i * FRAME_NANOS
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            if (i % 60 == 59) rig.fleet.deliver(rig.api, rig.graph, rig.fleet.tick())
            rig.meter.frame(phase)
        }
    }

    /**
     * Each worker's run's events counted as its hub took them in, every [SAMPLE_MS] off the main thread, and the
     * threads counted every third time (see [StreamThreads]): between [begin] and [end], the events each run took in,
     * the most it was ever behind what its server had [written], the longest it went without one, and the most
     * threads the streams' reading held at once.
     */
    private class StreamWatch(private val workers: Int, private val written: (Int) -> Long, private val count: (Int) -> Int) : AutoCloseable {
        class Watched(val taken: IntArray, val longestGapMs: LongArray, val maxLag: LongArray, val readersPeak: Int, val threadsPeak: Int, val appThreadsPeak: Int)

        private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "stream-watch").apply { isDaemon = true } }
        private val lock = Any()
        private var watching = false
        private val start = IntArray(workers)
        private val last = IntArray(workers)
        private val lastAt = LongArray(workers)
        private val longest = LongArray(workers)
        private val lag = LongArray(workers)
        private var readers = 0
        private var threads = 0
        private var app = 0
        private var samples = 0L

        fun begin() = synchronized(lock) {
            val t = System.nanoTime()
            for (w in 0 until workers) {
                start[w] = count(w)
                last[w] = start[w]
                lastAt[w] = t
                longest[w] = 0L
                lag[w] = 0L
            }
            readers = 0
            threads = 0
            app = 0
            watching = true
            executor.scheduleAtFixedRate(::sample, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS)
        }

        private fun sample() = synchronized(lock) {
            if (!watching) return
            val t = System.nanoTime()
            for (w in 0 until workers) {
                val sent = written(w)
                val n = count(w)
                lag[w] = maxOf(lag[w], sent - n)
                if (n != last[w]) {
                    last[w] = n
                    lastAt[w] = t
                } else {
                    longest[w] = maxOf(longest[w], (t - lastAt[w]) / 1_000_000)
                }
            }
            if (samples++ % 3 == 0L) {
                val census = StreamThreads.census()
                readers = maxOf(readers, census.readers.size)
                threads = maxOf(threads, census.total)
                app = maxOf(app, census.app)
            }
        }

        fun end(): Watched {
            sample()
            return synchronized(lock) {
                watching = false
                Watched(IntArray(workers) { last[it] - start[it] }, longest.copyOf(), lag.copyOf(), readers, threads, app)
            }
        }

        override fun close() {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val BIG_TURNS = 20
        const val BEAT_MS = 10L
        const val FRAME_NANOS = 16_666_667L
        const val WARM_FRAMES = 180
        const val WARM_UP_FRAMES = 300
        const val MEASURED_FRAMES = 600

        const val MAX_MAIN_DELIVERIES_PER_SEC = 60.0
        const val MAX_MAIN_ALLOC_KB_PER_FRAME = 600.0
        const val MAX_SCOPES_PER_FRAME = 6.0
        /** Five hundred workers' streams at a hundred events a second, twice the rows on the screen. */
        const val MAX_SCOPES_PER_FRAME_FIVE_HUNDRED = 8.0

        const val SAMPLE_MS = 100L
        /** A stream whose run is this many events behind what its server wrote (a second's worth) is stalled. */
        const val STALL_EVENTS = 100L
        /** Of the events each stream's server wrote over the phase, the least its run may have taken in. */
        const val MIN_DELIVERED_SHARE = 0.9
        const val MIN_EVENTS_PER_SEC = 80.0
        /** Threads the streams' reading may hold at most, whether 128 streams or 500 are open. */
        const val MAX_READER_THREADS = 8
    }
}
