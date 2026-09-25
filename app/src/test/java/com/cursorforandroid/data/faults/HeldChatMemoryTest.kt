package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * Bennett's 0.4.1 out-of-memory crash (internal/crash-reports/v0.4.1-oom-2026-09-25.txt): with Keep chats live on,
 * the chats held for him — his running agents, their coordinators, the few he opened last — kept the trace of every
 * turn their agents finished, past the window they show, for as long as the app stayed open. A trace is a turn's tool
 * calls with their whole results, so forty working agents filled a 256 MB heap within the hour. The window is all a
 * chat nobody looks at keeps; the disk has the rest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HeldChatMemoryTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun q(s: String) = Json.encodeToString(String.serializer(), s)

    private fun addChat(id: String, at: Long) {
        val runId = "run-done-$id"
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
        server.logs[runId] = listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":60000}""")
        server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Start."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done."))
        server.composers.putIfAbsent(id, FaultServer.Composer(id, id, activityMs = at))
    }

    /** A turn of [steps] tool calls, each reading a file of [fileChars] and running a shell whose output is a third of it. */
    private fun turnLog(runId: String, steps: Int, fileChars: Int): List<Pair<String, String>> {
        val file = buildString { var i = 0; while (length < fileChars) append("line ${i++}: val x$i = compute($i) // ${runId.takeLast(8)}\n") }
        val log = ArrayList<Pair<String, String>>()
        log += "status" to """{"runId":"$runId","status":"RUNNING"}"""
        repeat(steps) { s ->
            log += "thinking" to """{"text":${q("Considering step $s. ".repeat(40))}}"""
            val args = """{"path":"src/File$s.kt"}"""
            log += "tool_call" to """{"callId":"c$s","name":"read","status":"completed","args":$args,"result":{"content":${q(file)}}}"""
            log += "tool_call" to """{"callId":"s$s","name":"shell","status":"completed","args":{"command":"./gradlew test"},"result":{"output":${q(file.take(fileChars / 3))},"exitCode":0}}"""
            log += "assistant" to """{"text":${q("Step $s looks right. ")}}"""
        }
        return log
    }

    private fun liveHeapMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(80) }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) shr 20
    }

    private fun stat(stats: String, name: String): Int = Regex("\\b$name=(\\d+)").find(stats)!!.groupValues[1].toInt()

    @Test
    fun `held chats whose agents keep finishing turns keep only their window in memory`() = runBlocking {
        server = FaultServer(rttMillis = 2L..10L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        val chats = LiveSync.MAX_HELD
        repeat(chats) { addChat("bc-held-$it", now - 100_000L - 1_000L * it) }
        var generation = 0
        val previous = arrayOfNulls<String>(chats)
        fun newTurn(i: Int) {
            val runId = "run-h$i-${++generation}"
            server.startTurnElsewhere("bc-held-$i", "Turn $runId", runId)
            server.logs[runId] = turnLog(runId, steps = 6, fileChars = 12_000)
            // The server forgets a finished turn's log a turn later, so only the client's memory can grow.
            previous[i]?.let { server.logs.remove(it) }
            previous[i] = runId
        }
        repeat(chats) { newTurn(it) }
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
            lingerMs = 600_000L,
        )
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), MutableStateFlow(true), MutableStateFlow(true))
        rig.awaitUntil { sync.heldIds.value.size == chats }

        var heapAtTen = 0L
        var peakTraces = 0
        for (round in 1..ROUNDS) {
            delay(700)
            repeat(chats) { i -> server.endTurn("bc-held-$i", durationMs = 600L); newTurn(i) }
            if (round % 5 == 0) runCatching { rig.agents.refresh() }
            peakTraces = maxOf(peakTraces, stat(rig.conversations.stats(), "maxTraces"))
            if (round == 10) heapAtTen = liveHeapMb()
        }
        delay(2_000)
        val stats = rig.conversations.stats()
        val growth = liveHeapMb() - heapAtTen
        println("HELD rounds=$ROUNDS peakTracesPerChat=$peakTraces heapGrowth(10→$ROUNDS)=${growth}MB $stats · ${rig.hub.stats()}")

        assertThat(stat(stats, "held")).isEqualTo(chats)
        // Every held chat went through ROUNDS turns; it keeps the traces of the window it would open on, no more.
        assertThat(peakTraces).isAtMost(WINDOW_RUNS + 1)
        // Twenty more turns a chat, twenty chats, each turn ~0.2 MB of results: kept, that was 44 MB more by round 30 and climbing.
        assertThat(growth).isLessThan(30L)
    }

    private companion object {
        const val ROUNDS = 30
        /** The runs a chat opens on (`ConversationRepository.WINDOW_RUNS`). */
        const val WINDOW_RUNS = 10
    }
}
