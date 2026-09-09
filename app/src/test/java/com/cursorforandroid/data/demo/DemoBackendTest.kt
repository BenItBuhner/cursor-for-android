package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.PromptDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The demo backend stands in for the API in every walkthrough and screenshot, so where it behaves unlike the real one
 * a regression can pass unnoticed. Covers cancellation settling for good (DEMO-01), the store holding up under the
 * concurrent reads and writes the repositories actually make (DEMO-02), and cursor pagination (DEMO-03).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoBackendTest {

    private fun backend(): Pair<CursorApi, RunStreamer> = DemoBackendFactory.create()

    private suspend fun launchAgent(api: CursorApi, prompt: String = "Add a retry to the uploader"): Pair<String, String> {
        val created = api.createAgent(CreateAgentRequestDto(prompt = PromptDto(prompt), env = AgentEnvDto("cloud")))
        return created.agent.id to created.run.id
    }

    /**
     * A live run held directly against the store, without the API in front of it. `DemoCursorApi` hops to
     * Dispatchers.IO to imitate network latency, and a real delay there lets `runTest`'s virtual clock run the script
     * to its end while the cancel is still in flight; the store is what a cancel actually goes through.
     */
    private class Live(val agentId: String = "bc-live") {
        val store = DemoStore()
        val streamer = DemoRunStreamer(store)
        val runId = "run-live"

        init { addRun(runId, "Add a retry to the uploader") }

        fun addRun(id: String, prompt: String) {
            val iso = store.iso()
            val run = RunDto(id = id, agentId = agentId, status = "CREATING", createdAt = iso, updatedAt = iso)
            val agent = store.agent(agentId)
            if (agent == null) {
                store.addAgent(
                    agent = AgentDto(id = agentId, name = "Live", status = "ACTIVE", url = "https://cursor.com/agents/$agentId", createdAt = iso, updatedAt = iso, latestRunId = id),
                    legacy = V0AgentDto(agentId, "Live", "RUNNING", null, null, null, iso),
                    run = run,
                    firstMessage = prompt,
                    script = "generic",
                    servers = emptyList(),
                )
            } else {
                store.addRun(agent, run, prompt, "generic", emptyList())
            }
        }
    }

    @Test
    fun `stopping a run ends its stream as cancelled, and its script never calls it finished`() = runTest {
        val live = Live()
        val events = mutableListOf<RunStreamEvent>()

        val collector = launch { live.streamer.stream(live.agentId, live.runId, null).collect { events += it } }
        // Far enough in that the script has started working, but nowhere near its ending.
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(events.filterIsInstance<RunStreamEvent.ToolCall>()).isNotEmpty()
        assertThat(events).doesNotContain(RunStreamEvent.Done)

        assertThat(live.store.cancelRun(live.agentId, live.runId)).isTrue()
        // Everything the script had left to do, if it were still going to do it.
        advanceUntilIdle()
        collector.join()

        val results = events.filterIsInstance<RunStreamEvent.Result>()
        assertThat(results.map { it.status }).containsExactly(RunStatus.CANCELLED)
        assertThat(events.last()).isEqualTo(RunStreamEvent.Done)
        assertThat(live.store.run(live.agentId, live.runId)!!.status).isEqualTo("CANCELLED")
        // The epilogue would have left the agent's final answer in the transcript; a stopped run has no answer.
        assertThat(live.store.transcript(live.agentId)!!.none { it.type == "assistant_message" }).isTrue()
    }

    @Test
    fun `a stopped run replays as cancelled when the conversation is opened again`() = runTest {
        val live = Live()

        val collector = launch { live.streamer.stream(live.agentId, live.runId, null).collect { } }
        advanceTimeBy(1_000)
        runCurrent()
        live.store.cancelRun(live.agentId, live.runId)
        advanceUntilIdle()
        collector.join()

        val replayed = mutableListOf<RunStreamEvent>()
        launch { live.streamer.stream(live.agentId, live.runId, null).collect { replayed += it } }
        advanceUntilIdle()

        assertThat(replayed.filterIsInstance<RunStreamEvent.Result>().map { it.status }).containsExactly(RunStatus.CANCELLED)
        assertThat(replayed.last()).isEqualTo(RunStreamEvent.Done)
    }

    @Test
    fun `a run that already finished cannot be stopped`() = runTest {
        val (api, streamer) = backend()
        val (agentId, runId) = launchAgent(api)

        launch { streamer.stream(agentId, runId, null).collect { } }
        advanceUntilIdle()
        assertThat(api.getRun(agentId, runId).status).isEqualTo("FINISHED")

        val failure = runCatching { api.cancelRun(agentId, runId) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CursorApiException::class.java)
        assertThat((failure as CursorApiException).code).isEqualTo("run_not_cancellable")
        assertThat(api.getRun(agentId, runId).status).isEqualTo("FINISHED")
    }

    @Test
    fun `a follow-up after a stop runs on its own and finishes`() = runTest {
        val live = Live()

        val first = launch { live.streamer.stream(live.agentId, live.runId, null).collect { } }
        advanceTimeBy(1_000)
        runCurrent()
        live.store.cancelRun(live.agentId, live.runId)
        advanceUntilIdle()
        first.join()

        val secondRun = "run-live-2"
        live.addRun(secondRun, "Try again with a backoff")
        val events = mutableListOf<RunStreamEvent>()
        launch { live.streamer.stream(live.agentId, secondRun, null).collect { events += it } }
        advanceUntilIdle()

        assertThat(events.filterIsInstance<RunStreamEvent.Result>().map { it.status }).containsExactly(RunStatus.FINISHED)
        assertThat(live.store.run(live.agentId, secondRun)!!.status).isEqualTo("FINISHED")
        // The stopped run is still stopped: a later run does not rewrite it.
        assertThat(live.store.run(live.agentId, live.runId)!!.status).isEqualTo("CANCELLED")
    }

    @Test
    fun `the API's cancel settles the run and the legacy view of it together`() = runBlocking {
        val (api, _) = backend()
        val (agentId, runId) = launchAgent(api)

        api.cancelRun(agentId, runId)

        assertThat(api.getRun(agentId, runId).status).isEqualTo("CANCELLED")
        assertThat(api.listRuns(agentId).items.single { it.id == runId }.status).isEqualTo("CANCELLED")
        assertThat(api.listAgentsV0().agents.single { it.id == agentId }.status).isEqualTo("CANCELLED")
    }

    @Test
    fun `paging the agent list visits every agent once, in the order one page would give`() = runBlocking {
        val (api, _) = backend()
        val whole = api.listAgents().items.map { it.id }
        assertThat(api.listAgents().nextCursor).isNull()

        val paged = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.listAgents(limit = 3, cursor = cursor)
            assertThat(page.items.size).isAtMost(3)
            paged += page.items.map { it.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 100)

        assertThat(pages).isGreaterThan(1)
        assertThat(paged).containsNoDuplicates()
        assertThat(paged).isEqualTo(whole)
    }

    @Test
    fun `paging the legacy list and a conversation's runs behaves the same way`() = runBlocking {
        val (api, _) = backend()

        val wholeV0 = api.listAgentsV0().agents.map { it.id }
        assertThat(api.listAgentsV0().nextCursor).isNull()
        val pagedV0 = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = api.listAgentsV0(limit = 4, cursor = cursor)
            pagedV0 += page.agents.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        assertThat(pagedV0).isEqualTo(wholeV0)

        // The seed with several earlier turns has enough runs to need more than one page of two.
        val agentId = api.listAgents().items.first { api.listRuns(it.id).items.size > 2 }.id
        val wholeRuns = api.listRuns(agentId).items.map { it.id }
        val pagedRuns = mutableListOf<String>()
        var runCursor: String? = null
        var runPages = 0
        do {
            val page = api.listRuns(agentId, limit = 2, cursor = runCursor)
            pagedRuns += page.items.map { it.id }
            runCursor = page.nextCursor
            runPages++
        } while (runCursor != null && runPages < 100)
        assertThat(runPages).isGreaterThan(1)
        assertThat(pagedRuns).isEqualTo(wholeRuns)
    }

    @Test
    fun `a page size beyond the maximum is capped, and a cursor for a row that is gone ends the traversal`() = runBlocking {
        val (api, _) = backend()
        val first = api.listAgents(limit = 5)

        assertThat(api.listAgents(limit = 10_000).items.size).isAtMost(100)
        assertThat(api.listAgents(limit = 0).items).hasSize(1)

        api.delete(first.items.last().id)
        val after = api.listAgents(limit = 5, cursor = first.nextCursor)
        assertThat(after.items).isEmpty()
        assertThat(after.nextCursor).isNull()
    }

    @Test
    fun `the default page holds the whole demo, so what a walkthrough shows does not depend on paging`() = runBlocking {
        val (api, _) = backend()
        // AgentRepository asks for 100 at a time; the demo has to fit in one so the screenshots see all of it.
        val page = api.listAgents(limit = 100)
        assertThat(page.nextCursor).isNull()
        assertThat(page.items).isNotEmpty()
        assertThat(api.listAgentsV0(limit = 100).nextCursor).isNull()
    }

    @Test
    fun `the store holds up while it is read and written at the same time`(): Unit = runBlocking {
        val store = DemoStore()
        val existing = store.agentsByRecency(includeArchived = true).map { it.id }
        assertThat(existing).isNotEmpty()

        // Bounded rounds on Dispatchers.IO rather than spinning on Dispatchers.Default, whose thread count on a small
        // build machine is low enough that busy readers would starve the writer and hang the suite.
        coroutineScope {
            repeat(3) {
                launch(Dispatchers.IO) {
                    repeat(400) {
                        store.agentsByRecency(includeArchived = true).forEach { store.runsOf(it.id) }
                        store.v0Agents().forEach { store.transcript(it.id) }
                        store.repositoryUrls()
                        existing.forEach { store.latestRun(it) }
                        yield()
                    }
                }
            }
            launch(Dispatchers.IO) {
                repeat(400) { i ->
                    val id = "bc-stress-$i"
                    val runId = "run-stress-$i"
                    val iso = store.iso()
                    store.addAgent(
                        agent = AgentDto(id = id, name = "Stress $i", status = "ACTIVE", url = "https://cursor.com/agents/$id", createdAt = iso, updatedAt = iso, latestRunId = runId),
                        legacy = V0AgentDto(id, "Stress $i", "RUNNING", null, null, null, iso),
                        run = RunDto(id = runId, agentId = id, status = "RUNNING", createdAt = iso, updatedAt = iso),
                        firstMessage = "stress",
                        script = "generic",
                        servers = emptyList(),
                    )
                    store.updateRun(id, runId) { it.copy(status = "RUNNING") }
                    store.appendAssistant(id, "still going")
                    if (i % 2 == 0) store.cancelRun(id, runId) else store.finishRun(id, runId) { it.copy(status = "FINISHED") }
                    store.removeAgent(id)
                    yield()
                }
            }
        }

        // Everything the writer added it also removed, so the demo is back to its seeds and nothing tore.
        assertThat(store.agentsByRecency(includeArchived = true).map { it.id }).containsExactlyElementsIn(existing)
    }

    @Test
    fun `launching and deleting while the list is being read leaves the list consistent`(): Unit = runBlocking {
        val (api, _) = backend()
        val launched = mutableListOf<String>()

        coroutineScope {
            repeat(2) {
                launch(Dispatchers.IO) {
                    repeat(12) {
                        val listed = api.listAgents().items
                        // Every row a page returns must still be a row the demo can describe, or it was torn.
                        listed.take(3).forEach { runCatching { api.getAgent(it.id) } }
                        api.listAgentsV0()
                        yield()
                    }
                }
            }
            launch(Dispatchers.IO) {
                repeat(6) {
                    val (agentId, _) = launchAgent(api, "concurrent launch $it")
                    synchronized(launched) { launched += agentId }
                    api.delete(agentId)
                }
            }
        }

        val remaining = api.listAgents(limit = 100).items.map { it.id }
        assertThat(remaining).containsNoDuplicates()
        assertThat(remaining).containsNoneIn(launched)
    }
}
