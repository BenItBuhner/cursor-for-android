package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.ProjectViewState
import com.cursorforandroid.data.repo.RootScanRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
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
import java.time.Instant

/**
 * What one open Project costs on the wire, over a 300–900 ms connection. The Project's poll ([ProjectRepository.attach])
 * runs at a tenth of production's interval (2 s for 20 s) against a clock moved ten times faster than wall time, so
 * 30 s of wall time is five minutes of a Project on screen. An idle Project re-reads its memberships at most once a
 * minute, a change of the coordinator's record is still seen at the next tick, and a server refusing the poll is
 * asked less and less often until it answers again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectPollBudgetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val projectId = "bc-proj-poll"
    private val start = 1_800_000_000_000L
    @Volatile private var clockStep = 100L * SCALE
    @Volatile private var view: ProjectViewState? = null
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"), extended = true)
        fun created(ms: Long) = Instant.ofEpochMilli(ms).toString()
        server.addIdleAgent(projectId, "Poll Project", "run-$projectId", createdAt = created(start - 3_600_000L))
        server.composers[projectId] = Composer(projectId, "Poll Project", start - 3_600_000L, project = true)
        server.workers[projectId] = (1..20).map { "$projectId-w$it" to "MANAGER_SPAWN_KIND_CREATED" }
        (1..20).forEach { w -> addWorker(w, start - 3_600_000L - w * 60_000L) }
        (1..2).forEach { s ->
            val sid = "$projectId-side$s"
            server.addIdleAgent(sid, "Side $s", "run-$sid", createdAt = created(start - 3_500_000L))
            server.composers[sid] = Composer(sid, "Side $s", start - 3_500_000L, sideChatOf = projectId)
        }
        repeat(40) { i ->
            val id = "bc-own-${i + 1}"
            val at = start - i * 6 * 3_600_000L - 30_000L
            server.addIdleAgent(id, "Own ${i + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Own ${i + 1}", at)
        }
        server.pageSize = 50
        rig.now = start + 60_000L
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        rig.close()
        server.close()
    }

    private fun addWorker(n: Int, at: Long, manager: String? = projectId) {
        val wid = "$projectId-w$n"
        server.addIdleAgent(wid, "Worker $n", "run-$wid", createdAt = Instant.ofEpochMilli(at).toString())
        server.composers[wid] = Composer(wid, "Worker $n", at, manager = manager)
    }

    /** The account's list is read and the Project known, then a poll of its own is attached and its view followed. */
    private suspend fun attached(): ProjectRepository {
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        jobs += rig.scope.launch { while (true) { delay(100); rig.now += clockStep } }
        val lineage = object : ProjectLineageApi by rig.projectApi {
            override suspend fun record(id: String): ComposerSnapshot? = rig.accountAgents.record(id)
            override suspend fun scanRoots(maxPages: Int): RootScan = rig.accountAgents.scanRoots(maxPages)
            override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan = rig.accountAgents.scanRoots(maxPages, stopBelowActivityMillis)
        }
        val repo = ProjectRepository(rig.session, rig.agents, lineage, actions = rig.projectApi, store = rig.projectApi, scope = rig.scope, pollIntervalMs = 20_000L / SCALE, capabilities = { rig.capabilities }, retryDelaysMs = listOf(500L))
        jobs += rig.scope.launch { repo.view(projectId).collect { view = it } }
        delay(1_000)
        return repo
    }

    private suspend fun window(label: String, wallMs: Long, body: suspend () -> Unit = {}): Map<Route, Int> {
        val from = server.seen.size
        val started = System.nanoTime()
        body()
        val left = wallMs - (System.nanoTime() - started) / 1_000_000
        if (left > 0) delay(left)
        val counts = server.seen.drop(from).groupingBy { it.route }.eachCount().toSortedMap()
        println("POLL[$label] wall=${wallMs}ms simulated=${"%.1f".format(wallMs * clockStep / 100 / 60_000.0)}min total=${counts.values.sum()} routes=$counts")
        return counts
    }

    @Test
    fun `an idle open Project reads its memberships at most once a minute and still sees a change at the next tick`() = runBlocking<Unit> {
        val repo = attached()
        val idle = window("idle, 5 simulated min", 30_000) { repo.attach(projectId) }
        val ticks = idle[Route.AccountList] ?: 0
        val workers = idle[Route.Workers] ?: 0
        val children = idle[Route.Children] ?: 0
        assertThat(ticks).isAtLeast(5)
        // One read on attach, then one per simulated minute at most; the list goes on being read every tick.
        assertThat(workers).isAtMost(6)
        assertThat(children).isAtMost(6)
        assertThat(workers).isAtMost(ticks / 2 + 2)

        // At wall speed, just after a read by hand, the minute never runs out here: what is read next is what the
        // coordinator's record moving asks for.
        clockStep = 100L
        repo.refreshView(projectId)
        val quiet = window("unchanged record, clock at wall speed", 6_000)
        assertThat(quiet[Route.AccountList] ?: 0).isAtLeast(1)
        assertThat(quiet[Route.Workers] ?: 0).isEqualTo(0)
        assertThat(quiet[Route.Children] ?: 0).isEqualTo(0)

        val newcomer = "$projectId-w21"
        // Its own record names no manager: only the memberships say it is the Project's.
        addWorker(21, rig.now, manager = null)
        server.workers[projectId] = server.workers[projectId].orEmpty() + (newcomer to "MANAGER_SPAWN_KIND_CREATED")
        server.composers[projectId] = server.composers.getValue(projectId).copy(activityMs = rig.now)
        val seenAt = System.nanoTime()
        rig.awaitUntil(10_000) { view?.workers?.any { it.id == newcomer } == true }
        val waitedMs = (System.nanoTime() - seenAt) / 1_000_000
        println("POLL new worker shown after ${waitedMs}ms wall")
        assertThat(waitedMs).isLessThan(8_000)
        repo.detach(projectId)
    }

    @Test
    fun `a Project whose poll is refused backs off and returns to its interval once answered`() = runBlocking<Unit> {
        val repo = attached()
        repo.attach(projectId)
        rig.awaitUntil(30_000) { view?.hasSynced == true && view?.isSyncing == false }
        val refusal = Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1")
        val refused = listOf(Route.Workers, Route.Children, Route.ListAgents)
        val first = window("429 on Workers+Children+ListAgents, first 5 simulated min", 30_000) { refused.forEach { server.outage(it, refusal) } }
        // 2 s, then 4, 8 and 16 s (plus jitter) between ticks rather than one every 2 s; then 16 s from there on.
        assertThat(first.values.sum()).isAtMost(30)
        val next = window("429 on Workers+Children+ListAgents, next 5 simulated min", 30_000)
        refused.forEach { server.clear(it) }
        assertThat(next[Route.AccountList] ?: 0).isAtMost(2)
        assertThat(next.values.sum()).isAtMost(26)

        // The next tick that is answered resets the wait: ticks come at the plain interval again.
        val cleared = server.seen.size
        rig.awaitUntil(30_000) { server.seen.drop(cleared).any { it.route == Route.ListAgents && it.fault == null } && view?.isSyncing == false }
        delay(1_000)
        val recovered = window("answered again", 14_000)
        assertThat(recovered[Route.AccountList] ?: 0).isAtLeast(2)
        repo.detach(projectId)
    }

    private companion object {
        const val SCALE = 10L
    }
}
