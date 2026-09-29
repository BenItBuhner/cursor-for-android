package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.DeviceNetwork
import com.cursorforandroid.data.api.LOOKUP_FAILED_ONLINE
import com.cursorforandroid.data.api.OFFLINE
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bennett's v0.3.85 frame: a Project chat, with Wi-Fi and cellular both up, said "Couldn't refresh the transcript:
 * You're offline", its load stuck, `live: stream=none`, and chats in general having a hard time. Over HTTP/2 — how the
 * phone speaks to the account's host, every call on one connection — with the account's live streams held the way
 * the account holds them (heartbeats, never ended by the server), OkHttp's own limits, and several chats opened and
 * left before the Project, as a reader moving between a Project's workers does:
 *  - a live stream the watch gave up on (its chat left, or left rest) was read on for good behind the screen, and two
 *    of them took both of the live lane's permits, so no chat opened after them could hold one;
 *  - "offline" was any failed name lookup, with the phone online; now it is the phone's own word, a lookup the resolver
 *    fails is answered with the host's addresses of a moment ago, and a chat whose load could not reach Cursor reads
 *    itself again once it can, rather than keeping the failure up until Retry.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FalseOfflineTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val project = BigProject.AGENT_ID
    private val workers = BigProject.WORKERS.take(3)
    private val projectTurns = BigProject.turns(now - 120 * BigProject.TURN_SPACING_MS - 60_000L, turns = 120)
    private val resolverDown = "Unable to resolve host \"api2.cursor.sh\": No address associated with hostname"

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 100L..200L, http2 = true).start()
        server.liveHeartbeatMs = 300L
        workers.forEachIndexed { i, id -> addChat(id, "Worker ${i + 1}", BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6), manager = project) }
        addChat(project, BigProject.AGENT_NAME, projectTurns, manager = null)
        server.composers[project] = FaultServer.Composer(project, BigProject.AGENT_NAME, activityMs = now - 1_000L, project = true)
        server.workers[project] = workers.map { it to "MANAGER_SPAWN_KIND_CREATED" }
    }

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
        DeviceNetwork.install { null }
    }

    private fun addChat(id: String, name: String, turns: List<BigProject.Turn>, manager: String?) {
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = id, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.lastOrNull())
            if (BigProject.retained(turn, now)) server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[id] = AgentDto(id = id, name = name, status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = name, status = "FINISHED")
        server.transcripts[id] = BigProject.v0Transcript(turns)
        server.records[id] = turns.flatMap { it.record }
        if (manager != null) server.composers[id] = FaultServer.Composer(id, name, activityMs = newest.endedAt, manager = manager)
    }

    private fun rig(disk: File = folder.newFolder(), quietRetryDelaysMs: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L)): FaultRig = FaultRig(server.baseUrl, disk, readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true, quietRetryDelaysMs = quietRetryDelaysMs).also {
        it.now = now
        rigs += it
    }

    private fun FaultRig.state(id: String): ConversationState = conversations.state(id).value

    /** Every failure the chat says from now on, under the transcript or in its place: one up for as long as a frame, too. */
    private fun FaultRig.saidFailures(id: String): CopyOnWriteArrayList<String> {
        val seen = CopyOnWriteArrayList<String>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { conversations.state(id).collect { s -> listOfNotNull(s.transcriptError, s.error).forEach { seen += it } } }
        return seen
    }

    /** The Project saved by an earlier process on [disk], at rest: what the next process opens on. Its items on screen. */
    private suspend fun savedProject(disk: File): Int {
        val first = rig(disk)
        first.openAtRest(project)
        // A coordinator's window goes on widening and filling in behind the first paint: the saved copy is all of it.
        first.awaitUntil(30_000) { !first.state(project).isLoadingOlder && first.conversations.loadDiagnostics(project)!!.beta!!.incomplete == 0 }
        val items = first.state(project).items.size
        rigs.single().close()
        rigs.clear()
        return items
    }

    private fun ConversationState.atRest(): Boolean = items.isNotEmpty() && !isLoading && !isStreaming && runStatus?.isActive != true && traceStatus.pending == 0

    /** Opens [id] and waits for it at rest, its live stream held. */
    private suspend fun FaultRig.openAtRest(id: String) {
        conversations.attach(id)
        awaitUntil(60_000) { state(id).atRest() }
        awaitUntil(10_000) { server.liveOpen(id) == 1 }
    }

    private fun report(label: String, rig: FaultRig) {
        val held = (workers + project).associate { it.take(12) to server.liveOpen(it) }
        println("   $label: live streams held $held; connections=${server.connections.get()} lookups=${rig.dnsLookups.get()} fromKept=${rig.lastGoodDns.answeredFromKept}")
    }

    @Test
    fun `a live stream given up on is closed, not read on behind the screen`() = runBlocking<Unit> {
        val rig = rig()
        val worker = workers.first()
        val watch = rig.scope.launch { rig.record!!.watch(worker, since = null, resume = false) }
        rig.awaitUntil(10_000) { server.liveOpen(worker) == 1 }
        delay(1_000)
        watch.cancel()
        val cancelledAt = System.nanoTime()
        val closed = runCatching { rig.awaitUntil(5_000) { server.liveOpen(worker) == 0 && watch.isCompleted } }.isSuccess
        println("   given up on: ${if (closed) "closed after ${(System.nanoTime() - cancelledAt) / 1_000_000} ms" else "still held by the server after 5 s, the watch job ${if (watch.isCompleted) "ended" else "still running"}"}")
        assertWithMessage("the stream the watch gave up on, still held by the server").that(server.liveOpen(worker)).isEqualTo(0)
        assertThat(watch.isCompleted).isTrue()
    }

    @Test
    fun `chats opened and left hold no live stream, and the Project open after them still hears a turn started elsewhere`() = runBlocking<Unit> {
        val rig = rig()
        rig.agents.refresh()
        var lookups = -1
        var connections = -1
        for (worker in workers) {
            rig.openAtRest(worker)
            if (lookups < 0) {
                lookups = rig.dnsLookups.get()
                connections = server.connections.get()
            }
            rig.conversations.detach(worker)
            // The screen left: its stream goes with it, as the desktop's does when a chat closes.
            rig.awaitUntil(5_000) { server.liveOpen(worker) == 0 }
            report("after ${worker.take(12)}", rig)
        }
        rig.openAtRest(project)
        report("the Project open", rig)
        val prompt = "Coordinator: status of market 200, please."
        val started = System.nanoTime()
        server.startTurnElsewhere(project, prompt)
        rig.awaitUntil(15_000) { rig.state(project).items.any { it is UserMessage && it.text == prompt } }
        println("   the turn started elsewhere was on screen after ${(System.nanoTime() - started) / 1_000_000} ms")
        assertThat(server.liveOpenCount()).isAtMost(1)
        // The connections the first chat opened carried all of it: no chat and no stream cost a lookup of its own.
        assertThat(server.connections.get()).isEqualTo(connections)
        assertThat(rig.dnsLookups.get()).isEqualTo(lookups)
    }

    @Test
    fun `a lookup the resolver fails once the host has been reached reads the chat anyway, from the addresses of a moment ago`() = runBlocking<Unit> {
        DeviceNetwork.install { true }
        val rig = rig()
        rig.agents.refresh()
        rig.openAtRest(project)
        // A network handoff: the connection goes, and the resolver does not answer for the new one.
        rig.dnsFailure = resolverDown
        rig.accountClient.dispatcher.cancelAll()
        rig.client.dispatcher.cancelAll()
        rig.accountClient.connectionPool.evictAll()
        rig.client.connectionPool.evictAll()
        val connectionsBefore = server.connections.get()
        val from = server.seen.size
        rig.conversations.revalidate(project, force = true)
        rig.awaitUntil(5_000) { server.seen.drop(from).any { it.route == Route.RecordState } }
        rig.awaitUntil(30_000) { rig.state(project).atRest() }
        report("after the handoff", rig)
        assertThat(rig.state(project).transcriptError).isNull()
        assertThat(rig.state(project).error).isNull()
        assertThat(server.connections.get()).isGreaterThan(connectionsBefore)
        assertThat(rig.lastGoodDns.answeredFromKept).isAtLeast(1)
    }

    /**
     * Bennett's 0.4.15 coordinator: `record: error="You're offline." cause="UnknownHostException" online=true`. The
     * phone back from Doze, the resolver not answering for a moment and nothing looked up yet in this process to fall
     * back on: the read is asked again unseen, like any transient failure, and the chat catches up with nothing said.
     */
    @Test
    fun `a lookup that fails for a moment with the phone online is retried unseen, and the chat catches up with nothing said`() = runBlocking<Unit> {
        DeviceNetwork.install { true }
        val disk = folder.newFolder("disk")
        savedProject(disk)

        val second = rig(disk, quietRetryDelaysMs = listOf(500L, 1_000L, 2_000L, 4_000L))
        second.dnsFailure = resolverDown
        val said = second.saidFailures(project)
        second.conversations.attach(project)
        // The first read has failed on the lookup — said at once, before — and a quiet retry has failed after it.
        second.awaitUntil(60_000) { second.conversations.loadDiagnostics(project)?.record?.error != null }
        delay(1_000)
        val failedLookups = second.dnsLookups.get()
        second.dnsFailure = null
        val cleared = System.nanoTime()
        second.awaitUntil(30_000) { second.state(project).atRest() && second.conversations.loadDiagnostics(project)!!.fetched }
        println("   $failedLookups lookups failed; caught up ${(System.nanoTime() - cleared) / 1_000_000} ms after the resolver answered; said: $said")
        assertWithMessage("lookups that failed before the resolver answered").that(failedLookups).isAtLeast(2)
        assertWithMessage("a moment's failed lookup, with the phone online, said to the reader").that(said).isEmpty()
        assertThat(second.state(project).transcriptError).isNull()
    }

    @Test
    fun `a lookup that keeps failing with the phone online is said after the quiet retries, never as offline, and clears once the host answers`() = runBlocking<Unit> {
        DeviceNetwork.install { true }
        val disk = folder.newFolder("disk")
        val before = savedProject(disk)

        // The next process starts on a phone whose resolver is not answering: nothing looked up yet to fall back on.
        val second = rig(disk, quietRetryDelaysMs = listOf(300L, 600L, 900L))
        second.dnsFailure = resolverDown
        val said = second.saidFailures(project)
        second.conversations.attach(project)
        second.awaitUntil(60_000) { second.state(project).transcriptError != null && !second.state(project).isLoading }
        val shown = second.state(project)
        println("   the saved copy stands (${shown.items.size} items) with: ${shown.transcriptError}; lookups ${second.dnsLookups.get()}")
        assertThat(shown.items.size).isEqualTo(before)
        assertThat(shown.transcriptError).isEqualTo(LOOKUP_FAILED_ONLINE)
        assertWithMessage("a lookup failed with the phone online, said as offline").that(said).doesNotContain(OFFLINE)
        assertWithMessage("lookups: the first read's and one per quiet retry").that(second.dnsLookups.get()).isAtLeast(4)

        // The resolver answers again: the chat reads itself, with no Retry tapped, and goes back to holding its live stream.
        second.dnsFailure = null
        val cleared = System.nanoTime()
        val liveAsked = server.requests(Route.Live).size
        second.awaitUntil(30_000) { second.state(project).let { it.transcriptError == null && it.atRest() } }
        println("   read again by itself ${(System.nanoTime() - cleared) / 1_000_000} ms after the resolver answered")
        second.awaitUntil(10_000) { server.requests(Route.Live).size > liveAsked }
        assertThat(second.conversations.loadDiagnostics(project)!!.record!!.unreached).isFalse()
    }

    /**
     * The phone reads offline for a moment as it wakes from Doze (the system hands the app no network until it lets
     * it back on): the failure met in that moment is not said as "offline" once the phone is back online.
     */
    @Test
    fun `a moment's offline reading as the phone wakes is not said, and the chat reads itself once it is back online`() = runBlocking<Unit> {
        var online = true
        DeviceNetwork.install { online }
        val disk = folder.newFolder("disk")
        savedProject(disk)

        online = false
        val second = rig(disk)
        second.dnsFailure = resolverDown
        val said = second.saidFailures(project)
        second.conversations.attach(project)
        // The read has failed while the phone read offline; it is back online a moment later, as Doze lets it go.
        second.awaitUntil(60_000) { second.conversations.loadDiagnostics(project)?.record?.error != null }
        delay(500)
        online = true
        second.dnsFailure = null
        second.awaitUntil(30_000) { second.state(project).atRest() && second.conversations.loadDiagnostics(project)!!.fetched }
        println("   said while the phone woke: $said")
        assertThat(said).isEmpty()
    }

    @Test
    fun `offline is the phone's own word, and nothing is asked while it has no network`() = runBlocking<Unit> {
        var online = true
        DeviceNetwork.install { online }
        val disk = folder.newFolder("disk")
        rig(disk).openAtRest(project)
        rigs.single().close()
        rigs.clear()

        online = false
        val second = rig(disk)
        second.dnsFailure = resolverDown
        second.conversations.attach(project)
        second.awaitUntil(60_000) { second.state(project).transcriptError != null && !second.state(project).isLoading }
        assertThat(second.state(project).transcriptError).isEqualTo(OFFLINE)
        val lookups = second.dnsLookups.get()
        delay(6_000)
        assertWithMessage("lookups made while the phone had no network").that(second.dnsLookups.get()).isEqualTo(lookups)

        online = true
        second.dnsFailure = null
        second.awaitUntil(30_000) { second.state(project).let { it.transcriptError == null && it.atRest() } }
    }
}
