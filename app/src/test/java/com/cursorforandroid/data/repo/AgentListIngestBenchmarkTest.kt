package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RecordFields
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
import com.cursorforandroid.util.threadAllocatedBytes
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * What the agent list costs to feed while a fleet streams: 200 and 500 agents, 60 and 120 of them running, every
 * running agent's row patched once per one-second tick (the run monitor's word on each run's snapshot), and the
 * account round over the moved rows once per tick. Per tick: the time and allocation of the ingest on the calling
 * thread, the classification passes over the whole list, the rows classified one by one, the publications that
 * changed the list, and what the per-id collectors — a chat pane's row, a follow-up's idle watch — cost the thread
 * they are collected on. The counts are the budget; the times are printed for the record.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AgentListIngestBenchmarkTest {

    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: SessionManager
    private lateinit var prefs: PreferencesStore
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "bench-main") }

    /** [inner] with every dispatch counted: how many times a collector's thread was woken. */
    private class CountingDispatcher(private val inner: CoroutineDispatcher, private val count: AtomicInteger) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            count.incrementAndGet()
            inner.dispatch(context, block)
        }
    }

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
        main.shutdownNow()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private enum class Fleet(val agents: Int, val running: Int) { S200(200, 60), S500(500, 120) }

    private class Chat(val id: String, val parent: AgentParent?, val isProject: Boolean, var activity: Long, var running: Boolean) {
        fun snapshot() = ComposerSnapshot(
            id = id,
            name = id,
            archived = false,
            isProject = isProject,
            record = RecordFields(
                projectMetadata = if (isProject) "{}" else null,
                managerAgentId = parent?.takeIf { it.kind == AgentParentKind.PROJECT_WORKER }?.id,
                sideChatParentId = parent?.takeIf { it.kind == AgentParentKind.SIDE_CHAT }?.id,
            ),
            parent = parent,
            status = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
            activityAtMillis = activity,
            createdAtMillis = activity,
        )
    }

    /** Ten Projects (the first with 120 children), the account's own chats for the rest; [Fleet.running] running. */
    private fun fleet(size: Fleet): List<Chat> {
        val out = ArrayList<Chat>()
        var t = now - 60_000L
        val roots = (0 until 10).map { Chat("bc-root-$it", null, isProject = true, activity = t--, running = false) }
        out += roots
        repeat(120) { out += Chat("bc-big-$it", AgentParent(roots[0].id, if (it < 6) AgentParentKind.SIDE_CHAT else AgentParentKind.PROJECT_WORKER), false, t--, false) }
        repeat(60) { out += Chat("bc-p-$it", AgentParent(roots[1 + it % 9].id, AgentParentKind.PROJECT_WORKER), false, t--, false) }
        var own = 0
        while (out.size < size.agents) out += Chat("bc-own-${own++}", null, false, t--, false)
        out.filter { it.parent?.id == roots[0].id }.take(40).forEach { it.running = true }
        out.filter { !it.isProject && it.parent?.id != roots[0].id }.take(size.running - 40).forEach { it.running = true }
        check(out.size == size.agents && out.count { it.running } == size.running)
        return out
    }

    private fun Chat.row() = Agent(
        id = id,
        name = id,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = activity,
        updatedAtMillis = activity,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
    )

    private fun repository(chats: List<Chat>): AgentRepository {
        val repo = AgentRepository(session, prefs, AttachmentStore(ApplicationProvider.getApplicationContext()), cache = null, scope = scope)
        chats.asReversed().forEach { repo.upsert(it.row()) }
        repo.applyAccountSnapshots(chats.map { it.snapshot() })
        chats.filter { it.isProject }.forEach { root ->
            repo.applyLineage(root.id, chats.filter { it.parent?.id == root.id }.associate { it.id to it.parent!!.kind }, LineageSignal.MEMBERSHIP)
        }
        check(repo.state.value.agents.size == chats.size)
        return repo
    }

    /** A per-id collector as the chat pane and the follow-up queue collect one: this row, re-emitted when it changes. */
    private fun rowOf(repo: AgentRepository, id: String): Flow<Agent?> = repo.row(id)

    private enum class Kind { Patch, Round }

    private class Result(
        val msPerTick: Double,
        val allocKbPerTick: Double,
        val passesPerTick: Double,
        val rowsPerTick: Double,
        val changesPerTick: Double,
        val mainWakesIdlePerTick: Double,
        val mainWakesPatchedPerTick: Double,
        val emitsIdle: Int,
        val emitsPatched: Int,
        val indexedAfterPatch: Boolean,
    )

    private fun measure(size: Fleet, kind: Kind, ticks: Int = 40, warmup: Int = 20): Result {
        val chats = fleet(size)
        val repo = repository(chats)
        val running = chats.filter { it.running }
        val idleId = chats.first { !it.running && !it.isProject }.id
        val patchedId = running.first().id
        // One chat pane on an idle chat and one on a streaming chat, collected on a counted "main" thread.
        val idleEmits = AtomicInteger()
        val patchedEmits = AtomicInteger()
        val idleWakes = AtomicInteger()
        val patchedWakes = AtomicInteger()
        val idleMain = CountingDispatcher(main.asCoroutineDispatcher(), idleWakes)
        val patchedMain = CountingDispatcher(main.asCoroutineDispatcher(), patchedWakes)
        scope.launch(idleMain) { rowOf(repo, idleId).flowOn(Dispatchers.Default).collect { idleEmits.incrementAndGet() } }
        scope.launch(patchedMain) { rowOf(repo, patchedId).flowOn(Dispatchers.Default).collect { patchedEmits.incrementAndGet() } }
        Thread.sleep(200)
        var t = now
        var step = 0
        fun tick() {
            t += 1_000L
            step++
            running.forEach { it.activity = t }
            when (kind) {
                Kind.Patch -> running.forEach { c -> repo.patch(c.id) { it.copy(updatedAtMillis = t, summary = "step $step") } }
                Kind.Round -> repo.applyAccountSnapshots(running.map { it.snapshot() })
            }
        }
        repeat(warmup) { tick() }
        Thread.sleep(200)
        val passes0 = repo.publishCounts.passes.get()
        val rows0 = repo.publishCounts.rows.get()
        val changes0 = repo.publishCounts.changes.get()
        val idleEmits0 = idleEmits.get()
        val patchedEmits0 = patchedEmits.get()
        val idleWakes0 = idleWakes.get()
        val patchedWakes0 = patchedWakes.get()
        var nanos = 0L
        var bytes = 0L
        var indexed = true
        repeat(ticks) {
            val b0 = threadAllocatedBytes()
            val n0 = System.nanoTime()
            tick()
            nanos += System.nanoTime() - n0
            bytes += threadAllocatedBytes() - b0
            indexed = indexed && repo.state.value.isIndexed
            // A second between ticks, as far as the collectors are concerned: each sees the tick's last word.
            Thread.sleep(20)
        }
        Thread.sleep(200)
        val result = Result(
            msPerTick = nanos / 1e6 / ticks,
            allocKbPerTick = bytes / 1024.0 / ticks,
            passesPerTick = (repo.publishCounts.passes.get() - passes0).toDouble() / ticks,
            rowsPerTick = (repo.publishCounts.rows.get() - rows0).toDouble() / ticks,
            changesPerTick = (repo.publishCounts.changes.get() - changes0).toDouble() / ticks,
            mainWakesIdlePerTick = (idleWakes.get() - idleWakes0).toDouble() / ticks,
            mainWakesPatchedPerTick = (patchedWakes.get() - patchedWakes0).toDouble() / ticks,
            emitsIdle = idleEmits.get() - idleEmits0,
            emitsPatched = patchedEmits.get() - patchedEmits0,
            indexedAfterPatch = indexed,
        )
        println(
            "SCALE ingest fleet=$size kind=$kind running=${running.size} ms/tick=%.3f alloc/tick=%.0fKB classifyPasses/tick=%.2f rowsClassified/tick=%.1f changes/tick=%.1f mainWakes/tick idle=%.2f patched=%.2f emits idle=%d patched=%d indexed=%s"
                .format(result.msPerTick, result.allocKbPerTick, result.passesPerTick, result.rowsPerTick, result.changesPerTick, result.mainWakesIdlePerTick, result.mainWakesPatchedPerTick, result.emitsIdle, result.emitsPatched, result.indexedAfterPatch),
        )
        return result
    }

    @Test
    fun `a single row's patch classifies that row alone and wakes only its own collectors`() {
        for (size in Fleet.entries) {
            val r = measure(size, Kind.Patch)
            val running = size.running.toDouble()
            assertWithMessage("$size whole-list classification passes per tick").that(r.passesPerTick).isEqualTo(0.0)
            assertWithMessage("$size rows classified per tick").that(r.rowsPerTick).isEqualTo(running)
            assertWithMessage("$size publications per tick").that(r.changesPerTick).isEqualTo(running)
            assertWithMessage("$size the list stays indexed across patches").that(r.indexedAfterPatch).isTrue()
            assertWithMessage("$size an idle chat's collector is never woken on its thread").that(r.emitsIdle).isEqualTo(0)
            assertWithMessage("$size an idle chat's collector thread wakes").that(r.mainWakesIdlePerTick).isEqualTo(0.0)
            assertWithMessage("$size a streaming chat's collector sees its row move").that(r.emitsPatched).isAtLeast(1)
            assertWithMessage("$size a streaming chat's collector wakes at most once per patch").that(r.mainWakesPatchedPerTick).isAtMost(1.0)
        }
    }

    @Test
    fun `the account round over the moved rows classifies those rows alone`() {
        for (size in Fleet.entries) {
            val r = measure(size, Kind.Round)
            assertWithMessage("$size whole-list classification passes per tick").that(r.passesPerTick).isEqualTo(0.0)
            assertWithMessage("$size rows classified per tick").that(r.rowsPerTick).isAtMost(size.running.toDouble())
            assertWithMessage("$size publications per tick").that(r.changesPerTick).isAtMost(1.0)
            assertWithMessage("$size an idle chat's collector is never woken on its thread").that(r.emitsIdle).isEqualTo(0)
        }
    }
}
