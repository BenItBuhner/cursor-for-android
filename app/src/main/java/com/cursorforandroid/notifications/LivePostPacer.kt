package com.cursorforandroid.notifications

import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Paces the live notification's posts. The monitor's state changes with every word a followed run streams, but the
 * notification shows each run's step, not its words (see [LiveNotificationRenderer.look]), so most of those changes
 * would post an identical notification. And Android sheds a package's updates while it posts faster than about five a
 * second (see [PostBudget]), after each has been built and sent, leaving up whatever the last accepted one said.
 * [budget] is the service's, which its other posts count against too.
 *
 * So a state is posted only when its [look] differs from the one shown: at once while the budget allows, else the
 * latest look as soon as it does. A run leaving the notification or being stopped, and a [flush], post at once
 * whatever the budget; when that was over it, the look is posted again as soon as the budget allows, in case Android
 * shed it. The last look is always the one left showing.
 *
 * Not thread-safe: [offer], [flush] and [cancel] are called on [scope]'s single thread, as the service's are on Main.
 */
internal fun <L : Any> LivePostPacer(
    scope: CoroutineScope,
    look: (LiveActivityState) -> L,
    budget: PostBudget = PostBudget(),
    post: (L) -> Unit,
): PostPacer<LiveActivityState, L> = PostPacer(scope, look, ::runEnds, budget, post)

/** A run leaving the notification, or being stopped. */
private fun runEnds(shown: LiveActivityState, next: LiveActivityState): Boolean {
    val phases = next.running.associate { it.agentId to it.phase }
    return shown.running.any { run ->
        val phase = phases[run.agentId]
        phase == null || (phase != run.phase && (phase == LivePhase.Stopping || phase == LivePhase.Finished))
    }
}

/**
 * [LivePostPacer]'s pacing for any notification that shows a [look] of states [S]: posted when the look changes, within
 * [budget], except that a state that [ends] what was shown posts at once.
 */
internal class PostPacer<S : Any, L : Any>(
    private val scope: CoroutineScope,
    private val look: (S) -> L,
    private val ends: (shown: S, next: S) -> Boolean,
    private val budget: PostBudget = PostBudget(),
    private val post: (L) -> Unit,
) {
    private var shown: S? = null
    private var shownLook: L? = null
    private var pending: S? = null
    private var pendingLook: L? = null
    /** The last post went out over the budget: post the shown look again once it allows. */
    private var again = false
    private var wake: Job? = null

    fun offer(state: S) {
        val next = look(state)
        val ending = shown?.let { ends(it, state) } == true
        if (!ending && next == shownLook) {
            pending = null
            pendingLook = null
            return
        }
        if (ending || budget.allows()) {
            again = !budget.allows()
            emit(state, next)
        } else {
            pending = state
            pendingLook = next
        }
        schedule()
    }

    /** Posts what the budget is holding back now. */
    fun flush() {
        val state = pending ?: return
        again = !budget.allows()
        emit(state, pendingLook ?: look(state))
        schedule()
    }

    /** Drops what the budget is holding back. */
    fun cancel() {
        wake?.cancel()
        wake = null
        pending = null
        pendingLook = null
        again = false
    }

    private fun schedule() {
        if (pending == null && !again) return
        if (wake?.isActive == true) return
        wake = scope.launch {
            while (!budget.allows()) delay(budget.waitMs())
            wake = null
            if (pending == null && !again) return@launch
            val state = pending ?: shown ?: return@launch
            val next = pendingLook ?: shownLook ?: return@launch
            again = false
            emit(state, next)
        }
    }

    private fun emit(state: S, next: L) {
        shown = state
        shownLook = next
        pending = null
        pendingLook = null
        budget.post { post(next) }
    }
}

/**
 * Android's estimate of how fast a package posts notifications (AOSP `RateEstimator`, as `NotificationManagerService`
 * keeps it): a weighted average of the gaps between the posts it accepted, each new gap weighing [ALPHA]'s complement.
 * An update that would put the rate over five a second is shed, and does not count. This keeps the same estimate of
 * the posts it is told of, against [maxPerSecond], so that nothing posted within it is shed.
 *
 * A gap is measured from when the last post had been sent to when the next is about to be built, so it is never longer
 * than the one Android measures between the two arriving; every post the package makes belongs here, the live
 * notification's and the finished cards alike, since Android counts them all.
 */
internal class PostBudget(
    private val maxPerSecond: Double = MAX_PER_SECOND,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var sentAtMs = -1L
    private var gapSeconds = FIRST_GAP_SECONDS

    private fun gapIfPostedAt(nowMs: Long): Double =
        if (sentAtMs < 0) gapSeconds else ALPHA * gapSeconds + (1 - ALPHA) * maxOf((nowMs - sentAtMs) / 1_000.0, MIN_GAP_SECONDS)

    fun allows(nowMs: Long = clockMs()): Boolean = sentAtMs < 0 || gapIfPostedAt(nowMs) * maxPerSecond >= 1.0 - EPSILON

    /** How long after [nowMs] a post is allowed; 0 when it is now. */
    fun waitMs(nowMs: Long = clockMs()): Long {
        if (allows(nowMs)) return 0
        val sinceLast = (1.0 / maxPerSecond - ALPHA * gapSeconds) / (1 - ALPHA)
        return (ceil(sinceLast * 1_000).toLong() - (nowMs - sentAtMs)).coerceAtLeast(1)
    }

    /** Counts a post that [send] builds and sends. */
    fun <T> post(send: () -> T): T {
        val startedAt = clockMs()
        val result = send()
        record(startedAt, clockMs())
        return result
    }

    fun record(startedAtMs: Long, sentAtMs: Long = startedAtMs) {
        gapSeconds = gapIfPostedAt(startedAtMs)
        this.sentAtMs = sentAtMs
    }

    companion object {
        /** A little under Android's five, for an estimate that is not quite Android's (another build, another clock). */
        const val MAX_PER_SECOND = 4.8
        /** `NotificationManagerService`'s default `mMaxPackageEnqueueRate`. */
        const val ANDROID_MAX_PER_SECOND = 5.0
        private const val ALPHA = 0.7
        private const val MIN_GAP_SECONDS = 0.0005
        /** Before any post: as if the last came a second before, rather than Android's more generous start. */
        private const val FIRST_GAP_SECONDS = 1.0
        private const val EPSILON = 1e-9
    }
}
