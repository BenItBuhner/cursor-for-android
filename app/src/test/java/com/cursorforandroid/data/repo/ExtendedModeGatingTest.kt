package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.AccountList
import com.cursorforandroid.data.api.AccountProfile
import com.cursorforandroid.data.api.ComposerLifecycleApi
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.PinnedIds
import com.cursorforandroid.data.api.PinsApi
import com.cursorforandroid.data.api.ProfileApi
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every private surface, under both settings: with Extended mode off nothing reaches the account service and the
 * app behaves like a documented-API-only install; with it on, each path does what it did before the setting existed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExtendedModeGatingTest {

    private class RecordingAccount : PinsApi, ComposerLifecycleApi {
        val calls = CopyOnWriteArrayList<String>()
        val server = mutableSetOf("bc-2")
        override suspend fun list(): AccountList {
            calls += "list"
            return AccountList(
                PinnedIds(server.toSet(), loaded = true),
                mapOf("https://github.com/acme/app/pull/7" to PullRequestState.Merged),
                sources = mapOf("bc-1" to AgentSource.SLACK),
                composers = listOf(ComposerSnapshot("bc-1", name = "Renamed on iOS")),
            )
        }
        override suspend fun pin(ids: Collection<String>) { calls += "pin:${ids.joinToString()}"; server += ids }
        override suspend fun unpin(ids: Collection<String>) { calls += "unpin:${ids.joinToString()}"; server -= ids.toSet() }
        override suspend fun archive(id: String) { calls += "archive:$id" }
        override suspend fun unarchive(id: String) { calls += "unarchive:$id" }
        override suspend fun rename(id: String, name: String) { calls += "rename:$id:$name" }
    }

    private class RecordingSlashApi(val label: String) : SlashCommandApi {
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog {
            calls += "repo"
            return SlashCatalog(listOf(SlashCommand("$label-skill", origin = SlashCommand.Origin.Project)))
        }
        override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog {
            calls += "agent"
            return SlashCatalog(listOf(SlashCommand("$label-skill", origin = SlashCommand.Origin.Project)))
        }
        override suspend fun global(): List<SlashCommand> {
            calls += "global"
            return listOf(SlashCommand("$label-global", kind = SlashCommand.Kind.Command))
        }
    }

    private class RecordingProfile : ProfileApi {
        var calls = 0
        override suspend fun profile(): AccountProfile {
            calls++
            return AccountProfile("https://pics.example/alex.png", "alex@example.com", "Alex", "Rivera", "Acme")
        }
    }

    private val api = FakeCursorApi()
    private val account = RecordingAccount()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var context: Context
    private lateinit var prefs: PreferencesStore
    private lateinit var keyStore: SecureKeyStore
    private lateinit var session: SessionManager
    private var extended = false
    private val capabilities: suspend () -> Capabilities = { Capabilities.of(extended) }
    private val profile = RecordingProfile()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferencesStore(context)
        keyStore = SecureKeyStore(context)
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(
            keyStore, prefs, backend, CursorBackend(api, FakeRunStreamer(), isDemo = true),
            profile = lazyOf(profile), capabilities = capabilities,
        )
        api.addIdleAgent("bc-1", "One", "run-1")
        api.addIdleAgent("bc-2", "Two", "run-2")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun agents() = AgentRepository(session, prefs, AttachmentStore(context), cache = null, scope = scope, persistDelayMs = 10, account = account, capabilities = capabilities)

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    // ---- pins -------------------------------------------------------------------------------------------------------

    @Test
    fun `off, the pins are this device's and the account is never listed`() = runBlocking<Unit> {
        val agents = agents()
        val handed = CopyOnWriteArrayList<AccountList>()
        val pins = PinRepository(session, prefs, agents, account, scope, onList = { list, agentsToken -> handed += list; agents.applySources(list.sources, agentsToken) }, capabilities = capabilities)
        prefs.setPinsMigrated(true)

        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        assertThat(pins.sync().isSuccess).isTrue()
        agents.refresh()
        delay(100)

        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-1")
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(account.calls).isEmpty()
        assertThat(handed).isEmpty()
        assertThat(pins.state.value.active).isFalse()
        assertThat(pins.state.value.error).isNull()
        // Nothing the account would have said reached the rows either.
        assertThat(agents.agent("bc-1")?.name).isEqualTo("One")
        assertThat(agents.agent("bc-1")?.source).isNull()

        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()
        assertThat(account.calls).isEmpty()
    }

    @Test
    fun `on, the pins follow the account as before`() = runBlocking<Unit> {
        extended = true
        val agents = agents()
        val handed = CopyOnWriteArrayList<AccountList>()
        val pins = PinRepository(session, prefs, agents, account, scope, onList = { list, agentsToken -> handed += list; agents.applySources(list.sources, agentsToken) }, capabilities = capabilities)
        prefs.setPinsMigrated(true)

        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        agents.refresh()
        // The completed fetch is the cue for the round; the list handed on is what says the round has been.
        awaitUntil { handed.isNotEmpty() }
        awaitUntil { prefs.localAgentState.first().pinnedIds == setOf("bc-1", "bc-2") }

        assertThat(account.calls).containsAtLeast("pin:bc-1", "list").inOrder()
        assertThat(pins.state.value.active).isTrue()
        awaitUntil { agents.agent("bc-1")?.name == "Renamed on iOS" }
        assertThat(agents.agent("bc-1")?.source).isEqualTo(AgentSource.SLACK)
    }

    /**
     * Turning the mode off while a round is inside the account's list read — the setting is written first, then
     * `AppGraph.extendedMode.onDisabled` resets the pins, which is what is reproduced here. The answer that was on its
     * way is dropped rather than applied, the next cue asks the account nothing, and a pin made afterwards is the
     * device's own with nothing recorded as owed to a server.
     */
    @Test
    fun `turned off mid-round, the answer on its way is dropped and the next cue asks the account nothing`() = runBlocking<Unit> {
        extended = true
        val agents = agents()
        val listed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val gated = object : PinsApi by account {
            override suspend fun list(): AccountList {
                if (attempts.incrementAndGet() == 1) {
                    listed.complete(Unit)
                    release.await()
                }
                return account.list()
            }
        }
        val handed = CopyOnWriteArrayList<AccountList>()
        val pins = PinRepository(session, prefs, agents, gated, scope, onList = { list, _ -> handed += list }, capabilities = capabilities)
        prefs.setPinsMigrated(true)

        // The completed fetch cues the round; the mode goes off while its list read is in flight.
        agents.refresh()
        listed.await()
        extended = false
        pins.reset()
        release.complete(Unit)
        delay(300)

        // Nothing of the answer landed: no pin adopted, no name, nothing handed on; the state is the blank one.
        assertThat(account.calls).isEmpty()
        assertThat(handed).isEmpty()
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()
        assertThat(agents.agent("bc-1")?.name).isEqualTo("One")
        assertThat(pins.state.value).isEqualTo(PinSyncState())

        // The next completed fetch is the next cue, and it asks the account nothing.
        agents.refresh()
        delay(200)
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(pins.state.value.active).isFalse()

        // A pin made now is this device's: applied, and not recorded as owed to any server.
        assertThat(pins.toggle("bc-1").isSuccess).isTrue()
        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-1")
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(account.calls).isEmpty()
    }

    /**
     * A failed round leaves a retry chain behind (five seconds, twenty, a minute); turning the mode off has to end it,
     * or a retry would be the one private call the setting did not stop. The waits are shortened here to see it out.
     */
    @Test
    fun `turned off while a failed round waits to retry, the retries never come`() = runBlocking<Unit> {
        extended = true
        val agents = agents()
        val attempts = AtomicInteger()
        val offline = object : PinsApi by account {
            override suspend fun list(): AccountList {
                attempts.incrementAndGet()
                throw IOException("offline")
            }
        }
        val pins = PinRepository(session, prefs, agents, offline, scope, retryDelaysMs = listOf(150L, 150L, 150L), capabilities = capabilities)
        prefs.setPinsMigrated(true)

        agents.refresh()
        awaitUntil { pins.state.value.error != null }
        assertThat(attempts.get()).isEqualTo(1)

        extended = false
        pins.reset()
        // Past every wait the chain had left: had it survived, it would have run three more times by now.
        delay(700)
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(pins.state.value).isEqualTo(PinSyncState())

        extended = true
        assertThat(pins.sync().isSuccess).isFalse() // offline still; the point is that it asked again, now that it may
        assertThat(attempts.get()).isEqualTo(2)
    }

    // ---- profile ----------------------------------------------------------------------------------------------------

    @Test
    fun `off, the account is described by v1 me alone and GetMe is never called`() = runBlocking<Unit> {
        val user = session.signIn("key_abc").getOrThrow()
        delay(150)

        assertThat(profile.calls).isEqualTo(0)
        assertThat(user.profilePictureUrl).isNull()
        assertThat((session.state.value as SessionState.SignedIn).user).isEqualTo(user)
    }

    @Test
    fun `off, a picture cached by an earlier build is dropped when the key is re-validated`() = runBlocking<Unit> {
        keyStore.setApiKey("key_abc")
        prefs.setCredentialInfo(CredentialInfo(SignInMethod.ApiKey, expiresAtMs = null))
        prefs.setCachedUser(CursorUser("test", "alex@example.com", "Alex", "Rivera", null, profilePictureUrl = "https://pics.example/old.png"))

        session.restore()
        awaitUntil { (session.state.value as? SessionState.SignedIn)?.user?.profilePictureUrl == null }

        assertThat(prefs.cachedUser.first()?.profilePictureUrl).isNull()
        assertThat(profile.calls).isEqualTo(0)
    }

    @Test
    fun `on, GetMe fills in the picture as before`() = runBlocking<Unit> {
        extended = true
        session.signIn("key_abc").getOrThrow()

        awaitUntil { (session.state.value as? SessionState.SignedIn)?.user?.profilePictureUrl != null }
        assertThat(profile.calls).isEqualTo(1)
    }

    @Test
    fun `turning it off forgets the picture at once and re-describes the account from v1 me`() = runBlocking<Unit> {
        extended = true
        session.signIn("key_abc").getOrThrow()
        awaitUntil { (session.state.value as? SessionState.SignedIn)?.user?.profilePictureUrl != null }

        extended = false
        session.forgetAccountProfile()

        assertThat((session.state.value as SessionState.SignedIn).user.profilePictureUrl).isNull()
        assertThat(prefs.cachedUser.first()?.profilePictureUrl).isNull()
        awaitUntil { api.meCalls >= 2 }
        delay(100)
        assertThat(profile.calls).isEqualTo(1)
        assertThat((session.state.value as SessionState.SignedIn).user.profilePictureUrl).isNull()
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    @Test
    fun `off, archiving writes the public flag only and renaming is refused`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()

        assertThat(agents.archive("bc-1").isSuccess).isTrue()
        val rename = agents.rename("bc-1", "Something else")

        assertThat(account.calls).isEmpty()
        assertThat(api.agents.getValue("bc-1").status).isEqualTo("ARCHIVED")
        assertThat(rename.isFailure).isTrue()
        assertThat(rename.exceptionOrNull()).hasMessageThat().isEqualTo(AgentRepository.RENAME_NEEDS_EXTENDED_MODE)
        assertThat(agents.agent("bc-1")?.name).isEqualTo("One")
    }

    @Test
    fun `on, archive and rename go through the account as before`() = runBlocking<Unit> {
        extended = true
        val agents = agents()
        agents.refresh()

        assertThat(agents.archive("bc-1").isSuccess).isTrue()
        assertThat(agents.rename("bc-1", "Something else").isSuccess).isTrue()

        assertThat(account.calls).containsExactly("archive:bc-1", "rename:bc-1:Something else").inOrder()
        assertThat(agents.agent("bc-1")?.name).isEqualTo("Something else")
    }

    @Test
    fun `turning it off makes the rows forget the sources the account gave them, except the chats launched here`() = runBlocking<Unit> {
        val agents = agents()
        agents.refresh()
        agents.applySources(mapOf("bc-1" to AgentSource.SLACK, "bc-2" to AgentSource.API))
        prefs.markLaunchedHere("bc-2")

        agents.forgetAccountSources(prefs.localAgentState.first().launchedHereIds)

        assertThat(agents.agent("bc-1")?.source).isNull()
        assertThat(agents.agent("bc-2")?.source).isEqualTo(AgentSource.API)
    }

    // ---- slash commands ---------------------------------------------------------------------------------------------

    @Test
    fun `off, the catalogs come from the repository's contents and the account is never asked`() = runBlocking<Unit> {
        val accountApi = RecordingSlashApi("account")
        val repoApi = RecordingSlashApi("repo")
        val repo = SlashCommandRepository(session, accountApi, repoContents = repoApi, capabilities = capabilities)

        val catalog = repo.load(SlashScope.Repo("https://github.com/acme/web", "main"))
        val forAgent = repo.load(SlashScope.Agent("bc-1", "https://github.com/acme/web", "main"))

        assertThat(accountApi.calls).isEmpty()
        assertThat(repoApi.calls).containsExactly("repo", "agent")
        assertThat(catalog.byName("repo-skill")).isNotNull()
        assertThat(catalog.byName("account-skill")).isNull()
        assertThat(catalog.byName("repo-global")).isNull()
        assertThat(catalog.byName("goal")).isEqualTo(SlashCatalog.BUILT_IN.byName("goal"))
        assertThat(forAgent.byName("repo-skill")).isNotNull()
    }

    @Test
    fun `on, the catalogs come from the account as before`() = runBlocking<Unit> {
        extended = true
        val accountApi = RecordingSlashApi("account")
        val repoApi = RecordingSlashApi("repo")
        val repo = SlashCommandRepository(session, accountApi, repoContents = repoApi, capabilities = capabilities)

        val catalog = repo.load(SlashScope.Repo("https://github.com/acme/web", "main"))

        assertThat(repoApi.calls).isEmpty()
        assertThat(accountApi.calls).containsExactly("global", "repo")
        assertThat(catalog.byName("account-skill")).isNotNull()
        assertThat(catalog.byName("account-global")).isNotNull()
    }

    @Test
    fun `a catalog loaded under one mode is not shown under the other`() = runBlocking<Unit> {
        extended = true
        val accountApi = RecordingSlashApi("account")
        val repoApi = RecordingSlashApi("repo")
        val repo = SlashCommandRepository(session, accountApi, repoContents = repoApi, capabilities = capabilities)
        val scope = SlashScope.Repo("https://github.com/acme/web", "main")
        assertThat(repo.load(scope).byName("account-skill")).isNotNull()

        extended = false
        repo.reset()
        assertThat(repo.current(scope)).isEqualTo(SlashCatalog.BUILT_IN)
        val catalog = repo.load(scope)

        assertThat(catalog.byName("account-skill")).isNull()
        assertThat(catalog.byName("repo-skill")).isNotNull()
        assertThat(accountApi.calls).containsExactly("global", "repo")
    }

    // ---- pull requests ----------------------------------------------------------------------------------------------

    @Test
    fun `the pull request source follows the setting per lookup`() = runBlocking<Unit> {
        val asked = CopyOnWriteArrayList<String>()
        val source = CapabilityGatedPullRequestSource(
            capabilities,
            account = PullRequestSource { asked += "account:$it"; PullRequestLookup.Found(PullRequestState.Merged) },
            gitHub = PullRequestSource { asked += "github:$it"; PullRequestLookup.Found(PullRequestState.Open) },
        )

        assertThat(source.lookup("https://github.com/acme/app/pull/1")).isEqualTo(PullRequestLookup.Found(PullRequestState.Open))
        extended = true
        assertThat(source.lookup("https://github.com/acme/app/pull/1")).isEqualTo(PullRequestLookup.Found(PullRequestState.Merged))

        assertThat(asked).containsExactly("github:https://github.com/acme/app/pull/1", "account:https://github.com/acme/app/pull/1").inOrder()
    }

    // ---- the session every call above takes -------------------------------------------------------------------------

    @Test
    fun `off, no account session is started, and one minted before is not handed out again`() = runBlocking<Unit> {
        val server = MockWebServer().also { it.start() }
        try {
            var allowed = true
            val tokens = SessionTokenProvider(OkHttpClient(), { "key_abc" }, apiUrl = server.url("/").toString(), sessionAllowed = { allowed })
            server.enqueue(MockResponse().setBody("""{"accessToken":"t1","refreshToken":"rt"}"""))
            server.enqueue(MockResponse().setBody("""{"accessToken":"t2","refreshToken":"rt"}"""))
            assertThat(tokens.accessToken()).isEqualTo("t1")

            allowed = false
            val refused = runCatching { tokens.accessToken() }.exceptionOrNull() as SessionUnavailableException
            assertThat(refused.code).isEqualTo(SessionUnavailableException.EXTENDED_MODE_OFF)
            assertThat(refused.isPermanent).isTrue()
            assertThat(server.requestCount).isEqualTo(1)

            // Turned back on: the session from before the turn-off is gone, so a new exchange is made.
            allowed = true
            assertThat(tokens.accessToken()).isEqualTo("t2")
            assertThat(server.requestCount).isEqualTo(2)
        } finally {
            server.shutdown()
        }
    }
}
