package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.AgentMode
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.InteractionResolution
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.SteerOutcome
import com.cursorforandroid.domain.ToolPayload
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.UUID

/** Answering an agent's question from here: `SubmitInteractionResponseBackgroundComposer`. An interface so the repositories can be faked. */
fun interface InteractionApi {
    /**
     * Answers the `ask_question` call [toolCallId] of [agentId] with [answers], one per question asked; returns what
     * the account did with it.
     */
    suspend fun answerQuestion(agentId: String, toolCallId: String, answers: List<ToolPayload.Question.Answer>): InteractionResolution
}

/** What a follow-up filed through the account service carries beyond its text. */
data class AccountFollowup(
    val text: String,
    val images: List<PromptImage> = emptyList(),
    /** The mode the message goes out under; null keeps the chat's. */
    val mode: AgentMode? = null,
    /** The model the chat switches to from this run on; null keeps the current one. */
    val modelId: String? = null,
    /** Client-minted, so a retry after a lost reply files nothing twice. */
    val followupId: String = "fu-${UUID.randomUUID()}",
)

/**
 * The account's queue for a chat, the one the desktop, the web and the iOS app share: `AddAsyncFollowupBackgroundComposer`
 * files a follow-up (queued behind the turn under way, or, [synchronous], sent now in its place), and the six
 * `*PendingFollowup*` methods read and edit what is waiting.
 */
interface FollowupQueueApi {
    /** Files [followup]; returns the run it started, when the account named one (a message queued behind a turn has none yet). */
    suspend fun addFollowup(agentId: String, followup: AccountFollowup, synchronous: Boolean): String?

    suspend fun listPending(agentId: String): List<PendingFollowup>

    suspend fun updatePending(agentId: String, followupId: String, text: String)

    suspend fun deletePending(agentId: String, followupId: String)

    /** Moves [followupId] next to [targetFollowupId]: after it when [insertAfter], else before. */
    suspend fun reorderPending(agentId: String, followupId: String, targetFollowupId: String, insertAfter: Boolean)

    /** Sends a queued message now, interrupting the turn under way. */
    suspend fun submitPendingNow(agentId: String, followupId: String)

    /** Flags a queued message as being reworded on this device (so another client leaves it alone), or done. */
    suspend fun markEditing(agentId: String, followupId: String, editing: Boolean)
}

/** Steering and holding a running chat, stopping one of its tool calls, waking its machine. */
interface RunControlApi {
    /** `InjectBackgroundComposerContext`: a steer into the running turn of [agentId]. */
    suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome

    /** `InjectBackgroundComposerContext` with `promoteFollowupId`: a queued message delivered into the running turn as a steer. */
    suspend fun promoteFollowup(agentId: String, followupId: String, expectedRunId: String?): SteerOutcome

    suspend fun pause(agentId: String, runId: String?)

    suspend fun resume(agentId: String)

    /** `CancelBackgroundComposerToolCall`: stops one tool call; true when the account accepted the cancel. */
    suspend fun cancelToolCall(agentId: String, toolCallId: String): Boolean

    /** `WakeBackgroundComposer`: spins the chat's machine up ahead of a follow-up; true when the account signalled it. */
    suspend fun wake(agentId: String): Boolean
}

/**
 * The conversation-control corner of `aiserver.v1.BackgroundComposerService` (see [BackgroundComposerApi] for the
 * service and its transport): answering an agent's question, the account's follow-up queue, steering and holding a
 * run. Field names are the proto's in Connect JSON's lowerCamelCase; enums go out by their proto names and are read
 * by name or by number. Every call carries the account session from [SessionTokenProvider], which Extended mode
 * alone hands out.
 */
class SteeringApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : InteractionApi, FollowupQueueApi, RunControlApi {

    // ---- the question -----------------------------------------------------------------------------------------------

