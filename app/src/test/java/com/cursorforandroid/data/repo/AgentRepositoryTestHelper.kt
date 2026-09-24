package com.cursorforandroid.data.repo

import kotlinx.coroutines.Job

/** Test-only view of [AgentRepository] internals, the way [FollowUpRepositoryTestHelper] reads the follow-up queue's. */
internal object AgentRepositoryTestHelper {

    /**
     * Waits for the fetch in flight, if any, to run to the end of its job. A fetch lands (`refreshCompleted`) before
     * its tail — the rows fetched by id, the settle — and `refreshIfStale` skips a poll that finds one still in
     * flight, so a test of the polling cadence starts from here rather than from the landing.
     */
    suspend fun awaitFetchIdle(repo: AgentRepository) {
        val inFlight = AgentRepository::class.java.getDeclaredField("inFlight").apply { isAccessible = true }.get(repo) ?: return
        val job = inFlight.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(inFlight) as Job
        job.join()
    }
}
