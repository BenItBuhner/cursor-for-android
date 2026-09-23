package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.StepShape
import com.cursorforandroid.domain.TranscriptPerf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

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
    /**
     * The whole-chat index of the turn this step belongs to, when the record was read by turns (the blob-backed
     * record, see [ConversationRecordApi.turns]): every step of a turn carries the same index, and the record is
     * indexed by turn rather than by step. Null for a step of the step-indexed record (`FetchBackgroundComposer`).
     */
    val turnIndex: Int? = null,
    /** A prompt delivered into the turn under way (`agent.v1.UserMessage.turn_steer`), not one that started a turn. */
    val steer: Boolean = false,
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
 * The account's latest word on a chat's conversation (`StreamConversation`'s `initial_state`, see
 * [ConversationStateReader]), in the corner this app reads: how many turns the chat has ([turnCount], one per prompt), each turn's timing, whether a tool call is
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
    /**
     * The blob id of each turn (`ConversationStateStructure.turns[]`, base64 as Connect JSON writes `bytes`), oldest
     * first: what [ConversationRecordApi.turns] reads the turns from. Empty for a record that names none.
     */
    val turnBlobIds: List<String> = emptyList(),
    /** How many blobs the state read brought with it (the server's prefetch), into the shared cache: read from there rather than fetched. */
    val prefetchedCount: Int = 0,
    /** What the answer carried, keys only — where the state came from, how many turns, the stream's message kinds — for a notice that names a shape the build did not expect. */
    val shape: String = "",
    /** Where the account's live stream of the chat stood when this was read (see [LivePoint]): what an open chat's watch resumes from. */
    val live: LivePoint? = null,
)

/**
 * How much of a turn a read of the blob-backed record brings (see [ConversationRecordApi.readTurns]): [FULL] every
 * step; [MESSAGES] what the reader sees of a turn before opening its stretch — the turn's structure, its prompt, the
 * steps that are the coordinator's messages to the user (`send_message_step_indices`), and any other step already
 * held — the rest read after, behind what is on screen.
 */
enum class TurnPlan { FULL, MESSAGES }

/**
 * One turn of the blob-backed record as its steps (see [ConversationRecordApi.turns]): [index] is its whole-chat
 * index, [steps] its prompt first, then each step as the step-indexed record would have carried it; [blobs] how
 * many blobs were read for it and [prefetched] how many of them the state answer had carried, for the diagnostics.
 */
data class HeadlessTurn(
    val index: Int,
    val steps: List<HeadlessStep>,
    val blobs: Int = 0,
    val prefetched: Int = 0,
    /** The turn's own blob (`ConversationStateStructure.turns[index]`): content-addressed, so an unchanged id is an unchanged turn. */
    val blobId: String? = null,
    /** Every step was read; a [TurnPlan.MESSAGES] read leaves steps for later. */
    val complete: Boolean = true,
    /** Not read: the reader holds the turn under the same [blobId] already, and its items stand. */
    val reused: Boolean = false,
    /** How many steps the turn's structure lists, and how many of them are messages to the user; null when the structure was not read. */
    val stepTotal: Int? = null,
    val messageSteps: Int? = null,
    /** Blobs of the turn asked for ([asked]) and answered as missing or unreadable ([missing]), with the last such answer, for the drift check. */
    val asked: Int = 0,
    val missing: Int = 0,
    val lastMissing: ConnectRpcException? = null,
    /** Pieces the server failed to give after their retries (see [BlobRecord.Read.unavailable]), and the last such failure. */
    val unavailable: Int = 0,
    val lastUnavailable: Throwable? = null,
    /** The turn's structure was read (false: the server failed to give it, and nothing of the turn is known yet). */
    val readable: Boolean = true,
)

/** Turns [from] until [from] + `turns.size` of a chat of [turnCount] turns. */
data class HeadlessTurnPage(val turns: List<HeadlessTurn>, val from: Int, val turnCount: Int)

/**
 * The account's transcript of a chat, tool calls included, which outlives the documented stream's retention window.
 * An interface so the conversation repository can be tested against a fake.
 */
interface ConversationRecordApi {
    /** `FetchBackgroundComposer {bc_id, start_index, limit}`: [limit] steps from [startIndex], oldest first. */
    suspend fun fetch(agentId: String, startIndex: Int, limit: Int): HeadlessPage

    /** The chat's conversation state — its turn count, timings and pending work (see [RecordState]) — as `StreamConversation`'s `initial_state` gives it. */
    suspend fun state(agentId: String): RecordState

