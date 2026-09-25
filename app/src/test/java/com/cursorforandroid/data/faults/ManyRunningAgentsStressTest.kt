package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.crash.CrashContext
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptRows
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * An account like Bennett's on 0.3.99 and past it — tens to hundreds of chats running, several Projects — with the
 * reader switching between Projects as fast as a thumb can, background live sync on and off, and the list refreshing
 * underneath: the app's own stack against a server that holds every running turn's stream open, as the API does.
 * What must never happen is what killed his app twice: an exception nobody catches (on any thread), threads or run
 * streams without bound, or the chats kept in memory growing with the account.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManyRunningAgentsStressTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 40L..160L, http2 = true).start()
        server.holdRunningStreamsMs = 120_000L
        // Small pages, so the list is read in several and reorders between them as turns start and end.
        server.pageSize = 30
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

    /** [total] chats, [running] of them with a turn under way, and [projects] Projects each coordinating a share of them. */
    private fun account(total: Int, running: Int, projects: Int): List<String> {
        val coordinators = (0 until projects).map { "bc-project-$it" }
        coordinators.forEachIndexed { p, id -> addChat(id, "Project $p", now - 10_000L * p) }
        repeat(total) { i -> addChat("bc-load-$i", "Agent $i", now - 100_000L - 1_000L * i) }
        coordinators.forEachIndexed { p, id ->
            server.composers[id] = FaultServer.Composer(id, "Project $p", activityMs = now - 10_000L * p, project = true, running = true)
            val workers = (0 until total).filter { it % projects == p }.take(12).map { "bc-load-$it" }
            server.workers[id] = workers.map { it to "MANAGER_SPAWN_KIND_CREATED" }
            workers.forEach { w -> server.composers[w] = FaultServer.Composer(w, w, activityMs = now - 100_000L, manager = id) }
        }
        (coordinators + (0 until running).map { "bc-load-$it" }).forEachIndexed { n, id -> server.startTurnElsewhere(id, "Keep going on task $n.", "run-live-$n") }
        return coordinators
    }

    private fun addChat(id: String, name: String, at: Long) {
        val runId = "run-done-$id"
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done with $name.")
        server.logs[runId] = listOf(
            "assistant" to """{"text":"Done with $name."}""",
            "result" to """{"runId":"$runId","status":"FINISHED","text":"Done with $name.","durationMs":60000}""",
        )
        server.agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Start $name."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done with $name."))
        server.composers.putIfAbsent(id, FaultServer.Composer(id, name, activityMs = at))
    }

    private fun rig(): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 10_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also {
            it.now = now
            rigs += it
        }

    /** Keys a lazy list would have been handed twice (Compose aborts the frame, and the process, on the second). */
    private val duplicateKeys = CopyOnWriteArrayList<String>()

    private fun <T> checkUnique(where: String, items: List<T>, key: (T) -> String) {
        val seen = HashSet<String>()
        items.forEach { val k = key(it); if (!seen.add(k)) duplicateKeys += "$where: $k" }
    }

    /** What the sidebar and every open transcript would key their lists on, now. */
    private fun checkKeys(rig: FaultRig) {
        val agents = rig.agents.state.value.agents
        checkUnique("agent list", agents) { it.id }
        for (groupBy in listOf(GroupBy.Date, GroupBy.None, GroupBy.Status)) {
            val sections = AgentListOrganizer.organize(agents, ListPreferences(groupBy = groupBy), LocalAgentState(), nowMillis = now)
            checkUnique("sidebar headers ($groupBy)", sections) { "hdr-${it.key}" }
            sections.forEach { section ->
                val everyParentOpen = section.rows.flatMap { listOf(it) + it.descendants() }.mapTo(HashSet()) { it.agent.id }
                checkUnique("sidebar rows ($groupBy)", AgentListOrganizer.flatten(section.rows, everyParentOpen)) { "${section.key}:${it.row.agent.id}" }
            }
        }
        for (id in watched) {
            val state = rig.conversations.state(id).value
            checkUnique("transcript items $id", state.items) { it.id }
            for (coordinator in listOf(false, true)) checkUnique("transcript rows $id", TranscriptRows.of(state.items, coordinator, runActive = state.isStreaming)) { it.key }
        }
    }

    private val watched = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The server's side of a busy account: turns end and new ones start elsewhere, so the list reorders between pages. */
    private fun churn(round: Int, total: Int) {
        val random = java.util.Random(round.toLong())
        repeat(6) {
            val id = "bc-load-${random.nextInt(total)}"
            val agent = server.agents[id] ?: return@repeat
            if (agent.status == "ACTIVE") server.endTurn(id, durationMs = 5_000L) else server.startTurnElsewhere(id, "Another turn $round.$it.", "run-churn-$round-$it")
        }
    }

    private class Peaks {
        var threads = 0
        var chats = 0
        var streams = 0
        var pools = ""
        var heapMb = 0L
    }

    /**
     * The reader's thumb: each Project opened (its view and its coordinator's chat, one worker's chat beside it),
     * left after [dwellMs], the next one opened; the list refreshed every few switches; live sync toggled when
     * [toggleSync] says so. Returns the peaks seen along the way.
     */
    private suspend fun switchProjects(rig: FaultRig, sync: LiveSync, syncOn: MutableStateFlow<Boolean>, projects: List<String>, total: Int, rounds: Int, dwellMs: Long, toggleSync: Boolean): Peaks {
        val peaks = Peaks()
        repeat(rounds) { r ->
            for ((i, p) in projects.withIndex()) {
                val worker = server.workers[p].orEmpty().getOrNull(r % 12)?.first
                watched += p
                worker?.let { watched += it }
                rig.projects.attach(p)
                rig.conversations.attach(p)
                sync.opened(p)
                worker?.let { rig.conversations.attach(it); sync.opened(it) }
                delay(dwellMs)
                checkKeys(rig)
                worker?.let { rig.conversations.detach(it) }
                rig.conversations.detach(p)
                rig.projects.detach(p)
                if ((r * projects.size + i) % 5 == 0) rig.scope.launch { runCatching { rig.agents.refresh() } }
            }
            if (toggleSync && r % 4 == 3) syncOn.value = !syncOn.value
            churn(r, total)
            rig.scope.launch { runCatching { rig.agents.loadMore() } }
            sample(rig, peaks)
        }
        return peaks
    }

    private fun sample(rig: FaultRig, peaks: Peaks) {
        val threads = Thread.getAllStackTraces().keys
        if (threads.size > peaks.threads) {
            peaks.threads = threads.size
            peaks.pools = threads.groupingBy { CrashContext.pool(it.name) }.eachCount().entries.sortedByDescending { it.value }.take(8).joinToString { "${it.value}×${it.key}" }
        }
        val chats = Regex("chats=(\\d+)").find(rig.conversations.stats())!!.groupValues[1].toInt()
        peaks.chats = maxOf(peaks.chats, chats)
        val streams = Regex("streaming=(\\d+)").find(rig.hub.stats())!!.groupValues[1].toInt()
        peaks.streams = maxOf(peaks.streams, streams)
        val rt = Runtime.getRuntime()
        peaks.heapMb = maxOf(peaks.heapMb, (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
    }

    private fun scenario(total: Int, running: Int, liveSyncOn: Boolean, toggle: Boolean): Peaks = runBlocking {
        val projects = account(total, running, projects = 4)
        val rig = rig()
        rig.agents.refresh()
        // The list pages the reader scrolls through, as a sidebar of hundreds is read.
        repeat(3) { runCatching { rig.agents.loadMore() } }
        val syncOn = MutableStateFlow(liveSyncOn)
        val sync = LiveSync(
            target = object : LiveSync.Target {
                override fun hold(agentId: String) = rig.conversations.hold(agentId)
                override fun release(agentId: String) = rig.conversations.release(agentId)
                override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
            },
            scope = rig.scope,
            settleTimeoutMs = 5_000L,
        )
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), syncOn, MutableStateFlow(true))
        val peaks = switchProjects(rig, sync, syncOn, projects, total, rounds = 12, dwellMs = 120L, toggleSync = toggle)
        // Settle: whatever the switching set going finishes or fails, but does not throw where nobody catches.
        delay(3_000)
        sample(rig, peaks)
        checkKeys(rig)
        println("STRESS total=$total running=$running sync=$liveSyncOn toggle=$toggle: peak threads=${peaks.threads} [${peaks.pools}] chats=${peaks.chats} streams=${peaks.streams} heap=${peaks.heapMb}MB held=${sync.heldIds.value.size} requests=${server.seen.size}")
        println("STRESS   conversations: ${rig.conversations.stats()} · hub: ${rig.hub.stats()}")
        peaks
    }

    private fun assertHealthy(peaks: Peaks) {
        assertThat(duplicateKeys.distinct().take(20)).isEmpty()
        assertThat(uncaught.map { (thread, e) -> "$thread: ${e.stackTraceToString().take(2_000)}" }).isEmpty()
        // The chats kept in memory are the reader's few, the held ones and the cache's LRU: never the account.
        assertThat(peaks.chats).isAtMost(MAX_CHATS)
        // One stream per running chat followed: the held ones (at most MAX_HELD) plus the screens'.
        assertThat(peaks.streams).isAtMost(LiveSync.MAX_HELD + 6)
    }

    @Test
    fun `forty running chats, Projects switched fast, keep chats live on`() = assertHealthy(scenario(total = 60, running = 40, liveSyncOn = true, toggle = false))

    @Test
    fun `forty running chats, Projects switched fast, keep chats live off`() = assertHealthy(scenario(total = 60, running = 40, liveSyncOn = false, toggle = false))

    @Test
    fun `two hundred chats, a hundred and twenty running, live sync toggled while switching`() = assertHealthy(scenario(total = 200, running = 120, liveSyncOn = true, toggle = true))

    private companion object {
        /** The conversations' memory: its LRU of 24, plus what screens and holds keep beyond it. */
        const val MAX_CHATS = 24 + LiveSync.MAX_HELD + 4
    }
}
