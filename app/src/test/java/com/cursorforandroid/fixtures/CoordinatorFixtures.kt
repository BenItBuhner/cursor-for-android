package com.cursorforandroid.fixtures

import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.repo.TimelineBuilder
import com.cursorforandroid.domain.TimelineItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

/**
 * The coordinator wire shapes under `src/test/resources/fixtures/coordinator/`, none of them written by hand:
 *
 *  - `coordinator_run.sse` and `coordinator_shapes.json`: the `agent.v1` coordinator tool messages
 *    (`SendMessageToolCall`, `SendToUserToolCall`, `SendToAgentToolCall`, `CreateAgentToolCall`,
 *    `GetAgentStatusToolCall`) extracted from Cursor's own SDK bundle (`@cursor/sdk` 1.0.31), instantiated with
 *    protobuf-es and serialised the ways Cursor's TypeScript serialises them (`toJson()`, `JSON.stringify`,
 *    `useProtoFieldName`), framed as the documented stream's `tool_call` events in the shape the SDK's stream consumer
 *    reads (`{callId, name, status, args?, result?, truncated?}`), under both names the clients give each tool.
 *  - `fetch_background_composer.json`: the account's record (`FetchBackgroundComposer`) of the same turn, its
 *    `ClientSideToolV2Call` records shaped as Cursor's desktop converts an `agent.v1.ToolCall` to one (`name` from
 *    its model-facing table, `rawArgs = JSON.stringify(args)`, `tool` UNSPECIFIED for `SendMessage`, SEND_TO_USER
 *    for `send_to_user`), and the prompts' `agentMode`.
 *  - `injected_turns.json`: the `user_message` texts Cursor injects into a coordinator's conversation, taken from
 *    the real transcript of this repository's own Project coordinator (subagent completions under both instruction
 *    templates, a subscribed pull request's change), the report's own text replaced.
 */
object CoordinatorFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun text(name: String): String =
        requireNotNull(CoordinatorFixtures::class.java.getResourceAsStream("/fixtures/coordinator/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    fun json(name: String): JsonObject = json.parseToJsonElement(text(name)).jsonObject

    /** The bodies alone: `shapes("sendMessage", "text", "toJson")`. */
    fun shape(vararg path: String): JsonObject {
        var node: JsonObject = json("coordinator_shapes.json")
        for (key in path) node = node.getValue(key).jsonObject
        return node
    }

    /** The injected turns by name, as `GET /v0/agents/{id}/conversation` would hand each back as a `user_message`. */
    fun injectedTurns(): Map<String, String> =
        json.parseToJsonElement(text("injected_turns.json")).jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("text").jsonPrimitive.content }

    fun injectedTurn(name: String): String = injectedTurns().getValue(name)

    fun array(obj: JsonObject, key: String): JsonArray = obj.getValue(key).jsonArray

    /**
     * The run of an SSE fixture (`coordinator_run.sse`, or `agent_run.sse` — the first turn of a real worker, its
     * kinds and tool names as they occurred) replayed through the same accumulator the stream's events go through.
     */
    fun replay(name: String, runId: String, skipCallIds: Set<String> = emptySet()): List<TimelineItem> {
        val source = Buffer().writeUtf8(text(name))
        val live = TimelineBuilder.LiveRun(runId, timed = false)
        while (true) {
            val frame = SseParser.readFrame(source) ?: break
            val event = (SseParser.parse(frame) as? SseParser.Parsed.Delivered)?.event ?: continue
            if (event is RunStreamEvent.ToolCall && event.call.callId in skipCallIds) continue
            live.apply(event)
        }
        return live.snapshot()
    }
}
