package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.StreamThreads
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.ui.scale.ScaleMeter
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A coordinator's cloud subagents all working at once, 128 of them and then 500, each run's stream held open by an
 * HTTP/2 server as the API holds it and writing about a hundred events a second, every one followed through the real
 * [SseRunStreamer][com.cursorforandroid.data.api.SseRunStreamer] and
 * [LiveRunHub][com.cursorforandroid.data.repo.LiveRunHub]. Each stream is one HTTP/2 stream on one connection to the
 * host, and none may wait on another: every run followed takes in what its server writes, as it writes it.
 *
 * `SCALE run-streams` reports how long the fleet took to catch up before it was measured, the streams the server held
 * and the connections it was opened, the events each run's
 * subscriber was handed per second against those its server wrote, the most any run's subscriber was ever behind its
 * server and the longest any went without an event (its server's silences included: its threads, one a stream, are
 * the test's and not the app's), the threads
 * the streams' reading held at their most (a thread parked in the reader, or the multiplexer's own; see
 * [StreamThreads]) and the process's, and the CPU and allocations of the shared dispatcher pool (IO and Default) and
 * the process's CPU, per second.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunStreamMultiplexBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.close()
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `128 working subagents' streams all flow at once over one HTTP-2 connection`() = measure(128)

    @Test
    fun `500 working subagents' streams all flow at once over one HTTP-2 connection`() = measure(500)

    private fun measure(workers: Int) {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = BEAT_MS
        val written = ConcurrentHashMap<String, AtomicInteger>()
        server.liveRunGenerator = { runId, tick ->
            written.getOrPut(runId) { AtomicInteger() }.incrementAndGet()
            listOf("assistant" to """{"text":"t$tick "}""")
        }
        val ids = (0 until workers).map { "bc-stream-worker-%03d".format(it) }
        ids.forEachIndexed { i, id ->
            val at = Instant.ofEpochMilli(now - i).toString()
            server.agents[id] = AgentDto(id = id, name = "Worker $i", status = "ACTIVE", createdAt = at, updatedAt = at, latestRunId = "run-$id")
            server.v0[id] = V0AgentDto(id = id, name = "Worker $i", status = "RUNNING")
            server.startTurnElsewhere(id, "Scan market $i.", "run-$id")
        }
        rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 10_000L, http2 = true).also { it.now = now }
        runBlocking { rig.agents.refresh() }

        val events = ConcurrentHashMap<String, Int>()
        val jobs: List<Job> = ids.map { id -> rig.scope.launch { rig.hub.snapshots(id, "run-$id").collect { events[id] = it.eventCount } } }
        // Measured from the moment the fleet flows as it will from then on: every stream held and every run's
        // subscriber caught up with its server. A cold process spends its first seconds loading and compiling what
        // the streams run through, and the runs its dispatcher left for last are seconds behind then: a phase opened
        // while they still catch up reads their catching up as stalls. A fleet that never catches up is measured at
        // the bound, and fails the phase as it would have.
        val settleStarted = System.nanoTime()
        Thread.sleep(SETTLE_MS)
        val caughtUp = { server.liveRunOpen.get() == workers && ids.all { (written["run-$it"]?.get() ?: 0) - (events[it] ?: 0) <= CAUGHT_UP_EVENTS } }
        while (!caughtUp() && System.nanoTime() - settleStarted < TimeUnit.MILLISECONDS.toNanos(SETTLE_MAX_MS)) Thread.sleep(SAMPLE_MS)
        val settleMs = (System.nanoTime() - settleStarted) / 1_000_000

        val lock = Any()
        val before = ids.associateWith { events[it] ?: 0 }
        val writtenBefore = ids.associateWith { written["run-$it"]?.get() ?: 0 }
        val last = before.toMutableMap()
        val lastAt = ids.associateWith { System.nanoTime() }.toMutableMap()
        val longest = ids.associateWith { 0L }.toMutableMap()
        val lag = ids.associateWith { 0 }.toMutableMap()
        var peakOpen = 0
        var peakConnections = 0
        var readersPeak = 0
        var threadsPeak = 0
        var appPeak = 0
        var samples = 0L
        val pools0 = ScaleMeter.Jvm.threads()
        val cpu0 = ScaleMeter.Jvm.processCpuNanos()
        val started = System.nanoTime()
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({
            synchronized(lock) {
                val t = System.nanoTime()
                for (id in ids) {
                    val n = events[id] ?: 0
                    lag[id] = maxOf(lag.getValue(id), (written["run-$id"]?.get() ?: 0) - n)
                    if (n != last[id]) {
                        last[id] = n
                        lastAt[id] = t
                    } else {
                        longest[id] = maxOf(longest.getValue(id), (t - lastAt.getValue(id)) / 1_000_000)
                    }
                }
                peakOpen = maxOf(peakOpen, server.liveRunOpen.get())
                peakConnections = maxOf(peakConnections, rig.client.connectionPool.connectionCount())
                if (samples++ % 3 == 0L) {
                    val census = StreamThreads.census()
                    readersPeak = maxOf(readersPeak, census.readers.size)
                    threadsPeak = maxOf(threadsPeak, census.total)
                    appPeak = maxOf(appPeak, census.app)
                }
            }
        }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        Thread.sleep(PHASE_MS)
        sampler.shutdown()
        sampler.awaitTermination(1, TimeUnit.SECONDS)
        val seconds = (System.nanoTime() - started) / 1e9
        val cpuMsPerSec = (ScaleMeter.Jvm.processCpuNanos() - cpu0) / 1e6 / seconds
        val pools = ScaleMeter.Jvm.threads()
        val default = pools["default"]?.let { end -> pools0["default"]?.let { was -> LongArray(2) { end[it] - was[it] } } ?: end } ?: LongArray(2)

        val rates = ids.map { ((events[it] ?: 0) - before.getValue(it)) / seconds }
        val writtenRates = ids.map { ((written["run-$it"]?.get() ?: 0) - writtenBefore.getValue(it)) / seconds }
        val share = ids.indices.minOf { rates[it] / writtenRates[it].coerceAtLeast(1e-9) }
        val gaps = synchronized(lock) { ids.map { longest.getValue(it) } }
        val lags = synchronized(lock) { ids.map { lag.getValue(it) } }
        val stalled = lags.count { it > STALL_EVENTS }
        val sorted = rates.sorted()
        val hub = rig.hub.stats()
        jobs.forEach { it.cancel() }

        val line = "SCALE run-streams workers=$workers settleMs=$settleMs serverStreamsPeak=$peakOpen connectionsPeak=$peakConnections " +
            "serverConnections=${server.connections.get()} stalled=$stalled maxLagEvents=${lags.max()} longestSilenceMs=${gaps.max()} " +
            "eventsPerSecPerRun(min=${f(sorted.first())} p50=${f(sorted[sorted.size / 2])} max=${f(sorted.last())}) " +
            "writtenPerSecPerRunP50=${f(writtenRates.sorted()[workers / 2])} minDeliveredShare=${f(share)} " +
            "readerThreadsPeak=$readersPeak processThreadsPeak=$threadsPeak appThreadsPeak=$appPeak " +
            "procCpuMsPerSec=${f(cpuMsPerSec)} defaultCpuMsPerSec=${f(default[0] / 1e6 / seconds)} defaultAllocMBPerSec=${f(default[1] / 1048576.0 / seconds)} $hub"
        println(line)
        assertWithMessage(line).that(peakOpen).isEqualTo(workers)
        // Every run live all along: none fell a second behind what its server wrote, each took in all of it.
        assertWithMessage(line).that(stalled).isEqualTo(0)
        assertWithMessage(line).that(share).isAtLeast(MIN_DELIVERED_SHARE)
        assertWithMessage(line).that(sorted.first()).isAtLeast(MIN_EVENTS_PER_SEC)
        // The streams' reading holds a few threads, whether 128 streams are open or 500.
        assertWithMessage(line).that(readersPeak).isAtMost(MAX_READER_THREADS)
        // The client's pool keeps one connection for the REST calls; the streams share one more.
        assertWithMessage(line).that(peakConnections).isAtMost(1)
        assertWithMessage(line).that(server.connections.get()).isAtMost(2)
    }

    private fun f(v: Double) = "%.1f".format(v)

    private companion object {
        /** A held stream writes an event, then waits this long: about a hundred a second, never more. */
        const val BEAT_MS = 10L
        const val SETTLE_MS = 4_000L
        /** The longest the fleet is given to catch up before it is measured regardless. */
        const val SETTLE_MAX_MS = 30_000L
        /** Caught up: no run's subscriber more than a tenth of a second's events behind its server. */
        const val CAUGHT_UP_EVENTS = 10
        const val PHASE_MS = 6_000L
        const val SAMPLE_MS = 100L
        /** A run whose subscriber is this many events behind what its server wrote (a second's worth) is stalled. */
        const val STALL_EVENTS = 100
        const val MIN_DELIVERED_SHARE = 0.9
        const val MIN_EVENTS_PER_SEC = 60.0
        const val MAX_READER_THREADS = 8
    }
}
