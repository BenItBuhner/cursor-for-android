package com.cursorforandroid.data.local

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.KnownRoot
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.data.api.RecordFields
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.PullRequestStatus
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    /** The account records' blobs (the Beta transcript engine's, see `BlobCache`). */
    val blobs = BlobDiskStore(root.child("blobs"))

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
    /**
     * When a discovery pass last read the whole account list (to its end, or as far as a pass may): the registry is
     * the account's, and a later pass may stop at the page older than every Project it knows (see
     * `ProjectRepository.discoverRoots`). Null until one has, and on a disk an older build wrote.
     */
    val registryCompleteAtMillis: Long? = null,
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

    /** The rows and the registry's words in one read of the file (see [read] and [readLineage]). */
    suspend fun readWithLineage(): Pair<JsonDiskCache.Entry<List<Agent>>, CachedLineage?>? =
        cache.read(KEY, CachedAgentList.serializer(), VERSION)?.let { JsonDiskCache.Entry(it.value.agents, it.savedAtMillis) to it.value.lineage }

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
    /** The prompt was steered into [run] under way: shown after this many of the run's rows (see `ConversationRepository.LocalPrompt.steeredAfter`). */
    val steeredAfter: Int? = null,
    /**
     * Written by 0.3.70–0.3.75 alone, for a Project's message drawn in the transcript while it waited behind the turn
     * under way: the run it waited behind. Never written now; a prompt read back with it is dropped, its message being
     * on the account's queue card until its run starts.
     */
    val waitsBehind: String? = null,
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
    /** The window of the account's record that was open (Extended mode, see `RecordWindow`); null when the chat was read from `/v0` and `/v1` alone. */
    val record: CachedRecordWindow? = null,
    /** The messages queued on the account from here that had not been delivered (see [CachedAwaiting]). */
    val awaiting: List<CachedAwaiting> = emptyList(),
)

/**
 * A message this device queued on the account behind a turn, not yet seen delivered (`ConversationRepository.Awaiting`):
 * kept so that after a restart the card still knows it for this device's own, and hands it to the transcript in the
 * frame its run's prompt appears — not both at once while the account's list trails the run it started.
 */
@Serializable
data class CachedAwaiting(
    val localId: String,
    val text: String,
    val stagedAtMillis: Long,
    val placeholder: RunDto,
    val behindRunId: String? = null,
    val queuedAtMillis: Long,
    val queuedOnAccount: Boolean = true,
    val followupId: String? = null,
    /** The run the account named for it when it took it (a Project's coordinator mid-turn). */
    val runId: String? = null,
    val priorCopies: Int = 0,
    val priorTranscriptCopies: Int = 0,
    /** Its staged copies (see `AttachmentStore.staged`), to be filed under the run it starts. */
    val attachments: List<MessageAttachment> = emptyList(),
)

/**
 * The account's record of the chat as far as it was read: the record's size, where the loaded steps begin, and each
 * loaded turn's place, prompt and mode. The turns' items are files of their own (`TraceCache`, keyed
 * `record:<stepIndex>`), read back for the turns of the window and never all at once.
 */
@Serializable
data class CachedRecordWindow(
    val total: Int,
    val firstStep: Int,
    val turnCount: Int = 0,
    val turns: List<CachedRecordTurn> = emptyList(),
    /** Each turn's timing as the account gave it, by whole-chat turn index, when it was read. */
    val timings: List<CachedTurnTiming> = emptyList(),
    /** The window is of the blob-backed record, indexed by turn (see `RecordWindow.turnIndexed`); false for the step-indexed record's. */
    val turnIndexed: Boolean = false,
    /**
     * Where the account's live stream of the chat stood when the window was read (`LivePoint`): its offset and the
     * workflow status, so a watch after a restart resumes from there rather than asking for the whole state again.
     */
    val liveOffsetKey: String? = null,
    val liveStatus: String? = null,
    /** The chat is a Project's root by the state it was read with. */
    val rootProject: Boolean = false,
    /** When the account's word last confirmed the window current (see `ConversationRepository.Entry.currentAt`); zero: never. */
    val currentAtMillis: Long = 0L,
)

