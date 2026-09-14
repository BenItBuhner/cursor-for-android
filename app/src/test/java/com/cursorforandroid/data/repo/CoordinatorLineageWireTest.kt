package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.CoordinatorLineage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

/**
 * Default-mode lineage from the live stream, frame for frame from the fixture generated off Cursor's own protos
 * (see [CoordinatorFixtures]): the coordinator's worker-naming calls arrive as `sendToAgent`, `create_agent` and
 * `getAgentStatus`, and every one of them names its worker. Until 0.3.7 only the snake-case name matched, so the
 * SDK-spelled calls named nobody and the workers a live coordinator drove sat among the account's own chats.
 */
class CoordinatorLineageWireTest {

    private fun replay(): List<TimelineItem> {
        val source = Buffer().writeUtf8(CoordinatorFixtures.text("coordinator_run.sse"))
        val live = TimelineBuilder.LiveRun("run-coord-001", timed = false)
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.let { live.apply(it.event) }
        }
        return live.snapshot()
    }

    @Test
    fun `every worker-naming call on the live stream names its worker, whatever its spelling`() {
        val items = replay()
        val calls = items.filterIsInstance<ActivityGroup>().flatMap { it.calls }.associateBy { it.callId }
        val release = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671"
        val projects = "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724"
        // `sendToAgent` (SDK spelling): the worker addressed, in the arguments and in the result.
        assertThat(calls.getValue("c1").linkedAgentIds).containsExactly(release)
        // `create_agent` (proto spelling): the worker made, in the result.
        assertThat(calls.getValue("c9").linkedAgentIds).containsExactly(projects)
        // `getAgentStatus` (SDK spelling): the workers asked about and reported on.
        assertThat(calls.getValue("c10").linkedAgentIds).containsExactly(release, projects).inOrder()
        // The user-facing tool names nobody under either name.
        assertThat(calls.values.filter { CoordinatorLineage.isUserMessageTool(it.name) }.flatMap { it.linkedAgentIds }).isEmpty()

        assertThat(CoordinatorLineage.isCoordinator(items)).isTrue()
        assertThat(CoordinatorLineage.workerIds(items)).containsExactly(release, projects).inOrder()
        // Both are the coordinator's own: `create_agent` made one, `getAgentStatus` listed both.
        assertThat(CoordinatorLineage.createdWorkerIds(items)).containsExactly(projects, release)
    }
}
