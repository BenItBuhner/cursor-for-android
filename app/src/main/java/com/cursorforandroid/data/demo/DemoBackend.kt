package com.cursorforandroid.data.demo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentSummaryDto
import com.cursorforandroid.data.api.dto.AgentUsageResponseDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.ArtifactDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.CreateRunResponseDto
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.ListArtifactsResponseDto
import com.cursorforandroid.data.api.dto.ListModelsResponseDto
import com.cursorforandroid.data.api.dto.ListRepositoriesResponseDto
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.ModelListItemDto
import com.cursorforandroid.data.api.dto.ModelParamDto
import com.cursorforandroid.data.api.dto.ModelParameterDefinitionDto
import com.cursorforandroid.data.api.dto.ModelParameterValueDto
import com.cursorforandroid.data.api.dto.ModelVariantDto
import com.cursorforandroid.data.api.dto.RepositoryDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.V0SourceDto
import com.cursorforandroid.data.api.dto.V0TargetDto
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter

private fun toolEvent(id: String, name: String, status: String, vararg args: Pair<String, String>) = RunStreamEvent.ToolCall(
    SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }),
)

/**
 * Shared mutable state behind the demo API and streamer.
 *
 * Every field is private and every accessor is synchronized, returning copies rather than the collections
 * themselves. The demo runs against the same repositories as the real backend, which read from Dispatchers.IO while
 * scripts and user actions write: a refresh iterating the agent map while a launch or a delete restructured it was a
 * ConcurrentModificationException waiting for the right moment, and a caller holding one of these lists could see it
 * change under it. Script pacing stays outside the lock, since a delay must never be held while holding it.
 */
internal class DemoStore {
    private val now = AppClock.now()
    private val fmt = DateTimeFormatter.ISO_INSTANT
    fun iso(millis: Long = AppClock.now()): String = fmt.format(Instant.ofEpochMilli(millis))

    private val agents: MutableMap<String, AgentDto> = linkedMapOf()
    private val v0: MutableMap<String, V0AgentDto> = linkedMapOf()
    private val runs: MutableMap<String, MutableList<RunDto>> = linkedMapOf()
    private val transcripts: MutableMap<String, MutableList<V0ConversationMessageDto>> = linkedMapOf()
    private val scripts: MutableMap<String, String> = linkedMapOf()
    private val prompts: MutableMap<String, String> = linkedMapOf()
    /** Names of the inline MCP servers a run was created with, so its script can show a tool call against one. */
    private val mcpServers: MutableMap<String, List<String>> = linkedMapOf()
    /** Runs the user stopped, so the script playing one can find out and stop where it is. */
    private val cancelled: MutableSet<String> = linkedSetOf()
    /** Each run's retained event log: pre-built for finished seeds, recorded as live scripts play. */
    private val eventLogs: MutableMap<String, MutableList<RunStreamEvent>> = linkedMapOf()
    private var counter = 100

    init {
        DemoData.seeds.forEach { seed ->
            val run = DemoData.initialRun(seed, now)
            val earlier = DemoData.earlierRuns(seed, now)
            agents[seed.id] = DemoData.agentDto(seed, now, run.id)
            v0[seed.id] = DemoData.v0Dto(seed, now)
            runs[seed.id] = (earlier.map { it.second } + run).toMutableList()
            transcripts[seed.id] = DemoData.transcript(seed).toMutableList()
            seed.liveScript?.let { scripts[run.id] = it }
            prompts[run.id] = seed.prompt
            if (seed.trace.isNotEmpty()) eventLogs[run.id] = eventLog(seed.trace, seed.replies, run).toMutableList()
            earlier.forEach { (turn, turnRun) ->
                prompts[turnRun.id] = turn.prompt
                if (turn.trace.isNotEmpty()) eventLogs[turnRun.id] = eventLog(turn.trace, turn.replies, turnRun).toMutableList()
            }
        }
    }

    @Synchronized
    fun startLog(runId: String) { eventLogs[runId] = mutableListOf() }

    @Synchronized
    fun record(runId: String, event: RunStreamEvent) { eventLogs.getOrPut(runId) { mutableListOf() } += event }

    @Synchronized
    fun eventLog(runId: String): List<RunStreamEvent>? = eventLogs[runId]?.toList()

