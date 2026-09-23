package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoEncoder
import com.cursorforandroid.data.local.BlobDiskStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.fixtures.BlobFixtures
import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Beta engine's read of a turn to a plan (see [TurnPlan]) and the blob cache's two tiers: a coordinator's turn
 * read to its prompt and messages leaves the other steps for later and says so; a prompt kept in a blob of its own is
 * read from there; a prefetched copy stays in memory until confirmed, a whole one reaches the disk, and what the
 * server prefetched is named as held on the next read, in this process or the next.
 */
class BlobPlanTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val runs = SevenRunCoordinator.runs(firstAt = 1_800_000_000_000L)
    private val record = BlobFixtures.record(runs.flatMap { it.record })

    /** A source over [blobs], counting what it was asked for; [held] the ids it holds without asking. */
    private class Source(val blobs: Map<String, ByteArray>, val held: Set<String> = emptySet()) : BlobRecord.Source {
        val asked = ArrayList<String>()
        override suspend fun blob(id: String, whole: Boolean): ByteArray { asked += id; return blobs[id] ?: throw ConnectRpcException(404, "not_found", "blob not found") }
        override suspend fun held(id: String): BlobCache.Held? = if (id in held) BlobCache.Held(blobs.getValue(id), partial = false) else null
        override suspend fun confirm(id: String) = Unit
    }

    /** Turn [index]'s structure: its prompt's blob id, its steps' blob ids, and which steps are messages. */
    private fun structure(index: Int): Triple<String?, List<String>, List<Int>> {
        val turn = com.cursorforandroid.data.api.proto.ProtoWire.decode(record.blobs.getValue(record.turnIds[index]), AgentSchemas.CONVERSATION_TURN)
        val agent = turn["agentConversationTurn"]!!.jsonObject
        val messages = (agent["sendMessageStepIndices"] as? JsonArray)?.map { (it as JsonPrimitive).content.toInt() }.orEmpty()
        return Triple((agent["userMessage"] as? JsonPrimitive)?.content, (agent["steps"] as JsonArray).map { (it as JsonPrimitive).content }, messages)
    }

    @Test
    fun `a coordinator's turn read to its messages reads its structure, prompt and message steps, and leaves the rest for later`() = runBlocking<Unit> {
        // Run 2 of the seven: a message, a worker addressed, the notes edited, a status check, two notes.
        val source = Source(record.blobs)
        val read = BlobRecord.read(1, record.turnIds[1], source, TurnPlan.MESSAGES)
        val (prompt, steps, messages) = structure(1)
        assertThat(messages).hasSize(1)
        assertThat(read.complete).isFalse()
        assertThat(read.messageSteps).isEqualTo(1)
        assertThat(read.stepTotal).isEqualTo(steps.size)
        // The structure, the prompt, the message step: nothing else was asked for.
        assertThat(source.asked).containsExactly(record.turnIds[1], prompt, steps[messages.single()])
        assertThat(read.steps.first().userMessage).isEqualTo(runs[1].prompt)
        assertThat(read.steps.mapNotNull { it.toolCall?.name }).containsExactly("send_message")
        // Read whole, the same turn is complete and names every call.
        val whole = BlobRecord.read(1, record.turnIds[1], Source(record.blobs), TurnPlan.FULL)
        assertThat(whole.complete).isTrue()
        assertThat(whole.steps.mapNotNull { it.toolCall?.name }).containsExactly("send_message", "send_to_agent", "edit_file", "get_agent_status").inOrder()
    }

    @Test
    fun `a step already held is read with the messages, without asking for it`() = runBlocking<Unit> {
        val (_, steps, messages) = structure(1)
        val others = steps.filterIndexed { i, _ -> i !in messages }
        val source = Source(record.blobs, held = others.toSet())
        val read = BlobRecord.read(1, record.turnIds[1], source, TurnPlan.MESSAGES)
        assertThat(read.complete).isTrue()
        assertThat(source.asked).containsNoneIn(others)
    }

    @Test
    fun `a prompt that keeps its text in a blob of its own is read from there`() = runBlocking<Unit> {
        val text = "A report long enough to be kept apart from its message."
        val textBlob = text.toByteArray()
        val message = ProtoEncoder.encode(buildJsonObject { put("messageId", "m-1"); put("mode", AgentSchemas.AGENT_MODE_PROJECT); put("textBlobId", "dGV4dA==") }, AgentSchemas.USER_MESSAGE)
        val turn = ProtoEncoder.encode(buildJsonObject { put("agentConversationTurn", buildJsonObject { put("userMessage", "bXNn"); put("steps", JsonArray(emptyList())) }) }, AgentSchemas.CONVERSATION_TURN)
        val blobs = mapOf("dHVybg==" to turn, "bXNn" to message, "dGV4dA==" to textBlob)
        val read = BlobRecord.read(0, "dHVybg==", Source(blobs), TurnPlan.MESSAGES)
        assertThat(read.steps.first().userMessage).isEqualTo(text)
        assertThat(read.steps.first().shape!!.branch).isEqualTo("blob:user_message[text-blob]")
        assertThat(read.stepTotal).isEqualTo(0)
    }

    @Test
    fun `missing blobs are counted for the drift check, never thrown`() = runBlocking<Unit> {
        val (_, steps, _) = structure(1)
        val read = BlobRecord.read(1, record.turnIds[1], Source(record.blobs - steps.toSet()), TurnPlan.FULL)
        assertThat(read.missing).isEqualTo(steps.size)
        assertThat(read.lastMissing!!.code).isEqualTo("not_found")
    }

    @Test
    fun `a prefetched copy stays in memory until confirmed, a whole one reaches the disk, and the prefetch is named as held in the next process`() = runBlocking<Unit> {
        val dir = folder.newFolder("blobs")
        val cache = BlobCache(disk = BlobDiskStore(JsonDiskCache(dir)))
        cache.put("bc-1", "cHJlZmV0Y2hlZA==", byteArrayOf(1), partial = true)
        cache.keep("bc-1", "d2hvbGU=", byteArrayOf(2))
        cache.notePrefetched("bc-1", listOf("cHJlZmV0Y2hlZA==", "d2hvbGU="))
        val next = BlobCache(disk = BlobDiskStore(JsonDiskCache(dir)))
        // Only the whole copy made it to disk; the partial one is named only once it is held again.
        assertThat(next.read("bc-1", "d2hvbGU=")!!.bytes).isEqualTo(byteArrayOf(2))
        assertThat(next.read("bc-1", "cHJlZmV0Y2hlZA==")).isNull()
        assertThat(next.heldIds("bc-1")).containsExactly("d2hvbGU=")
        cache.confirm("bc-1", "cHJlZmV0Y2hlZA==")
        val third = BlobCache(disk = BlobDiskStore(JsonDiskCache(dir)))
        assertThat(third.heldIds("bc-1")).containsExactly("cHJlZmV0Y2hlZA==", "d2hvbGU=").inOrder()
    }

    @Test
    fun `a blob file is named by its id, whatever the id's alphabet`() {
        for (id in listOf("dGV4dA==", "a/b+c", "blob-1_x")) assertThat(BlobDiskStore.idOf(BlobDiskStore.nameOf(id))).isEqualTo(id)
        assertThat(BlobDiskStore.idOf(BlobDiskStore.nameOf("x".repeat(400)))).isNull()
    }
}