    override suspend fun answerQuestion(agentId: String, toolCallId: String, answers: List<ToolPayload.Question.Answer>): InteractionResolution {
        val request = SubmitInteractionResponseDto(
            bcId = agentId,
            interactionResponse = InteractionResponseDto(
                id = toolCallId,
                askQuestionInteractionResponse = AskQuestionResponseDto(
                    answers = answers.map { AnswerDto(it.questionId, it.selectedOptionIds, it.freeformText?.takeIf { t -> t.isNotBlank() }) },
                ),
            ),
            askQuestionToolCallId = toolCallId,
        )
        val response = call("SubmitInteractionResponseBackgroundComposer", request, SubmitInteractionResponseDto.serializer(), SubmitInteractionResponseResponseDto.serializer())
        return InteractionResolution.parse(response.resolutionOutcome?.contentOrNull)
    }

    // ---- the queue --------------------------------------------------------------------------------------------------

    override suspend fun addFollowup(agentId: String, followup: AccountFollowup, synchronous: Boolean): String? {
        val text = followup.text.trim()
        val images = followup.images.takeIf { it.isNotEmpty() }?.map { image ->
            SelectedImageDto(data = Base64.getEncoder().encodeToString(image.bytes), mimeType = image.mimeType.lowercase(), uuid = UUID.randomUUID().toString())
        }
        val request = AddFollowupDto(
            bcId = agentId,
            followup = text,
            synchronous = synchronous,
            followupMessage = ConversationMessageDto(text = text, agentMode = followup.mode?.wireName),
            requestedModel = followup.modelId?.takeIf { it.isNotBlank() }?.let { RequestedModelDto(it) },
            followupSource = SOURCE,
            followupId = followup.followupId,
            followupConversationAction = ConversationActionDto(
                userMessageAction = UserMessageActionDto(
                    userMessage = UserMessageDto(
                        text = text,
                        messageId = "msg-${UUID.randomUUID()}",
                        mode = followup.mode?.wireName,
                        selectedContext = images?.let { SelectedContextDto(selectedImages = it) },
                    ),
                    sendToInteractionListener = true,
                ),
            ),
        )
        val response = call("AddAsyncFollowupBackgroundComposer", request, AddFollowupDto.serializer(), AddFollowupResponseDto.serializer())
        return response.runId?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun listPending(agentId: String): List<PendingFollowup> {
        val response = call("ListPendingFollowups", BcIdDto(agentId), BcIdDto.serializer(), PendingFollowupsResponseDto.serializer())
        return response.pendingFollowups.mapNotNull { dto ->
            val id = dto.followupId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PendingFollowup(
                id = id,
                text = dto.text.orEmpty(),
                createdAtMillis = dto.createdAtMs?.longOrNull ?: dto.createdAtMs?.contentOrNull?.toLongOrNull(),
                source = AgentSource.parse(dto.source?.contentOrNull),
                isEditing = dto.isEditing?.booleanOrNull ?: dto.editing?.booleanOrNull ?: false,
            )
        }
    }

    override suspend fun updatePending(agentId: String, followupId: String, text: String) {
        val request = UpdatePendingDto(agentId, followupId, ConversationMessageDto(text = text.trim()))
        call("UpdatePendingFollowup", request, UpdatePendingDto.serializer(), OutcomeDto.serializer()).require("UpdatePendingFollowup")
    }

    override suspend fun deletePending(agentId: String, followupId: String) {
        call("DeletePendingFollowup", FollowupIdDto(agentId, followupId), FollowupIdDto.serializer(), OutcomeDto.serializer()).require("DeletePendingFollowup")
    }

    override suspend fun reorderPending(agentId: String, followupId: String, targetFollowupId: String, insertAfter: Boolean) {
        val request = ReorderPendingDto(agentId, followupId, targetFollowupId, insertAfter)
        call("ReorderPendingFollowup", request, ReorderPendingDto.serializer(), OutcomeDto.serializer()).require("ReorderPendingFollowup")
    }

    override suspend fun submitPendingNow(agentId: String, followupId: String) {
        call("SubmitPendingFollowupNow", FollowupIdDto(agentId, followupId), FollowupIdDto.serializer(), OutcomeDto.serializer()).require("SubmitPendingFollowupNow")
    }

    override suspend fun markEditing(agentId: String, followupId: String, editing: Boolean) {
        call("MarkFollowupEditing", MarkEditingDto(agentId, followupId, editing), MarkEditingDto.serializer(), OutcomeDto.serializer()).require("MarkFollowupEditing")
    }

    // ---- the run ----------------------------------------------------------------------------------------------------

    override suspend fun steer(agentId: String, text: String, expectedRunId: String?): SteerOutcome {
        val request = InjectContextDto(
            bcId = agentId,
            injectContextAction = InjectContextActionDto(
                injectionId = "inj-${UUID.randomUUID()}",
                expectedRunId = expectedRunId,
                userContext = UserContextDto(UserMessageDto(text = text.trim(), messageId = "msg-${UUID.randomUUID()}")),
            ),
            source = SOURCE,
        )
        val response = call("InjectBackgroundComposerContext", request, InjectContextDto.serializer(), InjectContextResponseDto.serializer())
        return SteerOutcome.parse(response.outcome?.contentOrNull)
    }

    override suspend fun promoteFollowup(agentId: String, followupId: String, expectedRunId: String?): SteerOutcome {
        val request = InjectContextDto(
            bcId = agentId,
            injectContextAction = InjectContextActionDto(injectionId = "inj-${UUID.randomUUID()}", expectedRunId = expectedRunId),
            source = SOURCE,
            promoteFollowupId = followupId,
        )
        val response = call("InjectBackgroundComposerContext", request, InjectContextDto.serializer(), InjectContextResponseDto.serializer())
        return SteerOutcome.parse(response.outcome?.contentOrNull)
    }

    override suspend fun pause(agentId: String, runId: String?) {
        call("PauseBackgroundComposer", PauseDto(agentId, SOURCE, runId), PauseDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun resume(agentId: String) {
        call("ResumeBackgroundComposer", BcIdDto(agentId), BcIdDto.serializer(), EmptyDto.serializer())
    }

    override suspend fun cancelToolCall(agentId: String, toolCallId: String): Boolean {
        val response = call("CancelBackgroundComposerToolCall", CancelToolCallDto(agentId, toolCallId, SOURCE), CancelToolCallDto.serializer(), CancelToolCallResponseDto.serializer())
        // An empty reply is the zero value, which proto3 JSON leaves out: the cancel was not accepted.
        return response.accepted == true
    }

    override suspend fun wake(agentId: String): Boolean {
        val response = call("WakeBackgroundComposer", WakeDto(agentId, WAKE_REASON_FOLLOWUP_COMPOSE), WakeDto.serializer(), WakeResponseDto.serializer())
        return response.signaled == true
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    /**
     * The queue methods answer `{success, errorMessage}`. A refusal the account spells out is an error; a reply that
     * says nothing either way is taken as done — proto3 JSON leaves a false `success` out, so the read of the queue
     * that follows every edit is what settles it.
     */
    private fun OutcomeDto.require(method: String) {
        val refused = success == false || !errorMessage.isNullOrBlank()
        if (refused) throw ConnectRpcException(200, null, errorMessage?.takeIf { it.isNotBlank() } ?: "Cursor refused $method.")
    }

    // Request fields carry no defaults on purpose where the wire needs them: CursorJson does not encode defaults.

    @Serializable
    private data class BcIdDto(val bcId: String)

    @Serializable
    private data class FollowupIdDto(val bcId: String, val followupId: String)

    @Serializable
    private data class SubmitInteractionResponseDto(val bcId: String, val interactionResponse: InteractionResponseDto, val askQuestionToolCallId: String)

    /** `agent.v1.InteractionResponse`: the query's id and, of its oneof, the answer to an `ask_question`. */
    @Serializable
    private data class InteractionResponseDto(val id: String, val askQuestionInteractionResponse: AskQuestionResponseDto)

    @Serializable
    private data class AskQuestionResponseDto(val answers: List<AnswerDto>)

    /** One answer as the tool's own result spells it (`answers[{questionId, selectedOptionIds, freeformText}]`). */
    @Serializable
    private data class AnswerDto(val questionId: String, val selectedOptionIds: List<String>, val freeformText: String? = null)

    @Serializable
    private data class SubmitInteractionResponseResponseDto(val resolutionOutcome: JsonPrimitive? = null)

    @Serializable
    private data class AddFollowupDto(
        val bcId: String,
        val followup: String,
        val synchronous: Boolean,
        val followupMessage: ConversationMessageDto,
        val requestedModel: RequestedModelDto? = null,
        val followupSource: String,
        val followupId: String,
        val followupConversationAction: ConversationActionDto,
    )

    /** The corner of `aiserver.v1.ConversationMessage` a follow-up from here fills in. */
    @Serializable
    private data class ConversationMessageDto(val text: String, val agentMode: String? = null)

    @Serializable
    private data class RequestedModelDto(val modelId: String)

    @Serializable
    private data class ConversationActionDto(val userMessageAction: UserMessageActionDto)

    @Serializable
    private data class UserMessageActionDto(val userMessage: UserMessageDto, val sendToInteractionListener: Boolean)

    /** The corner of `agent.v1.UserMessage` sent here: the text, its id, the mode and the attached images. */
    @Serializable
    private data class UserMessageDto(val text: String, val messageId: String, val mode: String? = null, val selectedContext: SelectedContextDto? = null)

    @Serializable
    private data class SelectedContextDto(val selectedImages: List<SelectedImageDto>)

    /** `agent.v1.SelectedImage` with its bytes inline (`data` of the `data_or_blob_id` oneof, base64 in JSON). */
    @Serializable
    private data class SelectedImageDto(val data: String, val mimeType: String, val uuid: String)

    @Serializable
    private data class AddFollowupResponseDto(val runId: String? = null)

    @Serializable
    private data class PendingFollowupsResponseDto(val pendingFollowups: List<PendingFollowupDto> = emptyList())

    /** `aiserver.v1.PendingFollowup` as the list reports it; int64 and enums by name or by number. */
    @Serializable
    private data class PendingFollowupDto(
        val followupId: String? = null,
        val text: String? = null,
        val createdAtMs: JsonPrimitive? = null,
        val source: JsonPrimitive? = null,
        val isEditing: JsonPrimitive? = null,
        val editing: JsonPrimitive? = null,
    )

    @Serializable
    private data class UpdatePendingDto(val bcId: String, val followupId: String, val updatedMessage: ConversationMessageDto)

    @Serializable
    private data class ReorderPendingDto(val bcId: String, val followupId: String, val targetFollowupId: String, val insertAfter: Boolean)

    @Serializable
    private data class MarkEditingDto(val bcId: String, val followupId: String, val editing: Boolean)

    @Serializable
    private data class OutcomeDto(val success: Boolean? = null, val errorMessage: String? = null)

    @Serializable
    private data class InjectContextDto(val bcId: String, val injectContextAction: InjectContextActionDto, val source: String, val promoteFollowupId: String? = null)

    @Serializable
    private data class InjectContextActionDto(val injectionId: String, val expectedRunId: String? = null, val userContext: UserContextDto? = null)

    @Serializable
    private data class UserContextDto(val userMessage: UserMessageDto)

    @Serializable
    private data class InjectContextResponseDto(val outcome: JsonPrimitive? = null)

    @Serializable
    private data class PauseDto(val bcId: String, val source: String, val runId: String? = null)

    @Serializable
    private data class CancelToolCallDto(val bcId: String, val toolCallId: String, val source: String)

    @Serializable
    private data class CancelToolCallResponseDto(val accepted: Boolean? = null)

    @Serializable
    private data class WakeDto(val bcId: String, val reason: String)

    @Serializable
    private data class WakeResponseDto(val signaled: Boolean? = null)

    @Serializable
    private class EmptyDto

    companion object {
        /** What this app is to the account service: a client on the API, like the SDK's chats. */
        val SOURCE: String = AgentSource.API.wireName
        /** `aiserver.v1.WakeBackgroundComposerReason`: the machine is woken because a follow-up is being composed. */
        const val WAKE_REASON_FOLLOWUP_COMPOSE = "WAKE_BACKGROUND_COMPOSER_REASON_FOLLOWUP_COMPOSE"
    }
}