    /** Turns a finished turn's scripted steps into the events its stream would have carried, mirroring the transcript. */
    private fun eventLog(trace: List<DemoData.Step>, turnReplies: List<String>, run: RunDto): List<RunStreamEvent> = buildList {
        add(RunStreamEvent.Status(run.id, RunStatus.RUNNING))
        val replies = turnReplies.iterator()
        var calls = 0
        trace.forEach { step ->
            when (step) {
                is DemoData.Step.Thought -> add(RunStreamEvent.Thinking(step.text))
                is DemoData.Step.Tool -> {
                    val id = "c${++calls}"
                    val key = when (step.name) {
                        "grep" -> "pattern"
                        "codebase_search", "web_search" -> "query"
                        "run_terminal_cmd" -> "command"
                        else -> "path"
                    }
                    add(toolEvent(id, step.name, "running", key to step.arg))
                    add(toolEvent(id, step.name, "completed", key to step.arg))
                }
                is DemoData.Step.Delegate -> {
                    val id = "s${++calls}"
                    add(toolEvent(id, "task", "running", "subagent_type" to "explore", "description" to step.description))
                    add(toolEvent(id, "task", "completed", "subagent_type" to "explore", "description" to step.description))
                }
                DemoData.Step.Reply -> if (replies.hasNext()) add(RunStreamEvent.Assistant(replies.next()))
            }
        }
        replies.forEachRemaining { add(RunStreamEvent.Assistant(it)) }
        add(RunStreamEvent.Result(run.id, RunStatus.parse(run.status), run.result, run.durationMs, run.git))
    }

    @Synchronized
    fun nextId(prefix: String) = "$prefix-demo-${++counter}"

    @Synchronized
    fun latestRun(agentId: String): RunDto? = runs[agentId]?.maxByOrNull { it.createdAt }

    @Synchronized
    fun agent(id: String): AgentDto? = agents[id]

    @Synchronized
    fun hasAgent(id: String): Boolean = id in agents

    /** Newest activity first, which is the order the list screen shows and the order pages are cut from. */
    @Synchronized
    fun agentsByRecency(includeArchived: Boolean): List<AgentDto> =
        agents.values.filter { includeArchived || it.status != "ARCHIVED" }.sortedByDescending { it.updatedAt }

    @Synchronized
    fun v0Agents(): List<V0AgentDto> = v0.values.toList()

    /** Null for an agent the demo has never heard of, so callers can answer 404 as the real API would. */
    @Synchronized
    fun runsOf(agentId: String): List<RunDto>? = runs[agentId]?.sortedByDescending { it.createdAt }

    @Synchronized
    fun run(agentId: String, runId: String): RunDto? = runs[agentId]?.firstOrNull { it.id == runId }

    @Synchronized
    fun transcript(agentId: String): List<V0ConversationMessageDto>? = transcripts[agentId]?.toList()

    @Synchronized
    fun script(runId: String): String? = scripts[runId]

    @Synchronized
    fun prompt(runId: String): String = prompts[runId].orEmpty()

    @Synchronized
    fun mcpServersOf(runId: String): List<String> = mcpServers[runId].orEmpty()

    @Synchronized
    fun repositoryUrls(): List<String> = v0.values.mapNotNull { it.source?.repository }.distinct()

    @Synchronized
    fun addAgent(agent: AgentDto, legacy: V0AgentDto, run: RunDto, firstMessage: String, script: String, servers: List<String>) {
        agents[agent.id] = agent
        v0[agent.id] = legacy
        runs[agent.id] = mutableListOf(run)
        transcripts[agent.id] = mutableListOf(V0ConversationMessageDto(nextId("msg"), "user_message", firstMessage))
        scripts[run.id] = script
        prompts[run.id] = firstMessage
        mcpServers[run.id] = servers
    }

    @Synchronized
    fun addRun(agent: AgentDto, run: RunDto, prompt: String, script: String, servers: List<String>?) {
        runs.getOrPut(agent.id) { mutableListOf() } += run
        agents[agent.id] = agent.copy(status = "ACTIVE", latestRunId = run.id, updatedAt = run.createdAt)
        transcripts.getOrPut(agent.id) { mutableListOf() } += V0ConversationMessageDto(nextId("msg"), "user_message", prompt)
        scripts[run.id] = script
        prompts[run.id] = prompt
        // Omitted on a follow-up means "keep the agent's configuration", so inherit the previous run's list.
        mcpServers[run.id] = servers ?: agent.latestRunId?.let { mcpServers[it] }.orEmpty()
    }

    @Synchronized
    fun removeAgent(id: String) {
        agents.remove(id)
        v0.remove(id)
        runs.remove(id)?.forEach { cancelled -= it.id }
        transcripts.remove(id)
    }

    @Synchronized
    fun updateRun(agentId: String, runId: String, transform: (RunDto) -> RunDto) {
        val list = runs[agentId] ?: return
        val idx = list.indexOfFirst { it.id == runId }
        if (idx >= 0) list[idx] = transform(list[idx])
        agents[agentId]?.let { agents[agentId] = it.copy(updatedAt = iso()) }
    }

