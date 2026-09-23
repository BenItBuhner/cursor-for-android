package com.cursorforandroid.domain

/**
 * A subagent as the row Cursor's desktop draws for one (the glass build's `SubagentTaskCard`, Cursor 3.21.18): a
 * `task` the agent delegated, and a Project coordinator's `create_agent` or `send_to_agent` — which the desktop
 * projects onto the same task card (`coordinatorAgentTaskProjection`) — or `stop_agent`, drawn here the same way.
 *
 * [title] is the desktop's `description`: the task's; a worker's name, else the first line of its prompt cut to
 * [TITLE_MAX]; a message's title, else [FOLLOW_UP]. Null when the call gave none: the row then reads the child's
 * name, else [NEW_SUBAGENT]. [modelId] is the model the call asked for, [environment] where the child was asked
 * to run, when the call said.
 */
data class SubagentCall(
    val source: Source,
    val title: String?,
    val agentId: String? = null,
    val modelId: String? = null,
    val subagentType: String? = null,
    val environment: SubagentPlacement? = null,
    val prompt: String? = null,
) {
    /** Which call the row stands for: a task, a worker created, messaged (steered, queued, or neither said), or stopped. */
    enum class Source { Task, Created, Steered, Queued, Messaged, Stopped }

    /** A Project's worker rather than a task's subagent: it runs in a VM of its own, and a later call may address it again. */
    val isWorker: Boolean get() = source != Source.Task

    /** True when [agentId] names a cloud agent this app can open (`bc-…`). */
    val isCloudAgent: Boolean get() = agentId?.startsWith("bc-") == true

    companion object {
        /** The desktop's title for a worker cancelled before it started. */
        const val NEW_AGENT = "New Agent"
        const val NEW_SUBAGENT = "New subagent"
        const val FOLLOW_UP = "Agent follow-up"
        /** Ours: a stop names the worker; with no name for it, this. */
        const val AGENT = "Agent"
        const val TITLE_MAX = 80

        /**
         * The row [call] is drawn as, or null for a call that is not a subagent's. A call that failed carries no
         * payload (see `ToolCallMapper`), so it is read off the call's name and summary instead.
         */
        fun of(call: ToolCall): SubagentCall? {
            when (val payload = call.payload) {
                is ToolPayload.Subagent -> return SubagentCall(
                    source = Source.Task,
                    title = payload.description?.trim()?.takeIf { it.isNotEmpty() },
                    agentId = payload.agentId?.takeIf { it.isNotBlank() },
                    modelId = payload.model?.trim()?.takeIf { it.isNotEmpty() },
                    subagentType = payload.subagentType,
                    environment = SubagentPlacement.parse(payload.environment),
                )
                is ToolPayload.WorkerAction -> return ofWorker(payload)
                null -> Unit
                else -> return null
            }
            if (call.kind == ToolKind.Task) return SubagentCall(Source.Task, call.summary.trim().takeIf { it.isNotEmpty() && it != "subagent" })
            val summary = call.summary.trim().takeIf { it.isNotEmpty() }
            val agentId = summary?.takeIf { it.startsWith("bc-") } ?: call.linkedAgentIds.firstOrNull()
            return when (ToolNames.coordinatorTool(call.name)) {
                "create_agent" -> SubagentCall(Source.Created, summary?.takeUnless { it.startsWith("bc-") }?.let(::capped), agentId = agentId)
                "send_to_agent" -> SubagentCall(Source.Messaged, FOLLOW_UP, agentId = agentId)
                "stop_agent" -> SubagentCall(Source.Stopped, null, agentId = agentId)
                else -> null
            }
        }

        private fun ofWorker(action: ToolPayload.WorkerAction): SubagentCall? {
            val agentId = action.worker?.agentId?.takeIf { it.isNotBlank() }
            val placement = if (action.workerId != null) SubagentPlacement.Machine else SubagentPlacement.Cloud
            return when (action.kind) {
                ToolPayload.WorkerAction.Kind.Created -> SubagentCall(
                    source = Source.Created,
                    title = action.title?.trim()?.takeIf { it.isNotEmpty() } ?: action.text?.let(::firstLine)?.takeIf { it.isNotEmpty() }?.let(::capped),
                    agentId = agentId,
                    modelId = action.model?.trim()?.takeIf { it.isNotEmpty() },
                    environment = placement,
                    prompt = action.text,
                )
                ToolPayload.WorkerAction.Kind.Messaged -> SubagentCall(
                    source = when (action.delivery) {
                        ToolPayload.WorkerAction.Delivery.Followup -> Source.Steered
                        ToolPayload.WorkerAction.Delivery.Queue -> Source.Queued
                        null -> Source.Messaged
                    },
                    title = action.title?.trim()?.takeIf { it.isNotEmpty() } ?: FOLLOW_UP,
                    agentId = agentId,
                    environment = SubagentPlacement.Cloud,
                    prompt = action.text,
                )
                ToolPayload.WorkerAction.Kind.Stopped -> SubagentCall(Source.Stopped, null, agentId = agentId, environment = SubagentPlacement.Cloud)
                ToolPayload.WorkerAction.Kind.Status, ToolPayload.WorkerAction.Kind.ReadTranscript -> null
            }
        }

        private fun firstLine(text: String): String = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()

        /** The desktop's `QnS`: a prompt's first line, 79 characters and an ellipsis past [TITLE_MAX]. */
        internal fun capped(text: String): String = if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX - 1) + "\u2026"
    }
}

