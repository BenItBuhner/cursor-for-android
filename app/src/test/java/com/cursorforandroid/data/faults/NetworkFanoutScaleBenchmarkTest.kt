package com.cursorforandroid.data.faults

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ApiThrottle
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime

/**
 * Network/data-layer fan-out at the shared S50/S200/S500 fleet shapes. The test drives the real repositories,
 * Connect throttle, OkHttp clients and SSE hub against [FaultServer]. Six wall-clock ticks represent a 60-second
 * foreground window; requests still pay 100–300 ms RTT, and the contended Big Project open pays 300–900 ms.
 *
 * This is a baseline harness, not a claim that the current counts are ideal. Guards only catch order-of-magnitude
 * regressions; every result is printed as a machine-readable `SCALE` line for median aggregation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NetworkFanoutScaleBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = CopyOnWriteArrayList<FaultRig>()
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var rigSequence = 0

    @After
    fun tearDown() {
        rigs.asReversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
        previousHandler?.let(Thread::setDefaultUncaughtExceptionHandler)
    }

    @Test
    fun `S50 network and data fan-out baseline`() = runFleet(ScaleFleet.S50)

    @Test
    fun `S200 network and data fan-out baseline`() = runFleet(ScaleFleet.S200)

    @Test
    fun `S500 network and data fan-out baseline`() {
        assumeTrue(
            "Set -Dscale.s500=true for the heaviest fleet",
            System.getProperty("scale.s500") == "true" || System.getenv("SCALE_S500") == "true",
        )
        runFleet(ScaleFleet.S500)
    }

    private fun runFleet(fleet: ScaleFleet): Unit = runBlocking {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught += thread.name to error }
        server = FaultServer(rttMillis = 100L..300L, http2 = true).start().also {
            it.liveRunStreams = true
            it.liveRunBeatMs = 500L
            it.pageSize = 100
            it.accountPageSize = 200
        }
        val fixture = ScaleFleetServer(server, fleet)

        runCold(fixture, keepLive = true)
        runCold(fixture, keepLive = false)
        runExtendedOpenBackstackAndBackground(fixture)
        runStandardBackstack(fixture)

        val unexpected = uncaught.filterNot { (thread, error) ->
            // Closing a rig shuts down OkHttp's executor; a retry sleeping in the interceptor is interrupted by that
            // deliberate teardown and may reach the process handler after its owning scenario has finished.
            thread.startsWith("OkHttp Dispatcher") &&
                error is InterruptedException &&
                error.stackTrace.any { it.className.endsWith("RetryInterceptorKt") && it.methodName == "sleepInSlices" }
        }
        assertThat(unexpected.map { (thread, error) -> "$thread: ${error.stackTraceToString().take(1_500)}" }).isEmpty()
    }

    private suspend fun runCold(fixture: ScaleFleetServer, keepLive: Boolean) {
        val rig = rig(extended = true)
        val foreground = MutableStateFlow(true)
        val enabled = MutableStateFlow(keepLive)
        val sync = liveSync(rig, enabled, foreground)
        val probe = EmissionProbe(rig)
        var firstRowMs = 0L
        var refreshCompleteMs = 0L

        val result = measure("cold_idle_keep_${if (keepLive) "on" else "off"}", fixture, rig, probe) {
            val started = System.nanoTime()
            val refresh = rig.scope.async { rig.agents.refresh(depth = RefreshDepth.Full) }
            rig.awaitUntil(60_000) { rig.agents.state.value.agents.isNotEmpty() }
            firstRowMs = (System.nanoTime() - started) / 1_000_000
            refresh.await()
            refreshCompleteMs = (System.nanoTime() - started) / 1_000_000
            loadAll(rig)
            if (keepLive) rig.awaitUntil(60_000) { sync.heldIds.value.isNotEmpty() }
            tickMinute(fixture, rig)
        }.copy(
            extras = mapOf(
                "firstRowMs" to firstRowMs.toString(),
                "refreshCompleteMs" to refreshCompleteMs.toString(),
                "rows" to rig.agents.state.value.agents.size.toString(),
            ),
        )

        result.print()
        assertGuards(result, fixture.fleet, background = false)
        probe.close()
        close(rig)
    }

    private suspend fun runExtendedOpenBackstackAndBackground(fixture: ScaleFleetServer) {
        val rig = rig(extended = true)
        rig.agents.refresh(depth = RefreshDepth.Full)
        loadAll(rig)
        val foreground = MutableStateFlow(true)
        val sync = liveSync(rig, MutableStateFlow(true), foreground)
        val probe = EmissionProbe(rig)
        val held = List(ApiThrottle.DEFAULT_MAX_IN_FLIGHT) { FaultServer.Fault.Held() }
        val projectEmissions = AtomicInteger()
        var projectView: Job? = null
        var firstContentMs = 0L

        val big = measure("big_project_open", fixture, rig, probe) {
            server.rttMillis = 300L..900L
            server.script(FaultServer.Route.Workers, *held.toTypedArray())
            val blockers = held.indices.map { i ->
                rig.scope.async { runCatching { rig.projectApi.workersForManager(fixture.smallProjectIds[i % fixture.smallProjectIds.size]) } }
            }
            rig.awaitUntil(20_000) { rig.accountRpc.throttle.inFlight(ApiThrottle.Lane.CONTROL) == ApiThrottle.DEFAULT_MAX_IN_FLIGHT }
            val started = System.nanoTime()
            rig.projects.attach(fixture.bigProjectId)
            projectView = rig.scope.launch {
                rig.projects.view(fixture.bigProjectId).collect { projectEmissions.incrementAndGet() }
            }
            rig.conversations.attach(fixture.bigProjectId)
            rig.steering.attach(fixture.bigProjectId)
            sync.opened(fixture.bigProjectId)
            rig.awaitUntil(120_000) {
                rig.conversations.state(fixture.bigProjectId).value.let { !it.isLoading && it.items.isNotEmpty() }
            }
            firstContentMs = (System.nanoTime() - started) / 1_000_000
            held.forEach { it.release() }
            blockers.forEach { it.await() }
            tickMinute(fixture, rig)
            server.rttMillis = 100L..300L
        }.copy(
            extras = mapOf(
                "firstContentMs" to firstContentMs.toString(),
                "projectWorkers" to fixture.bigWorkerIds.size.toString(),
                "projectEmissions" to projectEmissions.get().toString(),
                "turns" to "2000",
            ),
        )
        big.print()
        assertGuards(big, fixture.fleet, background = false)

        val opened = ArrayList<String>()
        val workers = fixture.bigWorkerIds.take(3)
        val backstack = measure("three_workers_backstack_extended", fixture, rig, probe) {
            rig.conversations.pause(fixture.bigProjectId)
            rig.steering.detach(fixture.bigProjectId)
            rig.projects.detach(fixture.bigProjectId)
            projectView?.cancelAndJoin()
            for (worker in workers) {
                opened.lastOrNull()?.let {
                    rig.conversations.pause(it)
                    rig.steering.detach(it)
                }
                rig.conversations.attach(worker)
                rig.steering.attach(worker)
                sync.opened(worker)
                rig.awaitUntil(60_000) { !rig.conversations.state(worker).value.isLoading }
                opened += worker
            }
            tickMinute(fixture, rig)
        }.copy(extras = mapOf("backstack" to opened.size.toString()))
        backstack.print()
        assertGuards(backstack, fixture.fleet, background = false)

        opened.lastOrNull()?.let {
            rig.conversations.pause(it)
            rig.steering.detach(it)
        }
        foreground.value = false
        rig.awaitUntil(30_000) { sync.heldIds.value.isEmpty() }
        delay(1_000)
        val background = measure("background_after_grace", fixture, rig, probe) {
            repeat(LOGICAL_TICKS) {
                fixture.tick()
                delay(TICK_WALL_MS)
            }
        }
        background.print()
        assertGuards(background, fixture.fleet, background = true)

        val returnedAt = System.nanoTime()
        foreground.value = true
        opened.lastOrNull()?.let {
            rig.conversations.resume(it)
            rig.steering.attach(it)
            sync.opened(it)
            rig.awaitUntil(60_000) { !rig.conversations.state(it).value.isLoading }
        }
        val returnMs = (System.nanoTime() - returnedAt) / 1_000_000
        val returned = measure("return_foreground", fixture, rig, probe) { tickMinute(fixture, rig) }
            .copy(extras = mapOf("firstContentMs" to returnMs.toString()))
        returned.print()
        assertGuards(returned, fixture.fleet, background = false)

        probe.close()
        close(rig)
    }

    private suspend fun runStandardBackstack(fixture: ScaleFleetServer) {
        val rig = rig(extended = false)
        rig.agents.refresh(depth = RefreshDepth.Full)
        loadAll(rig)
        val probe = EmissionProbe(rig)
        val workers = fixture.bigWorkerIds.take(3)
        val opened = ArrayList<String>()
        val result = measure("three_workers_backstack_standard", fixture, rig, probe) {
            for (worker in workers) {
                opened.lastOrNull()?.let { rig.conversations.pause(it) }
                rig.conversations.attach(worker)
                rig.awaitUntil(60_000) { !rig.conversations.state(worker).value.isLoading }
                opened += worker
            }
            tickMinute(fixture, rig)
        }.copy(extras = mapOf("backstack" to opened.size.toString()))
        result.print()
        assertGuards(result, fixture.fleet, background = false)
        probe.close()
        close(rig)
    }

    private fun rig(extended: Boolean): FaultRig =
        FaultRig(
            server.baseUrl,
            folder.newFolder("rig-${rigSequence++}"),
            readTimeoutMs = 15_000L,
            extended = extended,
            engine = if (extended) TranscriptEngine.BETA else TranscriptEngine.STABLE,
            queuePollMs = 1_000L,
            http2 = true,
        ).also {
            rigs += it
        }

    private fun liveSync(
        rig: FaultRig,
        enabled: MutableStateFlow<Boolean>,
        foreground: MutableStateFlow<Boolean>,
    ): LiveSync = LiveSync(
        target = object : LiveSync.Target {
            override fun hold(agentId: String) = rig.conversations.hold(agentId)
            override fun release(agentId: String) = rig.conversations.release(agentId)
            override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
        },
        scope = rig.scope,
        settleTimeoutMs = 15_000L,
        backgroundGraceMs = 500L,
    ).also { sync ->
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), enabled, foreground)
    }

    private suspend fun loadAll(rig: FaultRig) {
        var previous = -1
        while (rig.agents.state.value.hasMore && rig.agents.state.value.agents.size != previous) {
            previous = rig.agents.state.value.agents.size
            runCatching { rig.agents.loadMore() }
        }
    }

    private suspend fun tickMinute(fixture: ScaleFleetServer, rig: FaultRig) {
        repeat(LOGICAL_TICKS) { tick ->
            fixture.tick()
            if (tick % 3 == 2) runCatching { rig.agents.refresh(silent = true, depth = RefreshDepth.Quick) }
            shadowOf(Looper.getMainLooper()).idle()
            delay(TICK_WALL_MS)
        }
    }

    private suspend fun measure(
        scenario: String,
        fixture: ScaleFleetServer,
        rig: FaultRig,
        emissions: EmissionProbe,
        block: suspend () -> Unit,
    ): ScaleResult {
        val seenAt = server.seen.size
        val emittedAt = emissions.count.get()
        val mainAt = emissions.mainCount.get()
        val mainWorkAt = emissions.mainWorkNanos.get()
        val meter = Meter(rig)
        val lane = LaneSamples(rig)
        val sampler = rig.scope.launch {
            while (true) {
                lane.sample()
                delay(SAMPLE_MS)
            }
        }
        val started = System.nanoTime()
        block()
        shadowOf(Looper.getMainLooper()).idle()
        val wallMs = (System.nanoTime() - started) / 1_000_000
        sampler.cancelAndJoin()
        lane.sample()
        meter.checkpoint()
        val allocated = meter.allocatedBytes()
        val result = ScaleResult(
            scenario = scenario,
            fleet = fixture.fleet,
            routes = server.seen.drop(seenAt).groupingBy { it.route }.eachCount(),
            streamPeak = lane.streamPeak,
            streamMean = if (lane.samples == 0) 0.0 else lane.streamSum.toDouble() / lane.samples,
            laneInFlight = lane.inFlight,
            laneWaiting = lane.waiting,
            laneSaturatedMs = lane.saturatedSamples.mapValues { it.value * SAMPLE_MS },
            bytesIn = meter.bytesIn,
            appThreads = lane.appThreads,
            listEmissions = emissions.count.get() - emittedAt,
            mainEmissions = emissions.mainCount.get() - mainAt,
            mainWorkNanos = emissions.mainWorkNanos.get() - mainWorkAt,
            peakRetained = meter.peakRetained,
            allocated = allocated,
            running = fixture.runningCount(),
            wallMs = wallMs,
        )
        meter.close()
        return result
    }

    private fun assertGuards(result: ScaleResult, fleet: ScaleFleet, background: Boolean) {
        assertThat(result.streamPeak).isAtMost(if (background) 0 else LiveSync.MAX_HELD + 6)
        assertThat(result.routes.values.sum()).isAtMost(5_000)
        assertThat(result.perRunningRead).isAtMost(30.0)
        assertThat(result.laneWaiting.values.maxOrNull() ?: 0).isAtMost(fleet.total + 200)
        if (background) assertThat(result.routes.values.sum()).isAtMost(4)
    }

    private suspend fun close(rig: FaultRig) {
        rigs.remove(rig)
        rig.close()
        repeat(80) {
            if (server.liveRunOpen.get() == 0) return
            delay(25)
        }
    }

    private class EmissionProbe(rig: FaultRig) {
        val count = AtomicInteger()
        val mainCount = AtomicInteger()
        val mainWorkNanos = AtomicLong()
        private val job: Job = rig.scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            rig.agents.state.collect { state ->
                count.incrementAndGet()
                if (Looper.myLooper() == Looper.getMainLooper()) mainCount.incrementAndGet()
                mainWorkNanos.addAndGet(
                    measureNanoTime {
                        AgentListOrganizer.organize(
                            state.agents,
                            ListPreferences(),
                            LocalAgentState(),
                            nowMillis = rig.now,
                        )
                    },
                )
            }
        }

        suspend fun close() = job.cancelAndJoin()
    }

    private inner class LaneSamples(private val rig: FaultRig) {
        val inFlight = ApiThrottle.Lane.entries.associateWith { 0 }.toMutableMap()
        val waiting = ApiThrottle.Lane.entries.associateWith { 0 }.toMutableMap()
        val saturatedSamples = ApiThrottle.Lane.entries.associateWith { 0 }.toMutableMap()
        var streamPeak = 0
        var streamSum = 0L
        var samples = 0
        var appThreads = 0

        fun sample() {
            val throttle = rig.accountRpc.throttle
            ApiThrottle.Lane.entries.forEach { lane ->
                val active = throttle.inFlight(lane)
                inFlight[lane] = maxOf(inFlight.getValue(lane), active)
                waiting[lane] = maxOf(waiting.getValue(lane), throttle.waiting(lane))
                if (active >= capacity(lane)) saturatedSamples[lane] = saturatedSamples.getValue(lane) + 1
            }
            val streams = server.liveRunOpen.get()
            streamPeak = maxOf(streamPeak, streams)
            streamSum += streams
            samples++
            appThreads = maxOf(appThreads, Thread.getAllStackTraces().keys.count { Meter.isApp(it.name) })
        }

        private fun capacity(lane: ApiThrottle.Lane): Int = when (lane) {
            ApiThrottle.Lane.CONTROL -> ApiThrottle.DEFAULT_MAX_IN_FLIGHT
            ApiThrottle.Lane.BLOBS -> ApiThrottle.BLOBS_IN_FLIGHT
            ApiThrottle.Lane.WATCH -> ApiThrottle.WATCHES_IN_FLIGHT
            ApiThrottle.Lane.MEDIA -> ApiThrottle.MEDIA_IN_FLIGHT
            ApiThrottle.Lane.STATE, ApiThrottle.Lane.STATE_ON_SCREEN -> ApiThrottle.STATES_IN_FLIGHT
        }
    }

    private data class ScaleResult(
        val scenario: String,
        val fleet: ScaleFleet,
        val routes: Map<FaultServer.Route, Int>,
        val streamPeak: Int,
        val streamMean: Double,
        val laneInFlight: Map<ApiThrottle.Lane, Int>,
        val laneWaiting: Map<ApiThrottle.Lane, Int>,
        val laneSaturatedMs: Map<ApiThrottle.Lane, Long>,
        val bytesIn: Long,
        val appThreads: Int,
        val listEmissions: Int,
        val mainEmissions: Int,
        val mainWorkNanos: Long,
        val peakRetained: Long,
        val allocated: Long,
        val running: Int,
        val wallMs: Long,
        val extras: Map<String, String> = emptyMap(),
    ) {
        val perRunningRead: Double
            get() {
                val reads = listOf(
                    FaultServer.Route.GetRun,
                    FaultServer.Route.Record,
                    FaultServer.Route.RecordState,
                    FaultServer.Route.Blob,
                ).sumOf { routes[it] ?: 0 }
                return reads.toDouble() / running.coerceAtLeast(1)
            }

        fun print() {
            val routeText = REPORT_ROUTES.joinToString(" ") { route -> "${route.name}Rpm=${routes[route] ?: 0}" }
            val laneText = ApiThrottle.Lane.entries.joinToString(" ") { lane ->
                val key = lane.name.lowercase(Locale.US).replace("_", "")
                "${key}InFlightPeak=${laneInFlight.getValue(lane)} ${key}WaitingPeak=${laneWaiting.getValue(lane)} ${key}SaturatedMs=${laneSaturatedMs.getValue(lane)}"
            }
            val mainUs = if (mainEmissions == 0) 0 else mainWorkNanos / mainEmissions / 1_000
            val extraText = extras.entries.joinToString(" ") { "${it.key}=${it.value}" }
            println(
                "SCALE $scenario fleet=${fleet.label} $routeText " +
                    "perRunningReadRpm=${f(perRunningRead)} openSsePeak=$streamPeak openSseMean=${f(streamMean)} " +
                    "$laneText bytesIn=$bytesIn appThreadsPeak=$appThreads listEmissionsPerSec=${f(listEmissions / 60.0)} " +
                    "mainEmissions=$mainEmissions mainWorkUsPerEmission=$mainUs retainedBytes=$peakRetained allocatedBytes=$allocated " +
                    "wallMs=$wallMs $extraText",
            )
        }

        private fun f(value: Double): String = String.format(Locale.US, "%.2f", value)
    }

    private companion object {
        const val LOGICAL_TICKS = 6
        const val TICK_WALL_MS = 1_000L
        const val SAMPLE_MS = 100L

        val REPORT_ROUTES = listOf(
            FaultServer.Route.ListAgents,
            FaultServer.Route.ListAgentsV0,
            FaultServer.Route.GetRun,
            FaultServer.Route.AccountList,
            FaultServer.Route.Workers,
            FaultServer.Route.Children,
            FaultServer.Route.Record,
            FaultServer.Route.RecordState,
            FaultServer.Route.Live,
            FaultServer.Route.Blob,
            FaultServer.Route.QueueList,
            FaultServer.Route.Stream,
            FaultServer.Route.ListRuns,
        )
    }
}
