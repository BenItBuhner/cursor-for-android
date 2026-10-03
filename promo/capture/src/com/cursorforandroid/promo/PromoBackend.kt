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
const val HERO_BRANCH = "cursor/dark-mode-5c2a"
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
        )
        val run = RunDto(id = runId, agentId = id, status = "CREATING", createdAt = nowIso, updatedAt = nowIso)
        store.addAgent(
            agent = agent,
            legacy = V0AgentDto(id, agent.name, "CREATING", repo?.let { V0SourceDto(it.url, it.startingRef) }, V0TargetDto(url = agent.url), null, nowIso),
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
 *
 * The hero run takes a steer at a step boundary, as an agent reads one: what [steering] holds for it once its checks
 * have run, filed with the conversation and placed in the turn before the run says anything about it.
 */
internal class PromoRunStreamer(
    private val store: DemoStore,
    private val script: PromoScript,
    private val steering: PromoSteering,
    private val review: PromoReview,
) : RunStreamer {

    /** The chat the hero run plays in, once it has started. */
    @Volatile
    var heroAgent: String? = null
        private set

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
                if (prompt.equals(script.text("hero.prompt").text, ignoreCase = true)) hero(agentId) else anything()
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

        private suspend fun shell(n: Int, command: String, ms: Long, stdout: String) = call(
            id("t$n"), "run_terminal_cmd", args("command" to command), ms,
            buildJsonObject { put("success", buildJsonObject { put("stdout", stdout); put("exitCode", 0) }) },
        )

        /**
         * An edit as the stream carries one: the simplified call with its line counts, and the typed update with the
         * diff. The model writes the edit's added lines while it runs, at the stream's own pace, and the edit takes at
         * least [ms] in all.
         */
        private suspend fun edit(n: Int, key: String, ms: Long) {
            val change = script.edit(key)
            val callId = id("e$n")
            val a = args("path" to change.path)
            emit(RunStreamEvent.ToolCall(SseToolCallDto(callId = callId, name = "edit_file", status = "running", args = a)))
            var left = change.tokens
            var burst = 0
            var spent = 0L
            while (left > 0) {
                val tokens = minOf(left, if (burst % 2 == 0) 7 else 8)
                PacingLog.record(VirtualTime.nowMs, tokens, "edit", key)
                left -= tokens
                burst++
                pause(BURST_MS)
                spent += BURST_MS
            }
            if (spent < ms) pause(ms - spent)
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

        /**
         * The step boundary where the run reads what was steered into it, if anything comes within [STEER_WAIT_MS]:
         * filed with the conversation, then left until the app has placed it in the turn. False when nothing came.
         */
        private suspend fun steered(agentId: String): Boolean {
            var waited = 0L
            var text = steering.take(agentId)
            while (text == null) {
                if (waited >= STEER_WAIT_MS) return false
                pause(POLL_MS)
                waited += POLL_MS
                text = steering.take(agentId)
            }
            steering.deliver(agentId, text)
            waited = 0L
            while (!steering.placed(agentId)) {
                check(waited < STEER_WAIT_MS) { "The steer was never placed in the turn" }
                pause(POLL_MS)
                waited += POLL_MS
            }
            return true
        }

        suspend fun hero(agentId: String): Outcome {
            heroAgent = agentId
            pause(400)
            say("hero.intro")
            pause(200)
            edit(1, "src/styles/theme.css", 700)
            // Long enough for the first diff to be read and closed before the next edit joins it into a stretch.
            pause(2_780)
            edit(2, "src/hooks/useTheme.ts", 900)
            edit(3, "src/components/ThemeToggle.tsx", 900)
            edit(4, "src/components/Header.tsx", 500)
            pause(200)
            shell(1, "npm run typecheck", 500, TYPECHECK_OUTPUT)
            if (steered(agentId)) {
                pause(250)
                say("hero.adapt")
                pause(1_200)
                edit(5, "src/hooks/useTheme.ts#system", 900)
            }
            pause(200)
            shell(2, "npm test", 2_600, TEST_OUTPUT)
            shell(3, "gh pr create --fill", 1_600, HERO_PR)
            review.opened(AppClock.now())
            shell(4, "gh pr checks 221 --watch", PromoReview.CHECKS_MS + 200, CHECKS_OUTPUT)
            pause(200)
            val final = say("hero.final")
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
        const val POLL_MS = VirtualTime.FRAME_MS
        const val STEER_WAIT_MS = 20_000L
        val TYPECHECK_OUTPUT = """
            > cesium@0.9.4 typecheck
            > tsc --noEmit
        """.trimIndent()
        val TEST_OUTPUT = """
            > cesium@0.9.4 test
            > vitest run

             ✓ src/components/Header.test.tsx (3 tests) 41ms
             ✓ src/pages/Dashboard.test.tsx (7 tests) 126ms

             Test Files  2 passed (2)
                  Tests  10 passed (10)
               Duration  1.08s
        """.trimIndent()
        val CHECKS_OUTPUT = """
            ✓  typecheck  GitHub Actions
            ✓  test       GitHub Actions
            ✓  build      GitHub Actions

            All checks were successful
        """.trimIndent()
    }
}
