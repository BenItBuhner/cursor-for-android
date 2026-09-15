package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.PullRequestStatus
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.TimelineItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the app remembers between launches so the next start renders from disk before the network answers:
 * the agent list, the transcripts that were opened (or prefetched), the traces of the runs whose streams were
 * seen, and the composer catalogs. All of it is derived from the API and safe to lose, so it lives in the cache
 * directory and is wiped on sign-out.
 */
class AppCaches(private val root: JsonDiskCache) {
    val agents = AgentListCache(root.child("agents"))
    val conversations = ConversationCache(root.child("conversations"))
    val traces = TraceCache(root.child("traces"))
    val catalog = CatalogCache(root.child("catalog"))
    val pullRequests = PullRequestCache(root.child("pullrequests"))
    val slashCommands = SlashCommandCache(root.child("slashcommands"))
    /** Which store each Project's coordinator owns, and the context documents opened from a chat (see `StoreFileRepository`). */
    val storeFiles: JsonDiskCache = root.child("storefiles")

    /**
     * Stops the caches accepting writes, before the work that feeds them is cancelled. A blocking write already in
     * flight cannot be cancelled, so this is what keeps it from landing behind [clear].
     */
    fun invalidate() = root.invalidate()

    suspend fun clear() = root.clear()
}

/**
 * The `/` catalog the account service last listed for a repository or an agent, one file per scope, so the composer's
 * popover has its project and plugin skills before the network answers (and while it does not).
 */
class SlashCommandCache(private val cache: JsonDiskCache, private val maxEntries: Int = MAX_ENTRIES) {
    suspend fun read(scopeKey: String): JsonDiskCache.Entry<SlashCatalog>? = cache.read(scopeKey, SlashCatalog.serializer(), VERSION)

    suspend fun write(scopeKey: String, catalog: SlashCatalog, token: Int = cache.token()) {
        if (cache.write(scopeKey, SlashCatalog.serializer(), VERSION, catalog, token)) cache.prune(maxEntries)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun clear() = cache.clear()

    /** Forgets every catalog without disturbing the other caches; for Extended mode changing. */
    suspend fun removeAll() = cache.removeAll()

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 100
    }
}

@Serializable
private data class CachedPullRequests(val statuses: Map<String, PullRequestStatus>)

/** What GitHub last said about the agents' pull requests, by `prUrl`, so the Git filter is right from the first frame. */
class PullRequestCache(private val cache: JsonDiskCache) {
    suspend fun read(): Map<String, PullRequestStatus>? = cache.read(KEY, CachedPullRequests.serializer(), VERSION)?.value?.statuses

    suspend fun write(statuses: Map<String, PullRequestStatus>, token: Int = cache.token()) {
        cache.write(KEY, CachedPullRequests.serializer(), VERSION, CachedPullRequests(statuses), token)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun clear() = cache.clear()

    /** Forgets the states without disturbing the other caches; for Extended mode changing, when their source changes. */
    suspend fun removeAll() = cache.removeAll()

    private companion object {
        const val KEY = "states"
        const val VERSION = 1
    }
}

@Serializable
private data class CachedAgentList(val agents: List<Agent>, val lineage: CachedLineage? = null)

/**
 * What the account has said about where chats belong, kept with the list: the placements of chats the pages did not
 * hold when the word came (their rows carry nothing yet), and the sources it gave them. Restored with the rows, so a
 * restart does not lose the evidence a later page will be placed by.
 */
@Serializable
data class CachedLineage(
    val placements: List<CachedPlacement> = emptyList(),
    val sources: Map<String, AgentSource> = emptyMap(),
    val hintRefused: Set<String> = emptySet(),
    /** The root registry (see [KnownRoot]): every Project the account was known to have, whether or not a row is on disk. */
    val roots: List<KnownRoot> = emptyList(),
    /** The account's records of chats no row on disk holds, for the pages that bring them (see `AgentRepository.pendingRecords`). */
    val records: List<CachedRecord> = emptyList(),
)

/** One chat's placement: under [parentId] in the capacity of [kind], by [signal]'s word ([parentId] null is an older build's root placement, read no more). */
@Serializable
data class CachedPlacement(val id: String, val parentId: String? = null, val kind: AgentParentKind? = null, val signal: LineageSignal)

/** One chat's account record, the fields the desktop's predicates read, for a chat no row on disk holds. */
@Serializable
data class CachedRecord(val id: String, val fields: RecordFields)

class AgentListCache(private val cache: JsonDiskCache) {
    suspend fun read(): JsonDiskCache.Entry<List<Agent>>? =
        cache.read(KEY, CachedAgentList.serializer(), VERSION)?.let { JsonDiskCache.Entry(it.value.agents, it.savedAtMillis) }

