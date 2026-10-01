package com.cursorforandroid.data.repo

import android.Manifest
import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunDigest
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.cursorforandroid.notifications.LiveNotificationRenderer
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.notifications.LivePostPacer
import com.cursorforandroid.notifications.PostBudget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

/**
 * SCALE-6: the live notification while the runs it follows stream a hundred tokens a second, through the real
 * [LiveRunHub], [RunMonitor], renderer and the service's post path (pace, build, `notify`). Every run thinks, writes,
 * reads a file and moves on, every two seconds; 1, 8, 20 and 60 agents run, of which the monitor follows up to eight.
 * Main's wiring (every token published, digested and posted) and this branch's run the same script.
 *
 * Everything runs on one virtual clock ([TestCoroutineScheduler]): the hub, the monitor, the post path and the post
 * budget, with the script's events emitted at their virtual moments. So what is posted and when, and what that costs in
 * snapshots and digests, come out the same on any machine, however loaded; only the hub's choice to publish a run's
 * words between its periods reads the real clock, which can add a snapshot but never changes a step or a post.
 *
 * What a user sees is judged against the truth: the notification the renderer draws for each run's step at each
 * moment, rebuilt from the script afterwards with the app's own timeline builder. On a device Android sheds a
 * package's updates past about five a second, so main is also shown as a device would keep its posts ([PostBudget] at
 * Android's ceiling). Printed as `SCALE` lines, with the CPU the test thread spent (all of the pipeline runs on it) for
 * the record only. Asserts that this branch posts within Android's budget with nothing shed, shows step changes at
 * least as soon as main does on a device (a single run's at once), ends on the last look, and builds, digests and
 * posts a fraction of what main does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackgroundRunSamplingBenchmarkTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private enum class Wiring { MAIN, BRANCH }

    /** A post, [atMs] after the script started (before it, for the warm-up's), and the text it shows. */
    private class Post(val atMs: Long, val notification: Notification) {
        val text: String by lazy { textOf(notification) }
    }

    private class Scripted(val atMs: Long, val run: Int, val event: RunStreamEvent)

    private class Measured(
        val wiring: Wiring,
        val followed: Int,
        val events: Int,
        val snapshots: Long,
        val digests: Int,
        /** Every post, the warm-up's included. */
        val posts: List<Post>,
        /** The notification's truth: when it changed, and to what. */
        val truth: List<Pair<Long, String>>,
        /** The test thread's CPU over the script, which is the whole pipeline's; varies by machine, so only reported. */
        val cpuMs: Long,
        val wallMs: Long,
        /** Virtual time from the followed runs' ends being streamed to the monitor dropping them. */
        val finishMs: Long,
    )

    /** What one view of the posts (as posted, or as a device keeps them) showed against the truth. */
    private class Seen(
        val posts: Int,
        val staleShare: Double,
        val meanAgeMs: Double,
        val p95AgeMs: Long,
        val meanLagMs: Double,
        val p95LagMs: Long,
        val maxLagMs: Long,
        val unshown: Int,
        /** Looks that were true for a while but never on screen: overtaken before they were posted, or shed. */
        val skipped: Int,
        val endsOnTruth: Boolean,
    )

    @Test
    fun `the live notification posts only what changes, within Android's budget, at a hundred tokens a second`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        LiveNotifications.ensureChannels(app)
        for (running in SCENARIOS) {
            val main = measure(Wiring.MAIN, running)
            val branch = measure(Wiring.BRANCH, running)
            val mainPosted = seen(main.posts, main.truth)
            val mainDevice = seen(onDevice(main.posts), main.truth)
            val branchPosted = seen(branch.posts, branch.truth)
            val branchDevice = seen(onDevice(branch.posts), branch.truth)
            report(running, main, "posted", mainPosted)
            report(running, main, "device", mainDevice)
            report(running, branch, "posted", branchPosted)

            report(running, branch, "device", branchDevice)
            // Every post one Android accepts: nothing built and sent to be shed, at most about 4.8 a second.
            assertThat(branchDevice.posts).isEqualTo(branchPosted.posts)
            assertThat(branchPosted.posts / WINDOW_SECONDS).isAtMost(PostBudget.ANDROID_MAX_PER_SECOND)
            // As lively as main on a device, or livelier: step changes shown as soon, what is shown no older.
            assertThat(branchPosted.p95LagMs).isAtMost(mainDevice.p95LagMs + LAG_SLACK_MS)
            assertThat(branchPosted.meanAgeMs).isAtMost(mainDevice.meanAgeMs + LAG_SLACK_MS)
            assertThat(branchPosted.unshown).isEqualTo(0)
            assertThat(branchPosted.skipped).isAtMost(mainDevice.skipped + SKIP_SLACK)
            // The last look is the one left up.
            assertThat(branchPosted.endsOnTruth).isTrue()
            // A single run's steps are seconds apart, well inside the budget: every one is shown the moment it happens.
            if (running == 1) {
                assertThat(branchPosted.skipped).isEqualTo(0)
                assertThat(branchPosted.maxLagMs).isEqualTo(0)
            }
            // And for less: main publishes, digests and posts per token.
            assertThat(branch.snapshots * 4).isLessThan(main.snapshots)
            assertThat(branch.digests * 4).isLessThan(main.digests)
            assertThat(branchPosted.posts * 4).isLessThan(mainPosted.posts)
            // A run's end reaches the monitor at once, not a sampling period later.
            assertThat(branch.finishMs).isEqualTo(0)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun measure(wiring: Wiring, running: Int): Measured {
        val scheduler = TestCoroutineScheduler()
        // One thread, as the service's post path is on Main; the hub and the monitor share it, on the same clock.
        val clock = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + clock)
        fun advanceTo(atMs: Long) {
            val by = atMs - scheduler.currentTime
            if (by > 0) scheduler.advanceTimeBy(by.milliseconds)
            scheduler.runCurrent()
        }
        // Setup waits on the repository's refresh, which runs on real threads; nothing timed does.
        fun settle(what: String, until: () -> Boolean) {
            val deadline = System.nanoTime() + SETUP_TIMEOUT_MS * 1_000_000
            while (!until()) {
                check(System.nanoTime() < deadline) { "$what not reached in ${SETUP_TIMEOUT_MS} ms" }
                advanceTo(scheduler.currentTime + 20)
                Thread.sleep(1)
            }
        }
        val api = FakeCursorApi()
        val streamer = FakeRunStreamer(replay = 8_192)
        val prefs = PreferencesStore(app)
        val backend = CursorBackend(api, streamer, isDemo = true)
        val session = SessionManager(SecureKeyStore(app), prefs, backend, backend)
        session.enterDemo()
        val agents = AgentRepository(session, prefs, AttachmentStore(app))
        val hub = LiveRunHub(session, agents, pollIntervalMs = 500, releaseGraceMs = 200, scope = scope)
        val monitor = RunMonitor(
            agents, hub, runRecord = { agentId, runId -> api.getRun(agentId, runId) }, refreshIntervalMs = 600_000,
            dispatcher = clock,
            sampleMs = if (wiring == Wiring.MAIN) 0L else RunMonitor.SAMPLE_MS,
        )
        try {
            val ids = (0 until running).map { "bc-$it" }
            ids.forEachIndexed { i, id -> api.addRunningAgent(id, "Worker $i", "run-$id", createdAt = createdAt(i)) }
            // A long turn already under way: the digest and the summary walk every item on each snapshot.
            ids.forEach { id -> HISTORY.forEach { streamer.emit("run-$id", it) } }
            agents.refresh()

            val posted = ArrayList<Pair<Long, Notification>>()
            val post = { notification: Notification ->
                LiveNotifications.post(app, LiveNotificationRenderer.LIVE_ID, notification)
                posted += scheduler.currentTime to notification
            }
            // The service's post path: main's posts every state the monitor publishes.
            when (wiring) {
                Wiring.MAIN -> scope.launch {
                    monitor.state.collect { state -> if (state.running.isNotEmpty()) post(LiveNotificationRenderer.live(app, state)) }
                }
                Wiring.BRANCH -> {
                    val budget = PostBudget(clockMs = { scheduler.currentTime })
                    val pacer = LivePostPacer(scope, look = { LiveNotificationRenderer.look(app, it) }, budget = budget) { look ->
                        post(LiveNotificationRenderer.live(app, look))
                    }
                    scope.launch { monitor.state.collect { state -> if (state.running.isNotEmpty()) pacer.offer(state) else pacer.flush() } }
                }
            }
            monitor.start()
            val followedCount = minOf(running, RunMonitor.MAX_TRACKED)
            settle("every followed run caught up") {
                monitor.state.value.running.count { it.phase == LivePhase.Running } >= followedCount &&
                    monitor.state.value.running.none { hub.current(it.agentId, it.runId)?.items.orEmpty().size < HISTORY_CALLS }
            }
            advanceTo(scheduler.currentTime + SETTLE_MS)
            val followed = monitor.state.value.running
            val runningCount = monitor.state.value.runningCount
            val script = script(followed.size)

            val publishedAt = hub.published.get()
            val digestsAt = monitor.digests.get()
            val cpuAt = threadCpuNanos()
            val wallAt = System.nanoTime()
            val t0 = scheduler.currentTime
            // Each event on its own, as a stream delivers them, and followed through to the post before the next.
            script.forEach { scripted ->
                advanceTo(t0 + scripted.atMs)
                streamer.emit(followed[scripted.run].runId, scripted.event)
                scheduler.runCurrent()
            }
            advanceTo(t0 + STREAM_MS + SETTLE_MS)
            val cpuMs = (threadCpuNanos() - cpuAt) / 1_000_000
            val wallMs = (System.nanoTime() - wallAt) / 1_000_000
            val snapshots = hub.published.get() - publishedAt
            val digests = monitor.digests.get() - digestsAt

            val finishedAt = scheduler.currentTime
            followed.forEach { run ->
                api.runs[run.runId] = api.runs.getValue(run.runId).copy(status = "FINISHED")
                streamer.emit(run.runId, RunStreamEvent.Result(run.runId, RunStatus.FINISHED, "Done.", 60_000, null))
                streamer.emit(run.runId, RunStreamEvent.Done)
            }
            // The other running agents take their places; only the followed ones' leaving is timed.
            val followedIds = followed.map { it.agentId }.toSet()
            scheduler.runCurrent()
            settle("the followed runs' ends") { monitor.state.value.running.none { it.agentId in followedIds } }
            val finishMs = scheduler.currentTime - finishedAt

            return Measured(
                wiring, followed.size, script.size, snapshots, digests, posted.map { (at, n) -> Post(at - t0, n) },
                truth(followed, runningCount, script), cpuMs, wallMs, finishMs,
            )
        } finally {
            monitor.stop()
            scope.cancel()
        }
    }

    /**
     * [runs] runs, each on a two-second cycle offset from the others: thinking for 0.3 s and writing for 1.2 s at a
     * hundred tokens a second, then reading a file for 0.4 s, and a beat with nothing running.
     */
    private fun script(runs: Int): List<Scripted> = buildList {
        for (run in 0 until runs) {
            var start = run * CYCLE_MS / runs
            var cycle = 0
            while (start < STREAM_MS) {
                repeat(30) { add(Scripted(start + it * TOKEN_MS, run, RunStreamEvent.Thinking("thought $cycle.$it "))) }
                repeat(120) { add(Scripted(start + 300 + it * TOKEN_MS, run, RunStreamEvent.Assistant("word$cycle.$it "))) }
                add(Scripted(start + 1_500, run, tool("r$run-c$cycle", "src/Step$cycle.kt", "running")))
                add(Scripted(start + 1_900, run, tool("r$run-c$cycle", "src/Step$cycle.kt", "completed")))
                start += CYCLE_MS
                cycle++
            }
        }
    }.filter { it.atMs < STREAM_MS }.sortedBy { it.atMs }

    /**
     * What the notification should show and when: each followed run's step as the app's timeline builder reads it off
     * the events so far, drawn by the renderer, at each moment a step changes.
     */
    private fun truth(followed: List<TrackedRun>, runningCount: Int, script: List<Scripted>): List<Pair<Long, String>> {
        val builders = followed.map { run -> TimelineBuilder.LiveRun(run.runId, nowProvider = { 0L }).also { b -> HISTORY.forEach(b::apply) } }
        val activities = builders.map { RunDigest.from(it.snapshot()).activity }.toMutableList()
        val previous = arrayOfNulls<RunStreamEvent>(followed.size)
        fun text(): String = textOf(
            LiveNotificationRenderer.live(
                app,
                LiveActivityState(
                    running = followed.mapIndexed { i, run -> run.copy(phase = LivePhase.Running, digest = RunDigest(activity = activities[i])) },
                    hasReconciled = true,
                    runningCount = runningCount,
                ),
            ),
        )
        val changes = arrayListOf(Long.MIN_VALUE to text())
        script.forEach { scripted ->
            val before = previous[scripted.run]
            builders[scripted.run].apply(scripted.event)
            previous[scripted.run] = scripted.event
            val step = scripted.event is RunStreamEvent.ToolCall ||
                (scripted.event is RunStreamEvent.Thinking && before !is RunStreamEvent.Thinking) ||
                (scripted.event is RunStreamEvent.Assistant && before !is RunStreamEvent.Assistant)
            if (step) {
                activities[scripted.run] = RunDigest.from(builders[scripted.run].snapshot()).activity
                val text = text()
                if (text != changes.last().second) changes += scripted.atMs to text
            }
        }
        return changes
    }

    /** The posts a device keeps: Android sheds an update that would put the package over five a second. */
    private fun onDevice(posts: List<Post>): List<Post> {
        val android = PostBudget(PostBudget.ANDROID_MAX_PER_SECOND)
        return posts.filter { post -> android.allows(post.atMs).also { if (it) android.record(post.atMs) } }
    }

    private fun seen(posts: List<Post>, truth: List<Pair<Long, String>>): Seen {
        fun truthIndex(t: Long): Int = truth.indexOfLast { it.first <= t }
        fun shownAt(t: Long): Post? = posts.lastOrNull { it.atMs <= t }
        // How long ago what is shown at [t] stopped being true; 0 while it is.
        fun ageAt(t: Long): Long {
            val shown = shownAt(t) ?: return t
            val now = truthIndex(t)
            if (truth[now].second == shown.text) return 0
            for (j in now - 1 downTo 0) if (truth[j].second == shown.text) return t - truth[j + 1].first
            return t - shown.atMs.coerceAtMost(t)
        }
        val ages = (0 until STREAM_MS step SAMPLE_EVERY_MS).map(::ageAt)
        val lags = ArrayList<Long>()
        var unshown = 0
        truth.forEachIndexed { i, (at, text) ->
            if (at !in 0 until STREAM_MS) return@forEachIndexed
            if (shownAt(at)?.text == text) {
                lags += 0
                return@forEachIndexed
            }
            val since = HashSet<String>()
            var j = i
            val caughtUp = posts.firstOrNull { post ->
                if (post.atMs < at) return@firstOrNull false
                while (j < truth.size && truth[j].first <= post.atMs) since += truth[j++].second
                post.text in since
            }
            if (caughtUp == null) unshown++ else lags += caughtUp.atMs - at
        }
        val skipped = truth.indices.count { i ->
            val (at, text) = truth[i]
            val until = truth.getOrNull(i + 1)?.first ?: Long.MAX_VALUE
            at in 0 until STREAM_MS && shownAt(at)?.text != text && posts.none { it.atMs in at until until && it.text == text }
        }
        val window = posts.count { it.atMs in 0 until STREAM_MS + SETTLE_MS }
        val sortedAges = ages.sorted()
        val sortedLags = lags.sorted()
        return Seen(
            posts = window,
            staleShare = ages.count { it > 0 }.toDouble() / ages.size,
            meanAgeMs = ages.average(),
            p95AgeMs = sortedAges[sortedAges.size * 95 / 100],
            meanLagMs = if (lags.isEmpty()) 0.0 else lags.average(),
            p95LagMs = if (lags.isEmpty()) 0 else sortedLags[sortedLags.size * 95 / 100],
            maxLagMs = sortedLags.lastOrNull() ?: 0,
            unshown = unshown,
            skipped = skipped,
            endsOnTruth = shownAt(STREAM_MS + SETTLE_MS - 1)?.text == truth.last().second,
        )
    }

    private fun report(running: Int, m: Measured, view: String, seen: Seen) = println(
        "SCALE live_notification running=$running followed=${m.followed} wiring=${m.wiring.name.lowercase(Locale.US)} view=$view " +
            "tokensPerRunPerSec=${1_000 / TOKEN_MS} events=${m.events} truthChanges=${m.truth.count { it.first in 0 until STREAM_MS }} " +
            "snapshots=${m.snapshots} digests=${m.digests} posts=${seen.posts} postsPerSec=${f(seen.posts / WINDOW_SECONDS)} " +
            "cpuMs=${m.cpuMs} wallMs=${m.wallMs} staleShare=${f(seen.staleShare)} meanAgeMs=${f(seen.meanAgeMs)} " +
            "p95AgeMs=${seen.p95AgeMs} meanLagMs=${f(seen.meanLagMs)} p95LagMs=${seen.p95LagMs} maxLagMs=${seen.maxLagMs} " +
            "unshown=${seen.unshown} skipped=${seen.skipped} endsOnTruth=${seen.endsOnTruth} finishMs=${m.finishMs}",
    )

    private fun tool(id: String, path: String, status: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = "read_file", status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    // Through reflection: the unit tests compile against android.jar, which has no java.lang.management.
    private val threads: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val currentThreadCpuTime = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")

    private fun threadCpuNanos(): Long = (currentThreadCpuTime.invoke(threads) as Long).coerceAtLeast(0)

    private fun f(value: Double) = String.format(Locale.US, "%.2f", value)

    private companion object {
        val SCENARIOS = listOf(1, 8, 20, 60)
        const val TOKEN_MS = 10L
        const val CYCLE_MS = 2_000L
        const val STREAM_MS = 8_000L
        const val SETTLE_MS = 1_500L
        const val WINDOW_SECONDS = (STREAM_MS + SETTLE_MS) / 1_000.0
        const val SAMPLE_EVERY_MS = 10L
        /**
         * The branch paces to 4.8 posts a second, a device keeps main's at Android's 5: its posts come about 8 ms
         * further apart, so a step change queued behind a few of them is shown that much later.
         */
        const val LAG_SLACK_MS = 40L
        /**
         * The steps main may show that the branch cannot: over the window, Android's 5 a second keeps at most this
         * many more posts than the pacer's 4.8 makes, and each shows one step at most.
         */
        val SKIP_SLACK = ceil((PostBudget.ANDROID_MAX_PER_SECOND - PostBudget.MAX_PER_SECOND) * WINDOW_SECONDS).toInt()
        const val HISTORY_CALLS = 150
        const val SETUP_TIMEOUT_MS = 60_000L

        val HISTORY: List<RunStreamEvent> = buildList {
            add(RunStreamEvent.Status(null, RunStatus.RUNNING))
            repeat(HISTORY_CALLS) { c ->
                add(RunStreamEvent.Thinking("Looking at part $c."))
                add(
                    RunStreamEvent.ToolCall(
                        SseToolCallDto(callId = "h$c", name = "read_file", status = "completed", args = buildJsonObject { put("path", JsonPrimitive("src/File$c.kt")) }),
                    ),
                )
                add(RunStreamEvent.Assistant("Step $c done. "))
            }
        }

        fun createdAt(i: Int) = String.format(Locale.US, "2026-04-13T18:%02d:%02d.000Z", 30 + i / 60, i % 60)

        fun textOf(notification: Notification): String {
            val extras = notification.extras
            return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
                .joinToString(" | ") { extras.getCharSequence(it)?.toString().orEmpty() }
        }
    }
}
