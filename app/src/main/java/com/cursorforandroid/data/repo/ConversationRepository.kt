package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConversationRecordApi
import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
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
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.CoordinatorLineage
import com.cursorforandroid.domain.CoordinatorTranscript
import com.cursorforandroid.domain.GoalTranscript
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ProjectDiagnostics
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunOrder
import com.cursorforandroid.domain.TranscriptLoadDiagnostics
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    /**
     * The chat has turns older than the ones shown. [items] render the newest window of runs; the ones before it
     * are built (and their traces read or replayed) when the reader scrolls up to them (see [ConversationRepository.loadOlder]).
     */
    val hasOlder: Boolean = false,
    /** Older turns are being paged in right now: their run records fetched, or their traces read. */
    val isLoadingOlder: Boolean = false,
    /**
     * The account's own record says the chat runs in Project mode (`agent_mode = AGENT_MODE_PROJECT` on a prompt of
     * its transcript, Extended mode): a coordinator's chat, whatever the list calls it and whatever its tools show.
     */
    val isProjectConversation: Boolean = false,
    /**
     * The last fetch of `/v0/agents/{id}/conversation` failed (a timeout on a transcript of hundreds of turns, a
     * server error) while the run list answered: what is shown is the transcript last read, or none. Null once a
     * fetch has gone through. Shown on the screen rather than swallowed.
     */
    val transcriptError: String? = null,
    /** Where the traces of the turns shown stand: how many are on screen, still coming, gone for good, or failed. */
    val traceStatus: TraceStatus = TraceStatus(),
)

/**
 * Of the finished runs the window shows, how many have their trace (thoughts, tool calls, payloads) on screen and
 * why the others do not: [pending] are being read or replayed, [expired] have no log left on the server (and no
 * copy here), [failed] could not be read this time (the network) and will be asked for again on the next fetch.
 */
data class TraceStatus(
    val shown: Int = 0,
    val pending: Int = 0,
    val expired: Int = 0,
    val failed: Int = 0,
) {
    val missing: Int get() = expired + failed
}

/** The failure of a [ConversationRepository.launch] that was stopped from the chat before the server had answered. */
class LaunchCancelledException : RuntimeException("The chat was stopped before it started.")

