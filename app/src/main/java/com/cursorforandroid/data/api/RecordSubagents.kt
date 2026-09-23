package com.cursorforandroid.data.api

import com.cursorforandroid.domain.SubagentChild
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The in-VM subagents a chat's conversation state tracks, by the id of the task call that started each: the
 * state's `subagent_runs_by_parent_tool_call_id` (`agent.v1.SubagentRunState`: its status, title and detail) and
 * `communicate_update_states_by_parent_tool_call_id` (`CommunicateUpdateTurnState`: the steps it announced, the
 * newest last) — what the desktop's subagent row reads for a subagent with no cloud chat of its own. Proto3 JSON
 * writes the status by name, the blob's decoding by number; both are read.
 */
internal object RecordSubagents {

    fun of(runs: JsonElement?, updates: JsonElement?): Map<String, SubagentChild> = of(runs as? JsonObject ?: emptyMap(), updates as? JsonObject ?: emptyMap())

    fun of(runs: Map<String, JsonElement>, updates: Map<String, JsonElement>): Map<String, SubagentChild> {
        if (runs.isEmpty() && updates.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, SubagentChild>()
        for (callId in runs.keys + updates.keys) {
            if (callId.isBlank() || callId in out) continue
            val run = runs[callId] as? JsonObject
            val history = (updates[callId] as? JsonObject)?.get("history") as? JsonArray
            val step = history?.lastOrNull { (it as? JsonObject)?.text("step") != null }?.let { (it as JsonObject).text("step") }
            out[callId] = SubagentChild(
                status = status(run?.get("status")),
                step = step,
                action = run?.text("detail"),
                name = run?.text("title"),
            )
        }
        return out
    }

    /** `agent.v1.SubagentRunStatus`: running (a backgrounded run is one), success, error, aborted; null for unspecified. */
    fun status(raw: JsonElement?): SubagentChild.Status? {
        val word = (raw as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() ?: return null
        return when {
            word == "1" || word == "2" || word.endsWith("RUNNING") || word.endsWith("BACKGROUNDED") -> SubagentChild.Status.Running
            word == "3" || word.endsWith("SUCCESS") -> SubagentChild.Status.Succeeded
            word == "4" || word.endsWith("ERROR") -> SubagentChild.Status.Failed
            word == "5" || word.endsWith("ABORTED") -> SubagentChild.Status.Aborted
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