    /**
     * The blob-backed record, by turn: turns [from] until [from] + [limit] of the chat, oldest first, each read from
     * its blobs (`GetBlobForAgentKV`) and given as the steps the step-indexed record would have carried. [state] is
     * the conversation state when the caller read it a moment ago, else it is read here. Null when this record has
     * no turn read (a fake of the step-indexed record alone).
     */
    suspend fun turns(agentId: String, from: Int, limit: Int, state: RecordState? = null): HeadlessTurnPage? = null

    /**
     * [turns] read to [plan], the turns whose blob id [held] names (by whole-chat index) left unread and given as
     * [HeadlessTurn.reused]: the reader has them already, and a content-addressed turn does not change.
     */
    suspend fun readTurns(agentId: String, from: Int, limit: Int, state: RecordState?, plan: TurnPlan, held: Map<Int, String> = emptyMap(), patient: Boolean = true): HeadlessTurnPage? = turns(agentId, from, limit, state)

    /** Whether [turns] is the read this record serves: the step-indexed read is gone from the server, the blob-backed one stands. */
    val readsTurns: Boolean get() = false

    /** What [agentId]'s blob reads have come to this process (see [BlobCache.Counts]) and the memory tier's size, for the diagnostics; null for a record without blobs. */
    fun blobCounts(agentId: String): Pair<BlobCache.Snapshot, Long>? = null

    /**
     * Holds the account's live stream of [agentId]'s conversation open until it says the chat moved on from [since]
     * (see [ConversationStateReader.watch]), resuming from [since]'s offset when [resume]. Null where there is no
     * live stream to hold.
     */
    suspend fun watch(agentId: String, since: LivePoint?, resume: Boolean): LiveWatch? = null