    /**
     * Records that the user stopped [runId] and settles it, unless it has settled already. The script playing it
     * finds out through [isCancelled] and stops; the real backend terminates the stream the same way.
     */
    @Synchronized
    fun cancelRun(agentId: String, runId: String): Boolean {
        val list = runs[agentId] ?: return false
        val idx = list.indexOfFirst { it.id == runId }
        if (idx < 0 || RunStatus.parse(list[idx].status).isTerminal) return false
        cancelled += runId
        list[idx] = list[idx].copy(status = "CANCELLED", updatedAt = iso(), durationMs = 0)
        agents[agentId]?.let { agents[agentId] = it.copy(status = "IDLE", updatedAt = iso()) }
        return true
    }

    @Synchronized
    fun isCancelled(runId: String): Boolean = runId in cancelled

    /**
     * Settles [runId] as its script's ending describes, and reports whether it was still there to settle. A run the
     * user cancelled has settled already, and a script that was mid-flight when that happened must not undo it.
     */
    @Synchronized
    fun finishRun(agentId: String, runId: String, transform: (RunDto) -> RunDto): Boolean {
        if (runId in cancelled) return false
        val list = runs[agentId] ?: return false
        val idx = list.indexOfFirst { it.id == runId }
        if (idx < 0 || RunStatus.parse(list[idx].status).isTerminal) return false
        list[idx] = transform(list[idx])
        agents[agentId]?.let { agents[agentId] = it.copy(status = "IDLE", updatedAt = iso()) }
        return true
    }

    @Synchronized
    fun setLifecycle(agentId: String, status: String) {
        agents[agentId]?.let { agents[agentId] = it.copy(status = status, updatedAt = iso()) }
    }

    @Synchronized
    fun appendAssistant(agentId: String, text: String) {
        transcripts.getOrPut(agentId) { mutableListOf() } += V0ConversationMessageDto(nextId("msg"), "assistant_message", text)
        v0[agentId]?.let { v0[agentId] = it.copy(summary = text.lines().first().take(160)) }
    }

    @Synchronized
    fun setV0Status(agentId: String, status: String, branch: String? = null, prUrl: String? = null) {
        v0[agentId]?.let { existing ->
            v0[agentId] = existing.copy(
                status = status,
                target = (existing.target ?: V0TargetDto()).let { t -> t.copy(branchName = branch ?: t.branchName, prUrl = prUrl ?: t.prUrl) },
            )
        }
    }
}

/**
 * One page of [all], the way a keyset cursor works: [cursor] is the id of the last item of the previous page and the
 * page starts after it, so a traversal never repeats an item even when the list changes between requests. An
 * unrecognised cursor ends the traversal, which is what a real cursor pointing at a deleted row degrades to.
 */
private fun <T> pageOf(all: List<T>, limit: Int, cursor: String?, id: (T) -> String): Pair<List<T>, String?> {
    val size = limit.coerceIn(1, DEMO_MAX_PAGE_SIZE)
    val from = cursor?.let { c -> all.indexOfFirst { id(it) == c }.let { if (it < 0) all.size else it + 1 } } ?: 0
    val items = all.drop(from).take(size)
    return items to if (from + items.size < all.size) items.lastOrNull()?.let(id) else null
}

/** What the live API accepts as the largest page, so a caller asking for more is answered the same way. */
private const val DEMO_MAX_PAGE_SIZE = 100

internal class DemoCursorApi(private val store: DemoStore) : CursorApi {

