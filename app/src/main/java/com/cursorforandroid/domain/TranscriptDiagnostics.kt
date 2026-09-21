package com.cursorforandroid.domain

/**
 * The redacted account of one chat's transcript as this build reads it, for a coordinator's chat that still shows
 * its notes as messages or its messages as bare rows. One line per item — its kind, and for a tool call the tool's
 * name, the kind it is filed under, its status, what payload was read off it and the names of its argument keys —
 * plus the decision that reads the chat as a coordinator's and what made it, and, in Extended mode, the shape of the
 * account's record for the chat's newest turns: every step's keys and value types and the parser branch that took
 * it (see [TurnShape]). No text of any message, prompt, note, report or argument is in it, and ids are cut to their
 * tails, so it can be sent as it is.
 */
/** The order the server listed a chat's runs in, as the load read it off the first page (see `ConversationRepository.newestRuns`). */
enum class RunOrder { NEWEST_FIRST, OLDEST_FIRST }

/**
 * The load's own account of one chat, for the transcript diagnostics: where the window sits, what of the run list is
 * in hand and in which order it came, per run of the window whether its trace is on screen and if not why, whether the
 * live run is being followed and what its stream has said, and the last failures. No content; ids are cut to tails.
 */
data class TranscriptLoadDiagnostics(
    val attached: Int,
    val paused: Boolean,
    val fetched: Boolean,
    val fetchedAtIso: String?,
    val messages: Int,
    val prompts: Int,
    val runsLoaded: Int,
    val runsComplete: Boolean,
    val hasOlderCursor: Boolean,
    val runOrder: RunOrder?,
    val latestFetchedById: Boolean,
    /** How many of the newest turns the window renders, and the turn indices it covers ([windowStart] until [chatTurns]). */
    val window: Int,
    val windowStart: Int,
    val chatTurns: Int,
    /** The window's runs, oldest first: id tail, status, and the trace's state (shown / pending / expired / failed / live / none). */
    val runs: List<RunLine>,
    val traceQueue: Int,
    val traceInFlight: Int,
    val traceWorkerRunning: Boolean,
    val expiredBeforeIso: String?,
    val expiredRuns: Int,
    val failedTraces: Int,
    /** The run the chat follows or would follow, and whether a stream is open on it. */
    val liveRunId: String?,
    val following: Boolean,
    /** What the shared stream of the live run has said so far, when one is open. */
    val liveStream: LiveStreamLine?,
    val lastError: String?,
    val transcriptError: String?,
    val transcriptUnavailable: Boolean,
    /** Where the transcript comes from: `record` (the account's own record, Extended mode) or `runs` (`/v0` text over `/v1` runs). */
    val source: String = "runs",
    /** The account's record as far as it was read, when the chat has been asked for it (Extended mode). */
    val record: RecordLine? = null,
    /** The chat's status as shown, and the words it was reconciled from (see `ConversationRepository.Entry.chatStatus`). */
    val status: StatusLine? = null,
    /** How the transcript's prompts were paired with the runs on the documented path (see `TurnPairing`); null on the record path. */
    val pairing: PairingLine? = null,
    /**
     * The record's newest turns as this session read them, step by step — keys and value types, the parser branch
     * that took each step, how each call's arguments came together — and never a value (see [TurnShape]). What says
     * whether a coordinator's messages are in the record at all, and in what shape, when they are not on screen.
     */
    val shapes: List<TurnShape> = emptyList(),
) {
    /**
     * [shown] is the status the screen has; [latestRun] the latest run record's; [streaming] whether a stream is open
     * on it; [rowRunning] the row's word; [accountRunning] the account list's running set's (Extended mode);
     * [rowNewerThanRecordMs] how much newer the row's activity is than the record (negative: older). [failure] is
     * why the chat reads as failed when it does — or why its newest run does — see [FailureLine].
     */
    /**
     * The documented path's pairing of prompts with runs: how many prompts there are and how many runs, how many
     * turns each kind of evidence settled — the time an injected turn carries, this device's own echoes, the turn
     * under way, a run's result, position — how many runs no prompt started ([promptless]) and how many prompts
     * have no run in hand ([runless]).
     */
    data class PairingLine(val prompts: Int, val runs: Int, val timestamp: Int, val echo: Int, val live: Int, val result: Int, val position: Int, val promptless: Int, val runless: Int) {
        val text: String get() = "pairing: prompts=$prompts runs=$runs timestamp=$timestamp echo=$echo live=$live result=$result position=$position promptless=$promptless runless=$runless"
    }

    data class StatusLine(
        val shown: String,
        val latestRun: String,
        val streaming: Boolean,
        val rowRunning: Boolean,
        val accountRunning: Boolean,
        val rowNewerThanRecordMs: Long?,
        val failure: FailureLine? = null,
        /** When the account last named this composer's status (the reading [accountRunning] is), so the `send:` line's older reading is read against it. */
        val accountAtIso: String? = null,
    )

    /**
     * The failure the chat shows, and where the word came from: [runIdTail] the run the server says failed; [source]
     * which of the server's words said so — `stream` (the run's own `result` event), `run-record` (`GET …/runs/{id}`),
     * `account-record` (the account's transcript, `FetchBackgroundComposer`, for the reason) — with `+account-record`
     * when the reason came from the account's transcript while the status came from the run's record; [reason] the
     * server's reason as shown (redacted in the report); [current] whether the conversation has moved past it
     * (false: demoted to a line inside its stretch; true: the row of its own and the chat's status).
     */
    data class FailureLine(val runIdTail: String, val source: String, val reason: String?, val current: Boolean) {
        val text: String get() = "run=$runIdTail source=$source current=$current reason=${reason?.let { "\"$it\"" } ?: "-"}"
    }

    /**
     * One run of the window (a record turn with the run it pairs with, in Extended mode). [message] is the
     * coordinator's word to the user through the turn's stages, for a coordinator's chat: what the record has of it
     * (`body`, `recovered`, `missing`, `none`), whether its result was recorded, and what reached the screen (`yes`,
     * `missing`, `none`) from which source (`record`, `log`, `live`) — which says where a reply the reader cannot see
     * was lost. Null for a chat that is not a coordinator's.
     */
    data class RunLine(val idTail: String, val status: String, val trace: String, val items: Int, val message: String? = null)

    /**
     * The record: its size in steps, where the loaded steps begin, how many turns are loaded of how many the account
     * says the chat has, whether the state was read, whether the record answered with nothing, and the last failure.
     */
    data class RecordLine(
        val total: Int,
        val firstStep: Int,
        val turnsLoaded: Int,
        val turnCount: Int?,
        val stateRead: Boolean,
        val empty: Boolean,
        val error: String?,
        val fallback: FallbackLine? = null,
        /** How the record is read: `turns` (the blob-backed record, `GetLatestAgentConversationState` + `GetBlobForAgentKV`) or `steps` (`FetchBackgroundComposer`). */
        val read: String = "steps",
    )

    /**
     * The record refused or failed with nothing of it on screen and the documented path stands in (see
     * `ConversationState.recordFallback`): since when, how long the read took, the pause the server named, and until
     * when the record is left alone.
     */
    data class FallbackLine(
        val sinceIso: String,
        val readMs: Long,
        val retryAfterMs: Long?,
        val refusedUntilIso: String?,
        /** The request path the refused read was made on, as sent, and what the server answered — so a casing or a routing question is settled from the export alone. */
        val path: String? = null,
        val httpCode: Int? = null,
        val code: String? = null,
    ) {
        val text: String get() = "fallback=runs since=$sinceIso readMs=$readMs retryAfterMs=${retryAfterMs ?: "-"} refusedUntil=${refusedUntilIso ?: "-"} asked=${path?.let { "POST $it" } ?: "-"} http=${httpCode ?: "-"} code=${code ?: "-"}"
    }

    data class LiveStreamLine(val events: Int, val status: String, val reconnecting: Boolean, val expired: Boolean, val finished: Boolean, val items: Int)
}

