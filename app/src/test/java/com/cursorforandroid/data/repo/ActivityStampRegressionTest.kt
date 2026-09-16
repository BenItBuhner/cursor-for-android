package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentsWindowList
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SortOrder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/**
 * Bennett, v0.3.22: weeks-old chats listed under Today while the desktop, web and iOS bucket them as old. The
 * desktop dates a chat by the record's `lastMessageActivityAtMs ?? updatedAtMs` (Cursor 3.20.21, `$pS`; the header's
 * `lastUpdatedAt`; `f3v` buckets by it, `rUm` orders by it). The app dated a chat by the public API's `updatedAt`,
 * raised further by the latest run's `updatedAt` — both of which the account moves whenever it touches the row (a
 * status sweep, a pull-request check, a VM expiring), so a chat nobody had spoken to in a month read as fresh at every
 * refresh. Here a month-old record is touched by every refresh path the app has — the pages, the legacy status list,
 * the run verification, the account round, the record read by id, the pin resolution, a run record patched in, the
 * read marker and the pin written — and stays in Older, ordered by the same time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ActivityStampRegressionTest {

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(true) }
    private val records = HashMap<String, ComposerSnapshot>()

    private val now = System.currentTimeMillis()
    private val day = 24 * 3_600_000L
    private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()

    /** A month-old chat whose row the account keeps touching: created 40 days ago, last spoken to then, the row stamped today. */
    private val oldActivity = now - 40 * day
    /** A chat spoken to two days ago whose row stamp is older than that (the account had no reason to touch it since). */
    private val midActivity = now - 2 * day

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        api.agents["bc-old"] = AgentDto(id = "bc-old", name = "Luxury app interface", status = "IDLE", createdAt = iso(oldActivity), updatedAt = iso(now), latestRunId = "run-old")
        api.v0["bc-old"] = V0AgentDto(id = "bc-old", name = "Luxury app interface", status = "FINISHED")
        api.runs["run-old"] = RunDto(id = "run-old", agentId = "bc-old", status = "FINISHED", createdAt = iso(oldActivity), updatedAt = iso(now), durationMs = 65_000, result = "Done.")
        api.agents["bc-mid"] = AgentDto(id = "bc-mid", name = "Button snap placement", status = "IDLE", createdAt = iso(now - 12 * day), updatedAt = iso(now - 10 * day), latestRunId = "run-mid")
        api.v0["bc-mid"] = V0AgentDto(id = "bc-mid", name = "Button snap placement", status = "FINISHED")
        api.runs["run-mid"] = RunDto(id = "run-mid", agentId = "bc-mid", status = "FINISHED", createdAt = iso(now - 12 * day), updatedAt = iso(now - 10 * day), durationMs = 65_000, result = "Done.")
        records["bc-old"] = record("bc-old", activity = oldActivity, rowStamp = now)
        records["bc-mid"] = record("bc-mid", activity = midActivity, rowStamp = now - 10 * day)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun record(id: String, activity: Long, rowStamp: Long, status: RunStatus? = RunStatus.FINISHED) =
        ComposerSnapshot(id, name = api.agents[id]?.name, archived = false, status = status, activityAtMillis = activity, createdAtMillis = api.agents[id]?.createdAt?.let { Instant.parse(it).toEpochMilli() })

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, recordOf = { records[it] }, runningScanPages = 1)

    private fun sections(agents: AgentRepository, local: LocalAgentState = LocalAgentState()) =
        AgentListOrganizer.organize(agents.state.value.agents, ListPreferences(), local, nowMillis = now, zone = ZoneOffset.UTC)

    private fun bucketOf(agents: AgentRepository, id: String) = sections(agents).first { s -> s.rows.any { it.agent.id == id } }.title

    @Test
    fun `a month-old record touched by every refresh path stays in Older, and is ordered by the same time`() = runBlocking<Unit> {
        val agents = agents()
        // The pages, the legacy status list and the run verification: the public row and the run both say "today".
        agents.refresh()
        // The rows land with their records (Extended mode fetches them by id); wait for the record read.
        agents.state.first { s -> s.agents.all { it.record != null } }
        assertThat(agents.agent("bc-old")!!.activityAtMillis).isEqualTo(oldActivity)
        assertThat(agents.agent("bc-old")!!.listedAtMillis).isEqualTo(oldActivity)
        assertThat(bucketOf(agents, "bc-old")).isEqualTo("Older")

        // The account round, twice, with the row stamped again by the account each time.
        agents.applyAccountSnapshots(listOf(record("bc-old", oldActivity, now), record("bc-mid", midActivity, now - 10 * day)))
        agents.applyAccountSnapshots(listOf(record("bc-old", oldActivity, now + 60_000)))
        // The record read by id, which merges the public record and its latest run (both "today") into the row.
        assertThat(agents.loadDetail("bc-old").isSuccess).isTrue()
        // A run record patched in by a monitor or watchdog.
        agents.patch("bc-old") { it.withLatestRun(api.runs.getValue("run-old")) }
        // The status scan's account half: the list names it finished, again.
        agents.applyAccountSnapshots(listOf(record("bc-old", oldActivity, now, status = RunStatus.FINISHED)))
        // The user's own writes: the read marker and the pin, the pin resolving the row by id once more.
        prefs.markRead("bc-old", agents.agent("bc-old")!!.listedAtMillis)
        prefs.setPinnedIds(setOf("bc-old"))
        agents.resolvePinned()
        // And the next refresh, the public row still stamped today.
        agents.refresh()
        agents.state.first { s -> s.agents.all { it.record != null } }

        val old = agents.agent("bc-old")!!
        assertThat(old.updatedAtMillis).isAtLeast(now - 60_000)
        assertThat(old.activityAtMillis).isEqualTo(oldActivity)
        assertThat(old.listedAtMillis).isEqualTo(oldActivity)
        assertThat(AgentsWindowList.timeBucket(old.listedAtMillis, now, ZoneOffset.UTC)).isEqualTo(AgentsWindowList.TimeBucket.OLDER)
        // Pinned, it sits in Pinned; unpinned, in Older — never Today.
        assertThat(sections(agents, LocalAgentState(pinnedIds = setOf("bc-old"))).first { it.key == AgentListOrganizer.PINNED_KEY }.rows.map { it.agent.id }).containsExactly("bc-old")
        assertThat(bucketOf(agents, "bc-old")).isEqualTo("Older")
        // The chat spoken to two days ago, whose row stamp is ten days old, is Last 7 Days and ordered above.
        assertThat(bucketOf(agents, "bc-mid")).isEqualTo("Last 7 Days")
        val order = AgentListOrganizer.sort(agents.state.value.agents.map { AgentListOrganizer.toRow(it, LocalAgentState(), now) }, SortOrder.Updated).map { it.agent.id }
        assertThat(order).containsExactly("bc-mid", "bc-old").inOrder()
        // Read at its activity, and the account's touching of the row does not unread it.
        assertThat(AgentListOrganizer.isUnread(old, LocalAgentState(readMarkers = mapOf("bc-old" to oldActivity)), now)).isFalse()
    }

    @Test
    fun `a message from this device dates the chat now until the record says, and a record read never moves it back to the row stamp`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        agents.state.first { s -> s.agents.all { it.record != null } }
        assertThat(bucketOf(agents, "bc-old")).isEqualTo("Older")
        // A reply sent from here: the chat is active now, whatever the record last said.
        agents.patch("bc-old") { it.touched(now) }
        assertThat(agents.agent("bc-old")!!.listedAtMillis).isEqualTo(now)
        assertThat(bucketOf(agents, "bc-old")).isEqualTo("Today")
        // The account's next record carries the message's activity; a later one that dates nothing changes nothing.
        agents.applyAccountSnapshots(listOf(record("bc-old", activity = now + 5_000, rowStamp = now + 5_000)))
        assertThat(agents.agent("bc-old")!!.listedAtMillis).isEqualTo(now + 5_000)
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-old", name = "Luxury app interface", archived = false)))
        assertThat(agents.agent("bc-old")!!.listedAtMillis).isEqualTo(now + 5_000)
    }
}