    /** Simulated network latency must behave like real IO, never a main-thread delay. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun AgentDto.summary() = AgentSummaryDto(id, name, status, env, url, createdAt, updatedAt, latestRunId)

    override suspend fun me(): ApiKeyInfoDto = io {
        delay(150)
        ApiKeyInfoDto(apiKeyName = "Demo key", createdAt = store.iso(), userId = 42, userEmail = "demo@cursor.local", userFirstName = "Demo", userLastName = "User")
    }

    override suspend fun models(): ListModelsResponseDto = io {
        delay(120)
        // Shaped like the live catalogue: a model's `variants` enumerate every accepted parameter combination (a
        // context window × effort grid, an effort × fast grid), and the `parameters` definitions name them.
        val efforts = listOf("low" to "Low", "medium" to "Medium", "high" to "High", "xhigh" to "Extra High", "max" to "Max")
        val contexts = listOf("300k" to "300K", "1m" to "1M")
        val effortDefinition = { values: List<Pair<String, String>> -> ModelParameterDefinitionDto("effort", "Effort", values.map { (v, n) -> ModelParameterValueDto(v, n) }) }
        ListModelsResponseDto(
            items = listOf(
                ModelListItemDto(
                    id = "claude-fable-5.1-thinking", displayName = "Claude Fable 5.1", description = "Best for complex, long-horizon coding.",
                    parameters = listOf(ModelParameterDefinitionDto("context", "Context", contexts.map { (v, n) -> ModelParameterValueDto(v, n) }), effortDefinition(efforts)),
                    variants = contexts.reversed().flatMap { (context, contextName) ->
                        efforts.reversed().map { (effort, effortName) ->
                            ModelVariantDto(
                                params = listOf(ModelParamDto("context", context), ModelParamDto("effort", effort)),
                                displayName = "Claude Fable 5.1" + (if (context == "1m") " $contextName" else "") + " $effortName",
                                isDefault = context == "1m" && effort == "max",
                            )
                        }
                    },
                ),
                // Every variant carries the model's name, as in the live catalogue; they differ only by their parameters.
                ModelListItemDto(
                    id = "cursor-grok-4.6", displayName = "Cursor Grok 4.6", description = "Cursor's fast reasoning model.",
                    parameters = listOf(
                        effortDefinition(efforts.dropLast(1)),
                        ModelParameterDefinitionDto("fast", "Fast", listOf(ModelParameterValueDto("false"), ModelParameterValueDto("true", "Fast"))),
                    ),
                    variants = efforts.dropLast(1).flatMap { (effort, _) ->
                        listOf("true", "false").map { fast ->
                            ModelVariantDto(
                                params = listOf(ModelParamDto("effort", effort), ModelParamDto("fast", fast)),
                                displayName = "Cursor Grok 4.6",
                                isDefault = effort == "high" && fast == "true",
                            )
                        }
                    },
                ),
                ModelListItemDto(
                    id = "composer-2.5", displayName = "Composer 2.5", description = "Cursor's fast frontier model.",
                    parameters = listOf(ModelParameterDefinitionDto("fast", "Fast", listOf(ModelParameterValueDto("false"), ModelParameterValueDto("true", "Fast")))),
                    variants = listOf(
                        ModelVariantDto(params = listOf(ModelParamDto("fast", "true")), displayName = "Composer 2.5", isDefault = true),
                        ModelVariantDto(params = listOf(ModelParamDto("fast", "false")), displayName = "Composer 2.5"),
                    ),
                ),
                ModelListItemDto(id = "gpt-5.6", displayName = "GPT-5.6", variants = listOf(ModelVariantDto(params = listOf(ModelParamDto("effort", "high")), displayName = "GPT-5.6 High", isDefault = true))),
                ModelListItemDto(id = "gemini-3.8-flash", displayName = "Gemini 3.8 Flash", variants = listOf(ModelVariantDto(params = emptyList(), displayName = "Gemini 3.8 Flash", isDefault = true))),
                ModelListItemDto(id = "auto-smart", displayName = "Auto", description = "Cursor Router picks the model."),
            ),
        )
    }

    override suspend fun repositories(): ListRepositoriesResponseDto = io {
        delay(400)
        ListRepositoriesResponseDto(store.repositoryUrls().map { RepositoryDto(it) })
    }

    override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto = io {
        delay(250)
        val (items, next) = pageOf(store.agentsByRecency(includeArchived), limit, cursor) { it.id }
        ListAgentsResponseDto(items = items.map { it.summary() }, nextCursor = next)
    }

    override suspend fun getAgent(id: String): AgentDto = io {
        delay(100)
        store.agent(id) ?: throw notFound("agent_not_found")
    }

    override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto = io {
        delay(500)
        val id = body.agentId ?: store.nextId("bc")
        if (store.hasAgent(id)) throw CursorApiException(409, "agent_id_conflict", "An agent with this id already exists.")
        val runId = store.nextId("run")
        val nowIso = store.iso()
        val repo = body.repos?.firstOrNull()
        val agent = AgentDto(
            id = id,
            name = body.name ?: SlashCommands.strip(body.prompt.text).lines().first().take(60).ifBlank { "New agent" },
            status = "ACTIVE",
            env = body.env ?: com.cursorforandroid.data.api.dto.AgentEnvDto("cloud"),
            url = "https://cursor.com/agents/$id",
            createdAt = nowIso,
            updatedAt = nowIso,
            latestRunId = runId,
            repos = body.repos ?: emptyList(),
            workOnCurrentBranch = body.workOnCurrentBranch ?: false,
            autoCreatePR = body.autoCreatePR,
        )
        val run = RunDto(id = runId, agentId = id, status = "CREATING", createdAt = nowIso, updatedAt = nowIso)
        store.addAgent(
            agent = agent,
            legacy = V0AgentDto(id, agent.name, "CREATING", repo?.let { V0SourceDto(it.url, it.startingRef) }, V0TargetDto(url = agent.url, autoCreatePr = body.autoCreatePR), null, nowIso),
            run = run,
            firstMessage = body.prompt.text,
            script = scriptFor(body.prompt.text, body.mode),
            servers = body.mcpServers.orEmpty().map { it.name },
        )
        CreateAgentResponseDto(agent, run)
    }

    /** Plan mode has its own script; otherwise a `/multitask` prompt fans out to subagents, anything else is generic. */
    private fun scriptFor(prompt: String, mode: String?): String = when {
        mode == "plan" -> "plan"
        SlashCommands.has(prompt, SlashCommands.MULTITASK) -> "multitask"
        else -> "generic"
    }

