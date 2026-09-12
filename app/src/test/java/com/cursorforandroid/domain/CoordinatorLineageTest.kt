package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test

/** What a Project's own transcript says about its workers: the one lineage signal the documented stream carries. */
class CoordinatorLineageTest {

    private fun call(name: String, vararg linked: String) = ToolCall(callId = "c-$name-${linked.joinToString("-")}", name = name, kind = ToolKind.Other, status = "completed", summary = "", linkedAgentIds = linked.toList())

    @Test
    fun `a transcript with the coordinator's tools is a coordinator's, and the ids in them are its workers, once each`() {
        val items = listOf(
            UserMessage("u1", "Kick off the launch"),
            ActivityGroup("a1", listOf(ThinkingBlock("Two tracks."), call("create_agent", "bc-w1"), call("create_agent", "bc-w2"))),
            AssistantMessage("m1", "Started two workers."),
            UserMessage("u2", "Status?"),
            ActivityGroup("a2", listOf(call("get_agent_status", "bc-w1", "bc-w2", "bc-w3"), call("send_to_agent", "bc-w1"), call("read_file"))),
            RunFooter("f2", "run-2", RunStatus.FINISHED, 1_000L, emptyList()),
        )
        assertThat(CoordinatorLineage.isCoordinator(items)).isTrue()
        assertThat(CoordinatorLineage.workerIds(items)).containsExactly("bc-w1", "bc-w2", "bc-w3").inOrder()
    }

    @Test
    fun `an ordinary chat's transcript says nothing, and neither does another tool naming an agent`() {
        val plain = listOf(
            UserMessage("u1", "Fix the test"),
            ActivityGroup("a1", listOf(call("read_file"), call("edit_file"), call("run_terminal_cmd"), call("task", "bc-sub"))),
            AssistantMessage("m1", "Fixed."),
        )
        assertThat(CoordinatorLineage.isCoordinator(plain)).isFalse()
        assertThat(CoordinatorLineage.workerIds(plain)).isEmpty()
        assertThat(CoordinatorLineage.isCoordinatorTool("create_agent")).isTrue()
        assertThat(CoordinatorLineage.isCoordinatorTool("createAgentToolCall")).isFalse()
        assertThat(CoordinatorLineage.isCoordinatorTool("stop_agent_tool_call")).isTrue()
        assertThat(CoordinatorLineage.isCoordinatorTool("fetch_cloud_agent_data")).isFalse()
        // A coordinator that has created nothing yet is still a coordinator.
        val early = listOf(ActivityGroup("a1", listOf(call("get_agent_status"))))
        assertThat(CoordinatorLineage.isCoordinator(early)).isTrue()
        assertThat(CoordinatorLineage.workerIds(early)).isEmpty()
    }

    @Test
    fun `the ids ride along in the trace kept on disk, and a trace from before they were kept still reads`() {
        val serializer = ListSerializer(TimelineItem.serializer())
        val items: List<TimelineItem> = listOf(ActivityGroup("a1", listOf(call("create_agent", "bc-w1"))))
        val json = CursorJson.encodeToString(serializer, items)
        assertThat(json).contains(""""linkedAgentIds":["bc-w1"]""")
        assertThat(CoordinatorLineage.workerIds(CursorJson.decodeFromString(serializer, json))).containsExactly("bc-w1")
        val old = """[{"type":"activity","id":"a1","steps":[{"type":"tool","callId":"c","name":"create_agent","kind":"Other","status":"completed","summary":""}]}]"""
        val decoded = CursorJson.decodeFromString(serializer, old)
        assertThat(CoordinatorLineage.isCoordinator(decoded)).isTrue()
        assertThat(CoordinatorLineage.workerIds(decoded)).isEmpty()
    }
}
