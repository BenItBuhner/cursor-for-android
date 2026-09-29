package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.RecomposeCounter
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * What one publication of a streaming reply costs the main thread on the conversation screen, at 1200 and at 6000
 * turns (see [ScreenPublications]): a burst of 60 deltas after a warm-up, the clock held, the main thread's CPU time
 * inside `waitForIdle` summed over the burst and divided by the publications that reached the screen (the streaming
 * reply's recompositions). Beside it, how often the parts of the screen that no delta changes ran per publication:
 * the header, the composer, the strips over it, the queue's deliveries and the screen's own body. Every number is
 * printed as a `BENCH publication` line; the assertions hold what does not depend on the machine.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ScreenPublicationBenchmarkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        RecomposeCounter.uninstall()
    }

    /** The current thread's CPU time (`ThreadMXBean`, through reflection: the test compiles against the Android SDK). */
    private object MainCpu {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val method = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()

        fun nanos(): Long = runCatching { method?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    @Test
    fun `a streaming reply's publications at 1200 turns`() = burst(1200)

    @Test
    fun `a streaming reply's publications at 6000 turns`() = burst(6000)

    private fun burst(turns: Int) {
        val chat = ScreenPublications(turns)
        chat.seed(now)
        chat.compose(compose)
        val viewModel = chat.viewModel(compose)
        fun settle() = chat.settle(compose, viewModel)
        chat.publish()
        settle()
        assertThat(chat.shows(compose, "goal-strip") && chat.shows(compose, "load-notice")).isTrue()
        repeat(WARM_UP) { chat.publish(); Thread.sleep(20); compose.waitForIdle() }
        chat.publish()
        settle()

        // What a wait for idle costs the main thread with nothing published, taken off the burst's.
        var idle = 0L
        repeat(DELTAS) {
            Thread.sleep(20)
            val c0 = MainCpu.nanos()
            compose.waitForIdle()
            idle += MainCpu.nanos() - c0
        }

        RecomposeCounter.install()
        var cpu = 0L
        var wall = 0L
        repeat(DELTAS) {
            chat.publish()
            Thread.sleep(20)
            val c0 = MainCpu.nanos()
            val t0 = System.nanoTime()
            compose.waitForIdle()
            cpu += MainCpu.nanos() - c0
            wall += System.nanoTime() - t0
        }
        val c0 = MainCpu.nanos()
        settle()
        cpu += MainCpu.nanos() - c0
        val publications = RecomposeCounter.count("ReplyMessage")
        fun per(name: String) = "%.2f".format(RecomposeCounter.count(name).toDouble() / publications.coerceAtLeast(1))
        report(turns, "burst", "deltas=$DELTAS publications=$publications mainCpu=${"%.1f".format(cpu / 1e6)}ms wall=${"%.1f".format(wall / 1e6)}ms idleWaits=${"%.1f".format(idle / 1e6)}ms")
        report(turns, "perDelta.mainCpuMs", "%.2f".format(cpu / 1e6 / DELTAS))
        report(turns, "perPublication.mainCpuMs", "%.2f".format(cpu / 1e6 / publications.coerceAtLeast(1)))
        report(turns, "perPublication.mainCpuMsNetOfIdleWait", "%.2f".format((cpu - idle) / 1e6 / publications.coerceAtLeast(1)))
        report(
            turns,
            "perPublication.recompositions",
            "ConversationScreen=${per("ConversationScreen")} ChatHeader=${per("ChatHeader")} ComposerBox=${per("ComposerBox")} " +
                "GoalDock=${per("GoalDock")} GoalStrip=${per("GoalStrip")} LoadNoticeRow=${per("LoadNoticeRow")} QueueDeliveries=${per("QueueDeliveries")} " +
                "TranscriptRowView=${per("TranscriptRowView")}",
        )
        report(turns, "top", RecomposeCounter.top(25))
        assertThat(publications).isGreaterThan(0)
        assertThat(chat.published.items.size).isEqualTo(ScreenPublications.transcript(turns, now).size + 1)
    }

    private fun report(turns: Int, metric: String, value: Any?) = println("BENCH publication | turns=$turns | $metric | $value")

    private companion object {
        const val WARM_UP = 40
        const val DELTAS = 60
    }
}