    /** The lineage kept with the list (see [CachedLineage]); null when the list was written without one. */
    suspend fun readLineage(): CachedLineage? = cache.read(KEY, CachedAgentList.serializer(), VERSION)?.value?.lineage

    suspend fun write(agents: List<Agent>, token: Int = cache.token(), lineage: CachedLineage? = null) {
        cache.write(KEY, CachedAgentList.serializer(), VERSION, CachedAgentList(agents, lineage), token)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun clear() = cache.clear()

    private companion object {
        const val KEY = "list"
        const val VERSION = 1
    }
}

/**
 * A prompt sent from this device that the server had not reported in full when the transcript was written: its
 * message, the run the server created for it, and the reply when the run finished while it was being watched.
 */
@Serializable
data class CachedLocalPrompt(
    val message: V0ConversationMessageDto,
    val run: RunDto,
    val reply: V0ConversationMessageDto? = null,
)

/**
 * The raw inputs of a transcript rather than the rendered timeline, which is rebuilt from these on every load together
 * with the run traces and the prompt images kept on this device. The server's transcript and run list are kept as
 * they were reported, apart from the prompts sent from here, so the next start can go on standing them in for
 * whatever the server has still not caught up with.
 */
@Serializable
data class CachedConversation(
    val agentId: String,
    val messages: List<V0ConversationMessageDto>,
    val runs: List<RunDto>,
    val transcriptUnavailable: Boolean = false,
    /** The agent row's `updatedAt` when this was fetched; a newer row means the transcript has moved on. */
    val agentUpdatedAtMillis: Long = 0L,
    val local: List<CachedLocalPrompt> = emptyList(),
    /** Whether [runs] are every run of the chat, or only the newest ones as far as the list was paged ([olderRunsCursor] then says where the rest starts). */
    val runsComplete: Boolean = true,
    val olderRunsCursor: String? = null,
    /** How many of the newest runs were rendered when this was written; the next open starts on the same window. Zero: the default. */
    val window: Int = 0,
)

class ConversationCache(private val cache: JsonDiskCache, private val maxEntries: Int = MAX_ENTRIES) {
    suspend fun read(agentId: String): JsonDiskCache.Entry<CachedConversation>? =
        cache.read(agentId, CachedConversation.serializer(), VERSION)