    /**
     * The turns [turns] names (whole-chat index to the turn's blob id) read from the blobs this device holds alone —
     * memory, then the disk — never the network: a saved turn whose file is gone rebuilt before anything is painted
     * (see `ConversationRepository.rebuiltFromHeldBlobs`). A piece not held leaves its turn short, not unreadable.
     */
    suspend fun heldTurns(agentId: String, turns: Map<Int, String>): List<HeadlessTurn> = emptyList()
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
    /**
     * Read the record by turns from its blobs (see [turns]) — the read Cursor's client makes, and the only one the
     * server still answers since `FetchBackgroundComposer` was removed (September 2026). False reads the
     * step-indexed record ([fetch]) for the tests that keep that wire's captured shapes readable.
     */
    override val readsTurns: Boolean = true,
    /** The blobs read so far, shared with every other reader of the record (the goal strip's, see `SteeringApi`). */
    val blobs: BlobCache = BlobCache(),
    /** The waits between attempts of a read the server failed (see [ServerRetry]). */
    private val waits: ServerRetry.Waits = ServerRetry.Waits(),
) : ConversationRecordApi {

    /** The conversation state read (`StreamConversation`, PREWARM), which names the turns and brings the newest blobs. */
    private val states = ConversationStateReader(rpc, tokens, blobs, waits.state)

    override fun blobCounts(agentId: String): Pair<BlobCache.Snapshot, Long> = blobs.counts(agentId).snapshot() to blobs.memoryBytes

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

    /**
     * The chat's conversation state off `StreamConversation`'s `initial_state` (see [ConversationStateReader]): the
     * turn list, each turn's timing, the pending calls, the counters. The blobs the server sent with it are in
     * [blobs] already, so [turns] reads them from there. `GetLatestAgentConversationState`, which said the same,
     * is gone from the server (2026-09-21) and is not asked.
     */
    override suspend fun state(agentId: String): RecordState {
        // A read in flight is joined: the goal strip reads the same state as the chat opens.
        val initial = states.read(agentId)
        // The state inline, as the desktop takes it; else the state's own blob (`initial_state.blob_id`), read as the structure.
        var source = if (initial.conversationState != null) "inline" else "absent"
        val stateJson = initial.conversationState ?: initial.stateBlobId?.let { id ->
            val bytes = runCatching { blob(agentId, id) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            val decoded = bytes?.let { runCatching { ProtoWire.decode(it, AgentSchemas.CONVERSATION_STATE) }.getOrNull() }
            source = when {
                bytes == null -> "blob(missing)"
                decoded == null -> "blob(unreadable)"
                else -> "blob"
            }
            decoded
        }
        val conversation = stateJson?.let { runCatching { CursorJson.decodeFromJsonElement(ConversationStateDto.serializer(), it) }.getOrNull() }
        val cloud = initial.cloudAgentState
        return RecordState(
            turnCount = conversation?.turns?.size ?: 0,
            timings = conversation?.turnTimings?.map { TurnTiming(it.durationMs?.toLongLenient(), it.timestampMs?.toLongLenient()) } ?: emptyList(),
            pendingToolCalls = conversation?.pendingToolCalls?.size ?: 0,
            isRootProject = conversation?.isRootProjectConversation == true,
            numPriorInteractionUpdates = cloud?.get("numPriorInteractionUpdates")?.toLongLenient() ?: 0L,
            // `conversation_rewind_epoch` was the unary's own field; the stream carries no such counter.
            rewindEpoch = 0L,
            turnBlobIds = conversation?.turns?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotBlank() } } ?: emptyList(),
            prefetchedCount = initial.prefetchedCount,
            shape = "conversationState=$source turns=${conversation?.turns?.size ?: 0} messages=${initial.kinds.joinToString(",")} cloudAgentState=${cloud?.keys?.sorted()?.joinToString(",", "[", "]") ?: "absent"}",
            live = LivePoint(ConversationStateReader.offsetKeyOf(cloud), LivePoint.status(initial.workflowStatus), conversation?.turns?.size),
        )
    }

    override suspend fun watch(agentId: String, since: LivePoint?, resume: Boolean): LiveWatch = states.watch(agentId, since, resume)

    override suspend fun heldTurns(agentId: String, turns: Map<Int, String>): List<HeadlessTurn> = coroutineScope {
        turns.entries.sortedBy { it.key }.map { (index, id) ->
            async {
                val counter = BlobRecord.Counter()
                val read = BlobRecord.read(index, id, heldSource(agentId, counter), TurnPlan.FULL)
                HeadlessTurn(
                    index, read.steps, counter.blobs.get(), counter.prefetched.get(),
                    blobId = id, complete = read.complete, stepTotal = read.stepTotal, messageSteps = read.messageSteps,
                    asked = read.asked, missing = read.missing, lastMissing = read.lastMissing,
                    unavailable = read.unavailable, lastUnavailable = read.lastUnavailable, readable = read.readable,
                )
            }
        }.awaitAll()
    }

    /** A turn read's pieces from the cache alone (see [heldTurns]): one not held is left for later, as a failed piece is. */
    private fun heldSource(agentId: String, counter: BlobRecord.Counter) = object : BlobRecord.Source {
        override suspend fun blob(id: String, whole: Boolean): ByteArray {
            counter.blobs.incrementAndGet()
            val held = blobs.read(agentId, id)
            if (held != null && !(whole && held.partial)) return held.bytes
            throw java.io.IOException("Not held on this device: $id")
        }

        override suspend fun held(id: String): BlobCache.Held? = blobs.read(agentId, id)

        override suspend fun confirm(id: String) = blobs.confirm(agentId, id)
    }

    /**
     * The turns [from] until [from] + [limit], each from its blobs: the turn's structure, the user's message and
     * every step, decoded against [AgentSchemas] (see [BlobRecord]). A handful of blobs are in flight at once, and
     * every blob read is kept (blobs are immutable, named by their content), so a turn read twice costs one round
     * trip the second time, and the delta of a chat that grew costs the new turns' blobs alone.
     */
    override suspend fun turns(agentId: String, from: Int, limit: Int, state: RecordState?): HeadlessTurnPage =
        readTurns(agentId, from, limit, state, TurnPlan.FULL, emptyMap())

    /**
     * The turns [from] until [from] + [limit], each from its blobs to [plan]: the turn's structure, the user's
     * message and its steps, decoded against [AgentSchemas] (see [BlobRecord]). Blobs come from the cache first —
     * memory, then disk — and from the network only when neither holds them, [BLOB_PARALLELISM] at once across the
     * page. A turn whose blob id [held] names is not read at all. A blob the server fails to give is asked again
     * with backoff (see [ServerRetry]) — [patient] the whole way, else once, for a read the screen is waiting on,
     * the piece left for the reads behind it.
     */
    override suspend fun readTurns(agentId: String, from: Int, limit: Int, state: RecordState?, plan: TurnPlan, held: Map<Int, String>, patient: Boolean): HeadlessTurnPage {
        val known = state ?: state(agentId)
        val ids = known.turnBlobIds
        val start = from.coerceIn(0, ids.size)
        val end = (start + limit.coerceAtLeast(0)).coerceAtMost(ids.size)
        if (start >= end) return HeadlessTurnPage(emptyList(), start, ids.size)
        val gate = Semaphore(BLOB_PARALLELISM)
        val turns = coroutineScope {
            // Newest first: the gate lets the turns on screen through before the ones above them.
            (start until end).reversed().map { index ->
                async {
                    val id = ids[index]
                    if (held[index] == id) return@async HeadlessTurn(index, emptyList(), blobId = id, reused = true)
                    val counter = BlobRecord.Counter()
                    val read = BlobRecord.read(index, id, source(agentId, gate, counter, if (patient) waits.pieces else waits.onScreen), plan)
                    HeadlessTurn(
                        index, read.steps, counter.blobs.get(), counter.prefetched.get(),
                        blobId = id, complete = read.complete, stepTotal = read.stepTotal, messageSteps = read.messageSteps,
                        asked = read.asked, missing = read.missing, lastMissing = read.lastMissing,
                        unavailable = read.unavailable, lastUnavailable = read.lastUnavailable, readable = read.readable,
                    )
                }
            }.awaitAll().asReversed()
        }
        return HeadlessTurnPage(turns, start, ids.size)
    }

    /** Where a turn read of [agentId] takes its blobs from: the cache, then the network through [gate]. */
    private fun source(agentId: String, gate: Semaphore, counter: BlobRecord.Counter, retryDelaysMs: List<Long>) = object : BlobRecord.Source {
        override suspend fun blob(id: String, whole: Boolean): ByteArray {
            counter.blobs.incrementAndGet()
            val held = blobs.read(agentId, id)
            if (held != null && !(whole && held.partial)) {
                if (held.partial) counter.prefetched.incrementAndGet()
                return held.bytes
            }
            return gate.withPermit { fetchBlob(agentId, id, retryDelaysMs) }
        }

        override suspend fun held(id: String): BlobCache.Held? = blobs.read(agentId, id)

        override suspend fun confirm(id: String) = blobs.confirm(agentId, id)
    }

    /**
     * One blob of the chat's record (`GetBlobForAgentKV {bc_id, blob_id}` → `blob_data`), from the cache when it was
     * read before. Blobs are content-addressed and never change, so the cache needs no invalidation; it is bounded
     * by count and by bytes (see [BlobCache]).
     */
    suspend fun blob(agentId: String, blobId: String): ByteArray {
        blobs.read(agentId, blobId)?.takeIf { !it.partial }?.let { return it.bytes }
        return fetchBlob(agentId, blobId)
    }

    /**
     * The network's copy of a blob, kept whole in memory and on disk. A failure of the server's own — a 5xx (the
     * load balancer's bare 502 among them), `unavailable`, a dropped connection — is asked again with backoff (see
     * [ServerRetry]); what still fails after that is thrown for the turn to leave the piece for later, never the page.
     */
    private suspend fun fetchBlob(agentId: String, blobId: String, retryDelaysMs: List<Long> = waits.pieces): ByteArray {
        val counts = blobs.counts(agentId)
        val response = try {
            ServerRetry.withRetries(retryDelaysMs, onRetry = { _, _, _ -> counts.retried.incrementAndGet() }) {
                TranscriptPerf.session(agentId).network("blob")
                counts.fetched.incrementAndGet()
                rpc.unaryWithSession(
                    SERVICE,
                    "GetBlobForAgentKV",
                    tokens,
                    BlobRequestDto(bcId = agentId, blobId = blobId),
                    BlobRequestDto.serializer(),
                    BlobResponseDto.serializer(),
                    // A blob has no other source: a rate limit is waited out and the read made once more (see ApiThrottle.call).
                    retryRefusals = true,
                    lane = ApiThrottle.Lane.BLOBS,
                )
            }
        } catch (e: Throwable) {
            val connect = e as? ConnectRpcException
            if (connect != null && (connect.httpCode == 404 || connect.code == "not_found")) counts.missing.incrementAndGet()
            if (ServerRetry.isTransient(e)) counts.failed.incrementAndGet()
            throw e
        }
        val bytes = response.blobData?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (bytes == null) {
            counts.missing.incrementAndGet()
            throw ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "Cursor's answer to GetBlobForAgentKV carried no blob.", path = ConnectRpc.path(SERVICE, BLOB_METHOD))
        }
        counts.fetchedBytes.addAndGet(bytes.size.toLong())
        blobs.keep(agentId, blobId, bytes)
        return bytes
    }

    @Serializable
    private data class FetchRequestDto(val bcId: String, val startIndex: Int, val limit: Int)

    @Serializable
    private data class BlobRequestDto(val bcId: String, val blobId: String)

    @Serializable
    private data class BlobResponseDto(val blobData: String? = null)

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
        const val BLOB_METHOD = "GetBlobForAgentKV"
        /**
         * Blob reads in flight at once for one page of turns: a few hundred bytes each, so the round trip is the cost,
         * not the body. Below the desktop's own: Cursor 3.21.18 puts no bound on its blob reads but the 64 of its cache
         * migration, so eight is not what makes the server fail one.
         */
        const val BLOB_PARALLELISM = 8

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
