package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.DateHeader
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class ConversationState(
    val agentId: String,
    val items: List<TimelineItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val activeRunId: String? = null,
    val runStatus: RunStatus? = null,
    val isStreaming: Boolean = false,
    val transcriptUnavailable: Boolean = false,
)

/**
 * Owns the transcript for each open agent. History comes from `/v0/agents/{id}/conversation` and
 * `/v1/agents/{id}/runs`; anything happening right now arrives through the [LiveRunHub], which shares one SSE
 * stream per run with the live-notification monitor.
 *
 * Opening a chat is cache-first: the transcript saved on disk (by an earlier visit or the background prefetch)
 * renders immediately and the network only revalidates it. Everything the transcript is built from — never the
 * rendered items, whose date headers are relative — is written back after each load, follow-up and finished run.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    private val hub: LiveRunHub,
    private val cache: ConversationCache? = null,
    /** Prefetching only runs while the app is visible; the default lets tests and the demo skip that question. */
    private val isForeground: () -> Boolean = { true },
    private val prefetchLimit: Int = PREFETCH_LIMIT,
    private val prefetchSpacingMs: Long = PREFETCH_SPACING_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private inner class Entry(agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(ConversationState(agentId))
        var history: List<TimelineItem> = emptyList()
        /** Items of the run in progress; folded into [history] the moment the run finishes. */
        var liveItems: List<TimelineItem> = emptyList()
        /** What [history] was built from and what the disk cache stores. */
        var messages: List<V0ConversationMessageDto> = emptyList()
        var runs: List<RunDto> = emptyList()
        var transcriptUnavailable = false
        /** The agent row's `updatedAt` the inputs correspond to; the prefetch skips rows that have not moved. */
        var inputsUpdatedAt = 0L
        /** True once this session fetched the inputs from the network (as opposed to disk). */
        var fetched = false
        var streamJob: Job? = null
        var loadJob: Job? = null
        var attached = 0
        var lastUsedAt = AppClock.now()

        val hasInputs: Boolean get() = messages.isNotEmpty() || runs.isNotEmpty()
        val isIdle: Boolean get() = attached == 0 && streamJob == null && loadJob?.isActive != true
    }

    private val entries = LinkedHashMap<String, Entry>()
    /** agentId -> the `agentUpdatedAtMillis` of its entry on disk ([ABSENT] when known to be missing). */
    private val diskIndex = ConcurrentHashMap<String, Long>()
    private var prefetchJob: Job? = null
    private var pendingPrefetch: List<Agent>? = null

    init {
        // A completed list fetch is the cue to warm the transcripts most likely to be opened next.
        scope.launch {
            agents.state
                .filter { it.hasLoaded && !it.isRefreshing && !it.isFromCache }
                .map { it.agents }
                .distinctUntilChanged()
                .collect { schedulePrefetch(it) }
        }
    }

    private fun entry(agentId: String): Entry = synchronized(entries) {
        entries[agentId]?.also { it.lastUsedAt = AppClock.now() } ?: run {
            evictIdleEntries()
            Entry(agentId).also { entries[agentId] = it }
        }
    }

    /** Keeps memory bounded: the least recently used transcripts nobody is looking at are dropped (the disk keeps them). */
    private fun evictIdleEntries() {
        if (entries.size < MAX_ENTRIES) return
        entries.values.filter { it.isIdle }.sortedBy { it.lastUsedAt }
            .take(entries.size - MAX_ENTRIES + 1)
            .forEach { victim ->
                entries.values.remove(victim)
                victim.scope.cancel()
            }
    }

    fun state(agentId: String): StateFlow<ConversationState> = entry(agentId).state.asStateFlow()

    /** True while at least one conversation screen shows this agent. */
    fun isAttached(agentId: String): Boolean = synchronized(entries) { (entries[agentId]?.attached ?: 0) > 0 }

    /** Attach a screen. Loads history on first attach and keeps streaming while at least one screen is attached. */
    fun attach(agentId: String) {
        val e = entry(agentId)
        e.attached++
        if (e.attached == 1) {
            e.loadJob?.cancel()
            e.loadJob = e.scope.launch {
                agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                load(e, agentId)
            }
        }
    }

    fun detach(agentId: String) {
        val e = entry(agentId)
        e.attached = (e.attached - 1).coerceAtLeast(0)
        if (e.attached == 0) {
            e.streamJob?.cancel()
            e.streamJob = null
            e.state.update { it.copy(isStreaming = false) }
        }
    }

    /** Drops everything known about an agent, on disk too; used when it is deleted. */
    fun forget(agentId: String) {
        synchronized(entries) { entries.remove(agentId) }?.scope?.cancel()
        diskIndex[agentId] = ABSENT
        cache?.let { scope.launch { it.remove(agentId) } }
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.streamJob?.cancel()
        e.loadJob?.cancel()
        e.loadJob = e.scope.launch { load(e, agentId) }
    }

    private suspend fun load(e: Entry, agentId: String) {
        val backend = session.current
        val api = backend.api
        if (!e.hasInputs) restoreFromCache(e, agentId)
        e.state.update { it.copy(isLoading = true, error = null) }
        try {
            coroutineScope {
                val conversation = async { runCatching { api.conversationV0(agentId) } }
                val runs = async { runCatching { api.listRuns(agentId, limit = 50).items } }
                val convResult = conversation.await()
                val runList = runs.await().getOrElse { emptyList() }
                val messages = convResult.getOrNull()?.messages ?: emptyList()
                val transcriptUnavailable = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                val latestStatus = latest?.statusEnum()
                val fetched = convResult.isSuccess || runList.isNotEmpty()
                if (fetched) {
                    applyInputs(e, messages, runList, transcriptUnavailable && messages.isEmpty(), agents.agent(agentId)?.updatedAtMillis ?: 0L)
                    e.fetched = true
                }
                e.state.update {
                    it.copy(
                        isLoading = false,
                        error = if (convResult.isFailure && runList.isEmpty()) convResult.exceptionOrNull()?.userMessage() else null,
                    )
                }
                // The full agent record only enriches the row (repo, PR, duration); it never holds up the transcript,
                // and the latest run is already known from the list above, saving a round-trip.
                launch { agents.loadDetail(agentId, latest) }
                if (fetched) {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    persist(e, backend)
                    if (latest != null && latestStatus?.isActive == true) startStreaming(e, agentId, latest)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            e.state.update { it.copy(isLoading = false, error = t.userMessage()) }
        }
    }

    /** Shows the transcript saved by an earlier visit, if any, while the network answers. */
    private suspend fun restoreFromCache(e: Entry, agentId: String) {
        val cached = readCache(agentId) ?: return
        if (e.hasInputs || e.fetched) return
        applyInputs(e, cached.messages, cached.runs, cached.transcriptUnavailable, cached.agentUpdatedAtMillis)
        // A run that was still active when the cache was written has very likely finished since; the live state
        // ("Working…", Stop) waits for the network unless a fetched agent row confirms the run is still going.
        val list = agents.state.value
        val row = list.agents.firstOrNull { it.id == agentId }
        if (e.state.value.runStatus?.isActive == true && (list.isFromCache || row?.isRunning != true)) {
            e.state.update { it.copy(runStatus = null) }
        }
    }

    /** Rebuilds the history from raw transcript inputs and publishes it (unless a run is streaming on top). */
    private fun applyInputs(
        e: Entry,
        messages: List<V0ConversationMessageDto>,
        runs: List<RunDto>,
        transcriptUnavailable: Boolean,
        agentUpdatedAt: Long,
    ) {
        e.messages = messages
        e.runs = runs
        e.transcriptUnavailable = transcriptUnavailable
        e.inputsUpdatedAt = agentUpdatedAt
        e.history = TimelineBuilder.fromHistory(messages, runs)
        e.liveItems = emptyList()
        val latest = runs.maxByOrNull { parseIsoMillis(it.createdAt) }
        e.state.update {
            it.copy(
                items = e.history,
                activeRunId = latest?.id,
                runStatus = latest?.statusEnum(),
                isStreaming = false,
                transcriptUnavailable = transcriptUnavailable,
            )
        }
    }

    private suspend fun readCache(agentId: String): CachedConversation? {
        val store = cache?.takeIf { !session.isDemo } ?: return null
        val cached = store.read(agentId)?.value
        diskIndex[agentId] = cached?.agentUpdatedAtMillis ?: ABSENT
        return cached
    }

    private suspend fun persist(e: Entry, backend: CursorBackend) {
        val store = cache ?: return
        if (backend.isDemo || !e.hasInputs) return
        val agentId = e.state.value.agentId
        store.write(CachedConversation(agentId, e.messages, e.runs, e.transcriptUnavailable, e.inputsUpdatedAt))
        diskIndex[agentId] = e.inputsUpdatedAt
    }

    private fun startStreaming(e: Entry, agentId: String, run: RunDto) {
        e.streamJob?.cancel()
        e.liveItems = emptyList()
        e.state.update { it.copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true, items = e.history) }
        e.streamJob = e.scope.launch {
            val backend = session.current
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    if (snapshot.finished) {
                        e.history = e.history + snapshot.items
                        e.liveItems = emptyList()
                        e.state.update { it.copy(items = e.history, runStatus = snapshot.status, isStreaming = false) }
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        val finishedAt = snapshot.finishedAtMillis ?: AppClock.now()
                        prefs.markRead(agentId, finishedAt)
                        recordFinishedRun(e, run, snapshot, finishedAt)
                        persist(e, backend)
                    } else {
                        e.liveItems = snapshot.items
                        e.state.update { it.copy(items = e.history + snapshot.items, runStatus = snapshot.status, isStreaming = true) }
                    }
                }
        }
    }

    /**
     * Folds a run that finished while we watched into the cached inputs, the way the server will report it: the
     * final reply as an assistant message and the run itself as terminal. The next open renders it from disk.
     */
    private fun recordFinishedRun(e: Entry, run: RunDto, snapshot: LiveRunHub.Snapshot, finishedAt: Long) {
        val result = snapshot.result
        val text = result?.text?.trim().orEmpty()
        val terminal = run.copy(
            status = snapshot.status.name,
            updatedAt = ISO.format(Instant.ofEpochMilli(finishedAt)),
            durationMs = result?.durationMs ?: run.durationMs,
            result = text.ifEmpty { run.result },
            git = result?.git ?: run.git,
        )
        e.runs = e.runs.filterNot { it.id == run.id } + terminal
        if (text.isNotEmpty() && e.messages.none { it.id == "res-${run.id}" }) {
            e.messages = e.messages + V0ConversationMessageDto("res-${run.id}", "assistant_message", text)
        }
        e.inputsUpdatedAt = maxOf(e.inputsUpdatedAt, finishedAt)
    }

    suspend fun sendFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList()): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = AppClock.now()
        val optimistic = listOf(
            DateHeader("hdr-local-$now", TimeFormat.conversationStamp(now)),
            UserMessage("local-$now", trimmed, now),
        )
        e.history = e.history + optimistic
        e.state.update { it.copy(items = e.history + e.liveItems, error = null) }

        val result = agents.followUp(agentId, trimmed, images)
        return result.fold(
            onSuccess = { run ->
                e.messages = e.messages + V0ConversationMessageDto("local-$now", "user_message", trimmed)
                e.runs = e.runs.filterNot { it.id == run.id } + run
                e.inputsUpdatedAt = maxOf(e.inputsUpdatedAt, now)
                startStreaming(e, agentId, run)
                persist(e, session.current)
                Result.success(Unit)
            },
            onFailure = { t ->
                e.history = e.history.filterNot { it.id == "hdr-local-$now" || it.id == "local-$now" }
                e.state.update { it.copy(items = e.history + e.liveItems, error = t.userMessage()) }
                Result.failure(t)
            },
        )
    }

    suspend fun cancelActiveRun(agentId: String): Result<Unit> {
        val e = entry(agentId)
        val runId = e.state.value.activeRunId ?: return Result.failure(IllegalStateException("No active run."))
        return agents.cancelRun(agentId, runId).onSuccess {
            e.state.update { it.copy(runStatus = RunStatus.CANCELLED) }
        }
    }

    fun clearError(agentId: String) = entry(agentId).state.update { it.copy(error = null) }

    fun resetAll() {
        synchronized(this) {
            prefetchJob?.cancel()
            prefetchJob = null
            pendingPrefetch = null
        }
        diskIndex.clear()
        synchronized(entries) {
            entries.values.forEach { it.scope.cancel() }
            entries.clear()
        }
    }

    // -- prefetch ----------------------------------------------------------------------------------------------------

    /**
     * Warms the transcripts the user is most likely to open next: the most recently updated idle agents whose
     * cached copy is missing or older than the row. One agent at a time, spaced out, and abandoned at the first
     * failure (offline, rate limited) rather than hammering the API; the next completed list fetch tries again.
     */
    private fun schedulePrefetch(list: List<Agent>) {
        if (cache == null || session.isDemo) return
        val candidates = list.asSequence()
            .filter { !it.isArchived && !it.isRunning }
            .sortedByDescending { it.updatedAtMillis }
            .take(prefetchLimit)
            .toList()
        if (candidates.isEmpty()) return
        synchronized(this) {
            // A pass already running is left alone (cancelling it would waste its in-flight request); it picks up
            // the newest candidates when it is done.
            pendingPrefetch = candidates
            if (prefetchJob?.isActive == true) return
            prefetchJob = scope.launch {
                while (true) {
                    val next = synchronized(this@ConversationRepository) { pendingPrefetch.also { pendingPrefetch = null } } ?: break
                    prefetch(next)
                }
            }
        }
    }

    private suspend fun prefetch(candidates: List<Agent>) {
        val backend = session.current
        val api = backend.api
        for (agent in candidates) {
            if (!isForeground()) return
            if (isFresh(agent)) continue
            val ok = runCatching {
                coroutineScope {
                    val conversation = async { api.conversationV0(agent.id) }
                    val runs = async { api.listRuns(agent.id, limit = 50).items }
                    val messages = conversation.await().messages
                    val runList = runs.await()
                    val e = entry(agent.id)
                    // A screen that opened it in the meantime owns the entry now.
                    if (e.attached == 0 && e.streamJob == null) {
                        applyInputs(e, messages, runList, transcriptUnavailable = false, agent.updatedAtMillis)
                        e.fetched = true
                        e.state.update { it.copy(isLoading = false) }
                        persist(e, backend)
                    }
                }
            }.isSuccess
            if (!ok) return
            delay(prefetchSpacingMs)
        }
    }

    /**
     * The transcript is fresh when what we hold (in memory or on disk) is as new as the agent row. Each agent's
     * file is read at most once per session for this check; afterwards the index answers from memory.
     */
    private suspend fun isFresh(agent: Agent): Boolean {
        val inMemory = synchronized(entries) { entries[agent.id] }
        if (inMemory != null && inMemory.hasInputs && inMemory.inputsUpdatedAt >= agent.updatedAtMillis) return true
        val onDisk = diskIndex[agent.id] ?: (readCache(agent.id)?.agentUpdatedAtMillis ?: ABSENT)
        return onDisk >= agent.updatedAtMillis
    }

    private companion object {
        const val MAX_ENTRIES = 24
        const val PREFETCH_LIMIT = 6
        const val PREFETCH_SPACING_MS = 400L
        const val ABSENT = -1L
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
