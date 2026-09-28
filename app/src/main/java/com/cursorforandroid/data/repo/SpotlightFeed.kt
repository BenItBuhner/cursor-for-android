package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.SpotlightContent
import com.cursorforandroid.domain.SpotlightTarget
import com.cursorforandroid.domain.SpotlightView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile

/**
 * What the Spotlight notification shows for a [SpotlightTarget], frame by frame. It opens no connection of its own:
 * every run is read through [LiveRunHub.snapshots], so a run the chat screen, keep-live or the live notification's
 * monitor already follows is not streamed twice.
 *
 *  - A chat: its latest run, followed until the run ends ([Frame.Finished]).
 *  - A Project: the coordinator's run while it runs, and the running chats under it — up to [maxMembers] of them
 *    followed as unwatched subscribers, which the hub looks in on every few seconds rather than streams. Once nothing
 *    in the Project has run for [projectGraceMs] the Spotlight is over ([Frame.Ended]); the grace bridges a
 *    coordinator's turn ending and the next one, or a worker's report, starting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotlightFeed(
    /** The agent list as it stands. */
    private val rows: Flow<List<Agent>>,
    /** The chat's record from the server, for a chat the list does not hold or whose run it does not know yet. */
    private val load: suspend (agentId: String) -> Agent?,
    /** The live snapshots of one run; [watched] false lets the hub look in on it instead of streaming it. */
    private val run: (agentId: String, runId: String, startedAtMillis: Long?, watched: Boolean) -> Flow<LiveRunHub.Snapshot>,
    private val projectGraceMs: Long = PROJECT_GRACE_MS,
    private val maxMembers: Int = SpotlightContent.MAX_LINES,
) {
    constructor(agents: AgentRepository, hub: LiveRunHub) : this(
        rows = agents.state.map { it.agents },
        load = { id -> agents.loadDetail(id).getOrNull() },
        run = { agentId, runId, startedAt, watched -> hub.snapshots(agentId, runId, startedAt, if (watched) null else UNWATCHED) },
    )

    sealed interface Frame {
        /** [covered] are the chats this frame speaks for, which the live notification's roster leaves out. */
        data class Live(val view: SpotlightView, val covered: Set<String>) : Frame

        /** The chat's run ended: the finished card takes over from here. */
        data class Finished(val agent: Agent, val snapshot: LiveRunHub.Snapshot) : Frame

        /** Nothing left to follow: the chat is gone or not running, or the Project stayed idle past its grace. */
        data object Ended : Frame
    }

    fun of(target: SpotlightTarget): Flow<Frame> = if (target.isProject) project(target) else chat(target)

    private fun chat(target: SpotlightTarget): Flow<Frame> = flow {
        val id = target.agentId
        val listed = rows.first().firstOrNull { it.id == id }
        val agent = listed?.takeIf { it.latestRunId != null } ?: load(id) ?: listed
        val runId = agent?.latestRunId
        // A chat at rest has nothing live to show; following its last run would replay a finished turn as news.
        if (agent == null || runId == null || !agent.isRunning) {
            emit(Frame.Ended)
            return@flow
        }
        val row = rows.map { list -> list.firstOrNull { it.id == id } ?: agent }.distinctUntilChanged()
        emitAll(
            combine(run(id, runId, null, true), row) { snapshot, current -> snapshot to current }
                .transformWhile { (snapshot, current) ->
                    if (snapshot.finished) {
                        emit(Frame.Finished(current, snapshot))
                        false
                    } else {
                        val view = SpotlightContent.ofRun(id, current.name, snapshot.items, snapshot.startedAtMillis, snapshot.reconnecting)
                        emit(Frame.Live(view, setOf(id)))
                        true
                    }
                },
        )
    }

    /** A chat as the Project's frames need it: stable across list refreshes that change nothing shown. */
    private data class Chat(val id: String, val name: String, val runId: String?)

    /** Who in the Project is running, and which of them are followed: what the Project's frames are built from. */
    private data class Members(
        val coordinator: Chat,
        /** The coordinator's run while it runs, else null. */
        val coordinatorRun: String?,
        val running: List<Chat>,
        val followed: List<Chat>,
        val chatCount: Int,
    )

    private fun project(target: SpotlightTarget): Flow<Frame> = rows
        .map { list -> members(list, target.agentId) }
        .distinctUntilChanged()
        .flatMapLatest { members ->
            if (members == null) return@flatMapLatest flowOf(Frame.Ended)
            val covered = setOf(target.agentId) + members.running.map { it.id }
            if (members.coordinatorRun == null && members.running.isEmpty()) {
                return@flatMapLatest flow {
                    emit(Frame.Live(view(target, members, null, emptyList()), covered))
                    delay(projectGraceMs)
                    emit(Frame.Ended)
                }
            }
            val coordinatorFlow: Flow<SpotlightContent.Member?> = members.coordinatorRun
                ?.let { runId -> run(members.coordinator.id, runId, null, true).map { it.member(members.coordinator.name) } }
                ?: flowOf(null)
            val memberFlows = members.followed.map { chat ->
                run(chat.id, checkNotNull(chat.runId), null, false).map { it.member(chat.name) }
            }
            val followedFlow: Flow<List<SpotlightContent.Member>> =
                if (memberFlows.isEmpty()) flowOf(emptyList()) else combine(memberFlows) { it.toList() }
            combine(coordinatorFlow, followedFlow) { coordinator, followed ->
                Frame.Live(view(target, members, coordinator, followed), covered)
            }
        }
        .distinctUntilChanged()

    private fun members(list: List<Agent>, projectId: String): Members? {
        val coordinator = list.firstOrNull { it.id == projectId && !it.isArchived } ?: return null
        val chats = list.filter { it.parent?.id == projectId && !it.isArchived }
        val running = chats.filter { it.isRunning }.sortedByDescending { it.updatedAtMillis }.map { it.chat() }
        return Members(
            coordinator = coordinator.chat(),
            coordinatorRun = coordinator.latestRunId?.takeIf { coordinator.isRunning },
            running = running,
            followed = running.filter { it.runId != null }.take(maxMembers),
            chatCount = chats.size,
        )
    }

    private fun Agent.chat() = Chat(id, name, latestRunId)

    private fun view(target: SpotlightTarget, members: Members, coordinator: SpotlightContent.Member?, followed: List<SpotlightContent.Member>): SpotlightView {
        // Running chats the hub has not reported on yet still get their line, from the list's word alone.
        val lines = members.running.take(maxMembers).map { chat ->
            followed.firstOrNull { it.agentId == chat.id } ?: SpotlightContent.Member(chat.id, chat.name, null, null)
        }
        return SpotlightContent.ofProject(
            projectId = target.agentId,
            title = members.coordinator.name,
            coordinator = coordinator,
            running = lines,
            runningCount = members.running.size,
            chatCount = members.chatCount,
            fallbackStartedAtMillis = target.startedAtMillis,
        )
    }

    private fun LiveRunHub.Snapshot.member(name: String) = SpotlightContent.Member(agentId, name, items, startedAtMillis)

    companion object {
        const val PROJECT_GRACE_MS = 30_000L
        private val UNWATCHED: StateFlow<Boolean> = MutableStateFlow(false)
    }
}
