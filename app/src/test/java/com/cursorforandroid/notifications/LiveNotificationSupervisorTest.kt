package com.cursorforandroid.notifications

import com.cursorforandroid.data.repo.AgentListState
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The policy behind [LiveNotificationCoordinator], driven on virtual time: which decisions start the service, how a
 * refused start is retried, how a service that goes down with agents still running is brought back, and when the
 * supervisor stops trying.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveNotificationSupervisorTest {

    private val user = CursorUser("key", "dev@example.com", "Dev", null, 1L)
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(user, isDemo = false))
    private val list = MutableStateFlow(AgentListState())
    private val enabled = MutableStateFlow(true)
    private val serviceActive = MutableStateFlow(false)

    /** Virtual times at which the service was asked to start. */
    private val starts = mutableListOf<Long>()
    private var stops = 0
    private var acceptStart = true

    private fun agent(id: String, status: RunStatus) = Agent(
        id = id,
        name = "Agent $id",
        lifecycle = AgentLifecycle.ACTIVE,
        runStatus = status,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        latestRunId = "run-$id",
        repoUrl = null,
        startingRef = null,
    )

    private fun fetched(vararg running: String, finished: List<String> = emptyList()) = AgentListState(
        agents = running.map { agent(it, RunStatus.RUNNING) } + finished.map { agent(it, RunStatus.FINISHED) },
        hasLoaded = true,
        isFromCache = false,
    )

    private fun TestScope.supervise(now: () -> Long = { currentTime }): Job = launch {
        LiveNotificationSupervisor(
            start = { starts += currentTime; acceptStart },
            stop = { stops++ },
            now = now,
        ).run(liveDecisions(session, list, enabled, serviceActive))
    }

    /** Advances past [ms] and runs what is due at the new time. */
    private fun TestScope.tick(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `starts the service once agents run and leaves it alone once it reports up`() = runTest {
        val job = supervise()
        runCurrent()
        assertThat(starts).isEmpty()

        list.value = fetched("bc-1")
        runCurrent()
        assertThat(starts).containsExactly(0L)

        // The service came up: the pending retry is dropped in favour of one re-issue, and nothing more follows.
        tick(1_000)
        serviceActive.value = true
        runCurrent()
        assertThat(starts).containsExactly(0L, 1_000L)
        tick(10 * 60_000)
        assertThat(starts).hasSize(2)
        assertThat(stops).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `a running set that changes re-issues the start so the service re-posts, and nothing else`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        serviceActive.value = true
        runCurrent()
        val before = starts.size

        list.value = fetched("bc-1", "bc-2")
        runCurrent()
        assertThat(starts).hasSize(before + 1)
        tick(10 * 60_000)
        assertThat(starts).hasSize(before + 1)
        job.cancel()
    }

    @Test
    fun `a start that never brings the service up is asked for again with growing pauses, then given up on`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        // Attempts at 0, 3, 9, 21 and 45 s: each pause doubles, and the fifth attempt is the last.
        tick(60 * 60_000)
        assertThat(starts).containsExactly(0L, 3_000L, 9_000L, 21_000L, 45_000L).inOrder()
        job.cancel()
    }

    @Test
    fun `a start the platform refuses outright is retried the same way`() = runTest {
        acceptStart = false
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        tick(60 * 60_000)
        assertThat(starts).hasSize(LiveNotificationSupervisor.MAX_START_ATTEMPTS)
        job.cancel()
    }

    @Test
    fun `a service that goes down while agents still run is brought back, later each time, until it stays up`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        serviceActive.value = true
        runCurrent()
        assertThat(starts).containsExactly(0L, 0L)

        // Lost at 10 s: back after the first pause (5 s).
        tick(10_000)
        serviceActive.value = false
        runCurrent()
        assertThat(starts).hasSize(2)
        tick(4_999)
        assertThat(starts).hasSize(2)
        tick(1)
        assertThat(starts.last()).isEqualTo(15_000L)
        serviceActive.value = true
        runCurrent()

        // Lost again before it was up for a minute: the pause doubles (10 s).
        tick(5_000)
        serviceActive.value = false
        runCurrent()
        val lostAt = currentTime
        tick(9_999)
        assertThat(starts.last()).isLessThan(lostAt)
        tick(1)
        assertThat(starts.last()).isEqualTo(lostAt + 10_000)
        serviceActive.value = true
        runCurrent()

        // Up for a full minute: healthy again, so the next loss starts the pauses over at 5 s.
        tick(61_000)
        serviceActive.value = false
        runCurrent()
        val lostAgainAt = currentTime
        tick(5_000)
        assertThat(starts.last()).isEqualTo(lostAgainAt + 5_000)
        job.cancel()
    }

    @Test
    fun `the pause before a restart never grows past its cap`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        repeat(12) {
            serviceActive.value = true
            runCurrent()
            tick(1_000)
            serviceActive.value = false
            runCurrent()
            val lostAt = currentTime
            tick(LiveNotificationSupervisor.RESTART_MAX_MS)
            assertThat(starts.last()).isAtMost(lostAt + LiveNotificationSupervisor.RESTART_MAX_MS)
            assertThat(starts.last()).isAtLeast(lostAt)
        }
        job.cancel()
    }

    @Test
    fun `a service that stops because the last run finished is not restarted`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        serviceActive.value = true
        runCurrent()
        val before = starts.size

        // The row settles before the service's idle grace elapses, so the running set empties first.
        list.value = fetched(finished = listOf("bc-1"))
        runCurrent()
        tick(3_000)
        serviceActive.value = false
        tick(10 * 60_000)
        assertThat(starts).hasSize(before)

        // The next run starts at once, with no restart pause: the previous stop was not a loss.
        list.value = fetched("bc-2", finished = listOf("bc-1"))
        runCurrent()
        assertThat(starts).hasSize(before + 1)
        assertThat(starts.last()).isEqualTo(currentTime)
        job.cancel()
    }

    @Test
    fun `nothing starts while the list is the one restored from disk, or the feature is off, or nothing runs`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1").copy(isFromCache = true)
        tick(60_000)
        assertThat(starts).isEmpty()

        enabled.value = false
        list.value = fetched("bc-1")
        tick(60_000)
        assertThat(starts).isEmpty()

        enabled.value = true
        list.value = fetched(finished = listOf("bc-1"))
        tick(60_000)
        assertThat(starts).isEmpty()
        job.cancel()
    }

    @Test
    fun `signing out stops the service and its retries`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        assertThat(starts).hasSize(1)

        session.value = SessionState.SignedOut
        runCurrent()
        assertThat(stops).isEqualTo(1)
        tick(60 * 60_000)
        assertThat(starts).hasSize(1)
        job.cancel()
    }

    @Test
    fun `a notification that could not appear stops the service instead of starting it`() = runTest {
        val canShow = MutableStateFlow(false)
        val job = launch {
            LiveNotificationSupervisor(
                start = { starts += currentTime; acceptStart },
                stop = { stops++ },
            ).run(liveDecisions(session, list, enabled, serviceActive, canShowLive = { canShow.value }))
        }
        list.value = fetched("bc-1")
        tick(60 * 60_000)
        assertThat(starts).isEmpty()
        assertThat(stops).isEqualTo(1)

        // The user allowed them while the app was away; the collection restarts, and this time the service is asked for.
        canShow.value = true
        list.value = fetched("bc-1", "bc-2")
        runCurrent()
        assertThat(starts).hasSize(1)
        job.cancel()
    }

    @Test
    fun `churn in the running set still resets the loss count after a healthy spell`() = runTest {
        val job = supervise()
        list.value = fetched("bc-1")
        runCurrent()
        serviceActive.value = true
        runCurrent()

        // The running set changes many times, but the service stays up long enough to count as healthy.
        tick(30_000)
        list.value = fetched("bc-1", "bc-2")
        runCurrent()
        tick(31_000)
        list.value = fetched("bc-2", "bc-3")
        runCurrent()

        serviceActive.value = false
        runCurrent()
        val lostAt = currentTime
        tick(4_999)
        assertThat(starts.last()).isLessThan(lostAt + LiveNotificationSupervisor.RESTART_BASE_MS)
        tick(1)
        assertThat(starts.last()).isEqualTo(lostAt + LiveNotificationSupervisor.RESTART_BASE_MS)
        job.cancel()
    }

    @Test
    fun `signing out clears only the live notifications for agents that were being tracked`() = runTest {
        var cleared: Set<String>? = null
        val job = launch {
            LiveNotificationSupervisor(
                start = { true },
                stop = { stops++ },
                clearPosted = { cleared = it },
            ).run(
                flowOf(
                    LiveDecision.Track(setOf("bc-1", "bc-2"), serviceActive = true),
                    LiveDecision.SignedOut,
                ),
            )
        }
        runCurrent()
        assertThat(cleared).isEqualTo(setOf("bc-1", "bc-2"))
        assertThat(stops).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `decisions are pure functions of what the app knows`() {
        val signedIn = SessionState.SignedIn(user, isDemo = false)
        assertThat(liveDecision(signedIn, fetched("bc-1"), enabled = true, serviceActive = false, canShowLive = false))
            .isEqualTo(LiveDecision.Blocked)
        assertThat(liveDecision(SessionState.SignedOut, fetched("bc-1"), enabled = true, serviceActive = false)).isEqualTo(LiveDecision.SignedOut)
        assertThat(liveDecision(SessionState.Loading, fetched("bc-1"), enabled = true, serviceActive = false)).isEqualTo(LiveDecision.Idle)
        assertThat(liveDecision(signedIn, fetched("bc-1").copy(isFromCache = true), enabled = true, serviceActive = false)).isEqualTo(LiveDecision.Idle)
        assertThat(liveDecision(signedIn, fetched("bc-1"), enabled = false, serviceActive = false)).isEqualTo(LiveDecision.Idle)
        assertThat(liveDecision(signedIn, fetched(finished = listOf("bc-1")), enabled = true, serviceActive = true)).isEqualTo(LiveDecision.Idle)
        assertThat(liveDecision(signedIn, fetched("bc-1", "bc-2"), enabled = true, serviceActive = true))
            .isEqualTo(LiveDecision.Track(setOf("bc-1", "bc-2"), serviceActive = true))
        assertThat(liveDecision(signedIn, fetched("bc-1", "bc-2"), enabled = true, serviceActive = true, quietIds = setOf("bc-1")))
            .isEqualTo(LiveDecision.Track(setOf("bc-2"), serviceActive = true))
        assertThat(liveDecision(signedIn, fetched("bc-1"), enabled = true, serviceActive = false, quietIds = setOf("bc-1")))
            .isEqualTo(LiveDecision.Idle)
    }
}
