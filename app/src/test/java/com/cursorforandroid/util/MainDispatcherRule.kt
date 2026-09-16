package com.cursorforandroid.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `Dispatchers.Main` for one test: whatever a test before left set is reset first, the test's dispatcher is set,
 * and the original comes back afterwards — each step retried for a moment, because the test dispatcher refuses to
 * be swapped while any thread is in the middle of resuming through it (a view model coroutine finishing on an IO
 * thread, from this test or the one before). Every test class that touches Main goes through this, so none of them
 * inherits another's dispatcher or fails on its leftovers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: () -> CoroutineDispatcher = { UnconfinedTestDispatcher() }) : TestWatcher() {

    override fun starting(description: Description) {
        swap { Dispatchers.resetMain() }
        swap { Dispatchers.setMain(dispatcher()) }
    }

    override fun finished(description: Description) {
        swap { Dispatchers.resetMain() }
    }

    /** Switches Main mid-test, for a test that needs a scheduler of its own. */
    fun set(dispatcher: CoroutineDispatcher) = swap { Dispatchers.setMain(dispatcher) }

    private fun swap(block: () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IllegalStateException) {
                if (++attempt > MAX_ATTEMPTS) throw e
                Thread.sleep(SLICE_MS)
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 400
        const val SLICE_MS = 5L
    }
}
