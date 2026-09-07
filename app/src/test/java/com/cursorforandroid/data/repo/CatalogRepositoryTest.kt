package com.cursorforandroid.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.local.CatalogCache
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.Repository
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