object TranscriptDiagnostics {

    /** The conversation's state, in the facts the report prints. */
    class State(
        val items: List<TimelineItem>,
        val isLoading: Boolean = false,
        val isStreaming: Boolean = false,
        val isReconnecting: Boolean = false,
        val runStatus: RunStatus? = null,
        val hasOlder: Boolean = false,
        val transcriptUnavailable: Boolean = false,
        /** The account's record said a prompt was sent in Project mode (Extended mode). */
        val recordProjectMode: Boolean = false,
        val error: String? = null,
    )

    class Input(
        val appVersion: String,
        val nowIso: String,
        val extendedMode: Boolean,
        /** The transcript engine chosen in Settings (see [TranscriptEngine]); what `engine=` in the export says. */
        val engine: TranscriptEngine = TranscriptEngine.DEFAULT,
        /** The chat diagnosed, or null when no chat has been opened this session. */
        val agentId: String?,
        /** The list's row for the chat, when the list holds one. */
        val agent: Agent?,
        val state: State?,
        /** The row's placement in the registry, when it has one: parent (null for a root) and signal. */
        val placement: Pair<AgentParent?, LineageSignal>? = null,
        /** The load's own account of the chat (see [TranscriptLoadDiagnostics]); null when the chat has no entry. */
        val load: TranscriptLoadDiagnostics? = null,
        /** What the pipeline cost since the chat was opened (see [TranscriptPerf]); null when it was never opened this process. */
        val perf: TranscriptPerf.Snapshot? = null,
        /** The send path's account of the chat (see [SendDiagnostics]); null when nothing was sent or queued from here. */
        val send: SendDiagnostics? = null,
    )

