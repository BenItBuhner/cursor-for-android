package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One step of the account's own copy of a chat's transcript (`HeadlessAgenticComposerResponse`), in the corner this
 * app reads: a prompt, a stretch of reply text, a thought, a tool call, or a tool call's result. Every field but the
 * one the step carries is null.
 */
data class HeadlessStep(
    val userMessage: String? = null,
    val text: String? = null,
    val thinking: String? = null,
    val toolCall: HeadlessToolCall? = null,
    val toolResult: HeadlessToolResult? = null,
    /**
     * The prompt was sent in Project mode (`ConversationMessage.agent_mode = AGENT_MODE_PROJECT`): the chat is a
     * Project's coordinator, whatever else its transcript carries. Only a prompt step says so.
     */
    val projectMode: Boolean = false,
)

/** A tool call as the account records it: its id, the tool's name, and the arguments the model wrote, as JSON. */
data class HeadlessToolCall(val callId: String, val name: String, val args: JsonElement?)

/** What a tool call came back with, as JSON, by the call's id. */
data class HeadlessToolResult(val callId: String, val result: JsonElement?)

/** One page of the record: [steps] from [startIndex] on, out of [totalResponses] the account holds for the chat. */
data class HeadlessPage(val steps: List<HeadlessStep>, val startIndex: Int, val totalResponses: Int)

/**
 * The account's transcript of a chat, tool calls included, which outlives the documented stream's retention window.
 * An interface so the conversation repository can be tested against a fake.
 */
interface ConversationRecordApi {
    /** `FetchBackgroundComposer {bc_id, start_index, limit}`: [limit] steps from [startIndex], oldest first. */
    suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage
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
        val response = rpc.unaryWithSession(
            SERVICE,
            "FetchBackgroundComposer",
            tokens,
            FetchRequestDto(bcId = agentId, startIndex = startIndex.coerceAtLeast(0), limit = limit.coerceAtLeast(1)),
            FetchRequestDto.serializer(),
            FetchResponseDto.serializer(),
        )
        return HeadlessPage(response.responses.mapNotNull { it.toStep() }, startIndex.coerceAtLeast(0), response.totalResponses ?: response.responses.size)
    }

    @Serializable
    private data class FetchRequestDto(val bcId: String, val startIndex: Int, val limit: Int)

    @Serializable
    private data class FetchResponseDto(
        val responses: List<ResponseDto> = emptyList(),
        val totalResponses: Int? = null,
    )

    /** The oneof-ish shape of `HeadlessAgenticComposerResponse`, each field present on the steps of its kind. */
    @Serializable
    private data class ResponseDto(
        val text: String? = null,
        /** `ClientSideToolV2Call`, read whole: its typed `*Params` member is named after the tool (see [toolCall]). */
        val toolCall: JsonObject? = null,
        val finalToolResult: ToolResultDto? = null,
        val userMessage: UserMessageDto? = null,
        val humanMessage: HumanMessageDto? = null,
        val thinking: ThinkingDto? = null,
    ) {
        fun toStep(): HeadlessStep? = when {
            userMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = userMessage.text)
            humanMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = humanMessage.text, projectMode = humanMessage.isProjectMode)
            toolCall != null -> readToolCall(toolCall)?.let { HeadlessStep(toolCall = it) }
            finalToolResult != null && finalToolResult.toolCallId.isNotBlank() -> HeadlessStep(toolResult = HeadlessToolResult(finalToolResult.toolCallId, finalToolResult.result))
            thinking?.text?.isNotEmpty() == true -> HeadlessStep(thinking = thinking.text)
            !text.isNullOrEmpty() -> HeadlessStep(text = text)
            else -> null
        }
    }

    @Serializable
    private data class ToolResultDto(val toolCallId: String = "", val result: JsonElement? = null)

    @Serializable
    private data class UserMessageDto(val text: String? = null)

    /** `ConversationMessage`, of which the text and the mode are read: `agent_mode` is an enum, by name or by number. */
    @Serializable
    private data class HumanMessageDto(val text: String? = null, val agentMode: JsonElement? = null) {
        val isProjectMode: Boolean get() = readProjectMode(agentMode)
    }

    @Serializable
    private data class ThinkingDto(val text: String? = null)

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"

        /** `AgentMode.AGENT_MODE_PROJECT` — by its name in Connect JSON, or by its number (6) when an encoder writes enums so. */
        internal const val AGENT_MODE_PROJECT = 6

        /** `ClientSideToolV2.SEND_TO_USER` (65), the one coordinator tool the legacy enum names. */
        private const val CLIENT_SIDE_TOOL_SEND_TO_USER = 65

        internal fun readProjectMode(mode: JsonElement?): Boolean {
            val primitive = mode as? JsonPrimitive ?: return false
            primitive.intOrNull?.let { return it == AGENT_MODE_PROJECT }
            return primitive.contentOrNull?.uppercase()?.let { it == "PROJECT" || it.endsWith("_PROJECT") } == true
        }

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
            val id = (call["toolCallId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val params = call.entries.firstOrNull { (key, value) -> key.endsWith("Params") && value is JsonObject }
            val name = (call["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: toolEnumName(call["tool"])
                ?: params?.key?.removeSuffix("Params")?.let(::snakeCase)
                ?: ""
            val raw = (call["rawArgs"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val args = raw?.let { text -> runCatching { CursorJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) } } ?: params?.value
            return HeadlessToolCall(id, name, args)
        }

        /** The tool's name from the legacy enum: `CLIENT_SIDE_TOOL_V2_READ_FILE_V2` → `read_file_v2`; a number only when it is the one coordinator tool the enum has. */
        private fun toolEnumName(tool: JsonElement?): String? {
            val primitive = tool as? JsonPrimitive ?: return null
            primitive.intOrNull?.let { return if (it == CLIENT_SIDE_TOOL_SEND_TO_USER) "send_to_user" else null }
            val name = primitive.contentOrNull?.trim()?.removePrefix("CLIENT_SIDE_TOOL_V2_")?.lowercase() ?: return null
            return name.takeIf { it.isNotEmpty() && it != "unspecified" }
        }

        private fun snakeCase(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }
}
