package com.cursorforandroid.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The installed version's notes against a mock GitHub: one request per version, remembered on disk; what a tag
 * without a release, a failed request and a CI build do; and the read state that hides the surfaces until the next
 * version. Robolectric only for [PreferencesStore].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WhatsNewRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferencesStore
    private lateinit var cacheDir: File
    private var now = 1_788_900_000_000L

    /** Tag -> the response for it; anything else is a 404. Appended from the server's thread while tests assert. */
    private val responses = HashMap<String, () -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        prefs = PreferencesStore(context)
        cacheDir = folder.newFolder("whats-new")
        responses["v0.3.37"] = { MockResponse().setBody(WhatsNewFixtures.releaseJson()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val tag = request.path?.substringAfter("/releases/tags/", "") ?: ""
                return responses[tag]?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repository(version: String = "0.3.37"): WhatsNewRepository = WhatsNewRepository(
        client = GitHubReleasesClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            GitHubFixtures.OWNER_REPO,
            apiBaseUrl = server.url("/").toString(),
            now = { now },
            freeSpace = { Long.MAX_VALUE / 2 },
        ),
        prefs = prefs,
        cache = JsonDiskCache(cacheDir, dispatcher = Dispatchers.Unconfined),
        installedVersionName = version,
        now = { now },
    )

    private fun paths() = requests.map { it.path }

    @Test
    fun `the installed version's notes are read by its tag once, kept on disk, and never asked for again`() = runBlocking {
        val repo = repository()
        assertThat(repo.notes.value).isNull()
        assertThat(repo.tagName).isEqualTo("v0.3.37")

        repo.refreshNow()

        val notes = repo.notes.value!!
        assertThat(notes).isEqualTo(WhatsNewFixtures.notes())
        assertThat(paths()).containsExactly("/repos/BenItBuhner/cursor-for-android/releases/tags/v0.3.37")

        // Again in the same process, and in the next one: the disk answers.
        repo.refreshNow()
        val next = repository()
        next.refreshNow()
        assertThat(next.notes.value).isEqualTo(notes)
        assertThat(paths()).hasSize(1)
    }

    @Test
    fun `a tag with no release, or one whose notes are not written yet, is asked about again after a while`() = runBlocking {
        responses["v0.3.37"] = { MockResponse().setBody(WhatsNewFixtures.releaseJson(body = WhatsNewFixtures.BODY_WITHOUT_NOTES)) }
        val repo = repository()

        repo.refreshNow()
        assertThat(repo.notes.value).isNull()
        assertThat(paths()).hasSize(1)

        // Within the interval nothing goes out, in this process or the next.
        now += WhatsNewRepository.NO_NOTES_RETRY_MS / 2
        repo.refreshNow()
        repository().refreshNow()
        assertThat(paths()).hasSize(1)

        // Past it, the question is asked again — and this time the notes are there.
        now += WhatsNewRepository.NO_NOTES_RETRY_MS
        responses["v0.3.37"] = { MockResponse().setBody(WhatsNewFixtures.releaseJson()) }
        val later = repository()
        later.refreshNow()
        assertThat(later.notes.value).isEqualTo(WhatsNewFixtures.notes())
        assertThat(paths()).hasSize(2)

        // A version that was never released at all reads the same way, from a 404.
        val unreleased = repository("0.3.99")
        unreleased.refreshNow()
        assertThat(unreleased.notes.value).isNull()
        assertThat(paths().last()).endsWith("/releases/tags/v0.3.99")
    }

    @Test
    fun `a failed request leaves the notes as they were and is retried an hour later at the earliest`() = runBlocking {
        responses["v0.3.37"] = { MockResponse().setResponseCode(500) }
        val repo = repository()

        repo.refreshNow()
        assertThat(repo.notes.value).isNull()
        assertThat(paths()).hasSize(1)
        // Nothing was written: a failure is not a "no notes" verdict.
        assertThat(cacheDir.listFiles()!!.filter { it.isFile }).isEmpty()

        now += WhatsNewRepository.FAILED_RETRY_MS / 2
        repo.refreshNow()
        assertThat(paths()).hasSize(1)

        now += WhatsNewRepository.FAILED_RETRY_MS
        responses["v0.3.37"] = { MockResponse().setBody(WhatsNewFixtures.releaseJson()) }
        repo.refreshNow()
        assertThat(repo.notes.value).isEqualTo(WhatsNewFixtures.notes())
        assertThat(paths()).hasSize(2)
    }

    @Test
    fun `GitHub's rate limit is a failure like any other, nothing is written for it, and the hour is waited out`() = runBlocking {
        responses["v0.3.37"] = { MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setBody("""{"message":"API rate limit exceeded"}""") }
        val repo = repository()

        repo.refreshNow()
        assertThat(repo.notes.value).isNull()
        assertThat(cacheDir.listFiles()!!.filter { it.isFile }).isEmpty()
        now += WhatsNewRepository.FAILED_RETRY_MS - 1
        repo.refreshNow()
        assertThat(paths()).hasSize(1)

        // The hour GitHub named is up at the same time as the repository's own interval; a refusal then is still no verdict.
        now += 1
        repo.refreshNow()
        assertThat(paths()).hasSize(2)
        assertThat(repo.notes.value).isNull()
        assertThat(cacheDir.listFiles()!!.filter { it.isFile }).isEmpty()
    }

    @Test
    fun `a CI build with build metadata has no release to look for and asks nothing`() = runBlocking {
        val repo = repository("0.3.38-dev.42+gabc1234")

        repo.refreshNow()

        assertThat(repo.notes.value).isNull()
        assertThat(paths()).isEmpty()
        assertThat(repo.unread.first()).isNull()
    }

    @Test
    fun `the notes are unread until the page is opened, and unread again for the next installed version`() = runBlocking {
        val repo = repository()
        assertThat(repo.unread.first()).isNull()

        repo.refreshNow()
        assertThat(repo.unread.first()).isEqualTo(WhatsNewFixtures.notes())

        repo.markRead()
        assertThat(prefs.whatsNewReadVersion.first()).isEqualTo("0.3.37")
        assertThat(repo.unread.first()).isNull()
        // Still known, still read, in the next process.
        val restarted = repository()
        restarted.refreshNow()
        assertThat(restarted.notes.value).isEqualTo(WhatsNewFixtures.notes())
        assertThat(restarted.unread.first()).isNull()

        // The next release installs: its notes replace the previous version's on disk and are unread once more.
        responses["v0.3.38"] = { MockResponse().setBody(WhatsNewFixtures.releaseJson(tag = "v0.3.38")) }
        val updated = repository("0.3.38")
        updated.refreshNow()
        assertThat(updated.notes.value!!.versionName).isEqualTo("0.3.38")
        assertThat(updated.unread.first()!!.versionName).isEqualTo("0.3.38")
        assertThat(paths().last()).endsWith("/releases/tags/v0.3.38")

        updated.markRead()
        assertThat(updated.unread.first()).isNull()
        assertThat(prefs.whatsNewReadVersion.first()).isEqualTo("0.3.38")
    }
}
