package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 * The legacy transcript is text only. Every run's thinking, tool calls and subagents live in its event log, which
 * the hub replays for finished runs as long as the API retains it, so the same trace the live view showed is
 * there to dig into after the fact. Runs whose log has expired keep their text.
 *
 * Opening a chat is cache-first: the transcript saved on disk (by an earlier visit or the background prefetch)
 * renders immediately and the network only revalidates it. What is written back is the transcript's inputs in the
 * shape the server will report them — never the rendered items, whose date headers are relative, and never the
 * traces, which the hub replays.
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
    /**
     * A follow-up sent from this device: its prompt and its run, a placeholder until the server has answered, and
     * — once the run finished while we watched — the reply the transcript will carry, for the disk copy.
     */
    private data class LocalPrompt(val message: V0ConversationMessageDto, val run: RunDto, val reply: V0ConversationMessageDto? = null)

    /**
     * Everything shown for one agent is derived from a few inputs, so a trace that lands, a follow-up that is sent
     * or a live snapshot that arrives all rebuild the same way. Mutations happen under the entry's monitor.
     */
    private inner class Entry(val agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val state = MutableStateFlow(ConversationState(agentId))
        /** The legacy transcript as the server returned it (or as the disk remembered it). */
        var messages: List<V0ConversationMessageDto> = emptyList()
        /** The v1 runs as the server returned them (or as the disk remembered them). */
        var runs: List<RunDto> = emptyList()
        /** Follow-ups sent from here that the server's run list has not caught up with; shown after the history. */
        var local: List<LocalPrompt> = emptyList()
        /** Per run: the items its stream produced, replayed for finished runs and updated live for the active one. */
        var traces: Map<String, List<TimelineItem>> = emptyMap()
        var transcriptUnavailable = false
        /** The agent row's `updatedAt` the inputs correspond to; the prefetch skips rows that have not moved. */
        var inputsUpdatedAt = 0L
        /** True once this session fetched the inputs from the network (as opposed to disk). */
        var fetched = false
        var streamJob: Job? = null
        var traceJob: Job? = null
        var loadJob: Job? = null
        var attached = 0
        var lastUsedAt = AppClock.now()

        val hasInputs: Boolean get() = messages.isNotEmpty() || runs.isNotEmpty()
        val isIdle: Boolean get() = attached == 0 && streamJob == null && traceJob?.isActive != true && loadJob?.isActive != true

        fun items(): List<TimelineItem> =
            TimelineBuilder.fromHistory(messages, runs, traces) + TimelineBuilder.fromHistory(local.map { it.message }, local.map { it.run }, traces)

        /** The inputs as the server will report them once it has caught up, which is what the disk keeps. */
        fun flattened(): CachedConversation = CachedConversation(
            agentId = agentId,
            messages = messages + local.flatMap { listOfNotNull(it.message, it.reply) },
            runs = runs + local.map { it.run },
            transcriptUnavailable = transcriptUnavailable,
            agentUpdatedAtMillis = inputsUpdatedAt,
        )
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
            e.traceJob?.cancel()
            e.traceJob = null
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
        e.traceJob?.cancel()
        e.loadJob?.cancel()
        e.loadJob = e.scope.launch { load(e, agentId) }
    }

    /** Rebuilds the visible items from the entry's inputs after [mutate] has changed them. */
    private inline fun Entry.publish(mutate: Entry.() -> Unit = {}, transform: ConversationState.() -> ConversationState = { this }) {
        synchronized(this) {
            mutate()
            state.update { it.copy(items = items()).transform() }
        }
    }

    private suspend fun load(e: Entry, agentId: String) {
        val backend = session.current
        val api = backend.api
        if (!e.hasInputs) restoreFromCache(e, agentId)
        e.state.update { it.copy(isLoading = true, error = null) }
        try {
            coroutineScope {
                val conversation = async { runCatching { api.conversationV0(agentId) } }
                val runPage = async { runCatching { api.listRuns(agentId, limit = 50).items } }
                val convResult = conversation.await()
                val runList = runPage.await().getOrElse { emptyList() }
                val transcript = convResult.getOrNull()?.messages ?: emptyList()
                val transcriptUnavailable = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                val latestStatus = latest?.statusEnum()
                val fetched = convResult.isSuccess || runList.isNotEmpty()
                if (fetched) {
                    // Traces already known are kept: a finished run's log does not change, and the active run's is
                    // overwritten by the first live snapshot. Local follow-ups hand over to the server once it lists
                    // their run.
                    e.publish(
                        mutate = {
                            messages = transcript
                            runs = runList
                            this.transcriptUnavailable = transcriptUnavailable && transcript.isEmpty()
                            inputsUpdatedAt = agents.agent(agentId)?.updatedAtMillis ?: 0L
                            local = local.filter { prompt -> runList.none { it.id == prompt.run.id } }
                            this.fetched = true
                        },
                        transform = {
                            copy(
                                isLoading = false,
                                error = null,
                                activeRunId = latest?.id,
                                runStatus = latestStatus,
                                isStreaming = false,
                                transcriptUnavailable = transcriptUnavailable && transcript.isEmpty(),
                            )
                        },
                    )
                } else {
                    e.state.update { it.copy(isLoading = false, error = convResult.exceptionOrNull()?.userMessage()) }
                }
                // The full agent record only enriches the row (repo, PR, duration); it never holds up the transcript,
                // and the latest run is already known from the list above, saving a round-trip.
                launch { agents.loadDetail(agentId, latest) }
                if (fetched) {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    persist(e, backend)
                    if (latest != null && latestStatus?.isActive == true) startStreaming(e, agentId, latest)
                    loadTraces(e, agentId, runList.filter { it.statusEnum().isTerminal })
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
        val latest = cached.runs.maxByOrNull { parseIsoMillis(it.createdAt) }
        // A run that was still active when the cache was written has very likely finished since; the live state
        // ("Working…", Stop) waits for the network unless a fetched agent row confirms the run is still going.
        val list = agents.state.value
        val row = list.agents.firstOrNull { it.id == agentId }
        val status = latest?.statusEnum()?.takeUnless { it.isActive && (list.isFromCache || row?.isRunning != true) }
        e.publish(
            mutate = {
                messages = cached.messages
                runs = cached.runs
                transcriptUnavailable = cached.transcriptUnavailable
                inputsUpdatedAt = cached.agentUpdatedAtMillis
            },
            transform = { copy(activeRunId = latest?.id, runStatus = status, transcriptUnavailable = cached.transcriptUnavailable) },
        )
    }

    private suspend fun readCache(agentId: String): CachedConversation? {
        val store = cache?.takeIf { !session.isDemo } ?: return null
        val cached = store.read(agentId)?.value
        diskIndex[agentId] = cached?.agentUpdatedAtMillis ?: ABSENT
        return cached
    }

    private suspend fun persist(e: Entry, backend: CursorBackend) {
        val store = cache ?: return
        if (backend.isDemo) return
        val snapshot = synchronized(e) { if (e.hasInputs || e.local.isNotEmpty()) e.flattened() else null } ?: return
        store.write(snapshot)
        diskIndex[snapshot.agentId] = snapshot.agentUpdatedAtMillis
    }

    /**
     * Replays the retained stream of every finished run that has no trace yet, newest first: the retention window
     * is time-based, so once one run's log has expired every older run's has too and the rest are skipped. A few
     * workers pull from the ordered queue, so the newest runs are always the first ones asked.
     */
    private fun loadTraces(e: Entry, agentId: String, finishedRuns: List<RunDto>) {
        e.traceJob?.cancel()
        val pending = synchronized(e) { finishedRuns.filter { it.id !in e.traces } }.sortedByDescending { parseIsoMillis(it.createdAt) }
        if (pending.isEmpty()) return
        e.traceJob = e.scope.launch {
            val next = AtomicInteger(0)
            val newestExpired = AtomicLong(Long.MIN_VALUE)
            repeat(minOf(MAX_PARALLEL_REPLAYS, pending.size)) {
                launch {
                    while (true) {
                        val run = pending.getOrNull(next.getAndIncrement()) ?: return@launch
                        val createdAt = parseIsoMillis(run.createdAt)
                        if (createdAt < newestExpired.get()) continue
                        val snapshot = hub.replay(agentId, run.id, createdAt.takeIf { it > 0 })
                        when {
                            snapshot.hasTrace -> e.publish(mutate = { traces = traces + (run.id to snapshot.items) })
                            snapshot.expired -> newestExpired.updateAndGet { maxOf(it, createdAt) }
                        }
                    }
                }
            }
        }
    }

    private fun startStreaming(e: Entry, agentId: String, run: RunDto) {
        e.streamJob?.cancel()
        e.publish(transform = { copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true) })
        e.streamJob = e.scope.launch {
            val backend = session.current
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    // The live snapshot is this run's trace; the builder places it after the run's prompt.
                    e.publish(
                        mutate = { traces = traces + (run.id to snapshot.items) },
                        transform = { copy(runStatus = snapshot.status, isStreaming = !snapshot.finished) },
                    )
                    if (snapshot.finished) {
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        val finishedAt = snapshot.finishedAtMillis ?: AppClock.now()
                        prefs.markRead(agentId, finishedAt)
                        e.publish(mutate = { recordFinishedRun(run, snapshot, finishedAt) })
                        persist(e, backend)
                    }
                }
        }
    }

    /**
     * Folds a run that finished while we watched into the inputs, the way the server will report it: the run itself
     * as terminal and its final reply as an assistant message. The disk copy renders it on the next open before the
     * hub has replayed the trace; in memory the trace stands in for the text, so nothing shows twice.
     */
    private fun Entry.recordFinishedRun(run: RunDto, snapshot: LiveRunHub.Snapshot, finishedAt: Long) {
        val result = snapshot.result
        val text = result?.text?.trim().orEmpty()
        val terminal = run.copy(
            status = snapshot.status.name,
            updatedAt = Instant.ofEpochMilli(finishedAt).toString(),
            durationMs = result?.durationMs ?: run.durationMs,
            result = text.ifEmpty { run.result },
            git = result?.git ?: run.git,
        )
        val reply = text.takeIf { it.isNotEmpty() }?.let { V0ConversationMessageDto("res-${run.id}", "assistant_message", it) }
        if (local.any { it.run.id == run.id }) {
            local = local.map { if (it.run.id == run.id) it.copy(run = terminal, reply = reply) else it }
        } else {
            runs = runs.map { if (it.id == run.id) terminal else it }
            if (reply != null && messages.none { it.id == reply.id }) messages = messages + reply
        }
        inputsUpdatedAt = maxOf(inputsUpdatedAt, finishedAt)
    }

    suspend fun sendFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList()): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = AppClock.now()
        // The prompt shows up right away, paired with a placeholder run so it gets a timestamp like every other turn.
        val localId = "local-$now"
        val nowIso = Instant.ofEpochMilli(now).toString()
        val placeholder = RunDto(id = localId, agentId = agentId, status = RunStatus.CREATING.name, createdAt = nowIso, updatedAt = nowIso)
        e.publish(
            mutate = { local = local + LocalPrompt(V0ConversationMessageDto(localId, "user_message", trimmed), placeholder) },
            transform = { copy(error = null) },
        )

        val result = agents.followUp(agentId, trimmed, images)
        return result.fold(
            onSuccess = { run ->
                e.publish(
                    mutate = {
                        local = local.map { if (it.run.id == localId) it.copy(run = run) else it }
                        inputsUpdatedAt = maxOf(inputsUpdatedAt, now)
                    },
                )
                startStreaming(e, agentId, run)
                persist(e, session.current)
                Result.success(Unit)
            },
            onFailure = { t ->
                e.publish(
                    mutate = { local = local.filterNot { it.run.id == localId } },
                    transform = { copy(error = t.userMessage()) },
                )
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
     * Text only: traces are replayed when a chat is actually opened.
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
                    val transcript = conversation.await().messages
                    val runList = runs.await()
                    val e = entry(agent.id)
                    // A screen that opened it in the meantime owns the entry now.
                    if (e.attached == 0 && e.streamJob == null) {
                        e.publish(
                            mutate = {
                                messages = transcript
                                this.runs = runList
                                transcriptUnavailable = false
                                inputsUpdatedAt = agent.updatedAtMillis
                                fetched = true
                            },
                            transform = {
                                val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                                copy(isLoading = false, activeRunId = latest?.id, runStatus = latest?.statusEnum())
                            },
                        )
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
        /** Finished runs replayed at once; older logs mostly answer with `410 stream_expired`, which is cheap. */
        const val MAX_PARALLEL_REPLAYS = 3
    }
}
