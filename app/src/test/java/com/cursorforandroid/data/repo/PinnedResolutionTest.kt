package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.EnvironmentFilter
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.StatusFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * A pin is a promise the chat is shown. The regression on 0.3.2: the list read only its newest page, and a pinned
 * chat older than that — a Remote Control chat on the user's own machine, pinned for months — was fetched by id only
 * inside the account's pin sync, which default mode never runs and which stops at the first failure. Now every
 * pinned id the pages do not hold is fetched by id on every refresh, in either mode, stood in from its account
 * record when the public API will not give it, and listed whatever the Chats filters say.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PinnedResolutionTest {

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private var extended = false
    private var record: ComposerSnapshot? = null
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        // Twelve chats, newest first, three to a page: the pinned machine chat is the oldest of them all.
        for (i in 1..11) api.addIdleAgent("bc-$i", "Chat $i", "run-$i", createdAt = "2026-04-${(30 - i).toString().padStart(2, '0')}T10:00:00.000Z")
        val at = "2026-03-01T10:00:00.000Z"
        api.agents["bc-machine"] = AgentDto(id = "bc-machine", name = "Codex-Poly-Bot Scaling", status = "ACTIVE", env = AgentEnvDto(type = "machine", name = "poly-bot"), createdAt = at, updatedAt = at, latestRunId = "run-machine")
        api.v0["bc-machine"] = V0AgentDto(id = "bc-machine", name = "Codex-Poly-Bot Scaling", status = "RUNNING")
        api.runs["run-machine"] = RunDto(id = "run-machine", agentId = "bc-machine", status = "RUNNING", createdAt = at, updatedAt = at)
        api.pageSize = 3
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, capabilities = capabilities, recordOf = { record }, runningScanPages = 1)

    private fun sections(agents: AgentRepository, prefs: ListPreferences = ListPreferences(), pinned: Set<String>) =
        AgentListOrganizer.organize(agents.state.value.agents, prefs, LocalAgentState(pinnedIds = pinned), nowMillis = 1_800_000_000_000L, zone = ZoneOffset.UTC)

    @Test
    fun `a pinned chat beyond the loaded pages is fetched by id on the refresh, in default mode, and listed whatever the filters say`() = runBlocking<Unit> {
        prefs.setPinnedIds(setOf("bc-machine"))
        val agents = agents()
        agents.refresh()

        // One page of three was read; the pinned chat, eleven rows deeper, is on the list all the same.
        assertThat(agents.state.value.agents.map { it.id }).containsExactly("bc-1", "bc-2", "bc-3", "bc-machine")
        val row = agents.agent("bc-machine")!!
        assertThat(row.envType).isEqualTo(EnvType.MACHINE)
        assertThat(row.isRunning).isTrue()
        assertThat(agents.unresolvedPinned()).isEmpty()
        val pinnedRows = sections(agents, pinned = setOf("bc-machine")).first { it.key == AgentListOrganizer.PINNED_KEY }.rows.map { it.agent.id }
        assertThat(pinnedRows).containsExactly("bc-machine")
        // The Chats filters do not answer for a pinned chat: cloud-only, finished-only, another repository — it stays.
        val narrowed = ListPreferences(statuses = setOf(StatusFilter.Read), environments = setOf(EnvironmentFilter.Cloud), repos = setOf("acme/other"))
        val narrowedSections = sections(agents, narrowed, pinned = setOf("bc-machine"))
        assertThat(narrowedSections.first { it.key == AgentListOrganizer.PINNED_KEY }.rows.map { it.agent.id }).containsExactly("bc-machine")
        assertThat(narrowedSections.flatMap { it.rows }.map { it.agent.id }).containsExactly("bc-machine")
        // The next refresh does not drop it for being outside the window it re-read.
        agents.refresh()
        assertThat(agents.agent("bc-machine")).isNotNull()
    }

    @Test
    fun `pinning a chat the pages do not hold fetches it without a refresh`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        assertThat(agents.agent("bc-9")).isNull()
        prefs.setPinnedIds(setOf("bc-9"))
        withTimeout(5_000) { while (agents.agent("bc-9") == null) delay(10) }
        assertThat(agents.agent("bc-9")?.name).isEqualTo("Chat 9")
    }

    @Test
    fun `a pinned chat the public API will not give is stood in from its account record, or named as unresolved`() = runBlocking<Unit> {
        // The account knows a chat the public API does not (it answers 404 for it).
        prefs.setPinnedIds(setOf("bc-private"))
        val agents = agents()
        agents.refresh()
        assertThat(agents.agent("bc-private")).isNull()
        assertThat(agents.unresolvedPinned()).containsExactly("bc-private")

        // Extended mode: the account's record stands in for the row until the public API knows the chat.
        extended = true
        record = ComposerSnapshot("bc-private", name = "Private machine chat", archived = false)
        agents.resolvePinned()
        // Still within the retry interval: the unresolved pin is not asked for again yet.
        assertThat(agents.agent("bc-private")).isNull()
        com.cursorforandroid.util.AppClock.nowMillis = { System.currentTimeMillis() + AgentRepository.PINNED_GONE_RETRY_MS + 1 }
        try {
            agents.resolvePinned()
        } finally {
            com.cursorforandroid.util.AppClock.nowMillis = System::currentTimeMillis
        }
        val standIn = agents.agent("bc-private")!!
        assertThat(standIn.name).isEqualTo("Private machine chat")
        assertThat(standIn.envType).isEqualTo(EnvType.UNKNOWN)
        assertThat(agents.unresolvedPinned()).isEmpty()
        assertThat(sections(agents, pinned = setOf("bc-private")).first { it.key == AgentListOrganizer.PINNED_KEY }.rows.map { it.agent.id }).containsExactly("bc-private")
    }

    @Test
    fun `a pinned chat a coordinator's create_agent named stays under the coordinator, and its own record puts it back among the account's chats`() = runBlocking<Unit> {
        extended = true
        prefs.setPinnedIds(setOf("bc-machine", "bc-2"))
        val agents = agents()
        agents.refresh()
        // Default mode's one word: the coordinator's create_agent named both. A pinned child is a child (VuC pins
        // top-level headers only), so neither is a Pinned row while the stamp stands.
        agents.applyLineage("bc-1", mapOf("bc-machine" to AgentParentKind.PROJECT_WORKER, "bc-2" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-machine")?.isProjectScoped).isTrue()
        assertThat(agents.agent("bc-2")?.isProjectScoped).isTrue()
        assertThat(sections(agents, pinned = setOf("bc-machine", "bc-2")).none { it.key == AgentListOrganizer.PINNED_KEY }).isTrue()
        // The account's records name no manager for either: the record is the word, and the transcript's stamp is
        // not made again over a record that has been read — both are pinned rows of the account's own.
        agents.applyAccountSnapshots(listOf(ComposerSnapshot("bc-2"), ComposerSnapshot("bc-machine")))
        assertThat(agents.agent("bc-2")?.isProjectScoped).isFalse()
        assertThat(agents.agent("bc-machine")?.isProjectScoped).isFalse()
        agents.applyLineage("bc-1", mapOf("bc-2" to AgentParentKind.PROJECT_WORKER), authoritative = false)
        assertThat(agents.agent("bc-2")?.isProjectScoped).isFalse()
        val pinnedRows = sections(agents, pinned = setOf("bc-machine", "bc-2")).first { it.key == AgentListOrganizer.PINNED_KEY }.rows.map { it.agent.id }
        assertThat(pinnedRows).containsExactly("bc-2", "bc-machine")
        // The account's own word still places it: the record naming the manager, or the root's membership.
        agents.applyLineage("bc-1", mapOf("bc-2" to AgentParentKind.PROJECT_WORKER), LineageSignal.MEMBERSHIP)
        assertThat(agents.agent("bc-2")?.parent).isEqualTo(AgentParent("bc-1", AgentParentKind.PROJECT_WORKER))
        assertThat(agents.agent("bc-2")?.isProjectScopedByEvidence).isTrue()
    }
}
