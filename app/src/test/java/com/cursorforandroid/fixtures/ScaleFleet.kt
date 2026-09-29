package com.cursorforandroid.fixtures

import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseFrame
import com.cursorforandroid.data.api.SseParser
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.repo.withLatestRun
import com.cursorforandroid.domain.AgentParent
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.domain.RunStatus
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

/**
 * A whole account at scale, for the UI scale benchmarks: [Size.agents] chats spread over thirty days — a handful of
 * small Projects with their workers, one Big Project (the [BigProject] coordinator, [BigProject.AGENT_ID]) with
 * [Size.bigChildren] children (workers and a few side chats), and ordinary chats for the rest — of which
 * [Size.running] are running, [Size.bigRunning] of them the Big Project's.
 *
 * The fleet moves the way a phone watching it sees it move: every [tick] (a second) each running chat writes a step
 * and its activity time moves to now, so its row climbs to the top of its group; every [FLIP_EVERY] ticks about
 * [FLIP_PERCENT]% of the chats flip between running and finished, as many each way. [deliver] hands a tick to the
 * app the way the account's list and the run monitor do (an account round for the moved rows, a run record for each
 * flip) and updates the fake server to match, so a refresh on its own schedule reads the same fleet.
 *
 * Deterministic: a fixed seed, every run of a test sees the same fleet move the same way.
 */
class ScaleFleet(val size: Size, val now: Long, seed: Int = 50) {

    /** The fleets of the scale workstream: S50 (the phone that lags today), S200, and S500 (the goal). */
    enum class Size(val agents: Int, val smallProjects: Int, val workersPerSmall: Int, val bigChildren: Int, val running: Int, val bigRunning: Int) {
        S50(agents = 50, smallProjects = 3, workersPerSmall = 5, bigChildren = 20, running = 15, bigRunning = 10),
        S200(agents = 200, smallProjects = 6, workersPerSmall = 12, bigChildren = 120, running = 60, bigRunning = 40),
        S500(agents = 500, smallProjects = 10, workersPerSmall = 15, bigChildren = 150, running = 120, bigRunning = 60);

        companion object {
            /**
             * The fleets a run measures: `SCALE_FLEETS` (a comma-separated list, e.g. `S50,S200,S500`) when set, else
             * S50 and S200 — S500 is left to a run that asks for it, to keep the CI shard short.
             */
            fun enabled(): List<Size> = System.getenv("SCALE_FLEETS")?.takeIf { it.isNotBlank() }
                ?.split(',')?.map { valueOf(it.trim().uppercase()) }
                ?: listOf(S50, S200)
        }
    }

    enum class Role(val isRoot: Boolean = false) { CHAT, SMALL_ROOT(isRoot = true), SMALL_WORKER, BIG_ROOT(isRoot = true), BIG_WORKER, BIG_SIDE_CHAT }

    class Member(
        val id: String,
        val name: String,
        val role: Role,
        val parentId: String?,
        val createdAt: Long,
        var activityAt: Long,
        var running: Boolean,
        var runSeq: Int = 1,
        var steps: Int = 0,
    ) {
        /** A run of the chat's own transcript (the Big coordinator's), which the fleet then leaves to it. */
        var transcriptRun: String? = null
        val runId: String get() = transcriptRun ?: "run-$id-$runSeq"
        val parentKind: AgentParentKind? get() = when (role) {
            Role.SMALL_WORKER, Role.BIG_WORKER -> AgentParentKind.PROJECT_WORKER
            Role.BIG_SIDE_CHAT -> AgentParentKind.SIDE_CHAT
            else -> null
        }
    }

    /** What one [tick] moved: the chats that wrote a step, and the ones that flipped (a subset of [moved]). */
    class Tick(val index: Int, val at: Long, val moved: List<Member>, val flipped: List<Member>)

    private val random = Random(seed)
    val members: List<Member>
    val byId: Map<String, Member>
    val big: Member
    var ticks = 0
        private set
    var at: Long = now
        private set

