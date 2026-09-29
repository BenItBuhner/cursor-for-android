package com.cursorforandroid.util

import android.os.Handler
import android.view.Choreographer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Mends the two process-wide pieces of Compose that one test can break for every test after it in the same JVM.
 *
 * Compose applies a snapshot write made outside a composition through `GlobalSnapshotManager`: a global write observer
 * sends to a channel, and a coroutine on [AndroidUiDispatcher.Main] receives and calls `Snapshot.sendApplyNotifications`.
 * Both are started once per JVM (per Robolectric sandbox), and neither recovers:
 *
 * - The dispatcher runs its queue from one Handler message and its frame work from one Choreographer callback, and
 *   records that each is posted (`scheduledTrampolineDispatch`, `scheduledFrameDispatch`) so later work only queues
 *   behind it. Robolectric empties both queues for the next test but leaves the flags set: a write landing after a
 *   test's final idle (a worker that outlived its test) leaves the dispatcher counting on a message or frame that is
 *   gone, or one timed before the clock went back, and it never posts again - nor does an activity's own recomposer,
 *   which waits for its frames there.
 * - The receiving coroutine dies with the first exception out of `sendApplyNotifications`: a worker writing while the
 *   test harness resumes the recomposer inline (its `ApplyingContinuationInterceptor` applies again on every resume)
 *   ends in a `StackOverflowError` there. The channel is cancelled with it, the manager stays "started", and its write
 *   observer - first in the list, sharing the manager's `sent` flag - keeps swallowing every later write.
 *
 * Either way no global write is applied outside a frame again, `Snapshot.current.hasPendingChanges()` stays true, and
 * every Compose test left in the JVM pumps frames until Espresso's 60 s idle timeout (`AppNotIdleException`): the
 * cascade that ran CI shard jobs into their 30-minute limit.
 *
 * The loopers are reset after Robolectric's own setup hooks and a worker's write can land at any point around that, so
 * the mending happens where every wait for idle passes, on the main thread: Compose's idling strategy drains the main
 * looper through `Espresso.onIdle()` on each turn, and Robolectric's Espresso asks the registered idling resources there.
 * The one registered here is never busy; asked, it re-posts the dispatcher's message when the dispatcher counts on one,
 * and starts the manager again when its channel is closed. Registered in `META-INF/services`.
 */
class UiDispatcherRearm : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val registry = IdlingRegistry.getInstance()
        if (registry.resources.none { it is Guard }) registry.register(Guard)
    }

    private object Guard : IdlingResource {
        override fun getName() = "UiDispatcherRearm"
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) = Unit
        override fun isIdleNow(): Boolean {
            mend()
            return true
        }
    }

    companion object {
        /**
         * The mending itself, on the main thread: for a test that runs the main looper by hand instead of through
         * Espresso (`shadowOf(mainLooper).idle()`), where the idling resource is never asked.
         */
        fun mend() {
            rearmDispatcher()
            restartSnapshotManager()
        }

        private fun <T> reflect(what: String, find: () -> T): T = runCatching(find).getOrElse {
            throw IllegalStateException("$what is gone (a Compose update?): UiDispatcherRearm needs a new look", it)
        }

        private fun field(owner: String, name: String): Field =
            reflect("$owner.$name") { Class.forName(owner).getDeclaredField(name).apply { isAccessible = true } }

        private const val DISPATCHER = "androidx.compose.ui.platform.AndroidUiDispatcher"
        private const val MANAGER = "androidx.compose.ui.platform.GlobalSnapshotManager"
        private const val SNAPSHOTS = "androidx.compose.runtime.snapshots.SnapshotKt"

        private val mainDelegate = field(DISPATCHER, "Main\$delegate")
        private val dispatcherLock = field(DISPATCHER, "lock")
        private val handler = field(DISPATCHER, "handler")
        private val dispatchCallback = field(DISPATCHER, "dispatchCallback")
        private val trampolineScheduled = field(DISPATCHER, "scheduledTrampolineDispatch")
        private val frameScheduled = field(DISPATCHER, "scheduledFrameDispatch")
        private val toRunOnFrame = field(DISPATCHER, "toRunOnFrame")

        private val manager: Any = field(MANAGER, "INSTANCE").get(null)
        private val started = field(MANAGER, "started").get(null) as AtomicBoolean
        private val sent = field(MANAGER, "sent").get(null) as AtomicBoolean
        private val ensureStarted: Method = reflect("$MANAGER.ensureStarted") { Class.forName(MANAGER).getMethod("ensureStarted") }
        private val managerObserverChannel = field("$MANAGER\$ensureStarted\$2", "\$channel")
        private val globalWriteObservers = field(SNAPSHOTS, "globalWriteObservers")
        private val snapshotLock: Any = field(SNAPSHOTS, "lock").get(null)

        private fun rearmDispatcher() {
            if (!(mainDelegate.get(null) as Lazy<*>).isInitialized()) return
            val dispatcher = AndroidUiDispatcher.Main[kotlin.coroutines.ContinuationInterceptor] as AndroidUiDispatcher
            synchronized(dispatcherLock.get(dispatcher)) {
                // Posted whether or not the queues hold one already: a message or frame callback posted during the
                // reset keeps its time from before the clock went back, and waits for a time the paused clock will
                // not reach. A second run finds nothing queued and does nothing; only a lost run is harmful.
                val callback = dispatchCallback.get(dispatcher)
                if (trampolineScheduled.getBoolean(dispatcher)) (handler.get(dispatcher) as Handler).post(callback as Runnable)
                // The frame side only when a frame is awaited (a window recomposer's `withFrameNanos`): posting on
                // every idle would keep a frame pending for no one.
                if (frameScheduled.getBoolean(dispatcher) && (toRunOnFrame.get(dispatcher) as List<*>).isNotEmpty()) {
                    dispatcher.choreographer.postFrameCallback(callback as Choreographer.FrameCallback)
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        private fun restartSnapshotManager() {
            val dead = (globalWriteObservers.get(null) as List<*>).filter { observer ->
                observer != null && managerObserverChannel.declaringClass.isInstance(observer) &&
                    (managerObserverChannel.get(observer) as SendChannel<*>).isClosedForSend
            }.toSet()
            if (dead.isEmpty()) return
            synchronized(snapshotLock) { globalWriteObservers.set(null, (globalWriteObservers.get(null) as List<*>) - dead) }
            sent.set(false)
            started.set(false)
            ensureStarted.invoke(manager)
            Snapshot.sendApplyNotifications()
        }
    }
}
