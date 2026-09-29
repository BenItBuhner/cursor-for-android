package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The account's queue and goal are polled for a chat on screen only (Extended mode): a chat whose screen stopped —
 * the app backgrounded, or another chat pushed over it, its view model still alive on the back stack — asks nothing,
 * and coming back reads the queue at once. Against a [FaultServer] at 300–900 ms RTT, the queue poll scaled 1:10.
 * The screen's start and stop are what `ConversationScreen`'s `LifecycleStartEffect` does through the view models.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SteeringVisibilityTest {

    @get:Rule val folder = TemporaryFolder()

    private val closers = ArrayList<AutoCloseable>()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun `a stopped chat screen polls neither the queue nor the goal, and coming back reads the queue at once`(): Unit = runBlocking {
        val server = FaultServer(rttMillis = 300L..900L).start().also { closers += it }
        val worker = BigProject.WORKERS.first()
        val turns = BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = worker, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[worker] = AgentDto(id = worker, name = "Scanner", status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$worker")
        server.v0[worker] = V0AgentDto(id = worker, name = "Scanner", status = "FINISHED")
        server.transcripts[worker] = BigProject.v0Transcript(turns)
        server.records[worker] = turns.flatMap { it.record }
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = newest.endedAt)
        // Scaled 10x: the queue is read every 1 s here, 10 s in the app; the goal every third poll.
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA, queuePollMs = 1_000L).also { it.now = now; closers += it }
        rig.agents.refresh()
        rig.conversations.attach(worker)
        // The screen starts.
        rig.conversations.resume(worker)
        rig.steering.attach(worker)
        rig.awaitUntil(60_000) { rig.conversations.state(worker).value.let { !it.isLoading && it.runStatus?.isActive != true } }
        delay(1_500)

        val windowMs = 10_000L
        fun polls(from: Int) = server.seen.drop(from).groupingBy { it.route }.eachCount().let { (it[Route.QueueList] ?: 0) to (it[Route.RecordState] ?: 0) }
        val visibleFrom = server.seen.size
        delay(windowMs)
        val (visibleLists, visibleGoals) = polls(visibleFrom)

        // The screen stops (backgrounded, or another chat pushed on top); the view models live on.
        rig.conversations.pause(worker)
        rig.steering.detach(worker)
        delay(1_500)
        val stoppedFrom = server.seen.size
        delay(windowMs)
        val (stoppedLists, stoppedGoals) = polls(stoppedFrom)

        // The screen starts again: the card is not left on what it read before it stopped.
        val backFrom = server.seen.size
        val backAt = System.nanoTime()
        rig.conversations.resume(worker)
        rig.steering.attach(worker)
        rig.awaitUntil(5_000) { server.seen.drop(backFrom).any { it.route == Route.QueueList } }
        val backMs = (System.nanoTime() - backAt) / 1_000_000
        rig.steering.detach(worker)

        println(
            "NETAUDIT steering-visibility (scaled 1:10): visible ${windowMs}ms lists=$visibleLists goals=$visibleGoals; " +
                "stopped ${windowMs}ms lists=$stoppedLists goals=$stoppedGoals; queue read ${backMs}ms after the screen came back",
        )
        assertWithMessage("the visible chat keeps its queue current").that(visibleLists).isAtLeast(4)
        assertWithMessage("the visible chat keeps its goal current").that(visibleGoals).isAtLeast(1)
        assertWithMessage("queue reads while stopped").that(stoppedLists).isEqualTo(0)
        assertWithMessage("goal reads while stopped").that(stoppedGoals).isEqualTo(0)
        assertThat(backMs).isLessThan(5_000L)
    }
}
