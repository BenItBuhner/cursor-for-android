package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.toCursorError
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.CachedConversation
import com.cursorforandroid.data.local.CachedLocalPrompt
import com.cursorforandroid.data.local.CachedTrace
import com.cursorforandroid.data.local.ConversationCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.StagedAttachments
import com.cursorforandroid.data.local.TraceCache
import com.cursorforandroid.data.repo.TimelineBuilder.withUniqueIds
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /**
     * The live connection dropped and is being re-established; the trace shown is complete up to the drop and the
     * run is still going. Only meaningful while [isStreaming].
     */
    val isReconnecting: Boolean = false,
    val transcriptUnavailable: Boolean = false,
)

/** The failure of a [ConversationRepository.launch] that was stopped from the chat before the server had answered. */
class LaunchCancelledException : RuntimeException("The chat was stopped before it started.")

/**
 * Owns the transcript for each open agent. History comes from `/v0/agents/{id}/conversation` and
 * `/v1/agents/{id}/runs`; anything happening right now arrives through the [LiveRunHub], which shares one SSE
 * stream per run with the live-notification monitor.
 *
 * The legacy transcript is text only. Every run's thinking, tool calls and subagents live in its event log, which
 * the hub replays for finished runs as long as the API retains it, so the same trace the live view showed is
 * there to dig into after the fact. A trace seen whole — followed live to its result, here or by the notification
 * monitor, or replayed — is kept on disk from then on, so it survives the retention window and a restart. Runs
 * whose log expired before it was ever seen keep their text. The images a prompt carried never come back from the
 * server at all; they are filled in from the on-device [AttachmentStore].
 *
 * Opening a chat is cache-first: the transcript and traces saved on disk (by an earlier visit or the background
 * prefetch) render immediately and the network only revalidates them. What is written back for the transcript is
 * its inputs in the shape the server will report them — never the rendered items, which are derived from them.
 *
 * Prompts sent from this device are on screen before the server has answered — a new chat [launch]es around its
 * prompt, a follow-up appears the moment it is sent — and stay there while the server's transcript and run list
 * catch up with them, each at its own pace.
 */
