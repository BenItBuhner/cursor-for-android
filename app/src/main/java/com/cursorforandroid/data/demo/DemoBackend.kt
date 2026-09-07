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
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Shared mutable state behind the demo API and streamer. */
internal class DemoStore {
    private val now = AppClock.now()
    private val fmt = DateTimeFormatter.ISO_INSTANT
    fun iso(millis: Long = AppClock.now()): String = fmt.format(Instant.ofEpochMilli(millis))

    val seeds = DemoData.seeds.associateBy { it.id }.toMutableMap()
    val agents: MutableMap<String, AgentDto> = linkedMapOf()
    val v0: MutableMap<String, V0AgentDto> = linkedMapOf()
    val runs: MutableMap<String, MutableList<RunDto>> = linkedMapOf()
    val transcripts: MutableMap<String, MutableList<V0ConversationMessageDto>> = linkedMapOf()
    val scripts: MutableMap<String, String> = linkedMapOf()
    val prompts: MutableMap<String, String> = linkedMapOf()
    /** Names of the inline MCP servers a run was created with, so its script can show a tool call against one. */
    val mcpServers: MutableMap<String, List<String>> = linkedMapOf()
    private var counter = 100

    init {
        DemoData.seeds.forEach { seed ->
            val run = DemoData.initialRun(seed, now)
            agents[seed.id] = DemoData.agentDto(seed, now, run.id)
            v0[seed.id] = DemoData.v0Dto(seed, now)
            runs[seed.id] = mutableListOf(run)
            transcripts[seed.id] = DemoData.transcript(seed).toMutableList()
            seed.liveScript?.let { scripts[run.id] = it }
            prompts[run.id] = seed.prompt
        }
    }

    @Synchronized
    fun nextId(prefix: String) = "$prefix-demo-${++counter}"

    @Synchronized
    fun latestRun(agentId: String): RunDto? = runs[agentId]?.maxByOrNull { it.createdAt }

