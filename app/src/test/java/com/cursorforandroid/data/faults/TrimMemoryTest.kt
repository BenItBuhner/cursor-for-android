package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.Meter.Companion.mb
import com.cursorforandroid.data.repo.ConversationRepository
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
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
 * The system asking for memory back (`onTrimMemory`, see `AppGraph.trimMemory`): the chats kept in memory that
 * nothing shows, holds or streams are let go — all but the last few gently, every one of them hard — and the heap
 * they held comes back. What a screen shows, and what keep chats live holds, stays; a chat let go opens again whole,
 * from the disk.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TrimMemoryTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        rig?.close()
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun q(s: String) = Json.encodeToString(String.serializer(), s)

    /** A finished chat whose one turn read a few big files: each chat in memory holds its trace. */
    private fun addHeavyChat(id: String, at: Long) {
        val runId = "run-$id"
        val file = buildString { var i = 0; while (length < 50_000) append("line ${i++}: val v$i = compute($i) // $id\n") }
        server.runs[runId] = RunDto(id = runId, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
        server.logs[runId] = (0 until 3).map { s ->
            "tool_call" to """{"callId":"c$s","name":"read","status":"completed","args":{"path":"src/F$s.kt"},"result":{"content":${q(file)}}}"""
        } + listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$runId","status":"FINISHED","text":"Done.","durationMs":60000}""")
        server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = runId, url = "https://cursor.com/agents/$id")
        server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
        server.transcripts[id] = listOf(V0ConversationMessageDto("$runId-u", "user_message", "Read the files."), V0ConversationMessageDto("$runId-a", "assistant_message", "Done."))
    }

    private fun chats(conversations: ConversationRepository): Int = Regex("\\bchats=(\\d+)").find(conversations.stats())!!.groupValues[1].toInt()

    @Test
    fun `a trim lets go of the chats nobody shows or holds, and they open again whole`() = runBlocking {
        server = FaultServer(rttMillis = 2L..8L).start()
        val ids = (0 until 24).map { "bc-trim-$it" }
        ids.forEachIndexed { i, id -> addHeavyChat(id, now - 100_000L - i * 1_000L) }
        val rig = FaultRig(server.baseUrl, folder.newFolder("disk"), extended = false, engine = TranscriptEngine.STABLE).also { it.now = now; this@TrimMemoryTest.rig = it }
        rig.agents.refresh()
        val conversations = rig.conversations
        suspend fun openAndLeave(id: String) {
            conversations.attach(id)
            rig.awaitUntil(20_000) { conversations.state(id).value.let { s -> !s.isLoading && s.items.any { it is ActivityGroup } && s.traceStatus.pending == 0 } }
            conversations.detach(id)
        }
        ids.forEach { openAndLeave(it) }
        // Two held by keep chats live, one on the screen.
        conversations.hold(ids[0])
        conversations.hold(ids[1])
        conversations.attach(ids[2])
        rig.awaitUntil(10_000) { !conversations.state(ids[2]).value.isLoading }
        rig.awaitUntil(10_000) { conversations.stats().contains("loading=0") }
        assertThat(chats(conversations)).isEqualTo(24)
        val before = Meter.retainedHeap()

        assertThat(conversations.trimMemory(hard = false)).isEqualTo(24 - 3 - KEPT_ON_TRIM)
        assertThat(chats(conversations)).isEqualTo(3 + KEPT_ON_TRIM)
        conversations.trimMemory(hard = true)
        assertThat(chats(conversations)).isEqualTo(3)
        assertThat(conversations.isHeld(ids[0])).isTrue()
        assertThat(conversations.isHeld(ids[1])).isTrue()
        assertThat(conversations.isAttached(ids[2])).isTrue()
        rig.hub.trimMemory()
        val after = Meter.retainedHeap()
        println("TRIM retained before=${before.mb} after=${after.mb} · ${conversations.stats()} · ${rig.hub.stats()}")
        // Twenty-one chats let go, each holding its turn's results (some 120 KB of them as kept): megabytes back.
        assertThat(before - after).isAtLeast(3L shl 19)

        // A chat let go opens again with its turn whole — from the disk, with no replay of its run.
        val replaysBefore = server.seen.count { it.route == FaultServer.Route.Stream }
        val back = ids[10]
        conversations.attach(back)
        rig.awaitUntil(20_000) { conversations.state(back).value.let { s -> !s.isLoading && s.items.any { it is ActivityGroup } } }
        assertThat(server.seen.count { it.route == FaultServer.Route.Stream }).isEqualTo(replaysBefore)
        conversations.detach(back)
    }

    private companion object {
        /** `ConversationRepository.KEPT_ON_TRIM`. */
        const val KEPT_ON_TRIM = 4
    }
}
