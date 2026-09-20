package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.StepShape
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One step of the account's own copy of a chat's transcript (`HeadlessAgenticComposerResponse`), in the corner this
 * app reads: a prompt, a stretch of reply text, a thought, a tool call, a tool call's result, or the error the turn
 * ended in. Every field but the one the step carries is null. [shape] is the step as it came, keys and value types
 * only, for the transcript diagnostics (see [StepShape]).
 */
data class HeadlessStep(
    val userMessage: String? = null,
    val text: String? = null,
    val thinking: String? = null,
    val toolCall: HeadlessToolCall? = null,
    val toolResult: HeadlessToolResult? = null,
    /** The server's account of why the turn failed (`HeadlessAgenticComposerResponse.error.message`). */
    val error: String? = null,
    /**
     * The prompt was sent in Project mode (`ConversationMessage.agent_mode = AGENT_MODE_PROJECT`): the chat is a
     * Project's coordinator, whatever else its transcript carries. Only a prompt step says so.
     */
    val projectMode: Boolean = false,
    val shape: StepShape? = null,
)

/**
 * A tool call as the account records it: its id, the tool's name, and the arguments the model wrote, as JSON. A
 * call the model streamed — a coordinator's `SendMessage`, whose text the desktop shows as it is written — is
 * recorded as several responses for the same id, each with a piece of the arguments: [rawArgs] carries a piece that
 * is not JSON on its own, for `HeadlessTranscript` to join with the rest; [isLastMessage] marks the last of them.
 * [source] says where the id, the name and the arguments were read from (`id=toolCallId name=name args=json`), for
 * the diagnostics.
 */
data class HeadlessToolCall(
    val callId: String,
    val name: String,
    val args: JsonElement?,
    val rawArgs: String? = null,
    val isStreaming: Boolean = false,
    val isLastMessage: Boolean = false,
    val source: String = "",
)

/** What a tool call came back with, as JSON, by the call's id. */
data class HeadlessToolResult(val callId: String, val result: JsonElement?)

/** One page of the record: [steps] from [startIndex] on, out of [totalResponses] the account holds for the chat. */
data class HeadlessPage(val steps: List<HeadlessStep>, val startIndex: Int, val totalResponses: Int)

/** One turn's timing as the conversation state keeps it (`agent.v1.StepTiming`): how long it ran, and when it ended. */
data class TurnTiming(val durationMs: Long?, val timestampMs: Long?)

/**
 * The account's latest word on a chat's conversation (`GetLatestAgentConversationState`), in the corner this app
 * reads: how many turns the chat has ([turnCount], one per prompt), each turn's timing, whether a tool call is
 * pending (the agent is mid-step), whether the chat is a Project's root conversation, and the two counters that say
 * how far the live stream has gone ([numPriorInteractionUpdates]) and whether the chat was rewound ([rewindEpoch]).
 */
data class RecordState(
    val turnCount: Int,
    val timings: List<TurnTiming>,
    val pendingToolCalls: Int,
    val isRootProject: Boolean,
    val numPriorInteractionUpdates: Long,
    val rewindEpoch: Long,
)

/**
 * The account's transcript of a chat, tool calls included, which outlives the documented stream's retention window.
 * An interface so the conversation repository can be tested against a fake.
 */
interface ConversationRecordApi {
    /** `FetchBackgroundComposer {bc_id, start_index, limit}`: [limit] steps from [startIndex], oldest first. */
    suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage

    /** `GetLatestAgentConversationState {bc_id}`: the chat's turn count, timings and pending work (see [RecordState]). */
    suspend fun state(agentId: String): RecordState
}

/**
 * `aiserver.v1.BackgroundComposerService/FetchBackgroundComposer`: the headless-composer transcript the account
 * keeps of every chat — `text`, `tool_call` / `final_tool_result` pairs, `thinking`, the `user_message` (or
 * `human_message`) that starts each turn — indexed and paged by `start_index` / `limit`, with `total_responses` for
 * the whole. Extended mode only, behind the `accountTranscript` capability: the documented `/v0` transcript carries
 * text alone and the documented run log expires, so this is the one place a turn's tool calls come back from once
 * both are gone.
 */
class HeadlessConversationApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ConversationRecordApi {

