package com.cursorforandroid.util

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A composition coroutine that hops to the IO dispatcher for a decode or a file read must come back to the main
 * thread: under the Compose test harness the composition's effects run unconfined, so a plain `withContext(IO)`
 * hands the caller — and the snapshot write after it — to the IO worker (see [ioThenMain]). The two shapes side by
 * side, under the harness's own arrangement: the plain hop resumes on the worker, the confined one on main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainConfinedTest {

    private val mainThread: Thread = Looper.getMainLooper().thread

    /** Runs the main looper's queue until [done], the way the harness's waitForIdle does between checks. */
    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "the hop never came back" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun `the caller of an IO hop resumes on the main thread, whichever thread finished the work`() {
        val gate = CompletableDeferred<Int>()
        var workedOn: Thread? = null
        var resumedOn: Thread? = null
        var answer = 0
        // The harness's own arrangement: an unconfined dispatcher, so a continuation runs wherever it is resumed.
        CoroutineScope(UnconfinedTestDispatcher()).launch {
            answer = ioThenMain {
                workedOn = Thread.currentThread()
                gate.await()
            }
            resumedOn = Thread.currentThread()
        }
        pumpUntil { workedOn != null }
        assertThat(workedOn).isNotSameInstanceAs(mainThread)
        assertThat(resumedOn).isNull()

        // The work finishes from a thread of its own; the caller is back on main through the looper.
        Thread { gate.complete(7) }.apply { name = "work-finishes"; start(); join() }
        pumpUntil { resumedOn != null }
        assertThat(resumedOn).isSameInstanceAs(mainThread)
        assertThat(answer).isEqualTo(7)
    }

    /**
     * The same rule for a flow a composition collects: one shared on the Default dispatcher — `AgentsViewModel.uiState`'s
     * shape, `flowOn(Default)` then `stateIn` — has each value set from a worker. Collected under the harness's
     * unconfined arrangement, the collector carries on on that worker, and the state it writes is written off the main
     * thread (`ShareDestinationScreenTest`'s picker, left on "Loading chats…" twice on main's CI); collected on
     * [Dispatchers.Main.immediate], as `collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)` does, it
     * takes each value on the main thread.
     */
    @Test
    fun `a flow shared on the Default dispatcher reaches an unconfined collector on a worker and a main-confined one on main`() {
        val source = MutableStateFlow(0)
        val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val shared = source.map { it }.flowOn(Dispatchers.Default).stateIn(sharing, SharingStarted.Eagerly, -1)
        val unconfined = CopyOnWriteArrayList<Pair<Int, Thread>>()
        val confined = CopyOnWriteArrayList<Pair<Int, Thread>>()
        val collectors = CoroutineScope(UnconfinedTestDispatcher())
        collectors.launch { shared.collect { unconfined += it to Thread.currentThread() } }
        collectors.launch { withContext(Dispatchers.Main.immediate) { shared.collect { confined += it to Thread.currentThread() } } }
        try {
            pumpUntil { unconfined.any { it.first == 0 } && confined.any { it.first == 0 } }
            source.value = 1
            pumpUntil { unconfined.any { it.first == 1 } && confined.any { it.first == 1 } }
            assertThat(unconfined.single { it.first == 1 }.second).isNotSameInstanceAs(mainThread)
            assertThat(confined.single { it.first == 1 }.second).isSameInstanceAs(mainThread)
        } finally {
            sharing.cancel()
            collectors.cancel()
        }
    }

    @Test
    fun `the plain hop this replaces resumes its caller on the worker under the same arrangement`() {
        val gate = CompletableDeferred<Unit>()
        var workedOn: Thread? = null
        var resumedOn: Thread? = null
        CoroutineScope(UnconfinedTestDispatcher()).launch {
            withContext(Dispatchers.IO) {
                workedOn = Thread.currentThread()
                gate.await()
            }
            resumedOn = Thread.currentThread()
        }
        val finisher = Thread { gate.complete(Unit) }.apply { name = "work-finishes" }
        finisher.start()
        finisher.join()
        pumpUntil { resumedOn != null }
        // Resumed by the completing thread, on the IO dispatcher's side of the hop: never the main thread.
        assertThat(resumedOn).isNotSameInstanceAs(mainThread)
        assertThat(workedOn).isNotSameInstanceAs(mainThread)
    }
}
