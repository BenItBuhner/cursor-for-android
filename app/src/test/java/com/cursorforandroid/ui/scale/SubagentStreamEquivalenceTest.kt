package com.cursorforandroid.ui.scale

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.fixtures.ScaleFleet
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The coordinator's 128 working subagents ([SubagentStretchScene]) under the same scripted streams ([WorkerScript],
 * five events a worker every three frames: a hundred a second at sixty frames a second), frame by frame: after each
 * frame every stretch line on screen and every subagent row on screen is read, and each frame's reading must be
 * one of the readings main's build gave at that frame or at the frame either side of it ([RECORDING], recorded on
 * main at 3388ff7f with `SUBAGENT_EQUIVALENCE_RECORD` set to the file to write). The same text, the same counts,
 * as promptly, and never a placeholder where main has the child's word.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentStreamEquivalenceTest {

    /** Effects run on the main thread in its frames, as a phone runs them (see [SubagentStreamBudgetBenchmarkTest.compose]). */
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.tearDown()
    }

    @Test
    fun `every line and row reads as main's did, frame by frame, within a frame`() {
        val record = System.getenv("SUBAGENT_EQUIVALENCE_RECORD")?.takeIf { it.isNotBlank() }
        val frames = record(settleOffFrame = record == null)
        if (record != null) {
            File(record).apply { parentFile?.mkdirs() }.writeText(encode(frames))
            println("SCALE subagent-equivalence recorded ${frames.size} frames to $record")
            return
        }
        val main = decode(checkNotNull(javaClass.getResource(RECORDING)) { "no recording at $RECORDING" }.readText())
        assertWithMessage("frames").that(frames.size).isEqualTo(main.size)
        var exact = 0
        var late = 0
        var early = 0
        frames.forEachIndexed { f, reading ->
            when (reading) {
                main[f] -> exact++
                main.getOrNull(f - 1) -> late++
                main.getOrNull(f + 1) -> early++
                else -> assertWithMessage("frame $f: main read\n${main[f].joinToString("\n")}\nthis build read\n${reading.joinToString("\n")}").fail()
            }
        }
        val changes = main.zipWithNext().count { (a, b) -> a != b }
        println("SCALE subagent-equivalence frames=${frames.size} exact=$exact oneFrameLate=$late oneFrameEarly=$early mainChanges=$changes lines=${frames.last().count { it.startsWith("line:") }} rows=${frames.last().count { it.startsWith("row:") }}")
        // The streams moved what the screen reads most frames, and every stretch still counts its sixteen at work.
        assertWithMessage("frames that changed").that(changes).isAtLeast(FRAMES / 2)
        assertWithMessage(frames.last().joinToString("\n")).that(frames.last().count { it.startsWith("line:${SubagentStretchScene.PER_STRETCH} Working") }).isEqualTo(SubagentStretchScene.STRETCHES)
    }

    /**
     * What the screen reads after each of [FRAMES] frames, the streams scripted a frame at a time. Main works each
     * child's state out inside the frame; with [settleOffFrame] each frame first waits until every child's state, worked
     * out off the main thread, shows what its run's latest snapshot says, so a slow machine reads settled frames too.
     */
    private fun record(settleOffFrame: Boolean): List<List<String>> {
        rig = ScaleRig(compose, ScaleFleet.Size.S50, now)
        SubagentStretchScene.install(rig.api, now)
        rig.start(bigTurns = 20)
        val graph = rig.graph
        val workers = SubagentStretchScene.workers
        runBlocking { workers.forEach { rig.streamer.emit("run-$it", RunStreamEvent.Status("run-$it", RunStatus.RUNNING)) } }
        val derived = AtomicLong()
        val shown = ConcurrentHashMap<String, SubagentChild>()
        val source: (String) -> Flow<SubagentChild?> = { id ->
            graph.subagentActivity.of(id).onEach { child ->
                if (child != null) shown[id] = child else shown.remove(id)
                derived.incrementAndGet()
            }
        }
        compose.setContent { SubagentStretchScene.Screen(graph, source) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(SETTLE_FRAMES) { rig.meter.frame(null) }

        var emitted = 0L
        val readings = ArrayList<List<String>>(FRAMES)
        for (f in 0 until FRAMES) {
            val due = (f + 1L) * EVENTS_PER_3_FRAMES / 3
            runBlocking {
                while (emitted < due) {
                    for (w in workers.indices) rig.streamer.emit(SubagentStretchScene.run(w), WorkerScript.event(w, emitted))
                    emitted++
                }
            }
            // The hub has every event, and whatever works them off the main thread has gone quiet.
            val deadline = System.nanoTime() + 5_000_000_000L
            while (workers.any { (graph.liveRuns.current(it, "run-$it")?.eventCount ?: 0) < emitted + 1 } && System.nanoTime() < deadline) Thread.sleep(1)
            if (settleOffFrame) {
                val live = workers.associateWith { id ->
                    graph.liveRuns.current(id, "run-$id")?.let { SubagentRows.withRun(SubagentChild(), it.items, it.finished, it.status) }
                }
                while (live.any { (id, run) -> run != null && !shows(shown[id], run) } && System.nanoTime() < deadline) Thread.sleep(1)
            }
            var last = -1L
            while (derived.get() != last && System.nanoTime() < deadline) {
                last = derived.get()
                Thread.sleep(QUIET_MS)
            }
            rig.meter.frame(null)
            readings += SubagentStretchScene.lines(compose).map { "line:$it" } + SubagentStretchScene.rows(compose).map { "row:$it" }
        }
        return readings
    }

    /** [child] carries its run's word: a child's live status, step and action outrank the list's. */
    private fun shows(child: SubagentChild?, run: SubagentChild): Boolean = child != null &&
        (run.status == null || child.status == run.status) &&
        (run.step == null || child.step == run.step) &&
        (run.action == null || child.action == run.action)

    /** One frame a line, `index<TAB>readings joined`, a frame left out when it read as the one before. */
    private fun encode(frames: List<List<String>>): String = buildString {
        append("frames=").append(frames.size).append('\n')
        frames.forEachIndexed { f, reading -> if (f == 0 || reading != frames[f - 1]) append(f).append('\t').append(reading.joinToString(SEPARATOR)).append('\n') }
    }

    private fun decode(text: String): List<List<String>> {
        val lines = text.lines().filter { it.isNotEmpty() }
        val count = lines.first().removePrefix("frames=").toInt()
        val changed = lines.drop(1).associate { line -> line.substringBefore('\t').toInt() to line.substringAfter('\t').split(SEPARATOR) }
        var current = emptyList<String>()
        return List(count) { f -> changed[f]?.also { current = it } ?: current }
    }

    private companion object {
        const val RECORDING = "/subagent-stream-equivalence/main-3388ff7f.txt"
        const val FRAMES = 600
        const val SETTLE_FRAMES = 30
        const val EVENTS_PER_3_FRAMES = 5
        const val QUIET_MS = 3L
        const val SEPARATOR = "\u001F"
    }
}
