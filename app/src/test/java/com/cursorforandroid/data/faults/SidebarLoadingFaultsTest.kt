package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.data.repo.RefreshOutcome
import com.cursorforandroid.data.repo.RootScanRecord
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.PendingWork
import com.cursorforandroid.ui.agents.SidebarTail
import com.cursorforandroid.ui.agents.sidebarTail
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The sidebar's tail — the one row past the last chat — under a phone's weather, over the app's real stack against
 * [FaultServer] with the account layer wired as the app wires it: 300–900 ms round trips, and bursts of `429` on the
 * list routes (the public list, the status scan, the account's list the round and the discovery scan read). The
 * frame this is about (Bennett's, 2026-09-21 13:13): under "Older · 108", "Loading more…" and "Still syncing older
 * items…" at once, the second of them for minutes — the first drawn from the list's paging, the second from the
 * account round, the memberships and the discovery scan, work that runs on every poll and, refused with 429 after
 * 429, for as long as the refusals last. The rule pinned here (see `SidebarTail`):
 *
 *  - at most one loading row, read by the one rule the sidebar draws it by ([sidebarTail]), and never while the
 *    refresh indicator is up;
 *  - a spinner is work in flight: every "Loading more…" frame has an item registered for it ([PendingWork]), and
 *    the account's background passes — the scan, the memberships — are not among them;
 *  - every loading state ends within the bounds of the work behind it: in rows, in nothing, or in the server's
 *    words with Retry; a page that failed is not asked for again behind a spinner;
 *  - a folded group's count is the rows loaded into it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SidebarLoadingFaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private lateinit var root: File

    @Before
    fun setUp() {
        server = FaultServer().start()
        root = folder.newFolder("rig")
        rig = FaultRig(server.baseUrl, root, extended = true)
        buildAccount()
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    /**
     * The account: two Projects with their workers and a side chat, and sixty chats of the account's own over a
     * month, the public list paged in twenties so the sidebar has pages to ask for; the account's list paged in
     * thirties so the discovery scan has pages to read.
     */
    private fun buildAccount() {
        val now = 1_800_000_000_000L
        fun created(ms: Long) = Instant.ofEpochMilli(ms).toString()
        listOf("bc-proj-1" to 5, "bc-proj-2" to 3).forEachIndexed { p, (id, members) ->
            val at = now - (p + 1) * 3_600_000L
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
        server.addIdleAgent("bc-proj-2-side", "Side chat", "run-side", createdAt = created(now - 2 * 3_600_000L - 10_000L))
        server.composers["bc-proj-2-side"] = Composer("bc-proj-2-side", "Side chat", now - 2 * 3_600_000L - 10_000L, sideChatOf = "bc-proj-2")
        repeat(60) { i ->
            val id = "bc-own-${i + 1}"
            val at = now - i * 12 * 3_600_000L - 30_000L
            server.addIdleAgent(id, "Own chat ${i + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Own chat ${i + 1}", at, running = i == 0)
        }
        server.pageSize = 20
        server.accountPageSize = 30
        rig.now = now + 60_000L
    }

    private val list: AgentListState get() = rig.agents.state.value
    private fun tail(): SidebarTail = sidebarTail(list, rig.pending.state.value)

    /** One frame of the tail as the sidebar would draw it, with what stood behind it. */
    private data class Frame(val atMillis: Long, val tail: SidebarTail, val isRefreshing: Boolean, val work: List<String>, val isLoadingMore: Boolean)

    /** Every frame of the tail from now on, in order: what a reader would have seen at the end of the list. */
    private inner class TailRecording {
        val frames = CopyOnWriteArrayList<Frame>()
        private val job: Job = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(rig.agents.state, rig.pending.state) { list, work -> Frame(server.nowMillis(), sidebarTail(list, work), list.isRefreshing, work.items.map { it.name }, list.isLoadingMore) }
                .collect { frames += it }
        }

        fun stop() = job.cancel()

        /** The tail's distinct successive states, by kind. */
        fun kinds(): List<String> {
            val out = ArrayList<String>()
            frames.forEach { f -> val k = kind(f.tail); if (out.lastOrNull() != k) out += k }
            return out
        }

        /** The longest stretch of frames the tail read "Loading more…", in millis. */
        fun longestLoadingMs(): Long {
            var longest = 0L
            var since = -1L
            frames.forEach { f ->
                if (f.tail is SidebarTail.Loading) { if (since < 0) since = f.atMillis } else if (since >= 0) { longest = maxOf(longest, f.atMillis - since); since = -1 }
            }
            if (since >= 0) longest = maxOf(longest, (frames.lastOrNull()?.atMillis ?: since) - since)
            return longest
        }

        /**
         * The rule, frame by frame: never a spinner with the refresh indicator up; every spinner with work registered
         * for it (or the page just asked for, whose mark and item are published together) and none of that work the
         * account's background passes; a spinner that outlives its work by no more than a beat.
         */
        fun assertRule() {
            frames.forEach { f ->
                assertWithMessage("a loading row under the refresh indicator: $f").that(f.tail is SidebarTail.Loading && f.isRefreshing).isFalse()
                if (f.tail is SidebarTail.Loading) {
                    assertWithMessage("a spinner without work behind it: $f").that(f.work.isNotEmpty() || f.isLoadingMore).isTrue()
                    f.work.forEach { name -> assertWithMessage("the tail spins for the account's background pass: $f").that(name).doesNotContainMatch("(?i)discovery|membership|lineage|worker|children") }
                }
            }
            // A "Loading more…" frame whose work list is empty (the ask marked, its item not yet seen) is followed
            // within a beat by one with the item, or by the row's end.
            frames.zipWithNext().forEach { (a, b) ->
                if (a.tail is SidebarTail.Loading && a.work.isEmpty()) assertWithMessage("an empty spinner that stayed: $a then $b").that(b.atMillis - a.atMillis).isAtMost(500L)
            }
        }
    }

    /** What a reader saw, for the record beside the run this replaces (see the PR): the span, the spinner's share of it, its longest stretch. */
    private fun report(label: String, recording: TailRecording) {
        val frames = recording.frames
        if (frames.size < 2) return
        var loading = 0L
        frames.zipWithNext().forEach { (a, b) -> if (a.tail is SidebarTail.Loading) loading += b.atMillis - a.atMillis }
        println("$label frames=${frames.size} span=${frames.last().atMillis - frames.first().atMillis}ms loadingRow=${loading}ms longestLoading=${recording.longestLoadingMs()}ms states=${recording.kinds()} work=${frames.flatMap { it.work }.distinct()}")
    }

    private fun kind(tail: SidebarTail): String = when (tail) {
        SidebarTail.None -> "none"
        is SidebarTail.Loading -> "loading"
        SidebarTail.More -> "more"
        is SidebarTail.Failed -> "failed"
    }

    private fun sections() = AgentListOrganizer.organize(list.agents, ListPreferences(), LocalAgentState(), nowMillis = rig.now, zone = ZoneOffset.UTC, knownRoots = rig.agents.knownRoots.value)

    /** The account's own chats loaded, by the organizer's date groups: every folded count is the rows in the group. */
    private fun assertCountsAgree() {
        val loaded = list.agents.filter { it.parent == null && !it.isProjectRoot }.map { it.id }.toSet()
        val grouped = sections().filter { it.key.startsWith("date:") }.flatMap { s -> s.rows.map { it.agent.id } }.toSet()
        assertWithMessage("the date groups' counts are the loaded chats").that(grouped).isEqualTo(loaded)
    }

    private suspend fun quiet(windowMs: Long = 2_500L) {
        rig.awaitUntil(120_000) { rig.pending.items.isEmpty() && !list.isRefreshing && !list.isLoadingMore && !rig.pins.state.value.isSyncing && !rig.projects.syncingLineage.value && rig.projects.lastRootScan.value?.status != RootScanRecord.Status.Running }
        // Held for a while: nothing starts again on its own.
        rig.watch(windowMs) { assertThat(rig.pending.state.value.shown).isFalse() }
    }

    /**
     * Bennett's frame, at his conditions: a pull with the list's window read, then the reader at the end of the
     * list asking for pages, the account round, the discovery scan and the memberships all going — with the list
     * routes refusing in bursts. One row at a time, each ending; the scan and the memberships never in it.
     */
    @Test
    fun `under 429 bursts on the list routes the tail is one row at a time and every loading state ends`() = runBlocking<Unit> {
        // The first fetch: the window's one page and the status scan, the account's list ahead of it.
        rig.agents.refresh()
        assertThat(list.agents.size).isAtLeast(20)
        val recording = TailRecording()

        // The weather: a burst of refusals on the public list routes, each naming a second's wait, then answers;
        // the account service refusing every call for twenty seconds — a rate limit that lasts, as Bennett's did.
        val refusal = Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1")
        server.script(Route.ListAgents, refusal, refusal)
        server.script(Route.ListAgentsV0, refusal)
        server.outage(Route.AccountList, refusal)
        server.outage(Route.Workers, refusal)
        val lifting = rig.scope.launch { delay(20_000); server.clear(Route.AccountList); server.clear(Route.Workers) }

        // The pull: the indicator, then the tail's row for the rest of the work.
        rig.agents.refresh(depth = RefreshDepth.Full)
        // The reader at the end of the list, page after page, as the sidebar asks: each ask marks the tail at once.
        while (list.hasMore) {
            val outcome = rig.agents.loadMore()
            if (outcome == RefreshOutcome.Failed) break
            assertCountsAgree()
        }
        quiet()
        lifting.join()
        recording.stop()
        report("AFTER", recording)

        recording.assertRule()
        assertWithMessage("the tail's states: ${recording.kinds()}").that(recording.kinds()).contains("loading")
        // Every loading stretch ended, within the bounds of a burst's waits and the round trips behind it.
        assertWithMessage("the longest 'Loading more…': ${recording.longestLoadingMs()} ms; states ${recording.kinds()}").that(recording.longestLoadingMs()).isAtMost(45_000L)
        // The end of the list: nothing more, nothing spinning — or the server's words with Retry, never a spinner.
        val end = tail()
        assertWithMessage("the tail at the end: $end").that(end is SidebarTail.None || end is SidebarTail.Failed).isTrue()
        if (end is SidebarTail.None) assertThat(list.agents.map { it.id }).containsAtLeastElementsIn(server.agents.keys)
        assertCountsAgree()
        // The Projects' members are theirs, whatever the weather did to the memberships' reads.
        assertThat(list.agents.filter { it.id.startsWith("bc-proj-1-w") }.all { it.parent?.id == "bc-proj-1" }).isTrue()
    }

    /**
     * A page the server keeps refusing ends in its words and Retry: the tail does not spin for it again on its own —
     * the sidebar asks for a failed page only by the tap — and Retry, once the refusals stop, brings the page.
     */
    @Test
    fun `a page that fails ends in the server's words with Retry, and is not asked for again until the tap`() = runBlocking<Unit> {
        rig.agents.refresh()
        assertThat(list.hasMore).isTrue()
        val recording = TailRecording()
        server.outage(Route.ListAgents, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1"))
        val before = server.requests(Route.ListAgents).size

        val outcome = rig.agents.loadMore()
        assertThat(outcome).isEqualTo(RefreshOutcome.Failed)
        val failed = tail()
        assertThat(failed).isInstanceOf(SidebarTail.Failed::class.java)
        assertThat((failed as SidebarTail.Failed).message).isEqualTo("Rate limited by Cursor: Too many requests from this key. Try again in 1 s.")
        assertThat(list.isLoadingMore).isFalse()
        assertThat(list.hasMore).isTrue()
        // The retry interceptor's attempts, and then nothing: no page asked for behind the failed one.
        val attempts = server.requests(Route.ListAgents).size - before
        assertThat(attempts).isAtMost(3)
        rig.watch(3_000) {
            assertThat(server.requests(Route.ListAgents).size - before).isEqualTo(attempts)
            assertThat(tail()).isInstanceOf(SidebarTail.Failed::class.java)
        }

        // The refusals stop; the tap asks again, and the page lands.
        server.clear(Route.ListAgents)
        assertThat(rig.agents.loadMore()).isEqualTo(RefreshOutcome.Refreshed)
        assertThat(list.loadMoreError).isNull()
        assertThat(list.agents.size).isAtLeast(40)
        quiet()
        recording.stop()
        recording.assertRule()
        assertThat(recording.kinds()).containsAtLeast("loading", "failed", "loading").inOrder()
    }

    /**
     * A cold start on the disk a previous session left, and a reopen: the disk copy shows before any request goes
     * out, the silent refresh runs without a row (nothing the user asked for), and a pull then shows the one row
     * until its work is over — under a burst of refusals on the account's list.
     */
    @Test
    fun `a cold start shows the disk copy first, a silent refresh shows no row, and a pull's row ends`() = runBlocking<Unit> {
        // A previous session: the list and the registry reach the disk.
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        quiet()
        rig.close()

        // The next start, on the same disk (the same folder, a new stack).
        server.seen.clear()
        rig = FaultRig(server.baseUrl, root, extended = true)
        rig.now = 1_800_000_060_000L
        val recording = TailRecording()
        rig.agents.restoreFromCache()
        assertThat(list.hasLoaded).isTrue()
        assertThat(list.agents).isNotEmpty()
        assertWithMessage("no request before the disk copy").that(server.seen).isEmpty()
        assertThat(tail()).isEqualTo(SidebarTail.None)

        // The silent refresh (a disk copy was on screen): work in flight, nothing shown for it.
        server.script(Route.AccountList, Fault.Status(429, "rate_limited", "Too many requests.", retryAfter = "1"), Fault.Status(429, "rate_limited", "Too many requests.", retryAfter = "1"))
        rig.agents.refresh(silent = true)
        rig.watch(1_000) { assertThat(rig.pending.state.value.shown).isFalse() }
        quiet()
        assertThat(recording.frames.none { it.tail is SidebarTail.Loading }).isTrue()

        // A pull: the indicator, then the row for the rest, then nothing.
        rig.agents.refresh()
        quiet()
        recording.stop()
        recording.assertRule()
        assertThat(recording.kinds()).contains("loading")
        assertThat(tail()).isAnyOf(SidebarTail.None, SidebarTail.More)
    }

    /**
     * The discovery scan interrupted by a burst of 429s and resumed: a pass a page of which was refused is a partial
     * pass, tried again — never a Running that outlives the request behind it — and the pass that gets through
     * leaves the registry complete. Throughout, the scan is nobody's spinner in the sidebar.
     */
    @Test
    fun `a discovery scan interrupted by 429s is a partial pass tried again, never a Running left behind, and never the tail's`() = runBlocking<Unit> {
        val recording = TailRecording()
        val statuses = CopyOnWriteArrayList<Pair<Long, RootScanRecord.Status?>>()
        val watcher = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.projects.lastRootScan.collect { statuses += server.nowMillis() to it?.status } }
        // The account's list read ahead of the fetch goes through (the scan is cued by it); the scan's first page
        // goes through; its second is refused twice — the throttle's one retry included — so the pass stops there.
        val refusal = Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1")
        server.script(Route.AccountList, Fault.Pass, Fault.Pass, refusal, refusal)
        rig.agents.refresh()
        // The scan ran into the refusals: a partial pass of one page, with a retry scheduled — and not Running. The
        // record is read in the one reading that found it partial: the retry is a second away, and a record read
        // again after the wait may be the retry's.
        var partial: RootScanRecord? = null
        rig.awaitUntil(60_000) { rig.projects.lastRootScan.value?.takeIf { it.status == RootScanRecord.Status.Partial && it.pagesRead == 1 }?.also { partial = it } != null }
        assertThat(partial!!.notice).contains("page 2")
        assertThat(rig.agents.registryCompleteAtMillis).isNull()

        // The retry (the refusals are spent) reads the whole list and the registry is complete. The watcher is let go
        // once it has seen the end itself: it collects on the rig's threads, and the pass's last word reaching the
        // flow is not its reaching the watcher (#289's CI cancelled it in between and read no Done).
        rig.awaitUntil(60_000) { statuses.lastOrNull()?.second == RootScanRecord.Status.Done }
        watcher.cancel()
        // Running was never held longer than a pass's pages take: every Running stretch ended within the bound.
        statuses.zipWithNext().forEach { (a, b) -> if (a.second == RootScanRecord.Status.Running) assertWithMessage("Running for ${b.first - a.first} ms").that(b.first - a.first).isAtMost(30_000L) }
        assertThat(statuses.map { it.second }).containsAtLeast(RootScanRecord.Status.Running, RootScanRecord.Status.Partial, RootScanRecord.Status.Running, RootScanRecord.Status.Done).inOrder()
        assertWithMessage("the pass that got through: ${rig.projects.lastRootScan.value}; statuses $statuses").that(rig.projects.lastRootScan.value?.complete).isTrue()
        assertThat(rig.agents.registryCompleteAtMillis).isNotNull()
        assertThat(rig.agents.knownRoots.value.map { it.id }).containsExactly("bc-proj-1", "bc-proj-2")
        quiet()
        recording.stop()
        recording.assertRule()
        // The scan's pages were never the tail's work.
        assertWithMessage("the tail's work: ${recording.frames.flatMap { it.work }.distinct()}").that(recording.frames.flatMap { it.work }.none { it.contains("discovery", ignoreCase = true) || it.contains("membership", ignoreCase = true) }).isTrue()
        // And no flag of the scan's is left set: the status is terminal, the memberships are over, every Project's view is quiet.
        assertThat(rig.projects.lastRootScan.value?.status).isEqualTo(RootScanRecord.Status.Done)
        assertThat(rig.projects.syncingLineage.value).isFalse()
        assertThat(rig.projects.view("bc-proj-1").first().isSyncing).isFalse()
    }
}