/** Where a subagent runs, as the row's glyph says it: a VM of its own in the cloud, this machine, or a self-hosted one. */
enum class SubagentPlacement {
    Cloud, Local, Machine;

    companion object {
        /**
         * The task's `environment` (`agent.v1.SubagentExecutionEnvironment`) as the streams spell it — by name
         * (`SUBAGENT_EXECUTION_ENVIRONMENT_CLOUD`, `cloud`) or, off the record, by number (1 local, 2 cloud); null
         * for anything else.
         */
        fun parse(raw: String?): SubagentPlacement? {
            val word = raw?.trim()?.lowercase() ?: return null
            return when {
                word == "1" -> Local
                word == "2" -> Cloud
                word.endsWith("cloud") -> Cloud
                word.endsWith("local") -> Local
                word.endsWith("self_hosted") || word.endsWith("machine") || word.endsWith("worker") -> Machine
                else -> null
            }
        }

        /** Where an agent of the list runs: a self-hosted machine, or the cloud (a pool is the cloud's too). */
        fun of(env: EnvType?): SubagentPlacement? = when (env) {
            EnvType.CLOUD, EnvType.POOL -> Cloud
            EnvType.MACHINE -> Machine
            EnvType.UNKNOWN, null -> null
        }
    }
}

/** The model a subagent runs on, as the row names it: the catalog's name, and whether Fast is on. */
data class SubagentModel(val label: String, val fast: Boolean = false)

/**
 * What is known of a subagent beyond the call that started it: where its run stands, the step it last announced
 * (`communicate_update`), the action it is on, whether it waits on the reader, its own name and model. Read from the
 * list's row and live stream of a cloud child, and from the account's record of an in-VM one (Extended mode).
 */
data class SubagentChild(
    val status: Status? = null,
    val step: String? = null,
    val action: String? = null,
    val waiting: Boolean = false,
    val name: String? = null,
    val model: SubagentModel? = null,
) {
    /** The desktop's subagent run states (`agent.v1.SubagentRunStatus`), a backgrounded run being a running one. */
    enum class Status { Running, Succeeded, Failed, Aborted }
}

/**
 * The row's state as the desktop draws it: the indicator in its slot, the line under the title, whether that line
 * shimmers, and whether it reads as a call for attention.
 */
data class SubagentLook(
    val indicator: Indicator,
    val status: String,
    val active: Boolean = false,
    val dimmed: Boolean = false,
    val attention: Boolean = false,
    /** Set when the row's title is the desktop's for a worker that never started, whatever the call named. */
    val title: String? = null,
) {
    /** The desktop's `XE1` modes: the dot grid while running, a grey dot once done, red for an error, yellow while waiting. */
    enum class Indicator { Running, Finished, Error, Attention }
}

