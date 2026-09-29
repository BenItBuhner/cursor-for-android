package com.cursorforandroid.notifications

import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The live notification's posts: only when what it shows changes, at once while Android's rate budget allows and the
 * latest as soon as it does, never faster than Android accepts; a run's end at once; the last look always left up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePostPacerTest {

    private fun run(id: String, step: String, summary: String = "", phase: LivePhase = LivePhase.Running) =
        TrackedRun(id, "run-$id", "Agent $id", RunStatus.RUNNING, phase, startedAtMillis = 0L, digest = RunDigest(activity = RunDigest.Activity(step)), summary = summary)

    private fun state(vararg runs: TrackedRun) = LiveActivityState(running = runs.toList(), hasReconciled = true, runningCount = runs.size)

    /** What a test notification shows: each run's step and phase, never its words, as [LiveNotificationRenderer.look]. */
    private fun look(state: LiveActivityState): List<String> = state.running.map { "${it.agentId}:${it.phase}:${it.digest.activity.label}" }

    private class Post(val atMs: Long, val look: List<String>)

    private fun TestScope.budget() = PostBudget(clockMs = { testScheduler.currentTime })

    private fun TestScope.pacer(posts: MutableList<Post>, budget: PostBudget = budget()) =
        LivePostPacer(backgroundScope, look = ::look, budget) { posts += Post(testScheduler.currentTime, it) }

    /** Whether Android, estimating the rate as it does, would have accepted every one of [posts]. */
    private fun androidAcceptsAll(posts: List<Post>): Boolean {
        val android = PostBudget(PostBudget.ANDROID_MAX_PER_SECOND)
        return posts.all { post -> android.allows(post.atMs).also { if (it) android.record(post.atMs) } }
    }

    @Test
    fun `words streaming into the step under way post nothing, and a new step posts at once`() = runTest {
        val posts = ArrayList<Post>()
        val pacer = pacer(posts)
        pacer.offer(state(run("a", "Writing", "one")))
        repeat(200) { i ->
            advanceTimeBy(10)
            pacer.offer(state(run("a", "Writing", "one t$i")))
        }
        assertThat(posts).hasSize(1)

        pacer.offer(state(run("a", "Reading", "one t199")))
        assertThat(posts.map { it.look.single() }).containsExactly("a:Running:Writing", "a:Running:Reading").inOrder()
        assertThat(posts.last().atMs).isEqualTo(testScheduler.currentTime)
    }

    @Test
    fun `steps changing faster than Android accepts post the latest as soon as it would, and never faster`() = runTest {
        val posts = ArrayList<Post>()
        val pacer = pacer(posts)
        val steps = listOf("Writing", "Reading", "Thinking", "Editing")
        var last = state()
        // Eight runs, one of them changing step every 40 ms (25 changes a second) for ten seconds.
        repeat(250) { i ->
            last = state(*Array(8) { r -> run("r$r", if (r == i % 8) steps[(i / 8) % steps.size] else steps[r % steps.size]) })
            pacer.offer(last)
            advanceTimeBy(40)
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(androidAcceptsAll(posts)).isTrue()
        // About 4.8 a second over the eleven, after the short burst the first post allows: every slot is used, none past it.
        assertThat(posts.size).isAtLeast(46)
        assertThat(posts.size).isAtMost(58)
        assertThat(posts.last().look).isEqualTo(look(last))
    }

    @Test
    fun `a change after a quiet spell posts at once, and a burst goes out as fast as the budget allows`() = runTest {
        val posts = ArrayList<Post>()
        val pacer = pacer(posts)
        pacer.offer(state(run("a", "Writing")))
        advanceTimeBy(5_000)
        // A tool starts and ends, and the next thought begins, within 30 ms.
        pacer.offer(state(run("a", "Reading")))
        advanceTimeBy(10)
        pacer.offer(state(run("a", "Working")))
        advanceTimeBy(20)
        pacer.offer(state(run("a", "Thinking")))
        advanceTimeBy(1_000)
        assertThat(posts.map { it.look.single().substringAfterLast(':') }).containsExactly("Writing", "Reading", "Working", "Thinking").inOrder()
        assertThat(posts[1].atMs).isEqualTo(5_000)
        assertThat(androidAcceptsAll(posts)).isTrue()
        assertThat(posts.last().atMs - 5_000).isAtMost(250)
    }

    @Test
    fun `a run leaving or asked to stop is posted at once, and again once the budget allows`() = runTest {
        val posts = ArrayList<Post>()
        val pacer = pacer(posts)
        // Keep the budget spent.
        repeat(20) { i ->
            pacer.offer(state(run("a", if (i % 2 == 0) "Writing" else "Reading"), run("b", "Writing")))
            advanceTimeBy(50)
        }
        val before = posts.size
        val stopAt = testScheduler.currentTime
        pacer.offer(state(run("a", "Reading", phase = LivePhase.Stopping), run("b", "Writing")))
        assertThat(posts.last().atMs).isEqualTo(stopAt)
        assertThat(posts.last().look.first()).isEqualTo("a:Stopping:Reading")
        advanceTimeBy(10)
        pacer.offer(state(run("b", "Writing")))
        assertThat(posts.last().look).containsExactly("b:Running:Writing")
        assertThat(posts.size).isEqualTo(before + 2)
        // Over budget, so Android may have shed it: posted again once it would not.
        advanceTimeBy(1_000)
        assertThat(posts.size).isEqualTo(before + 3)
        assertThat(posts.last().look).containsExactly("b:Running:Writing")
        advanceTimeBy(5_000)
        assertThat(posts.size).isEqualTo(before + 3)
    }

    @Test
    fun `the service's other posts count against the same budget`() = runTest {
        val posts = ArrayList<Post>()
        val budget = budget()
        val pacer = pacer(posts, budget)
        pacer.offer(state(run("a", "Writing")))
        advanceTimeBy(5_000)
        // Finished cards for seven other agents, posted together: all the burst a quiet spell earns.
        val cards = ArrayList<Long>()
        repeat(7) { budget.post { cards += testScheduler.currentTime } }
        pacer.offer(state(run("a", "Reading")))
        assertThat(posts).hasSize(1)
        advanceTimeBy(1_000)
        assertThat(posts.map { it.look.single() }).containsExactly("a:Running:Writing", "a:Running:Reading").inOrder()
        val all = (posts.map { it.atMs } + cards).sorted().map { Post(it, emptyList()) }
        assertThat(androidAcceptsAll(all)).isTrue()
    }

    @Test
    fun `flush posts what the budget holds back, cancel drops it`() = runTest {
        val posts = ArrayList<Post>()
        val pacer = pacer(posts)
        repeat(6) { i ->
            pacer.offer(state(run("a", "Step $i")))
            advanceTimeBy(5)
        }
        val held = posts.size
        assertThat(posts.last().look.single()).isNotEqualTo("a:Running:Step 5")
        pacer.flush()
        assertThat(posts.size).isEqualTo(held + 1)
        assertThat(posts.last().look.single()).isEqualTo("a:Running:Step 5")

        advanceTimeBy(5_000)
        val settled = posts.size
        repeat(10) { i ->
            pacer.offer(state(run("a", "Next $i")))
            advanceTimeBy(5)
        }
        val beforeCancel = posts.size
        pacer.cancel()
        advanceTimeBy(5_000)
        assertThat(posts.size).isEqualTo(beforeCancel)
        assertThat(beforeCancel).isGreaterThan(settled)
    }

    @Test
    fun `the Spotlight's pacing posts each change within the budget it shares with the roster, the latest left up`() = runTest {
        val budget = budget()
        val posts = ArrayList<Pair<Long, String>>()
        val pacer = PostPacer<String, String>(backgroundScope, look = { it }, ends = { _, _ -> false }, budget) { posts += testScheduler.currentTime to it }
        pacer.offer("Reading")
        pacer.offer("Reading")
        assertThat(posts).containsExactly(0L to "Reading")

        // The roster posting meanwhile spends the same budget, so the Spotlight's next change waits for it.
        repeat(6) { budget.record(testScheduler.currentTime); advanceTimeBy(20) }
        pacer.offer("Grepping")
        assertThat(posts).hasSize(1)
        repeat(40) { i -> advanceTimeBy(25); pacer.offer("Step $i") }
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(posts.last().second).isEqualTo("Step 39")
        val android = PostBudget(PostBudget.ANDROID_MAX_PER_SECOND)
        assertThat(posts.all { (at, _) -> android.allows(at).also { if (it) android.record(at) } }).isTrue()
    }

    @Test
    fun `the budget settles at its ceiling, a quiet spell earns a short burst, and waiting its wait is always enough`() {
        val budget = PostBudget(maxPerSecond = 4.5)
        var now = 0L
        var posted = 0
        while (now < 10_000) {
            // Past the first two seconds, whose opening burst the first post's generous start allows.
            if (budget.allows(now)) { budget.record(now); if (now >= 2_000) posted++ }
            now += 1
        }
        assertThat(posted).isIn(35..37)

        now += 3_000
        var burst = 0
        while (budget.allows(now)) { budget.record(now); burst++ }
        assertThat(burst).isIn(3..6)
        repeat(50) {
            val wait = budget.waitMs(now)
            assertThat(budget.allows(now + wait)).isTrue()
            if (wait > 1) assertThat(budget.allows(now + wait - 1)).isFalse()
            now += wait
            budget.record(now)
        }
    }
}
