package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoEncoder
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.domain.SubagentChild
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The in-VM subagents a chat's state tracks (Extended mode, the Beta engine): `subagent_runs_by_parent_tool_call_id`
 * and `communicate_update_states_by_parent_tool_call_id`, keyed by the task call that started each — what the row
 * under a task reads when the subagent has no cloud chat of its own to follow.
 */
class RecordSubagentsTest {

    private val state = Json.parseToJsonElement(
        """
        {
          "subagentRunsByParentToolCallId": {
            "toolu_task_1": {"parentToolCallId": "toolu_task_1", "subagentId": "sa-1", "environment": "1", "status": "1", "title": "CursorBench chart hover highlight", "detail": "Editing Chart.kt"},
            "toolu_task_2": {"parentToolCallId": "toolu_task_2", "subagentId": "sa-2", "status": "3", "title": "Audit the tests"},
            "toolu_task_3": {"parentToolCallId": "toolu_task_3", "subagentId": "sa-3", "status": "4", "title": "Flaky test hunt", "completionReason": "crashed"}
          },
          "communicateUpdateStatesByParentToolCallId": {
            "toolu_task_1": {"history": [{"step": "Reading the chart", "messageIndex": 1}, {"step": "Wiring the hover state", "messageIndex": 4}]}
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `the state's blob decodes to each subagent's status, step and action`() {
        // Written as the account's blob is, read back through the schema the app reads it with.
        val decoded = ProtoWire.decode(ProtoEncoder.encode(state, AgentSchemas.CONVERSATION_STATE), AgentSchemas.CONVERSATION_STATE)
        val subagents = RecordSubagents.of(decoded["subagentRunsByParentToolCallId"], decoded["communicateUpdateStatesByParentToolCallId"])
        assertThat(subagents.keys).containsExactly("toolu_task_1", "toolu_task_2", "toolu_task_3").inOrder()
        assertThat(subagents.getValue("toolu_task_1")).isEqualTo(
            SubagentChild(SubagentChild.Status.Running, step = "Wiring the hover state", action = "Editing Chart.kt", name = "CursorBench chart hover highlight"),
        )
        assertThat(subagents.getValue("toolu_task_2").status).isEqualTo(SubagentChild.Status.Succeeded)
        assertThat(subagents.getValue("toolu_task_3").status).isEqualTo(SubagentChild.Status.Failed)
    }

    @Test
    fun `the inline state spells the status by name, and a step with no run yet still counts`() {
        val inline = Json.parseToJsonElement(
            """
            {
              "subagentRunsByParentToolCallId": {"a": {"status": "SUBAGENT_RUN_STATUS_BACKGROUNDED"}, "b": {"status": "SUBAGENT_RUN_STATUS_ABORTED"}, "c": {"status": "SUBAGENT_RUN_STATUS_UNSPECIFIED"}},
              "communicateUpdateStatesByParentToolCallId": {"d": {"history": [{"step": "Planning the migration"}]}}
            }
            """.trimIndent(),
        ) as JsonObject
        val subagents = RecordSubagents.of(inline["subagentRunsByParentToolCallId"], inline["communicateUpdateStatesByParentToolCallId"])
        assertThat(subagents.getValue("a").status).isEqualTo(SubagentChild.Status.Running)
        assertThat(subagents.getValue("b").status).isEqualTo(SubagentChild.Status.Aborted)
        assertThat(subagents.getValue("c").status).isNull()
        assertThat(subagents.getValue("d")).isEqualTo(SubagentChild(step = "Planning the migration"))
    }

    @Test
    fun `a state without them, or with them in a shape this build does not expect, has none`() {
        assertThat(RecordSubagents.of(null, null)).isEmpty()
        assertThat(RecordSubagents.of(Json.parseToJsonElement("[]"), Json.parseToJsonElement("\"x\""))).isEmpty()
        assertThat(RecordSubagents.status(null)).isNull()
    }
}