/**
 * One turn of the saved window. For the blob-backed record, [blobId] is the turn's own blob (content-addressed: a
 * state naming the same id names the turn unchanged, and the next load does not read it), [complete] whether every
 * step was read, and [stepTotal] / [messageSteps] what its structure listed.
 */
@Serializable
data class CachedRecordTurn(
    val stepIndex: Int,
    val stepCount: Int,
    val prompt: String? = null,
    val projectMode: Boolean = false,
    val errorMessage: String? = null,
    val blobId: String? = null,
    val complete: Boolean = true,
    val stepTotal: Int? = null,
    val messageSteps: Int? = null,
)

@Serializable
data class CachedTurnTiming(val durationMs: Long? = null, val timestampMs: Long? = null)

/**
 * The transcripts' inputs, one file per chat, kept for the [maxEntries] chats last opened or written and within
 * [maxBytes] of them: a long chat's file runs to megabytes, so the count alone bounded nothing.
 */
class ConversationCache(
    private val cache: JsonDiskCache,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES,
    private val maxRecordBytes: Long = MAX_RECORD_BYTES,
) {
    /** The Beta engine's windows (see [readRecord]), in a directory of their own: pruned apart, cleared with the rest. */
    private val records = cache.child(RECORDS)

    suspend fun read(agentId: String): JsonDiskCache.Entry<CachedConversation>? =
        cache.read(agentId, CachedConversation.serializer(), VERSION)?.also { cache.touch(agentId) }

    suspend fun write(conversation: CachedConversation, token: Int = cache.token()) {
        if (cache.write(conversation.agentId, CachedConversation.serializer(), VERSION, conversation, token)) cache.prune(maxEntries, maxBytes)
    }

    /**
     * The Beta engine's window of the chat's record, kept apart from [read]'s file. That file is written by every
     * load — the documented path's too: a Stable-engine open, a fallback, the list's warm-up — and a copy written
     * without the record's window cost the next Beta open its whole window, read again from the start. Written by
     * the Beta engine's reads alone.
     */
    suspend fun readRecord(agentId: String): CachedRecordWindow? =
        records.read(agentId, CachedRecordWindow.serializer(), VERSION)?.also { records.touch(agentId) }?.value

    suspend fun writeRecord(agentId: String, window: CachedRecordWindow, token: Int = cache.token()) {
        if (records.write(agentId, CachedRecordWindow.serializer(), VERSION, window, token)) records.prune(maxEntries, maxRecordBytes)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun remove(agentId: String) {
        cache.remove(agentId)
        records.remove(agentId)
    }

    suspend fun clear() = cache.clear()

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 200
        const val MAX_BYTES = 64L shl 20
        /** A window lists its turns, not their items (those are [TraceCache] files), so it is small next to a transcript. */
        const val MAX_RECORD_BYTES = 16L shl 20
        const val RECORDS = "record-windows"
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
 *
 * The whole store is held to [maxTotalBytes] as well (see [trim]). The run monitor writes the trace of every run it
 * sees finish, in chats that may never be opened here, so the per-agent budget times [maxAgents] bounded nothing a
 * phone would call bounded.
 */
class TraceCache(
    private val cache: JsonDiskCache,
    private val maxAgents: Int = MAX_AGENTS,
    private val maxRunsPerAgent: Int = MAX_RUNS_PER_AGENT,
    private val maxBytesPerAgent: Long = MAX_BYTES_PER_AGENT,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    /** One writer per agent at a time: the index is a read-merge-write, small as it is. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Agents whose whole-file trace store has been looked for (and migrated when found) this process. */
    private val migrated = ConcurrentHashMap.newKeySet<String>()

    /** What the store weighs on disk as far as this process has followed its writes; [UNMEASURED] until [trim] has walked it. */
    private val bytesOnDisk = AtomicLong(UNMEASURED)

    /** One [trim] at a time; a write that finds one under way leaves the budget to it. */
    private val trimming = Mutex()

    /** The directories of the agents being written right now, which [trim] leaves alone. */
    private val writing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun agentCache(agentId: String): JsonDiskCache = cache.child(JsonDiskCache.sanitize(agentId))

    /** Every trace saved for the agent, by run id. Reads every file; the conversation reads its window with the [read] by ids. */
    suspend fun read(agentId: String): Map<String, CachedTrace> {
        migrate(agentId)
        return read(agentId, runIds(agentId))
    }

    /**
     * The traces the disk holds for [runIds], by run id: one small file per run named, nothing else touched. The
     * files are read [READ_PARALLELISM] at a time: a turn's file is a few hundred kilobytes of JSON, and a window of
     * thirty read one after another on a phone was the second the tool calls took to come up after a restart.
     */
    suspend fun read(agentId: String, runIds: Collection<String>): Map<String, CachedTrace> {
        if (runIds.isEmpty()) return emptyMap()
        migrate(agentId)
        val files = agentCache(agentId)
        val wanted = runIds.distinct().filter { it != INDEX_KEY && files.has(it) }
        if (wanted.isEmpty()) return emptyMap()
        val read: List<CachedTrace?> = if (wanted.size == 1) {
            listOf(files.read(wanted[0], CachedTrace.serializer(), readableVersions(wanted[0]))?.value)
        } else {
            coroutineScope {
                val next = java.util.concurrent.atomic.AtomicInteger(0)
                val out = arrayOfNulls<CachedTrace>(wanted.size)
                val readers = List(minOf(READ_PARALLELISM, wanted.size)) {
                    launch {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= wanted.size) return@launch
                            out[i] = files.read(wanted[i], CachedTrace.serializer(), readableVersions(wanted[i]))?.value
                        }
                    }
                }
                // Every reader done before the array is read: the scope alone would join them after this value was taken.
                readers.joinAll()
                out.toList()
            }
        }
        val found = LinkedHashMap<String, CachedTrace>()
        wanted.forEachIndexed { i, runId -> read[i]?.let { found[runId] = it } }
        if (found.isNotEmpty()) markOpened(files.root)
        return found
    }

    /** Stamps an agent's traces as shown on this device, which is what ranks them above the ones only the monitor wrote (see [trim]). */
    private suspend fun markOpened(dir: File) = withContext(Dispatchers.IO) {
        val marker = File(dir, OPENED_MARKER)
        runCatching { if (!marker.createNewFile()) marker.setLastModified(System.currentTimeMillis()) }
        Unit
    }

    /**
     * Which builds' files stand for [key]. A run's trace is read across builds: its items only gain fields, and what
     * an earlier build misread is set right at render time. A record turn (`record:<step>`) is not: it is rebuilt
     * from the record's steps, and a build that reads more of them — a coordinator's streamed message, from 5 — must
     * not keep showing a turn an earlier build read less of.
     */
    private fun readableVersions(key: String): Iterable<Int> = if (key.startsWith(RECORD_KEY_PREFIX) || key.startsWith(RECORD_TURN_KEY_PREFIX)) RECORD_READABLE_VERSIONS else READABLE_VERSIONS

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
        val dir = files.root.name
        var delta = 0L
        writing += dir
        try {
            locks.getOrPut(agentId) { Mutex() }.withLock {
                val index = readIndex(agentId).runs.associateBy { it.runId }.toMutableMap()
                val indexBytes = files.size(INDEX_KEY)
                var wrote = false
                for (trace in traces.distinctBy { it.runId }) {
                    if (trace.runId == INDEX_KEY) continue
                    if (files.write(trace.runId, CachedTrace.serializer(), VERSION, trace, token)) {
                        val bytes = files.size(trace.runId)
                        delta += bytes - (index[trace.runId]?.bytes ?: 0L)
                        index[trace.runId] = CachedTraceIndex.Entry(trace.runId, trace.createdAtMillis, bytes)
                        wrote = true
                    }
                }
                if (!wrote) return
                val kept = withinBudget(index.values.sortedByDescending { it.createdAtMillis })
                val keptIds = kept.mapTo(HashSet()) { it.runId }
                index.values.filter { it.runId !in keptIds }.forEach {
                    files.remove(it.runId)
                    delta -= it.bytes
                }
                files.write(INDEX_KEY, CachedTraceIndex.serializer(), INDEX_VERSION, CachedTraceIndex(kept), token)
                delta += files.size(INDEX_KEY) - indexBytes
            }
        } finally {
            writing -= dir
        }
        bytesOnDisk.updateAndGet { if (it == UNMEASURED) it else it + delta }
        if (trimming.tryLock()) {
            try {
                withContext(Dispatchers.IO) { trim(current = dir) }
            } finally {
                trimming.unlock()
            }
        }
    }

    /**
     * Holds the store to [maxTotalBytes] and [maxAgents], whole agents at a time, never [current] (the agent just
     * written) or one being written. Measured only when the running total says it is over, or on the first write of
     * the process; otherwise a stat of the agents' directories.
     *
     * Over the budget, the least valuable go first until the store is back to seven eighths of it:
     *  1. the whole-file stores of 0.3.1 and before that were never migrated (nothing else deletes them);
     *  2. the agents never shown on this device — traces the run monitor wrote for chats nobody opened here — the
     *     longest unwritten first;
     *  3. the agents that were shown, the longest unopened and unwritten first.
     * Only when those opened here do not fit on their own does a chat someone reads go, so the chats of the Projects
     * in use keep opening from disk.
     */
    private fun trim(current: String) {
        val root = cache.root
        val known = bytesOnDisk.get()
        if (known in 0..maxTotalBytes && (root.listFiles { f -> f.isDirectory }?.size ?: 0) <= maxAgents) return
        val held = root.listFiles()?.map(::holdingOf)
        if (held == null) {
            bytesOnDisk.set(0L)
            return
        }
        var total = held.sumOf { it.bytes }
        var agents = held.count { it.isAgent }
        val target = if (total > maxTotalBytes) maxTotalBytes - maxTotalBytes / 8 else Long.MAX_VALUE
        val candidates = held
            .filter { it.file.name != current && it.file.name !in writing }
            .sortedWith(compareBy<Holding>({ it.rank }, { it.usedAt }))
        for (holding in candidates) {
            val overBytes = total > target
            if (!overBytes && agents <= maxAgents) break
            if (!overBytes && !holding.isAgent) continue
            if (holding.file.deleteRecursively()) {
                total -= holding.bytes
                if (holding.isAgent) agents--
            }
        }
        bytesOnDisk.set(total)
    }

    private fun holdingOf(file: File): Holding {
        val bytes = DiskSweep.bytesUnder(file)
        if (!file.isDirectory) return Holding(file, false, bytes, RANK_LEGACY, file.lastModified())
        val openedAt = File(file, OPENED_MARKER).lastModified()
        return if (openedAt > 0L) {
            Holding(file, true, bytes, RANK_OPENED, maxOf(openedAt, file.lastModified()))
        } else {
            Holding(file, true, bytes, RANK_UNOPENED, file.lastModified())
        }
    }

    /** One thing [trim] can delete: an agent's directory, or a file a build before 0.3.2 wrote at the top. */
    private class Holding(val file: File, val isAgent: Boolean, val bytes: Long, val rank: Int, val usedAt: Long)

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
            files.read(runId, CachedTrace.serializer(), readableVersions(runId))?.value?.let { CachedTraceIndex.Entry(runId, it.createdAtMillis, files.size(runId)) }
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
        bytesOnDisk.set(UNMEASURED)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun remove(agentId: String) {
        migrated.add(agentId)
        cache.remove(agentId)
        agentCache(agentId).drop()
        bytesOnDisk.set(UNMEASURED)
    }

    suspend fun clear() {
        migrated.clear()
        cache.clear()
        bytesOnDisk.set(UNMEASURED)
    }

    companion object {
        /**
         * Per-run files. 2 and 3 were the whole-file store's shapes (2: tool calls carry their Cursor-worded summary,
         * server and stats, and subagents are tool calls; 3: a call keeps the clipped output it opens onto instead
         * of the raw payload it was read from); 4 is the same [CachedTrace], one file per run; 5 reads a record
         * turn's tool calls whole (streamed and streamed-back ones included), so record turns written before it are
         * rebuilt (see [readableVersions]).
         */
        const val VERSION = 5
        /** A trace written by any build since the per-run layout is read; the items only ever gain fields with defaults. */
        private val READABLE_VERSIONS = 4..VERSION
        /** A record turn written by a build that read the record as this one does. */
        private val RECORD_READABLE_VERSIONS = 5..VERSION
        /** The key prefix of a record turn's file (see `RecordTurn.traceKey`). */
        const val RECORD_KEY_PREFIX = "record:"
        /** The key prefix of a turn of the blob-backed record, indexed by turn rather than by step (see `RecordTurn.traceKey`). */
        const val RECORD_TURN_KEY_PREFIX = "record-turn:"
        private val LEGACY_VERSIONS = listOf(2, 3)
        private const val INDEX_KEY = "_index"
        private const val INDEX_VERSION = 1
        private const val MAX_AGENTS = 200
        /** As deep as a chat can be paged: past this the oldest go, so a chat that never stops cannot fill the disk. */
        private const val MAX_RUNS_PER_AGENT = 400

        /**
         * One agent's traces at most. A tool call's payload is clipped to 40 000 characters, so this is room for some
         * eight hundred payload-carrying calls before the oldest runs go — a long chat's worth, where 2 MiB was six runs'.
         */
        const val MAX_BYTES_PER_AGENT = 32L shl 20
        /**
         * The whole store at most: six chats at the per-agent budget, and in practice the traces of dozens, since a
         * chat's traces are a fraction of it. Past it the traces nobody opened here go before any that were.
         */
        const val MAX_TOTAL_BYTES = 192L shl 20
        /** Files decoded at once by [read]: the decode is the cost, and phones have the cores for a few. */
        private const val READ_PARALLELISM = 4
        /** In an agent's directory, touched whenever its traces are read back to be shown. Not `.json`: no key reads it. */
        private const val OPENED_MARKER = ".opened"
        private const val UNMEASURED = -1L
        /** [trim]'s order: what goes first. */
        private const val RANK_LEGACY = 0
        private const val RANK_UNOPENED = 1
        private const val RANK_OPENED = 2
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

    /** The machines' workers as the fleet endpoint last listed each (see [MachineWorker]), for a machine that has since gone offline. */
    suspend fun readWorkers(): Map<String, MachineWorker>? = cache.read(WORKERS, CachedWorkers.serializer(), VERSION)?.value?.workers

    suspend fun writeWorkers(workers: Map<String, MachineWorker>, token: Int = cache.token()) {
        cache.write(WORKERS, CachedWorkers.serializer(), VERSION, CachedWorkers(workers), token)
    }

    /** Taken when the work that will write starts; see [JsonDiskCache.token]. */
    fun token(): Int = cache.token()

    suspend fun clear() = cache.clear()

    @Serializable
    private data class CachedWorkers(val workers: Map<String, MachineWorker> = emptyMap())

    private companion object {
        const val MODELS = "models"
        const val REPOSITORIES = "repositories"
        const val WORKERS = "machine-workers"
        const val VERSION = 1
    }
}
