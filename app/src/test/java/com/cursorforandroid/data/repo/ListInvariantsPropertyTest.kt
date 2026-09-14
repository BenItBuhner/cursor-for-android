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
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.ProjectNotificationPrefs
import com.cursorforandroid.domain.LiveRunning
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.LocalAgentState
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.notifications.LiveDecision
import com.cursorforandroid.notifications.liveDecision
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The sidebar's invariants under whatever order the list is built in. A seeded account — Projects, workers the
 * account created and adopted, side chats, an archived Project, a Remote Control chat on the user's machine,
 * running chats deep in the list, pins on some of each — is walked through random sequences of the operations the
 * app performs: a refresh of the newest page, the next page, the account list's word (Extended), a root's
 * membership answer, a coordinator's transcript naming chats it should and should not, a restart from the disk,
 * Extended mode turned off. After every step:
 *
 * 1. every pinned chat is on the list, whatever page it is on, whatever it runs on, however it was classified;
 * 2. no chat the account calls its own is hidden — it is a primary row, or nested under a coordinator on the
 *    transcript's word alone, never gone;
 * 3. no chat placed on positive evidence is a primary row;
 * 4. after a refresh, what the app counts as running is exactly what the server says is running, less the chats
 *    positive evidence places inside a Project;
 * 5. the notification decision never tracks a chat positive evidence places inside a Project.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ListInvariantsPropertyTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var cache: AgentListCache
    private var extended = true
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }

    /** The account as the server has it. */
    private class Truth(
        val roots: Set<String>,
        /** Children by id, with what places them: the record's own field, or a root's membership list. */
        val children: Map<String, Evidence>,
        val running: Set<String>,
        val pinned: Set<String>,
        val machine: String,
        val archivedRoot: String,
        val all: List<String>,
    )

    private enum class Evidence { RECORD, MEMBERSHIP }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true), capabilities = capabilities)
        cache = AgentListCache(JsonDiskCache(folder.newFolder("agents"), dispatcher = Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun repository() = AgentRepository(session, prefs, AttachmentStore(context), cache, scope, persistDelayMs = 1, capabilities = capabilities, runningScanPages = 40, maxMaterializedRunning = 100)

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** Seeds the server; ids carry their kind so a failure reads at a glance. */
    private fun seed(random: Random): Truth {
        api.agents.clear(); api.v0.clear(); api.runs.clear()
        val base = 1_800_000_000_000L
        var created = base
        fun next(): String = iso(created).also { created -= 3_600_000L }
        val roots = mutableSetOf<String>()
        val children = mutableMapOf<String, Evidence>()
        val running = mutableSetOf<String>()
        val all = mutableListOf<String>()
        fun add(id: String, name: String, runs: Boolean, archived: Boolean = false, env: AgentEnvDto = AgentEnvDto()) {
            val at = next()
            val runId = "run-$id"
            api.agents[id] = AgentDto(id = id, name = name, status = if (archived) "ARCHIVED" else if (runs) "ACTIVE" else "IDLE", env = env, createdAt = at, updatedAt = at, latestRunId = runId)
            api.v0[id] = V0AgentDto(id = id, name = name, status = if (runs) "RUNNING" else "FINISHED")
            api.runs[runId] = RunDto(id = runId, agentId = id, status = if (runs) "RUNNING" else "FINISHED", createdAt = at, updatedAt = at, durationMs = if (runs) null else 60_000)
            if (runs) running += id
            all += id
        }
        // Interleaved by creation so every page holds a mix: a Project, its workers, a plain chat, and so on.
        val projects = random.nextInt(2, 4)
        val machine = "bc-machine"
        val archivedRoot = "bc-root-archived"
        var plain = 0
        for (p in 1..projects) {
            val root = "bc-root-$p"
            add(root, "Project $p", runs = random.nextBoolean())
            roots += root
            repeat(random.nextInt(2, 5)) { w ->
                val id = "bc-w$p-$w"
                add(id, "Worker $p.$w", runs = random.nextBoolean())
                children[id] = if (random.nextBoolean()) Evidence.RECORD else Evidence.MEMBERSHIP
            }
            add("bc-side-$p", "Side chat $p", runs = false)
            children["bc-side-$p"] = Evidence.RECORD
            repeat(random.nextInt(1, 3)) { add("bc-plain-${plain++}", "Plain $plain", runs = random.nextInt(3) == 0) }
        }
        add(machine, "Codex-Poly-Bot Scaling", runs = true, env = AgentEnvDto(type = "machine", name = "poly-bot"))
        add(archivedRoot, "Archived Project", runs = false, archived = true)
        roots += archivedRoot
        add("bc-w-archived", "Worker of the archived", runs = false)
        children["bc-w-archived"] = Evidence.MEMBERSHIP
        repeat(random.nextInt(2, 5)) { add("bc-plain-${plain++}", "Plain $plain", runs = random.nextInt(3) == 0) }
        val pinned = setOf(machine, all.last(), children.keys.first(), "bc-plain-0")
        return Truth(roots, children, running, pinned, machine, archivedRoot, all)
    }

    /** The account list's record of every chat, Extended mode: managers and side chats on the records that carry them. */
    private fun snapshots(truth: Truth, ids: Collection<String>): List<ComposerSnapshot> = ids.map { id ->
        when {
            id in truth.roots -> ComposerSnapshot(id, isProject = true, archived = id == truth.archivedRoot, status = if (id in truth.running) RunStatus.RUNNING else RunStatus.FINISHED)
            truth.children[id] == Evidence.RECORD && id.startsWith("bc-side-") -> ComposerSnapshot(id, parent = AgentParent(rootOf(id), AgentParentKind.SIDE_CHAT), source = AgentSource.AS_SIDE_CHAT_FROM_CLOUD, status = RunStatus.FINISHED)
            truth.children[id] == Evidence.RECORD -> ComposerSnapshot(id, parent = AgentParent(rootOf(id), AgentParentKind.PROJECT_WORKER), status = if (id in truth.running) RunStatus.RUNNING else RunStatus.FINISHED)
            // An adopted worker's record, a plain chat's, the machine's: silent on lineage.
            else -> ComposerSnapshot(id, status = if (id in truth.running) RunStatus.RUNNING else RunStatus.FINISHED)
        }
    }

    private fun rootOf(id: String): String = when {
        id == "bc-w-archived" -> "bc-root-archived"
        id.startsWith("bc-w") -> "bc-root-" + id.removePrefix("bc-w").substringBefore('-')
        id.startsWith("bc-side-") -> "bc-root-" + id.removePrefix("bc-side-")
        else -> error("no root for $id")
    }

    private fun sections(agents: AgentRepository, local: LocalAgentState) =
        AgentListOrganizer.organize(agents.state.value.agents, ListPreferences(statuses = StatusFilter.entries.toSet()), local, nowMillis = 1_800_000_100_000L, zone = ZoneOffset.UTC)

    private fun List<com.cursorforandroid.domain.AgentSection>.allRows(): List<AgentRow> = flatMap { it.rows }.flatMap { listOf(it) + it.descendants() }

    private fun List<com.cursorforandroid.domain.AgentSection>.topLevel(): List<AgentRow> = flatMap { it.rows }

    @Test
    fun `pinned shown, primaries never hidden, evidence children never primary, running as the server has it, nothing project-scoped notified`() = runBlocking<Unit> {
        for (seedValue in 1L..24L) {
            val random = Random(seedValue)
            extended = true
            val truth = seed(random)
            api.pageSize = random.nextInt(3, 7)
            cache.clear()
            prefs.setPinnedIds(truth.pinned)
            var agents = repository()
            // Evidence the app has been given so far: the children placed on it (what invariant 3 holds a row to),
            // and the roots the account has called Projects (which the running count leaves out).
            val placed = mutableSetOf<String>()
            val evidenceRoots = mutableSetOf<String>()
            /** Roots whose record's flag an account window has shown. */
            val flagged = mutableSetOf<String>()
            val hinted = mutableSetOf<String>()
            val steps = mutableListOf<String>()

            fun check(where: String, afterRefresh: Boolean) {
                val local = LocalAgentState(pinnedIds = truth.pinned)
                val list = agents.state.value
                if (!list.hasLoaded || list.isFromCache) return
                val sections = sections(agents, local)
                val top = sections.topLevel()
                val everywhere = sections.allRows().map { it.agent.id }.toSet()
                val context = "seed $seedValue, $where, after ${steps.joinToString(" > ")}"
                // 1. Pinned chats are on the list, top level (a pinned child stands on its own), whatever else is true of them.
                for (id in truth.pinned) {
                    assertWithMessage("$context: pinned $id shown").that(top.map { it.agent.id }).contains(id)
                }
                // 2. A chat of the account's own that the list holds is never gone from the sidebar.
                for (row in list.agents) {
                    if (row.id in truth.children || row.id in truth.roots) continue
                    assertWithMessage("$context: primary ${row.id} visible").that(everywhere).contains(row.id)
                    // Nested only ever on a hint, and never a chat on the user's machine.
                    if (row.isProjectScoped) {
                        assertWithMessage("$context: primary ${row.id} nested only by a hint").that(row.isProjectScopedByEvidence).isFalse()
                        assertWithMessage("$context: machine chat ${row.id} never nested").that(row.envType.name).isNotEqualTo("MACHINE")
                    }
                }
                // 3. A chat placed on positive evidence is never a primary row (a pinned one stands in Pinned by the user's word).
                assertWithMessage("$context: evidence children among the primary rows").that(top.map { it.agent.id }.filter { it in placed && it !in truth.pinned }).isEmpty()
                for (id in placed) {
                    val row = list.agents.firstOrNull { it.id == id } ?: continue
                    assertWithMessage("$context: evidence child $id scoped by evidence").that(row.isProjectScopedByEvidence).isTrue()
                }
                // 4. After a refresh the running rows outside the Projects are the server's running set less the chats
                //    evidence places inside a Project; the live count is the server's running set whole.
                if (afterRefresh) {
                    val expected = truth.running.filter { it !in placed && it !in evidenceRoots }.toSet()
                    val counted = list.agents.filter { it.isRunning && !it.isProjectScopedByEvidence }.map { it.id }.toSet()
                    assertWithMessage("$context: running rows outside the Projects").that(counted).isEqualTo(expected)
                    assertWithMessage("$context: scan").that(agents.runningScan.value.ids).isEqualTo(truth.running)
                    assertWithMessage("$context: live count").that(LiveRunning.ids(list.agents, agents.runningScan.value)).isEqualTo(truth.running.toSet())
                }
                // 5. The notification decision counts every running agent, and with the switch off none evidence places
                //    inside a Project; a card for a chat inside a Project is never on by default.
                val decision = liveDecision(session.state.value, list, enabled = true, serviceActive = false, scan = agents.runningScan.value)
                if (decision is LiveDecision.Track) {
                    assertWithMessage("$context: tracked").that(decision.runningIds).containsAtLeastElementsIn(list.agents.filter { it.isRunning }.map { it.id })
                }
                val ownOnly = liveDecision(session.state.value, list, enabled = true, serviceActive = false, scan = agents.runningScan.value, projectPrefs = ProjectNotificationPrefs(countProjectAgentsInLive = false))
                if (ownOnly is LiveDecision.Track) {
                    assertWithMessage("$context: tracked with the switch off").that(ownOnly.runningIds.intersect(placed)).isEmpty()
                }
                for (id in placed + evidenceRoots) {
                    val row = list.agents.firstOrNull { it.id == id } ?: continue
                    if (row.isProjectScopedByEvidence) assertWithMessage("$context: card for $id").that(ProjectNotificationPrefs.DEFAULT.announces(row)).isFalse()
                }
            }

            suspend fun refresh() { steps += "refresh"; agents.refresh(); check("refresh", afterRefresh = true) }
            refresh()
            repeat(14) {
                when (random.nextInt(9)) {
                    0, 1 -> refresh()
                    2 -> { steps += "loadMore"; agents.loadMore(); check("loadMore", afterRefresh = false) }
                    3 -> if (extended) {
                        // The account list's window: the newest few records, as the pin sync would fold them in.
                        val window = truth.all.take(random.nextInt(4, truth.all.size + 1))
                        steps += "account(${window.size})"
                        agents.applyAccountSnapshots(snapshots(truth, window))
                        agents.reconcileRunning()
                        agents.resolvePinned()
                        placed += window.filter { truth.children[it] == Evidence.RECORD }
                        // The records' flags are root evidence; a worker's record naming its manager makes a candidate, no more.
                        flagged += window.filter { it in truth.roots }
                        evidenceRoots += window.filter { it in truth.roots }
                        check("account", afterRefresh = false)
                    }
                    4 -> if (extended) {
                        val root = truth.roots.random(random)
                        val members = truth.children.filterKeys { rootOf(it) == root }
                        steps += "membership($root)"
                        val named = members.filterValues { it == Evidence.MEMBERSHIP }.keys
                        agents.applyLineage(root, named.associateWith { AgentParentKind.PROJECT_WORKER }, LineageSignal.MEMBERSHIP, retract = setOf(AgentParentKind.PROJECT_WORKER))
                        placed += named
                        // A membership that names a worker is root evidence; one that names nobody is not — and takes
                        // the root out unless its record's flag or a worker's record still holds it.
                        if (named.isNotEmpty()) evidenceRoots += root
                        else if (root !in flagged) evidenceRoots -= root
                        check("membership", afterRefresh = false)
                    }
                    5 -> {
                        // A coordinator's transcript names its workers — and, as a coordinator may, chats that are not
                        // its workers: a plain chat it messaged and the chat on the user's machine.
                        val root = truth.roots.first()
                        val workers = truth.children.filterKeys { rootOf(it) == root }.keys
                        val strays = listOf(truth.machine, "bc-plain-0")
                        steps += "hint"
                        agents.applyLineage(root, (workers + strays).associateWith { AgentParentKind.PROJECT_WORKER }, authoritative = false)
                        hinted += strays
                        check("hint", afterRefresh = false)
                    }
                    6 -> {
                        // The disk holds the rows as last persisted; a restart starts from them (and the registry from what they carry).
                        steps += "restart"
                        fun List<com.cursorforandroid.domain.Agent>.lineage() = map { Triple(it.id, it.parent, it.isProject to it.scopeSignal) }.toSet()
                        val persisted = agents.state.value.agents.lineage()
                        withTimeout(5_000) { while (cache.read()?.value?.lineage() != persisted) delay(5) }
                        // The process is gone with its repository: nothing of the old instance writes the disk again.
                        agents.reset()
                        agents = repository()
                        agents.restoreFromCache()
                        check("restore", afterRefresh = false)
                        refresh()
                    }
                    7 -> {
                        steps += "extended:${!extended}"
                        extended = !extended
                        if (!extended) agents.forgetAccountSources(emptySet())
                        check("mode", afterRefresh = false)
                    }
                    8 -> {
                        // The user pins a chat the list may not hold: it must appear all the same.
                        val id = truth.all.random(random)
                        steps += "pin($id)"
                        prefs.setPinnedIds(truth.pinned + id)
                        agents.resolvePinned()
                        prefs.setPinnedIds(truth.pinned)
                    }
                }
            }
            // The hinted strays are chats of the account's own: once the record has spoken they are primary rows again.
            if (extended && hinted.isNotEmpty()) {
                agents.applyAccountSnapshots(snapshots(truth, truth.all))
                for (id in hinted) {
                    assertWithMessage("seed $seedValue: hinted stray $id reverted by its record").that(agents.agent(id)?.isProjectScoped ?: false).isFalse()
                }
            }
            agents.reset()
        }
    }
}
