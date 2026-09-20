package com.cursorforandroid.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * A dispatcher whose work can be held: every task handed to it while [hold] is set waits in a queue until
 * [release]. For pinning an order the scheduler would otherwise choose — a write in flight across a sign-out, a
 * finish's bookkeeping still running when a load lands — so the test decides when the held work runs.
 */
class HeldDispatcher : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val held = LinkedBlockingQueue<Runnable>()

    @Volatile var hold = false

    /** Tasks waiting for [release]. */
    val heldCount: Int get() = held.size

    val dispatcher: CoroutineDispatcher = Executor { task -> if (hold) held.add(task) else executor.execute(task) }.asCoroutineDispatcher()

    /** Lets the held tasks run, in the order they arrived, and everything after them at once. */
    fun release() {
        hold = false
        while (true) executor.execute(held.poll() ?: break)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