    /** The decision the conversation screen makes, spelled out: which of its three words fired. */
    class Decision(val listProject: Boolean, val recordProjectMode: Boolean, val content: Boolean, val evidence: List<String>) {
        val coordinatorMode: Boolean get() = listProject || recordProjectMode || content
    }

    fun decide(agent: Agent?, items: List<TimelineItem>, recordProjectMode: Boolean): Decision = Decision(
        listProject = agent?.looksLikeProject == true,
        recordProjectMode = recordProjectMode,
        content = CoordinatorTranscript.hasCoordinatorContent(items),
        evidence = CoordinatorTranscript.evidence(items),
    )

    fun render(input: Input): String = buildString {
        appendLine("Cursor for Android ${input.appVersion} · transcript diagnostics · ${input.nowIso}")
        appendLine("mode=${if (input.extendedMode) "extended" else "default"} engine=${input.engine.key}")
        val id = input.agentId
        if (id == null) {
            appendLine("chat: none opened this session")
            return@buildString
        }
        val agent = input.agent
        val state = input.state
        appendLine(
            "chat=${ProjectDiagnostics.tail(id)} row=${if (agent == null) "not in list" else "loaded"}" +
                (agent?.let { " isProject=${it.isProject} isProjectRoot=${it.isProjectRoot} looksLikeProject=${it.looksLikeProject} scope=${it.scope.name} signal=${it.scopeSignal?.name ?: "-"} source=${it.source?.name ?: "-"} parent=${it.parent?.let { p -> "${ProjectDiagnostics.tail(p.id)}/${p.kind.name}" } ?: "-"}" } ?: "") +
                (input.placement?.let { (parent, signal) -> " registry=${parent?.let { p -> ProjectDiagnostics.tail(p.id) } ?: "root"}/${signal.name}" } ?: ""),
        )
        if (state == null) {
            appendLine("state: none (the chat has not been loaded)")
            return@buildString
        }
        appendLine("state: items=${state.items.size} loading=${state.isLoading} streaming=${state.isStreaming} reconnecting=${state.isReconnecting} run=${state.runStatus?.name ?: "-"} hasOlder=${state.hasOlder} transcriptUnavailable=${state.transcriptUnavailable} recordProjectMode=${state.recordProjectMode}" + (state.error?.let { " error=\"${redact(it)}\"" } ?: ""))
        input.load?.let { describe(it) }
        // Where the time went: the open's moments, the publications and what the presenter, the markdown cache, the
        // disk and the network cost for them (see [TranscriptPerf.Snapshot.render]).
        input.perf?.let { appendLine(it.render()) }
        // The send path: the last send-or-queue decision with every input it read, the queue, and each attempt's outcome.
        input.send?.let { append(it.render()) }
        val decision = decide(agent, state.items, state.recordProjectMode)
        appendLine("classification: ${if (decision.coordinatorMode) "COORDINATOR" else "agent"} listProject=${decision.listProject} recordProjectMode=${decision.recordProjectMode} content=${decision.content}" + (if (decision.evidence.isNotEmpty()) " evidence=${decision.evidence.joinToString(",")}" else ""))
        val presented = CoordinatorTranscript.present(state.items, decision.coordinatorMode)
        val rows = TranscriptRows.of(presented, decision.coordinatorMode, runActive = state.isStreaming || state.runStatus?.isActive == true)
        appendLine(
            "presented: items=${presented.size} folded=${state.items.size - presented.size} staleMessages=${state.items.count { it is ActivityGroup && CoordinatorTranscript.needsRefresh(listOf(it)) }}" +
                " rows=${rows.size} stretches=${rows.count { it is TranscriptRow.Stretch && it.single == null }} messages=${rows.count { it is TranscriptRow.Message }}",
        )
        appendLine()
        appendLine("items (kind · facts; tool calls: name · kind · status · payload · argKeys · linked · truncated):")
        state.items.forEach { item -> describe(item) }
    }