class ConversationRepository(
    private val session: SessionManager,
    private val agents: AgentRepository,
    private val prefs: PreferencesStore,
    private val hub: LiveRunHub,
    private val attachments: AttachmentStore,
    private val cache: ConversationCache? = null,
    private val traceCache: TraceCache? = null,
    /** Prefetching only runs while the app is visible; the default lets tests and the demo skip that question. */
    private val isForeground: () -> Boolean = { true },
    private val prefetchLimit: Int = PREFETCH_LIMIT,
    private val prefetchSpacingMs: Long = PREFETCH_SPACING_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * A prompt sent from this device — the one that launched the chat, or a follow-up: its message and its run, a
     * placeholder until the server has answered, and — once the run finished while we watched — the reply the
     * transcript will carry, for the disk copy.
     */
    private data class LocalPrompt(val message: V0ConversationMessageDto, val run: RunDto, val reply: V0ConversationMessageDto? = null) {
        /** True once the server has answered with the real run; until then [run] is the placeholder named after the prompt. */
        val filed: Boolean get() = run.id != message.id
    }

    /** What the stream of the run being followed has told so far. */
    private data class LiveTrace(val runId: String, val items: List<TimelineItem>)

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
        /** Prompts sent from here that the server has not reported in full yet (see [items]). */
        var local: List<LocalPrompt> = emptyList()
        /**
         * Per finished run: its complete trace — replayed from the retained log, followed live to its `result`, or
         * read back from disk. Nothing partial belongs here — the builder lets a trace stand in for the run's
         * transcript, and [loadTraces] never asks for a run again once it is present.
         */
        var traces: Map<String, List<TimelineItem>> = emptyMap()
        /**
         * The story so far of the run [streamJob] is following, shown in place of the transcript it does not have
         * yet. The job owns it: it goes when following stops, and a snapshot from a job that is no longer the
         * follower is ignored. Left behind, it would keep standing in for a run that has since finished — hiding the
         * reply and the footer — and pass for a complete trace, so the run would never be replayed either.
         */
        var live: LiveTrace? = null
        /** True once the traces saved on disk have been folded in; the file is read at most once per entry. */
        var tracesRestored = false
        /** Runs whose finish has been folded into the inputs and written back; the screen and the hub both report it. */
        val recordedFinishes = HashSet<String>()
        var transcriptUnavailable = false
        /** The agent row's `updatedAt` the inputs correspond to; the prefetch skips rows that have not moved. */
        var inputsUpdatedAt = 0L
        /** True once this session fetched the inputs from the network (as opposed to disk). */
        var fetched = false
        /** When the inputs were last fetched; a return to the foreground moments later does not fetch them again. */
        var fetchedAt = 0L
        /** Per run: the images its prompt carried. A prompt in flight is keyed by its placeholder run. */
        var promptImages: Map<String, List<MessageAttachment>> = emptyMap()
        var streamJob: Job? = null
        var traceJob: Job? = null
        var loadJob: Job? = null
        /** The request creating this chat, from the moment its prompt is shown until the server has answered (see [launch]). */
        var launch: Deferred<Result<Launched>>? = null
        @Volatile var launching = false
        var attached = 0
        var lastUsedAt = AppClock.now()

        val hasInputs: Boolean get() = messages.isNotEmpty() || runs.isNotEmpty()
        val isIdle: Boolean get() = attached == 0 && streamJob == null && traceJob?.isActive != true && loadJob?.isActive != true && !launching

        /**
         * The server's view joined with the prompts sent from here. `/v0/agents/{id}/conversation` pairs its user
         * messages with the runs by position, oldest first, and the two endpoints catch up with a new run
         * independently: right after a launch the run list has the run while the transcript is still empty, and a
         * follow-up's prompt can be in the transcript before the run list has its run. So the transcript is laid
         * over as many runs as it has prompts for — the runs of prompts sent from here included, so a prompt the
         * transcript already lists pairs with the run only the local copy has — and every run past that is rendered
         * on its own, with the prompt sent from here when there is one, headed by the run alone when there is none
         * (an agent without a transcript, a run started elsewhere the transcript has not caught up with). Whichever
         * endpoint reports a prompt sent from here first, it shows once and never goes missing.
         *
         * Each segment has unique ids on its own; the join is made unique too, because a follow-up can briefly exist
         * on both sides (the server listed its run while the request was still in flight), and a repeated id aborts
         * the list that renders these.
         */
        fun items(): List<TimelineItem> {
            val shown = shownTraces()
            val ordered = allRuns()
            val covered = coveredCount()
            val items = TimelineBuilder.fromHistory(messages, ordered.take(covered), shown, promptImages).toMutableList()
            ordered.drop(covered).forEach { run ->
                val prompt = local.firstOrNull { it.run.id == run.id }
                items += TimelineBuilder.fromHistory(listOfNotNull(prompt?.message, prompt?.reply), listOf(run), shown, promptImages)
            }
            return items.withUniqueIds()
        }

        /** The complete traces, plus the story so far of the followed run unless it already has a complete one. */
        private fun shownTraces(): Map<String, List<TimelineItem>> {
            val current = live?.takeUnless { it.runId in traces } ?: return traces
            return traces + (current.runId to current.items)
        }

        /** Every run known, oldest first: the server's, then the runs of prompts sent from here that its list lacks — placeholders included. */
        fun allRuns(): List<RunDto> {
            val listed = runs.mapTo(HashSet()) { it.id }
            return (runs + local.map { it.run }.filter { it.id !in listed }).sortedBy { parseIsoMillis(it.createdAt) }
        }

        /** How many runs, oldest first, the server's transcript has a prompt for. */
        fun coveredCount(): Int = messages.count { it.type == USER_MESSAGE }

        /** True when this run's message is shown from a prompt sent from here rather than from the server's transcript. */
        fun standsIn(runId: String): Boolean = local.any { it.run.id == runId } && allRuns().drop(coveredCount()).any { it.id == runId }

        /** The newest run there is, counting prompts sent from here that the server's list has not caught up with (never a placeholder: there is nothing to stream for it yet). */
        fun latestRun(): RunDto? = (runs + local.filter { it.filed }.map { it.run }).maxByOrNull { parseIsoMillis(it.createdAt) }

        /**
         * What the disk keeps: the server's inputs as reported, and the prompts sent from here — those the server has
         * answered with a run; one still awaiting its answer has nothing the disk could pair it with — so the next
         * start can go on standing them in for what the server has still not caught up with.
         */
        fun toCached(): CachedConversation = CachedConversation(
            agentId = agentId,
            messages = messages,
            runs = runs,
            transcriptUnavailable = transcriptUnavailable,
            agentUpdatedAtMillis = inputsUpdatedAt,
            local = local.filter { it.filed }.map { CachedLocalPrompt(it.message, it.run, it.reply) },
        )

        /** Runs the placeholder of a prompt sent now must sort after, whatever the device clock says relative to the server's. */
        fun newestRunAt(): Long = (runs + local.map { it.run }).maxOfOrNull { parseIsoMillis(it.createdAt) } ?: 0L

        fun runById(runId: String): RunDto? = runs.firstOrNull { it.id == runId } ?: local.firstOrNull { it.run.id == runId }?.run
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
        // A run that finishes while no screen is streaming it — the notification monitor holds the stream, or the
        // screen left mid-run — still reaches the transcript and the disk, so the chat is whole when it is opened.
        scope.launch {
            hub.finishes.collect { snapshot -> runCatching { onRunFinished(snapshot) }.onFailure { if (it is CancellationException) throw it } }
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

    /**
     * Attach a screen. Loads history on first attach and keeps streaming while at least one screen is attached. A
     * chat whose launch is still in flight has nothing on the server to load: its prompt is already on screen, and the
     * launch starts the stream when the server answers.
     */
    fun attach(agentId: String) {
        val e = entry(agentId)
        synchronized(e) {
            e.attached++
            if (e.attached == 1 && !e.launching) {
                e.loadJob?.cancel()
                e.loadJob = e.scope.launch {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    load(e, agentId)
                }
            }
        }
    }

    /**
     * Detach a screen. When the last one leaves, everything in flight for it stops: the load (whose tail would
     * otherwise start following a run nobody is looking at), the replays and the live stream. The next attach loads
     * afresh, and the hub still holds the story of a run that is followed again within its grace period.
     */
    fun detach(agentId: String) {
        val e = entry(agentId)
        synchronized(e) {
            e.attached = (e.attached - 1).coerceAtLeast(0)
            if (e.attached == 0) {
                e.loadJob?.cancel()
                e.loadJob = null
                e.traceJob?.cancel()
                e.traceJob = null
                e.stopFollowing()
            }
        }
    }

    /** Drops everything known about an agent, on disk too; used when it is deleted. */
    fun forget(agentId: String) {
        synchronized(entries) { entries.remove(agentId) }?.scope?.cancel()
        diskIndex[agentId] = ABSENT
        scope.launch {
            cache?.remove(agentId)
            traceCache?.remove(agentId)
        }
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.stopFollowing()
        e.traceJob?.cancel()
        e.loadJob?.cancel()
        e.loadJob = e.scope.launch { load(e, agentId) }
    }

    /**
     * Brings an open chat back up to date after the app returns to the foreground: while it was away the network
     * may have taken the stream down mid-run, or the run may have finished. Loads the history again, which restarts
     * the stream of a run still going and replays one that ended. A load already in flight, or one that completed
     * moments ago (the first open), is left alone.
     */
    fun revalidate(agentId: String) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        synchronized(e) {
            // A chat still being launched has nothing on the server to fetch; the launch settles it when the server answers.
            if (e.attached == 0 || e.loadJob?.isActive == true || e.launching) return
            if (AppClock.now() - e.fetchedAt < REVALIDATE_MIN_INTERVAL_MS) return
            e.loadJob = e.scope.launch { load(e, agentId) }
        }
    }

    /** Rebuilds the visible items from the entry's inputs after [mutate] has changed them. */
    private inline fun Entry.publish(mutate: Entry.() -> Unit = {}, transform: ConversationState.() -> ConversationState = { this }) {
        synchronized(this) {
            mutate()
            state.update { it.copy(items = items()).transform() }
        }
    }

    /** Stops following the active run. Its story so far goes with the job (see [Entry.live]). */
    private fun Entry.stopFollowing() {
        streamJob?.cancel()
        publish(mutate = { streamJob = null; live = null }, transform = { copy(isStreaming = false, isReconnecting = false) })
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
                val stored = async { runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap()) }
                val convResult = conversation.await()
                val runResult = runPage.await()
                val onDevice = stored.await()
                // Each endpoint answers for itself: what one of them could not read is left as it was rather than
                // reported as absent, so a timeout on the run list does not strip the footers and traces off an
                // otherwise healthy transcript — nor write that shape to disk.
                val transcript = convResult.getOrNull()?.messages
                val fetchedRuns = runResult.getOrNull()
                val transcriptFailed = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                val fetched = convResult.isSuccess || fetchedRuns?.isNotEmpty() == true
                // The newest run once the inputs are merged: usually the server's, but a follow-up sent from here
                // that the list has not caught up with yet is newer, and a reload must keep following it rather
                // than declare the previous run the active one.
                var latest: RunDto? = null
                var merged: List<RunDto> = emptyList()
                var unavailable = false
                if (fetched) {
                    // The traces are kept: they are complete, and a finished run's log does not change. Local prompts
                    // hand over to the server once it reports them in full.
                    e.publish(
                        mutate = {
                            transcript?.let { messages = it }
                            fetchedRuns?.let { runs = it }
                            unavailable = transcriptFailed && messages.isEmpty()
                            this.transcriptUnavailable = unavailable
                            inputsUpdatedAt = agents.agent(agentId)?.updatedAtMillis ?: 0L
                            pruneLocal()
                            // The disk knows every filed prompt; only prompts still in flight exist solely in memory.
                            promptImages = onDevice + promptImages.filterKeys { key -> local.any { it.run.id == key } }
                            this.fetched = true
                            fetchedAt = AppClock.now()
                            latest = latestRun()
                            merged = runs
                        },
                        transform = {
                            copy(
                                isLoading = false,
                                error = null,
                                activeRunId = latest?.id,
                                runStatus = latest?.statusEnum(),
                                isStreaming = false,
                                isReconnecting = false,
                                transcriptUnavailable = unavailable,
                            )
                        },
                    )
                } else {
                    e.reportLoadFailure(convResult.exceptionOrNull())
                }
                // The latest run is the row's execution state (a turn that ended in an error is only visible here),
                // so the sidebar reflects it right away. The full agent record then enriches the row (repo, PR,
                // duration); it never holds up the transcript, and reuses this run, saving a round-trip.
                latest?.let { run -> agents.patch(agentId) { it.withLatestRun(run) } }
                launch { agents.loadDetail(agentId, latest) }
                if (fetched) {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    persist(e, backend)
                    val active = latest?.takeIf { it.statusEnum().isActive }
                    if (active != null) {
                        startStreaming(e, agentId, active)
                    } else {
                        // The run being followed is over by the server's account: what its stream told before the
                        // connection dropped, or the outcome read from the run record, must not stand in for it any
                        // longer — the transcript has the reply now, and the replay below brings the whole trace.
                        val followed = synchronized(e) { e.live?.runId }
                        if (followed != null && merged.any { it.id == followed && it.statusEnum().isTerminal }) e.stopFollowing()
                    }
                    loadTraces(e, agentId, merged.filter { it.statusEnum().isTerminal })
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            e.reportLoadFailure(t)
        }
    }

    /**
     * Nothing came back from the server. That is the chat's error when there is nothing else to show; with a prompt
     * sent from here on screen it is a revalidation that did not work out, and the next one will.
     */
    private fun Entry.reportLoadFailure(cause: Throwable?) {
        val standingIn = synchronized(this) { local.isNotEmpty() }
        state.update { it.copy(isLoading = false, error = if (standingIn) it.error else cause?.userMessage()) }
    }

    /**
     * Drops the local prompts the server now reports in full — the run listed and, going by position, its message in
     * the transcript. Only ever applied to the server's answer: the disk copy is these prompts' own flattened form and
     * proves nothing about what the server knows.
     */
    private fun Entry.pruneLocal() {
        if (local.isEmpty()) return
        val ordered = runs.sortedBy { parseIsoMillis(it.createdAt) }
        val covered = messages.count { it.type == USER_MESSAGE }
        local = local.filter { prompt -> ordered.indexOfFirst { it.id == prompt.run.id }.let { it < 0 || it >= covered } }
    }

    /** Shows the transcript and traces saved by an earlier visit, if any, while the network answers. */
    private suspend fun restoreFromCache(e: Entry, agentId: String) {
        val cached = readCache(agentId)
        val savedTraces = if (e.tracesRestored) emptyMap() else readTraces(agentId)
        if (cached == null && savedTraces.isEmpty()) {
            e.publish(mutate = { tracesRestored = true })
            return
        }
        if (e.hasInputs || e.fetched) return
        val latest = cached?.let { it.runs + it.local.map { saved -> saved.run } }?.maxByOrNull { parseIsoMillis(it.createdAt) }
        // A run that was still active when the cache was written has very likely finished since; the live state
        // ("Working…", Stop) waits for the network unless a fetched agent row confirms the run is still going.
        val list = agents.state.value
        val row = list.agents.firstOrNull { it.id == agentId }
        val status = latest?.statusEnum()?.takeUnless { it.isActive && (list.isFromCache || row?.isRunning != true) }
        // The prompts' images live on this device only, so the saved transcript can show them right away too.
        val onDevice = runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap())
        e.publish(
            mutate = {
                if (cached != null) {
                    messages = cached.messages
                    runs = cached.runs
                    transcriptUnavailable = cached.transcriptUnavailable
                    inputsUpdatedAt = cached.agentUpdatedAtMillis
                    // The prompts sent from here go on standing in, exactly as they did when the copy was written.
                    local = local + cached.local.filter { saved -> local.none { it.run.id == saved.run.id } }.map { LocalPrompt(it.message, it.run, it.reply) }
                }
                if (promptImages.isEmpty()) promptImages = onDevice
                foldSavedTraces(savedTraces)
            },
            transform = {
                if (cached != null) copy(activeRunId = latest?.id, runStatus = status, transcriptUnavailable = cached.transcriptUnavailable) else this
            },
        )
    }

    /** Traces already in memory tell the same story as the disk; the disk fills in the runs memory lacks. */
    private fun Entry.foldSavedTraces(saved: Map<String, CachedTrace>) {
        tracesRestored = true
        if (saved.isNotEmpty()) traces = saved.mapValues { it.value.items } + traces
    }

    private suspend fun readCache(agentId: String): CachedConversation? {
        val store = cache?.takeIf { !session.isDemo } ?: return null
        val cached = store.read(agentId)?.value
        diskIndex[agentId] = cached?.agentUpdatedAtMillis ?: ABSENT
        return cached
    }

    private suspend fun readTraces(agentId: String): Map<String, CachedTrace> {
        val store = traceCache?.takeIf { !session.isDemo } ?: return emptyMap()
        return runCatching { store.read(agentId) }.getOrDefault(emptyMap())
    }

    private suspend fun persist(e: Entry, backend: CursorBackend) {
        val store = cache ?: return
        if (backend.isDemo) return
        val snapshot = synchronized(e) { if (e.hasInputs || e.local.isNotEmpty()) e.toCached() else null } ?: return
        store.write(snapshot)
        diskIndex[snapshot.agentId] = snapshot.agentUpdatedAtMillis
    }

    private suspend fun writeTrace(agentId: String, runId: String, createdAtMillis: Long, items: List<TimelineItem>) {
        val store = traceCache?.takeIf { !session.isDemo } ?: return
        runCatching { store.put(agentId, listOf(CachedTrace(runId, createdAtMillis, items))) }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Completes the traces of finished runs. The disk is consulted first (once per entry: an entry the prefetch
     * created never went through [restoreFromCache]); what it lacks is replayed from the retained stream, newest
     * first: the retention window is time-based, so once one run's log has expired every older run's has too and
     * the rest are skipped. A few workers pull from the ordered queue, so the newest runs are always the first ones
     * asked. Every trace that lands is written back, so it is asked for exactly once in the life of the install.
     */
    private fun loadTraces(e: Entry, agentId: String, finishedRuns: List<RunDto>) {
        e.traceJob?.cancel()
        e.traceJob = e.scope.launch {
            if (!e.tracesRestored) {
                val saved = readTraces(agentId)
                e.publish(mutate = { foldSavedTraces(saved) })
            }
            val pending = synchronized(e) { finishedRuns.filter { it.id !in e.traces } }.sortedByDescending { parseIsoMillis(it.createdAt) }
            if (pending.isEmpty()) return@launch
            // A log the hub already saw expire (an earlier pass, a reload) settles every older run before any worker
            // starts, so those are never asked again — the answer would depend on which worker happened to finish first.
            val knownExpired = pending.filter { hub.current(agentId, it.id)?.expired == true }.maxOfOrNull { parseIsoMillis(it.createdAt) } ?: Long.MIN_VALUE
            val next = AtomicInteger(0)
            val newestExpired = AtomicLong(knownExpired)
            repeat(minOf(MAX_PARALLEL_REPLAYS, pending.size)) {
                launch {
                    while (true) {
                        val run = pending.getOrNull(next.getAndIncrement()) ?: return@launch
                        val createdAt = parseIsoMillis(run.createdAt)
                        if (createdAt < newestExpired.get()) continue
                        val snapshot = hub.replay(agentId, run.id, createdAt.takeIf { it > 0 })
                        when {
                            snapshot.hasTrace -> settleTrace(e, run, snapshot.items)
                            snapshot.expired -> newestExpired.updateAndGet { maxOf(it, createdAt) }
                        }
                    }
                }
            }
        }
    }

    /** Files a run's complete trace and keeps it on disk. */
    private suspend fun settleTrace(e: Entry, run: RunDto, items: List<TimelineItem>) {
        e.publish(mutate = { traces = traces + (run.id to items) })
        writeTrace(e.agentId, run.id, parseIsoMillis(run.createdAt), items)
    }

    private fun startStreaming(e: Entry, agentId: String, run: RunDto) {
        e.streamJob?.cancel()
        // Started only once it is the entry's follower, so not even its first snapshot can be taken for a stale one.
        val job = e.scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            val startedAt = parseIsoMillis(run.createdAt).takeIf { it > 0 }
            hub.snapshots(agentId, run.id, startedAt)
                .transformWhile { snapshot -> emit(snapshot); !snapshot.finished }
                .collect { snapshot ->
                    if (!e.applyLive(self, run, snapshot)) return@collect
                    if (snapshot.finished) {
                        // The screen is open, so the finished turn counts as read. finishedAtMillis is the updatedAt
                        // the hub writes to the agent row, whether or not that patch has landed yet.
                        val finishedAt = snapshot.finishedAtMillis ?: AppClock.now()
                        prefs.markRead(agentId, finishedAt)
                        recordFinish(e, run, snapshot, finishedAt)
                        // An outcome read from the run record after the stream broke lacks whatever happened in
                        // between; now that the run is over, its retained log is read whole.
                        if (!snapshot.hasTrace && !snapshot.expired) loadTraces(e, agentId, listOf(run))
                    }
                }
        }
        val following = synchronized(e) {
            // The last screen may have left while the load that got here was wrapping up: then nobody is looking,
            // and the next attach decides afresh.
            if (e.attached == 0) return@synchronized false
            e.publish(mutate = { streamJob = job; live = null }, transform = { copy(activeRunId = run.id, runStatus = RunStatus.parse(run.status), isStreaming = true) })
            true
        }
        if (following) job.start() else job.cancel()
    }

    /**
     * Applies a snapshot of the run [follower] is streaming: the story so far while it runs, and once it has
     * finished on its own `result` the complete trace, filed with the replayed ones (the builder places either after
     * the run's prompt). An outcome read from the run record after the stream broke stays the story so far: it has
     * the final reply and the footer to show, but not everything in between, so it never passes for the trace. A
     * snapshot from a job that is no longer the follower — its cancel raced the emission — is dropped (false).
     *
     * Until the stream has said anything, the run record's status stands rather than the snapshot's default: a fresh
     * run is still CREATING ("Starting…") while its VM boots, not RUNNING because a connection was opened. A snapshot
     * taken between two connections marks the state as reconnecting.
     */
    private fun Entry.applyLive(follower: Job?, run: RunDto, snapshot: LiveRunHub.Snapshot): Boolean = synchronized(this) {
        if (streamJob !== follower) return false
        publish(
            mutate = {
                if (snapshot.hasTrace) {
                    traces = traces + (run.id to snapshot.items)
                    live = null
                } else {
                    live = LiveTrace(run.id, snapshot.items)
                }
            },
            transform = {
                copy(
                    runStatus = if (snapshot.eventCount > 0 || snapshot.finished) snapshot.status else runStatus,
                    isStreaming = !snapshot.finished,
                    isReconnecting = snapshot.reconnecting && !snapshot.finished,
                )
            },
        )
        true
    }

    /**
     * A run the hub followed live has ended. Whether or not a screen is (still) streaming it, its outcome is folded
     * into the transcript and its trace — when the stream told the whole story — is kept, so the chat is complete
     * the next time it is opened, from disk if need be.
     */
    private suspend fun onRunFinished(snapshot: LiveRunHub.Snapshot) {
        val e = synchronized(entries) { entries[snapshot.agentId] }
        val run = e?.let { synchronized(it) { it.runById(snapshot.runId) } }
        when {
            e != null && run != null -> recordFinish(e, run, snapshot, snapshot.finishedAtMillis ?: AppClock.now())
            snapshot.hasTrace -> {
                // Nothing in memory places this run yet (never opened here, or started elsewhere): the trace is kept
                // on its own, and the next load fetches the history it belongs to.
                e?.publish(mutate = { traces = traces + (snapshot.runId to snapshot.items) })
                writeTrace(snapshot.agentId, snapshot.runId, snapshot.startedAtMillis, snapshot.items)
            }
        }
    }

    /**
     * Records a run that finished while this device was connected: the outcome goes into the inputs and, when the
     * stream told the whole story, the trace is filed and kept on disk. The screen's own follower and the hub's
     * report both arrive here for the same run; it is recorded once.
     */
    private suspend fun recordFinish(e: Entry, run: RunDto, snapshot: LiveRunHub.Snapshot, finishedAt: Long) {
        val first = synchronized(e) { e.recordedFinishes.add(run.id) }
        if (!first) return
        e.publish(
            mutate = {
                recordFinishedRun(run, snapshot, finishedAt)
                if (snapshot.hasTrace) traces = traces + (run.id to snapshot.items)
            },
        )
        persist(e, session.current)
        if (snapshot.hasTrace) writeTrace(e.agentId, run.id, parseIsoMillis(run.createdAt, snapshot.startedAtMillis), snapshot.items)
    }

    /**
     * Folds a run that finished while we watched into the inputs, the way the server will report it: the run itself
     * as terminal and its final reply as an assistant message. The disk copy renders it on the next open before the
     * hub has replayed the trace; in memory the trace stands in for the text, so nothing shows twice. The reply goes
     * wherever this run's message is shown from — the local prompt while it stands in, else the transcript.
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
        val reply = text.takeIf { it.isNotEmpty() }?.let { V0ConversationMessageDto("res-${run.id}", ASSISTANT_MESSAGE, it) }
        runs = runs.map { if (it.id == run.id) terminal else it }
        local = local.map { if (it.run.id == run.id) it.copy(run = terminal, reply = reply) else it }
        if (!standsIn(run.id) && reply != null && messages.none { it.id == reply.id }) messages = messages + reply
        inputsUpdatedAt = maxOf(inputsUpdatedAt, finishedAt)
    }

    /**
     * Files the follow-up and starts streaming its run. [modelId] (with [modelParams] and the [modelDisplayName] it is
     * shown under) switches the chat to another model from this run on; [planMode] asks for plan or agent mode
     * explicitly. Both null keep the chat as it is — see [AgentRepository.followUp].
     */
    suspend fun sendFollowUp(
        agentId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
        mcpServers: List<McpServer> = emptyList(),
        planMode: Boolean? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
    ): Result<Unit> {
        val e = entry(agentId)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val now = AppClock.now()
        // The prompt shows up right away, paired with a placeholder run so it is built like every other turn — ordered by
        // the run's stamp, its images keyed by the run — until the server's run takes its place.
        val localId = "$LOCAL_RUN_PREFIX$now"
        val placeholder = e.placeholderRun(localId, now)
        // Written before the request so the bubble shows its images from the first frame, like the text. Storage
        // trouble costs the previews, never the send.
        val staged = runCatching { attachments.stage(images) }.getOrDefault(StagedAttachments.EMPTY)
        e.publish(
            mutate = {
                local = local + LocalPrompt(V0ConversationMessageDto(localId, USER_MESSAGE, trimmed), placeholder)
                if (staged.attachments.isNotEmpty()) promptImages = promptImages + (localId to staged.attachments)
            },
            transform = { copy(error = null) },
        )

        val result = agents.followUp(
            agentId,
            trimmed,
            images,
            planMode = planMode,
            mcpServers = mcpServers,
            modelId = modelId,
            modelParams = modelParams,
            modelDisplayName = modelDisplayName,
        )
        return result.fold(
            onSuccess = { run ->
                // Filed under the run so the next history load finds them; the bubble follows the files to their new paths.
                val kept = runCatching { attachments.commit(agentId, run.id, staged) }.getOrDefault(staged.attachments)
                e.publish(
                    mutate = {
                        // A reload that raced the request may already list this run. The local copy stays all the
                        // same: [Entry.items] shows the server's copy of the turn once the transcript has it, and ours
                        // for as long as only the run list does; the next load prunes it once both have caught up.
                        local = local.map { if (it.run.id == localId) it.copy(run = run) else it }
                        promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (run.id to kept) }
                        inputsUpdatedAt = maxOf(inputsUpdatedAt, now)
                    },
                )
                startStreaming(e, agentId, run)
                persist(e, session.current)
                Result.success(Unit)
            },
            onFailure = { t ->
                attachments.discard(staged)
                e.publish(
                    mutate = {
                        local = local.filterNot { it.run.id == localId }
                        promptImages = promptImages - localId
                    },
                    transform = { copy(error = t.userMessage()) },
                )
                Result.failure(t)
            },
        )
    }

    /**
     * Launches a new chat around its prompt, so the composer can open it without waiting for the server. The message
     * is published at once — paired with a placeholder `CREATING` run, so the chat reads "Starting…", its images staged
     * like a follow-up's — and its row is put in the agent list; the request goes out and [onStaged] runs, which is
     * where the composer navigates to the chat. The server's reply swaps in the run it created and,
     * while a screen is attached, starts its stream. A failure takes the prompt and the row down again and is
     * returned. Stopping the chat before the server has answered ([cancelLaunch]) fails the launch with
     * [LaunchCancelledException]; cancelling the caller abandons the request itself — which is why the caller is the
     * [ChatLauncher], whose scope no screen owns, and not the composer.
     *
     * Requires the client-minted [LaunchRequest.agentId] the composer always sends: it is what the chat is shown under
     * before the server has named it, and what lets a retry adopt an agent the first attempt already created.
     */
    suspend fun launch(request: LaunchRequest, modelDisplayName: String?, onStaged: () -> Unit = {}): Result<Launched> {
        val agentId = requireNotNull(request.agentId) { "A launch needs a client-minted agent id to show the chat under." }
        val e = entry(agentId)
        synchronized(e) {
            if (e.launching) return Result.failure(IllegalStateException("This chat is already being started."))
            e.launching = true
        }
        val now = AppClock.now()
        val localId = "$LOCAL_RUN_PREFIX$now"
        val placeholder = e.placeholderRun(localId, now)
        // Staged before anything is shown, so the bubble has its images from its first frame. Storage trouble costs
        // the previews, never the launch.
        val staged = runCatching { attachments.stage(request.images) }.getOrDefault(StagedAttachments.EMPTY)
        agents.beginLaunch(request, modelDisplayName)
        e.publish(
            mutate = {
                local = local + LocalPrompt(V0ConversationMessageDto(localId, USER_MESSAGE, request.prompt.trim()), placeholder)
                if (staged.attachments.isNotEmpty()) promptImages = promptImages + (localId to staged.attachments)
            },
            transform = { copy(isLoading = false, error = null, activeRunId = null, runStatus = RunStatus.CREATING, isStreaming = false, transcriptUnavailable = false) },
        )
        // The request runs in the entry's scope so a Stop from the chat can cancel it without the launcher's
        // coroutine being involved; the launcher's own cancellation takes the request down with it. It is on the
        // entry before the chat opens, so a Stop tapped the instant the chat is on screen still finds it.
        val inFlight = e.scope.async { agents.launch(request, modelDisplayName, saveImages = false) }
        synchronized(e) { e.launch = inFlight }
        onStaged()

        val result: Result<Launched> = try {
            inFlight.await()
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) {
                Result.failure(LaunchCancelledException())
            } else {
                inFlight.cancel()
                Result.failure(cancelled)
            }
        }
        // Whatever happened, the chat must be left consistent — including when the launcher is already cancelled.
        withContext(NonCancellable) {
            result.fold(
                onSuccess = { launched -> settleLaunch(e, launched, localId, staged, now) },
                onFailure = { rollbackLaunch(e, localId, staged) },
            )
            synchronized(e) {
                e.launch = null
                e.launching = false
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    /** The server created the chat: its run takes the placeholder's place, the images are filed under it, and its stream starts. */
    private suspend fun settleLaunch(e: Entry, launched: Launched, localId: String, staged: StagedAttachments, sentAt: Long) {
        val run = launched.run
        if (run == null) {
            // A retry adopted an agent whose run could not be read: the server owns the transcript from here on.
            attachments.discard(staged)
            e.publish(mutate = { local = local.filterNot { it.run.id == localId }; promptImages = promptImages - localId })
            if (e.attached > 0) reload(e.agentId)
            return
        }
        val kept = runCatching { attachments.commit(e.agentId, run.id, staged) }.getOrDefault(staged.attachments)
        e.publish(
            mutate = {
                local = local.map { if (it.run.id == localId) it.copy(run = run) else it }
                promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (run.id to kept) }
                inputsUpdatedAt = maxOf(inputsUpdatedAt, sentAt)
            },
            transform = { copy(activeRunId = run.id, runStatus = run.statusEnum()) },
        )
        // With nobody looking, the stream waits for the next attach, whose load starts it like any active run's.
        if (e.attached > 0 && run.statusEnum().isActive) startStreaming(e, e.agentId, run)
        persist(e, session.current)
    }

    /** The chat was not created: nothing of it stays, on screen or on disk. */
    private suspend fun rollbackLaunch(e: Entry, localId: String, staged: StagedAttachments) {
        attachments.discard(staged)
        e.publish(
            mutate = {
                local = local.filterNot { it.run.id == localId }
                promptImages = promptImages - localId
            },
            transform = { ConversationState(agentId) },
        )
        agents.discardLaunch(e.agentId)
    }

    suspend fun cancelActiveRun(agentId: String): Result<Unit> {
        val e = entry(agentId)
        // A chat the server has not answered for yet has no run to cancel; stopping it gives the launch up instead.
        if (cancelLaunch(agentId)) return Result.success(Unit)
        val runId = e.state.value.activeRunId ?: return Result.failure(IllegalStateException("No active run."))
        return agents.cancelRun(agentId, runId).onSuccess {
            e.state.update { it.copy(runStatus = RunStatus.CANCELLED) }
        }
    }

    /**
     * Abandons a [launch] the server has not answered yet, from any screen; the launcher gets a
     * [LaunchCancelledException] and the prompt comes down. False when there is nothing in flight for this chat.
     */
    fun cancelLaunch(agentId: String): Boolean {
        val e = synchronized(entries) { entries[agentId] } ?: return false
        val inFlight = synchronized(e) { e.launch?.takeIf { it.isActive } } ?: return false
        inFlight.cancel()
        return true
    }

    /**
     * The run a prompt sent now is paired with until the server has answered. Stamped now, but never before the newest
     * run already known: the transcript is ordered by these stamps, and a device clock behind the server's must not
     * file a new prompt under an older turn.
     */
    private fun Entry.placeholderRun(id: String, now: Long): RunDto {
        val at = Instant.ofEpochMilli(maxOf(now, synchronized(this) { newestRunAt() } + 1)).toString()
        return RunDto(id = id, agentId = agentId, status = RunStatus.CREATING.name, createdAt = at, updatedAt = at)
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
                    val latest = runList.maxByOrNull { parseIsoMillis(it.createdAt) }
                    // The list only said the agent went idle; the run says how the turn ended (an error, say), and the
                    // row is the one place the sidebar learns that from without the chat being opened.
                    latest?.let { run -> agents.patch(agent.id) { it.withLatestRun(run) } }
                    val e = entry(agent.id)
                    // A screen that opened it in the meantime owns the entry now.
                    if (e.attached == 0 && e.streamJob == null) {
                        e.publish(
                            mutate = {
                                messages = transcript
                                this.runs = runList
                                transcriptUnavailable = false
                                inputsUpdatedAt = agent.updatedAtMillis
                                pruneLocal()
                                fetched = true
                            },
                            transform = { copy(isLoading = false, activeRunId = latest?.id, runStatus = latest?.statusEnum()) },
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
        /** A chat opened this recently is not fetched again when the app comes to the foreground. */
        const val REVALIDATE_MIN_INTERVAL_MS = 5_000L
        /** The two message types of `/v0/agents/{id}/conversation`. */
        const val USER_MESSAGE = "user_message"
        const val ASSISTANT_MESSAGE = "assistant_message"
        /** Ids of the runs local prompts are paired with until the server has answered (the same id as their message, see [LocalPrompt.filed]). */
        const val LOCAL_RUN_PREFIX = "local-"
    }
}
