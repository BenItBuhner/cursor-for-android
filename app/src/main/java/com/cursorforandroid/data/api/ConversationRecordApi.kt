package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
        val toolCall: ToolCallDto? = null,
        val finalToolResult: ToolResultDto? = null,
        val userMessage: UserMessageDto? = null,
        val humanMessage: HumanMessageDto? = null,
        val thinking: ThinkingDto? = null,
    ) {
        fun toStep(): HeadlessStep? = when {
            userMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = userMessage.text)
            humanMessage?.text?.isNotBlank() == true -> HeadlessStep(userMessage = humanMessage.text)
            toolCall != null && toolCall.toolCallId.isNotBlank() -> HeadlessStep(toolCall = HeadlessToolCall(toolCall.toolCallId, toolCall.name ?: "", toolCall.args()))
            finalToolResult != null && finalToolResult.toolCallId.isNotBlank() -> HeadlessStep(toolResult = HeadlessToolResult(finalToolResult.toolCallId, finalToolResult.result))
            thinking?.text?.isNotEmpty() == true -> HeadlessStep(thinking = thinking.text)
            !text.isNullOrEmpty() -> HeadlessStep(text = text)
            else -> null
        }
    }

    /** `ClientSideToolV2Call`: the arguments as the model wrote them, a JSON string, beside the typed `*_params`. */
    @Serializable
    private data class ToolCallDto(
        val toolCallId: String = "",
        val name: String? = null,
        val rawArgs: String? = null,
    ) {
        fun args(): JsonElement? = rawArgs?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { CursorJson.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
        }
    }

    @Serializable
    private data class ToolResultDto(val toolCallId: String = "", val result: JsonElement? = null)

    @Serializable
    private data class UserMessageDto(val text: String? = null)

    @Serializable
    private data class HumanMessageDto(val text: String? = null)

    @Serializable
    private data class ThinkingDto(val text: String? = null)

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"
    }
}