    /**
     * The load's lines: the window's bounds over the chat's turns, the run list in hand and its order, per run of the
     * window whether its trace is on screen, the live follow, and the last failures — what tells a chat that opened
     * with its text and no tool calls, or with its running turn read as over, from one that loaded whole.
     */
    private fun StringBuilder.describe(load: TranscriptLoadDiagnostics) {
        appendLine(
            "load: source=${load.source} attached=${load.attached} paused=${load.paused} fetched=${load.fetched} fetchedAt=${load.fetchedAtIso ?: "-"} messages=${load.messages} prompts=${load.prompts}" +
                " runs=${load.runsLoaded} complete=${load.runsComplete} olderCursor=${load.hasOlderCursor} order=${load.runOrder?.name ?: "-"} latestById=${load.latestFetchedById}" +
                " window=${load.window} turns=[${load.windowStart},${load.chatTurns})",
        )
        load.pairing?.let { appendLine(it.text) }
        load.record?.let { r ->
            appendLine("record: read=${r.read} total=${r.total} firstStep=${r.firstStep} turnsLoaded=${r.turnsLoaded} turnCount=${r.turnCount ?: "-"} state=${if (r.stateRead) "read" else "-"} empty=${r.empty}" + (r.error?.let { " error=\"${redact(it)}\"" } ?: "") + (r.fallback?.let { " ${it.text}" } ?: ""))
        }
        load.status?.let { st ->
            appendLine(
                "status: shown=${st.shown} latestRun=${st.latestRun} streaming=${st.streaming} rowRunning=${st.rowRunning} accountRunning=${st.accountRunning}${st.accountAtIso?.let { "@$it" } ?: ""} rowNewerThanRecordMs=${st.rowNewerThanRecordMs ?: "-"}" +
                    (st.failure?.let { f -> " failed: ${f.copy(reason = f.reason?.let(::redact)).text}" } ?: ""),
            )
        }
        val shown = load.runs.count { it.trace.startsWith("shown") }
        appendLine(
            "traces: shown=$shown of ${load.runs.count { it.trace != "live" }} queue=${load.traceQueue} inFlight=${load.traceInFlight} worker=${load.traceWorkerRunning} expiredRuns=${load.expiredRuns} expiredBefore=${load.expiredBeforeIso ?: "-"} failed=${load.failedTraces}",
        )
        load.runs.forEach { appendLine("  run ${it.idTail} ${it.status} trace=${it.trace} items=${it.items}" + (it.message?.let { m -> " sendMessage: $m" } ?: "")) }
        appendLine(
            "live: run=${load.liveRunId?.let { ProjectDiagnostics.tail(it) } ?: "-"} following=${load.following}" +
                (load.liveStream?.let { " stream=events:${it.events},status:${it.status},reconnecting:${it.reconnecting},expired:${it.expired},finished:${it.finished},items:${it.items}" } ?: " stream=none"),
        )
        appendLine("errors: last=${load.lastError?.let { "\"${redact(it)}\"" } ?: "-"} transcript=${load.transcriptError?.let { "\"${redact(it)}\"" } ?: "-"} transcriptUnavailable=${load.transcriptUnavailable}")
        if (load.shapes.isNotEmpty()) describe(load.shapes)
    }

    /**
     * The shape dump: the record's newest turns, one line per step — its index, the parser branch that consumed it,
     * its keys with their value types (see `RecordShapes` for the grammar) — then one line per tool call with how the
     * replay resolved it. Nothing anyone wrote is in it: strings are lengths, ids are tails, and the only values shown
     * are enum-like tokens under the keys that name a kind of thing (the tool's name above all).
     */
    private fun StringBuilder.describe(shapes: List<TurnShape>) {
        appendLine()
        append(renderShapes(shapes, "newest ${shapes.size} turns of the record"))
    }

    /**
     * The shape dump on its own, for [describe] and for the verification harness (`tools/transcript-verify`), which
     * dumps every turn it read rather than the newest few: [scope] is the words after `shapes:` that say which turns.
     */
    fun renderShapes(shapes: List<TurnShape>, scope: String): String = buildString {
        appendLine("shapes: $scope, oldest first (step: index · branch · keys:types; call: id · name · steps · args · result):")
        for (turn in shapes) {
            // The coordinator's word to the user as the record gave it: with its body, read leniently, in pieces that
            // never read, without arguments — or no such call in the record at all, which is what the run's log then answers for.
            val messages = turn.calls.filter { ToolNames.coordinatorTool(it.name) == ToolNames.USER_MESSAGE_TOOL }
            val stage = when {
                messages.isEmpty() -> "none"
                messages.any { it.args == "json" || it.args == "value" || it.args.startsWith("joined") } -> "body"
                messages.any { it.args.startsWith("recovered") } -> "recovered"
                messages.any { it.args.startsWith("partial") } -> "partial"
                else -> "missing"
            }
            val result = if (messages.isEmpty()) "" else " result=${if (messages.any { it.result }) "yes" else "no"}"
            appendLine("turn@${turn.stepIndex} steps=${turn.steps.size} prompt=${turn.prompt} project=${turn.projectMode} calls=${turn.calls.size} sendMessage=$stage$result")
            turn.steps.take(MAX_SHAPE_STEPS).forEach { step -> appendLine("  ${step.index} ${step.branch} ${step.keys}") }
            if (turn.steps.size > MAX_SHAPE_STEPS) appendLine("  … +${turn.steps.size - MAX_SHAPE_STEPS} more steps")
            turn.calls.forEach { call -> appendLine("  call ${call.idTail} ${call.name.ifBlank { "<blank>" }} steps=${call.steps} args=${call.args} result=${call.result}") }
        }
    }

    /** Step lines per turn in the shape dump; a turn with more says how many were left out. */
    const val MAX_SHAPE_STEPS = 160

    private fun StringBuilder.describe(item: TimelineItem) {
        when (item) {
            is UserMessage -> appendLine("  user chars=${item.text.length} attachments=${item.attachments.size}${if (item.isPending) " pending" else ""}")
            is AssistantMessage -> appendLine("  assistant chars=${item.markdown.length}${if (item.isStreaming) " streaming" else ""}")
            is SummaryRow -> appendLine("  summary")
            is NoticeCard -> appendLine("  notice tone=${item.tone.name}")
            is RunFooter -> appendLine("  footer status=${item.status.name} duration=${item.durationMs ?: "-"} branches=${item.branches.size}" + (item.reason?.let { " reasonChars=${it.length}" } ?: ""))
            is SystemNotification -> appendLine(
                "  notification kind=${item.kind.name} title=\"${item.title}\" tone=${item.tone.name} summaryChars=${item.summary?.length ?: 0} bodyChars=${item.body?.length ?: 0}" +
                    (item.agentId?.let { " agent=${ProjectDiagnostics.tail(it)}" } ?: "") + (item.narration?.let { " narrationChars=${it.length}" } ?: ""),
            )
            is ActivityGroup -> {
                appendLine("  activity steps=${item.steps.size} coordination=${item.isCoordination} grouped=${item.isWorkGrouped}")
                item.steps.forEach { step ->
                    when (step) {
                        is ThinkingBlock -> appendLine("    thinking chars=${step.text.length}${if (step.isStreaming) " streaming" else ""}")
                        is ToolCall -> appendLine("    tool ${describe(step)}")
                    }
                }
            }
        }
    }

    /** One tool call, its content left out: `SendMessage · Other→Coordinator · completed · coordinator_message(missing) · args=[text] · linked=0 · truncated=- · id=…SendA`. */
    fun describe(call: ToolCall): String {
        val read = GoalTranscript.reinterpret(CoordinatorTranscript.reinterpret(call))
        val kind = if (read.kind != call.kind) "${call.kind.name}→${read.kind.name}" else call.kind.name
        val payload = when (val p = read.payload) {
            null -> "-"
            is ToolPayload.CoordinatorMessage -> "coordinator_message" + (if (p.missing) "(missing)" else "(${p.message.length} chars${if (p.recovered) ",recovered" else ""})")
            is ToolPayload.GoalChange -> "goal(${p.action.name.lowercase()}" + (p.status?.let { ",$it" } ?: "") + (if (p.missing) ",missing" else "") + (if (p.error != null) ",error" else "") + ")"
            is ToolPayload.WorkerAction -> "worker_action(${p.kind.name.lowercase()},workers=${p.workers.size},reported=${p.reported})"
            is ToolPayload.FileDiff -> "diff"
            is ToolPayload.FileContent -> "file(${p.kind.name.lowercase()})"
            is ToolPayload.GeneratedImage -> "image(src=${p.src != null})"
            is ToolPayload.Recording -> "recording"
            is ToolPayload.Subagent -> "subagent(cloud=${p.isCloudAgent})"
            is ToolPayload.Question -> "question(${p.questions.size},answered=${p.isAnswered})"
        }
        val truncated = call.truncated?.let { t -> listOfNotNull("args".takeIf { t.args }, "result".takeIf { t.result }).joinToString("+") } ?: "-"
        // The id's tail joins the line to the shape dump's call line for the same call.
        return "${call.name.ifBlank { "<blank>" }} · $kind · ${call.status}${if (call.isError) "(error)" else ""} · $payload · args=[${call.argKeys.joinToString(",")}] · linked=${call.linkedAgentIds.size} · truncated=$truncated · id=${ProjectDiagnostics.tail(call.callId)}"
    }

    /** An error message keeps its words but not any id, URL or quoted text it might carry. */
    private fun redact(text: String): String =
        text.replace(Regex("""bc-[A-Za-z0-9-]+"""), "bc-…").replace(Regex("""https?://[^\s)"]+"""), "<url>").replace(Regex("\"[^\"]*\""), "\"…\"").take(160)
}