/**
 * The desktop's rules for a subagent's row (`SubagentTaskCard`, `subagentTaskState`, `subagentTaskStatusLine`), and
 * the child's current action read off its transcript (`$0i`).
 */
object SubagentRows {
    const val PLANNING = "Planning next moves"
    const val THINKING = "Thinking"
    const val STARTING = "Starting up"
    const val COMPLETED = "Completed"
    const val STOPPED = "Stopped"
    const val STOPPED_WITH_ERROR = "Stopped with error"
    const val COULD_NOT_START = "Couldn't start"
    const val CANCELLED = "Cancelled"
    /** Ours, where the desktop has no row: a stop under way, and one that failed. */
    const val STOPPING = "Stopping"
    const val COULD_NOT_STOP = "Couldn't stop"
    /** Ours: a cloud child waiting on the reader (a question), where the desktop's local child waits on an approval. */
    const val WAITING = "Waiting for input"
    /** How much of the child's reply the action line carries (`$0i`). */
    const val REPLY_CHARS = 50

    /**
     * The row's state. [latest] is false for a worker's row a later row addresses again: its turn is over as far as
     * this transcript can tell, so it settles, as the desktop's rows do per run. [child] is what is known of the child
     * beyond the call; without it the call alone says where things stand.
     */
    fun look(call: ToolCall, subagent: SubagentCall, child: SubagentChild?, latest: Boolean = true): SubagentLook {
        val interrupted = call.status == ToolCall.STATUS_INTERRUPTED
        if (subagent.source == SubagentCall.Source.Stopped) {
            return when {
                call.isRunning -> SubagentLook(SubagentLook.Indicator.Running, STOPPING, active = true)
                call.isError -> SubagentLook(SubagentLook.Indicator.Error, COULD_NOT_STOP)
                else -> SubagentLook(SubagentLook.Indicator.Finished, STOPPED, dimmed = true)
            }
        }
        if (subagent.isWorker) {
            // The call itself stands for the child's start: until it returns there is no worker, or no message delivered.
            if (call.isRunning && (subagent.agentId == null || child?.status == null)) return SubagentLook(SubagentLook.Indicator.Running, STARTING, active = true)
            if (call.isError) return SubagentLook(SubagentLook.Indicator.Finished, COULD_NOT_START, dimmed = true)
            if (interrupted && subagent.agentId == null) {
                return SubagentLook(SubagentLook.Indicator.Error, CANCELLED, title = if (subagent.source == SubagentCall.Source.Created) SubagentCall.NEW_AGENT else null)
            }
            if (!latest) return SubagentLook(SubagentLook.Indicator.Finished, COMPLETED)
        }
        if (child != null) {
            if (child.waiting) return SubagentLook(SubagentLook.Indicator.Attention, WAITING, attention = true)
            when (child.status) {
                SubagentChild.Status.Running -> return running(child)
                SubagentChild.Status.Succeeded -> return SubagentLook(SubagentLook.Indicator.Finished, COMPLETED)
                SubagentChild.Status.Failed -> return SubagentLook(SubagentLook.Indicator.Error, STOPPED_WITH_ERROR)
                SubagentChild.Status.Aborted -> return SubagentLook(SubagentLook.Indicator.Finished, STOPPED, dimmed = true)
                null -> Unit
            }
        }
        return when {
            call.isRunning -> running(child)
            interrupted -> SubagentLook(SubagentLook.Indicator.Finished, STOPPED, dimmed = true)
            call.isError -> SubagentLook(SubagentLook.Indicator.Error, STOPPED_WITH_ERROR)
            else -> SubagentLook(SubagentLook.Indicator.Finished, COMPLETED)
        }
    }

    /** The desktop's status text while the child works (`gRa`): its own step, else its latest action, else planning. */
    private fun running(child: SubagentChild?): SubagentLook {
        val text = child?.step?.trim()?.takeIf { it.isNotEmpty() } ?: child?.action?.trim()?.takeIf { it.isNotEmpty() } ?: PLANNING
        return SubagentLook(SubagentLook.Indicator.Running, text, active = true)
    }

