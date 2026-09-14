package com.cursorforandroid.domain

/**
 * The redacted account of one chat's transcript as this build reads it, for a coordinator's chat that still shows
 * its notes as messages or its messages as bare rows. One line per item — its kind, and for a tool call the tool's
 * name, the kind it is filed under, its status, what payload was read off it and the names of its argument keys —
 * plus the decision that reads the chat as a coordinator's and what made it. No text of any message, prompt, note,
 * report or argument is in it, and ids are cut to their tails, so it can be sent as it is.
 */
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
        /** The chat diagnosed, or null when no chat has been opened this session. */
        val agentId: String?,
        /** The list's row for the chat, when the list holds one. */
        val agent: Agent?,
        val state: State?,
        /** The row's placement in the registry, when it has one: parent (null for a root) and signal. */
        val placement: Pair<AgentParent?, LineageSignal>? = null,
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
        appendLine("mode=${if (input.extendedMode) "extended" else "default"}")
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

    private fun StringBuilder.describe(item: TimelineItem) {
        when (item) {
            is UserMessage -> appendLine("  user chars=${item.text.length} attachments=${item.attachments.size}${if (item.isPending) " pending" else ""}")
            is AssistantMessage -> appendLine("  assistant chars=${item.markdown.length}${if (item.isStreaming) " streaming" else ""}")
            is SummaryRow -> appendLine("  summary")
            is NoticeCard -> appendLine("  notice tone=${item.tone.name}")
            is RunFooter -> appendLine("  footer status=${item.status.name} duration=${item.durationMs ?: "-"} branches=${item.branches.size}")
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

    /** One tool call, its content left out: `SendMessage · Other→Coordinator · completed · coordinator_message(missing) · args=[text] · linked=0 · truncated=-`. */
    fun describe(call: ToolCall): String {
        val read = GoalTranscript.reinterpret(CoordinatorTranscript.reinterpret(call))
        val kind = if (read.kind != call.kind) "${call.kind.name}→${read.kind.name}" else call.kind.name
        val payload = when (val p = read.payload) {
            null -> "-"
            is ToolPayload.CoordinatorMessage -> "coordinator_message" + (if (p.missing) "(missing)" else "(${p.message.length} chars)")
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
        return "${call.name.ifBlank { "<blank>" }} · $kind · ${call.status}${if (call.isError) "(error)" else ""} · $payload · args=[${call.argKeys.joinToString(",")}] · linked=${call.linkedAgentIds.size} · truncated=$truncated"
    }

    /** An error message keeps its words but not any id, URL or quoted text it might carry. */
    private fun redact(text: String): String =
        text.replace(Regex("""bc-[A-Za-z0-9-]+"""), "bc-…").replace(Regex("""https?://[^\s)"]+"""), "<url>").replace(Regex("\"[^\"]*\""), "\"…\"").take(160)
}
