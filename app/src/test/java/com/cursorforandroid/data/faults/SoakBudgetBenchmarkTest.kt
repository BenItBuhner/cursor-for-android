package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.Meter.Companion.mb
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the app costs with an account like Bennett's and far past it — hundreds of agents, a hundred and more of them
 * working at once, a chat thousands of turns long — held to budgets: the heap still reachable at its peak, the bytes
 * on the wire, the bytes allocated. A change that makes any of them grow past its budget fails here, before a phone
 * finds it. The budgets and what they were before the memory overhaul are in each test; the numbers per change are
 * kept in the project store (`internal/memory-overhaul.md`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SoakBudgetBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += t.name to e }
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun q(s: String) = Json.encodeToString(String.serializer(), s)

    private fun rig(): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("disk-${rigs.size}"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also {
            it.now = now
            rigs += it
        }

    private fun addChat(id: String, name: String, at: Long) {
        val runId = "run-done-$id"
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done with $name.")
        server.logs[runId] = listOf("assistant" to """{"text":"Done with $name."}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"Done with $name.","durationMs":60000}""")
        server.agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Start $name."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done with $name."))
        server.composers.putIfAbsent(id, FaultServer.Composer(id, name, activityMs = at))
    }

    /** [total] chats, [running] working, [projects] Projects each coordinating twelve of them. Returns the coordinators. */
    private fun fleet(total: Int, running: Int, projects: Int): List<String> {
        val coordinators = (0 until projects).map { "bc-project-$it" }
        coordinators.forEachIndexed { p, id -> addChat(id, "Project $p", now - 10_000L * p) }
        repeat(total) { i -> addChat("bc-soak-$i", "Agent $i", now - 100_000L - 1_000L * i) }
        coordinators.forEachIndexed { p, id ->
            server.composers[id] = FaultServer.Composer(id, "Project $p", activityMs = now - 10_000L * p, project = true, running = true)
            val workers = (0 until total).filter { it % projects == p }.take(12).map { "bc-soak-$it" }
            server.workers[id] = workers.map { it to "MANAGER_SPAWN_KIND_CREATED" }
            workers.forEach { w -> server.composers[w] = FaultServer.Composer(w, w, activityMs = now - 100_000L, manager = id) }
        }
        (coordinators + (0 until running).map { "bc-soak-$it" }).forEachIndexed { n, id -> server.startTurnElsewhere(id, "Keep going on task $n.", "run-live-$n") }
        return coordinators
    }

    /** Each beat of a working agent's stream: a shell run with a few KB of output, and a line of narration. */
    private val working: (String, Int) -> List<Pair<String, String>> = { runId, tick ->
        val output = "PASS test_$tick (${runId.takeLast(6)})\n".repeat(90)
        listOf(
            "tool_call" to """{"callId":"g$tick","name":"shell","status":"completed","args":{"command":"./gradlew test --tests T$tick"},"result":{"output":${q(output)},"exitCode":0}}""",
            "assistant" to """{"text":${q("Step $tick passes; on to the next. ")}}""",
        )
    }

    private fun liveSync(rig: FaultRig, foreground: MutableStateFlow<Boolean> = MutableStateFlow(true)): LiveSync =
        LiveSync(
            target = object : LiveSync.Target {
                override fun hold(agentId: String) = rig.conversations.hold(agentId)
                override fun release(agentId: String) = rig.conversations.release(agentId)
                override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
            },
            scope = rig.scope,
            settleTimeoutMs = 5_000L,
            backgroundGraceMs = 500L,
        ).also { it.start(rig.agents.state.map { s -> s.agents }.distinctUntilChanged(), MutableStateFlow(true), foreground) }

    /**
     * A small chat opened and left before the meter starts: what the first chat of a process loads once and keeps
     * (Robolectric's resource tables, some 16 MB of them) is not the scenario's to pay for.
     */
    private suspend fun warmUp(rig: FaultRig, agentId: String) {
        rig.conversations.attach(agentId)
        rig.awaitUntil(10_000) { rig.conversations.state(agentId).value.let { !it.isLoading && it.items.isNotEmpty() } }
        rig.conversations.detach(agentId)
        // The framework's own resources, as a chat with day-old turns reads them for its dates.
        android.text.format.DateUtils.getRelativeTimeSpanString(now - 3 * 86_400_000L, now, android.text.format.DateUtils.DAY_IN_MILLIS)
        android.text.format.DateFormat.getTimeFormat(rig.context).format(java.util.Date(now))
        android.content.res.Resources.getSystem().configuration
    }

    private fun report(name: String, meter: Meter, rig: FaultRig) {
        println("SOAK $name: ${meter.summary()} · ${rig.conversations.stats()} · ${rig.hub.stats()}")
        for (route in listOf(FaultServer.Route.GetAgent, FaultServer.Route.GetRun, FaultServer.Route.ListRuns, FaultServer.Route.Conversation)) {
            val byAgent = server.seen.filter { it.route == route }.groupingBy { Regex("bc-[a-z]+-\\d+").find(it.path)?.value ?: it.path.take(40) }.eachCount()
            println("SOAK   $route by agent (${byAgent.size}): ${byAgent.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}=${it.value}" }}")
        }
        println("SOAK   requests: ${server.seen.groupingBy { it.route }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
        assertThat(uncaught.map { (thread, e) -> "$thread: ${e.stackTraceToString().take(1_500)}" }).isEmpty()
    }

    private fun budget(what: String, value: Long, max: Long) =
        assertWithMessage("$what ${value.mb} over its budget of ${max.mb}").that(value).isAtMost(max)

    private fun calls(what: String, value: Int, max: Int) =
        assertWithMessage("$what: $value calls, over the budget of $max").that(value).isAtMost(max)

    /**
     * Three hundred agents, a hundred and sixty working — every one of their streams saying something twice a second
     * — six Projects switched through as fast as a thumb goes, eight times round, with keep chats live on and the
     * list refreshing and turns ending and starting underneath.
     */
    @Test
    fun `hundreds of working agents with Projects switched fast stay within budget`() = fleetWithin("fleet", runsDatedAt = now)

    /**
     * The same fleet with every turn's run dated an hour behind the chat's last finished one (a server clock off):
     * the agent's record still names it as the latest. Read by dates alone, each held chat reloaded itself every
     * round trip while its turn ran — 3,636 calls and 349 MB allocated where the fleet takes some 840 and 135 MB.
     */
    @Test
    fun `hundreds of working agents whose runs are dated behind stay within budget`() = fleetWithin("fleet dated behind", runsDatedAt = now - 3_600_000L)

    private fun fleetWithin(name: String, runsDatedAt: Long) = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { runsDatedAt }
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        server.liveRunGenerator = working
        server.pageSize = 50
        val projects = fleet(total = 300, running = 160, projects = 6)
        val rig = rig()
        rig.agents.refresh()
        repeat(3) { runCatching { rig.agents.loadMore() } }
        warmUp(rig, "bc-soak-299")
        val meter = Meter(rig)
        val sync = liveSync(rig)
        rig.awaitUntil(20_000) { sync.heldIds.value.size >= LiveSync.MAX_HELD }
        repeat(8) { round ->
            for ((i, p) in projects.withIndex()) {
                val worker = server.workers[p].orEmpty().getOrNull(round % 12)?.first
                rig.projects.attach(p)
                rig.conversations.attach(p)
                sync.opened(p)
                worker?.let { rig.conversations.attach(it); sync.opened(it) }
                delay(300)
                worker?.let { rig.conversations.detach(it) }
                rig.conversations.detach(p)
                rig.projects.detach(p)
                if ((round * projects.size + i) % 5 == 0) rig.scope.launch { runCatching { rig.agents.refresh() } }
            }
            val random = java.util.Random(round.toLong())
            repeat(8) {
                val id = "bc-soak-${random.nextInt(300)}"
                if (server.agents[id]?.status == "ACTIVE") server.endTurn(id, durationMs = 5_000L) else server.startTurnElsewhere(id, "Another turn $round.$it.", "run-churn-$round-$it")
            }
            meter.checkpoint()
        }
        delay(2_000)
        meter.checkpoint()
        report(name, meter, rig)
        meter.close()
        budget("peak retained heap", meter.peakRetained, FLEET_HEAP)
        budget("bytes received", meter.bytesIn, FLEET_BYTES_IN)
        budget("bytes allocated", meter.allocatedBytes(), FLEET_ALLOCATED)
        calls("the fleet's requests", meter.calls, FLEET_CALLS)
    }

    /**
     * A Project coordinator's chat 1,500 turns long — a tenth of them a report read whole, tens of thousands of
     * characters each — opened, scrolled back twenty pages, and closed: what the open chat holds, and what is left
     * once it is closed and the reader has gone on to twenty-four other chats.
     */
    @Test
    fun `a chat thousands of turns long, scrolled far back and closed, stays within budget`() = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.pageSize = 100
        val agentId = LongProject.AGENT_ID
        val turns = LongProject.turns(now - HUGE_TURNS * LongProject.TURN_SPACING_MS, turns = HUGE_TURNS)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = iso(turn.startedAt), updatedAt = iso(turn.endedAt), durationMs = turn.durationMs, result = null)
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = iso(turns.first().startedAt), updatedAt = iso(newest.startedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        repeat(24) { addChat("bc-other-$it", "Other $it", now - 500_000L - it * 1_000L) }
        val rig = rig()
        rig.agents.refresh()
        val conversations = rig.conversations
        warmUp(rig, "bc-other-23")
        val meter = Meter(rig)
        conversations.attach(agentId)
        rig.awaitUntil(60_000) { conversations.state(agentId).value.let { !it.isLoading && it.items.isNotEmpty() } }
        var pages = 0
        repeat(SCROLL_PAGES) {
            val before = conversations.state(agentId).value.items.size
            rig.awaitUntil(30_000) { !conversations.state(agentId).value.isLoadingOlder }
            conversations.loadOlder(agentId)
            val grew = runCatching { rig.awaitUntil(30_000) { conversations.state(agentId).value.let { it.items.size > before && !it.isLoadingOlder } } }.isSuccess
            if (grew) pages++
        }
        delay(1_000)
        val open = meter.checkpoint()
        val items = conversations.state(agentId).value.items.size
        conversations.detach(agentId)
        // The reader goes on: twenty-four other chats opened and left, the huge one falls out of the cache.
        repeat(24) {
            val id = "bc-other-$it"
            conversations.attach(id)
            rig.awaitUntil(10_000) { !conversations.state(id).value.isLoading }
            conversations.detach(id)
        }
        delay(1_000)
        val closed = meter.checkpoint()
        report("huge pages=$pages items=$items open=${open.mb} closed=${closed.mb}", meter, rig)
        meter.close()
        assertThat(pages).isAtLeast(10)
        budget("heap with the chat open, scrolled back", open, HUGE_OPEN_HEAP)
        budget("heap once closed", closed, HUGE_CLOSED_HEAP)
        budget("bytes received", meter.bytesIn, HUGE_BYTES_IN)
        budget("bytes allocated", meter.allocatedBytes(), HUGE_ALLOCATED)
        calls("the long chat's requests", meter.calls, HUGE_CALLS)
    }

    /**
     * The same hundred and sixty working agents with the app in the background: nothing on screen, keep chats live
     * standing down. What the app may spend there is the list's own polling, not the agents' streams.
     */
    @Test
    fun `in the background, hundreds of working agents cost next to nothing`() = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        // Turns started now are the newest runs of their chats, as the fixtures date the finished ones.
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        server.liveRunGenerator = working
        server.pageSize = 50
        fleet(total = 300, running = 160, projects = 6)
        val rig = rig()
        rig.agents.refresh()
        val foreground = MutableStateFlow(true)
        val sync = liveSync(rig, foreground)
        rig.awaitUntil(20_000) { sync.heldIds.value.size >= LiveSync.MAX_HELD }
        foreground.value = false
        rig.awaitUntil(20_000) { sync.heldIds.value.isEmpty() }
        // What the release set going (the linger, the streams' last reads) is over before the meter starts.
        delay(3_000)
        val meter = Meter(rig)
        delay(15_000)
        meter.checkpoint()
        report("background", meter, rig)
        meter.close()
        budget("bytes received in the background", meter.bytesIn, BACKGROUND_BYTES_IN)
        assertThat(server.liveRunOpen.get()).isEqualTo(0)
    }

    /**
     * Forty working chats looked in on one after another — more runs than the live-run table keeps — then the first
     * ten opened again once their agents have done a little more: what those reopens cost on the wire, and how many
     * of their runs' streams were read again from the first event rather than resumed.
     */
    @Test
    fun `working chats reopened after their runs left the live table resume rather than replay`() = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        val ids = (0 until EVICTED_CHATS).map { "bc-evict-$it" }
        ids.forEachIndexed { i, id ->
            addChat(id, "Worker $i", now - 100_000L - 1_000L * i)
            server.startTurnElsewhere(id, "Work on part $i.", "run-evict-$i")
            server.appendRunEvents("run-evict-$i", (1..RUN_EVENTS).flatMap { tick -> working("run-evict-$i", tick) })
        }
        val rig = rig()
        rig.agents.refresh()
        warmUp(rig, "bc-evict-${EVICTED_CHATS - 1}")
        val conversations = rig.conversations
        suspend fun look(id: String, tick: Int) {
            conversations.attach(id)
            rig.awaitUntil(20_000) {
                conversations.state(id).value.let { s -> s.isStreaming && s.items.any { it.toString().contains("Step $tick passes") } }
            }
            conversations.detach(id)
        }
        ids.forEachIndexed { i, id -> look(id, RUN_EVENTS) }
        delay(1_000)
        // The agents work on while nobody looks.
        ids.forEachIndexed { i, _ -> server.appendRunEvents("run-evict-$i", (RUN_EVENTS + 1..RUN_EVENTS + 3).flatMap { tick -> working("run-evict-$i", tick) }) }
        val streamsBefore = server.seen.size
        val meter = Meter(rig)
        val stories = ids.take(REOPENED).map { id ->
            look(id, RUN_EVENTS + 3)
            conversations.state(id).value.items.joinToString("\n")
        }
        delay(1_000)
        meter.checkpoint()
        val reopenStreams = server.seen.drop(streamsBefore).filter { it.route == FaultServer.Route.Stream }
        val replays = reopenStreams.count { it.lastEventId == null }
        report("reopen after eviction streams=${reopenStreams.size} replays=$replays", meter, rig)
        meter.close()
        // Resumed, each turn's story is whole: its first step, its newest, and none of them twice.
        stories.forEach { story ->
            assertThat(story).contains("Step 1 passes")
            assertThat(Regex("Step ${RUN_EVENTS + 3} passes").findAll(story).count()).isEqualTo(1)
            assertThat(Regex("Step $RUN_EVENTS passes").findAll(story).count()).isEqualTo(1)
        }
        assertWithMessage("runs read again from their first event").that(replays).isAtMost(REOPEN_REPLAYS)
        budget("bytes received reopening ten working chats", meter.bytesIn, REOPEN_BYTES_IN)
    }

    /**
     * Twenty working chats held by keep chats live, none of them on screen, their agents writing a step each half second
     * for thirty seconds: how long their streams stay open, what the app allocates keeping them current, and — once a
     * few are opened — how soon each shows its newest step and whether its story came through whole.
     */
    @Test
    fun `held working chats nobody looks at are looked in on, not streamed`() = runBlocking {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        val ids = (0 until LiveSync.MAX_HELD).map { "bc-held-$it" }
        ids.forEachIndexed { i, id ->
            addChat(id, "Held $i", now - 100_000L - 1_000L * i)
            server.startTurnElsewhere(id, "Work on part $i.", "run-held-$i")
            server.appendRunEvents("run-held-$i", working("run-held-$i", 1))
        }
        val rig = rig()
        rig.agents.refresh()
        warmUp(rig, "bc-held-0")
        val sync = liveSync(rig)
        rig.awaitUntil(30_000) { sync.heldIds.value.size == ids.size }
        ids.forEach { rig.conversations.settled(it) }
        delay(2_000)
        val meter = Meter(rig)
        var streamSamples = 0L
        val sampler = rig.scope.launch {
            while (true) { streamSamples += server.liveRunOpen.get(); delay(100) }
        }
        var tick = 1
        val started = System.nanoTime()
        while (System.nanoTime() - started < HELD_WORK_MS * 1_000_000) {
            tick++
            ids.forEachIndexed { i, _ -> server.appendRunEvents("run-held-$i", working("run-held-$i", tick)) }
            delay(500)
        }
        meter.checkpoint()
        sampler.cancel()
        val streamSeconds = streamSamples / 10.0
        val opens = ids.take(HELD_OPENED).map { id ->
            val openedAt = System.nanoTime()
            rig.conversations.attach(id)
            rig.awaitUntil(10_000) { rig.conversations.state(id).value.items.any { it.toString().contains("Step $tick passes") } }
            val ms = (System.nanoTime() - openedAt) / 1_000_000
            val story = rig.conversations.state(id).value.items.joinToString("\n")
            rig.conversations.detach(id)
            ms to story
        }
        report("held unwatched streamSeconds=${"%.0f".format(streamSeconds)} opens=${opens.map { it.first }}ms", meter, rig)
        meter.close()
        opens.forEach { (_, story) ->
            for (step in listOf(1, tick / 2, tick)) assertThat(Regex("Step $step passes").findAll(story).count()).isEqualTo(1)
        }
        assertWithMessage("stream-seconds open for twenty held chats over ${HELD_WORK_MS / 1000} s").that(streamSeconds).isAtMost(HELD_STREAM_SECONDS)
        budget("bytes allocated keeping twenty working chats current", meter.allocatedBytes(), HELD_ALLOCATED)
        opens.forEach { (ms, _) -> assertWithMessage("opening a held chat to its newest step").that(ms).isAtMost(HELD_OPEN_MS) }
    }

    private companion object {
        const val HELD_WORK_MS = 30_000L
        const val HELD_OPENED = 5
        const val HELD_STREAM_SECONDS = 1_000.0
        const val HELD_ALLOCATED = 1_000L shl 20
        const val HELD_OPEN_MS = 2_000L
        const val EVICTED_CHATS = 40
        const val REOPENED = 10
        /** Beats of work in each run's log before it is first looked at: some 300 KB of tool output a run. */
        const val RUN_EVENTS = 80
        const val HUGE_TURNS = 1_500
        const val SCROLL_PAGES = 20

        const val MB = 1L shl 20
        // Budgets: what the code measured when each was set (in the comment), with room for a CI runner's noise, and
        // below what the regressions they stand guard over cost.
        /** 7.8 MB. Held chats keeping every finished turn's trace (0.4.1's out-of-memory crash) added 44 MB in 20 turns. */
        const val FLEET_HEAP = 32 * MB
        /** 3.4 MB: the twenty held streams' own words, and the chats opened. */
        const val FLEET_BYTES_IN = 8 * MB
        /** 135 MB (160 MB before hex digests and timestamps stopped being formatted and parsed afresh at every read). */
        const val FLEET_ALLOCATED = 300 * MB
        /** 838. A held chat re-read in a loop while its record and run list disagreed made 3,600. */
        const val FLEET_CALLS = 1_500
        /** 20.9 MB, of which some 16 MB is Robolectric's framework resource table, loaded once mid-scenario. */
        const val HUGE_OPEN_HEAP = 40 * MB
        /** 16.5 MB, the same 16 MB of it Robolectric's: the long chat itself is gone from memory once it is closed. */
        const val HUGE_CLOSED_HEAP = 22 * MB
        /** 3.6 MB: a page of turns' prompts and messages at a time, each turn's blobs once. */
        const val HUGE_BYTES_IN = 8 * MB
        /** 538 MB (1,394 MB before). */
        const val HUGE_ALLOCATED = 900 * MB
        /** 2,886, nearly all of them one blob each. */
        const val HUGE_CALLS = 4_000
        /** Nothing at all: keep chats live stands down in the background, and nothing else streams. */
        const val BACKGROUND_BYTES_IN = 256L * 1024
        /** 0 of 10 (10 of 10 before runs leaving the live table were parked to be resumed). */
        const val REOPEN_REPLAYS = 1
        /** 0.1 MB: what the turns did meanwhile, and the chats' own reads. Replaying their runs from the first event was 1.9 MB. */
        const val REOPEN_BYTES_IN = 512L * 1024
    }
}
