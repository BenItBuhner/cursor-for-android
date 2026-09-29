package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random

/**
 * A publication of a few rows classifies those rows alone when nothing else the classification reads has moved
 * (see `AgentRepository.publishRows`). Held here to the whole-list pass it stands in for: the same operations —
 * patches, new rows, account rounds, memberships, stamps cleared, runs seen ending, records read ahead of their rows
 * — on a repository that classifies the whole list on every publication, and the lists compared after every one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentListRowPublishTest {

    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: SessionManager
    private lateinit var prefs: PreferencesStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val api = FakeCursorApi()
        session = SessionManager(SecureKeyStore(context), prefs, CursorBackend(api, FakeRunStreamer(), isDemo = false), CursorBackend(api, FakeRunStreamer(), isDemo = true))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun repository(rowsAlone: Boolean) =
        AgentRepository(session, prefs, AttachmentStore(ApplicationProvider.getApplicationContext()), cache = null, scope = scope).also { it.classifyRowsAlone = rowsAlone }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun row(id: String, running: Boolean, at: Long, runId: String = "run-$id-0") = Agent(
        id = id,
        name = id,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = at,
        updatedAtMillis = at,
        latestRunId = runId,
        repoUrl = null,
        startingRef = null,
    )

    private val roots = (0 until 4).map { "bc-root-$it" }
    private val kinds = listOf(AgentParentKind.PROJECT_WORKER, AgentParentKind.SIDE_CHAT, AgentParentKind.SUBAGENT)

    private fun snapshot(random: Random, id: String): ComposerSnapshot {
        val isRoot = id in roots
        val parent = if (isRoot || random.nextInt(3) == 0) null else AgentParent(roots[random.nextInt(roots.size)], kinds[random.nextInt(kinds.size)])
        val project = isRoot && random.nextInt(10) != 0
        return ComposerSnapshot(
            id = id,
            name = if (random.nextInt(5) == 0) null else "$id ${random.nextInt(3)}",
            archived = listOf(null, false, false, true)[random.nextInt(4)],
            isProject = project,
            record = RecordFields(
                projectMetadata = if (project) "{}" else null,
                managerAgentId = parent?.takeIf { it.kind == AgentParentKind.PROJECT_WORKER }?.id,
                subagentParentId = parent?.takeIf { it.kind == AgentParentKind.SUBAGENT }?.id,
                sideChatParentId = parent?.takeIf { it.kind == AgentParentKind.SIDE_CHAT }?.id,
            ),
            parent = parent,
            status = if (random.nextBoolean()) RunStatus.RUNNING else RunStatus.FINISHED,
            activityAtMillis = if (random.nextInt(4) == 0) null else now - random.nextLong(0, 600_000L),
            createdAtMillis = now - 3_600_000L,
        )
    }

    private fun runTrace(seed: Int, ops: Int) {
        val random = Random(seed)
        val fast = repository(rowsAlone = true)
        val full = repository(rowsAlone = false)
        val both = listOf(fast, full)
        val ids = ArrayList<String>()
        var next = 0
        val seq = HashMap<String, Int>()
        fun newId() = "bc-$seed-${next++}"
        (roots + List(40) { newId() }).forEach { id ->
            ids += id
            val seeded = row(id, random.nextBoolean(), now - random.nextLong(0, 3_600_000L))
            both.forEach { it.upsert(seeded) }
        }
        val recorded = ids.filter { random.nextBoolean() }
        both.forEach { r -> val rr = Random(seed); r.applyAccountSnapshots(recorded.map { snapshot(rr, it) }) }
        val lastSnapshot = HashMap<String, ComposerSnapshot>()
        repeat(ops) { step ->
            val op = random.nextInt(13)
            val id = ids[random.nextInt(ids.size)]
            val opRandom = random.nextInt()
            now += random.nextLong(0, 5_000L)
            val what = when (op) {
                11 -> {
                    // The account saying again what it said: the row does not change, a stamp it has caught up with goes.
                    val again = lastSnapshot.values.shuffled(Random(opRandom)).take(1 + random.nextInt(6))
                    both.forEach { it.applyAccountSnapshots(again) }
                    "account round again ${again.size}"
                }
                12 -> {
                    now += AgentRepository.ACTION_GRACE_MS
                    "time past the action grace"
                }
                0, 1, 2 -> {
                    val at = now
                    val running = random.nextBoolean()
                    both.forEach { r -> r.patch(id) { it.copy(updatedAtMillis = at, summary = "step $step", runStatus = if (running) RunStatus.RUNNING else it.runStatus) } }
                    "patch $id"
                }
                3 -> {
                    val fresh = newId()
                    ids += fresh
                    val running = random.nextBoolean()
                    both.forEach { it.upsert(row(fresh, running, now)) }
                    "upsert new $fresh"
                }
                4 -> {
                    val subset = List(1 + random.nextInt(12)) { ids[random.nextInt(ids.size)] } + List(random.nextInt(3)) { "bc-$seed-${next + it}" }
                    val round = Random(opRandom).let { rr -> subset.map { snapshot(rr, it) } }
                    round.forEach { lastSnapshot[it.id] = it }
                    both.forEach { it.applyAccountSnapshots(round) }
                    "account round ${subset.size}"
                }
                5 -> {
                    val root = roots[random.nextInt(roots.size)]
                    val members = List(random.nextInt(6)) { ids[random.nextInt(ids.size)] }.associateWith { kinds[random.nextInt(2)] }
                    val signal = listOf(LineageSignal.MEMBERSHIP, LineageSignal.COORDINATOR_CREATED, LineageSignal.ACTION)[random.nextInt(3)]
                    val retract = if (random.nextBoolean()) setOf(AgentParentKind.PROJECT_WORKER) else emptySet()
                    both.forEach { it.applyLineage(root, members, signal, retract) }
                    "lineage $root ${members.size} $signal"
                }
                6 -> {
                    val runId = fast.agent(id)?.latestRunId ?: "run-$id-0"
                    both.forEach { it.noteRunEnded(id, runId, RunStatus.FINISHED) }
                    "run ended $id"
                }
                7 -> {
                    val n = seq.merge(id, 1, Int::plus)!!
                    val adopt = random.nextBoolean()
                    val status = if (random.nextBoolean()) "RUNNING" else "FINISHED"
                    val runId = if (adopt) "run-$id-$n" else fast.agent(id)?.latestRunId ?: "run-$id-0"
                    both.forEach { it.recordRun(id, RunDto(id = runId, agentId = id, status = status, createdAt = iso(now), updatedAt = iso(now)), adopt = adopt) }
                    "record run $id $status"
                }
                8 -> {
                    both.forEach { it.clearLineage(id) }
                    "clear lineage $id"
                }
                9 -> {
                    // A record read ahead of its row, then the row: the row is dressed with the record it waited on.
                    val fresh = newId()
                    ids += fresh
                    both.forEach { r -> r.applyAccountSnapshots(listOf(snapshot(Random(opRandom), fresh))) }
                    both.forEach { it.upsert(row(fresh, true, now)) }
                    "record then row $fresh"
                }
                else -> {
                    both.forEach { r -> r.patch(id) { it } }
                    "no-op patch $id"
                }
            }
            assertWithMessage("seed $seed step $step ($what)").that(fast.state.value).isEqualTo(full.state.value)
        }
        assertWithMessage("seed $seed: rows classified alone").that(fast.publishCounts.rows.get()).isGreaterThan(ops / 4)
        assertWithMessage("seed $seed: the reference never classifies rows alone").that(full.publishCounts.rows.get()).isEqualTo(0)
    }

    @Test
    fun `publishing a few rows leaves the list as the whole-list pass would`() {
        for (seed in 1..12) runTrace(seed, ops = 400)
    }

    @Test
    fun `a row patched in place keeps the list's index and its lookups`() {
        val repo = repository(rowsAlone = true)
        (0 until 50).forEach { repo.upsert(row("bc-$it", running = false, at = now - it)) }
        repo.patch("bc-7") { it.copy(summary = "one") }
        val first = repo.state.value
        assertThat(first.isIndexed).isTrue()
        repo.patch("bc-9") { it.copy(summary = "two") }
        assertThat(repo.state.value.isIndexed).isTrue()
        assertThat(repo.agent("bc-9")?.summary).isEqualTo("two")
        assertThat(repo.agent("bc-7")?.summary).isEqualTo("one")
        assertThat(repo.agent("bc-missing")).isNull()
        assertThat(repo.state.value.agents.map { it.id }).isEqualTo(first.agents.map { it.id })
        repo.upsert(row("bc-new", running = true, at = now))
        assertThat(repo.state.value.agents.first().id).isEqualTo("bc-new")
        assertThat(repo.agent("bc-new")).isNotNull()
        assertThat(repo.agent("bc-9")?.summary).isEqualTo("two")
    }

    @Test
    fun `a row's flow emits when that row changes and not when another does`() = runBlocking {
        val repo = repository(rowsAlone = true)
        (0 until 20).forEach { repo.upsert(row("bc-$it", running = false, at = now - it)) }
        val seen = ArrayList<Agent?>()
        val job = launch(Dispatchers.Unconfined) { repo.row("bc-3").collect { seen += it } }
        repo.patch("bc-5") { it.copy(summary = "other") }
        repo.patch("bc-3") { it.copy(summary = "mine") }
        repo.patch("bc-6") { it.copy(summary = "other") }
        repo.upsert(row("bc-new", running = false, at = now))
        yield()
        job.cancel()
        assertThat(seen.map { it?.summary }).containsExactly(null, "mine").inOrder()
        assertThat(repo.row("bc-gone").first()).isNull()
        assertThat(repo.row("bc-3").take(1).toList().single()?.summary).isEqualTo("mine")
    }
}
