package com.cursorforandroid.promo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The capture's clock. The frame loop moves it [FRAME_MS] per captured frame, and the scripted backend waits on it
 * rather than on the wall clock, so a run's pacing in the video is its pacing here however long a frame takes to
 * render. Scripted work runs inside [scripted]; the loop only captures once all of it is waiting again ([quiet]).
 *
 * A frame is 16 ms of this clock and 1/60 s of video, so the app's time runs at 0.96 of the video's: every duration
 * the app shows is a little shorter than the footage it takes, never longer, and a stream is never faster on screen
 * than it was sent.
 */
object VirtualTime {
    const val FRAME_MS = 16L

    /** 2026-09-28 09:41:00 UTC: what the status bar and every "2m ago" in the app are measured from. */
    const val EPOCH_MS = 1_790_588_460_000L

    @Volatile
    var nowMs: Long = 0L
        private set

    private class Waiter(val at: Long, val done: CompletableDeferred<Unit>)

    private val lock = Any()
    private val waiters = ArrayList<Waiter>()
    private val busy = AtomicInteger(0)
    private val lastEmitNanos = AtomicLong(0L)
    private val emitCount = AtomicLong(0L)

    fun reset() {
        synchronized(lock) {
            nowMs = 0L
            waiters.clear()
        }
        busy.set(0)
        lastEmitNanos.set(0L)
        emitCount.set(0L)
    }

    fun wallMillis(): Long = EPOCH_MS + nowMs

    /** Runs [block] as scripted work: counted busy from here until it waits on the clock, and again after each wait. */
    suspend fun <T> scripted(block: suspend () -> T): T {
        busy.incrementAndGet()
        try {
            return block()
        } finally {
            busy.decrementAndGet()
        }
    }

    /**
     * Starts [block] as scripted work in [scope], counted busy from this call rather than from when the coroutine is
     * first dispatched, so no frame is captured in between as if nothing were about to happen.
     */
    fun launch(scope: CoroutineScope, block: suspend () -> Unit): Job {
        busy.incrementAndGet()
        return scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                block()
            } finally {
                busy.decrementAndGet()
            }
        }
    }

    /** Waits [ms] of virtual time. Only valid inside [scripted] or [launch]. */
    suspend fun sleep(ms: Long) {
        if (ms <= 0) return
        val waiter = synchronized(lock) { Waiter(nowMs + ms, CompletableDeferred()).also { waiters += it } }
        busy.decrementAndGet()
        try {
            waiter.done.await()
        } catch (c: CancellationException) {
            // Still queued: nothing counted this coroutine busy again, so it is counted here before it unwinds.
            if (synchronized(lock) { waiters.remove(waiter) }) busy.incrementAndGet()
            throw c
        }
    }

    fun advance(ms: Long) {
        val due = synchronized(lock) {
            nowMs += ms
            val ready = waiters.filter { it.at <= nowMs }.sortedBy { it.at }
            waiters.removeAll(ready.toSet())
            ready
        }
        due.forEach {
            busy.incrementAndGet()
            it.done.complete(Unit)
        }
    }

    fun quiet(): Boolean = busy.get() == 0

    fun noteEmit() {
        lastEmitNanos.set(System.nanoTime())
        emitCount.incrementAndGet()
    }

    /** How many events the scripted backend has emitted so far; a frame compares it with the last one's. */
    val emits: Long get() = emitCount.get()

    /** Wall-clock millis since the scripted backend last emitted, or null when it never has. */
    fun wallMsSinceEmit(): Long? = lastEmitNanos.get().takeIf { it != 0L }?.let { (System.nanoTime() - it) / 1_000_000 }
}
