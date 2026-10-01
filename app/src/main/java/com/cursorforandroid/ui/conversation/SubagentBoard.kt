package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.cursorforandroid.domain.SubagentChild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a transcript's cloud subagents stand, one live state per child however many places draw it — a stretch's
 * line counting it, its row once the stretch opens, the same row in a second pane — all fed by one collection of
 * [source] (the app's [SubagentActivity][com.cursorforandroid.data.repo.SubagentActivity], which works each event
 * off the main thread) and published together, once a frame: whatever the children said since the last frame lands
 * on the main thread as one pass over the children that moved, just before the frame recomposes, never an event at
 * a time. Each child's newest word is the one published; nothing is dropped that the frame would have drawn.
 *
 * A child is followed for as long as anything composed follows it (see [followed]), as before: a row scrolled out of
 * a lazy list lets go of its child unless its stretch's line still follows it.
 *
 * Main thread only, but for the collections, which may say what they have on any thread.
 */
@Stable
internal class SubagentBoard private constructor(private val source: (agentId: String) -> Flow<SubagentChild?>) {

    /** One child's place on the board. */
    inner class Slot(val agentId: String) {
        internal val state: MutableState<SubagentChild?> = mutableStateOf(null)
        @Volatile internal var latest: SubagentChild? = null
        internal val dirty = AtomicBoolean(false)
        internal var holders = 0
        internal var job: Job? = null
    }

    private val slots = HashMap<String, Slot>()
    private val moved = ConcurrentLinkedQueue<Slot>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    /** Held by the one follower whose frames publish the board; the rest wait their turn. */
    private val pumping = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    internal fun slot(agentId: String): Slot = slots.getOrPut(agentId) { Slot(agentId) }

    /** Follows [slot]'s child until cancelled; one of the followers publishes the whole board meanwhile, a frame at a time. */
    internal suspend fun hold(slot: Slot) {
        acquire(slot)
        try {
            pumping.withLock {
                while (true) {
                    if (moved.isEmpty()) signal.receive()
                    withFrameNanos { publish() }
                }
            }
        } finally {
            release(slot)
        }
    }

    private fun acquire(slot: Slot) {
        boards.getOrPut(source) { this }
        slots.getOrPut(slot.agentId) { slot }
        if (slot.holders++ > 0) return
        slot.job = scope.launch {
            source(slot.agentId).collect { child ->
                slot.latest = child
                if (slot.dirty.compareAndSet(false, true)) {
                    moved.add(slot)
                    signal.trySend(Unit)
                }
            }
        }
    }

    private fun release(slot: Slot) {
        if (--slot.holders > 0) return
        slot.job?.cancel()
        slot.job = null
        if (slots[slot.agentId] === slot) slots.remove(slot.agentId)
        if (slots.isEmpty() && boards[source] === this) boards.remove(source)
    }

    private fun publish() {
        while (true) {
            val slot = moved.poll() ?: return
            slot.dirty.set(false)
            slot.state.value = slot.latest
        }
    }

    companion object {
        /** The board of each source followed on screen; a bound `SubagentActivity::of` is equal to another of the same object. */
        private val boards = HashMap<(String) -> Flow<SubagentChild?>, SubagentBoard>()

        fun of(source: (agentId: String) -> Flow<SubagentChild?>): SubagentBoard = boards.getOrPut(source) { SubagentBoard(source) }
    }
}

/** The board of the transcript's [TranscriptControls.subagentActivity]. */
@Composable
internal fun rememberSubagentBoard(): SubagentBoard {
    val source = LocalTranscriptControls.current.subagentActivity
    return remember(source) { SubagentBoard.of(source) }
}

/** [agentId]'s live state, followed while this is composed; null until its child has said anything. */
@Composable
internal fun SubagentBoard.followed(agentId: String): State<SubagentChild?> {
    val slot = remember(this, agentId) { slot(agentId) }
    LaunchedEffect(slot) { hold(slot) }
    return slot.state
}
