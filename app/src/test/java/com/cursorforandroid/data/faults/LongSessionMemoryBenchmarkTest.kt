package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.Meter.Companion.mb
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bennett's 0.4.26 out-of-memory crash (2026-09-30, `internal/oom-crash-0930.md`): eight hours into a session with 31
 * agents working, the 512 MB heap full. Each working agent's turn grows its story for as long as it runs — a diff and
 * a file for every edit and read — and the stories stayed on the heap: the diffs always, the files once the session
 * had spilled its 64 MB (the budget was spent, never given back), and the story of every run the live-run table still
 * had, followed or not. Here thirty-one agents work turn after turn while the chats are opened one after another, and
 * what stays reachable is held to a budget. The server's logs are generated, not kept, so its memory stays flat.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LongSessionMemoryBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val ticks = AtomicInteger()

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun addChat(id: String, at: Long) {
        val runId = "run-done-$id"
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
        server.logs[runId] = listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":60000}""")
        server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Start."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done."))
        server.composers.putIfAbsent(id, FaultServer.Composer(id, id, activityMs = at))
    }

    /**
     * A turn's log as the API would hold it, one beat ([EVENTS_PER_BEAT] events) per tick since [startTick]: its
     * events made when asked for, so the server keeps nothing. Ended at [endBeats], it closes with its result.
     */
    private inner class GrowingLog(private val runId: String, private val startTick: Int, private val endBeats: Int? = null) : AbstractList<Pair<String, String>>() {
        private val beats: Int get() = endBeats ?: (ticks.get() - startTick).coerceAtLeast(0)
        override val size: Int get() = 1 + EVENTS_PER_BEAT * beats + (if (endBeats != null) 1 else 0)
        override fun get(index: Int): Pair<String, String> = when {
            index == 0 -> "status" to """{"runId":"$runId","status":"RUNNING"}"""
            endBeats != null && index == size - 1 -> "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":1000}"""
            else -> beat(runId, (index - 1) / EVENTS_PER_BEAT + 1, (index - 1) % EVENTS_PER_BEAT)
        }
        fun ended(): GrowingLog = GrowingLog(runId, startTick, beats)
    }

    /** One step of the agent's work: a thought, a file read, an edit, a test run, a word. Every file and diff its own. */
    private fun beat(runId: String, beat: Int, part: Int): Pair<String, String> {
        val tag = "$runId step $beat"
        return when (part) {
            0 -> "thinking" to """{"text":${q("Considering $tag. ".repeat(36))}}"""
            1 -> "tool_call" to """{"callId":"r$beat","name":"read","status":"completed","args":{"path":"src/F$beat.kt"},"result":{"content":${q("// $tag\n$FILE")}}}"""
            2 -> "tool_call" to """{"callId":"e$beat","name":"edit","status":"completed","args":{"path":"src/F$beat.kt"},"result":{"diffString":${q("# $tag\n$DIFF")}}}"""
            3 -> "tool_call" to """{"callId":"s$beat","name":"shell","status":"completed","args":{"command":"./gradlew test"},"result":{"output":${q("$tag\n" + FILE.take(6_000))},"exitCode":0}}"""
            else -> "assistant" to """{"text":${q("Step $beat looks right. ")}}"""
        }
    }

    @Test
    fun `thirty-one agents working through a long session keep the heap bounded`() = runBlocking {
        server = FaultServer(rttMillis = 2L..10L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        repeat(CHATS) { addChat("bc-s-$it", now - 100_000L - 1_000L * it) }
        val turns = IntArray(RUNNING)
        val logs = arrayOfNulls<GrowingLog>(RUNNING)
        fun startTurn(i: Int) {
            val runId = "run-s$i-${++turns[i]}"
            server.startTurnElsewhere("bc-s-$i", "Turn ${turns[i]}", runId)
            logs[i] = GrowingLog(runId, ticks.get()).also { server.logs[runId] = it }
        }
        repeat(RUNNING) { startTurn(it) }
        val rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also {
            it.now = now
            rigs += it
        }
        rig.agents.refresh()
        val sync = LiveSync(
            target = object : LiveSync.Target {
                override fun hold(agentId: String) = rig.conversations.hold(agentId)
                override fun release(agentId: String) = rig.conversations.release(agentId)
                override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
            },
            scope = rig.scope,
            settleTimeoutMs = 10_000L,
            lingerMs = 3_000L,
        )
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), MutableStateFlow(true), MutableStateFlow(true))
        rig.awaitUntil { sync.heldIds.value.isNotEmpty() }
        val base = Meter.retainedHeap()

        val work = launch(Dispatchers.Default) {
            while (true) {
                delay(BEAT_MS)
                val tick = ticks.incrementAndGet()
                repeat(RUNNING) { i ->
                    // Staggered, so turns end all through the session rather than all at once.
                    if ((tick + i * 3) % TURN_BEATS == 0) {
                        val ended = logs[i]!!
                        server.endTurn("bc-s-$i", durationMs = 1_000L)
                        server.logs["run-s$i-${turns[i]}"] = ended.ended()
                        startTurn(i)
                    }
                }
                repeat(RUNNING) { server.touch("bc-s-$it") }
            }
        }
        var peak = 0L
        var open: String? = null
        for (round in 1..ROUNDS) {
            val next = "bc-s-${(round * 7) % CHATS}"
            open?.let { rig.conversations.detach(it) }
            sync.opened(next)
            rig.conversations.attach(next)
            open = next
            delay(STEP_MS)
            if (round % 5 == 0) {
                runCatching { rig.agents.refresh() }
                val heap = Meter.retainedHeap() - base
                peak = maxOf(peak, heap)
                println("LONG round=$round ticks=${ticks.get()} retained=${heap.mb} held=${sync.heldIds.value.size} ${rig.conversations.stats()} · ${rig.hub.stats()}")
            }
        }
        work.cancel()
        open?.let { rig.conversations.detach(it) }
        val end = Meter.retainedHeap() - base
        peak = maxOf(peak, end)
        println("LONG end ticks=${ticks.get()} turns=${turns.sum()} retained=${end.mb} peak=${peak.mb} · ${rig.conversations.stats()} · ${rig.hub.stats()}")

        assertWithMessage("heap retained once the session is over").that(end).isLessThan(END_BUDGET_BYTES)
        assertWithMessage("peak heap retained over the session").that(peak).isLessThan(PEAK_BUDGET_BYTES)
    }

    private companion object {
        const val CHATS = 40
        const val RUNNING = 31
        const val EVENTS_PER_BEAT = 5
        const val BEAT_MS = 300L
        const val TURN_BEATS = 40
        const val ROUNDS = 40
        const val STEP_MS = 1_500L
        /**
         * 0.4.26 retained 140 MB at the end of the session and climbed all the way, a turn's files and diffs at a
         * time. With the texts on disk for as long as they are held, the twenty held chats' ten-turn windows among
         * them, it rests near 40 MB, and a load decoding a turn's log goes above that for a moment.
         */
        const val END_BUDGET_BYTES = 64L shl 20
        const val PEAK_BUDGET_BYTES = 128L shl 20
        val FILE = buildString { var i = 0; while (length < 16_000) append("line ${i++}: val x$i = compute($i)\n") }
        val DIFF = buildString { var i = 0; while (length < 8_000) append("@@ -$i +$i @@\n-val a$i = 1\n+val a$i = 2\n") }
        fun q(s: String) = Json.encodeToString(String.serializer(), s)
    }
}