    override suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage {
        val from = startIndex.coerceAtLeast(0)
        // A refusal is heard at once: the transcript has the documented endpoints to fall back on, and the pause the
        // refusal asks for is waited out by the next record read, not by this open (see ApiThrottle.call).
        val response = rpc.unaryWithSession(
            SERVICE,
            "FetchBackgroundComposer",
            tokens,
            FetchRequestDto(bcId = agentId, startIndex = from, limit = limit.coerceAtLeast(1)),
            FetchRequestDto.serializer(),
            FetchResponseDto.serializer(),
            retryRefusals = false,
        )
        // One step per response, a blank one for a response this app reads nothing from (a status, a done marker):
        // the record is paged by index, and a page's steps must line up with the indices it was asked for.
        return HeadlessPage(response.responses.mapIndexed { i, json -> parseStep(json, from + i) }, from, response.totalResponses ?: response.responses.size)
    }

    override suspend fun state(agentId: String): RecordState {
        val response = rpc.unaryWithSession(
            SERVICE,
            "GetLatestAgentConversationState",
            tokens,
            StateRequestDto(bcId = agentId),
            StateRequestDto.serializer(),
            StateResponseDto.serializer(),
            retryRefusals = false,
        )
        val latest = response.latestConversationState
        val conversation = latest?.conversationState
        return RecordState(
            turnCount = conversation?.turns?.size ?: 0,
            timings = conversation?.turnTimings?.map { TurnTiming(it.durationMs?.toLongLenient(), it.timestampMs?.toLongLenient()) } ?: emptyList(),
            pendingToolCalls = conversation?.pendingToolCalls?.size ?: 0,
            isRootProject = conversation?.isRootProjectConversation == true,
            numPriorInteractionUpdates = latest?.numPriorInteractionUpdates?.toLongLenient() ?: 0L,
            rewindEpoch = latest?.conversationRewindEpoch?.toLongLenient() ?: 0L,
        )
    }

    @Serializable
    private data class FetchRequestDto(val bcId: String, val startIndex: Int, val limit: Int)

    @Serializable
    private data class StateRequestDto(val bcId: String)

    @Serializable
    private data class StateResponseDto(val latestConversationState: LatestStateDto? = null)

    /** `aiserver.v1.LatestAgentConversationState`: the counters are `uint32`, written as numbers or as strings by an encoder. */
    @Serializable
    private data class LatestStateDto(
        val conversationState: ConversationStateDto? = null,
        val numPriorInteractionUpdates: JsonElement? = null,
        val conversationRewindEpoch: JsonElement? = null,
    )

    /** `agent.v1.ConversationStateStructure`, of which the turn list (blob ids, counted only), the timings and the pending calls are read. */
    @Serializable
    private data class ConversationStateDto(
        val turns: List<JsonElement> = emptyList(),
        val turnTimings: List<TimingDto> = emptyList(),
        val pendingToolCalls: List<JsonElement> = emptyList(),
        val isRootProjectConversation: Boolean? = null,
    )

    /** `agent.v1.StepTiming`: `uint64`s, which proto3 JSON writes as strings. */
    @Serializable
    private data class TimingDto(val durationMs: JsonElement? = null, val timestampMs: JsonElement? = null)

    /** The page as it came: each response kept as JSON, so its shape can be described whatever keys it carries (see [parseStep]). */
    @Serializable
    private data class FetchResponseDto(
        val responses: List<JsonObject> = emptyList(),
        val totalResponses: Int? = null,
    )

