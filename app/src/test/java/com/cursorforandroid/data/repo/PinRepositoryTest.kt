package com.cursorforandroid.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

        override suspend fun list(): AccountList {
            failing?.let { throw it }
            listCalls++
            return AccountList(PinnedIds(server.toSet(), loaded), pullRequests.toMap())
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

    /** The real settings store, with its reads held up and its writes refused on demand. */
    private class GatedSettings(context: android.content.Context) : DataStore<Preferences> {
        private val delegate = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("pin_repository_test") },
        )

        /** Awaited by every read while set, so a test can stop a caller between what it reads and what it writes. */
        @Volatile var readGate: CompletableDeferred<Unit>? = null
        val reads = AtomicInteger()
        @Volatile var writesFail = false

        override val data: Flow<Preferences> = flow {
            reads.incrementAndGet()
            readGate?.await()
            emitAll(delegate.data)
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (writesFail) throw IOException("No space left on device")
            return delegate.updateData(transform)
        }
    }

    private val api = FakeCursorApi()
    private val pinsApi = FakePinsApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: GatedSettings
    private lateinit var prefs: PreferencesStore
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository
    private lateinit var pins: PinRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = GatedSettings(context)
        prefs = PreferencesStore(context, settings)
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
    fun `two taps in quick succession leave this device, the account and the pending changes agreeing`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        val listed = CompletableDeferred<Unit>()
        var release: Continuation<Unit>? = null
        val gated = object : PinsApi by pinsApi {
            override suspend fun list(): AccountList {
                suspendCoroutine<Unit> { release = it; listed.complete(Unit) }
                return pinsApi.list()
            }
        }
        val repository = PinRepository(session, prefs, agents, gated, scope, now = { now })

        // A round in flight has the server to itself, so both taps land locally before either can send anything.
        val sync = scope.launch { repository.sync() }
        listed.await()
        val first = scope.launch { repository.toggle("bc-1") }
        val second = scope.launch { repository.toggle("bc-1") }
        // Both flips have landed once the pin is back where it started and a wish is still waiting for the server.
        awaitUntil { pinnedIds().isEmpty() && prefs.pendingPinChanges.first().isNotEmpty() }
        release!!.resume(Unit)
        sync.join()
        first.join()
        second.join()

        // What goes out is where the taps left things, once: the call that gets there second finds nothing to say.
        assertThat(pinnedIds()).isEmpty()
        assertThat(pinsApi.server).isEmpty()
        assertThat(pinsApi.pinCalls).isEmpty()
        assertThat(pinsApi.unpinCalls).containsExactly(listOf("bc-1"))
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(repository.state.value.pendingCount).isEqualTo(0)
    }

    @Test
    fun `a pin tapped while a sync is in flight outlives the account's answer`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        pinsApi.server += "bc-2"
        val listed = CompletableDeferred<Unit>()
        var release: Continuation<Unit>? = null
        val gated = object : PinsApi by pinsApi {
            override suspend fun list(): AccountList {
                suspendCoroutine<Unit> { release = it; listed.complete(Unit) }
                return pinsApi.list()
            }
        }
        val repository = PinRepository(session, prefs, agents, gated, scope, now = { now })

        val sync = scope.launch { repository.sync() }
        listed.await()
        val tap = scope.launch { repository.toggle("bc-1") }
        awaitUntil { pinnedIds().contains("bc-1") }
        release!!.resume(Unit)
        sync.join()
        tap.join()

        assertThat(pinnedIds()).containsExactly("bc-1", "bc-2")
        assertThat(pinsApi.server).containsExactly("bc-1", "bc-2")
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
    }

    @Test
    fun `a sync answered after a sign-out writes none of the previous account's pins`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        pinsApi.server += setOf("bc-1", "bc-2")
        val listed = CompletableDeferred<Unit>()
        var release: Continuation<Unit>? = null
        val gated = object : PinsApi by pinsApi {
            override suspend fun list(): AccountList {
                suspendCoroutine<Unit> { release = it; listed.complete(Unit) }
                return pinsApi.list()
            }
        }
        val handed = CopyOnWriteArrayList<AccountList>()
        val repository = PinRepository(session, prefs, agents, gated, scope, now = { now }, onList = { handed += it })

        val sync = scope.launch { repository.sync() }
        listed.await()
        repository.reset()
        release!!.resume(Unit)
        sync.join()
        // Serialized behind the round: this returning means the round has let go, having written nothing.
        assertThat(repository.toggle("bc-3").isSuccess).isTrue()

        assertThat(pinnedIds()).containsExactly("bc-3")
        assertThat(handed).isEmpty()
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
    }

    /**
     * The pin the user asked for is a preference write like any other, and it can be the one that fails. Reporting
     * it as done would leave the sidebar and the account disagreeing about a pin nobody ever made.
     */
    @Test
    fun `a pin the settings could not save is reported as failed, not as pinned`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        settings.writesFail = true

        val result = pins.toggle("bc-1")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        settings.writesFail = false
        assertThat(pinnedIds()).isEmpty()
        assertThat(pinsApi.pinCalls).isEmpty()
    }

    /**
     * The tap has read what it needs and is about to flip the pin when the sign-out lands. The flip must not happen
     * after it: the preferences are cleared as part of that sign-out, so a pin written afterwards is the previous
     * account's state left sitting in the next one's.
     */
    @Test
    fun `a pin tapped for the previous account is never flipped into the next one's`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        val before = settings.reads.get()
        settings.readGate = CompletableDeferred()

        val tap = scope.launch { assertThat(pins.toggle("bc-1").isSuccess).isTrue() }
        awaitUntil { settings.reads.get() > before }
        // reset() takes the same lock the flip does, so this returning means no flip is half-done behind it.
        pins.reset()
        settings.readGate!!.complete(Unit)
        settings.readGate = null
        tap.join()

        assertThat(pinnedIds()).isEmpty()
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(pinsApi.pinCalls).isEmpty()
    }

    @Test
    fun `more pinned agents than one round fetches arrive over the next rounds, however many fail`() = runBlocking<Unit> {
        prefs.setPinsMigrated(true)
        val order = object : PinsApi by pinsApi {
            override suspend fun list(): AccountList =
                AccountList(PinnedIds(linkedSetOf("bc-gone-1", "bc-gone-2", "bc-1", "bc-2"), loaded = true), emptyMap())
        }
        // Two per round, and the two the account can no longer produce are tried first.
        val repository = PinRepository(session, prefs, agents, order, scope, now = { now }, maxMaterialized = 2)

        assertThat(repository.sync().isSuccess).isTrue()
        assertThat(agents.agent("bc-1")).isNull()
        assertThat(agents.agent("bc-2")).isNull()

        assertThat(repository.sync().isSuccess).isTrue()

        assertThat(agents.agent("bc-1")?.name).isEqualTo("One")
        assertThat(agents.agent("bc-2")?.name).isEqualTo("Two")
        assertThat(pinnedIds()).containsExactly("bc-gone-1", "bc-gone-2", "bc-1", "bc-2")
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