    init {
        val out = ArrayList<Member>(size.agents)
        fun age(maxDays: Int) = now - random.nextLong(60_000L, maxDays * DAY)
        big = Member(BigProject.AGENT_ID, BigProject.AGENT_NAME, Role.BIG_ROOT, null, now - 2 * DAY, now - 90_000L, running = false)
        out += big
        val sideChats = (size.bigChildren / 20).coerceAtLeast(2)
        repeat(size.bigChildren) { i ->
            val created = age(2)
            val role = if (i < sideChats) Role.BIG_SIDE_CHAT else Role.BIG_WORKER
            val name = if (role == Role.BIG_SIDE_CHAT) "Big side chat ${i + 1}" else "Big worker ${i + 1}: market scan ${100 + i}"
            out += Member("bc-scale-big-%04d".format(i), name, role, big.id, created, created + random.nextLong(0, now - created), running = false)
        }
        repeat(size.smallProjects) { p ->
            val rootCreated = age(30)
            val root = Member("bc-scale-p%02d-root".format(p), "Project ${p + 1}", Role.SMALL_ROOT, null, rootCreated, rootCreated + random.nextLong(0, now - rootCreated), running = false)
            out += root
            repeat(size.workersPerSmall) { w ->
                val created = rootCreated + random.nextLong(0, now - rootCreated)
                out += Member("bc-scale-p%02d-w%02d".format(p, w), "P${p + 1} worker ${w + 1}", Role.SMALL_WORKER, root.id, created, created + random.nextLong(0, now - created), running = false)
            }
        }
        var chat = 0
        while (out.size < size.agents) {
            val created = age(30)
            out += Member("bc-scale-chat-%04d".format(chat), "Chat ${chat + 1}: ${TOPICS[chat % TOPICS.size]}", Role.CHAT, null, created, created + random.nextLong(0, now - created), running = false)
            chat++
        }
        // The running set: the Big Project's share among its workers, the rest over the other workers and chats.
        val bigWorkers = out.filter { it.role == Role.BIG_WORKER }.shuffled(random)
        bigWorkers.take(size.bigRunning).forEach { it.running = true }
        val others = out.filter { it.role == Role.SMALL_WORKER || it.role == Role.CHAT }.shuffled(random)
        others.take(size.running - size.bigRunning).forEach { it.running = true }
        out.filter { it.running }.forEach { it.activityAt = now - random.nextLong(1_000L, 120_000L) }
        members = out
        byId = out.associateBy { it.id }
    }

    val running: List<Member> get() = members.filter { it.running }
    val bigChildren: List<Member> get() = members.filter { it.parentId == big.id }

    /** The fleet into the fake server: every chat's row, its legacy row and its latest run. */
    fun install(api: FakeCursorApi) {
        members.forEach { m -> writeTo(api, m) }
    }

    private fun writeTo(api: FakeCursorApi, m: Member) {
        val created = BigProject.iso(m.createdAt)
        val updated = BigProject.iso(m.activityAt)
        api.agents[m.id] = AgentDto(id = m.id, name = m.name, status = if (m.running) "ACTIVE" else "IDLE", createdAt = created, updatedAt = updated, latestRunId = m.runId)
        api.v0[m.id] = V0AgentDto(id = m.id, name = m.name, status = if (m.running) "RUNNING" else "FINISHED")
        if (m.transcriptRun == null) api.runs[m.runId] = run(m)
    }

    private fun run(m: Member) = RunDto(
        id = m.runId,
        agentId = m.id,
        status = if (m.running) "RUNNING" else "FINISHED",
        createdAt = BigProject.iso(m.activityAt - 60_000L),
        updatedAt = BigProject.iso(m.activityAt),
        durationMs = if (m.running) null else 60_000L,
        result = if (m.running) null else "Done.",
    )

    /** The account's record of [m], as `ListBackgroundComposers` carries it. */
    fun snapshot(m: Member) = ComposerSnapshot(
        id = m.id,
        name = m.name,
        archived = false,
        isProject = m.role.isRoot,
        parent = m.parentId?.let { AgentParent(it, m.parentKind!!) },
        status = if (m.running) RunStatus.RUNNING else RunStatus.FINISHED,
        activityAtMillis = m.activityAt,
        createdAtMillis = m.createdAt,
    )

    /**
     * The app's first sight of the fleet, once its list has loaded: the account's records for every chat (the
     * Projects flagged, every child placed) and each Project's membership, as the account layer's first round gives.
     */
    fun place(graph: AppGraph) {
        graph.agents.applyAccountSnapshots(members.map(::snapshot))
        members.filter { it.role.isRoot }.forEach { root ->
            graph.agents.applyLineage(root.id, members.filter { it.parentId == root.id }.associate { it.id to it.parentKind!! }, LineageSignal.MEMBERSHIP)
        }
    }

    /** One second on: every running chat writes a step; on every [FLIP_EVERY]th, [FLIP_PERCENT]% of the chats flip. */
    fun tick(): Tick {
        ticks++
        at += 1_000L
        val flipped = if (ticks % FLIP_EVERY == 0) flips() else emptyList()
        flipped.forEach { m ->
            m.running = !m.running
            if (m.running) m.runSeq++
        }
        val moved = members.filter { it.running }.onEach { it.steps++; it.activityAt = at } + flipped.filterNot { it.running }.onEach { it.activityAt = at }
        return Tick(ticks, at, moved, flipped)
    }