    /**
     * The oneof-ish shape of `HeadlessAgenticComposerResponse`, each field present on the steps of its kind. A tool
     * call arrives as `tool_call` (`ClientSideToolV2Call`) or, for a call the runner streamed back piece by piece, as
     * `streamed_back_tool_call` (`StreamedBackToolCall {tool, tool_call_id, name, raw_args, …}`): both name the call
     * and carry its arguments, and both are steps of the call. `error` is the server's word on a turn that failed.
     * Everything else with a tool call's id but nothing of its own — a `status`, an `is_message_done` marker — is a
     * blank step.
     */
    @Serializable
    private data class ResponseDto(
        val text: String? = null,
        /** `ClientSideToolV2Call`, read whole: its typed `*Params` member is named after the tool (see [toolCall]). */
        val toolCall: JsonObject? = null,
        val streamedBackToolCall: JsonObject? = null,
        val finalToolResult: ToolResultDto? = null,
        val userMessage: UserMessageDto? = null,
        val humanMessage: HumanMessageDto? = null,
        val thinking: ThinkingDto? = null,
        val error: ErrorDto? = null,
        val status: JsonElement? = null,
        val isMessageDone: Boolean? = null,
    ) {
        /** The step this response is, with the name of the branch that read it (see [StepShape.branch]); a blank step for one nothing reads. */
        fun read(): Pair<HeadlessStep, String> = when {
            userMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = userMessage.text) to "user_message"
            // A `ConversationMessage` typed as the model's is its text, not a prompt, whatever member carries it.
            humanMessage?.text?.isNotBlank() == true && humanMessage.isAssistant -> HeadlessStep(text = humanMessage.text) to "human_message(ai)"
            humanMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = humanMessage.text, projectMode = humanMessage.isProjectMode) to "human_message"
            toolCall != null -> readToolCall(toolCall).let { call -> (call?.let { HeadlessStep(toolCall = it) } ?: HeadlessStep()) to "tool_call[${call?.source ?: DROPPED}]" }
            streamedBackToolCall != null -> readToolCall(streamedBackToolCall).let { call -> (call?.let { HeadlessStep(toolCall = it.copy(isStreaming = true)) } ?: HeadlessStep()) to "streamed_back_tool_call[${call?.source ?: DROPPED}]" }
            finalToolResult != null && finalToolResult.toolCallId.isNotBlank() -> HeadlessStep(toolResult = HeadlessToolResult(finalToolResult.toolCallId, finalToolResult.result)) to "final_tool_result"
            finalToolResult != null -> HeadlessStep() to "final_tool_result[$DROPPED]"
            thinking?.text?.isNotEmpty() == true -> HeadlessStep(thinking = thinking.text) to "thinking"
            error?.message?.isNotBlank() == true -> HeadlessStep(error = error.message) to "error"
            !text.isNullOrEmpty() -> HeadlessStep(text = text) to "text"
            status != null -> HeadlessStep() to "blank(status)"
            isMessageDone == true -> HeadlessStep() to "blank(message_done)"
            else -> HeadlessStep() to "blank"
        }
    }

    @Serializable
    private data class ToolResultDto(val toolCallId: String = "", val result: JsonElement? = null)

    /** `HeadlessAgenticComposerResponse.Error {message, error_details}`: the message is the reason the turn failed, in the server's words. */
    @Serializable
    private data class ErrorDto(val message: String? = null)

    @Serializable
    private data class UserMessageDto(val text: String? = null)

    /**
     * `ConversationMessage`, of which the text, the mode and the type are read: `agent_mode` and `type`
     * (`MessageType`: HUMAN 1, AI 2) are enums, by name or by number.
     */
    @Serializable
    private data class HumanMessageDto(val text: String? = null, val agentMode: JsonElement? = null, val type: JsonElement? = null) {
        val isProjectMode: Boolean get() = readProjectMode(agentMode)
        val isAssistant: Boolean get() = readAssistantType(type)
    }

    @Serializable
    private data class ThinkingDto(val text: String? = null)

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"

        /** `AgentMode.AGENT_MODE_PROJECT` — by its name in Connect JSON, or by its number (6) when an encoder writes enums so. */
        internal const val AGENT_MODE_PROJECT = 6

        /** `ClientSideToolV2.SEND_TO_USER` (65), the one coordinator tool the legacy enum names. */
        private const val CLIENT_SIDE_TOOL_SEND_TO_USER = 65

        /** The branch's word for a call or result step nothing could be read from: it carried no id of any kind. */
        internal const val DROPPED = "dropped:no-id"

        /**
         * One response of the record as this app reads it, with its shape kept beside it: the typed reading of the
         * fields this build knows, and the keys and value types of everything the response carried, known or not
         * (see [RecordShapes]). [index] is the step's place in the record.
         */
        internal fun parseStep(json: JsonObject, index: Int): HeadlessStep {
            val (step, branch) = runCatching { CursorJson.decodeFromJsonElement(ResponseDto.serializer(), json).read() }
                .getOrElse { HeadlessStep() to "blank(unreadable)" }
            return step.copy(shape = StepShape(index, branch, RecordShapes.describe(json)))
        }

        internal fun readProjectMode(mode: JsonElement?): Boolean {
            val primitive = mode as? JsonPrimitive ?: return false
            primitive.intOrNull?.let { return it == AGENT_MODE_PROJECT }
            return primitive.contentOrNull?.uppercase()?.let { it == "PROJECT" || it.endsWith("_PROJECT") } == true
        }

        /** `ConversationMessage.MessageType.MESSAGE_TYPE_AI` (2): the message is the model's, by name or by number. */
        internal fun readAssistantType(type: JsonElement?): Boolean {
            val primitive = type as? JsonPrimitive ?: return false
            primitive.intOrNull?.let { return it == MESSAGE_TYPE_AI }
            return primitive.contentOrNull?.uppercase()?.let { it == "AI" || it.endsWith("_AI") } == true
        }

        /** `ConversationMessage.MessageType.MESSAGE_TYPE_AI`. */
        internal const val MESSAGE_TYPE_AI = 2

        /**
         * `aiserver.v1.ClientSideToolV2Call` as this app reads it: the id, the tool's name and its arguments.
         *
         * The name is the model-facing one the record carries (`SendMessage`, `read_file`, …). When it is blank, the
         * tool is named from what else the record says: the `tool` enum by name (`CLIENT_SIDE_TOOL_V2_SEND_TO_USER`
         * → `send_to_user`) or number, else the typed `*Params` member the oneof filled (`readFileV2Params` →
         * `read_file_v2`). The arguments are `raw_args` as the model wrote them; when that is blank the typed params
         * stand in, their fields being the ones the mappers already read.
         */
        internal fun readToolCall(call: JsonObject): HeadlessToolCall? {
            // The call's id, or the model's id for it when the record carries no other: what its result names it by.
            val idSource = when {
                call.string("toolCallId") != null -> "toolCallId"
                call.string("modelCallId") != null -> "modelCallId"
                else -> return null
            }
            val id = call.string(idSource)!!
            val params = call.entries.firstOrNull { (key, value) -> (key.endsWith("Params") || key.endsWith("Stream")) && value is JsonObject }
            var nameSource = "name"
            val name = call.string("name")
                ?: toolEnumName(call["tool"])?.also { nameSource = "tool" }
                ?: params?.key?.removeSuffix("Params")?.removeSuffix("Stream")?.let(::snakeCase)?.also { nameSource = "params" }
                ?: "".also { nameSource = "blank" }
            val raw = call.string("rawArgs")
            // Arguments that are not JSON on their own are a piece of a streamed call's, kept as text to be joined.
            val parsed = raw?.let { text -> runCatching { CursorJson.parseToJsonElement(text) }.getOrNull()?.takeIf { it is JsonObject } }
            val typed = params?.takeIf { it.key.endsWith("Params") }?.value
            val args = parsed ?: typed
            val argsSource = when {
                parsed != null -> "json"
                typed != null -> "params"
                raw != null -> "piece"
                else -> "none"
            }
            return HeadlessToolCall(
                id,
                name,
                args,
                rawArgs = raw?.takeIf { parsed == null },
                isStreaming = call.boolean("isStreaming"),
                isLastMessage = call.boolean("isLastMessage"),
                source = "id=$idSource name=$nameSource args=$argsSource",
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true

        /** The tool's name from the legacy enum: `CLIENT_SIDE_TOOL_V2_READ_FILE_V2` → `read_file_v2`; a number only when it is the one coordinator tool the enum has. */
        private fun toolEnumName(tool: JsonElement?): String? {
            val primitive = tool as? JsonPrimitive ?: return null
            primitive.intOrNull?.let { return if (it == CLIENT_SIDE_TOOL_SEND_TO_USER) "send_to_user" else null }
            val name = primitive.contentOrNull?.trim()?.removePrefix("CLIENT_SIDE_TOOL_V2_")?.lowercase() ?: return null
            return name.takeIf { it.isNotEmpty() && it != "unspecified" }
        }

        private fun snakeCase(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

        /** A proto integer as Connect JSON writes it: a number, or a string for the 64-bit kinds. */
        internal fun JsonElement.toLongLenient(): Long? {
            val primitive = this as? JsonPrimitive ?: return null
            return primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull() ?: primitive.doubleOrNull?.toLong()
        }
    }
}
