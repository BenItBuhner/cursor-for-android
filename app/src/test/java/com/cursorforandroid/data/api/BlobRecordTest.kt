package com.cursorforandroid.data.api

import com.cursorforandroid.data.repo.HeadlessTranscript
import com.cursorforandroid.fixtures.BlobFixtures
import com.cursorforandroid.fixtures.SevenRunCoordinator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The blob-backed record read into the record's steps: a turn's prompt, its calls with their arguments and results
 * paired by id, its text and thoughts — the same steps the step-indexed record gave, so the turn splitter and the
 * replay read them as they read that record; a blob that cannot be read is a step saying so, never another's words.
 */
class BlobRecordTest {

    private val runs = SevenRunCoordinator.runs(firstAt = 1_800_000_000_000L)
    private val steps = runs.flatMap { it.record }
    private val record = BlobFixtures.record(steps)

    private suspend fun turn(index: Int, record: BlobFixtures.Record = this.record): List<HeadlessStep> =
        BlobRecord.turn(index, record.turnIds[index], read = { id -> record.blobs[id] ?: throw IllegalStateException("no blob $id") })

    @Test
    fun `a coordinator's turn reads as its prompt, its message call with its body and result, and its notes`() = runBlocking {
        // Run 2 of the seven: the frame's message, a worker addressed, the notes edited, a status check, two notes.
        val steps = turn(1)
        val prompt = steps.first()
        assertThat(prompt.userMessage).isEqualTo(runs[1].prompt)
        assertThat(prompt.projectMode).isTrue()
        assertThat(prompt.turnIndex).isEqualTo(1)
        assertThat(prompt.shape!!.branch).isEqualTo("blob:user_message")
        val calls = steps.mapNotNull { it.toolCall }
        assertThat(calls.map { it.name }).containsExactly("send_message", "send_to_agent", "edit_file", "get_agent_status").inOrder()
        val message = calls.first()
        assertThat(message.callId).isEqualTo("toolu_1_1_send")
        assertThat(message.args!!.jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content).isEqualTo(SevenRunCoordinator.M2)
        assertThat(message.source).isEqualTo("id=toolCallId name=variant args=json")
        val results = steps.mapNotNull { it.toolResult }
        assertThat(results.map { it.callId }).containsExactlyElementsIn(calls.map { it.callId }).inOrder()
        assertThat(steps.filter { it.text != null }.map { it.text }).containsExactly("Queue cleared; one consolidated message queued for the primary.", "Bennett is told what was queued and in what order.").inOrder()
        assertThat(steps.all { it.turnIndex == 1 }).isTrue()
        // Cut into turns, it is the one turn, indexed as the record indexes it.
        val turns = HeadlessTranscript.split(steps)
        assertThat(turns).hasSize(1)
        assertThat(turns.single().turnIndex).isEqualTo(1)
        assertThat(turns.single().steps.count { it.toolCall != null }).isEqualTo(4)
    }

    @Test
    fun `a turn whose prompt blob is missing is still a turn of its own, without a prompt`() = runBlocking {
        val cut = BlobFixtures.record(steps, omitPromptOfTurn = 2)
        val steps = turn(2, cut)
        assertThat(steps.first().userMessage).isNull()
        assertThat(steps.first().toolCall).isNotNull()
        // Two turns read together cut at the turn boundary although the second has no prompt step.
        val both = turn(1, cut) + steps
        val turns = HeadlessTranscript.split(both)
        assertThat(turns.map { it.turnIndex }).containsExactly(1, 2).inOrder()
        assertThat(turns[1].prompt).isNull()
        assertThat(turns[1].steps.size).isEqualTo(steps.size)
    }

    @Test
    fun `a step the server has grown keeps its unknown fields in its shape, and an unreadable blob is a blank step`() = runBlocking {
        val grown = BlobFixtures.record(steps, unknownStepFields = true)
        val steps = turn(1, grown)
        // Every step blob's shape (a result step's shape is its result alone) names the field this build does not know.
        val own = steps.drop(1).filter { it.toolResult == null }
        assertThat(own).isNotEmpty()
        assertThat(own.all { it.shape!!.keys.contains("_unknownFields") }).isTrue()
        // A blob that is not a message: a blank step carrying the word, the turn read on around it.
        val first = BlobFixtures.stepId(grown, 1, 0)
        val broken = BlobFixtures.Record(grown.turnIds, grown.blobs.mapValues { (id, bytes) -> if (id == first) "garbage".toByteArray() else bytes })
        val read = turn(1, broken)
        assertThat(read[1].shape!!.branch).isEqualTo("blob:step[unreadable]")
        assertThat(read[1].toolCall).isNull()
        assertThat(read.count { it.toolCall != null }).isEqualTo(3)
    }
}
