package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.notifications.LiveDecision
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The running set is not a function of how far the sidebar has scrolled. On 0.3.2 the list read its newest page and
 * the live tracking read the list, so an old chat with a fresh turn — or any running chat past the first hundred —
 * was neither counted nor followed until the reader scrolled to it. Now every refresh reads the legacy list's
 * statuses a few pages deep, the account list's statuses in Extended mode, and fetches by id whatever runs that no
 * page holds; only positive evidence of a Project takes a running chat off the count.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunningScanTest {

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private var extended = false
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        // Twelve chats, three to a page; the running ones sit on the first, third and fourth pages.
        for (i in 1..12) {
            val at = "2026-04-${(30 - i).toString().padStart(2, '0')}T10:00:00.000Z"
            if (i == 2 || i == 7 || i == 11) api.addRunningAgent("bc-$i", "Chat $i", "run-$i", createdAt = at) else api.addIdleAgent("bc-$i", "Chat $i", "run-$i", createdAt = at)
        }
        api.pageSize = 3
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents(scanPages: Int = 5) = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, runningScanPages = scanPages)

    /** What the notification decision would track: the running rows less those positive evidence places inside a Project. */
    private fun decision(agents: AgentRepository) = LiveDecision.Track(agents.state.value.agents.filter { it.isRunning && !it.isProjectScopedByEvidence }.mapTo(HashSet()) { it.id }, serviceActive = false)

    @Test
    fun `a refresh finds what runs beyond the loaded pages and brings it to the list`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        // One page loaded; the scan read five and named all three running chats; the two beyond the page were fetched by id.
        assertThat(agents.runningScan.value.ids).containsExactly("bc-2", "bc-7", "bc-11")
        assertThat(agents.runningScan.value.pagesRead).isEqualTo(4)
        assertThat(agents.runningScan.value.complete).isTrue()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-1", "bc-2", "bc-3", "bc-7", "bc-11")
        assertThat(agents.state.value.agents.filter { it.isRunning }.map { it.id }).containsExactly("bc-2", "bc-7", "bc-11")
        assertThat(decision(agents)).isEqualTo(LiveDecision.Track(setOf("bc-2", "bc-7", "bc-11"), serviceActive = false))
        assertThat(agents.state.value.hasMore).isTrue()
        // The next refresh keeps them: they are older than the window it re-reads.
        agents.refresh()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-1", "bc-2", "bc-3", "bc-7", "bc-11")
    }

    @Test
    fun `a scan that stops short says so, and the next page carries on`() = runBlocking<Unit> {
        val agents = agents(scanPages = 2)
        agents.refresh()
        assertThat(agents.runningScan.value.ids).containsExactly("bc-2")
        assertThat(agents.runningScan.value.pagesRead).isEqualTo(2)
        assertThat(agents.runningScan.value.complete).isFalse()
        // Scrolling brings the third page, and the row on it says it runs.
        agents.loadMore()
        agents.loadMore()
        assertThat(agents.agent("bc-7")?.isRunning).isTrue()
    }

    @Test
    fun `in Extended mode the account's statuses name the running set too, and only positive evidence of a Project takes a chat off it`() = runBlocking<Unit> {
        extended = true
        val agents = agents(scanPages = 1)
        agents.refresh()
        assertThat(agents.runningScan.value.ids).containsExactly("bc-2")
        // The account list's window: bc-7 runs as a Project's worker (its record names the manager), bc-11 runs as a chat of its own.
        agents.applyAccountSnapshots(
            listOf(
                ComposerSnapshot("bc-1", isProject = true, status = RunStatus.FINISHED),
                ComposerSnapshot("bc-7", parent = AgentParent("bc-1", AgentParentKind.PROJECT_WORKER), status = RunStatus.RUNNING),
                ComposerSnapshot("bc-11", status = RunStatus.RUNNING),
                ComposerSnapshot("bc-12", status = RunStatus.FINISHED),
            ),
        )
        assertThat(agents.runningScan.value.accountIds).containsExactly("bc-7", "bc-11")
        agents.reconcileRunning()
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-1", "bc-2", "bc-3", "bc-7", "bc-11")
        assertThat(agents.agent("bc-7")?.isProjectScopedByEvidence).isTrue()
        assertThat(decision(agents)).isEqualTo(LiveDecision.Track(setOf("bc-2", "bc-11"), serviceActive = false))

        // A coordinator's create_agent naming bc-11 is default mode's word; its record has been read and names no
        // parent, so the record stands: bc-11 stays a chat of its own, on the count and the notifications.
        agents.applyLineage("bc-1", mapOf("bc-11" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-11")?.isProjectScoped).isFalse()
        assertThat(decision(agents)).isEqualTo(LiveDecision.Track(setOf("bc-2", "bc-11"), serviceActive = false))
        // The root's membership naming it places it (the desktop's seeded managerAgentId).
        agents.applyLineage("bc-1", mapOf("bc-11" to AgentParentKind.PROJECT_WORKER), authoritative = true)
        assertThat(agents.agent("bc-11")?.isProjectScopedByEvidence).isTrue()
        assertThat(decision(agents)).isEqualTo(LiveDecision.Track(setOf("bc-2"), serviceActive = false))
    }
}
