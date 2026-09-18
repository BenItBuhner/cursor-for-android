package com.cursorforandroid.domain

import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the last refresh cost, stage by stage, for the diagnostics export: which passes ran, how many network calls
 * each made, when each started and ended, when the spinner was let go and when the last of it settled. One
 * recorder is shared by the list, the account round and the Projects (see `AppGraph`); a pull begins a new record
 * and every pass that follows it appends. Nothing here is the user's text.
 */
class RefreshStats(private val now: () -> Long = AppClock::now) {

    /** One pass of a refresh: what it read, how many calls it took, and its span. */
    data class Stage(val name: String, val calls: Int, val startedAtMillis: Long, val endedAtMillis: Long, val note: String? = null) {
        val durationMs: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0L)
    }

    data class Snapshot(
        val startedAtMillis: Long,
        /** When the pull-to-refresh indicator was let go (the first page and the status scan landed); null until it was. */
        val spinnerReleasedAtMillis: Long? = null,
        val stages: List<Stage> = emptyList(),
    ) {
        val totalCalls: Int get() = stages.sumOf { it.calls }
        val spinnerMs: Long? get() = spinnerReleasedAtMillis?.let { it - startedAtMillis }
        /** When the last pass recorded so far ended — the refresh's settle, once nothing else appends; null before any pass. */
        val settledAtMillis: Long? get() = stages.maxOfOrNull { it.endedAtMillis }
        val settledMs: Long? get() = settledAtMillis?.let { it - startedAtMillis }
    }

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** A refresh begins: the record starts over. */
    fun begin(): Long {
        val at = now()
        _snapshot.value = Snapshot(startedAtMillis = at)
        return at
    }

    /** Appends one pass to the current record (a pass with no record open — a poll before any pull — opens one). */
    fun stage(name: String, calls: Int, startedAtMillis: Long, endedAtMillis: Long = now(), note: String? = null) {
        _snapshot.update { current ->
            val base = current ?: Snapshot(startedAtMillis = startedAtMillis)
            base.copy(stages = base.stages + Stage(name, calls, startedAtMillis, endedAtMillis, note))
        }
    }

    /** Times [block] as one pass, counting its calls from what it returns. */
    suspend fun <T> timed(name: String, calls: (T) -> Int, note: (T) -> String? = { null }, block: suspend () -> T): T {
        val startedAt = now()
        val result = block()
        stage(name, calls(result), startedAt, now(), note(result))
        return result
    }

    fun spinnerReleased() {
        _snapshot.update { it?.copy(spinnerReleasedAtMillis = it.spinnerReleasedAtMillis ?: now()) }
    }
}
