package com.cursorforandroid.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The goal wire shapes under `src/test/resources/fixtures/goal/`, none of them written by hand: the `agent.v1` goal
 * messages (`CreateGoalArgs`, `CreateGoalResult`, `UpdateGoalArgs`, `UpdateGoalResult`, `GoalState`, `GoalStatus`)
 * extracted from Cursor's own SDK bundle (`@cursor/sdk` 1.0.31; `agent.v1.ToolCall` fields 70 `create_goal_tool_call`
 * and 71 `update_goal_tool_call`, `ConversationStateStructure` field 32 `goal_state`), instantiated with protobuf-es
 * and serialised the ways Cursor's TypeScript serialises them (`toJson()`, `JSON.stringify`, `useProtoFieldNames`,
 * `enumAsInteger`), then framed as:
 *
 *  - `goal_shapes.json`: the bodies alone, plus the tools' names under each client's vocabulary and the desktop's labels.
 *  - `goal_run.sse`, `goal_completed_run.sse`, `goal_refused_run.sse`: the documented stream's `tool_call` events in the
 *    shape the SDK's consumer reads (`{callId, name, status, args?, result?}`) — a goal set (`createGoal`), the
 *    continuation that completes it (`updateGoal`), and one the account refused, under the desktop's table name.
 *  - `goal_fetch_background_composer.json`: the account's record of the same turns (`FetchBackgroundComposer`), its
 *    `ClientSideToolV2Call` records named from the desktop's table (`create_goal`, `update_goal`), `rawArgs = JSON.stringify(args)`.
 *  - `goal_state.json`: `GetLatestAgentConversationStateResponse` in Connect JSON, the goal's corner of it, for an
 *    active, a paused and a complete goal, one with the enum as a number, and one with no goal at all.
 */
object GoalFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun text(name: String): String =
        requireNotNull(GoalFixtures::class.java.getResourceAsStream("/fixtures/goal/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    fun json(name: String): JsonObject = json.parseToJsonElement(text(name)).jsonObject

    /** One body of `goal_shapes.json`: `shape("createGoal", "args", "toJson")`. */
    fun shape(vararg path: String): JsonElement {
        var node: JsonElement = json("goal_shapes.json")
        for (key in path) node = node.jsonObject.getValue(key)
        return node
    }

    /** One `GetLatestAgentConversationStateResponse` of `goal_state.json`, as the account would answer it. */
    fun stateResponse(name: String): String = json("goal_state.json").getValue(name).toString()
}
