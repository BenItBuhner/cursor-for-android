package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.data.local.BlobDiskStore
import com.cursorforandroid.domain.TranscriptPerf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The blobs of the account's conversation records, by chat and blob id: content-addressed and never changing, so a
 * blob read once is never asked for again, and the ids in hand are what the next state read tells the server it
 * need not send (`StreamConversationRequest.pre_fetched_blob_ids`). Two tiers: a small memory tier, least recently
 * used first, bounded by count and by bytes, in front of [disk] when there is one (the Beta engine's, see
 * [BlobDiskStore]), which a new process reads the turns it already had from. Shared by every reader of the record
 * (see [ConversationStateReader], [HeadlessConversationApi]).
 *
 * A blob the state read prefetched is held as [Held.partial]: the read asks for heavy step data to be left out
 * (`filter_heavy_step_data`, as the desktop's prewarm does), and the same id then names bytes that may lack a step's
 * payload. Such a copy stays in memory until it is confirmed whole — read as a turn's structure or its prompt, which
 * are not step data — or replaced by the network's own copy; only whole copies reach the disk.
 */
class BlobCache(
    private val maxBlobs: Int = MAX_BLOBS,
    private val maxBytes: Long = MAX_BYTES,
    val disk: BlobDiskStore? = null,
) {
    /** A blob as held: its bytes, and whether they are the prefetch's copy, heavy step data possibly left out. */
    class Held(val bytes: ByteArray, val partial: Boolean)

    /**
     * What one chat's reads came to, since the process started: blobs the network was asked for (and their bytes),
     * read from memory, read from disk, carried by a state read's prefetch, and answered as missing or unreadable.
     * A load's own share is the difference of two [snapshot]s.
     */
    class Counts {
        val fetched = AtomicInteger()
        val fetchedBytes = AtomicLong()
        val memory = AtomicInteger()
        val disk = AtomicInteger()
        val prefetched = AtomicInteger()
        val missing = AtomicInteger()
        /** Attempts made again after a transient failure (see [ServerRetry]), and reads that still failed after them. */
        val retried = AtomicInteger()
        val failed = AtomicInteger()

        fun snapshot(): Snapshot = Snapshot(fetched.get(), fetchedBytes.get(), memory.get(), disk.get(), prefetched.get(), missing.get(), retried.get(), failed.get())
    }

    data class Snapshot(val fetched: Int = 0, val fetchedBytes: Long = 0, val memory: Int = 0, val disk: Int = 0, val prefetched: Int = 0, val missing: Int = 0, val retried: Int = 0, val failed: Int = 0) {
        operator fun minus(other: Snapshot) = Snapshot(fetched - other.fetched, fetchedBytes - other.fetchedBytes, memory - other.memory, disk - other.disk, prefetched - other.prefetched, missing - other.missing, retried - other.retried, failed - other.failed)
    }

    private val blobs = object : LinkedHashMap<String, Held>(64, 0.75f, true) {}
    private var bytes = 0L
    private val counts = ConcurrentHashMap<String, Counts>()

    /** The latest conversation state read per chat, shared by its readers (see [ConversationStateReader.read]). */
    internal val states = ConcurrentHashMap<String, ConversationStateReader.Recent>()

    fun counts(agentId: String): Counts = counts.getOrPut(agentId) { Counts() }

    /** Bytes the memory tier holds, all chats together. */
    val memoryBytes: Long @Synchronized get() = bytes

    /** The memory tier's copy of a blob, whole or partial. */
    @Synchronized
    fun get(agentId: String, blobId: String): ByteArray? = blobs["$agentId/$blobId"]?.bytes

    @Synchronized
    fun held(agentId: String, blobId: String): Held? = blobs["$agentId/$blobId"]

    /** Into the memory tier; [partial] for the prefetch's copies (see [Held.partial]). */
    @Synchronized
    fun put(agentId: String, blobId: String, value: ByteArray, partial: Boolean = false) {
        val key = "$agentId/$blobId"
        blobs[key]?.let { old -> if (!old.partial && partial) return }
        blobs.remove(key)?.let { bytes -= it.bytes.size }
        blobs[key] = Held(value, partial)
        bytes += value.size
        val iterator = blobs.entries.iterator()
        while ((blobs.size > maxBlobs || bytes > maxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            bytes -= eldest.value.bytes.size
            iterator.remove()
        }
    }

    /** A whole copy — the network's — into memory and onto the disk. */
    suspend fun keep(agentId: String, blobId: String, value: ByteArray) {
        put(agentId, blobId, value, partial = false)
        disk?.write(agentId, blobId, value)
    }

    /**
     * The blob as held, memory first, then the disk (a disk hit goes back into memory); null when neither has it.
     * Counted for the chat's reads.
     */
    suspend fun read(agentId: String, blobId: String): Held? {
        held(agentId, blobId)?.let { counts(agentId).memory.incrementAndGet(); return it }
        val fromDisk = disk?.read(agentId, blobId) ?: return null
        counts(agentId).disk.incrementAndGet()
        put(agentId, blobId, fromDisk, partial = false)
        return Held(fromDisk, partial = false)
    }

    /** A partial copy read as data no filter touches (a turn's structure, its prompt): whole after all, and kept on disk. */
    suspend fun confirm(agentId: String, blobId: String) {
        val held = held(agentId, blobId)?.takeIf { it.partial } ?: return
        synchronized(this) { blobs["$agentId/$blobId"] = Held(held.bytes, partial = false) }
        disk?.write(agentId, blobId, held.bytes)
    }

    /** The ids held for [agentId] in memory: what the server need not send again. */
    @Synchronized
    fun ids(agentId: String): List<String> {
        val prefix = "$agentId/"
        return blobs.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    /**
     * What a state read tells the server this device holds for [agentId], at most [max]: the memory tier's ids, most
     * recently used first, then the disk's most recent — the newest turns' blobs, which are the ones the server
     * prefetches. Not every id held: a Project of thousands of turns holds tens of thousands of blobs, and the
     * request would outweigh the prefetch it saves.
     */
    suspend fun heldIds(agentId: String, max: Int = MAX_HELD_IDS): List<String> {
        // What the server prefetched last time, where it is still held: the blobs it will want to send again.
        val noted = prefetched[agentId] ?: disk?.readIndex(agentId).orEmpty()
        val held = noted.filter { id -> held(agentId, id) != null || disk?.has(agentId, id) == true }
        val memory = synchronized(this) {
            val prefix = "$agentId/"
            blobs.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.asReversed()
        }
        val known = (held + memory).distinct()
        if (known.size >= max || disk == null) return known.take(max)
        return (known + disk.recentIds(agentId, max)).distinct().take(max)
    }

    private val prefetched = ConcurrentHashMap<String, List<String>>()

    /**
     * The blobs a state read prefetched for [agentId] this time, noted with the ones noted before (newest first,
     * [MAX_HELD_IDS] at most) in memory and on disk: the next read names them as held, so the server sends only what
     * changed — in this process or the next.
     */
    suspend fun notePrefetched(agentId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val merged = (ids + (prefetched[agentId] ?: disk?.readIndex(agentId).orEmpty())).distinct().take(MAX_HELD_IDS)
        prefetched[agentId] = merged
        disk?.writeIndex(agentId, merged)
    }

    companion object {
        const val MAX_BLOBS = 4_000
        const val MAX_BYTES = 48L * 1024 * 1024
        /** The memory tier when a disk tier stands behind it: the window's blobs, not the chat's. */
        const val MEMORY_BLOBS_WITH_DISK = 2_000
        const val MEMORY_BYTES_WITH_DISK = 12L * 1024 * 1024
        /** Ids a state read names as held, at most (the server prefetches about thirty). */
        const val MAX_HELD_IDS = 120
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
 * The request mirrors the desktop's PREWARM request field for field, the ids of the blobs already held told to the
 * server so it sends only what this device lacks. Everything the server prefetches lands in [blobs], as partial
 * copies (see [BlobCache.Held.partial]). Readers sharing [blobs] share their reads too: one in flight is joined, not
 * repeated, and a reader that can wait accepts one a moment old (see [read]).
 */
@OptIn(ExperimentalSerializationApi::class)
class ConversationStateReader(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
    val blobs: BlobCache,
    /** The waits between attempts of a read the server failed (see [readNow]). */
    private val retryDelaysMs: List<Long> = ServerRetry.Waits().state,
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
        /** Bytes of prefetched blobs the answer carried. */
        val prefetchedBytes: Long = 0L,
        /** `initial_state.blob_id`: the state's own blob, which stands in for [conversationState] when the answer carries none inline. */
        val stateBlobId: String? = null,
    )

    /** A chat's state read in flight: its readers wait on [answer]. */
    internal class Recent(val answer: CompletableDeferred<InitialState>)

    /**
     * One PREWARM read of [agentId]'s conversation: the stream is left the moment `initial_state` has been read. A
     * read of the chat already in flight — the goal strip's as the chat opens, the transcript's — is joined, not
     * repeated; a read that has finished is never taken for a new one. Throws [ConnectRpcException] with the request
     * path when the server refuses, or when the stream ends without a state.
     */
    suspend fun read(agentId: String): InitialState {
        while (true) {
            val inFlight = blobs.states[agentId]
            if (inFlight != null) {
                try {
                    return inFlight.answer.await()
                } catch (e: CancellationException) {
                    // The reader that owned it was cancelled (its screen left), not this one: read afresh.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val mine = Recent(CompletableDeferred())
            if (blobs.states.putIfAbsent(agentId, mine) != null) continue
            try {
                val state = readNow(agentId)
                mine.answer.complete(state)
                return state
            } catch (t: Throwable) {
                mine.answer.completeExceptionally(t)
                throw t
            } finally {
                blobs.states.remove(agentId, mine)
            }
        }
    }

    /**
     * [readOnce], asked again after a failure of the server's own or of the connection (see [ServerRetry]): the
     * first paint waits on it, so it is asked a few times rather than the blobs' number of times.
     */
    private suspend fun readNow(agentId: String): InitialState =
        ServerRetry.withRetries(retryDelaysMs, onRetry = { _, _, _ -> blobs.counts(agentId).retried.incrementAndGet() }) { readOnce(agentId) }

    private suspend fun readOnce(agentId: String): InitialState {
        TranscriptPerf.session(agentId).network("state")
        val known = blobs.heldIds(agentId)
        val request = StreamConversationRequestDto(bcId = agentId, preFetchedBlobIds = known)
        var state: InitialState? = null
        var prefetched = 0
        var prefetchedBytes = 0L
        val prefetchedIds = ArrayList<String>()
        val kinds = ArrayList<String>()
        rpc.serverStreamWithSession(SERVICE, METHOD, tokens, request, StreamConversationRequestDto.serializer(), retryRefusals = false) { message ->
            val case = message.keys.firstOrNull { it != "@type" } ?: "empty"
            kinds += case
            when (case) {
                "prefetchedBlobs" -> {
                    store(agentId, (message["prefetchedBlobs"] as? JsonObject)?.get("preFetchedBlobs"), prefetchedIds).let { (n, size) -> prefetched += n; prefetchedBytes += size }
                    true
                }
                "initialState" -> {
                    val initial = message["initialState"] as? JsonObject
                    store(agentId, initial?.get("preFetchedBlobs"), prefetchedIds).let { (n, size) -> prefetched += n; prefetchedBytes += size }
                    val cloud = initial?.get("cloudAgentState") as? JsonObject
                    state = InitialState(
                        conversationState = cloud?.get("conversationState") as? JsonObject,
                        cloudAgentState = cloud,
                        workflowStatus = (initial?.get("workflowStatus") as? JsonPrimitive)?.contentOrNull,
                        prefetchedCount = prefetched,
                        kinds = kinds.toList(),
                        prefetchedBytes = prefetchedBytes,
                        stateBlobId = (initial?.get("blobId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                    )
                    // The desktop returns here; the stream would go on with the live updates, which the run's own stream carries for this app.
                    false
                }
                else -> true
            }
        }
        blobs.counts(agentId).prefetched.addAndGet(prefetched)
        blobs.notePrefetched(agentId, prefetchedIds)
        return state ?: throw ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "The conversation stream ended without an initial state (${kinds.ifEmpty { listOf("no messages") }.joinToString(",")}).", path = ConnectRpc.path(SERVICE, METHOD))
    }

    /** The `PreFetchedBlob[]` of [element] into the cache, as partial copies, their ids onto [prefetchedIds]; how many landed and their bytes. */
    private fun store(agentId: String, element: kotlinx.serialization.json.JsonElement?, prefetchedIds: MutableList<String>): Pair<Int, Long> {
        val items = element as? JsonArray ?: return 0 to 0L
        var count = 0
        var size = 0L
        for (item in items) {
            val blob = item as? JsonObject ?: continue
            val id = (blob["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val value = (blob["value"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: continue
            blobs.put(agentId, id, value, partial = true)
            prefetchedIds += id
            count++
            size += value.size
        }
        return count to size
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
