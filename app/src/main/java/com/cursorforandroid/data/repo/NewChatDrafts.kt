package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.DraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The new chats written and not sent, as the sidebar lists them above its groups and the New Chat composer opens them
 * (see [DraftStore] for what each keeps on disk). Several can be kept at once; the composer has one of them open
 * ([open]), and that one is left out of the sidebar while the composer is on screen — it is the draft being written,
 * not one left behind.
 *
 * Sending one hands it to the chat it went out as ([markLaunched]): from that moment the sidebar shows the chat, not the
 * draft. It is deleted once the server has it; should the launch fail, it is a draft again, with the reason
 * ([DraftStore.Record.error]). A process that ends in between leaves the record marked; the next one settles it
 * against the chat list — the chat is there, and the draft goes, or it is not, and the draft is back.
 *
 * The rail and a composer talk through [requests]: open this draft, start a fresh one, this draft was deleted, this
 * draft's launch came back. Other
 * composers can file into the same store — the new-chat widget's quick composer writes a record with
 * [DraftStore.ORIGIN_QUICK_COMPOSER] when it is left, and the sidebar lists it like any other.
 */
class NewChatDrafts(
    private val store: DraftStore,
    private val agents: AgentRepository,
    private val launcher: ChatLauncher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    /** The drafts known in memory, most recent first; [loaded] once the disk has been read for this account. */
    data class State(val loaded: Boolean = false, val drafts: List<DraftStore.Record> = emptyList())

    /** What the sidebar asks of the New Chat composer. */
    sealed interface Request {
        /** Open [id] in the composer, keeping what the composer held as a draft of its own. */
        data class Open(val id: String) : Request

        /** A fresh composer; what it held, if anything, stays a draft. */
        data object Fresh : Request

        /** [id] was deleted from the sidebar: a composer that has it open lets it go. */
        data class Deleted(val id: String) : Request

        /** [id]'s launch did not go through: a composer with nothing in it takes it back, else it waits in the sidebar. */
        data class Returned(val id: String) : Request
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _open = MutableStateFlow<String?>(null)

    /** The draft the New Chat composer has open, if one has been set. */
    val open: StateFlow<String?> = _open.asStateFlow()

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 16)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /**
     * The open composer's own save, run now: what it holds may still be inside its debounce. Registered by the
     * composer for as long as it lives.
     */
    @Volatile var composerSave: (suspend () -> Unit)? = null

    private val loadLock = Mutex()
    /** Bumped on [reset]: a load or a save started for the account signing out lands nothing in the next one's list. */
    private val generation = AtomicInteger()
    /**
     * From a sign-out until the next account's [load]: a composer of the account that signed out, still alive for a
     * frame, writes nothing where the next account would read it.
     */
    @Volatile private var closed = false
    @Volatile private var work: CoroutineScope = workScope()

    private fun workScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    init {
        scope.launch { launcher.accepted.collect { agentId -> settleAccepted(agentId) } }
        scope.launch { launcher.failures.collect { failed -> settleFailed(failed.agentId, failed.reason, failed.asked) } }
    }

    /** Reads the drafts from disk, once per account; later calls return at once. */
    suspend fun load() {
        if (_state.value.loaded) return
        val startedIn = generation.get()
        loadLock.withLock {
            if (_state.value.loaded || generation.get() != startedIn) return
            closed = false
            val listed = store.list()
            if (generation.get() != startedIn) return
            _state.update { s ->
                // Anything saved while the disk was being read is newer than its copy on disk.
                val known = s.drafts.mapTo(HashSet()) { it.id }
                State(loaded = true, drafts = sorted(s.drafts + listed.filter { it.id !in known }))
            }
            if (_state.value.drafts.any { it.launchedAs != null }) work.launch { reconcileLaunched(startedIn) }
        }
    }

    fun record(id: String): DraftStore.Record? = _state.value.drafts.firstOrNull { it.id == id }

    /** The draft [agentId] was sent from, while the server has yet to take it. */
    fun launchedAs(agentId: String): DraftStore.Record? = _state.value.drafts.firstOrNull { it.launchedAs == agentId }

    /**
     * The rail's last open or fresh ask, until a composer takes it: the New Chat pane's view model is created only when
     * the pane is first shown, which after a process death with a chat on top is after the tap that asked for it.
     */
    @Volatile private var pending: Request? = null

    fun request(request: Request) {
        if (request is Request.Open || request is Request.Fresh) pending = request
        _requests.tryEmit(request)
    }

    /** Takes the rail's pending ask (see [pending]); a composer calls it as it starts and as it answers one. */
    fun takePending(): Request? = pending.also { pending = null }

    /** The composer has [id] open now (or none). */
    fun setOpen(id: String?) {
        _open.value = id
    }

    /**
     * Files [record] — in the sidebar at once, on disk once written. A draft that has been sent keeps being the chat's
     * whatever a save still under way says. False when the disk refused it (the account signed out meanwhile).
     */
    suspend fun save(record: DraftStore.Record): Boolean {
        if (closed) return false
        val kept = record.copy(launchedAs = record(record.id)?.launchedAs ?: record.launchedAs)
        _state.update { s -> s.copy(drafts = sorted(s.drafts.filterNot { it.id == kept.id } + kept)) }
        return store.write(kept)
    }

    /**
     * Deletes draft [id], on disk too, and tells a composer that has it open — unless [byComposer]: the composer emptied
     * it itself, and a "deleted" heard back from here would empty it again onto a new draft, taking whatever had been
     * typed into it since.
     */
    suspend fun remove(id: String, byComposer: Boolean = false) {
        _state.update { s -> s.copy(drafts = s.drafts.filterNot { it.id == id }) }
        if (!byComposer) _requests.tryEmit(Request.Deleted(id))
        store.delete(id)
    }

    /**
     * Draft [id] went out as chat [agentId]: from now the sidebar shows the chat, and the draft waits, out of sight,
     * for the server's answer. In memory at once — before the chat's row is listed — and on disk right after.
     */
    fun markLaunched(id: String, agentId: String) {
        var marked: DraftStore.Record? = null
        _state.update { s -> s.copy(drafts = s.drafts.map { if (it.id == id) it.copy(launchedAs = agentId, error = null, errorAsked = null).also { m -> marked = m } else it }) }
        marked?.let { record -> work.launch { store.write(record) } }
    }

    /** What the composer holds is written now: the app is leaving the screen, or the account is signing out. */
    suspend fun saveOpen() {
        composerSave?.invoke()
    }

    /** [saveOpen], without waiting for it. */
    fun flush() {
        work.launch { saveOpen() }
    }

    /** The account has signed out: nothing of its drafts stays in memory, and the next account's are read afresh. */
    fun reset() {
        closed = true
        pending = null
        generation.incrementAndGet()
        work.cancel()
        work = workScope()
        _open.value = null
        _state.value = State()
    }

    private suspend fun settleAccepted(agentId: String) {
        val sent = launchedAs(agentId) ?: return
        remove(sent.id)
    }

    private suspend fun settleFailed(agentId: String, reason: String?, asked: String?) {
        val sent = launchedAs(agentId) ?: return
        val back = sent.copy(launchedAs = null, error = reason, errorAsked = asked?.takeIf { reason != null })
        _state.update { s -> s.copy(drafts = sorted(s.drafts.map { if (it.id == sent.id) back else it })) }
        _requests.tryEmit(Request.Returned(sent.id))
        store.write(back)
    }

    /**
     * Drafts marked as sent by a process that did not live to hear the answer: once the list has been read from the
     * server, the ones whose chat is there go, and the rest are drafts again.
     */
    private suspend fun reconcileLaunched(startedIn: Int) {
        val listed = agents.state.first { it.hasLoaded && !it.isFromCache }
        if (generation.get() != startedIn) return
        val present = listed.agents.mapTo(HashSet()) { it.id }
        for (sent in _state.value.drafts.filter { it.launchedAs != null }) {
            val agentId = sent.launchedAs ?: continue
            if (launcher.isLaunching(agentId)) continue
            if (agentId in present) {
                remove(sent.id)
            } else {
                val back = sent.copy(launchedAs = null)
                _state.update { s -> s.copy(drafts = sorted(s.drafts.map { if (it.id == sent.id) back else it })) }
                store.write(back)
            }
        }
    }

    private fun sorted(drafts: List<DraftStore.Record>): List<DraftStore.Record> = drafts.sortedByDescending { it.updatedAtMillis }
}
