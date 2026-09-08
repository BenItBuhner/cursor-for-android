package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.GitHubApiFactory
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PullRequestCache
import com.cursorforandroid.domain.PullRequestRef
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestStatus
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PullRequestRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val responses = mutableMapOf<String, () -> MockResponse>()
    private var now = 1_800_000_000_000L
    private var token: String? = null
    private var demo = false
    private lateinit var cache: PullRequestCache
    private lateinit var gitHub: GitHubPullRequestSource
    private val demoSource = PullRequestSource { _, _ -> PullRequestLookup.Found(PullRequestState.Draft) }

    private val open = "https://github.com/acme/app/pull/1"
    private val draft = "https://github.com/acme/app/pull/2"
    private val merged = "https://github.com/acme/app/pull/3"
    private val closed = "https://github.com/acme/app/pull/4"
    private val secret = "https://github.com/acme/vault/pull/5"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return responses[request.path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
            }
        }
        server.start()
        responses["/repos/acme/app/pulls/1"] = { pr("open") }
        responses["/repos/acme/app/pulls/2"] = { pr("open", draft = true) }
        responses["/repos/acme/app/pulls/3"] = { pr("closed", merged = true) }
        responses["/repos/acme/app/pulls/4"] = { pr("closed") }
        AppClock.nowMillis = { now }
        cache = PullRequestCache(JsonDiskCache(folder.newFolder("pullrequests"), nowProvider = { now }, dispatcher = Dispatchers.Unconfined))
        gitHub = GitHubPullRequestSource(GitHubApiFactory.retrofit(GitHubApiFactory.okHttp { token }, server.url("/").toString()), now = { now })
    }

    @After
    fun tearDown() {
        server.shutdown()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun pr(state: String, draft: Boolean = false, merged: Boolean = false) = MockResponse()
        .setHeader("X-RateLimit-Remaining", "42")
        .setBody("""{"number":1,"state":"$state","draft":$draft,"merged":$merged,"merged_at":${if (merged) "\"2026-01-01T00:00:00Z\"" else "null"}}""")

    private fun rateLimited(resetInSeconds: Long) = MockResponse()
        .setResponseCode(403)
        .setHeader("X-RateLimit-Remaining", "0")
        .setHeader("X-RateLimit-Reset", ((now / 1000) + resetInSeconds).toString())
        .setBody("""{"message":"API rate limit exceeded"}""")

    private fun repository(withCache: Boolean = true, account: PullRequestSource? = null) = PullRequestRepository(
        gitHub = gitHub,
        demo = demoSource,
        isDemo = { demo },
        readToken = { token },
        writeToken = { token = it },
        cache = if (withCache) cache else null,
        scope = CoroutineScope(Dispatchers.Unconfined),
        account = account,
    )

    private fun requestsFor(url: String) = synchronized(requests) { requests.count { it.path == "/repos/${url.removePrefix("https://github.com/").replace("/pull/", "/pulls/")}" } }

    private suspend fun PullRequestRepository.known() = states.first()

    @Test
    fun `reads each state from GitHub, remembers what it refused and saves the lot`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(open, draft, merged, closed, secret, "https://cursor.com/agents/bc-1"))

        assertThat(repo.known()).containsExactly(
            open, PullRequestState.Open,
            draft, PullRequestState.Draft,
            merged, PullRequestState.Merged,
            closed, PullRequestState.Closed,
        )
        // The private repository answered 404: remembered as unreadable, not asked about again on the next pass.
        assertThat(repo.statuses.value.getValue(secret).state).isNull()
        assertThat(synchronized(requests) { requests.size }).isEqualTo(5)
        assertThat(synchronized(requests) { requests.all { it.getHeader("Authorization") == null } }).isTrue()
        assertThat(synchronized(requests) { requests.first().getHeader("Accept") }).isEqualTo("application/vnd.github+json")

        val saved = cache.read()!!
        assertThat(saved.getValue(merged).state).isEqualTo(PullRequestState.Merged)
        assertThat(saved.getValue(secret).state).isNull()

        repo.refresh(listOf(open, draft, merged, closed, secret))
        assertThat(synchronized(requests) { requests.size }).isEqualTo(5)
    }

    @Test
    fun `the account's answer comes first, GitHub only where the account has none`() = runBlocking<Unit> {
        val gitlab = "https://gitlab.com/acme/app/-/merge_requests/7"
        val asked = mutableListOf<String>()
        val account = PullRequestSource { url, _ ->
            asked += url
            when (url) {
                // GitHub says open; the account, which the first-party apps show, says merged.
                open -> PullRequestLookup.Found(PullRequestState.Merged)
                gitlab -> PullRequestLookup.Found(PullRequestState.Open)
                secret -> PullRequestLookup.Unreadable
                else -> PullRequestLookup.Failed
            }
        }
        val repo = repository(account = account)

        repo.refresh(listOf(open, draft, gitlab, secret))

        assertThat(repo.known()).containsExactly(
            open, PullRequestState.Merged,
            draft, PullRequestState.Draft,
            gitlab, PullRequestState.Open,
        )
        assertThat(asked).containsExactly(open, draft, gitlab, secret).inOrder()
        // GitHub was asked only for what the account did not answer; a GitLab merge request is never GitHub's to answer.
        assertThat(requestsFor(open)).isEqualTo(0)
        assertThat(requestsFor(draft)).isEqualTo(1)
        assertThat(requestsFor(secret)).isEqualTo(1)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(2)
        assertThat(repo.statuses.value.getValue(secret).state).isNull()
    }

    @Test
    fun `without the account, pull requests on other SCMs are left alone`() = runBlocking<Unit> {
        val repo = repository()

        repo.refresh(listOf("https://gitlab.com/acme/app/-/merge_requests/7", open))

        assertThat(repo.known()).containsExactly(open, PullRequestState.Open)
        assertThat(repo.statuses.value).doesNotContainKey("https://gitlab.com/acme/app/-/merge_requests/7")
    }

    @Test
    fun `states seeded from the account's list replace GitHub's, never undo a merge, and reach the disk`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(open, merged))
        assertThat(repo.known()).containsExactly(open, PullRequestState.Open, merged, PullRequestState.Merged)
        val fresh = "https://github.com/acme/app/pull/8"
        now += 1_000

        repo.seed(mapOf(open to PullRequestState.Closed, merged to PullRequestState.Open, fresh to PullRequestState.Draft))

        assertThat(repo.known()).containsExactly(
            open, PullRequestState.Closed,
            merged, PullRequestState.Merged,
            fresh, PullRequestState.Draft,
        )
        assertThat(repo.statuses.value.getValue(open).checkedAtMillis).isEqualTo(now)
        assertThat(cache.read()!!.getValue(fresh).state).isEqualTo(PullRequestState.Draft)
        // Seeded states are fresh: the next pass has nothing to ask GitHub for.
        repo.refresh(listOf(open, merged, fresh))
        assertThat(requestsFor(fresh)).isEqualTo(0)
        assertThat(requestsFor(open)).isEqualTo(1)

        // The demo never seeds.
        demo = true
        repo.seed(mapOf("https://github.com/acme/app/pull/9" to PullRequestState.Open))
        assertThat(repo.statuses.value).doesNotContainKey("https://github.com/acme/app/pull/9")
    }

    @Test
    fun `states that can still move are re-read on their own schedule, a merged one never`() = runBlocking<Unit> {
        val repo = repository()
        val urls = listOf(open, merged, closed, secret)
        repo.refresh(urls)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(4)

        // A minute later nothing is due, unless the user asked: then what can move is re-read, what GitHub refused is not.
        now += 61_000
        repo.refresh(urls)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(4)
        repo.refresh(urls, eager = true)
        assertThat(requestsFor(open)).isEqualTo(2)
        assertThat(requestsFor(closed)).isEqualTo(2)
        assertThat(requestsFor(merged)).isEqualTo(1)
        assertThat(requestsFor(secret)).isEqualTo(1)

        // Ten minutes: open again; six hours: closed again; an hour: the refused one is tried once more.
        now += 10 * 60_000
        repo.refresh(urls)
        assertThat(requestsFor(open)).isEqualTo(3)
        assertThat(requestsFor(closed)).isEqualTo(2)
        assertThat(requestsFor(secret)).isEqualTo(1)
        now += 60 * 60_000
        repo.refresh(urls)
        assertThat(requestsFor(secret)).isEqualTo(2)
        assertThat(requestsFor(closed)).isEqualTo(2)
        now += 6 * 60 * 60_000
        repo.refresh(urls)
        assertThat(requestsFor(closed)).isEqualTo(3)
        assertThat(requestsFor(merged)).isEqualTo(1)

        // A state that moved on GitHub replaces the remembered one.
        responses["/repos/acme/app/pulls/1"] = { pr("closed", merged = true) }
        now += 10 * 60_000
        repo.refresh(urls)
        assertThat(repo.known()[open]).isEqualTo(PullRequestState.Merged)
    }

    @Test
    fun `a spent rate limit ends the pass and nothing is asked until GitHub's reset`() = runBlocking<Unit> {
        responses["/repos/acme/app/pulls/2"] = { rateLimited(resetInSeconds = 600) }
        val repo = repository()
        repo.refresh(listOf(open, draft, merged))
        assertThat(repo.known()).containsExactly(open, PullRequestState.Open)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(2)

        now += 5 * 60_000
        repo.refresh(listOf(open, draft, merged))
        assertThat(synchronized(requests) { requests.size }).isEqualTo(2)

        responses["/repos/acme/app/pulls/2"] = { pr("open", draft = true) }
        now += 6 * 60_000
        repo.refresh(listOf(open, draft, merged))
        assertThat(repo.known().keys).containsExactly(open, draft, merged)
    }

    @Test
    fun `a transient failure remembers nothing and is tried again next time`() = runBlocking<Unit> {
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setResponseCode(502) }
        val repo = repository()
        repo.refresh(listOf(open, merged))
        assertThat(repo.statuses.value).isEmpty()

        responses["/repos/acme/app/pulls/1"] = { pr("open") }
        repo.refresh(listOf(open, merged))
        assertThat(repo.known().keys).containsExactly(open, merged)
    }

    @Test
    fun `saved states show before GitHub is asked and are gone after a reset`() = runBlocking<Unit> {
        cache.write(mapOf(merged to PullRequestStatus(PullRequestState.Merged, now)))
        val repo = repository()
        repo.restoreFromCache()
        assertThat(repo.known()).containsExactly(merged, PullRequestState.Merged)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(0)

        repo.refresh(listOf(merged))
        assertThat(synchronized(requests) { requests.size }).isEqualTo(0)

        repo.reset()
        assertThat(repo.statuses.value).isEmpty()
    }

    @Test
    fun `a token is sent to GitHub, asks again about what was refused, and its removal forgets those answers`() = runBlocking<Unit> {
        val repo = repository()
        assertThat(repo.hasToken.value).isFalse()
        repo.refresh(listOf(open, secret))
        assertThat(repo.statuses.value.getValue(secret).state).isNull()

        responses["/repos/acme/vault/pulls/5"] = { pr("open") }
        repo.setToken(" ghp_secret ")
        assertThat(repo.hasToken.value).isTrue()
        assertThat(token).isEqualTo("ghp_secret")
        // The refused answer is forgotten, the ones GitHub gave are kept.
        assertThat(repo.statuses.value.keys).containsExactly(open)

        repo.refresh(listOf(open, secret))
        assertThat(repo.known()[secret]).isEqualTo(PullRequestState.Open)
        assertThat(synchronized(requests) { requests.last().getHeader("Authorization") }).isEqualTo("Bearer ghp_secret")
        assertThat(requestsFor(open)).isEqualTo(1)

        repo.setToken("")
        assertThat(repo.hasToken.value).isFalse()
        assertThat(token).isNull()
    }

    @Test
    fun `the demo answers from its seeds and leaves the disk alone`() = runBlocking<Unit> {
        demo = true
        val repo = repository()
        repo.refresh(listOf(open))
        assertThat(repo.known()).containsExactly(open, PullRequestState.Draft)
        assertThat(synchronized(requests) { requests.size }).isEqualTo(0)
        assertThat(cache.read()).isNull()

        // Leaving the demo for a real account starts from what the disk has for it, not from the demo's answers.
        demo = false
        repo.refresh(listOf(merged))
        assertThat(repo.known()).containsExactly(merged, PullRequestState.Merged)
    }

    @Test
    fun `GitHub's answers are told apart`() = runBlocking<Unit> {
        val ref = PullRequestRef("acme", "app", 1)
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        val limited = gitHub.lookup(open, ref) as PullRequestLookup.RateLimited
        assertThat(limited.untilMillis).isEqualTo(now + 120_000)

        responses["/repos/acme/app/pulls/1"] = { MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "7") }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Unreadable)
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setResponseCode(401) }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Unreadable)
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setResponseCode(503) }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Failed)
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setBody("<html>maintenance</html>") }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Failed)
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setBody("""{"state":"open","draft":false}""") }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Found(PullRequestState.Open))
        responses["/repos/acme/app/pulls/1"] = { MockResponse().setBody("""{"state":"closed","merged_at":"2026-01-01T00:00:00Z"}""") }
        assertThat(gitHub.lookup(open, ref)).isEqualTo(PullRequestLookup.Found(PullRequestState.Merged))
    }
}