    suspend fun write(conversation: CachedConversation, token: Int = cache.token()) {
        if (cache.write(conversation.agentId, CachedConversation.serializer(), VERSION, conversation, token)) cache.prune(maxEntries)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun remove(agentId: String) = cache.remove(agentId)

    suspend fun clear() = cache.clear()

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 200
    }
}

/**
 * The trace of one finished run — the thinking, tool and subagent items its stream produced, its final reply and
 * its footer — exactly as the conversation renders it. Unlike the transcript, whose items are derived from its inputs
 * on every load, a trace is complete in itself, so the rendered items are what is kept.
 */
@Serializable
data class CachedTrace(
    val runId: String,
    /** The run's `createdAt`; orders the traces of an agent and decides which ones a full file lets go of. */
    val createdAtMillis: Long,
    val items: List<TimelineItem>,
)

/** The whole-file format traces were kept in until 0.3.2: every trace of an agent in one entry (see [TraceCache.migrate]). */
@Serializable
private data class CachedTraces(val agentId: String, val runs: List<CachedTrace>)

/** What one agent's directory holds, so the newest runs can be kept without reading every file: per run, its stamp and size. */
@Serializable
private data class CachedTraceIndex(val runs: List<Entry> = emptyList()) {
    @Serializable
    data class Entry(val runId: String, val createdAtMillis: Long, val bytes: Long)
}

/**
 * The complete traces of finished runs, one file per run, under one directory per agent. The API only retains a
 * run's event log for a while (`410 stream_expired` afterwards), so a trace is written the moment it has been seen
 * whole — followed live to its result, or replayed — and opening the chat later shows it from here, whether or not
 * the log still exists. That makes this store the only copy of a run's tool calls once the log is gone, which is why
 * it is laid out as it is:
 *
 *  - A run is its own file. Writing one never re-reads or re-serialises the agent's other runs, a file that will not
 *    decode (a corrupt write, a shape a newer build wrote) costs that run alone, and the conversation reads exactly
 *    the runs of the window it shows rather than parsing everything the agent ever did to open a chat.
 *  - The budget bounds runs, not payload. Until 0.3.2 the agent's whole file had to fit 2 MiB, and a trace carries
 *    each tool call's clipped payload (up to [com.cursorforandroid.domain.ToolPayloadLimits.MAX_TEXT_CHARS] a call),
 *    so a chat with a few hundred reads and edits filled the budget with its newest handful of runs and the rest were
 *    dropped from the disk on every write; after a restart those runs were replayed from the server on every open,
 *    and once their logs had expired their tool calls were gone for good. The per-agent budget is now proportioned to
 *    the payloads a long chat actually carries, and the oldest runs beyond it go — never a run the conversation just
 *    watched or replayed.
 *  - The on-disk shape is read across versions: a trace written by an earlier build whose items still decode is kept,
 *    where the previous store deleted the agent's whole file on any version bump.
 *
 * What a call produced that the transcript renders — the clipped diff, file text, question, or the `file://` URI of a
 * generated image — is typed on the call ([com.cursorforandroid.domain.ToolPayload]) and travels with it; the raw
 * `args` and `result` never reach the disk (see [com.cursorforandroid.domain.ToolCall.output]).
 */
class TraceCache(
    private val cache: JsonDiskCache,
    private val maxAgents: Int = MAX_AGENTS,
    private val maxRunsPerAgent: Int = MAX_RUNS_PER_AGENT,
    private val maxBytesPerAgent: Long = MAX_BYTES_PER_AGENT,
) {
    /** One writer per agent at a time: the index is a read-merge-write, small as it is. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Agents whose whole-file trace store has been looked for (and migrated when found) this process. */
    private val migrated = ConcurrentHashMap.newKeySet<String>()

    private fun agentCache(agentId: String): JsonDiskCache = cache.child(JsonDiskCache.sanitize(agentId))

    /** Every trace saved for the agent, by run id. Reads every file; the conversation reads its window with the [read] by ids. */
    suspend fun read(agentId: String): Map<String, CachedTrace> {
        migrate(agentId)
        return read(agentId, runIds(agentId))
    }

    /** The traces the disk holds for [runIds], by run id: one small file per run named, nothing else touched. */
    suspend fun read(agentId: String, runIds: Collection<String>): Map<String, CachedTrace> {
        if (runIds.isEmpty()) return emptyMap()
        migrate(agentId)
        val files = agentCache(agentId)
        val found = LinkedHashMap<String, CachedTrace>()
        for (runId in runIds.distinct()) {
            if (runId == INDEX_KEY || !files.has(runId)) continue
            files.read(runId, CachedTrace.serializer(), READABLE_VERSIONS)?.value?.let { found[runId] = it }
        }
        return found
    }

    /** The runs the agent has a trace for, from the index. */
    suspend fun runIds(agentId: String): Set<String> {
        migrate(agentId)
        return readIndex(agentId).runs.mapTo(LinkedHashSet()) { it.runId }
    }

    /** Writes [traces], one file each, replacing what the agent had for the same runs, and lets the oldest go past the budget. */
    suspend fun put(agentId: String, traces: Collection<CachedTrace>, token: Int = cache.token()) {
        if (traces.isEmpty()) return
        migrate(agentId)
        val files = agentCache(agentId)
        locks.getOrPut(agentId) { Mutex() }.withLock {
            val index = readIndex(agentId).runs.associateBy { it.runId }.toMutableMap()
            var wrote = false
            for (trace in traces.distinctBy { it.runId }) {
                if (trace.runId == INDEX_KEY) continue
                if (files.write(trace.runId, CachedTrace.serializer(), VERSION, trace, token)) {
                    index[trace.runId] = CachedTraceIndex.Entry(trace.runId, trace.createdAtMillis, files.size(trace.runId))
                    wrote = true
                }
            }
            if (!wrote) return
            val kept = withinBudget(index.values.sortedByDescending { it.createdAtMillis })
            index.keys.filterNot { id -> kept.any { it.runId == id } }.forEach { files.remove(it) }
            files.write(INDEX_KEY, CachedTraceIndex.serializer(), INDEX_VERSION, CachedTraceIndex(kept), token)
        }
        cache.pruneChildren(maxAgents)
    }

    /**
     * The newest runs — already ordered newest first — that fit the agent's budget: at most [maxRunsPerAgent] of them,
     * and no more bytes than [maxBytesPerAgent] once the newest is in. The newest run is always kept, whatever it weighs.
     */
    private fun withinBudget(runs: List<CachedTraceIndex.Entry>): List<CachedTraceIndex.Entry> {
        var used = 0L
        val kept = ArrayList<CachedTraceIndex.Entry>(runs.size)
        for (run in runs) {
            used += run.bytes
            if (kept.isNotEmpty() && (used > maxBytesPerAgent || kept.size >= maxRunsPerAgent)) break
            kept += run
        }
        return kept
    }

    private suspend fun readIndex(agentId: String): CachedTraceIndex =
        agentCache(agentId).read(INDEX_KEY, CachedTraceIndex.serializer(), INDEX_VERSION)?.value ?: rebuildIndex(agentId)

    /** An index that is missing (a kill between a run's write and the index's) is rebuilt from the files that are there. */
    private suspend fun rebuildIndex(agentId: String): CachedTraceIndex {
        val files = agentCache(agentId)
        val entries = files.keys().filter { it != INDEX_KEY }.mapNotNull { runId ->
            files.read(runId, CachedTrace.serializer(), READABLE_VERSIONS)?.value?.let { CachedTraceIndex.Entry(runId, it.createdAtMillis, files.size(runId)) }
        }
        return CachedTraceIndex(entries)
    }

    /**
     * Moves an agent's traces out of the whole-file store an earlier build kept them in, run by run, once per agent
     * and process. What the old file held is what the new layout starts with; nothing a user already had is lost to
     * the change of shape.
     */
    private suspend fun migrate(agentId: String) {
        if (!migrated.add(agentId)) return
        if (!cache.has(agentId)) return
        val legacy = cache.read(agentId, CachedTraces.serializer(), LEGACY_VERSIONS)?.value
        if (legacy != null && legacy.runs.isNotEmpty()) {
            val files = agentCache(agentId)
            val token = cache.token()
            locks.getOrPut(agentId) { Mutex() }.withLock {
                val index = LinkedHashMap<String, CachedTraceIndex.Entry>()
                for (trace in legacy.runs) {
                    if (files.write(trace.runId, CachedTrace.serializer(), VERSION, trace, token)) {
                        index[trace.runId] = CachedTraceIndex.Entry(trace.runId, trace.createdAtMillis, files.size(trace.runId))
                    }
                }
                files.write(INDEX_KEY, CachedTraceIndex.serializer(), INDEX_VERSION, CachedTraceIndex(index.values.sortedByDescending { it.createdAtMillis }), token)
            }
        }
        cache.remove(agentId)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun remove(agentId: String) {
        migrated.add(agentId)
        cache.remove(agentId)
        agentCache(agentId).drop()
    }

    suspend fun clear() {
        migrated.clear()
        cache.clear()
    }

    private companion object {
        /**
         * Per-run files. 2 and 3 were the whole-file store's shapes (2: tool calls carry their Cursor-worded summary,
         * server and stats, and subagents are tool calls; 3: a call keeps the clipped output it opens onto instead
         * of the raw payload it was read from); 4 is the same [CachedTrace], one file per run.
         */
        const val VERSION = 4
        /** A trace written by any build since the per-run layout is read; the items only ever gain fields with defaults. */
        val READABLE_VERSIONS = 4..VERSION
        val LEGACY_VERSIONS = listOf(2, 3)
        const val INDEX_KEY = "_index"
        const val INDEX_VERSION = 1
        const val MAX_AGENTS = 200
        /** As deep as a chat can be paged: past this the oldest go, so a chat that never stops cannot fill the disk. */
        const val MAX_RUNS_PER_AGENT = 400

        /**
         * One agent's traces at most. A tool call's payload is clipped to 40 000 characters, so this is room for some
         * eight hundred payload-carrying calls before the oldest runs go — a long chat's worth, where 2 MiB was six runs'.
         */
        const val MAX_BYTES_PER_AGENT = 32L shl 20
    }
}

@Serializable
private data class CachedModels(val models: List<ModelOption>)

@Serializable
private data class CachedRepositories(val repositories: List<Repository>)

class CatalogCache(private val cache: JsonDiskCache) {
    suspend fun readModels(): JsonDiskCache.Entry<List<ModelOption>>? =
        cache.read(MODELS, CachedModels.serializer(), VERSION)?.let { JsonDiskCache.Entry(it.value.models, it.savedAtMillis) }

    suspend fun writeModels(models: List<ModelOption>, token: Int = cache.token()) {
        cache.write(MODELS, CachedModels.serializer(), VERSION, CachedModels(models), token)
    }

    suspend fun readRepositories(): JsonDiskCache.Entry<List<Repository>>? =
        cache.read(REPOSITORIES, CachedRepositories.serializer(), VERSION)?.let { JsonDiskCache.Entry(it.value.repositories, it.savedAtMillis) }

    suspend fun writeRepositories(repositories: List<Repository>, token: Int = cache.token()) {
        cache.write(REPOSITORIES, CachedRepositories.serializer(), VERSION, CachedRepositories(repositories), token)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun clear() = cache.clear()

    private companion object {
        const val MODELS = "models"
        const val REPOSITORIES = "repositories"
        const val VERSION = 1
    }
}