    private fun flips(): List<Member> {
        val each = (members.size * FLIP_PERCENT / 100 / 2).coerceAtLeast(1)
        val candidates = members.filter { it.role != Role.BIG_ROOT }
        return candidates.filter { it.running }.shuffled(random).take(each) + candidates.filterNot { it.running }.shuffled(random).take(each)
    }

    /**
     * [tick] into the app: the server's rows first, then the account's round over the moved rows and the run
     * monitor's word on each flipped one, the two ways a phone learns that rows moved.
     */
    fun deliver(api: FakeCursorApi, graph: AppGraph, tick: Tick) {
        tick.moved.forEach { writeTo(api, it) }
        if (tick.moved.isNotEmpty()) graph.agents.applyAccountSnapshots(tick.moved.map(::snapshot))
        tick.flipped.forEach { m -> graph.agents.patch(m.id) { it.withLatestRun(run(m)) } }
    }

    /**
     * The Big coordinator's transcript: [turns] finished turns of [BigProject.turns] ending a minute before [now], every
     * run's log in [streamer] and its record in [api]; with [live], one more turn under way that [streamStep] writes to.
     */
    fun installBigTranscript(api: FakeCursorApi, streamer: FakeRunStreamer, turns: Int, live: Boolean = false): List<BigProject.Turn> = runBlocking {
        val first = now - 60_000L - turns * BigProject.TURN_SPACING_MS
        val all = BigProject.turns(first, turns = turns)
        api.transcripts[big.id] = BigProject.v0Transcript(all)
        all.forEach { turn ->
            api.runs[turn.runId] = RunDto(id = turn.runId, agentId = big.id, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = "")
            turn.log.forEach { (event, data) -> SseParser.toEvent(SseFrame(event, null, data))?.let { streamer.emit(turn.runId, it) } }
            streamer.emit(turn.runId, RunStreamEvent.Done)
        }
        val latest = if (live) LIVE_RUN else all.last().runId
        api.runs.remove(big.runId)
        big.transcriptRun = latest
        if (live) {
            api.runs[LIVE_RUN] = RunDto(id = LIVE_RUN, agentId = big.id, status = "RUNNING", createdAt = BigProject.iso(now - 30_000L), updatedAt = BigProject.iso(now))
            api.transcripts[big.id] = api.transcripts.getValue(big.id) + com.cursorforandroid.data.api.dto.V0ConversationMessageDto("$LIVE_RUN-u", "user_message", "Keep the scanners going and report what moves.")
            streamer.emit(LIVE_RUN, RunStreamEvent.Status(LIVE_RUN, RunStatus.RUNNING))
            big.running = true
        }
        api.agents[big.id] = AgentDto(id = big.id, name = big.name, status = if (live) "ACTIVE" else "IDLE", createdAt = BigProject.iso(first), updatedAt = BigProject.iso(if (live) now else all.last().endedAt), latestRunId = latest)
        api.v0[big.id] = V0AgentDto(id = big.id, name = big.name, status = if (live) "RUNNING" else "FINISHED")
        all
    }

    /** One step of the live coordinator turn: a tool call started and finished, or about 200 characters of reply. */
    fun streamStep(streamer: FakeRunStreamer, n: Int) = runBlocking {
        if (n % 3 == 2) {
            streamer.emit(LIVE_RUN, RunStreamEvent.Assistant("Step $n: the scanner on market ${100 + n % 40} reports the book steady within the limits; the fee table row is filed and the next worker is told. "))
        } else {
            val call = { status: String -> SseParser.toEvent(SseFrame("tool_call", null, """{"callId":"live:$n","name":"read_file","status":"$status","args":{"target_file":"/cursor/stores/bc-big/notes.md"}${if (status == "completed") ""","result":{"success":{"contents":"notes line $n","totalLines":1}}""" else ""}}"""))!! }
            streamer.emit(LIVE_RUN, call("running"))
            streamer.emit(LIVE_RUN, call("completed"))
        }
    }

    companion object {
        const val DAY = 86_400_000L
        const val FLIP_EVERY = 10
        const val FLIP_PERCENT = 5
        const val LIVE_RUN = "run-big-live"
        private val TOPICS = listOf("fix the login redirect", "bump the SDK", "flaky upload test", "dark mode colours", "README pass", "retry on 502", "cache the avatars", "split the settings screen")
    }
}