    override suspend fun archive(id: String): IdResponseDto = io { delay(150); store.setLifecycle(id, "ARCHIVED"); IdResponseDto(id) }
    override suspend fun unarchive(id: String): IdResponseDto = io { delay(150); store.setLifecycle(id, "IDLE"); IdResponseDto(id) }
    override suspend fun delete(id: String): IdResponseDto = io {
        delay(150)
        store.removeAgent(id)
        IdResponseDto(id)
    }

    override suspend fun usage(id: String): AgentUsageResponseDto = AgentUsageResponseDto()

    override suspend fun artifacts(id: String): ListArtifactsResponseDto = io {
        delay(100)
        if (!store.hasAgent(id)) throw notFound("agent_not_found")
        ListArtifactsResponseDto(DemoData.artifacts[id].orEmpty().map { ArtifactDto(it.path, it.sizeBytes, store.iso()) })
    }

    /** The "presigned URL" of a demo artifact is the bundled asset, which Coil and ExoPlayer both read directly. */
    override suspend fun artifactUrl(id: String, path: String): DownloadArtifactResponseDto = io {
        delay(100)
        val artifact = DemoData.artifacts[id].orEmpty().firstOrNull { it.path == path } ?: throw notFound("artifact_not_found")
        DownloadArtifactResponseDto(url = MediaLoader.ASSET_PREFIX + artifact.asset, expiresAt = store.iso(AppClock.now() + 15 * 60_000L))
    }

    override suspend fun listRuns(id: String, limit: Int, cursor: String?): ListRunsResponseDto = io {
        delay(120)
        val (items, next) = pageOf(store.runsOf(id) ?: throw notFound("agent_not_found"), limit, cursor) { it.id }
        ListRunsResponseDto(items = items, nextCursor = next)
    }

    override suspend fun getRun(id: String, runId: String): RunDto =
        store.run(id, runId) ?: throw notFound("run_not_found")

    override suspend fun createRun(id: String, body: CreateRunRequestDto): CreateRunResponseDto = io {
        delay(350)
        val agent = store.agent(id) ?: throw notFound("agent_not_found")
        if (agent.status == "ARCHIVED") throw CursorApiException(409, "agent_archived", "Agent is archived.")
        store.latestRun(id)?.let { if (it.status == "RUNNING" || it.status == "CREATING") throw CursorApiException(409, "agent_busy", "Agent is busy.") }
        val nowIso = store.iso()
        val run = RunDto(id = store.nextId("run"), agentId = id, status = "CREATING", createdAt = nowIso, updatedAt = nowIso)
        store.addRun(agent, run, body.prompt.text, scriptFor(body.prompt.text, body.mode), body.mcpServers?.map { it.name })
        store.setV0Status(id, "RUNNING")
        CreateRunResponseDto(run)
    }

    override suspend fun cancelRun(id: String, runId: String): IdResponseDto = io {
        delay(150)
        if (store.run(id, runId) == null) throw notFound("run_not_found")
        // Marking it cancelled and telling its script to stop is one step, so a script cannot finish in between.
        if (!store.cancelRun(id, runId)) throw CursorApiException(409, "run_not_cancellable", "Run already finished.")
        store.setV0Status(id, "CANCELLED")
        IdResponseDto(runId)
    }

    override suspend fun listAgentsV0(limit: Int, cursor: String?): V0ListAgentsResponseDto = io {
        delay(200)
        val (items, next) = pageOf(store.v0Agents(), limit, cursor) { it.id }
        V0ListAgentsResponseDto(agents = items, nextCursor = next)
    }

    override suspend fun conversationV0(id: String): V0ConversationResponseDto = io {
        delay(180)
        V0ConversationResponseDto(id, store.transcript(id) ?: throw notFound("agent_not_found"))
    }

    private fun notFound(code: String) = CursorApiException(404, code, "Not found.")
}

/**
 * Replays scripted streams with realistic pacing and mutates the store when a run finishes. Like the real endpoint,
 * a finished run's stream replays its retained event log until the retention window lapses, then reports it expired.
 */
internal class DemoRunStreamer(private val store: DemoStore) : RunStreamer {

