package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.CatalogCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
    fun `saved catalogs show first and the rate-limited repository call is skipped while they are fresh`() = runBlocking<Unit> {
        cache.writeModels(listOf(ModelOption("claude", "Claude")))
        cache.writeRepositories(listOf(Repository("https://github.com/acme/app")))
        now += 10 * 60 * 1000
        api.modelItems = listOf(ModelListItemDto(id = "claude", displayName = "Claude"), ModelListItemDto(id = "gpt", displayName = "GPT"))
        api.repositoryUrls = listOf("https://github.com/acme/app", "https://github.com/acme/web")
        val catalog = CatalogRepository(session, cache)

        catalog.restoreFromCache()
        assertThat(catalog.models.value.map { it.id }).containsExactly("claude")
        assertThat(catalog.repositories.value.map { it.shortName }).containsExactly("app")

        // Repositories were fetched ten minutes ago: still inside the TTL, so no request is spent.
        assertThat(catalog.loadRepositories().getOrThrow().map { it.shortName }).containsExactly("app")
        assertThat(api.repositoriesCalls).isEqualTo(0)
        // Models are revalidated once per session and the fresh list replaces the saved one.
        assertThat(catalog.loadModels().getOrThrow().map { it.id }).containsExactly("claude", "gpt").inOrder()
        assertThat(catalog.loadModels().getOrThrow()).hasSize(2)
        assertThat(api.modelsCalls).isEqualTo(1)
        assertThat(cache.readModels()!!.value.map { it.id }).containsExactly("claude", "gpt").inOrder()

        // Past the TTL the repositories are fetched and saved again.
        now += 30 * 60 * 1000
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
        assertThat(catalog.loadRepositories().exceptionOrNull()!!.userMessage()).isEqualTo("Rate limited by Cursor. Try again in a moment.")
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

        // A forced refresh does skip the half-hour freshness window, once the minute is up.
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
}
