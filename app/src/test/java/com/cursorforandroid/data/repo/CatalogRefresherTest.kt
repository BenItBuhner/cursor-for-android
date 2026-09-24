package com.cursorforandroid.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The one refresh policy both composer catalogs keep (see [CatalogRefresher]). */
class CatalogRefresherTest {

    private val backend = Any()
    private val other = Any()
    private val start = 1_800_000_000_000L
    private val stale = CatalogRefresher.STALE_AFTER_MS

    private fun repositories() = CatalogRefresher(stale, CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS)
    private fun models() = CatalogRefresher(stale, CatalogRefresher.MODELS_MIN_INTERVAL_MS)

    @Test
    fun `a list is fresh for its backend until it is stale, and never for another one`() {
        val policy = repositories()
        assertThat(policy.isFresh(start, backend)).isFalse()

        policy.asked(start)
        policy.landed(start, backend)
        assertThat(policy.isFresh(start + stale - 1, backend)).isTrue()
        assertThat(policy.isFresh(start + stale, backend)).isFalse()
        assertThat(policy.isFresh(start, other)).isFalse()
    }

    @Test
    fun `an empty list that was fetched is as fresh as any, so an account with none is not asked every minute`() {
        val policy = repositories()
        policy.asked(start)
        policy.landed(start, backend)
        assertThat(policy.isFresh(start + CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS * 2, backend)).isTrue()
        assertThat(policy.dueAt(backend)).isEqualTo(start + stale)
    }

    @Test
    fun `both catalogs go stale at the same age, and only the endpoint's allowance differs`() {
        val repos = repositories()
        val models = models()
        for (policy in listOf(repos, models)) {
            policy.asked(start)
            policy.landed(start, backend)
        }
        assertThat(repos.dueAt(backend)).isEqualTo(models.dueAt(backend))
        assertThat(repos.mayAsk(start + 10_000)).isFalse()
        assertThat(models.mayAsk(start + 10_000)).isTrue()
    }

    @Test
    fun `an attempt spends the allowance whether or not it lands`() {
        val policy = repositories()
        policy.asked(start)
        policy.failed(start, retryAfterMs = null)
        assertThat(policy.mayAsk(start + CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS - 1)).isFalse()
        assertThat(policy.mayAsk(start + CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS)).isTrue()
    }

    @Test
    fun `a Retry-After holds every request, a forced one included, and the next pass`() {
        val policy = models()
        policy.asked(start)
        policy.failed(start, retryAfterMs = 5 * 60 * 1000L)
        assertThat(policy.mayAsk(start + 4 * 60 * 1000L)).isFalse()
        assertThat(policy.mayAsk(start + 5 * 60 * 1000L)).isTrue()
        assertThat(policy.dueAt(backend)).isEqualTo(start + 5 * 60 * 1000L)
    }

    @Test
    fun `a failing endpoint is asked less and less often in the background, never less than once per stale window`() {
        val policy = models()
        var now = start
        val gaps = mutableListOf<Long>()
        repeat(8) {
            policy.asked(now)
            policy.failed(now, retryAfterMs = null)
            val next = policy.dueAt(backend)
            gaps += next - now
            now = next
        }
        assertThat(gaps.take(4)).containsExactly(60_000L, 120_000L, 240_000L, 480_000L).inOrder()
        assertThat(gaps.drop(4).toSet()).containsExactly(stale)

        // The first fetch that lands clears the backoff.
        policy.asked(now)
        policy.landed(now, backend)
        assertThat(policy.failures).isEqualTo(0)
        assertThat(policy.dueAt(backend)).isEqualTo(now + stale)
    }

    @Test
    fun `a list saved by an earlier process is as old as its save, and its save spent the allowance`() {
        val policy = repositories()
        policy.restored(savedAt = start - 3 * 60 * 1000L, backend)
        assertThat(policy.isFresh(start, backend)).isTrue()
        assertThat(policy.dueAt(backend)).isEqualTo(start - 3 * 60 * 1000L + stale)

        val justSaved = repositories()
        justSaved.restored(savedAt = start, backend)
        assertThat(justSaved.mayAsk(start + 1_000)).isFalse()
    }

    @Test
    fun `a reset forgets everything`() {
        val policy = repositories()
        policy.asked(start)
        policy.failed(start, retryAfterMs = 60 * 60 * 1000L)
        policy.reset()
        assertThat(policy.mayAsk(start)).isTrue()
        assertThat(policy.failures).isEqualTo(0)
        assertThat(policy.dueAt(backend)).isEqualTo(CatalogRefresher.REPOSITORIES_MIN_INTERVAL_MS)
    }
}
