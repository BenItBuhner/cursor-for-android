package com.cursorforandroid.promo

import com.cursorforandroid.data.api.CursorApi
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.RunStreamer
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.CreateRunResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.RunGitBranchDto
import com.cursorforandroid.data.api.dto.RunGitDto
import com.cursorforandroid.data.api.dto.SseInteractionToolCallDto
import com.cursorforandroid.data.api.dto.SseInteractionUpdateDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0SourceDto
import com.cursorforandroid.data.api.dto.V0TargetDto
import com.cursorforandroid.data.demo.DemoCursorApi
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.repo.parseIsoMillis
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Marks the runs the capture started, as opposed to the demo's seeded ones. */
private const val PROMO_SCRIPT = "promo"

const val CESIUM_REPO = "https://github.com/techlitnow/cesium"
const val HERO_BRANCH = "cursor/usage-meter-4b1e"
const val HERO_PR = "https://github.com/techlitnow/cesium/pull/221"

/**
 * The demo's own API, with the two calls a send makes waiting on the capture's clock rather than the wall clock, so
 * "Starting" lasts as long on screen as it would on a phone.
 */
internal class PromoCursorApi(private val store: DemoStore, private val demo: DemoCursorApi = DemoCursorApi(store)) : CursorApi by demo {

    override suspend fun createAgent(body: CreateAgentRequestDto): CreateAgentResponseDto = withContext(Dispatchers.IO) {
        VirtualTime.scripted { VirtualTime.sleep(CREATE_AGENT_MS) }
        val id = body.agentId ?: store.nextId("bc")
        if (store.hasAgent(id)) throw CursorApiException(409, "agent_id_conflict", "An agent with this id already exists.")
        val runId = store.nextId("run")
        val nowIso = store.iso()
        val repo = body.repos?.firstOrNull()
        val agent = AgentDto(
            id = id,
            name = body.name ?: SlashCommands.strip(body.prompt.text).lines().first().take(60).ifBlank { "New agent" },
            status = "ACTIVE",
            env = body.env ?: AgentEnvDto("cloud"),
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
            script = PROMO_SCRIPT,
            servers = body.mcpServers.orEmpty().map { it.name },
        )
        CreateAgentResponseDto(agent, run)
    }

    override suspend fun createRun(id: String, body: CreateRunRequestDto): CreateRunResponseDto = withContext(Dispatchers.IO) {
        VirtualTime.scripted { VirtualTime.sleep(CREATE_RUN_MS) }
        val agent = store.agent(id) ?: throw CursorApiException(404, "agent_not_found", "Not found.")
        if (agent.status == "ARCHIVED") throw CursorApiException(409, "agent_archived", "Agent is archived.")
        store.latestRun(id)?.let { if (it.status == "RUNNING" || it.status == "CREATING") throw CursorApiException(409, "agent_busy", "Agent is busy.") }
        val nowIso = store.iso()
        val run = RunDto(id = store.nextId("run"), agentId = id, status = "CREATING", createdAt = nowIso, updatedAt = nowIso)
        store.addRun(agent, run, body.prompt.text, PROMO_SCRIPT, body.mcpServers?.map { it.name })
        store.setV0Status(id, "RUNNING")
        CreateRunResponseDto(run)
    }

    private companion object {
        const val CREATE_AGENT_MS = 700L
        const val CREATE_RUN_MS = 450L
    }
}

/**
 * Plays the capture's runs the way `DemoRunStreamer` plays the demo's, bookkeeping and all (the retained event log,
 * the run settling, the transcript's reply, replays of finished runs), but paced by [VirtualTime]: model output goes
 * out in bursts of 7 and 8 real tokens every 80 ms, and every tool takes as long as it plausibly would. The seeded
 * chats that are still running stay running, quietly, as a long run looks between steps.
 *
 * A scripted run plays once, from its first stream on, whoever is listening, as the account runs an agent whether or
 * not a phone follows it: the app lets a connection go when nothing on screen shows the run and takes it up again
 * later, and each connection is told everything said so far, then each event as it is said, as the API replays a run
 * to a stream that names no position. The demo's streamer starts its script again instead, which a few seconds of
 * script never shows.
 */
internal class PromoRunStreamer(private val store: DemoStore, private val script: PromoScript) : RunStreamer {

    private class Stopped : Exception(null, null, false, false)

    private class Outcome(val finalText: String, val branch: String? = null, val prUrl: String? = null)

    /** What one scripted run has said so far, and whether it is over. */
    private class Played {
        private val events = ArrayList<RunStreamEvent>()
        val state = MutableStateFlow(Progress(0, over = false))

        fun add(event: RunStreamEvent) = synchronized(this) {
            events += event
            state.value = Progress(events.size, over = false)
        }

        fun end() = synchronized(this) { state.value = Progress(events.size, over = true) }

        fun from(index: Int): List<RunStreamEvent> = synchronized(this) { events.subList(index.coerceAtMost(events.size), events.size).toList() }
    }

    private data class Progress(val said: Int, val over: Boolean)

    private val producers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val played = HashMap<String, Played>()