/** A follow-up shown in the transcript ahead of its request; see [ConversationRepository.stageFollowUp]. */
class StagedFollowUp internal constructor(
    internal val localId: String,
    val text: String,
    internal val attachments: StagedAttachments,
    internal val stagedAt: Long,
)

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
 * A chat is opened on its newest turns. The first page of the run list — read as the chat's newest runs whichever
 * order the server lists them in, anchored on the agent's latest run (see [newestRuns]) — and the transcript's text
 * are enough to render the newest [WINDOW_RUNS] runs; the rest of the run records are paged in behind the cursor so
 * every prompt is paired with its own run, and the older turns — their items, and the traces behind them — are
 * built only when the reader scrolls up to them ([loadOlder]), a window at a time. The disk keeps the window that
 * was open, so the next start renders it before the network answers. What the load could not bring — a transcript
 * that timed out, turns whose logs are gone or could not be read — is said on the screen ([ConversationState.transcriptError],
 * [ConversationState.traceStatus]) and in the diagnostics export ([loadDiagnostics]), never swallowed.
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
    /**
     * The first screen to show this chat. The finished notification for it can go: the user is reading the reply
     * here. Default is nothing, so repository tests do not need a shade.
     */
    private val onOpened: (agentId: String) -> Unit = {},
    private val prefetchLimit: Int = PREFETCH_LIMIT,
    private val prefetchSpacingMs: Long = PREFETCH_SPACING_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Where one agent's own work runs; a seam for tests that need to hold it up and see what the caller did meanwhile. */
    private val entryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The account's own transcript of a chat (Extended mode), for the turns whose documented log has expired and
     * which the disk never held; null (or the capability off) leaves those turns to their text.
     */
    private val record: ConversationRecordApi? = null,
    private val capabilities: suspend () -> Capabilities = { Capabilities.DOCUMENTED },
    /** Where the images the account's transcript carries are kept on the device, like the stream's (see [LiveRunHub]). */
    private val images: GeneratedImageStore? = null,
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
     * A built timeline with nothing yet from the run being followed, and the inputs it was built from. Every
     * mutation replaces the collection it changes, so identity is all it takes to tell whether the build still
     * stands — which it does for the whole of a run, however many events it streams.
     */
    private class Prefix(entry: Entry, private val liveRunId: String, val items: List<TimelineItem>) {
        private val messages = entry.messages
        private val runs = entry.runs
        private val local = entry.local
        private val traces = entry.traces
        private val promptImages = entry.promptImages
        private val window = entry.window
        private val runsComplete = entry.runsComplete
        val ids: Set<String> = items.mapTo(HashSet(items.size)) { it.id }

        fun matches(entry: Entry, runId: String): Boolean = liveRunId == runId &&
            messages === entry.messages && runs === entry.runs && local === entry.local &&
            traces === entry.traces && promptImages === entry.promptImages &&
            window == entry.window && runsComplete == entry.runsComplete
    }

    /**
     * How the transcript's messages line up with the runs shown. `/v0/agents/{id}/conversation` pairs its user
     * messages with the runs by position, oldest first; the window renders the newest runs, so the messages shown
     * start at the prompt of the first of them.
     */
    private class Layout(
        /** The transcript's messages shown: from the prompt of the oldest run in the window on. */
        val messages: List<V0ConversationMessageDto>,
        /** The runs those messages pair with by position, oldest first. */
        val paired: List<RunDto>,
        /** The runs shown after them — runs the transcript has no prompt for — each with the prompt sent from here when there is one. */
        val standing: List<RunDto>,
        /** Runs (and their prompts) older than the window: what [ConversationRepository.loadOlder] would bring. */
        val olderCount: Int,
        /** How many turns the chat has, runs in hand or not (see `Entry.chatTotal`). */
        val total: Int,
        /** How many of the window's prompts come before the first run in hand: they render without a run. */
        val runOffset: Int = 0,
    )

    /**
     * Everything shown for one agent is derived from a few inputs, so a trace that lands, a follow-up that is sent
     * or a live snapshot that arrives all rebuild the same way. Mutations happen under the entry's monitor.
     */
    private inner class Entry(val agentId: String) {
        val scope = CoroutineScope(SupervisorJob() + entryDispatcher)
        val state = MutableStateFlow(ConversationState(agentId))
        /** The legacy transcript as the server returned it (or as the disk remembered it). */
        var messages: List<V0ConversationMessageDto> = emptyList()
        /**
         * The v1 runs as the server returned them (or as the disk remembered them): the newest ones first of all, and
         * the older ones behind them as far as the list has been paged (see [runsComplete]).
         */
        var runs: List<RunDto> = emptyList()
        /** True once the run list has been read to its end (or as far as it is ever read): [runs] are all there are. */
        var runsComplete = true
        /** How the server listed the runs on the last fetch (see [newestRuns]); null before one. For the diagnostics. */
        var runOrder: RunOrder? = null
        /** The agent's latest run was missing from the list's first page and fetched by id on the last fetch. */
        var latestFetchedById = false
        /** The last `/v0` transcript fetch failed while the runs answered (see [ConversationState.transcriptError]). */
        var transcriptError: String? = null
        /**
         * Finished runs whose log could not be read on the last pass — the network, not the log's age — and are worth
         * asking for again (see [ConversationRepository.retryTraces]); cleared as each is queued again.
         */
        val failedTraces = HashSet<String>()
        /** Where the next page of older run records starts; null when there is none to fetch. */
        var olderRunsCursor: String? = null
        /** How many of the newest runs are rendered; grows by [WINDOW_RUNS] each time the reader asks for older turns. */
        var window = WINDOW_RUNS
        /** Older turns are being paged in (see [ConversationRepository.loadOlder]). */
        var loadingOlder = false
        /** Pages the rest of the run records behind the first one, so every prompt pairs with its run. */
        var runPagingJob: Job? = null
        /** Prompts sent from here that the server has not reported in full yet (see [items]). */
        var local: List<LocalPrompt> = emptyList()
        /**
         * Per finished run: its complete trace — replayed from the retained log, followed live to its `result`, or
         * read back from disk. Nothing partial belongs here — the builder lets a trace stand in for the run's
         * transcript, and [loadTraces] never asks for a run again once it is present.
         */
        var traces: Map<String, List<TimelineItem>> = emptyMap()
        /**
         * Runs whose trace in [traces] came off the disk from a build that did not read the coordinator's message
         * tool or the goal tools, so its message calls have no body and its goal no objective (see
         * [CoordinatorTranscript.needsRefresh], [GoalTranscript.needsRefresh]). Shown as they are —
         * re-read on the way to the screen — and asked for again like a run without a trace: the replay, or the
         * account's record, brings the bodies and replaces the file. Cleared as each lands.
         */
        val staleTraces = HashSet<String>()
        /** The account's record said a prompt of this chat was sent in Project mode (see [ConversationState.isProjectConversation]). */
        var projectMode = false
        /**
         * The story so far of the run [streamJob] is following, shown in place of the transcript it does not have
         * yet. The job owns it: it goes when following stops, and a snapshot from a job that is no longer the
         * follower is ignored. Left behind, it would keep standing in for a run that has since finished — hiding the
         * reply and the footer — and pass for a complete trace, so the run would never be replayed either.
         */
        var live: LiveTrace? = null
        /**
         * Finished runs whose traces are wanted and not yet in memory, by run id, in the order they were asked for.
         * One worker ([traceJob]) drains it, disk first, then the retained logs; asking for more runs adds to it
         * rather than replacing the pass under way, so a replay of forty runs is never cut short by the forty-first.
         */
        val traceQueue = LinkedHashMap<String, RunDto>()
        /** The runs the worker has taken off the queue and not settled yet; back on it if the pass is cut short. */
        val traceInFlight = LinkedHashMap<String, RunDto>()
        /**
         * Runs whose retained log is gone (`410 stream_expired`) or older than one that is: never asked for again this
         * session. The retention window is time-based, so once one run's log has expired every older run's has too.
         */
        var expiredBefore = Long.MIN_VALUE
        val expiredRuns = HashSet<String>()
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
        /**
         * Nothing can see the chat although a screen is still attached (see [pause]). A load that was already in
         * flight when this went up finishes — its inputs are worth having — but must not open a stream or replay a
         * log behind a screen nobody is looking at.
         */
        @Volatile var paused = false
        /**
         * Bumped by every [pause] and [detach]. [resume] and [revalidate] hand their work to [scope] rather than
         * doing it under the entry's monitor, so the screen can have stopped again by the time it runs; the count
         * taken when they were called is what tells them the entry has moved on since.
         */
        @Volatile var stops = 0
        /**
         * The run this device's [cancelRun] stopped. A load that was in flight when the cancel went out read the
         * run's record before it, and starting to follow the run is no word from the run: neither may put the chat
         * back to running for it (see [statusOf]) — which is what held a steer sent the moment a chat opened until
         * the stream ended. The run's own events, and a terminal status from anywhere, still have the last word.
         */
        @Volatile var cancelledRunId: String? = null

        /** The status a run record gives the chat: an active status for the run this device cancelled stays cancelled. */
        fun statusOf(run: RunDto): RunStatus {
            val status = run.statusEnum()
            return if (status.isActive && run.id == cancelledRunId) RunStatus.CANCELLED else status
        }
        /** The workers last reported to the agent list from this transcript (see [coordinatorLineage]); null before any. */
        var reportedWorkers: Set<String>? = null
        var lastUsedAt = AppClock.now()
        private var builtPrefix: Prefix? = null
        private var orderedFromRuns: List<RunDto>? = null
        private var orderedFromLocal: List<LocalPrompt>? = null
        private var orderedRuns: List<RunDto> = emptyList()

        val hasInputs: Boolean get() = messages.isNotEmpty() || runs.isNotEmpty()
        val isIdle: Boolean get() = attached == 0 && streamJob == null && traceJob?.isActive != true && loadJob?.isActive != true && runPagingJob?.isActive != true && !launching

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
         * Only the newest [window] runs are rendered, with the messages from the first of them on (see [layout]);
         * everything older waits for the reader to scroll up to it.
         *
         * Each segment has unique ids on its own; the join is made unique too, because a follow-up can briefly exist
         * on both sides (the server listed its run while the request was still in flight), and a repeated id aborts
         * the list that renders these.
         */
        fun items(): List<TimelineItem> {
            val current = live
            val layout = layout()
            // A followed run is always the newest one there is, so it sorts last and everything the transcript
            // renders before it is untouched by an event. That part is built once and kept until an input actually
            // changes; a streamed event only re-appends the run's own items.
            val tail = current?.takeIf { layout.standing.lastOrNull()?.id == it.runId && it.runId !in traces }
                ?: return build(shownTraces(), layout)
            val prefix = builtPrefix?.takeIf { it.matches(this, tail.runId) } ?: buildPrefix(tail.runId, layout)
            return prefix.items + tail.items.withUniqueIds(prefix.ids)
        }

        /**
         * Where the window sits over the chat. The chat has as many runs as the list holds once it is complete;
         * until then as many as the transcript has prompts for (every prompt starts a run), the runs not fetched yet
         * being its oldest. Run `r`, counted oldest first over the whole chat, pairs with the `r`th prompt; the runs
         * past the prompts stand on their own. The window is the newest [window] runs, and the messages shown start
         * at the prompt of its first run.
         */
        fun layout(): Layout {
            val ordered = allRuns()
            val prompts = messages.count { it.type == USER_MESSAGE }
            val total = chatTotal(ordered, prompts)
            // The runs in hand are the chat's newest; the ones before them are not fetched yet (or not at all).
            val base = total - ordered.size
            // The window is the newest [window] turns of the chat, runs in hand or not: a chat whose runs are still
            // being fetched shows its newest prompts with what there is, never the whole transcript.
            val firstShown = (total - window).coerceAtLeast(0)
            val shown = ordered.drop((firstShown - base).coerceAtLeast(0))
            val pairedCount = (prompts - maxOf(firstShown, base)).coerceIn(0, shown.size)
            return Layout(
                messages = messagesFromPrompt(firstShown),
                paired = shown.take(pairedCount),
                standing = shown.drop(pairedCount),
                olderCount = firstShown,
                total = total,
                runOffset = (base - firstShown).coerceAtLeast(0),
            )
        }

        /**
         * How many runs the chat has, counting the ones the list has not fetched. Exact once the list is complete.
         * Until then every prompt started a run, plus the prompts sent from here that the transcript has not caught
         * up with — told from the transcript's newest prompts, since a prompt sent from here is always the chat's
         * newest — and never fewer than the runs in hand.
         */
        private fun chatTotal(ordered: List<RunDto>, prompts: Int): Int {
            if (runsComplete) return ordered.size
            val listed = runs.mapTo(HashSet()) { it.id }
            val unlisted = local.filter { it.run.id !in listed }
            var missing = 0
            if (unlisted.isNotEmpty()) {
                // The transcript's newest prompt: the newest of the prompts sent from here that it has caught up with.
                val newest = messages.lastOrNull { it.type == USER_MESSAGE }?.text?.trim()
                for (prompt in unlisted.asReversed()) {
                    if (newest != null && newest == prompt.message.text.trim()) break
                    missing++
                }
            }
            return maxOf(prompts + missing, ordered.size)
        }

        /** The transcript from its [index]th user message on (0 is the whole of it; past the last prompt, nothing). */
        private fun messagesFromPrompt(index: Int): List<V0ConversationMessageDto> {
            if (index <= 0) return messages
            var seen = 0
            val start = messages.indexOfFirst { it.type == USER_MESSAGE && seen++ == index }
            return if (start < 0) emptyList() else messages.subList(start, messages.size)
        }

        /** The timeline with [shown] standing in for the runs that have a trace. */
        private fun build(shown: Map<String, List<TimelineItem>>, layout: Layout): List<TimelineItem> {
            // Prompts the server has not answered for yet read as pending; their placeholder run is the key.
            val pending = local.filterNot { it.filed }.mapTo(HashSet()) { it.run.id }
            val items = TimelineBuilder.fromHistory(layout.messages, layout.paired, shown, promptImages, pending, firstRunAt = layout.runOffset).toMutableList()
            layout.standing.forEach { run ->
                val prompt = local.firstOrNull { it.run.id == run.id }
                items += TimelineBuilder.fromHistory(listOfNotNull(prompt?.message, prompt?.reply), listOf(run), shown, promptImages, pending)
            }
            return items.withUniqueIds()
        }

        /** Everything but the followed run's own items: an empty trace makes the builder contribute nothing for it. */
        private fun buildPrefix(liveRunId: String, layout: Layout): Prefix {
            val items = build(traces + (liveRunId to emptyList()), layout)
            return Prefix(this, liveRunId, items).also { builtPrefix = it }
        }

        /** The complete traces, plus the story so far of the followed run unless it already has a complete one. */
        private fun shownTraces(): Map<String, List<TimelineItem>> {
            val current = live?.takeUnless { it.runId in traces } ?: return traces
            return traces + (current.runId to current.items)
        }

        /** Every run known, oldest first: the server's, then the runs of prompts sent from here that its list lacks — placeholders included. */
        fun allRuns(): List<RunDto> {
            if (orderedFromRuns === runs && orderedFromLocal === local) return orderedRuns
            val listed = runs.mapTo(HashSet()) { it.id }
            orderedRuns = (runs + local.map { it.run }.filter { it.id !in listed }).sortedBy { parseIsoMillis(it.createdAt) }
            orderedFromRuns = runs
            orderedFromLocal = local
            return orderedRuns
        }

        /**
         * How many of the chat's runs, oldest first, the server's transcript has a prompt for: its prompts, less the
         * runs older than what the list has fetched (whose prompts come first).
         */
        fun coveredCount(): Int {
            val prompts = messages.count { it.type == USER_MESSAGE }
            if (runsComplete) return prompts
            val ordered = allRuns()
            // The prompts of the runs not fetched yet come first; what is left pairs with the runs in hand, oldest first.
            val unfetched = chatTotal(ordered, prompts) - ordered.size
            return (prompts - unfetched).coerceIn(0, ordered.size)
        }

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
            runsComplete = runsComplete,
            olderRunsCursor = olderRunsCursor,
            // The window the reader had open, within reason: the next start renders it from disk before the network answers.
            window = window.coerceAtMost(MAX_RESTORED_WINDOW),
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

    private val lastOpened = MutableStateFlow<String?>(null)

    /** The chat a screen most recently attached to, or null before any: the one Settings › Advanced diagnoses. */
    val lastOpenedAgentId: StateFlow<String?> = lastOpened.asStateFlow()

    /** True while at least one conversation screen shows this agent. */
    fun isAttached(agentId: String): Boolean = synchronized(entries) { (entries[agentId]?.attached ?: 0) > 0 }

    /** The load's account of [agentId] for the diagnostics export (see [TranscriptLoadDiagnostics]); null for a chat never opened. */
    fun loadDiagnostics(agentId: String): TranscriptLoadDiagnostics? {
        val e = synchronized(entries) { entries[agentId] } ?: return null
        return synchronized(e) {
            val layout = e.layout()
            val live = e.live
            val streaming = e.streamJob?.isActive == true
            val liveRun = e.latestRun()?.takeIf { it.statusEnum().isActive }?.id ?: live?.runId
            val snapshot = liveRun?.let { hub.current(agentId, it) }
            fun traceOf(run: RunDto): String = when {
                run.statusEnum().isActive -> "live"
                run.id in e.traces -> if (run.id in e.staleTraces) "shown(stale)" else "shown"
                run.id in e.traceQueue || run.id in e.traceInFlight -> "pending"
                run.id in e.expiredRuns || parseIsoMillis(run.createdAt) < e.expiredBefore -> "expired"
                run.id in e.failedTraces -> "failed"
                else -> "none"
            }
            TranscriptLoadDiagnostics(
                attached = e.attached,
                paused = e.paused,
                fetched = e.fetched,
                fetchedAtIso = e.fetchedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
                messages = e.messages.size,
                prompts = e.messages.count { it.type == USER_MESSAGE },
                runsLoaded = e.runs.size,
                runsComplete = e.runsComplete,
                hasOlderCursor = e.olderRunsCursor != null,
                runOrder = e.runOrder,
                latestFetchedById = e.latestFetchedById,
                window = e.window,
                windowStart = layout.olderCount,
                chatTurns = layout.total,
                runs = (layout.paired + layout.standing).map { run ->
                    TranscriptLoadDiagnostics.RunLine(ProjectDiagnostics.tail(run.id), run.status, traceOf(run), e.traces[run.id]?.size ?: 0)
                },
                traceQueue = e.traceQueue.size,
                traceInFlight = e.traceInFlight.size,
                traceWorkerRunning = e.traceJob?.isActive == true,
                expiredBeforeIso = e.expiredBefore.takeIf { it > Long.MIN_VALUE }?.let { Instant.ofEpochMilli(it).toString() },
                expiredRuns = e.expiredRuns.size,
                failedTraces = e.failedTraces.size,
                liveRunId = liveRun,
                following = streaming,
                liveStream = snapshot?.let { TranscriptLoadDiagnostics.LiveStreamLine(it.eventCount, it.status.name, it.reconnecting, it.expired, it.finished, it.items.size) },
                lastError = e.state.value.error,
                transcriptError = e.transcriptError,
                transcriptUnavailable = e.transcriptUnavailable,
            )
        }
    }

    /**
     * Attach a screen. Loads history on first attach and keeps streaming while at least one screen is attached. A
     * chat whose launch is still in flight has nothing on the server to load: its prompt is already on screen, and the
     * launch starts the stream when the server answers.
     */
    fun attach(agentId: String) {
        val e = entry(agentId)
        lastOpened.value = agentId
        val firstScreen = synchronized(e) {
            e.attached++
            // A screen attaching can see the chat, whatever the last one that left had done.
            e.paused = false
            if (e.attached == 1 && !e.launching) {
                e.loadJob?.cancel()
                e.loadJob = e.scope.launch {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    load(e, agentId)
                }
            }
            e.attached == 1
        }
        if (firstScreen) onOpened(agentId)
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
                e.stops++
                e.paused = false
                e.traceQueue.clear()
                e.traceInFlight.clear()
                e.loadJob?.cancel()
                e.loadJob = null
                e.traceJob?.cancel()
                e.traceJob = null
                e.runPagingJob?.cancel()
                e.runPagingJob = null
                e.loadingOlder = false
                e.stopFollowing()
                e.trimWindow()
            }
        }
    }

    /**
     * With no screen on the chat, the window falls back to what the next open starts on and the traces of the turns
     * past it leave memory (the disk has them): however far a reader scrolled, a chat left behind holds no more than
     * [MAX_RESTORED_WINDOW] runs' worth of items and traces.
     */
    private fun Entry.trimWindow() {
        if (window <= MAX_RESTORED_WINDOW) return
        publish(
            mutate = {
                window = MAX_RESTORED_WINDOW
                val kept = layout().let { it.paired + it.standing }.mapTo(HashSet()) { it.id }
                traces = traces.filterKeys { it in kept }
            },
        )
    }

    /**
     * Drops everything known about an agent, on disk too; used when it is deleted. A screen still showing it keeps
     * its entry, emptied in place: it captured that state flow once, so dropping the entry would leave it collecting
     * a flow nothing can ever publish to again — and a reload would silently build a second one.
     */
    fun forget(agentId: String) {
        val attached = synchronized(entries) {
            val e = entries[agentId] ?: return@synchronized null
            if (e.attached > 0) return@synchronized e
            entries.remove(agentId)
            e.scope.cancel()
            null
        }
        attached?.emptyInPlace()
        diskIndex[agentId] = ABSENT
        scope.launch {
            cache?.remove(agentId)
            traceCache?.remove(agentId)
        }
    }

    private fun Entry.emptyInPlace() {
        streamJob?.cancel()
        traceJob?.cancel()
        loadJob?.cancel()
        runPagingJob?.cancel()
        publish(
            mutate = {
                messages = emptyList()
                runs = emptyList()
                runsComplete = true
                olderRunsCursor = null
                window = WINDOW_RUNS
                loadingOlder = false
                local = emptyList()
                traces = emptyMap()
                promptImages = emptyMap()
                live = null
                streamJob = null
                traceJob = null
                loadJob = null
                runPagingJob = null
                paused = false
                traceQueue.clear()
                traceInFlight.clear()
                staleTraces.clear()
                failedTraces.clear()
                runOrder = null
                latestFetchedById = false
                transcriptError = null
                projectMode = false
                expiredRuns.clear()
                expiredBefore = Long.MIN_VALUE
                recordedFinishes.clear()
                transcriptUnavailable = false
                fetched = false
                fetchedAt = 0L
                inputsUpdatedAt = 0L
            },
            transform = { ConversationState(agentId, isLoading = false) },
        )
    }

    /**
     * The screen stopped without its ViewModel going with it — the app was backgrounded, or a destination that keeps
     * it alive came up. Nothing can see the chat, so the stream and the replays stop; the reference count is
     * untouched, so this is not a [detach] and the entry stays warm for [resume].
     *
     * The run itself carries on: the hub keeps the shared stream alive for the live-notification monitor and for its
     * release grace, so a quick app switch tears nothing down, and a longer absence is caught up by [resume].
     */
    fun pause(agentId: String) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        synchronized(e) {
            if (e.attached == 0) return
            e.stops++
            e.paused = true
            e.traceJob?.cancel()
            e.traceJob = null
            // The replays under way wait, with the ones not started yet, for the screen to come back.
            e.requeueInFlight()
            e.stopFollowing()
        }
    }

    /**
     * The screen is back. A run still going is followed again straight away rather than waiting on [revalidate],
     * which skips the fetch when the history was read moments ago — after a short absence that would otherwise
     * leave the chat paused with a run streaming behind it.
     *
     * The caller is the composition, so nothing of this is done on its thread: the entry's monitor is held by the
     * background load and the live stream while they rebuild the timeline, and waiting for it there is a stall on
     * the main thread. What the screen coming back means for the entry is settled on [Entry.scope] instead.
     */
    fun resume(agentId: String) = offload(agentId) { e -> resumeNow(e) }

    private fun resumeNow(e: Entry) {
        val run = synchronized(e) {
            if (e.attached == 0) return
            e.paused = false
            if (e.streamJob != null || e.launching) null else e.latestRun()?.takeIf { it.statusEnum().isActive }
        }
        if (run != null) startStreaming(e, e.agentId, run)
        // The replays the pause turned away, and the ones it cut short, pick up where they were.
        loadTraces(e, e.agentId, emptyList())
        revalidateNow(e)
    }

    /**
     * Runs [work] for the agent's entry on that entry's own scope. The count of stops taken here is checked again
     * before [work] runs: the screen can have stopped, or the last one left, while this was waiting to be dispatched,
     * and the entry must not be brought back up behind it.
     */
    private fun offload(agentId: String, work: suspend (Entry) -> Unit) {
        val e = synchronized(entries) { entries[agentId] } ?: return
        val seen = e.stops
        e.scope.launch { if (e.stops == seen) work(e) }
    }

    fun reload(agentId: String) {
        val e = entry(agentId)
        e.stopFollowing()
        e.loadJob?.cancel()
        e.loadJob = e.scope.launch { load(e, agentId) }
    }

    /**
     * Brings an open chat back up to date after the app returns to the foreground: while it was away the network
     * may have taken the stream down mid-run, or the run may have finished. Loads the history again, which restarts
     * the stream of a run still going and replays one that ended. A load already in flight, or one that completed
     * moments ago (the first open), is left alone. Called from the composition, so it is settled off that thread for
     * the same reason [resume] is.
     */
    fun revalidate(agentId: String) = offload(agentId) { e -> revalidateNow(e) }

    private fun revalidateNow(e: Entry) {
        synchronized(e) {
            // A chat still being launched has nothing on the server to fetch; the launch settles it when the server answers.
            if (e.attached == 0 || e.loadJob?.isActive == true || e.launching) return
            if (AppClock.now() - e.fetchedAt < REVALIDATE_MIN_INTERVAL_MS) return
            e.loadJob = e.scope.launch { load(e, e.agentId) }
        }
    }

    /**
     * Rebuilds the visible items from the entry's inputs after [mutate] has changed them. What the rebuilt transcript
     * says about the chat's workers — the one word default mode has on a Project's lineage — goes to the agent list
     * once it has changed (see [Entry.coordinatorLineage]), outside the entry's monitor.
     */
    private inline fun Entry.publish(mutate: Entry.() -> Unit = {}, transform: ConversationState.() -> ConversationState = { this }) {
        val workers = synchronized(this) {
            mutate()
            val items = items()
            val layout = layout()
            val status = traceStatus(layout)
            state.update { it.copy(items = items, hasOlder = layout.olderCount > 0, traceStatus = status).transform() }
            coordinatorLineage(items)
        }
        if (workers != null) {
            // Only what the coordinator's tools returned as workers it created is placed — the `managerAgentId` the
            // account's record carries for a created worker, which default mode cannot read. A chat the coordinator
            // merely messaged or read is placed by nothing (the desktop reads no transcript at all).
            val (created, _) = workers
            if (created.isNotEmpty()) agents.applyLineage(agentId, created.associateWith { AgentParentKind.PROJECT_WORKER }, LineageSignal.COORDINATOR_CREATED)
        }
    }

    /**
     * Where the traces of the window's finished runs stand (see [TraceStatus]). Under the entry's monitor. A run the
     * disk or the log answered for is shown; one on the queue or in flight is pending; one whose log is gone (and
     * that no record filled) is expired; one asked for and not answered is failed, until the next fetch asks again.
     */
    private fun Entry.traceStatus(layout: Layout): TraceStatus {
        var shown = 0
        var pending = 0
        var expired = 0
        var failed = 0
        for (run in layout.paired + layout.standing) {
            if (!run.statusEnum().isTerminal) continue
            when {
                run.id in traces -> shown++
                run.id in traceQueue || run.id in traceInFlight -> pending++
                run.id in expiredRuns || parseIsoMillis(run.createdAt) < expiredBefore -> expired++
                run.id in failedTraces -> failed++
                // Not asked yet (the load is still on its way to asking): pending, not failed.
                else -> pending++
            }
        }
        return TraceStatus(shown, pending, expired, failed)
    }

    /**
     * The workers the chat's transcript names through the coordinator's tools (see [CoordinatorLineage]) — the ones
     * its tools returned as its own, and every one they addressed — when that has changed since it was last reported,
     * else null. A transcript with the tools but no ids yet still reports (empty sets): the chat is a coordinator,
     * and that alone places it.
     */
    private fun Entry.coordinatorLineage(items: List<TimelineItem>): Pair<Set<String>, Set<String>>? {
        if (!CoordinatorLineage.isCoordinator(items)) return null
        val workers = CoordinatorLineage.workerIds(items)
        if (workers == reportedWorkers) return null
        reportedWorkers = workers
        return CoordinatorLineage.createdWorkerIds(items) to workers
    }

    /** Stops following the active run. Its story so far goes with the job (see [Entry.live]). */
    private fun Entry.stopFollowing() {
        streamJob?.cancel()
        publish(mutate = { streamJob = null; live = null }, transform = { copy(isStreaming = false, isReconnecting = false) })
    }

    private suspend fun load(e: Entry, agentId: String) {
        val backend = session.current
        val tokens = cacheTokens()
        val api = backend.api
        if (!e.hasInputs) restoreFromCache(e, agentId)
        e.state.update { it.copy(isLoading = true, error = null) }
        try {
            coroutineScope {
                val conversation = async { runCatching { api.conversationV0(agentId) } }
                // The newest runs, one page: enough to render the window; the rest of the records follow behind the
                // cursor (see [pageOlderRuns]) rather than holding the first frame up.
                val runPage = async { runCatching { api.listRuns(agentId, limit = FIRST_RUN_PAGE) } }
                val stored = async { runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap()) }
                val convResult = conversation.await()
                val runResult = runPage.await()
                val onDevice = stored.await()
                // Each endpoint answers for itself: what one of them could not read is left as it was rather than
                // reported as absent, so a timeout on the run list does not strip the footers and traces off an
                // otherwise healthy transcript — nor write that shape to disk.
                val transcript = convResult.getOrNull()?.messages
                val firstPage = runResult.getOrNull()
                val transcriptFailed = convResult.isFailure && convResult.exceptionOrNull()?.toCursorError()?.httpCode != 404
                // The run the agent's row names as its latest: the one anchor that says which runs are the chat's
                // newest, whatever order the list came in (see [newestRuns]).
                val latestId = agents.agent(agentId)?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
                val (known, knownComplete) = synchronized(e) { e.runs to e.runsComplete }
                val newest = firstPage?.let { newestRuns(api, agentId, it, latestId, known, knownComplete) }
                val page = newest?.page
                val fetched = convResult.isSuccess || page?.items?.isNotEmpty() == true
                // The newest run once the inputs are merged: usually the server's, but a follow-up sent from here
                // that the list has not caught up with yet is newer, and a reload must keep following it rather
                // than declare the previous run the active one.
                var latest: RunDto? = null
                var merged: List<RunDto> = emptyList()
                var unavailable = false
                var pageOlder = false
                val transcriptIssue = if (transcriptFailed) convResult.exceptionOrNull()?.userMessage() ?: "The transcript could not be read." else null
                if (fetched) {
                    // The traces are kept: they are complete, and a finished run's log does not change. Local prompts
                    // hand over to the server once it reports them in full.
                    e.publish(
                        mutate = {
                            transcript?.let { messages = it }
                            newest?.let { mergeNewestPage(it.page, endKnown = it.endKnown); runOrder = if (it.ascending) RunOrder.OLDEST_FIRST else RunOrder.NEWEST_FIRST; latestFetchedById = it.latestFetched }
                            unavailable = transcriptFailed && messages.isEmpty()
                            this.transcriptUnavailable = unavailable
                            transcriptError = transcriptIssue
                            inputsUpdatedAt = agents.agent(agentId)?.updatedAtMillis ?: 0L
                            pruneLocal()
                            // The disk knows every filed prompt; only prompts still in flight exist solely in memory.
                            promptImages = onDevice + promptImages.filterKeys { key -> local.any { it.run.id == key } }
                            this.fetched = true
                            fetchedAt = AppClock.now()
                            latest = latestRun()
                            merged = runs
                            pageOlder = !runsComplete && olderRunsCursor != null
                        },
                        transform = {
                            copy(
                                isLoading = false,
                                error = null,
                                activeRunId = latest?.id,
                                runStatus = latest?.let { e.statusOf(it) },
                                isStreaming = false,
                                isReconnecting = false,
                                transcriptUnavailable = unavailable,
                                transcriptError = transcriptIssue,
                            )
                        },
                    )
                } else {
                    e.reportLoadFailure(convResult.exceptionOrNull() ?: runResult.exceptionOrNull())
                }
                // The latest run is the row's execution state (a turn that ended in an error is only visible here),
                // so the sidebar reflects it right away. The full agent record then enriches the row (repo, PR,
                // duration); it never holds up the transcript, and reuses this run, saving a round-trip.
                latest?.let { run -> agents.patch(agentId) { it.withLatestRun(run) } }
                launch { agents.loadDetail(agentId, latest) }
                if (fetched) {
                    agents.agent(agentId)?.let { prefs.markRead(agentId, it.updatedAtMillis) }
                    persist(e, backend, tokens)
                    val active = latest?.takeIf { it.statusEnum().isActive }
                    if (active != null) {
                        startStreaming(e, agentId, active)
                    } else {
                        // The run being followed is over by the server's account: what its stream told before the
                        // connection dropped, or the outcome read from the run record, must not stand in for it any
                        // longer — the transcript has the reply now, and the replay below brings the whole trace.
                        val followed = synchronized(e) { e.live?.runId }
                        if (followed != null && merged.any { it.id == followed && !it.statusEnum().isActive }) e.stopFollowing()
                    }
                    loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
                    if (pageOlder) pageOlderRuns(e, agentId)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            e.reportLoadFailure(t)
        }
    }

    /** The runs the window renders right now, newest last. */
    private fun Entry.shownRuns(): List<RunDto> = synchronized(this) { layout().let { it.paired + it.standing } }

    /**
     * Folds the newest page of the run list into [Entry.runs], which hold the chat's newest runs and, behind them, as
     * many older ones as the list has been paged to. The page's records replace what they name and the older runs
     * already held stay; a run started elsewhere since joins at the top. The page's cursor is the way to the rest of
     * the list only when nothing older is held already — otherwise the older records, and the cursor past them,
     * are the entry's own.
     */
    private fun Entry.mergeNewestPage(page: ListRunsResponseDto, endKnown: Boolean = true) {
        val fresh = page.items
        val cursor = page.nextCursor?.takeIf { it.isNotBlank() }
        if (cursor == null) {
            // The page is the whole list: whatever else was held (a copy from another session) is not the chat's.
            // Unless the end was never reached (see [newestRuns]): then these are the newest runs and the rest are
            // simply not to be had, so the prompts before them stand without runs.
            runs = fresh
            olderRunsCursor = null
            runsComplete = endKnown
            return
        }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val older = runs.filter { it.id !in freshIds }
        runs = fresh + older
        if (older.isEmpty()) {
            olderRunsCursor = cursor
            runsComplete = false
        }
    }

    /**
     * The first page of the run list as a page of the chat's newest runs, whichever way the server listed them.
     *
     * The reference documents `GET /v1/agents/{id}/runs` newest first, and the field has shown it oldest first: on a
     * chat of a hundred and sixty turns, the first page was the chat's first twenty runs, and everything built on it
     * — the run followed as the live one, the runs whose logs were asked for, the run the row was patched with — was
     * about turns finished months before. The text of the newest prompts, laid over them, showed with no tool calls,
     * the ongoing turn read as finished, and the row went idle. So the order is read off the page itself and off the
     * agent's [latestRunId]: a page listed oldest first is read on to the end of the list, so that the runs in hand
     * are every run and the newest among them (past [MAX_RUN_PAGES] the newest run alone stands, fetched by id); a
     * page listed newest first that lacks the latest run — the list lagging the agent's record — gets it by id. The
     * cursor a newest-first page carries stays the way to the older records (see [pageOlderRuns]).
     */
    private suspend fun newestRuns(api: CursorApi, agentId: String, first: ListRunsResponseDto, latestId: String?, known: List<RunDto>, knownComplete: Boolean): NewestRuns {
        val items = first.items
        val cursor = first.nextCursor?.takeIf { it.isNotBlank() }
        val ascending = items.size >= 2 && parseIsoMillis(items.first().createdAt) < parseIsoMillis(items.last().createdAt)
        var page = first
        var endKnown = true
        if (ascending && cursor != null && latestId != null && known.any { it.id == latestId }) {
            // Read before, and the agent has started nothing since: the runs in hand are the newest still, the page
            // refreshes the records it names, and the rest of the pages are not read again.
            val fresh = items.associateBy { it.id }
            page = ListRunsResponseDto(items = known.map { fresh[it.id] ?: it }, nextCursor = null)
            endKnown = knownComplete
        } else if (ascending && cursor != null) {
            // Oldest first: the newest runs are at the end of the list, so read to it.
            val all = items.toMutableList()
            var next: String? = cursor
            var pages = 1
            while (next != null && pages < MAX_RUN_PAGES) {
                val more = runCatching { api.listRuns(agentId, limit = RUN_PAGE_SIZE, cursor = next) }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    null
                } ?: break
                if (more.items.isEmpty()) { next = null; break }
                all += more.items
                next = more.nextCursor?.takeIf { it.isNotBlank() }
                pages++
            }
            page = if (next == null) {
                // Every run, newest first as the rest of this class expects a complete list to be laid out.
                ListRunsResponseDto(items = all.distinctBy { it.id }.sortedByDescending { parseIsoMillis(it.createdAt) }, nextCursor = null)
            } else {
                // Read as far as it will be, and still short of the end: the runs in hand are old ones, and laying
                // them under the newest prompts would be the very bug. The newest run alone stands, when it is known.
                endKnown = false
                ListRunsResponseDto(items = emptyList(), nextCursor = null)
            }
        }
        if (latestId != null && page.items.none { it.id == latestId }) {
            val latest = runCatching { api.getRun(agentId, latestId) }.getOrElse { t ->
                if (t is CancellationException) throw t
                null
            }
            if (latest != null) page = page.copy(items = listOf(latest) + page.items.filter { it.id != latest.id })
        }
        return NewestRuns(page, ascending = ascending, endKnown = endKnown, latestFetched = latestId != null && first.items.none { it.id == latestId } && page.items.any { it.id == latestId })
    }

    /** What [newestRuns] made of the first page: the page to merge, and the facts about it the diagnostics report. */
    private class NewestRuns(val page: ListRunsResponseDto, val ascending: Boolean, val endKnown: Boolean, val latestFetched: Boolean)

    /**
     * Pages the rest of the run records behind the newest page, oldest last, so the transcript's prompts pair with
     * their own runs and the older turns are ready to be built when the reader scrolls up to them. The records are
     * small; what is dear — replaying the runs' logs — waits for the window to reach them. Bounded: a chat longer
     * than [MAX_RUN_PAGES] pages has turns whose logs expired long ago, and its oldest prompts are shown without runs.
     * Once the records are in, the window's traces are asked for again: the runs the window pairs with can have
     * changed with the count.
     */
    private fun pageOlderRuns(e: Entry, agentId: String) {
        synchronized(e) {
            if (e.runPagingJob?.isActive == true || e.runsComplete || e.olderRunsCursor == null) return
            e.runPagingJob = e.scope.launch {
                val pages = pageOlderRunsNow(e, agentId, pages = MAX_RUN_PAGES)
                // The bound was reached with a cursor still in hand: the list is as complete as it will be read, so
                // the prompts beyond it stand without runs rather than waiting for pages nobody asks for.
                if (pages >= MAX_RUN_PAGES) {
                    e.publish(mutate = { if (!runsComplete && olderRunsCursor != null) runsComplete = true })
                    persist(e, session.current)
                }
                if (pages > 0 && synchronized(e) { e.attached > 0 }) loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
            }
        }
    }

    /**
     * Fetches up to [pages] pages of older run records and folds each in as it lands. Returns how many landed — a
     * failure leaves the cursor where it was for the next attempt.
     */
    private suspend fun pageOlderRunsNow(e: Entry, agentId: String, pages: Int): Int {
        val backend = session.current
        val tokens = cacheTokens()
        var fetched = 0
        for (i in 0 until pages) {
            val cursor = synchronized(e) { e.olderRunsCursor.takeUnless { e.runsComplete } } ?: break
            val page = runCatching { backend.api.listRuns(agentId, limit = RUN_PAGE_SIZE, cursor = cursor) }.getOrElse { t ->
                if (t is CancellationException) throw t
                break
            }
            fetched++
            e.publish(
                mutate = {
                    val known = runs.mapTo(HashSet()) { it.id }
                    runs = runs + page.items.filter { it.id !in known }
                    olderRunsCursor = page.nextCursor?.takeIf { it.isNotBlank() && page.items.isNotEmpty() }
                    runsComplete = olderRunsCursor == null
                    pruneLocal()
                },
            )
        }
        if (fetched > 0) persist(e, backend, tokens)
        return fetched
    }

    /**
     * Widens the window by [WINDOW_RUNS] older turns: their run records are fetched when the list has not reached
     * them yet, their items built, and their traces read from disk or replayed, newest first. For the screen's
     * scroll-up; settled on the entry's scope like [resume] is.
     */
    fun loadOlder(agentId: String) = offload(agentId) { e -> loadOlderNow(e) }

    /**
     * Asks again for the traces the last pass could not read (see [TraceStatus.failed]) — the reader's Retry — and
     * for anything of the window still missing that is not known to have expired.
     */
    fun retryTraces(agentId: String) = offload(agentId) { e ->
        synchronized(e) { e.failedTraces.clear() }
        loadTraces(e, agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
    }

    private suspend fun loadOlderNow(e: Entry) {
        val proceed = synchronized(e) {
            if (e.loadingOlder || e.attached == 0) return
            if (e.layout().olderCount <= 0) return
            e.loadingOlder = true
            true
        }
        if (!proceed) return
        e.state.update { it.copy(isLoadingOlder = true) }
        try {
            // Records first: the window must not widen onto prompts whose runs have not been fetched, or their
            // footers and traces would be missing while a page is still in flight.
            e.runPagingJob?.takeIf { it.isActive }?.join()
            val needsRecords = synchronized(e) {
                !e.runsComplete && e.olderRunsCursor != null && e.allRuns().size < e.window + WINDOW_RUNS
            }
            if (needsRecords) pageOlderRunsNow(e, e.agentId, pages = 1)
            e.publish(mutate = { window += WINDOW_RUNS })
            persist(e, session.current)
            loadTraces(e, e.agentId, e.shownRuns().filter { it.statusEnum().isTerminal })
        } finally {
            e.publish(mutate = { loadingOlder = false }, transform = { copy(isLoadingOlder = false) })
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
        val listed = runs.mapTo(HashSet()) { it.id }
        val ordered = allRuns()
        val covered = coveredCount()
        local = local.filter { prompt -> prompt.run.id !in listed || ordered.indexOfFirst { it.id == prompt.run.id } >= covered }
    }

    /** Shows the transcript and traces saved by an earlier visit, if any, while the network answers. */
    private suspend fun restoreFromCache(e: Entry, agentId: String) {
        // An entry that already has its inputs has nothing to restore.
        if (synchronized(e) { e.hasInputs || e.fetched }) return
        val cached = readCache(agentId) ?: return
        // The inputs arrived while the file was being read: the memory copy wins.
        if (synchronized(e) { e.hasInputs || e.fetched }) return
        val latest = (cached.runs + cached.local.map { saved -> saved.run }).maxByOrNull { parseIsoMillis(it.createdAt) }
        // A run that was still active when the cache was written has very likely finished since; the live state
        // ("Working…", Stop) waits for the network unless a fetched agent row confirms the run is still going.
        val list = agents.state.value
        val row = list.agents.firstOrNull { it.id == agentId }
        val status = latest?.statusEnum()?.takeUnless { it.isActive && (list.isFromCache || row?.isRunning != true) }
        // The prompts' images live on this device only, so the saved transcript can show them right away too.
        val onDevice = runCatching { attachments.forAgent(agentId) }.getOrDefault(emptyMap())
        // A copy whose runs are not the chat's newest — a page an earlier build kept from a list read oldest first,
        // killed before the rest — must not be laid under the newest prompts (see [newestRuns]). The row's latest run
        // is the anchor: an incomplete copy without it renders its prompts alone until the network answers.
        val trusted = cached.runsComplete || row?.latestRunId == null || row.latestRunId.startsWith(LOCAL_RUN_PREFIX) || cached.runs.any { it.id == row.latestRunId } || cached.local.any { it.run.id == row.latestRunId }
        e.publish(
            mutate = {
                messages = cached.messages
                runs = if (trusted) cached.runs else emptyList()
                runsComplete = if (trusted) cached.runsComplete else false
                olderRunsCursor = if (trusted) cached.olderRunsCursor else null
                if (cached.window > 0) window = cached.window.coerceIn(WINDOW_RUNS, MAX_RESTORED_WINDOW)
                transcriptUnavailable = cached.transcriptUnavailable
                inputsUpdatedAt = cached.agentUpdatedAtMillis
                // The prompts sent from here go on standing in, exactly as they did when the copy was written.
                local = local + cached.local.filter { saved -> local.none { it.run.id == saved.run.id } }.map { LocalPrompt(it.message, it.run, it.reply) }
                if (promptImages.isEmpty()) promptImages = onDevice
            },
            transform = { if (trusted) copy(activeRunId = latest?.id, runStatus = status, transcriptUnavailable = cached.transcriptUnavailable) else copy(transcriptUnavailable = cached.transcriptUnavailable) },
        )
        // The window's traces come straight from their files — one small read per run, nothing of the older turns —
        // so the chat is whole, tool calls and payloads included, before the network has said a word.
        val shown = e.shownRuns().filter { it.statusEnum().isTerminal }
        val saved = readTraces(agentId, shown.map { it.id })
        if (saved.isNotEmpty()) {
            e.publish(mutate = {
                traces = saved.mapValues { it.value.items } + traces
                // A file from an earlier build without the coordinator's messages or the goal's objective is shown,
                // and asked for again once the network answers (see [loadTraces]).
                staleTraces += saved.filterValues { isStaleTrace(it.items) }.keys
            })
        }
    }

    private suspend fun readCache(agentId: String): CachedConversation? {
        val store = cache?.takeIf { !session.isDemo } ?: return null
        val cached = store.read(agentId)?.value
        diskIndex[agentId] = cached?.agentUpdatedAtMillis ?: ABSENT
        return cached
    }

    /** The traces the disk holds for [runIds]: a file per run, and only the runs named. */
    private suspend fun readTraces(agentId: String, runIds: Collection<String>): Map<String, CachedTrace> {
        if (runIds.isEmpty()) return emptyMap()
        val store = traceCache?.takeIf { !session.isDemo } ?: return emptyMap()
        return runCatching { store.read(agentId, runIds) }.getOrDefault(emptyMap())
    }

    /**
     * The cache generations the work started under. Sampling them inside the write instead would let a pass that a
     * sign-out cancelled — one that finishes its file after the wipe, as the trace replay deliberately does — write
     * the signed-out account's chat back to a cache that has since been reopened for the next one.
     */
    private data class CacheTokens(val conversations: Int, val traces: Int)

    private fun cacheTokens() = CacheTokens(cache?.token() ?: 0, traceCache?.token() ?: 0)

    private suspend fun persist(e: Entry, backend: CursorBackend, tokens: CacheTokens = cacheTokens()) {
        val store = cache ?: return
        if (backend.isDemo) return
        val snapshot = synchronized(e) { if (e.hasInputs || e.local.isNotEmpty()) e.toCached() else null } ?: return
        store.write(snapshot, tokens.conversations)
        diskIndex[snapshot.agentId] = snapshot.agentUpdatedAtMillis
    }

    private suspend fun writeTrace(agentId: String, runId: String, createdAtMillis: Long, items: List<TimelineItem>, tokens: CacheTokens = cacheTokens()) =
        writeTraces(agentId, listOf(CachedTrace(runId, createdAtMillis, items)), tokens)

    /** Each trace to its own file (see [TraceCache]): nothing of the agent's other runs is read or written for it. */
    private suspend fun writeTraces(agentId: String, traces: List<CachedTrace>, tokens: CacheTokens = cacheTokens()) {
        if (traces.isEmpty()) return
        val store = traceCache?.takeIf { !session.isDemo } ?: return
        runCatching { store.put(agentId, traces, tokens.traces) }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Asks for the traces of [finishedRuns]: queued, newest first, behind whatever is already queued, and drained by
     * one worker per entry ([drainTraces]). Asking for more runs while a pass is under way adds to it rather than
     * starting over — the pass that replayed the newest forty runs used to be cancelled the moment the run being
     * followed finished, and the runs it had not reached stayed text-only until the next fetch. A paused entry keeps
     * its queue for [resume]; a detached one drops it.
     */
    private fun loadTraces(e: Entry, agentId: String, finishedRuns: List<RunDto>) {
        synchronized(e) {
            finishedRuns
                .filter { (it.id !in e.traces || it.id in e.staleTraces) && it.id !in e.traceQueue && it.id !in e.traceInFlight && it.id !in e.expiredRuns && parseIsoMillis(it.createdAt) >= e.expiredBefore }
                .sortedByDescending { parseIsoMillis(it.createdAt) }
                .forEach { e.traceQueue[it.id] = it; e.failedTraces -= it.id }
            // A worker still registered drains what was just queued on its next round (it unregisters itself under
            // this monitor the moment it finds the queue empty, so nothing can slip in between).
            if (e.paused || e.traceQueue.isEmpty() || e.traceJob != null) return
            e.traceJob = e.scope.launch { drainTraces(e, agentId) }
        }
    }

    /**
     * Completes the traces of the queued runs, newest first: the disk answers first — one file per run, only the
     * runs asked for — and what it lacks is replayed from the retained stream. The retention window is time-based,
     * so once one run's log has expired every older run's has too and the rest are skipped, this session and the
     * next (see [Entry.expiredBefore]). A few workers pull from the queue, so the newest runs are always the first
     * ones asked. Every trace that lands is written back the moment it does, so it is asked for exactly once in the
     * life of the install; a pass cut short (the screen paused) puts what it had not settled back on the queue.
     */
    private suspend fun drainTraces(e: Entry, agentId: String) {
        val self = currentCoroutineContext()[Job]
        try {
            drainTraces(e, agentId, self)
        } finally {
            // However this worker ends — the queue empty, a pause, a failure — it is no longer the one to drain.
            synchronized(e) { if (e.traceJob === self) e.traceJob = null }
        }
    }

    private suspend fun drainTraces(e: Entry, agentId: String, self: Job?) {
        val tokens = cacheTokens()
        while (true) {
            val batch = synchronized(e) {
                val taken = e.traceQueue.values.sortedByDescending { parseIsoMillis(it.createdAt) }
                e.traceQueue.clear()
                taken.forEach { e.traceInFlight[it.id] = it }
                // Nothing left: this worker is done, and the next request starts the next one. Decided under the
                // monitor [loadTraces] queues under, so a run queued from now on always finds a worker to start.
                if (taken.isEmpty() && e.traceJob === self) e.traceJob = null
                taken
            }
            if (batch.isEmpty()) return
            try {
                // A stale trace is already shown from the disk; what is wanted for it is the replay, not the file again.
                val stale = synchronized(e) { e.staleTraces.toSet() }
                val saved = readTraces(agentId, batch.map { it.id }.filter { it !in stale })
                if (saved.isNotEmpty()) {
                    e.publish(mutate = {
                        traces = saved.mapValues { it.value.items } + traces
                        saved.keys.forEach { traceInFlight.remove(it) }
                        // A file from an earlier build without the coordinator's messages or the goal's objective: shown, and replayed after all.
                        saved.filterValues { isStaleTrace(it.items) }.keys.forEach { runId ->
                            staleTraces += runId
                            batch.firstOrNull { it.id == runId }?.let { traceInFlight[runId] = it }
                        }
                    })
                }
                val pending = synchronized(e) { batch.filter { it.id in e.traceInFlight } }
                if (pending.isEmpty()) continue
                // A log the hub already saw expire (an earlier pass, a reload) settles every older run before any
                // worker starts, so those are never asked again — the answer would depend on which worker finished first.
                val knownExpired = pending.filter { hub.current(agentId, it.id)?.expired == true }.maxOfOrNull { parseIsoMillis(it.createdAt) } ?: Long.MIN_VALUE
                val next = AtomicInteger(0)
                val newestExpired = AtomicLong(maxOf(knownExpired, synchronized(e) { e.expiredBefore }))
                coroutineScope {
                    repeat(minOf(MAX_PARALLEL_REPLAYS, pending.size)) {
                        launch {
                            while (true) {
                                val run = pending.getOrNull(next.getAndIncrement()) ?: return@launch
                                val createdAt = parseIsoMillis(run.createdAt)
                                if (createdAt < newestExpired.get()) {
                                    synchronized(e) { e.expiredRuns += run.id; e.traceInFlight.remove(run.id) }
                                    continue
                                }
                                val snapshot = hub.replay(agentId, run.id, createdAt.takeIf { it > 0 })
                                when {
                                    snapshot.hasTrace -> {
                                        val trace = settleTrace(e, run, snapshot.items)
                                        // Kept the moment it is whole: a process that goes now loses nothing replayed.
                                        withContext(NonCancellable) { writeTraces(agentId, listOf(trace), tokens) }
                                    }
                                    snapshot.expired -> {
                                        newestExpired.updateAndGet { maxOf(it, createdAt) }
                                        synchronized(e) {
                                            e.expiredRuns += run.id
                                            e.expiredBefore = maxOf(e.expiredBefore, createdAt)
                                        }
                                    }
                                    // A log that could not be read right now is not there yet: said as such, and asked
                                    // for again on the next fetch or at the reader's request (see [retryTraces]).
                                    else -> synchronized(e) { e.failedTraces += run.id }
                                }
                                synchronized(e) { e.traceInFlight.remove(run.id) }
                            }
                        }
                    }
                }
                // The runs whose logs are gone: in Extended mode the account's own transcript still has their turns.
                val gone = synchronized(e) { pending.filter { it.id in e.expiredRuns && (it.id !in e.traces || it.id in e.staleTraces) } }
                if (gone.isNotEmpty()) fillFromRecord(e, agentId, gone, tokens)
            } finally {
                // Runs this pass did not get to (paused half-way) wait on the queue for the next one — unless a pause
                // already put them there and moved on (see [pause]). What the pass found out about the runs that
                // brought no trace — expired, failed — is published with the items as they are.
                e.publish(mutate = { if (traceJob === self) requeueInFlight() })
            }
        }
    }

    /**
     * Fills the traces of [runs] — finished runs whose documented log has expired — from the account's transcript
     * (Extended mode): the record's turns pair with the chat's runs by position, oldest first, and a turn is taken
     * only when the prompt it starts with is the one the transcript pairs with the run, so a record that counts its
     * turns differently never dresses a run in another turn's work. What lands is kept on disk like a replayed trace.
     */
    private suspend fun fillFromRecord(e: Entry, agentId: String, runs: List<RunDto>, tokens: CacheTokens) {
        val api = record ?: return
        if (session.isDemo || !capabilities().accountTranscript) return
        val (ordered, total, prompts) = synchronized(e) {
            val listed = e.runs.sortedBy { parseIsoMillis(it.createdAt) }
            val prompts = e.messages.filter { it.type == USER_MESSAGE }.map { it.text.trim() }
            Triple(listed, if (e.runsComplete) listed.size else maxOf(prompts.size, listed.size), prompts)
        }
        val wanted = runs.mapNotNull { run -> ordered.indexOfFirst { it.id == run.id }.takeIf { it >= 0 }?.let { run to it } }
        if (wanted.isEmpty()) return
        val unfetched = total - ordered.size
        val oldest = wanted.minOf { (_, index) -> unfetched + index }
        val turns = runCatching { HeadlessTranscript.tailTurns(api, agentId, count = total - oldest) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return
        if (turns.isEmpty()) return
        // The record carries the mode a prompt was sent in; a Project's coordinator is told from that alone.
        if (turns.any { it.projectMode } && synchronized(e) { !e.projectMode }) {
            e.publish(mutate = { projectMode = true }, transform = { copy(isProjectConversation = true) })
        }
        val sink = images?.forAgent(agentId)
        for ((run, index) in wanted) {
            val position = unfetched + index
            // The record's last turn is the chat's last run; count back from there.
            val turn = turns.getOrNull(turns.size - (total - position)) ?: continue
            val prompt = prompts.getOrNull(position)
            if (prompt != null && turn.prompt != null && prompt != turn.prompt.trim()) continue
            val items = HeadlessTranscript.trace(turn, run, sink)
            if (items.none { it !is RunFooter }) continue
            val trace = settleTrace(e, run, items)
            withContext(NonCancellable) { writeTraces(agentId, listOf(trace), tokens) }
        }
    }

    /** What a cut-short pass was working on goes back on the queue for the next one. Under the entry's monitor. */
    private fun Entry.requeueInFlight() {
        traceInFlight.values.filter { (it.id !in traces || it.id in staleTraces) && it.id !in expiredRuns }.forEach { traceQueue.putIfAbsent(it.id, it) }
        traceInFlight.clear()
    }

    /**
     * A trace off the disk that this build would read more from than the build that wrote it kept: a coordinator's
     * message without its body, a goal set without its objective. Worth asking the run's log for again.
     */
    private fun isStaleTrace(items: List<TimelineItem>): Boolean = CoordinatorTranscript.needsRefresh(items) || GoalTranscript.needsRefresh(items)

    /** Shows a run's complete trace and hands it to the caller for the file. */
    private fun settleTrace(e: Entry, run: RunDto, items: List<TimelineItem>): CachedTrace {
        e.publish(mutate = {
            traces = traces + (run.id to items)
            staleTraces -= run.id
        })
        return CachedTrace(run.id, parseIsoMillis(run.createdAt), items)
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
            // The last screen may have left, or the one still attached been covered, while the load that got here
            // was wrapping up: then nobody is looking, and the next attach or resume decides afresh.
            if (e.attached == 0 || e.paused) return@synchronized false
            e.publish(mutate = { streamJob = job; live = null }, transform = { copy(activeRunId = run.id, runStatus = e.statusOf(run), isStreaming = true) })
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
            // With no screen following it, the status would otherwise stay at the last thing the screen saw.
            transform = { if (activeRunId == run.id) copy(runStatus = snapshot.status) else this },
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
            // What the run reports of itself, else how long it was actually watched for: the footer of a run whose
            // outcome carries no duration would otherwise say nothing, about a run the notification timed.
            durationMs = result?.durationMs ?: run.durationMs ?: (finishedAt - snapshot.startedAtMillis).takeIf { it > 0 },
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
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val staged = stageFollowUp(agentId, text, images)
        return sendStaged(agentId, staged, images, mcpServers, planMode, modelId, modelParams, modelDisplayName)
            .onFailure { t ->
                // Refused as busy, the message is not lost: the caller queues it for the end of the turn. That is
                // nothing for the chat to show as an error.
                discardStaged(agentId, staged, t.userMessage().takeUnless { t.toCursorError()?.code == AGENT_BUSY })
            }
    }

    /**
     * Whether [text] is the newest prompt the server holds for this chat, filed no earlier than [sinceMillis]. This
     * is what a follow-up whose send the app did not live to see the end of has to ask before it goes out again: the
     * runs API takes no idempotency key, so nothing else can tell a message that arrived from one that did not.
     *
     * A follow-up the server accepted is, by construction, the last prompt of the chat and the newest run of it. The
     * run's stamp is the server's and [sinceMillis] this device's, so the two are compared with room for the clocks
     * disagreeing — being a little generous costs a duplicate that would not have been sent, never a message shown
     * as sent that was not.
     *
     * A failure is not an answer of no: the caller has to tell "it did not arrive" from "nobody could say".
     */
    suspend fun wasSentSince(agentId: String, text: String, sinceMillis: Long): Result<Boolean> = runCatching {
        val api = session.current.api
        coroutineScope {
            val conversation = async { api.conversationV0(agentId) }
            val runs = async { api.listRuns(agentId, limit = FIRST_RUN_PAGE) }
            val newest = conversation.await().messages.lastOrNull { it.type == USER_MESSAGE }?.text?.trim()
            val newestRunAt = runs.await().items.maxOfOrNull { parseIsoMillis(it.createdAt) } ?: 0L
            newest == text.trim() && newestRunAt >= sinceMillis - CLOCK_SKEW_ALLOWANCE_MS
        }
    }

    /**
     * Shows a follow-up in the transcript before its request goes out — as a pending bubble, paired with a placeholder
     * run so it is built like every other turn (ordered by the run's stamp, its images keyed by the run) until the
     * server's run takes its place. [sendStaged] sends it; [discardStaged] takes it down again. Split from
     * [sendFollowUp] so a queued message that is steered can be on screen while the turn it interrupts is still being
     * cancelled.
     */
    suspend fun stageFollowUp(agentId: String, text: String, images: List<PromptImage> = emptyList()): StagedFollowUp {
        val e = entry(agentId)
        val trimmed = text.trim()
        val now = AppClock.now()
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
        return StagedFollowUp(localId, trimmed, staged, now)
    }

    /**
     * Sends a [stageFollowUp]ed prompt. On success the server's run takes the placeholder's place — the bubble is no
     * longer pending — and its stream starts. On failure the bubble is left as it is, pending, for the caller to try
     * again or [discardStaged].
     */
    suspend fun sendStaged(
        agentId: String,
        staged: StagedFollowUp,
        images: List<PromptImage> = emptyList(),
        mcpServers: List<McpServer> = emptyList(),
        planMode: Boolean? = null,
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
    ): Result<Unit> {
        val e = entry(agentId)
        return agents.followUp(
            agentId,
            staged.text,
            images,
            planMode = planMode,
            mcpServers = mcpServers,
            modelId = modelId,
            modelParams = modelParams,
            modelDisplayName = modelDisplayName,
        ).map { run -> accepted(e, agentId, staged, run) }
    }

    /**
     * Files a follow-up through the account service rather than the documented run request — for a mode the
     * documented API cannot carry — and shows it like [sendFollowUp]. [send] answers with the run the account
     * started; when it names none (the message went into the account's queue behind a turn) the bubble comes down
     * without an error and the chat is reloaded, which is where the message shows up next.
     */
    suspend fun sendFollowUpVia(
        agentId: String,
        text: String,
        images: List<PromptImage> = emptyList(),
        modelId: String? = null,
        modelParams: List<ModelParam> = emptyList(),
        modelDisplayName: String? = null,
        send: suspend () -> String?,
    ): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Type a follow-up first."))
        val e = entry(agentId)
        val staged = stageFollowUp(agentId, text, images)
        return agents.followUpVia(agentId, modelId, modelParams, modelDisplayName, send)
            .map { run ->
                if (run != null) {
                    accepted(e, agentId, staged, run)
                } else {
                    discardStaged(agentId, staged)
                    reload(agentId)
                }
            }
            .onFailure { t -> discardStaged(agentId, staged, t.userMessage()) }
    }

    /** The server has filed [staged] as [run]: the bubble is no longer pending, its images follow it, and its stream starts. */
    private suspend fun accepted(e: Entry, agentId: String, staged: StagedFollowUp, run: RunDto) {
        val localId = staged.localId
        // Filed under the run so the next history load finds them; the bubble follows the files to their new paths.
        val kept = runCatching { attachments.commit(agentId, run.id, staged.attachments) }.getOrDefault(staged.attachments.attachments)
        e.publish(
            mutate = {
                // A reload that raced the request may already list this run. The local copy stays all the
                // same: [Entry.items] shows the server's copy of the turn once the transcript has it, and ours
                // for as long as only the run list does; the next load prunes it once both have caught up.
                local = local.map { if (it.run.id == localId) it.copy(run = run) else it }
                promptImages = (promptImages - localId).let { if (kept.isEmpty()) it else it + (run.id to kept) }
                inputsUpdatedAt = maxOf(inputsUpdatedAt, staged.stagedAt)
            },
        )
        startStreaming(e, agentId, run)
        persist(e, session.current)
    }

    /** Takes a [stageFollowUp]ed prompt that will not be sent down again, with the reason when there is one to show. */
    suspend fun discardStaged(agentId: String, staged: StagedFollowUp, error: String? = null) {
        val e = entry(agentId)
        attachments.discard(staged.attachments)
        e.publish(
            mutate = {
                local = local.filterNot { it.run.id == staged.localId }
                promptImages = promptImages - staged.localId
            },
            transform = { if (error != null) copy(error = error) else this },
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
        // A chat the server has not answered for yet has no run to cancel; stopping it gives the launch up instead.
        if (cancelLaunch(agentId)) return Result.success(Unit)
        val runId = runToCancel(agentId) ?: return Result.failure(IllegalStateException("No active run."))
        return cancelRun(agentId, runId)
    }

    /** Cancels one run of the chat and, once the server has agreed, shows the turn as cancelled. */
    suspend fun cancelRun(agentId: String, runId: String): Result<Unit> {
        val e = entry(agentId)
        return agents.cancelRun(agentId, runId).onSuccess {
            e.cancelledRunId = runId
            e.state.update { it.copy(runStatus = RunStatus.CANCELLED) }
        }
    }

    /**
     * The run a Stop is aimed at: the one the server is on, as far as this device knows. The row's latest run while
     * the row says the agent is running — a follow-up that went out while no screen was attached, or a turn started
     * elsewhere, reaches the row before the chat has loaded it, and the run the chat still follows may be over by
     * then — else the run the chat follows. Never a prompt's local placeholder, which the server knows nothing of.
     * Null when nothing is known to be running.
     */
    fun runToCancel(agentId: String): String? {
        val row = agents.agent(agentId)
        return row?.takeIf { it.isRunning }?.latestRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
            ?: entry(agentId).state.value.activeRunId?.takeUnless { it.startsWith(LOCAL_RUN_PREFIX) }
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
        val tokens = cacheTokens()
        val api = backend.api
        for (agent in candidates) {
            if (!isForeground()) return
            if (isFresh(agent)) continue
            val ok = runCatching {
                coroutineScope {
                    val conversation = async { api.conversationV0(agent.id) }
                    // The newest page alone: it is what the chat opens on, and the rest of the records follow when it does.
                    val runs = async { api.listRuns(agent.id, limit = FIRST_RUN_PAGE) }
                    val transcript = conversation.await().messages
                    val page = runs.await()
                    val latest = page.items.maxByOrNull { parseIsoMillis(it.createdAt) }
                    // The list only said the agent went idle; the run says how the turn ended (an error, say), and the
                    // row is the one place the sidebar learns that from without the chat being opened.
                    latest?.let { run -> agents.patch(agent.id) { it.withLatestRun(run) } }
                    val e = entry(agent.id)
                    // A screen that opened it in the meantime owns the entry now.
                    if (e.attached == 0 && e.streamJob == null) {
                        e.publish(
                            mutate = {
                                messages = transcript
                                mergeNewestPage(page)
                                transcriptUnavailable = false
                                inputsUpdatedAt = agent.updatedAtMillis
                                pruneLocal()
                                fetched = true
                            },
                            transform = { copy(isLoading = false, activeRunId = latest?.id, runStatus = latest?.statusEnum()) },
                        )
                        persist(e, backend, tokens)
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
        /** The newest runs, fetched first: enough for the window, small enough not to hold the first frame up. */
        const val FIRST_RUN_PAGE = 20
        /** The pages of older run records behind it, at the most the API serves per page. */
        const val RUN_PAGE_SIZE = 100
        /** Room for device and server clocks to disagree when matching a send marker to a run stamp. */
        const val CLOCK_SKEW_ALLOWANCE_MS = 60_000L
        /** Pages of older run records read past the first: beyond them the oldest prompts are shown without runs. */
        const val MAX_RUN_PAGES = 8
        /** How many of the newest runs a chat opens on, and by how many the window widens each time the reader scrolls up to its end. */
        const val WINDOW_RUNS = 10
        /** The widest window the disk copy reopens on: the runs whose traces are read before the first frame. */
        const val MAX_RESTORED_WINDOW = 30
        /** A chat opened this recently is not fetched again when the app comes to the foreground. */
        const val REVALIDATE_MIN_INTERVAL_MS = 5_000L
        /** The two message types of `/v0/agents/{id}/conversation`. */
        const val USER_MESSAGE = "user_message"
        const val ASSISTANT_MESSAGE = "assistant_message"
        /** Ids of the runs local prompts are paired with until the server has answered (the same id as their message, see [LocalPrompt.filed]). */
        const val LOCAL_RUN_PREFIX = "local-"
        /** `POST /v1/agents/{id}/runs` while a run is `CREATING` or `RUNNING`. */
        const val AGENT_BUSY = "agent_busy"
    }
}
