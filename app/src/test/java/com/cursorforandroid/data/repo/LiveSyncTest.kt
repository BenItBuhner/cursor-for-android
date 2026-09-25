package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Which chats background live sync holds, and when it takes and lets go of them. */
class LiveSyncTest {

    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun agent(id: String, running: Boolean = false, parent: String? = null, project: Boolean = false, archived: Boolean = false, updatedAgo: Long = 60_000L) = Agent(
        id = id,
        name = id,
        lifecycle = when {
            archived -> AgentLifecycle.ARCHIVED
            running -> AgentLifecycle.ACTIVE
            else -> AgentLifecycle.IDLE
        },
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - 3_600_000L,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = null,
        startingRef = null,
        isProject = project,
        parent = parent?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) },
    )

    /** Holds as the repository sees them, in order; each hold's first load finishes when the test says so. */
    private class Recorder(private val autoSettle: Boolean = true) : LiveSync.Target {
        val calls = CopyOnWriteArrayList<String>()
        val held = ConcurrentHashMap.newKeySet<String>()
        val loads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        override fun hold(agentId: String) { if (held.add(agentId)) calls += "hold $agentId" }
        override fun release(agentId: String) { calls += "release $agentId"; held -= agentId }
        override suspend fun settled(agentId: String) {
            if (!autoSettle) loads.getOrPut(agentId) { CompletableDeferred() }.await()
        }
    }

    private fun sync(target: LiveSync.Target, max: Int = LiveSync.MAX_HELD, grace: Long = 60_000L) =
        LiveSync(target, scope, maxHeld = max, lingerMs = 120_000L, backgroundGraceMs = grace, nowProvider = { now })

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test
    fun `holds the running chats, their coordinators first, then the chats opened last`() {
        val sync = sync(Recorder())
        sync.opened("bc-read")
        val list = listOf(
            agent("bc-idle"),
            agent("bc-read"),
            agent("bc-solo", running = true, updatedAgo = 5_000L),
            agent("bc-root", project = true),
            agent("bc-worker", running = true, parent = "bc-root", updatedAgo = 20_000L),
            agent("bc-gone", running = true, archived = true),
        )

        assertThat(sync.select(list)).containsExactly("bc-root", "bc-solo", "bc-worker", "bc-read").inOrder()
    }

    @Test
    fun `a chat that stopped running keeps its hold for a while, then lets go`() {
        val sync = sync(Recorder())
        sync.select(listOf(agent("bc-a", running = true)))

        now += 60_000L
        assertThat(sync.select(listOf(agent("bc-a")))).containsExactly("bc-a")
        now += 61_000L
        assertThat(sync.select(listOf(agent("bc-a")))).isEmpty()
    }

    @Test
    fun `never more than the cap, coordinators and the newest running chats kept`() {
        val sync = sync(Recorder(), max = 3)
        val list = (1..10).map { agent("bc-$it", running = true, updatedAgo = it * 1_000L) } + agent("bc-root", project = true, updatedAgo = 999_000L)
        val withWorker = list + agent("bc-w", running = true, parent = "bc-root", updatedAgo = 500_000L)

        assertThat(sync.select(withWorker)).containsExactly("bc-root", "bc-1", "bc-2").inOrder()
    }

    @Test
    fun `new holds are taken one at a time, each after the last one's first load`() = runBlocking {
        val target = Recorder(autoSettle = false)
        val sync = sync(target)
        val agents = MutableStateFlow(listOf(agent("bc-a", running = true, updatedAgo = 1_000L), agent("bc-b", running = true, updatedAgo = 2_000L)))
        sync.start(agents, MutableStateFlow(true), MutableStateFlow(true))

        until { target.calls.isNotEmpty() }
        delay(200)
        assertThat(target.calls).containsExactly("hold bc-a")
        until { target.loads.containsKey("bc-a") }
        target.loads.getValue("bc-a").complete(Unit)
        until { target.calls.size == 2 }
        assertThat(target.calls).containsExactly("hold bc-a", "hold bc-b").inOrder()
    }

    @Test
    fun `the switch turned off lets every hold go at once`() = runBlocking {
        val target = Recorder()
        val sync = sync(target)
        val enabled = MutableStateFlow(true)
        sync.start(MutableStateFlow(listOf(agent("bc-a", running = true), agent("bc-b", running = true))), enabled, MutableStateFlow(true))
        until { target.held.size == 2 }

        enabled.value = false
        until { target.held.isEmpty() }
        assertThat(sync.heldIds.value).isEmpty()
    }

    @Test
    fun `the app in the background lets go after the grace, and a quick switch keeps everything`() = runBlocking {
        val target = Recorder()
        val sync = sync(target, grace = 400L)
        val foreground = MutableStateFlow(true)
        sync.start(MutableStateFlow(listOf(agent("bc-a", running = true))), MutableStateFlow(true), foreground)
        until { target.held == setOf("bc-a") }

        foreground.value = false
        delay(100)
        foreground.value = true
        delay(600)
        assertThat(target.calls).containsExactly("hold bc-a")

        foreground.value = false
        until { target.held.isEmpty() }
        assertThat(target.calls).containsExactly("hold bc-a", "release bc-a").inOrder()
    }

    @Test
    fun `a chat that starts running is held on the list that says so, and idle chats hold nothing`() = runBlocking {
        val target = Recorder()
        val sync = sync(target)
        val agents = MutableStateFlow(listOf(agent("bc-a"), agent("bc-b")))
        sync.start(agents, MutableStateFlow(true), MutableStateFlow(true))
        delay(200)
        assertThat(target.calls).isEmpty()

        agents.value = listOf(agent("bc-a", running = true), agent("bc-b"))
        until { target.held == setOf("bc-a") }
    }
}
