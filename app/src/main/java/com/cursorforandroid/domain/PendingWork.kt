package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * The list's work in flight, by name: every page being fetched, every pass fetching rows by id, the account's list
 * being read. The sidebar's one loading row is drawn from this and nothing else — a spinner is a request on the
 * wire, or it is not shown — and the diagnostics export names what is behind it. An item is registered for exactly
 * as long as its call or pass runs ([track]: begun before, ended in `finally`, so a cancelled pass leaves nothing
 * registered), and the row is [State.shown] only for work the user asked for: a pull's tail, a page they scrolled
 * to. A poll's round in the background registers its work all the same (the diagnostics see it) and shows nothing.
 */
class PendingWork(private val now: () -> Long = AppClock::now) {

    /** One piece of work: what it is, and when it began. */
    data class Item(val id: Long, val name: String, val startedAtMillis: Long) {
        fun ageMillis(at: Long): Long = (at - startedAtMillis).coerceAtLeast(0L)
    }

    /**
     * The work in flight, oldest first, and whether the sidebar shows it. [shown] is raised by [show] when the user
     * asks for the list to move and lowered the moment nothing is in flight: it is never true with [items] empty.
     */
    data class State(val items: List<Item> = emptyList(), val shown: Boolean = false) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    private val ids = AtomicLong()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The work in flight right now, oldest first. */
    val items: List<Item> get() = _state.value.items

    /** Registers [name] as begun. Pair with [end] in a `finally`; prefer [track]. */
    fun begin(name: String): Item {
        val item = Item(ids.incrementAndGet(), name, now())
        _state.update { it.copy(items = it.items + item) }
        return item
    }

    /** [item] is over; with nothing else in flight, the row goes with it. */
    fun end(item: Item) {
        _state.update { s ->
            val left = s.items.filterNot { it.id == item.id }
            State(left, shown = s.shown && left.isNotEmpty())
        }
    }

    /** Runs [block] as one registered item of work — cancellation included, since the item ends in `finally`. */
    suspend fun <T> track(name: String, block: suspend () -> T): T {
        val item = begin(name)
        try {
            return block()
        } finally {
            end(item)
        }
    }

    /**
     * The user asked for the list to move: the work in flight is shown until it is all over. Nothing to show when
     * nothing is in flight — a request that ended before the row could be drawn is not a spinner.
     */
    fun show() {
        _state.update { if (it.items.isEmpty()) it else it.copy(shown = true) }
    }

    /** What the sidebar's row stands for, for the diagnostics: each item's name and age. */
    fun describe(at: Long = now()): List<String> = items.map { "${it.name} (${it.ageMillis(at)} ms)" }
}