    /**
     * The child's current action, read newest first the way the desktop reads its bubbles (`$0i`): a thought is
     * "Thinking"; a to-do update its "Completed 2 of 5"; any other tool its verb and subject ("Editing Timeline.kt",
     * "Searching web compose shimmer") when it has a subject, else the reading goes on to the step before; a reply its
     * first [REPLY_CHARS] characters. Null when nothing in [items] says.
     */
    fun actionOf(items: List<TimelineItem>): String? {
        for (i in items.indices.reversed()) {
            when (val item = items[i]) {
                is ActivityGroup -> {
                    for (j in item.steps.indices.reversed()) {
                        when (val step = item.steps[j]) {
                            is ThinkingBlock -> return THINKING
                            is ToolCall -> actionOf(step)?.let { return it }
                        }
                    }
                }
                is AssistantMessage -> plain(item.markdown)?.let { return it }
                is UserMessage -> return null
                else -> Unit
            }
        }
        return null
    }

    /** The step the child last announced (`communicate_update`'s `current_step`), newest first; null when it announced none. */
    fun stepOf(items: List<TimelineItem>): String? {
        for (i in items.indices.reversed()) {
            val group = items[i] as? ActivityGroup ?: continue
            for (j in group.calls.indices.reversed()) {
                val call = group.calls[j]
                if (isStepUpdate(call.name)) call.summary.trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /** Whether [name] is the tool an agent announces its current step with (`communicate_update`, `update_current_step`). */
    fun isStepUpdate(name: String): Boolean {
        val n = name.trim().lowercase().removeSuffix("toolcall").removeSuffix("_tool_call").replace("_", "")
        return n == "communicateupdate" || n == "updatecurrentstep"
    }

    private fun actionOf(call: ToolCall): String? {
        if (isStepUpdate(call.name)) return null
        val labels = call.labels ?: ToolNames.labels(call.kind, call.name)
        return when (call.kind) {
            ToolKind.Todo -> if (call.isRunning) null else labels.completed.takeIf { it.startsWith("Completed") }
            // The desktop gives these no subject: a command, an MCP tool, a delegation, a question, a coordinator's call.
            ToolKind.Shell, ToolKind.Mcp, ToolKind.McpTools, ToolKind.Task, ToolKind.Question, ToolKind.Coordinator, ToolKind.Lints, ToolKind.Plan, ToolKind.Other -> null
            else -> call.summary.trim().takeIf { it.isNotEmpty() }?.let { "${labels.loading} $it" }
        }
    }

    /** A reply's first words as plain text: markdown's marks dropped, lines joined, cut to [REPLY_CHARS]. */
    private fun plain(markdown: String): String? {
        val text = markdown
            .replace(MARKDOWN_LINK, "$1")
            .replace(MARKDOWN_MARKS, "")
            .replace(WHITESPACE, " ")
            .trim()
        if (text.isEmpty()) return null
        return if (text.length <= REPLY_CHARS) text else text.take(REPLY_CHARS)
    }

    private val MARKDOWN_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val MARKDOWN_MARKS = Regex("(^|\\s)#{1,6}\\s|[*_`>~]|^\\s*[-+]\\s", RegexOption.MULTILINE)
    private val WHITESPACE = Regex("\\s+")

    // -- the model's label --------------------------------------------------------------------------------------

    /** Built-in subagent types the desktop names by their type rather than their model (`c7p`). */
    private val TYPE_LABELLED = setOf("explore", "computeruse", "videoreview", "watchvideo", "browser-use", "pastconversationexplorer")
    /** Subagent types whose row names no model at all (`u7p`). */
    private val UNLABELLED = setOf("bugbot", "security-review")
    private val TYPE_ALIASES = mapOf("mediaReview" to "videoReview", "bash" to "shell", "browserUse" to "browser-use")

    /**
     * What the row's muted label says after the title (the desktop's `a7p`): nothing for a bugbot or security
     * review; the type's name for a built-in type ("Explorer", "Computer Use"); else the model — the one the call
     * asked for ([SubagentCall.modelId], or [workerModelId] from the call that created the worker), resolved against
     * the catalog, else the one the child runs on.
     */
    fun modelLabel(subagent: SubagentCall, models: List<ModelOption>, workerModelId: String? = null, child: Agent? = null, childModel: SubagentModel? = null): SubagentModel? {
        subagent.subagentType?.trim()?.takeIf { it.isNotEmpty() }?.let { type ->
            val key = (TYPE_ALIASES[type] ?: type).trim().lowercase().replace('_', '-')
            if (key in UNLABELLED) return null
            if (key in TYPE_LABELLED) return SubagentModel(typeName(type))
        }
        val id = subagent.modelId ?: workerModelId
        if (id != null) return modelOf(models, id)
        childModel?.let { return it }
        if (child != null) {
            val current = ModelResolution.forChat(child, models)
            if (!current.isAssumed) return SubagentModel(current.label, fast = current.choice?.params?.isFast() ?: child.modelParams.isFast())
        }
        return null
    }

    /** [id] as the catalog names it, Fast read off the variant it resolves to or, for one it cannot place, off the id's words. */
    fun modelOf(models: List<ModelOption>, id: String, params: List<ModelParam> = emptyList()): SubagentModel {
        val choice = ModelSlugs.resolve(models, id, params)
        if (choice != null) return SubagentModel(choice.label, choice.params.isFast())
        val qualifier = ModelSlugs.readableQualifier(models, id).orEmpty()
        return SubagentModel(ModelSlugs.readableName(models, id), fast = qualifier.split(" \u00B7 ").any { it.equals("Fast", ignoreCase = true) } || params.isFast())
    }

    private fun List<ModelParam>.isFast(): Boolean = any { it.id.equals("fast", ignoreCase = true) && it.value.equals("true", ignoreCase = true) }

    /** The desktop's `FnS`: "explore" is "Explorer", "computerUse" "Computer Use", anything else its words capitalised. */
    private fun typeName(type: String): String {
        val name = TYPE_ALIASES[type.trim()] ?: type.trim()
        return when (name) {
            "computerUse" -> "Computer Use"
            "explore" -> "Explorer"
            else -> name.replace(Regex("([a-z])([A-Z])"), "$1-$2").split('-', '_').filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
        }
    }

    // -- the placement glyph ------------------------------------------------------------------------------------

    /**
     * The glyph after the model (the desktop's `Bpg`). A Project's worker runs in a VM of its own, so it shows where:
     * the cloud, or the self-hosted machine it was sent to. A task asked to run locally runs in its parent's own VM
     * and shows nothing; one asked to run in the cloud gets a new VM and shows the cloud; one that said neither shows
     * its place only when that is not where [parent] runs (the desktop's `Fpg`).
     */
    fun placement(subagent: SubagentCall, parent: SubagentPlacement?, child: Agent? = null): SubagentPlacement? {
        val childPlace = SubagentPlacement.of(child?.envType)
        if (subagent.isWorker) return childPlace ?: subagent.environment ?: SubagentPlacement.Cloud
        return when (subagent.environment) {
            SubagentPlacement.Local -> null
            SubagentPlacement.Cloud -> childPlace ?: SubagentPlacement.Cloud
            SubagentPlacement.Machine -> SubagentPlacement.Machine
            null -> (childPlace ?: SubagentPlacement.Cloud.takeIf { subagent.isCloudAgent })?.takeIf { parent != null && it != parent }
        }
    }

    // -- the child ----------------------------------------------------------------------------------------------

    /** A run's status as a subagent's: going, done, failed (an expired run among them), or stopped. */
    fun statusOf(run: RunStatus?): SubagentChild.Status? = when (run) {
        RunStatus.CREATING, RunStatus.RUNNING -> SubagentChild.Status.Running
        RunStatus.FINISHED -> SubagentChild.Status.Succeeded
        RunStatus.ERROR, RunStatus.EXPIRED -> SubagentChild.Status.Failed
        RunStatus.CANCELLED -> SubagentChild.Status.Aborted
        RunStatus.UNKNOWN, null -> null
    }

    /** What the list's row says of a cloud child: how its latest run stands, whether it waits on the reader, its name and model. */
    fun childOf(agent: Agent, models: List<ModelOption>): SubagentChild {
        val status = statusOf(agent.runStatus)
        val current = ModelResolution.forChat(agent, models)
        return SubagentChild(
            status = status,
            waiting = agent.hasPendingInteraction && status != SubagentChild.Status.Succeeded && status != SubagentChild.Status.Failed && status != SubagentChild.Status.Aborted,
            name = agent.name.trim().takeIf { it.isNotEmpty() },
            model = current.takeUnless { it.isAssumed }?.let { SubagentModel(it.label, fast = it.choice?.params?.isFast() ?: agent.modelParams.isFast()) },
        )
    }

    /**
     * [child] as its run's stream tells it: the step it announced and the action it is on, read off the run's
     * [items]; a question it waits on; and, once the stream has seen the run end ([finished]), how it ended.
     */
    fun withRun(child: SubagentChild, items: List<TimelineItem>, finished: Boolean, status: RunStatus): SubagentChild {
        val waiting = !finished && items.lastOrNull { it is ActivityGroup }.let { group -> (group as? ActivityGroup)?.calls?.any { it.pendingQuestion != null } == true }
        return child.copy(
            status = if (finished) statusOf(status) ?: child.status else SubagentChild.Status.Running,
            step = stepOf(items) ?: child.step,
            action = actionOf(items) ?: child.action,
            waiting = waiting || (!finished && child.waiting),
        )
    }

    /**
     * The row's title: the state's own (a worker that never started), the call's, else the worker's name as the call
     * that created it gave it, else the child's own name, else the desktop's word for an unnamed subagent.
     */
    fun title(subagent: SubagentCall, look: SubagentLook, workerName: String? = null, childName: String? = null): String =
        look.title
            ?: subagent.title
            ?: workerName?.trim()?.takeIf { it.isNotEmpty() }
            ?: childName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (subagent.source == SubagentCall.Source.Stopped) SubagentCall.AGENT else SubagentCall.NEW_SUBAGENT

    // -- the rows of a transcript -------------------------------------------------------------------------------

    /**
     * What the rows of one transcript say about its workers, across every turn: the newest row about each worker
     * (the one whose state is live), and each worker's name and model as the call that created it gave them.
     */
    data class Index(val latest: Map<String, String> = emptyMap(), val workers: Map<String, Worker> = emptyMap()) {
        data class Worker(val name: String?, val modelId: String?)

        /** Whether [call]'s row is the newest about its worker; a task, or a row naming no worker, always is. */
        fun isLatest(call: ToolCall, subagent: SubagentCall): Boolean {
            if (!subagent.isWorker) return true
            val id = subagent.agentId ?: return true
            return latest[id]?.let { it == call.callId } ?: true
        }

        companion object {
            val EMPTY = Index()
        }
    }

    fun index(rows: List<TranscriptRow>): Index {
        var latest: MutableMap<String, String>? = null
        var workers: MutableMap<String, Index.Worker>? = null
        fun visit(row: TranscriptRow) {
            when (row) {
                is TranscriptRow.Subagent -> {
                    val subagent = row.subagent
                    val id = subagent.agentId ?: return
                    if (!subagent.isWorker) return
                    (latest ?: LinkedHashMap<String, String>().also { latest = it })[id] = row.call.callId
                    if (subagent.source == SubagentCall.Source.Created) {
                        (workers ?: LinkedHashMap<String, Index.Worker>().also { workers = it })[id] = Index.Worker(subagent.title, subagent.modelId)
                    }
                }
                is TranscriptRow.Events -> row.rows.forEach(::visit)
                else -> Unit
            }
        }
        rows.forEach(::visit)
        if (latest == null) return Index.EMPTY
        return Index(latest.orEmpty(), workers.orEmpty())
    }
}