    /** Raised by the recorder when the user has stopped the run, to unwind whatever the script was in the middle of. */
    private class Stopped : Exception(null, null, false, false)

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        val script = store.script(runId) ?: "generic"
        val run = store.run(agentId, runId)
        if (run != null && RunStatus.parse(run.status).isTerminal) {
            replay(run)
            return@flow
        }
        // Every event of the live run is retained, so a later visit can replay it the way the API would.
        store.startLog(runId)
        val recorder = object : FlowCollector<RunStreamEvent> {
            override suspend fun emit(value: RunStreamEvent) {
                // Every script step passes through here, so this is where a cancel takes effect: whatever the script
                // was going to do next, including its own ending, does not happen.
                if (store.isCancelled(runId)) throw Stopped()
                store.record(runId, value)
                this@flow.emit(value)
            }
        }
        val startedAt = AppClock.now()
        try {
            recorder.emit(RunStreamEvent.Status(runId, RunStatus.RUNNING))
            store.updateRun(agentId, runId) { it.copy(status = "RUNNING") }
            store.setV0Status(agentId, "RUNNING")
            val outcome = when (script) {
                "cesium" -> recorder.cesium()
                "codex" -> recorder.codex()
                "limbs" -> recorder.limbs()
                "plan" -> recorder.plan(store.prompt(runId))
                "multitask" -> recorder.multitask(store.prompt(runId), store.mcpServersOf(runId))
                else -> recorder.generic(store.prompt(runId), store.mcpServersOf(runId))
            }
            val duration = outcome.reportedDurationMs ?: (AppClock.now() - startedAt)
            val git = outcome.branch?.let { RunGitDto(listOf(RunGitBranchDto(repoUrl = "github.com/demo/repo", branch = it, prUrl = outcome.prUrl))) }
            // A cancel that landed between the last event and here has settled the run already; the script's ending
            // is not allowed to call it finished after that.
            val settled = store.finishRun(agentId, runId) {
                it.copy(status = "FINISHED", updatedAt = store.iso(), durationMs = duration, result = outcome.finalText, git = git ?: it.git)
            }
            if (!settled) throw Stopped()
            store.appendAssistant(agentId, outcome.finalText)
            store.setV0Status(agentId, "FINISHED", branch = outcome.branch, prUrl = outcome.prUrl)
            recorder.emit(RunStreamEvent.Result(runId, RunStatus.FINISHED, outcome.finalText, duration, git))
        } catch (_: Stopped) {
            // The live endpoint ends a cancelled run's stream with its terminal state; the store already says
            // CANCELLED, so this only reports it, and the log keeps it for a later replay.
            val result = RunStreamEvent.Result(runId, RunStatus.CANCELLED, null, AppClock.now() - startedAt, null)
            store.record(runId, result)
            emit(result)
        }
        emit(RunStreamEvent.Done)
    }

    private suspend fun FlowCollector<RunStreamEvent>.replay(run: RunDto) {
        val log = store.eventLog(run.id)
        val finishedAt = parseIsoMillis(run.updatedAt)
        if (log == null || AppClock.now() - finishedAt > DemoData.STREAM_RETENTION_MS) {
            emit(RunStreamEvent.Error("stream_expired", "This run's live stream has expired."))
            return
        }
        delay(200)
        log.forEach { emit(it) }
        emit(RunStreamEvent.Done)
    }

    private class Outcome(val finalText: String, val branch: String? = null, val prUrl: String? = null, val reportedDurationMs: Long? = null)

    private fun tool(id: String, name: String, status: String, vararg args: Pair<String, String>) = toolEvent(id, name, status, *args)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.type(text: String, chunk: Int = 18, delayMs: Long = 22) {
        var i = 0
        while (i < text.length) {
            val end = (i + chunk).coerceAtMost(text.length)
            emit(RunStreamEvent.Assistant(text.substring(i, end)))
            i = end
            delay(delayMs)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.think(text: String) {
        text.split(" ").chunked(6).forEach { words ->
            emit(RunStreamEvent.Thinking(words.joinToString(" ") + " "))
            delay(60)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.cesium(): Outcome {
        think("This is more of a strategic assessment request than a code change, so I should focus on understanding Cesium's actual architecture, features, cloud dependencies like Convex and Clerk, licensing, and any existing monetization hooks before I can give a useful answer. I'll dispatch a couple of parallel explore subagents alongside direct reads of key files like README, LICENSE, and package.json.")
        delay(300)
        emit(tool("c1", "read_file", "running", "path" to "LICENSE")); delay(250)
        emit(tool("c1", "read_file", "completed", "path" to "LICENSE"))
        emit(tool("c2", "read_file", "running", "path" to "package.json")); delay(200)
        emit(tool("c2", "read_file", "completed", "path" to "package.json"))
        emit(tool("c3", "grep", "running", "pattern" to "convex")); delay(200)
        emit(tool("c3", "grep", "completed", "pattern" to "convex"))
        emit(tool("c4", "grep", "running", "pattern" to "clerk")); delay(150)
        emit(tool("c4", "grep", "completed", "pattern" to "clerk"))
        emit(tool("c5", "read_file", "running", "path" to "README.md")); delay(150)
        emit(tool("c5", "read_file", "completed", "path" to "README.md"))
        listOf("stripe", "billing", "usage", "upstash").forEachIndexed { i, p ->
            emit(tool("g$i", "codebase_search", "running", "query" to p)); delay(120)
            emit(tool("g$i", "codebase_search", "completed", "query" to p))
        }
        delay(300)
        type("Hell yes, this is the kind of question I love sinking my teeth into. But I refuse to hand you generic \"open-core + SaaS\" slop without actually knowing what Cesium is made of. Let me tear through the repo first so the strategy is grounded in what you've actually built.\n\n")
        delay(300)
        emit(tool("s1", "task", "running", "subagent_type" to "explore", "description" to "Survey cloud sync layer"))
        delay(200)
        emit(tool("s2", "task", "running", "subagent_type" to "explore", "description" to "Survey first-party agent and inference"))
        delay(200)
        emit(tool("s3", "task", "running", "subagent_type" to "explore", "description" to "Survey distribution and platform surfaces"))
        delay(1200)
        type("Repo's telling me a LOT already: AGPL-3.0 on the whole thing, a live Convex + Clerk production cloud (`cesium.techlitnow.com`), self-hosted engines paired over an Upstash-backed rendezvous registry, tunnels piggybacking on free `localhost.run` / Cloudflare quick tunnels, GitHub Codespaces as a disposable engine host, and a first-party agent that is pure BYOK plus ChatGPT/SuperGrok subscription OAuth. Zero billing, zero metering so far, which is exactly the blank canvas I want.\n\nI've got three explorers finishing the inventory of the cloud layer, the inference/cost surfaces, and the distribution/team surfaces. The moment they report back I'll assemble the full monetization teardown for you.")
        delay(900)
        emit(tool("s2", "task", "completed", "subagent_type" to "explore", "description" to "Survey first-party agent and inference"))
        delay(700)
        emit(tool("s1", "task", "completed", "subagent_type" to "explore", "description" to "Survey cloud sync layer"))
        delay(500)
        emit(tool("s3", "task", "completed", "subagent_type" to "explore", "description" to "Survey distribution and platform surfaces"))
        delay(400)
        think("One down, and it confirmed the thing I was hoping for: the first-party agent is pure BYOK with costUsd null everywhere, and the only hosted inference surface is a stateless CORS relay on Vercel.")
        delay(200)
        val closing = "\n\nOne down, and it confirmed the thing I was hoping for: the first-party agent is pure BYOK with `costUsd: null` everywhere, and the only \"hosted\" inference surface is a stateless CORS relay on Vercel. That means the subsidized-inference lever is wide open, and the env bootstrap (`CESIUM_BASE_URL` / `CESIUM_PROVIDER_ID`) is already the exact seam where a Cesium-operated gateway would plug in.\n\n**The teardown**\n\n1. **Hosted control plane as the paid tier.** Keep the engine and client AGPL, charge for the Convex + Clerk cloud: pairing, sync, history. Free tier caps at 1 engine and 7 days of history.\n2. **Metered subsidized inference.** Ship a Cesium gateway behind `CESIUM_BASE_URL` with included credits per seat; BYOK stays free forever so the community never feels held hostage.\n3. **Transcription minutes** as the cleanest usage meter: predictable COGS, obviously valuable, trivially enforced server-side.\n4. **Team surfaces** (shared engines, SSO, audit log) at a per-seat price for the people who already pay for Codespaces."
        type(closing)
        return Outcome(finalText = closing.trim(), reportedDurationMs = 185_000)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.codex(): Outcome {
        emit(tool("k1", "run_terminal_cmd", "running", "command" to "kubectl scale deploy/poly-bot --replicas=12")); delay(900)
        emit(tool("k1", "run_terminal_cmd", "completed", "command" to "kubectl scale deploy/poly-bot --replicas=12"))
        type("Scaling the fleet to 12 workers now. I'll watch the catch-up queue depth and report the drain time.\n\n")
        for (i in 1..6) {
            emit(tool("k${i + 1}", "run_terminal_cmd", "running", "command" to "redis-cli LLEN catchup:queue")); delay(1500)
            emit(tool("k${i + 1}", "run_terminal_cmd", "completed", "command" to "redis-cli LLEN catchup:queue"))
            emit(RunStreamEvent.Heartbeat)
        }
        val text = "Backlog drained in 4m 12s with 12 workers (peak 601,635 events, 519 events/s sustained). The MM2 pre-registration is recorded in `/tmp/catchup/cf2-pre.json`. Leaving the fleet at 12 until the evening spike passes."
        type(text)
        return Outcome(finalText = text)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.limbs(): Outcome {
        think("The arm rig uses linear blend skinning with four influences; muscle bulge needs corrective shapes or a dual-quaternion pass.")
        emit(tool("l1", "read_file", "running", "path" to "rig/arm_rig.py")); delay(600)
        emit(tool("l1", "read_file", "completed", "path" to "rig/arm_rig.py"))
        emit(tool("l2", "edit_file", "running", "path" to "rig/arm_rig.py")); delay(1400)
        emit(tool("l2", "edit_file", "completed", "path" to "rig/arm_rig.py"))
        val text = "Switched the arm and leg meshes to dual-quaternion skinning and added bicep / calf corrective blendshapes driven by joint angle. Pushed to `cursor/limb-rigging-3e4f`; +5159 −8."
        type(text)
        return Outcome(finalText = text, branch = "cursor/limb-rigging-3e4f")
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.generic(prompt: String, mcpServers: List<String>): Outcome {
        think("Let me look at the relevant files before changing anything.")
        emit(tool("g1", "list_dir", "running", "path" to ".")); delay(400)
        emit(tool("g1", "list_dir", "completed", "path" to "."))
        emit(tool("g2", "grep", "running", "pattern" to prompt.split(" ").firstOrNull { it.length > 4 }.orEmpty().ifBlank { "TODO" })); delay(500)
        emit(tool("g2", "grep", "completed", "pattern" to "TODO"))
        mcpTool(mcpServers)
        val text = "Done. I handled \"${prompt.lines().first().take(80)}\" and pushed the change to a branch — this is the demo backend, so nothing left your device."
        type(text)
        return Outcome(finalText = text, branch = "cursor/demo-follow-up")
    }

    /** `/multitask`: the task is split and three subagents work at once, the way the web describes the mode. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.multitask(prompt: String, mcpServers: List<String>): Outcome {
        val task = SlashCommands.strip(prompt).lines().first().take(60).ifBlank { "the request" }
        think("Multitask mode: split \"$task\" into independent pieces and hand each to its own subagent so they run in parallel instead of queueing.")
        delay(300)
        mcpTool(mcpServers)
        emit(tool("m1", "task", "running", "subagent_type" to "generalPurpose", "description" to "Implement: $task")); delay(200)
        emit(tool("m2", "task", "running", "subagent_type" to "explore", "description" to "Find the tests that cover it")); delay(200)
        emit(tool("m3", "task", "running", "subagent_type" to "generalPurpose", "description" to "Update the docs")); delay(1500)
        emit(tool("m2", "task", "completed", "subagent_type" to "explore", "description" to "Find the tests that cover it")); delay(700)
        emit(tool("m3", "task", "completed", "subagent_type" to "generalPurpose", "description" to "Update the docs")); delay(600)
        emit(tool("m1", "task", "completed", "subagent_type" to "generalPurpose", "description" to "Implement: $task"))
        val text = "Three subagents ran in parallel: one implemented \"$task\", one found and ran the tests that cover it, one updated the docs. Their changes are merged on `cursor/demo-multitask` — this is the demo backend, so nothing left your device."
        type(text)
        return Outcome(finalText = text, branch = "cursor/demo-multitask")
    }

    /** One `mcp` tool call against the first attached server, so inline servers show up in the transcript. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.mcpTool(mcpServers: List<String>) {
        val server = mcpServers.firstOrNull() ?: return
        emit(tool("mcp1", "mcp", "running", "server" to server, "tool" to "list_tools")); delay(500)
        emit(tool("mcp1", "mcp", "completed", "server" to server, "tool" to "list_tools"))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<RunStreamEvent>.plan(prompt: String): Outcome {
        think("Plan mode: explore first, then draft a plan without editing files.")
        emit(tool("p1", "codebase_search", "running", "query" to prompt.take(60))); delay(700)
        emit(tool("p1", "codebase_search", "completed", "query" to prompt.take(60)))
        val text = "**Plan**\n\n1. Audit the current implementation and list the touch points.\n2. Introduce the change behind a flag.\n3. Add tests and a rollout note.\n\nReply with follow-up instructions and I'll switch to agent mode to implement it."
        type(text)
        return Outcome(finalText = text)
    }
}

/** Assembles the demo backend. */
object DemoBackendFactory {
    fun create(): Pair<CursorApi, RunStreamer> {
        val store = DemoStore()
        return DemoCursorApi(store) to DemoRunStreamer(store)
    }
}