    @Synchronized
    fun updateRun(agentId: String, runId: String, transform: (RunDto) -> RunDto) {
        val list = runs[agentId] ?: return
        val idx = list.indexOfFirst { it.id == runId }
        if (idx >= 0) list[idx] = transform(list[idx])
        agents[agentId]?.let { agents[agentId] = it.copy(updatedAt = iso()) }
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
        ListModelsResponseDto(
            items = listOf(
                ModelListItemDto(
                    id = "claude-fable-5.1-thinking", displayName = "Claude Fable 5.1", description = "Best for complex, long-horizon coding.",
                    variants = listOf(
                        ModelVariantDto(params = listOf(ModelParamDto("context", "1m"), ModelParamDto("effort", "max")), displayName = "Claude Fable 5.1 1M Max", isDefault = true),
                        ModelVariantDto(params = listOf(ModelParamDto("effort", "high")), displayName = "Claude Fable 5.1"),
                    ),
                ),
                // Like the live catalogue, both variants carry the model's name and differ only by their parameters.
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
        ListRepositoriesResponseDto(store.v0.values.mapNotNull { it.source?.repository }.distinct().map { RepositoryDto(it) })
    }

    override suspend fun listAgents(limit: Int, cursor: String?, includeArchived: Boolean): ListAgentsResponseDto = io {
        delay(250)
        val all = store.agents.values.filter { includeArchived || it.status != "ARCHIVED" }.sortedByDescending { it.updatedAt }
        ListAgentsResponseDto(items = all.map { it.summary() })
    }

    override suspend fun getAgent(id: String): AgentDto = io {
        delay(100)
        store.agents[id] ?: throw notFound("agent_not_found")
    }

    override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto = io {
        delay(500)
        val id = store.nextId("bc")
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
        synchronized(store) {
            store.agents[id] = agent
            store.v0[id] = V0AgentDto(id, agent.name, "CREATING", repo?.let { V0SourceDto(it.url, it.startingRef) }, V0TargetDto(url = agent.url, autoCreatePr = body.autoCreatePR), null, nowIso)
            store.runs[id] = mutableListOf(run)
            store.transcripts[id] = mutableListOf(V0ConversationMessageDto(store.nextId("msg"), "user_message", body.prompt.text))
            store.scripts[runId] = scriptFor(body.prompt.text, body.mode)
            store.prompts[runId] = body.prompt.text
            store.mcpServers[runId] = body.mcpServers.orEmpty().map { it.name }
        }
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
        synchronized(store) { store.agents.remove(id); store.v0.remove(id); store.runs.remove(id); store.transcripts.remove(id) }
        IdResponseDto(id)
    }

    override suspend fun usage(id: String): AgentUsageResponseDto = AgentUsageResponseDto()

    override suspend fun artifacts(id: String): ListArtifactsResponseDto = io {
        delay(100)
        if (id !in store.agents) throw notFound("agent_not_found")
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
        ListRunsResponseDto(items = (store.runs[id] ?: throw notFound("agent_not_found")).sortedByDescending { it.createdAt })
    }

    override suspend fun getRun(id: String, runId: String): RunDto =
        store.runs[id]?.firstOrNull { it.id == runId } ?: throw notFound("run_not_found")

    override suspend fun createRun(id: String, body: CreateRunRequestDto): CreateRunResponseDto = io {
        delay(350)
        val agent = store.agents[id] ?: throw notFound("agent_not_found")
        if (agent.status == "ARCHIVED") throw CursorApiException(409, "agent_archived", "Agent is archived.")
        store.latestRun(id)?.let { if (it.status == "RUNNING" || it.status == "CREATING") throw CursorApiException(409, "agent_busy", "Agent is busy.") }
        val runId = store.nextId("run")
        val nowIso = store.iso()
        val run = RunDto(id = runId, agentId = id, status = "CREATING", createdAt = nowIso, updatedAt = nowIso)
        synchronized(store) {
            store.runs.getOrPut(id) { mutableListOf() } += run
            store.agents[id] = agent.copy(status = "ACTIVE", latestRunId = runId, updatedAt = nowIso)
            store.transcripts.getOrPut(id) { mutableListOf() } += V0ConversationMessageDto(store.nextId("msg"), "user_message", body.prompt.text)
            store.scripts[runId] = scriptFor(body.prompt.text, body.mode)
            store.prompts[runId] = body.prompt.text
            // Omitted on a follow-up means "keep the agent's configuration", so inherit the previous run's list.
            store.mcpServers[runId] = body.mcpServers?.map { it.name } ?: agent.latestRunId?.let { store.mcpServers[it] }.orEmpty()
        }
        store.setV0Status(id, "RUNNING")
        CreateRunResponseDto(run)
    }

    override suspend fun cancelRun(id: String, runId: String): IdResponseDto = io {
        delay(150)
        val run = store.runs[id]?.firstOrNull { it.id == runId } ?: throw notFound("run_not_found")
        if (RunStatus.parse(run.status).isTerminal) throw CursorApiException(409, "run_not_cancellable", "Run already finished.")
        store.updateRun(id, runId) { it.copy(status = "CANCELLED", updatedAt = store.iso(), durationMs = 0) }
        store.setLifecycle(id, "IDLE")
        store.setV0Status(id, "CANCELLED")
        IdResponseDto(runId)
    }

    override suspend fun listAgentsV0(limit: Int, cursor: String?): V0ListAgentsResponseDto = io {
        delay(200)
        V0ListAgentsResponseDto(agents = store.v0.values.toList())
    }

    override suspend fun conversationV0(id: String): V0ConversationResponseDto = io {
        delay(180)
        V0ConversationResponseDto(id, store.transcripts[id] ?: throw notFound("agent_not_found"))
    }

    private fun notFound(code: String) = CursorApiException(404, code, "Not found.")
}

/** Replays scripted streams with realistic pacing and mutates the store when a run finishes. */
internal class DemoRunStreamer(private val store: DemoStore) : RunStreamer {

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        val script = store.scripts[runId] ?: "generic"
        val alreadyDone = store.runs[agentId]?.firstOrNull { it.id == runId }?.let { RunStatus.parse(it.status).isTerminal } == true
        if (alreadyDone) {
            emit(RunStreamEvent.Error("stream_expired", "This run's live stream has expired."))
            return@flow
        }
        emit(RunStreamEvent.Status(runId, RunStatus.RUNNING))
        store.updateRun(agentId, runId) { it.copy(status = "RUNNING") }
        store.setV0Status(agentId, "RUNNING")
        val startedAt = AppClock.now()
        val outcome = when (script) {
            "cesium" -> cesium()
            "codex" -> codex()
            "limbs" -> limbs()
            "plan" -> plan(store.prompts[runId].orEmpty())
            "multitask" -> multitask(store.prompts[runId].orEmpty(), store.mcpServers[runId].orEmpty())
            else -> generic(store.prompts[runId].orEmpty(), store.mcpServers[runId].orEmpty())
        }
        val elapsed = AppClock.now() - startedAt
        val duration = outcome.reportedDurationMs ?: elapsed
        val git = outcome.branch?.let { RunGitDto(listOf(RunGitBranchDto(repoUrl = "github.com/demo/repo", branch = it, prUrl = outcome.prUrl))) }
        store.appendAssistant(agentId, outcome.finalText)
        store.updateRun(agentId, runId) { it.copy(status = "FINISHED", updatedAt = store.iso(), durationMs = duration, result = outcome.finalText, git = git ?: it.git) }
        store.setLifecycle(agentId, "IDLE")
        store.setV0Status(agentId, "FINISHED", branch = outcome.branch, prUrl = outcome.prUrl)
        emit(RunStreamEvent.Result(runId, RunStatus.FINISHED, outcome.finalText, duration, git))
        emit(RunStreamEvent.Done)
    }

    private class Outcome(val finalText: String, val branch: String? = null, val prUrl: String? = null, val reportedDurationMs: Long? = null)

    private fun tool(id: String, name: String, status: String, vararg args: Pair<String, String>) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }),
    )

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