    override fun stream(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> = flow {
        synchronized(played) { played[runId] }?.let {
            follow(it)
            return@flow
        }
        val run = store.run(agentId, runId)
        if (run != null && RunStatus.parse(run.status).isTerminal) {
            replay(run)
            return@flow
        }
        if (store.script(runId) != PROMO_SCRIPT) {
            quietlyRunning(runId)
            return@flow
        }
        follow(play(agentId, runId))
    }

    private fun play(agentId: String, runId: String): Played = synchronized(played) {
        played[runId] ?: Played().also { run ->
            played[runId] = run
            VirtualTime.launch(producers) { perform(agentId, runId, run) }
        }
    }

    private suspend fun FlowCollector<RunStreamEvent>.follow(run: Played) {
        var next = 0
        while (true) {
            val progress = run.state.first { it.said > next || it.over }
            val batch = run.from(next)
            batch.forEach { emit(it) }
            next += batch.size
            if (progress.over && next >= progress.said) break
        }
        emit(RunStreamEvent.Done)
    }

    private suspend fun perform(agentId: String, runId: String, run: Played) {
        store.startLog(runId)
        val recorder = object : FlowCollector<RunStreamEvent> {
            override suspend fun emit(value: RunStreamEvent) {
                if (store.isCancelled(runId)) throw Stopped()
                store.record(runId, value)
                run.add(value)
                VirtualTime.noteEmit()
            }
        }
        val startedAt = AppClock.now()
        try {
            recorder.emit(RunStreamEvent.Status(runId, RunStatus.RUNNING))
            store.updateRun(agentId, runId) { it.copy(status = "RUNNING") }
            store.setV0Status(agentId, "RUNNING")
            val prompt = store.prompt(runId)
            val outcome = Scene(recorder, runId).run {
                when {
                    prompt.contains("usage meter", ignoreCase = true) -> hero()
                    prompt.contains("open a PR", ignoreCase = true) -> pullRequest()
                    else -> anything()
                }
            }
            val duration = AppClock.now() - startedAt
            val git = outcome.branch?.let { RunGitDto(listOf(RunGitBranchDto(repoUrl = CESIUM_REPO.removePrefix("https://"), branch = it, prUrl = outcome.prUrl))) }
            val settled = store.finishRun(agentId, runId) {
                it.copy(status = "FINISHED", updatedAt = store.iso(), durationMs = duration, result = outcome.finalText, git = git ?: it.git)
            }
            if (!settled) throw Stopped()
            store.appendAssistant(agentId, outcome.finalText)
            store.setV0Status(agentId, "FINISHED", branch = outcome.branch, prUrl = outcome.prUrl)
            recorder.emit(RunStreamEvent.Result(runId, RunStatus.FINISHED, outcome.finalText, duration, git))
        } catch (_: Stopped) {
            val result = RunStreamEvent.Result(runId, RunStatus.CANCELLED, null, AppClock.now() - startedAt, null)
            store.record(runId, result)
            run.add(result)
            VirtualTime.noteEmit()
        } finally {
            run.end()
        }
    }

    private suspend fun FlowCollector<RunStreamEvent>.quietlyRunning(runId: String) {
        VirtualTime.scripted {
            emit(RunStreamEvent.Status(runId, RunStatus.RUNNING))
            while (true) {
                VirtualTime.sleep(15_000)
                emit(RunStreamEvent.Heartbeat)
            }
        }
    }

    private suspend fun FlowCollector<RunStreamEvent>.replay(run: RunDto) {
        val log = store.eventLog(run.id)
        if (log == null || AppClock.now() - parseIsoMillis(run.updatedAt) > DemoData.STREAM_RETENTION_MS) {
            emit(RunStreamEvent.Error("stream_expired", "This run's live stream has expired."))
            return
        }
        delay(200)
        log.forEach { emit(it) }
        emit(RunStreamEvent.Done)
    }

    private inner class Scene(private val out: FlowCollector<RunStreamEvent>, private val runId: String) {

        private suspend fun emit(event: RunStreamEvent) = out.emit(event)

        private suspend fun pause(ms: Long) = VirtualTime.sleep(ms)

        private fun id(name: String) = "$runId:$name"

        /** Streams [key] as the model would: 7 then 8 tokens every 80 ms (93.75 a second on average, 98 at most). */
        private suspend fun stream(key: String, thinking: Boolean): String {
            val text = script.text(key)
            val pieces = text.pieces
            var i = 0
            var burst = 0
            while (i < pieces.size) {
                val budget = if (burst % 2 == 0) 7 else 8
                val chunk = StringBuilder()
                var tokens = 0
                while (i < pieces.size && (tokens == 0 || tokens + pieces[i].second <= budget)) {
                    chunk.append(pieces[i].first)
                    tokens += pieces[i].second
                    i++
                }
                emit(if (thinking) RunStreamEvent.Thinking(chunk.toString()) else RunStreamEvent.Assistant(chunk.toString()))
                PacingLog.record(VirtualTime.nowMs, tokens, if (thinking) "thinking" else "assistant", key)
                burst++
                pause(BURST_MS)
            }
            return text.text
        }

        private suspend fun think(key: String) = stream(key, thinking = true)

        private suspend fun say(key: String) = stream(key, thinking = false)

        private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

        private suspend fun call(id: String, name: String, args: JsonObject, ms: Long, result: JsonElement? = null) {
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = id, name = name, status = "running", args = args)))
            pause(ms)
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = id, name = name, status = "completed", args = args, result = result)))
        }

        private suspend fun read(n: Int, path: String, ms: Long) = call(id("r$n"), "read_file", args("path" to path), ms)

        private suspend fun grep(n: Int, pattern: String, ms: Long) = call(id("g$n"), "grep", args("pattern" to pattern), ms)

        private suspend fun search(n: Int, query: String, ms: Long) = call(id("q$n"), "codebase_search", args("query" to query), ms)

        private suspend fun shell(n: Int, command: String, ms: Long, stdout: String) = call(
            id("t$n"), "run_terminal_cmd", args("command" to command), ms,
            buildJsonObject { put("success", buildJsonObject { put("stdout", stdout); put("exitCode", 0) }) },
        )

        /** An edit as the stream carries one: the simplified call with its line counts, and the typed update with the diff. */
        private suspend fun edit(n: Int, path: String, ms: Long) {
            val change = script.edit(path)
            val callId = id("e$n")
            val a = args("path" to path)
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = callId, name = "edit_file", status = "running", args = a)))
            pause(ms)
            val counts = buildJsonObject { put("linesAdded", change.added); put("linesRemoved", change.removed) }
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = callId, name = "edit_file", status = "completed", args = a, result = buildJsonObject { put("success", counts) })))
            val value = buildJsonObject { put("linesAdded", change.added); put("linesRemoved", change.removed); put("diffString", change.diff) }
            emit(
                RunStreamEvent.Interaction(
                    SseInteractionUpdateDto(
                        type = SseInteractionUpdateDto.TOOL_CALL_COMPLETED,
                        callId = callId,
                        toolCall = SseInteractionToolCallDto("edit", a, buildJsonObject { put("status", "success"); put("value", value) }),
                    ),
                ),
            )
        }

        private fun taskArgs(type: String, description: String, model: String?) =
            if (model == null) args("subagent_type" to type, "description" to description) else args("subagent_type" to type, "description" to description, "model" to model)

        private suspend fun taskStart(n: Int, type: String, description: String, model: String? = null) =
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = id("s$n"), name = "task", status = "running", args = taskArgs(type, description, model))))

        private suspend fun taskDone(n: Int, type: String, description: String, model: String? = null, durationMs: Long) = emit(
            RunStreamEvent.ToolCall(
                SseToolCallDto(
                    callId = id("s$n"), name = "task", status = "completed", args = taskArgs(type, description, model),
                    result = buildJsonObject { put("success", buildJsonObject { put("durationMs", durationMs) }) },
                ),
            ),
        )

        suspend fun hero(): Outcome {
            pause(450)
            think("hero.think")
            pause(200)
            read(1, "convex/schema.ts", 650)
            read(2, "convex/usageAggregates.ts", 600)
            grep(1, "usageDaily", 550)
            search(1, "account page plan limits", 1_150)
            pause(250)
            say("hero.intro")
            pause(250)
            taskStart(1, "explore", MAP_USAGE)
            pause(220)
            taskStart(2, "generalPurpose", BUILD_METER, model = "composer-2.5")
            pause(5_280)
            taskDone(1, "explore", MAP_USAGE, durationMs = 5_500)
            pause(2_700)
            taskDone(2, "generalPurpose", BUILD_METER, model = "composer-2.5", durationMs = 8_200)
            pause(300)
            think("hero.think2")
            pause(200)
            edit(1, "convex/usage.ts", 1_300)
            edit(2, "src/components/UsageMeter.tsx", 1_700)
            edit(3, "src/routes/account.tsx", 900)
            edit(4, "src/i18n/en.json", 650)
            pause(250)
            shell(1, "npm test -- usage", 6_200, TEST_OUTPUT)
            pause(350)
            val final = say("hero.final")
            return Outcome(final, branch = HERO_BRANCH)
        }

        suspend fun pullRequest(): Outcome {
            pause(400)
            think("pr.think")
            pause(200)
            shell(2, "gh pr create --fill --base main", 2_400, HERO_PR)
            pause(300)
            val final = say("pr.final")
            return Outcome(final, branch = HERO_BRANCH, prUrl = HERO_PR)
        }

        suspend fun anything(): Outcome {
            pause(400)
            think("any.think")
            call(id("l1"), "list_dir", args("path" to "."), 500)
            pause(200)
            return Outcome(say("any.final"))
        }
    }

    private companion object {
        const val BURST_MS = 80L
        const val MAP_USAGE = "Map where usage is shown today"
        const val BUILD_METER = "Build the UsageMeter component"
        val TEST_OUTPUT = """
            > cesium@0.9.4 test
            > vitest run usage

             ✓ convex/usage.test.ts (9 tests) 412ms
             ✓ src/components/UsageMeter.test.tsx (5 tests) 188ms

             Test Files  2 passed (2)
                  Tests  14 passed (14)
               Duration  2.31s
        """.trimIndent()
    }
}
