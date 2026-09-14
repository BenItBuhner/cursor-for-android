package com.cursorforandroid.domain

import com.cursorforandroid.data.api.CursorJson
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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

    /**
     * Every spelling the wire uses for the coordinator's worker-naming tools: the proto's snake case, the SDK
     * vocabulary of the documented stream (`sendToAgent`, `createAgent`, `getAgentStatus`, `stopAgent`,
     * `readAgentTranscript`), the desktop's model-facing table (`CreateAgent`), with or without a `ToolCall`
     * suffix — see `internal/coordinator-transcript-wire-shapes.md`. Until 0.3.7 only the snake case matched, so a
     * live stream named no worker and default mode learned no lineage from a coordinator's transcript.
     */
    @Test
    fun `the coordinator's tools match under the proto's, the SDK's and the desktop's spellings`() {
        val spellings = mapOf(
            "create_agent" to listOf("createAgent", "CreateAgent", "createAgentToolCall", "create_agent_tool_call", "CREATE_AGENT"),
            "send_to_agent" to listOf("sendToAgent", "SendToAgent", "sendToAgentToolCall", "send_to_agent_tool_call"),
            "get_agent_status" to listOf("getAgentStatus", "GetAgentStatus", "getAgentStatusToolCall"),
            "stop_agent" to listOf("stopAgent", "StopAgent", "stopAgentToolCall"),
            "read_agent_transcript" to listOf("readAgentTranscript", "ReadAgentTranscript", "readAgentTranscriptToolCall"),
        )
        spellings.forEach { (proto, others) ->
            assertThat(CoordinatorLineage.isCoordinatorTool(proto)).isTrue()
            others.forEach { name -> assertWithMessage(name).that(CoordinatorLineage.isCoordinatorTool(name)).isTrue() }
        }
        // The user-facing tool names no worker, under either of its names and spellings; other tools are not the coordinator's.
        listOf("sendMessage", "SendMessage", "send_message", "sendToUser", "send_to_user", "SendMessageToolCall").forEach { name ->
            assertThat(CoordinatorLineage.isUserMessageTool(name)).isTrue()
            assertThat(CoordinatorLineage.isCoordinatorTool(name)).isFalse()
        }
        listOf("read_file", "task", "mcp_slack_send_message", "communicateUpdate", "sendFinalSummary", "fetchCloudAgentData").forEach { name ->
            assertThat(CoordinatorLineage.isCoordinatorTool(name)).isFalse()
            assertThat(CoordinatorLineage.isUserMessageTool(name)).isFalse()
        }

        // The live stream's turn, as the fixture off Cursor's protos carries it: `sendToAgent`, `getAgentStatus`
        // and `create_agent` name the two workers; both are the coordinator's own by `create_agent` and the status list.
        val items = listOf(
            ActivityGroup(
                "a1",
                listOf(
                    call("sendToAgent", "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671"),
                    ToolCall("c9", "create_agent", ToolKind.Coordinator, "completed", "Build Projects under Extended mode",
                        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Created, listOf(WorkerStatus(agentId = "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724", name = "Build Projects under Extended mode")), reported = true),
                        linkedAgentIds = listOf("bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724")),
                    ToolCall("c10", "getAgentStatus", ToolKind.Coordinator, "completed", "2 agents",
                        payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Status, listOf(WorkerStatus(agentId = "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671"), WorkerStatus(agentId = "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724")), reported = true),
                        linkedAgentIds = listOf("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671", "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724")),
                    call("sendMessage"),
                ),
            ),
        )
        assertThat(CoordinatorLineage.isCoordinator(items)).isTrue()
        assertThat(CoordinatorLineage.workerIds(items)).containsExactly("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671", "bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724").inOrder()
        assertThat(CoordinatorLineage.createdWorkerIds(items)).containsExactly("bc-4bb9edb1-ccec-5ae4-80f8-8f515daa7724", "bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")

        // A trace an older build kept under the coordinator kind with its payload but an unrecognised name still counts.
        val kept = listOf(ActivityGroup("a2", listOf(ToolCall("k1", "SendToAgentV2", ToolKind.Coordinator, "completed", "", payload = ToolPayload.WorkerAction(ToolPayload.WorkerAction.Kind.Messaged, listOf(WorkerStatus(agentId = "bc-old")))))))
        assertThat(CoordinatorLineage.isCoordinator(kept)).isTrue()
        assertThat(CoordinatorLineage.workerIds(kept)).containsExactly("bc-old")
    }
}
