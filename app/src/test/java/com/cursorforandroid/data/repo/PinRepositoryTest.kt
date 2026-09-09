package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pins kept in step with the account: the first sync's migration, the server's authority afterwards, changes that
 * wait for the server, refusals, and the pinned agents the list window left behind. Robolectric for the
 * preference store and the session.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PinRepositoryTest {

    private class FakePinsApi : PinsApi {
        val server: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var loaded = true
        /** Thrown by every call while set. */
        @Volatile var failing: Throwable? = null
        val pinCalls = CopyOnWriteArrayList<List<String>>()
        val unpinCalls = CopyOnWriteArrayList<List<String>>()
        @Volatile var listCalls = 0
        /** What the list says about pull requests, by URL. */
        val pullRequests: MutableMap<String, PullRequestState> = ConcurrentHashMap()
        val composers = CopyOnWriteArrayList<ComposerSnapshot>()

        override suspend fun list(): AccountList {
            failing?.let { throw it }
            listCalls++
            return AccountList(PinnedIds(server.toSet(), loaded), pullRequests.toMap(), composers = composers.toList())
        }

        override suspend fun pin(ids: Collection<String>) {
            failing?.let { throw it }
            pinCalls += ids.toList()
            server += ids
        }

        override suspend fun unpin(ids: Collection<String>) {
            failing?.let { throw it }
            unpinCalls += ids.toList()
            server -= ids.toSet()
        }
    }

    private val api = FakeCursorApi()
    private val pinsApi = FakePinsApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository
    private lateinit var pins: PinRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = PreferencesStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
        agents = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10)
        pins = PinRepository(session, prefs, agents, pinsApi, scope, now = { now })
        AppClock.nowMillis = { now }
        api.addIdleAgent("bc-1", "One", "run-1", createdAt = "2026-04-13T18:30:00.000Z")
        api.addIdleAgent("bc-2", "Two", "run-2", createdAt = "2026-04-13T17:30:00.000Z")
        api.addIdleAgent("bc-3", "Three", "run-3", createdAt = "2026-04-13T16:30:00.000Z")
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private suspend fun pinnedIds() = prefs.localAgentState.first().pinnedIds

    @Test
    fun `a list read overlays the account's name and archive flag`() = runBlocking<Unit> {
        pinsApi.composers += ComposerSnapshot("bc-1", name = "Renamed on iOS", archived = true)
        agents.refresh()
        awaitUntil { agents.state.value.agents.any { it.id == "bc-1" && it.name == "Renamed on iOS" && it.lifecycle == AgentLifecycle.ARCHIVED } }
    }

    @Test
    fun `the first sync pushes the pins made here for agents the account can see, then adopts the server's list`() = runBlocking<Unit> {
        prefs.setPinnedIds(setOf("bc-1", "bc-gone-elsewhere"))
        pinsApi.server += "bc-2"

        agents.refresh()
        awaitUntil { pins.state.value.lastSyncedAtMillis != null }

        assertThat(pinsApi.pinCalls).containsExactly(listOf("bc-1"))
        assertThat(pinnedIds()).containsExactly("bc-1", "bc-2")
        assertThat(prefs.pinsMigrated.first()).isTrue()
        assertThat(pins.state.value.active).isTrue()
        assertThat(pins.state.value.error).isNull()
    }

    @Test
    fun `after the migration the server's list is what shows, including pins removed elsewhere`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        prefs.setPinnedIds(setOf("bc-1"))
        pinsApi.server += "bc-2"

        val result = pins.sync()

        assertThat(result.isSuccess).isTrue()
        assertThat(pinsApi.pinCalls).isEmpty()
        assertThat(pinnedIds()).containsExactly("bc-2")
    }

    @Test
    fun `toggling pins on the server at once and unpins the same way`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)

        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        assertThat(pinnedIds()).containsExactly("bc-1")
        assertThat(pinsApi.pinCalls).containsExactly(listOf("bc-1"))

        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        assertThat(pinnedIds()).isEmpty()
        assertThat(pinsApi.unpinCalls).containsExactly(listOf("bc-1"))
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
    }

    @Test
    fun `a pin the server could not be told about stays, waits, and goes up with the next sync`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        pinsApi.failing = IOException("offline")

        val result = pins.toggle("bc-1")

        assertThat(result.isFailure).isTrue()
        assertThat(pinnedIds()).containsExactly("bc-1")
        assertThat(prefs.pendingPinChanges.first()).containsExactly("bc-1", true)
        assertThat(pins.state.value.pendingCount).isEqualTo(1)
        assertThat(pins.state.value.error).isNotNull()

        pinsApi.failing = null
        assertThat(pins.sync().isSuccess).isTrue()

        assertThat(pinsApi.pinCalls).containsExactly(listOf("bc-1"))
        assertThat(pinsApi.server).containsExactly("bc-1")
        assertThat(pinnedIds()).containsExactly("bc-1")
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(pins.state.value.error).isNull()
        assertThat(pins.state.value.pendingCount).isEqualTo(0)
    }

    @Test
    fun `a change the server refuses outright is dropped and the account's list adopted`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        prefs.setPinnedIds(setOf("bc-1"))
        prefs.setPendingPinChange("bc-1", true)
        pinsApi.server += "bc-2"
        val refusingApi = object : PinsApi by pinsApi {
            override suspend fun pin(ids: Collection<String>) = throw ConnectRpcException(403, "permission_denied", "Not the account's agent.")
        }
        val repository = PinRepository(session, prefs, agents, refusingApi, scope, now = { now })

        val result = repository.sync()

        assertThat(result.isSuccess).isTrue()
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(pinnedIds()).containsExactly("bc-2")
        assertThat(repository.state.value.error).contains("Not the account's agent.")
    }

    @Test
    fun `a server that did not load the pinned state leaves the pins here alone`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        prefs.setPinnedIds(setOf("bc-1"))
        pinsApi.loaded = false
        pinsApi.server += "bc-2"

        assertThat(pins.sync().isSuccess).isTrue()

        assertThat(pinnedIds()).containsExactly("bc-1")
    }

    @Test
    fun `the demo and the setting turned off keep pins on this device`() = runBlocking<Unit> {
        prefs.setPinSyncEnabled(false)
        pinsApi.server += "bc-2"
        pins.toggle("bc-1")
        pins.sync()
        // The list is still read (it carries the pull request states), but nothing about pins goes either way.
        assertThat(pinnedIds()).containsExactly("bc-1")
        assertThat(pinsApi.listCalls).isEqualTo(1)
        assertThat(pinsApi.pinCalls).isEmpty()
        assertThat(pins.state.value.active).isFalse()

        prefs.setPinSyncEnabled(true)
        session.enterDemo()
        pins.toggle("bc-demo-0001")
        pins.sync()
        assertThat(pinnedIds()).contains("bc-demo-0001")
        assertThat(pinsApi.listCalls).isEqualTo(1)
        assertThat(pinsApi.pinCalls).isEmpty()
    }

    @Test
    fun `every list read hands its pull request states on, whether or not the pins are being synced`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        pinsApi.pullRequests["https://github.com/acme/app/pull/1"] = PullRequestState.Merged
        pinsApi.server += "bc-2"
        val handed = mutableListOf<AccountList>()
        val repository = PinRepository(session, prefs, agents, pinsApi, scope, now = { now }, onList = { handed += it })

        assertThat(repository.sync().isSuccess).isTrue()
        assertThat(handed.single().pullRequests).containsExactly("https://github.com/acme/app/pull/1", PullRequestState.Merged)
        assertThat(pinnedIds()).containsExactly("bc-2")

        // Pins off: the list is still read for its pull request states, but the pins stay as they are here.
        prefs.setPinSyncEnabled(false)
        pinsApi.server += "bc-3"
        assertThat(repository.sync().isSuccess).isTrue()
        assertThat(handed).hasSize(2)
        assertThat(pinnedIds()).containsExactly("bc-2")
        assertThat(repository.state.value.active).isFalse()
    }

    @Test
    fun `a failure that retrying cannot fix halts syncing until the setting is turned on again`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        val policy = object : PinsApi by pinsApi {
            override suspend fun list(): AccountList =
                throw SessionUnavailableException("Device policy.", SessionUnavailableException.SIGN_IN_POLICY_VIOLATION)
        }
        var listCalls = 0
        val counting = object : PinsApi by policy {
            override suspend fun list(): AccountList { listCalls++; return policy.list() }
        }
        val repository = PinRepository(session, prefs, agents, counting, scope, now = { now })

        // The completed list fetch is what starts syncing (and watching the setting).
        agents.refresh()
        awaitUntil { repository.state.value.error != null }
        assertThat(repository.state.value.error).contains("Device policy.")
        assertThat(listCalls).isEqualTo(1)
        assertThat(repository.sync().isSuccess).isTrue() // halted: nothing is asked
        assertThat(listCalls).isEqualTo(1)

        prefs.setPinSyncEnabled(false)
        prefs.setPinSyncEnabled(true)
        awaitUntil { listCalls == 2 }
    }

    @Test
    fun `pinned agents the list window left behind are fetched by id and survive the next listing`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        // Six agents, one per page, five pages per full refresh: the oldest is never listed.
        api.pageSize = 1
        api.addIdleAgent("bc-4", "Four", "run-4", createdAt = "2026-04-13T15:30:00.000Z")
        api.addIdleAgent("bc-5", "Five", "run-5", createdAt = "2026-04-13T14:30:00.000Z")
        api.addIdleAgent("bc-old", "Old and pinned", "run-old", createdAt = "2026-04-13T13:30:00.000Z")
        pinsApi.server += "bc-old"

        agents.refresh()
        awaitUntil { pins.state.value.lastSyncedAtMillis != null }
        awaitUntil { agents.agent("bc-old") != null }

        assertThat(pinnedIds()).containsExactly("bc-old")
        assertThat(agents.agent("bc-old")?.name).isEqualTo("Old and pinned")

        // A complete listing that does not include it keeps it, because it is pinned.
        api.pageSize = Int.MAX_VALUE
        api.agents.remove("bc-old")
        api.agents.remove("bc-3")
        agents.refresh()
        assertThat(agents.agent("bc-old")).isNotNull()
        assertThat(agents.agent("bc-3")).isNull()
    }
}
