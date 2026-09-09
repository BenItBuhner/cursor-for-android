package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.SlashCommandApi
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.local.SlashCommandCache
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommand.Kind
import com.cursorforandroid.domain.SlashCommand.Origin
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SlashCommandRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSlashApi : SlashCommandApi {
        var repoCatalog = SlashCatalog(listOf(SlashCommand("deploy", "Deploy the app", origin = Origin.Project)))
        var agentCatalog = SlashCatalog(listOf(SlashCommand("release", "Cut a release", Kind.Command, Origin.Project)), pending = true)
        var globals: List<SlashCommand> = listOf(SlashCommand("goal", "Set a goal that Cursor will pursue", Kind.Command), SlashCommand("standup", "Standup notes", Kind.Command))
        var failScoped: Throwable? = null
        var failGlobal: Throwable? = null
        val repoCalls = mutableListOf<Pair<String, String?>>()
        val agentCalls = mutableListOf<Triple<String, String?, String?>>()
        var globalCalls = 0

        override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog {
            repoCalls += repoUrl to ref
            failScoped?.let { throw it }
            return repoCatalog
        }

        override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog {
            agentCalls += Triple(agentId, repoUrl, ref)
            failScoped?.let { throw it }
            return agentCatalog
        }

        override suspend fun global(): List<SlashCommand> {
            globalCalls++
            failGlobal?.let { throw it }
            return globals
        }
    }

    private val api = FakeSlashApi()
    private var now = 1_800_000_000_000L
    private lateinit var session: SessionManager
    private lateinit var cache: SlashCommandCache
    private val repoScope = SlashScope.Repo("https://github.com/acme/web", "main")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cursorApi = FakeCursorApi()
        val backend = CursorBackend(cursorApi, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), PreferencesStore(context), backend, CursorBackend(cursorApi, FakeRunStreamer(), isDemo = true))
        cache = SlashCommandCache(JsonDiskCache(folder.newFolder("slash"), nowProvider = { now }, dispatcher = Dispatchers.Unconfined))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `a repository's catalog is the built-ins under the global commands under the repository's own, saved for next time`() = runBlocking<Unit> {
        val repo = SlashCommandRepository(session, api, cache)
        assertThat(repo.current(repoScope)).isEqualTo(SlashCatalog.BUILT_IN)

        val catalog = repo.load(repoScope)

        assertThat(api.repoCalls).containsExactly("https://github.com/acme/web" to "main")
        assertThat(api.globalCalls).isEqualTo(1)
        assertThat(catalog.byName("goal")!!.description).isEqualTo("Set a goal that Cursor will pursue")
        assertThat(catalog.byName("goal")!!.origin).isEqualTo(Origin.BuiltIn)
        assertThat(catalog.byName("standup")).isEqualTo(SlashCommand("standup", "Standup notes", Kind.Command))
        assertThat(catalog.entries.last()).isEqualTo(SlashCommand("deploy", "Deploy the app", origin = Origin.Project))
        assertThat(catalog.byName("review")).isEqualTo(SlashCatalog.BUILT_IN.byName("review"))
        assertThat(repo.current(repoScope)).isEqualTo(catalog)
        assertThat(cache.read(repoScope.key)!!.value).isEqualTo(catalog)

        // Fresh: another load spends nothing, on either call.
        repo.load(repoScope)
        assertThat(api.repoCalls).hasSize(1)
        assertThat(api.globalCalls).isEqualTo(1)

        // Another repository asks for its own list but reuses the global commands.
        repo.load(SlashScope.Repo("https://github.com/acme/api", null))
        assertThat(api.repoCalls).hasSize(2)
        assertThat(api.globalCalls).isEqualTo(1)

        // Past the TTL the repository's list is asked for again.
        now += SlashCommandRepository.TTL_MS + 1
        repo.load(repoScope)
        assertThat(api.repoCalls).hasSize(3)
    }

    @Test
    fun `a saved catalog is published before the network is asked, and stands in when it is fresh`() = runBlocking<Unit> {
        val saved = SlashCatalog.BUILT_IN.mergedWith(SlashCatalog(listOf(SlashCommand("older", "From last time", origin = Origin.Project))))
        cache.write(repoScope.key, saved)
        now += 60_000
        val repo = SlashCommandRepository(session, api, cache)

        assertThat(repo.load(repoScope)).isEqualTo(saved)
        assertThat(api.repoCalls).isEmpty()
        assertThat(repo.current(repoScope)).isEqualTo(saved)

        // Stale: the saved copy shows, then the fetch replaces it.
        now += SlashCommandRepository.TTL_MS
        val fresh = repo.load(repoScope)
        assertThat(api.repoCalls).hasSize(1)
        assertThat(fresh.byName("older")).isNull()
        assertThat(fresh.byName("deploy")).isNotNull()
    }

    @Test
    fun `an agent's pending inventory is asked for again soon, a settled one keeps the usual TTL`() = runBlocking<Unit> {
        val repo = SlashCommandRepository(session, api, cache)
        val scope = SlashScope.Agent("bc-1", "https://github.com/acme/web", "main")

        val first = repo.load(scope)
        assertThat(api.agentCalls).containsExactly(Triple("bc-1", "https://github.com/acme/web", "main"))
        assertThat(first.pending).isTrue()
        assertThat(first.byName("release")!!.kind).isEqualTo(Kind.Command)

        now += SlashCommandRepository.PENDING_TTL_MS + 1
        api.agentCatalog = SlashCatalog(listOf(SlashCommand("release", "Cut a release", Kind.Command, Origin.Project), SlashCommand("deploy", "Deploy", origin = Origin.Project)))
        val second = repo.load(scope)
        assertThat(api.agentCalls).hasSize(2)
        assertThat(second.pending).isFalse()
        assertThat(second.byName("deploy")).isNotNull()

        now += SlashCommandRepository.PENDING_TTL_MS + 1
        repo.load(scope)
        assertThat(api.agentCalls).hasSize(2)
    }

    @Test
    fun `a refused account service leaves the built-ins and the global commands, and is left alone for a while`() = runBlocking<Unit> {
        api.failScoped = ConnectRpcException(403, "permission_denied", "Error")
        val repo = SlashCommandRepository(session, api, cache)

        val catalog = repo.load(repoScope)

        assertThat(catalog.byName("goal")!!.description).isEqualTo("Set a goal that Cursor will pursue")
        assertThat(catalog.byName("deploy")).isNull()
        assertThat(catalog.byName("review")).isNotNull()
        assertThat(api.repoCalls).hasSize(1)

        // Another scope inside the backoff does not spend a call on the scoped list.
        repo.load(SlashScope.Repo("https://github.com/acme/api", null))
        assertThat(api.repoCalls).hasSize(1)

        // Once the backoff has passed (and the catalog is stale) it is tried again.
        api.failScoped = null
        now += SlashCommandRepository.TTL_MS + 1
        assertThat(repo.load(repoScope).byName("deploy")).isNotNull()
        assertThat(api.repoCalls).hasSize(2)
    }

    @Test
    fun `everything failing keeps the built-ins and saves nothing`() = runBlocking<Unit> {
        api.failScoped = ConnectRpcException(401, "unauthenticated", "Error")
        api.failGlobal = ConnectRpcException(401, "unauthenticated", "Error")
        val repo = SlashCommandRepository(session, api, cache)

        assertThat(repo.load(repoScope)).isEqualTo(SlashCatalog.BUILT_IN)
        assertThat(cache.read(repoScope.key)).isNull()
        assertThat(repo.load(SlashScope.None)).isEqualTo(SlashCatalog.BUILT_IN)
        assertThat(api.globalCalls).isEqualTo(1)
    }

    @Test
    fun `no repository means the global commands only, never saved to disk`() = runBlocking<Unit> {
        val repo = SlashCommandRepository(session, api, cache)

        val catalog = repo.load(SlashScope.None)

        assertThat(api.repoCalls).isEmpty()
        assertThat(catalog.byName("standup")).isNotNull()
        assertThat(catalog.byName("deploy")).isNull()
        assertThat(cache.read(SlashScope.None.key)).isNull()
    }

    @Test
    fun `the demo backend offers the demo's project skills and never calls the account`() = runBlocking<Unit> {
        session.enterDemo()
        val repo = SlashCommandRepository(session, api, cache)

        val forRepo = repo.load(repoScope)
        val forAgent = repo.load(SlashScope.Agent("demo-1", null, null))

        assertThat(api.repoCalls).isEmpty()
        assertThat(api.globalCalls).isEqualTo(0)
        assertThat(forRepo.byName("chat-sdk")!!.origin).isEqualTo(Origin.Plugin)
        assertThat(forRepo.byName("triage")).isNull()
        assertThat(forAgent.byName("triage")!!.kind).isEqualTo(Kind.Command)
        assertThat(forRepo.search("go").map { it.name }).containsExactly("goal", "chat-sdk").inOrder()
    }

    @Test
    fun `reset forgets what is in memory, the saved copy stands until the caches are wiped`() = runBlocking<Unit> {
        val repo = SlashCommandRepository(session, api, cache)
        val fetched = repo.load(repoScope)

        repo.reset()

        assertThat(repo.current(repoScope)).isEqualTo(SlashCatalog.BUILT_IN)
        // Signing out wipes the disk with everything else; a bare reset (a backend swap) reads the copy back.
        assertThat(repo.load(repoScope)).isEqualTo(fetched)
        assertThat(api.globalCalls).isEqualTo(1)

        cache.clear()
        repo.reset()
        repo.load(repoScope)
        assertThat(api.globalCalls).isEqualTo(2)
        assertThat(api.repoCalls).hasSize(2)
    }
}
