package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * The sidebar's Extended-mode refresh while the account service throttles or stalls (`ListBackgroundComposers`
 * refused with `429 Retry-After: 3`, or never answered), over the app's stack against [FaultServer] at 300–900 ms
 * round trips on HTTP/2. The public list does not wait on the account: a refresh returns within a few seconds
 * whatever the account's list does, its first rows are not held past a round trip's budget, and once the account
 * answers again the rows end exactly as a healthy refresh leaves them — placed by the account's records, by the
 * desktop's predicates, and nothing else.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentRepositoryAccountStallTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 300L..900L, http2 = true).start()
        buildAccount()
    }

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    /** Two Projects with their workers, a side chat and sixty chats of the account's own; the public list paged in twenties, the account's in thirties. */
    private fun buildAccount() {
        fun created(ms: Long) = Instant.ofEpochMilli(ms).toString()
        listOf("bc-proj-1" to 5, "bc-proj-2" to 3).forEachIndexed { p, (id, members) ->
            val at = NOW - (p + 1) * 3_600_000L
            server.addIdleAgent(id, "Project ${p + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Project ${p + 1}", at, project = true)
            server.workers[id] = (1..members).map { "$id-w$it" to "MANAGER_SPAWN_KIND_CREATED" }
            (1..members).forEach { w ->
                val wid = "$id-w$w"
                val wat = at - w * 60_000L
                server.addIdleAgent(wid, "Worker $w of ${p + 1}", "run-$wid", createdAt = created(wat))
                server.composers[wid] = Composer(wid, "Worker $w of ${p + 1}", wat, manager = id)
            }
        }
        server.addIdleAgent("bc-proj-2-side", "Side chat", "run-side", createdAt = created(NOW - 2 * 3_600_000L - 10_000L))
        server.composers["bc-proj-2-side"] = Composer("bc-proj-2-side", "Side chat", NOW - 2 * 3_600_000L - 10_000L, sideChatOf = "bc-proj-2")
        repeat(60) { i ->
            val id = "bc-own-${i + 1}"
            val at = NOW - i * 12 * 3_600_000L - 30_000L
            server.addIdleAgent(id, "Own chat ${i + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Own chat ${i + 1}", at)
        }
        server.pageSize = 20
        server.accountPageSize = 30
    }

    private fun newRig(name: String, readTimeoutMs: Long = 3_000L): FaultRig {
        rig?.close()
        server.seen.clear()
        return FaultRig(server.baseUrl, folder.newFolder(name), extended = true, http2 = true, readTimeoutMs = readTimeoutMs).also {
            it.now = NOW + 60_000L
            rig = it
        }
    }

    /**
     * What the sidebar draws, by the organizer the sidebar draws with: every section and its tree of rows, and every
     * row's place (its parent, whether it is a Project) and whether its account record has landed.
     */
    private fun composition(rig: FaultRig): List<String> {
        val list = rig.agents.state.value
        val sections = AgentListOrganizer.organize(list.agents, ListPreferences(), LocalAgentState(), nowMillis = rig.now, zone = ZoneOffset.UTC, knownRoots = rig.agents.knownRoots.value)
        fun AgentRow.lines(depth: Int): List<String> =
            listOf("${"  ".repeat(depth)}${agent.id} \"${agent.name}\"" + (if (isPlaceholder) " placeholder" else "") + (if (isStandIn) " stand-in" else "")) + children.flatMap { it.lines(depth + 1) }
        val tree = sections.flatMap { s -> listOf("[${s.key}]") + s.rows.flatMap { it.lines(1) } }
        val rows = list.agents.map { "${it.id} parent=${it.parent?.id} root=${it.isProjectRoot} record=${it.record != null}" }.sorted()
        return tree + rows
    }

    /** A healthy cold refresh, and the sidebar it leaves once everything has landed: what a stalled one must end as. */
    private suspend fun healthyComposition(): List<String> {
        val healthy = newRig("healthy")
        healthy.agents.refresh()
        healthy.agents.loadMore()
        var last: List<String> = emptyList()
        // Settled: the same composition for a couple of seconds running, every row with its record.
        healthy.awaitUntil(60_000) {
            val now = composition(healthy)
            val settled = now == last && healthy.agents.state.value.agents.all { it.record != null } && healthy.pending.items.isEmpty()
            last = now
            if (!settled) kotlinx.coroutines.delay(1_000)
            settled
        }
        assertThat(last.filter { it.contains("record=false") }).isEmpty()
        return last
    }

    private class Timings(val firstRowsMs: Long?, val refreshMs: Long?, val accountListCalls: Int, val loadMoreMs: Long?)

    /** A cold Extended refresh under [fault] on the account's list, timed from the moment it is asked for. */
    private suspend fun stalledRefresh(label: String, fault: Fault, readTimeoutMs: Long = 3_000L): Pair<FaultRig, Timings> {
        val stalled = newRig(label, readTimeoutMs)
        server.outage(Route.AccountList, fault)
        val startedAt = System.nanoTime()
        fun elapsed() = (System.nanoTime() - startedAt) / 1_000_000
        var firstRowsMs: Long? = null
        val watcher = stalled.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stalled.agents.state.first { it.agents.isNotEmpty() }
            firstRowsMs = elapsed()
        }
        val refreshMs = withTimeoutOrNull(300_000) { stalled.agents.refresh(); elapsed() }
        watcher.cancel()
        val calls = server.requests(Route.AccountList).size
        // The reader at the end of the list meanwhile: the next page does not wait on the account either.
        val loadStartedAt = System.nanoTime()
        val loadMoreMs = withTimeoutOrNull(300_000) { stalled.agents.loadMore(); (System.nanoTime() - loadStartedAt) / 1_000_000 }
        val timings = Timings(firstRowsMs, refreshMs, calls, loadMoreMs)
        println("$label: first rows ${timings.firstRowsMs} ms, refresh returned ${timings.refreshMs} ms, ${timings.accountListCalls} AccountList calls by then, loadMore ${timings.loadMoreMs} ms")
        return stalled to timings
    }

    /** The account answers again; the next refresh (a poll, a pull) and the records it brings leave the sidebar as a healthy one does. */
    private suspend fun assertRecovers(stalled: FaultRig, healthy: List<String>, label: String) {
        server.clear(Route.AccountList)
        val startedAt = System.nanoTime()
        stalled.agents.refresh()
        var last = composition(stalled)
        val deadline = startedAt + RECOVERY_BUDGET_MS * 1_000_000
        while (last != healthy && System.nanoTime() < deadline) {
            kotlinx.coroutines.delay(100)
            last = composition(stalled)
        }
        println("$label: rows identical to the healthy refresh ${(System.nanoTime() - startedAt) / 1_000_000} ms after the account answered again")
        assertWithMessage("the sidebar once the account answers again ($label)").that(last).containsExactlyElementsIn(healthy).inOrder()
    }

    @Test
    fun `an account list refused with 429 holds neither the refresh nor its first rows, and the rows end as a healthy refresh leaves them`() = runBlocking<Unit> {
        val healthy = healthyComposition()
        val (stalled, timings) = stalledRefresh("429 Retry-After 3", Fault.Status(429, "resource_exhausted", "Too many requests.", retryAfter = "3"))

        assertWithMessage("first rows").that(timings.firstRowsMs).isNotNull()
        assertWithMessage("first rows (ms)").that(timings.firstRowsMs!!).isAtMost(FIRST_ROWS_BUDGET_MS)
        assertWithMessage("the refresh returned").that(timings.refreshMs).isNotNull()
        assertWithMessage("refresh (ms)").that(timings.refreshMs!!).isAtMost(REFRESH_BUDGET_MS)
        assertWithMessage("loadMore (ms)").that(timings.loadMoreMs ?: Long.MAX_VALUE).isAtMost(REFRESH_BUDGET_MS)
        // The rows the public list brought are on screen, bare, while the account refuses.
        assertThat(stalled.agents.state.value.agents.size).isAtLeast(20)
        assertRecovers(stalled, healthy, "429 Retry-After 3")
    }

    @Test
    fun `an account list that never answers holds neither the refresh nor its first rows, and the rows end as a healthy refresh leaves them`() = runBlocking<Unit> {
        val healthy = healthyComposition()
        // The account client's production read timeout: a silent call is given up on after thirty seconds.
        val (stalled, timings) = stalledRefresh("silent", Fault.Silence(), readTimeoutMs = 30_000L)

        assertWithMessage("first rows").that(timings.firstRowsMs).isNotNull()
        assertWithMessage("first rows (ms)").that(timings.firstRowsMs!!).isAtMost(FIRST_ROWS_BUDGET_MS)
        assertWithMessage("the refresh returned").that(timings.refreshMs).isNotNull()
        assertWithMessage("refresh (ms)").that(timings.refreshMs!!).isAtMost(REFRESH_BUDGET_MS)
        assertWithMessage("loadMore (ms)").that(timings.loadMoreMs ?: Long.MAX_VALUE).isAtMost(REFRESH_BUDGET_MS)
        assertThat(stalled.agents.state.value.agents.size).isAtLeast(20)
        assertRecovers(stalled, healthy, "silent")
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        /** A page and the prime's round-trip budget, at 900 ms round trips, with room for a loaded CI runner. */
        const val FIRST_ROWS_BUDGET_MS = 3_500L
        /** A few seconds: the healthy refresh at these round trips is about three. */
        const val REFRESH_BUDGET_MS = 8_000L
        /** The refresh after the account answers again, and the records it and the passes behind it bring. */
        const val RECOVERY_BUDGET_MS = 60_000L
    }
}
