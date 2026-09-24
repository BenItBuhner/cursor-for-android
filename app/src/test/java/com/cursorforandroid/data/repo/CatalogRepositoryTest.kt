package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ApiThrottle
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.PoolDto
import com.cursorforandroid.data.api.dto.WorkerDto
import com.cursorforandroid.data.api.dto.WorkerLabelDto
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.CatalogCache
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelSlugs
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CatalogRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private var now = 1_800_000_000_000L
    private lateinit var session: SessionManager
    private lateinit var cache: CatalogCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val backend = CursorBackend(api, FakeRunStreamer(), isDemo = false)
        session = SessionManager(SecureKeyStore(context), PreferencesStore(context), backend, CursorBackend(api, FakeRunStreamer(), isDemo = true))
        cache = CatalogCache(JsonDiskCache(folder.newFolder("catalog"), nowProvider = { now }, dispatcher = Dispatchers.Unconfined))
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `saved catalogs show first and neither is fetched again while it is fresh`() = runBlocking<Unit> {
        cache.writeModels(listOf(ModelOption("claude", "Claude")))
        cache.writeRepositories(listOf(Repository("https://github.com/acme/app")))
        now += 5 * 60 * 1000
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"), ModelListItemDto(id = "gpt", displayName = "GPT"))
        api.repositoryUrls = listOf("https://github.com/acme/app", "https://github.com/acme/web")
        val catalog = CatalogRepository(session, cache)

        catalog.restoreFromCache()
        assertThat(catalog.models.value.map { it.id }).containsExactly("claude")
        assertThat(catalog.repositories.value.map { it.shortName }).containsExactly("app")

        // Both were saved five minutes ago: inside the one freshness window, so no request is spent on either.
        assertThat(catalog.loadRepositories().getOrThrow().map { it.shortName }).containsExactly("app")
        assertThat(catalog.loadModels().getOrThrow().map { it.id }).containsExactly("claude")
        assertThat(api.repositoriesCalls).isEqualTo(0)
        assertThat(api.modelsCalls).isEqualTo(0)

        // Once stale, each is fetched again, replaces the saved one and is saved in its stead.
        now += CatalogRefresher.STALE_AFTER_MS
        assertThat(catalog.loadModels().getOrThrow().map { it.id }).containsExactly("claude", "gpt").inOrder()
        assertThat(catalog.loadModels().getOrThrow()).hasSize(2)
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(cache.readModels()!!.value.map { it.id }).containsExactly("claude", "gpt").inOrder()
        assertThat(catalog.loadRepositories().getOrThrow().map { it.shortName }).containsExactly("app", "web").inOrder()
        assertThat(api.repositoriesCalls).isEqualTo(1)
        assertThat(cache.readRepositories()!!.value).hasSize(2)
    }

    @Test
    fun `a failed refresh keeps the saved catalog instead of failing`() = runBlocking<Unit> {
        cache.writeModels(listOf(ModelOption("claude", "Claude")))
        api.failModels = IOException("down")
        val catalog = CatalogRepository(session, cache)
        assertThat(catalog.loadModels().getOrThrow().map { it.id }).containsExactly("claude")
        // Without anything saved the failure surfaces.
        api.failRepositories = IOException("down")
        assertThat(catalog.loadRepositories().isFailure).isTrue()
    }

    @Test
    fun `a failing repository fetch still spends the minute, so it is not retried without limit`() = runBlocking<Unit> {
        api.failRepositories = IOException("down")
        val catalog = CatalogRepository(session, cache)

        assertThat(catalog.loadRepositories().isFailure).isTrue()
        // The endpoint allows one request a minute whether it answered or not, so the next two are refused here.
        assertThat(catalog.loadRepositories().exceptionOrNull()!!.userMessage()).isEqualTo("Rate limited by Cursor: Cursor allows one repository refresh a minute. Try again in a moment.")
        assertThat(catalog.loadRepositories(force = true).isFailure).isTrue()
        assertThat(api.repositoriesCalls).isEqualTo(1)

        // Past the minute the next attempt goes out, and succeeds.
        now += 65 * 1000
        api.failRepositories = null
        api.repositoryUrls = listOf("https://github.com/acme/app")
        assertThat(catalog.loadRepositories().getOrThrow().map { it.shortName }).containsExactly("app")
        assertThat(api.repositoriesCalls).isEqualTo(2)
    }

    @Test
    fun `a forced refresh inside the minute returns what is shown instead of spending a request`() = runBlocking<Unit> {
        api.repositoryUrls = listOf("https://github.com/acme/app")
        val catalog = CatalogRepository(session, cache)

        assertThat(catalog.loadRepositories().getOrThrow()).hasSize(1)
        api.repositoryUrls = listOf("https://github.com/acme/app", "https://github.com/acme/web")
        assertThat(catalog.loadRepositories(force = true).getOrThrow()).hasSize(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)

        // A forced refresh does skip the freshness window, once the minute is up.
        now += 65 * 1000
        assertThat(catalog.loadRepositories(force = true).getOrThrow()).hasSize(2)
        assertThat(api.repositoriesCalls).isEqualTo(2)
    }

    @Test
    fun `a Retry-After the server sends outlasts the app's own minute`() = runBlocking<Unit> {
        api.failRepositories = throttled(retryAfterSeconds = 300)
        val catalog = CatalogRepository(session, cache)

        assertThat(catalog.loadRepositories().isFailure).isTrue()
        now += 65 * 1000
        assertThat(catalog.loadRepositories(force = true).isFailure).isTrue()
        assertThat(api.repositoriesCalls).isEqualTo(1)

        now += 5 * 60 * 1000
        api.failRepositories = null
        api.repositoryUrls = listOf("https://github.com/acme/app")
        assertThat(catalog.loadRepositories().getOrThrow()).hasSize(1)
        assertThat(api.repositoriesCalls).isEqualTo(2)
    }

    @Test
    fun `two callers arriving together cost one request between them`() = runBlocking<Unit> {
        api.repositoryUrls = listOf("https://github.com/acme/app")
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        api.repositoriesGate = CompletableDeferred()
        api.modelsGate = CompletableDeferred()
        val catalog = CatalogRepository(session, cache)

        val repos = listOf(async { catalog.loadRepositories() }, async { catalog.loadRepositories() })
        val models = listOf(async { catalog.loadModels() }, async { catalog.loadModels() })
        yield()

        // The slow repository fetch does not hold up the models one.
        api.modelsGate!!.complete(Unit)
        models.forEach { assertThat(it.await().getOrThrow().map { m -> m.id }).containsExactly("claude") }
        assertThat(api.modelsCalls).isEqualTo(1)

        api.repositoriesGate!!.complete(Unit)
        repos.forEach { assertThat(it.await().getOrThrow().map { r -> r.shortName }).containsExactly("app") }
        assertThat(api.repositoriesCalls).isEqualTo(1)
    }

    private fun throttled(retryAfterSeconds: Long): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.cursor.com/v1/repositories").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", retryAfterSeconds.toString())
            .build()
        return HttpException(
            Response.error<Unit>("""{"error":{"code":"rate_limited","message":"Slow down."}}""".toResponseBody("application/json".toMediaType()), raw),
        )
    }

    @Test
    fun `catalogs fetched for the previous account never reach the next one's pickers or disk`() = runBlocking<Unit> {
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        api.repositoryUrls = listOf("https://github.com/previous/app")
        api.modelsGate = CompletableDeferred()
        api.repositoriesGate = CompletableDeferred()
        val catalog = CatalogRepository(session, cache)

        val models = async { catalog.loadModels() }
        val repos = async { catalog.loadRepositories() }
        yield()
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)

        catalog.reset()
        api.modelsGate!!.complete(Unit)
        api.repositoriesGate!!.complete(Unit)
        models.await()
        repos.await()

        assertThat(catalog.models.value).isEmpty()
        assertThat(catalog.repositories.value).isEmpty()
        assertThat(cache.readModels()).isNull()
        assertThat(cache.readRepositories()).isNull()

        // The next account's own fetch lands as usual.
        assertThat(catalog.loadModels().getOrThrow().map { it.id }).containsExactly("claude")
        assertThat(catalog.models.value.map { it.id }).containsExactly("claude")
    }

    @Test
    fun `the saved repository list replaces one merely seeded from the agent list`() = runBlocking<Unit> {
        cache.writeRepositories(listOf(Repository("https://github.com/acme/app"), Repository("https://github.com/acme/web")))
        val catalog = CatalogRepository(session, cache)
        catalog.seedRepositories(listOf("https://github.com/acme/app"))
        assertThat(catalog.repositories.value).hasSize(1)
        catalog.restoreFromCache()
        assertThat(catalog.repositories.value).hasSize(2)
        assertThat(catalog.loadRepositories().getOrThrow()).hasSize(2)
        assertThat(api.repositoriesCalls).isEqualTo(0)
    }

    /** [AppClock] on the test's virtual clock, so [CatalogRepository.keepFresh]'s sleeps and the catalogs' ages agree. */
    private fun TestScope.clockOnVirtualTime() {
        val base = now
        AppClock.nowMillis = { base + testScheduler.currentTime }
    }

    private fun TestScope.passes(catalog: CatalogRepository, allowed: () -> Boolean = { true }) {
        backgroundScope.launch { catalog.keepFresh(allowed) }
        runCurrent()
    }

    @Test
    fun `in the foreground each catalog is fetched again as it goes stale, and a model announced meanwhile turns up`() = runTest {
        session.signIn("key_test").getOrThrow()
        clockOnVirtualTime()
        api.modelItems = listOf(ModelListItemDto(id = "claude-sonnet-4.6", displayName = "Claude Sonnet 4.6"))
        api.repositoryUrls = listOf("https://github.com/acme/app")
        val catalog = CatalogRepository(session, cache)

        passes(catalog)
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)

        // Cursor announces a model and the user connects a repository; nothing is asked while the lists are fresh.
        api.modelItems += ModelListItemDto(id = "claude-opus-5.5", displayName = "Claude Opus 5.5")
        api.repositoryUrls += "https://github.com/acme/web"
        advanceTimeBy(CatalogRefresher.STALE_AFTER_MS - 1)
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)

        advanceTimeBy(2)
        assertThat(api.modelsCalls).isEqualTo(2)
        assertThat(api.repositoriesCalls).isEqualTo(2)
        assertThat(catalog.models.value.map { it.id }).containsExactly("claude-sonnet-4.6", "claude-opus-5.5").inOrder()
        assertThat(catalog.repositories.value.map { it.shortName }).containsExactly("app", "web").inOrder()
        // The #306 normalizer places the new model's slugs against the refreshed list, as it does any other's.
        assertThat(ModelSlugs.resolve(catalog.models.value, "claude-5-5-opus")?.model?.id).isEqualTo("claude-opus-5.5")
        assertThat(cache.readModels()!!.value.map { it.id }).contains("claude-opus-5.5")
    }

    @Test
    fun `a pass skips what another caller has just brought`() = runTest {
        session.signIn("key_test").getOrThrow()
        clockOnVirtualTime()
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        api.repositoryUrls = listOf("https://github.com/acme/app")
        val catalog = CatalogRepository(session, cache)
        catalog.loadModels().getOrThrow()
        catalog.loadRepositories().getOrThrow()

        catalog.revalidateDue()
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)
        assertThat(catalog.nextPassIn(AppClock.now())).isEqualTo(CatalogRefresher.STALE_AFTER_MS)
    }

    @Test
    fun `no pass goes out without the device's say-so, signed out, or while the account service's throttle holds every call`() = runTest {
        clockOnVirtualTime()
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        val throttle = ApiThrottle(now = AppClock::now)
        val catalog = CatalogRepository(session, cache, throttlePausedUntil = throttle::pausedUntil)

        // Signed out: nothing to fetch for.
        catalog.revalidateDue()
        assertThat(api.modelsCalls).isEqualTo(0)

        session.signIn("key_test").getOrThrow()
        // No connection, a low battery, power saving: the loop wakes but asks nothing.
        var allowed = false
        passes(catalog) { allowed }
        advanceTimeBy(3 * CatalogRefresher.STALE_AFTER_MS)
        assertThat(api.modelsCalls).isEqualTo(0)
        assertThat(api.repositoriesCalls).isEqualTo(0)

        // A 429 elsewhere pauses every caller: the pass waits it out rather than add to it.
        allowed = true
        throttle.pause(10_000)
        catalog.revalidateDue()
        assertThat(api.modelsCalls).isEqualTo(0)
        advanceTimeBy(10_001)
        catalog.revalidateDue()
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(api.repositoriesCalls).isEqualTo(1)
    }

    @Test
    fun `a picker's own fetch waits out the account service's pause before it goes out`() = runTest {
        session.signIn("key_test").getOrThrow()
        clockOnVirtualTime()
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        val throttle = ApiThrottle(now = AppClock::now)
        val catalog = CatalogRepository(session, cache, throttlePausedUntil = throttle::pausedUntil)

        throttle.pause(4_000)
        val started = testScheduler.currentTime
        assertThat(catalog.loadModels(force = true).getOrThrow()).hasSize(1)
        assertThat(testScheduler.currentTime - started).isAtLeast(4_000L)
        assertThat(api.modelsCalls).isEqualTo(1)
    }

    @Test
    fun `a catalog that keeps failing is asked less and less often, never every pass`() = runTest {
        session.signIn("key_test").getOrThrow()
        clockOnVirtualTime()
        api.failModels = IOException("down")
        api.repositoryUrls = listOf("https://github.com/acme/app")
        val catalog = CatalogRepository(session, cache)

        passes(catalog)
        advanceTimeBy(CatalogRefresher.STALE_AFTER_MS - 1)
        // At 0, 1, 3 and 7 minutes: the wait doubles after each failure, where a pass a minute would have asked ten times.
        assertThat(api.modelsCalls).isEqualTo(4)
        // The healthy one is not dragged along.
        assertThat(api.repositoriesCalls).isEqualTo(1)

        api.failModels = null
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        advanceTimeBy(8 * 60 * 1000L)
        assertThat(catalog.models.value.map { it.id }).containsExactly("claude")
    }

    @Test
    fun `an account with nothing connected is not asked for its repositories every minute`() = runTest {
        session.signIn("key_test").getOrThrow()
        clockOnVirtualTime()
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"))
        api.repositoryUrls = emptyList()
        val catalog = CatalogRepository(session, cache)

        passes(catalog)
        advanceTimeBy(CatalogRefresher.STALE_AFTER_MS - 1)
        assertThat(api.repositoriesCalls).isEqualTo(1)
        assertThat(catalog.loadRepositories().getOrThrow()).isEmpty()
        assertThat(api.repositoriesCalls).isEqualTo(1)
    }

    @Test
    fun `live workers and pools are listed and a failed fleet call is empty rather than fatal`() = runBlocking<Unit> {
        api.workers = listOf(WorkerDto(name = "studio", displayName = "Studio", isInUse = false, repoName = "app", scope = "personal"))
        api.pools = listOf(PoolDto(name = "gpu", connectedWorkerCount = 2, inUseWorkerCount = 1))
        val catalog = CatalogRepository(session, cache)

        val listed = catalog.loadDevices().getOrThrow()
        assertThat(listed.map { it.target }).containsExactly(DeviceTarget.machine("studio"), DeviceTarget.pool("gpu")).inOrder()
        assertThat(listed[0].online).isTrue()
        // A name without an owner is no repository: "Empty strings for any-repo workers" is what the fleet sends for those.
        assertThat(listed[0].subtitle).isEqualTo("Online")
        assertThat(listed[0].repoUrl).isNull()
        assertThat(listed[1].subtitle).isEqualTo("2 connected · 1 in use")
        assertThat(listed[1].repoUrl).isNull()

        api.failWorkers = IOException("forbidden")
        api.failPools = IOException("forbidden")
        assertThat(catalog.loadDevices().getOrThrow()).isEmpty()
    }

    /**
     * `GET /v0/private-workers` names each machine's primary checkout (`repoUrl`, else `repoOwner`/`repoName`, and
     * `workspaceRootPath`); `GET /v0/private-workers/pools` names the repository a pool is tied to. Both ride on the
     * picker's rows so the composer's repository can follow the device; an any-repo pool pins none.
     */
    @Test
    fun `workers and pools carry the repository they are checked out at`() = runBlocking<Unit> {
        api.workers = listOf(
            WorkerDto(workerId = "a8574fe8", name = "bennett", isInUse = true, repoOwner = "bennett", repoName = "codex-poly-bot", repoUrl = "https://github.com/bennett/codex-poly-bot", workspaceRootPath = "/home/bennett/projects/codex-poly-bot", scope = "personal"),
            WorkerDto(workerId = "b1", name = "devbox", isInUse = false, repoOwner = "acme", repoName = "infra", scope = "personal"),
        )
        api.pools = listOf(
            PoolDto(name = "payments", connectedWorkerCount = 1, inUseWorkerCount = 0, repoOwner = "acme", repoName = "payments-service", repoUrl = "https://github.com/acme/payments-service"),
            PoolDto(name = "sandbox", connectedWorkerCount = 0),
        )
        val listed = CatalogRepository(session, cache).loadDevices().getOrThrow()

        val bennett = listed.first { it.target == DeviceTarget.machine("bennett") }
        assertThat(bennett.repoUrl).isEqualTo("https://github.com/bennett/codex-poly-bot")
        assertThat(bennett.subtitle).isEqualTo("Busy · /home/bennett/projects/codex-poly-bot")
        val devbox = listed.first { it.target == DeviceTarget.machine("devbox") }
        assertThat(devbox.repoUrl).isEqualTo("https://github.com/acme/infra")
        assertThat(devbox.subtitle).isEqualTo("acme/infra")
        assertThat(listed.first { it.target == DeviceTarget.pool("payments") }.repoUrl).isEqualTo("https://github.com/acme/payments-service")
        assertThat(listed.first { it.target == DeviceTarget.pool("sandbox") }.repoUrl).isNull()
    }

    /**
     * Each machine's row carries its worker the way the desktop keeps one (`RRe`): the id, the `name` label over the
     * listed name (`Ael`), the repository it registered, the owner. A machine a later listing leaves out — gone
     * offline — is still known by the worker it was last listed as, in this session and, from disk, the next.
     */
    @Test
    fun `a machine's worker rides on its row and outlives the machine going offline`() = runBlocking<Unit> {
        api.workers = listOf(
            WorkerDto(workerId = "5f1c9d2a", name = "bennett", repoOwner = "bennett", repoName = "codex-poly-bot", workspaceRootPath = "/home/bennett/projects/codex-poly-bot", userId = 42, scope = "personal"),
            WorkerDto(workerId = "9e8d7c6b", name = "Studio.local", repoOwner = "", repoName = "", userId = 42, labels = listOf(WorkerLabelDto("name", "studio")), scope = "personal"),
        )
        val catalog = CatalogRepository(session, cache)
        val listed = catalog.loadDevices().getOrThrow()

        assertThat(listed.first { it.target == DeviceTarget.machine("bennett") }.worker)
            .isEqualTo(MachineWorker(workerId = "5f1c9d2a", name = "bennett", repoLabel = "bennett/codex-poly-bot", ownerUserId = 42))
        assertThat(listed.first { it.target == DeviceTarget.machine("Studio.local") }.worker)
            .isEqualTo(MachineWorker(workerId = "9e8d7c6b", name = "studio", repoLabel = null, ownerUserId = 42))

        api.workers = emptyList()
        assertThat(catalog.loadDevices().getOrThrow()).isEmpty()
        assertThat(catalog.lastSeenWorker(DeviceTarget.machine("bennett"))?.workerId).isEqualTo("5f1c9d2a")
        assertThat(CatalogRepository(session, cache).also { it.loadDevices() }.lastSeenWorker(DeviceTarget.machine("bennett"))?.workerId).isEqualTo("5f1c9d2a")
    }
}
