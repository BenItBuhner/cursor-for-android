package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.Agent
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
private data class CachedAgentList(val agents: List<Agent>)

class AgentListCache(private val cache: JsonDiskCache) {
    suspend fun read(): JsonDiskCache.Entry<List<Agent>>? =
        cache.read(KEY, CachedAgentList.serializer(), VERSION)?.let { JsonDiskCache.Entry(it.value.agents, it.savedAtMillis) }

    suspend fun write(agents: List<Agent>, token: Int = cache.token()) {
        cache.write(KEY, CachedAgentList.serializer(), VERSION, CachedAgentList(agents), token)
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

@Serializable
private data class CachedTraces(val agentId: String, val runs: List<CachedTrace>)

/**
 * The complete traces of finished runs, one file per agent. The API only retains a run's event log for a while
 * (`410 stream_expired` afterwards), so a trace is written the moment it has been seen whole — followed live to its
 * result, or replayed — and opening the chat later shows it from here, whether or not the log still exists.
 *
 * Tool call payloads (`args`, `result`) are dropped on the way to disk: their summaries are already part of each call,
 * and they can be as large as the files the tool touched. What a call produced that the transcript does render — the
 * clipped diff, file text, question, or the `file://` URI of a generated image — is typed on the call
 * ([com.cursorforandroid.domain.ToolPayload]) and travels with it; a trace written before those existed reads as it did.
 */
class TraceCache(
    private val cache: JsonDiskCache,
    private val maxAgents: Int = MAX_AGENTS,
    private val maxRunsPerAgent: Int = MAX_RUNS_PER_AGENT,
    private val maxBytesPerAgent: Int = MAX_BYTES_PER_AGENT,
) {
    /** One writer per agent at a time: adding a run is a read-merge-write of the agent's file. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Every trace saved for the agent, by run id. */
    suspend fun read(agentId: String): Map<String, CachedTrace> =
        cache.read(agentId, CachedTraces.serializer(), VERSION)?.value?.runs?.associateBy { it.runId } ?: emptyMap()

    /** Adds [traces] to the agent's file, replacing what it held for the same runs; the newest runs are kept. */
    suspend fun put(agentId: String, traces: Collection<CachedTrace>, token: Int = cache.token()) {
        if (traces.isEmpty()) return
        locks.getOrPut(agentId) { Mutex() }.withLock {
            val merged = (read(agentId) + traces.associate { it.runId to it }).values
                .sortedByDescending { it.createdAtMillis }
                .take(maxRunsPerAgent)
            val kept = CachedTraces(agentId, withinBudget(merged))
            if (cache.write(agentId, CachedTraces.serializer(), VERSION, kept, token)) cache.prune(maxAgents)
        }
    }

    /**
     * The newest of [traces] — already ordered newest first — that fit the agent's byte budget. A trace is as long
     * as the run's log, so a count alone does not bound the file; the oldest are let go of until it does.
     */
    private fun withinBudget(traces: List<CachedTrace>): List<CachedTrace> {
        var used = 0
        val kept = ArrayList<CachedTrace>(traces.size)
        for (trace in traces) {
            used += CursorJson.encodeToString(CachedTrace.serializer(), trace).length
            if (kept.isNotEmpty() && used > maxBytesPerAgent) break
            kept += trace
        }
        return kept
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun remove(agentId: String) = cache.remove(agentId)

    suspend fun clear() = cache.clear()

    private companion object {
        /**
         * 2: tool calls carry their Cursor-worded summary, server and stats, and subagents are tool calls.
         * 3: a call keeps the clipped output it opens onto instead of the raw payload it was read from, so nothing
         * has to be stripped on the way to disk (see [ToolCall.output]).
         */
        const val VERSION = 3
        const val MAX_AGENTS = 200
        /**
         * Matches how deep the conversation pages its runs, so every run whose trace the load replays can be kept:
         * keeping fewer meant a long chat replayed the same logs on every open and then lost them for good once
         * they expired.
         */
        const val MAX_RUNS_PER_AGENT = 400

        /** How much of one agent's traces is worth keeping; past this the oldest go, however few runs that is. */
        const val MAX_BYTES_PER_AGENT = 2 shl 20
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
