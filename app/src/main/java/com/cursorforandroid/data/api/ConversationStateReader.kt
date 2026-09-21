package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.TranscriptPerf
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64

/**
 * The blobs of the account's conversation records this process has read, by chat and blob id: content-addressed and
 * never changing, so a blob read once is never asked for again, and the ids in hand are what the next state read
 * tells the server it need not send (`StreamConversationRequest.pre_fetched_blob_ids`). Bounded by count and by
 * bytes, least recently used first. Shared by every reader of the record (see [ConversationStateReader],
 * [HeadlessConversationApi]).
 */
class BlobCache(private val maxBlobs: Int = MAX_BLOBS, private val maxBytes: Long = MAX_BYTES) {
    private val blobs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}
    private var bytes = 0L

    @Synchronized
    fun get(agentId: String, blobId: String): ByteArray? = blobs["$agentId/$blobId"]

    @Synchronized
    fun put(agentId: String, blobId: String, value: ByteArray) {
        val key = "$agentId/$blobId"
        blobs.remove(key)?.let { bytes -= it.size }
        blobs[key] = value
        bytes += value.size
        val iterator = blobs.entries.iterator()
        while ((blobs.size > maxBlobs || bytes > maxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            bytes -= eldest.value.size
            iterator.remove()
        }
    }

    /** The ids held for [agentId]: what the server need not send again. */
    @Synchronized
    fun ids(agentId: String): List<String> {
        val prefix = "$agentId/"
        return blobs.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    companion object {
        const val MAX_BLOBS = 4_000
        const val MAX_BYTES = 48L * 1024 * 1024
    }
}

/**
 * The account's latest word on a chat's conversation, read the way Cursor's own client reads it in 3.21.16:
 * `BackgroundComposerService/StreamConversation` with `purpose = PREWARM` — the server answers first with the blobs
 * it prefetches (`prefetched_blobs`, ahead of the state when `should_send_prefetched_blobs_first`), then with
 * `initial_state { blob_id, cloud_agent_state { conversation_state, num_prior_interaction_updates, … },
 * pre_fetched_blobs[], workflow_status }`, and the desktop returns at that point, aborting the stream (the
 * `cloudAgentStreamPrefetch.js` path). The unary `GetLatestAgentConversationState` said the same and was called by
 * nothing first-party; the server removed it on 2026-09-21 ("getLatestAgentConversationState has been removed", on
 * Bennett's phone), as it had removed `FetchBackgroundComposer` the day before. This read is the one every
 * first-party client makes, so it is the one to build on.
 *
 * The request mirrors the desktop's PREWARM request field for field, the ids of the blobs already in [blobs] told
 * to the server so it sends only what this device lacks. Everything the server prefetches lands in [blobs].
 */
@OptIn(ExperimentalSerializationApi::class)
class ConversationStateReader(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
    val blobs: BlobCache,
) {
    /** What `initial_state` carried, in the corner this app reads, and the raw JSON of the rest for the diagnostics. */
    class InitialState(
        /** `cloud_agent_state.conversation_state` (`agent.v1.ConversationStateStructure`) as Connect JSON, or null when the state carried none. */
        val conversationState: JsonObject?,
        /** `cloud_agent_state` whole, for the counters beside the conversation state (`num_prior_interaction_updates`, `user_facing_error_details`, …). */
        val cloudAgentState: JsonObject?,
        /** `workflow_status`, as the server spells it. */
        val workflowStatus: String?,
        /** How many blobs the server sent ahead of and with the state. */
        val prefetchedCount: Int,
        /** The message kinds the stream carried before the state was in hand, for the diagnostics. */
        val kinds: List<String>,
    )

    /**
     * One PREWARM read of [agentId]'s conversation: the stream is left the moment `initial_state` has been read.
     * Throws [ConnectRpcException] with the request path when the server refuses, or when the stream ends without a state.
     */
    suspend fun read(agentId: String): InitialState {
        TranscriptPerf.session(agentId).network("state")
        val known = blobs.ids(agentId)
        val request = StreamConversationRequestDto(bcId = agentId, preFetchedBlobIds = known)
        var state: InitialState? = null
        var prefetched = 0
        val kinds = ArrayList<String>()
        rpc.serverStreamWithSession(SERVICE, METHOD, tokens, request, StreamConversationRequestDto.serializer(), retryRefusals = false) { message ->
            val case = message.keys.firstOrNull { it != "@type" } ?: "empty"
            kinds += case
            when (case) {
                "prefetchedBlobs" -> {
                    prefetched += store(agentId, (message["prefetchedBlobs"] as? JsonObject)?.get("preFetchedBlobs"))
                    true
                }
                "initialState" -> {
                    val initial = message["initialState"] as? JsonObject
                    prefetched += store(agentId, initial?.get("preFetchedBlobs"))
                    val cloud = initial?.get("cloudAgentState") as? JsonObject
                    state = InitialState(
                        conversationState = cloud?.get("conversationState") as? JsonObject,
                        cloudAgentState = cloud,
                        workflowStatus = (initial?.get("workflowStatus") as? JsonPrimitive)?.contentOrNull,
                        prefetchedCount = prefetched,
                        kinds = kinds.toList(),
                    )
                    // The desktop returns here; the stream would go on with the live updates, which the run's own stream carries for this app.
                    false
                }
                else -> true
            }
        }
        return state ?: throw ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "The conversation stream ended without an initial state (${kinds.ifEmpty { listOf("no messages") }.joinToString(",")}).", path = ConnectRpc.path(SERVICE, METHOD))
    }

    /** The `PreFetchedBlob[]` of [element] into the cache; how many landed. */
    private fun store(agentId: String, element: kotlinx.serialization.json.JsonElement?): Int {
        val items = element as? JsonArray ?: return 0
        var count = 0
        for (item in items) {
            val blob = item as? JsonObject ?: continue
            val id = (blob["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val value = (blob["value"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: continue
            blobs.put(agentId, id, value)
            count++
        }
        return count
    }

    /**
     * `aiserver.v1.StreamConversationRequest` as the desktop's prewarm sends it (`cloudAgentStreamPrefetch.js`, 3.21.16):
     * `{bcId, purpose: PREWARM, filterHeavyStepData: true, shouldSendPrefetchedBlobsFirst: true,
     * prefetchOnlyLastStepPerTurn: true, maxBlobsAfterPrefetch: 30, preFetchedBlobIds: [the blobs in hand]}`.
     * The Bloom-filter alternative (`preFetchedBlobFilter`) is behind a feature gate there and not sent here.
     */
    @Serializable
    data class StreamConversationRequestDto(
        val bcId: String,
        // Written out whatever their value: `CursorJson` leaves defaults out, and these are the request.
        @EncodeDefault val purpose: String = PURPOSE_PREWARM,
        @EncodeDefault val filterHeavyStepData: Boolean = true,
        @EncodeDefault val shouldSendPrefetchedBlobsFirst: Boolean = true,
        @EncodeDefault val prefetchOnlyLastStepPerTurn: Boolean = true,
        @EncodeDefault val maxBlobsAfterPrefetch: Int = 30,
        @EncodeDefault val preFetchedBlobIds: List<String> = emptyList(),
    )

    companion object {
        const val SERVICE = "aiserver.v1.BackgroundComposerService"
        const val METHOD = "StreamConversation"
        /** `aiserver.v1.StreamConversationPurpose.STREAM_CONVERSATION_PURPOSE_PREWARM` (2), by name as proto3 JSON writes enums. */
        const val PURPOSE_PREWARM = "STREAM_CONVERSATION_PURPOSE_PREWARM"
    }
}
