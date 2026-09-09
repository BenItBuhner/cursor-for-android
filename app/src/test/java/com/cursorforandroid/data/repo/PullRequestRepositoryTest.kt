package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PullRequestCache
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestStatus
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PullRequestRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private var demo = false
    private lateinit var cache: PullRequestCache
    private val demoSource = PullRequestSource { _ -> PullRequestLookup.Found(PullRequestState.Draft) }

    /** The account service, scripted per `prUrl`: what it has no line for it refuses, like a PR it has no record of. */
    private val answers = mutableMapOf<String, () -> PullRequestLookup>()
    private val asked = mutableListOf<String>()
    private val account = PullRequestSource { url ->
        synchronized(asked) { asked += url }
        answers[url]?.invoke() ?: PullRequestLookup.Unreadable
    }

    private val open = "https://github.com/acme/app/pull/1"
    private val draft = "https://github.com/acme/app/pull/2"
    private val merged = "https://github.com/acme/app/pull/3"
    private val closed = "https://github.com/acme/app/pull/4"
    private val gitlab = "https://gitlab.com/acme/app/-/merge_requests/7"
    private val unknown = "https://github.com/acme/vault/pull/5"

    @Before
    fun setUp() {
        answers[open] = { PullRequestLookup.Found(PullRequestState.Open) }
        answers[draft] = { PullRequestLookup.Found(PullRequestState.Draft) }
        answers[merged] = { PullRequestLookup.Found(PullRequestState.Merged) }
        answers[closed] = { PullRequestLookup.Found(PullRequestState.Closed) }
        answers[gitlab] = { PullRequestLookup.Found(PullRequestState.Open) }
        AppClock.nowMillis = { now }
        cache = PullRequestCache(JsonDiskCache(folder.newFolder("pullrequests"), nowProvider = { now }, dispatcher = Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun repository(withCache: Boolean = true) = PullRequestRepository(
        account = account,
        demo = demoSource,
        isDemo = { demo },
        cache = if (withCache) cache else null,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun askedFor(url: String) = synchronized(asked) { asked.count { it == url } }
    private fun askedCount() = synchronized(asked) { asked.size }

    private suspend fun PullRequestRepository.known() = states.first()

    @Test
    fun `reads each state from the account, remembers what it refused and saves the lot`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(open, draft, merged, closed, gitlab, unknown))

        assertThat(repo.known()).containsExactly(
            open, PullRequestState.Open,
            draft, PullRequestState.Draft,
            merged, PullRequestState.Merged,
            closed, PullRequestState.Closed,
            gitlab, PullRequestState.Open,
        )
        // The account had no answer: remembered as unreadable, not asked about again on the next pass.
        assertThat(repo.statuses.value.getValue(unknown).state).isNull()
        assertThat(askedCount()).isEqualTo(6)

        val saved = cache.read()!!
        assertThat(saved.getValue(merged).state).isEqualTo(PullRequestState.Merged)
        assertThat(saved.getValue(gitlab).state).isEqualTo(PullRequestState.Open)
        assertThat(saved.getValue(unknown).state).isNull()

        repo.refresh(listOf(open, draft, merged, closed, gitlab, unknown))
        assertThat(askedCount()).isEqualTo(6)
    }

    @Test
    fun `states seeded from the account's list fill in the unknown, never undo a merge, and reach the disk`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(merged, unknown))
        assertThat(repo.known()).containsExactly(merged, PullRequestState.Merged)
        val fresh = "https://github.com/acme/app/pull/8"
        now += 1_000

        repo.seed(mapOf(merged to PullRequestState.Open, fresh to PullRequestState.Draft, unknown to PullRequestState.Open))

        // The list has a state for what the live read had none for, and for a PR never asked about; a merge stays.
        assertThat(repo.known()).containsExactly(
            merged, PullRequestState.Merged,
            fresh, PullRequestState.Draft,
            unknown, PullRequestState.Open,
        )
        assertThat(repo.statuses.value.getValue(fresh).checkedAtMillis).isEqualTo(now)
        assertThat(repo.statuses.value.getValue(fresh).live).isFalse()
        assertThat(cache.read()!!.getValue(fresh).state).isEqualTo(PullRequestState.Draft)
        // Seeded states are fresh: the next pass has nothing to ask for.
        repo.refresh(listOf(merged, fresh, unknown))
        assertThat(askedFor(fresh)).isEqualTo(0)
        assertThat(askedFor(unknown)).isEqualTo(1)

        // The demo never seeds.
        demo = true
        repo.seed(mapOf("https://github.com/acme/app/pull/9" to PullRequestState.Open))
        assertThat(repo.statuses.value).doesNotContainKey("https://github.com/acme/app/pull/9")
    }

    @Test
    fun `a list read that agrees changes nothing and never postpones the live read that is due`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(open))
        val readAt = repo.statuses.value.getValue(open).checkedAtMillis

        // The list is read every half minute while the app is open; each read agrees with what the SCM said.
        repeat(5) {
            now += 30_000
            repo.seed(mapOf(open to PullRequestState.Open))
            assertThat(repo.statuses.value.getValue(open).checkedAtMillis).isEqualTo(readAt)
            assertThat(repo.statuses.value.getValue(open).live).isTrue()
        }
        // Two and a half minutes on, the PR is due: the SCM is asked, the list's agreement notwithstanding.
        repo.refresh(listOf(open))
        assertThat(askedFor(open)).isEqualTo(2)
        assertThat(repo.statuses.value.getValue(open).checkedAtMillis).isEqualTo(now)
    }

    @Test
    fun `a list read that disagrees with a fresh live answer waits its turn, and a merge never does`() = runBlocking<Unit> {
        val repo = repository()
        repo.refresh(listOf(open, draft))
        assertThat(repo.known()).containsExactly(open, PullRequestState.Open, draft, PullRequestState.Draft)

        // Half a minute on, the list's stored record says draft: the SCM was just asked, and it said open.
        now += 30_000
        repo.seed(mapOf(open to PullRequestState.Draft, draft to PullRequestState.Merged))
        assertThat(repo.known()[open]).isEqualTo(PullRequestState.Open)
        assertThat(repo.known()[draft]).isEqualTo(PullRequestState.Merged)
        assertThat(repo.statuses.value.getValue(open).checkedAtMillis).isEqualTo(now - 30_000)

        // Once the live answer is as old as the PR's own re-read interval, the list's word is taken up...
        now += 2 * 60_000
        repo.seed(mapOf(open to PullRequestState.Draft))
        assertThat(repo.known()[open]).isEqualTo(PullRequestState.Draft)
        assertThat(repo.statuses.value.getValue(open).live).isFalse()
        // ...and the SCM confirms or corrects it at its next turn, which a state lifted off the list does not postpone.
        answers[open] = { PullRequestLookup.Found(PullRequestState.Open) }
        now += 2 * 60_000
        repo.refresh(listOf(open))
        assertThat(repo.known()[open]).isEqualTo(PullRequestState.Open)
        assertThat(repo.statuses.value.getValue(open).live).isTrue()
    }

    @Test
    fun `states that can still move are re-read on their own schedule, a merged one never`() = runBlocking<Unit> {
        val repo = repository()
        val urls = listOf(open, merged, closed, unknown)
        repo.refresh(urls)
        assertThat(askedCount()).isEqualTo(4)

        // Half a minute later nothing is due, unless the user asked: then what can move is re-read, what was refused is not.
        now += 31_000
        repo.refresh(urls)
        assertThat(askedCount()).isEqualTo(4)
        repo.refresh(urls, eager = true)
        assertThat(askedFor(open)).isEqualTo(2)
        assertThat(askedFor(closed)).isEqualTo(2)
        assertThat(askedFor(merged)).isEqualTo(1)
        assertThat(askedFor(unknown)).isEqualTo(1)

        // Two minutes: open again; six hours: closed again; an hour: the refused one is tried once more.
        now += 2 * 60_000
        repo.refresh(urls)
        assertThat(askedFor(open)).isEqualTo(3)
        assertThat(askedFor(closed)).isEqualTo(2)
        assertThat(askedFor(unknown)).isEqualTo(1)
        now += 60 * 60_000
        repo.refresh(urls)
        assertThat(askedFor(unknown)).isEqualTo(2)
        assertThat(askedFor(closed)).isEqualTo(2)
        now += 6 * 60 * 60_000
        repo.refresh(urls)
        assertThat(askedFor(closed)).isEqualTo(3)
        assertThat(askedFor(merged)).isEqualTo(1)

        // A state that moved replaces the remembered one.
        answers[open] = { PullRequestLookup.Found(PullRequestState.Merged) }
        now += 2 * 60_000
        repo.refresh(urls)
        assertThat(repo.known()[open]).isEqualTo(PullRequestState.Merged)
    }

    @Test
    fun `a transient failure ends the pass, remembers nothing and is tried again next time`() = runBlocking<Unit> {
        answers[open] = { PullRequestLookup.Failed }
        val repo = repository()
        repo.refresh(listOf(open, merged))
        assertThat(repo.statuses.value).isEmpty()
        // The rest of the pass would end the same way, so it is not attempted.
        assertThat(askedFor(merged)).isEqualTo(0)

        answers[open] = { PullRequestLookup.Found(PullRequestState.Open) }
        repo.refresh(listOf(open, merged))
        assertThat(repo.known().keys).containsExactly(open, merged)
    }

    @Test
    fun `saved states show before the account is asked and are gone after a reset`() = runBlocking<Unit> {
        cache.write(mapOf(merged to PullRequestStatus(PullRequestState.Merged, now)))
        val repo = repository()
        repo.restoreFromCache()
        assertThat(repo.known()).containsExactly(merged, PullRequestState.Merged)
        assertThat(askedCount()).isEqualTo(0)

        repo.refresh(listOf(merged))
        assertThat(askedCount()).isEqualTo(0)

        repo.reset()
        assertThat(repo.statuses.value).isEmpty()
    }

    @Test
    fun `the demo answers from its seeds and leaves the disk alone`() = runBlocking<Unit> {
        demo = true
        val repo = repository()
        repo.refresh(listOf(open))
        assertThat(repo.known()).containsExactly(open, PullRequestState.Draft)
        assertThat(askedCount()).isEqualTo(0)
        assertThat(cache.read()).isNull()

        // Leaving the demo for a real account starts from what the disk has for it, not from the demo's answers.
        demo = false
        repo.refresh(listOf(merged))
        assertThat(repo.known()).containsExactly(merged, PullRequestState.Merged)
    }
}
