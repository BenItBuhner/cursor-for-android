package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * An account row steered from here, as the card projects it ([ConversationControls.placed]): a steer, in its place,
 * whatever else would be said under it — never a queued delivery's "Being delivered to the agent." — and, once the
 * account's list has let go of it and the card carries it on this device's word, ahead of the rows still waiting.
 */
class SteeredQueueRowsTest {
    private val first = PendingFollowup("fu-1", "Then add a test for the light theme")
    private val second = PendingFollowup("fu-2", "And a changelog line under Unreleased")

    @Test
    fun `a listed row being steered reads as its steer, in its place`() {
        val controls = ConversationControls(queue = listOf(second, first), steers = mapOf("fu-1" to SteerPhase.STEERING))
        val card = controls.placed(QueuePlacement.NONE).queue
        assertThat(card.map { it.id }).containsExactly("fu-2", "fu-1").inOrder()
        assertThat(card.map { it.steer }).containsExactly(null, SteerPhase.STEERING).inOrder()
    }

    @Test
    fun `a steered row the list let go of reads steered, not delivering, and stands ahead of the waiting rows`() {
        val carried = first.copy(note = QueuePlacement.DELIVERING_NOTE)
        val justQueued = PendingFollowup("fu-3", "One more thing", note = null)
        val placement = QueuePlacement(waiting = listOf(carried, justQueued))
        val controls = ConversationControls(queue = listOf(second), steers = mapOf("fu-1" to SteerPhase.STEERED))
        val card = controls.placed(placement).queue
        assertThat(card.map { it.id }).containsExactly("fu-1", "fu-2", "fu-3").inOrder()
        val steered = card.first()
        assertThat(steered.steer).isEqualTo(SteerPhase.STEERED)
        assertThat(steered.note).isNull()
    }

    @Test
    fun `a row not steered keeps the delivery's word`() {
        val placement = QueuePlacement(waiting = listOf(first.copy(note = QueuePlacement.DELIVERING_NOTE)))
        val card = ConversationControls().placed(placement).queue
        assertThat(card.single().steer).isNull()
        assertThat(card.single().note).isEqualTo(QueuePlacement.DELIVERING_NOTE)
    }

    @Test
    fun `a steer of a message the transcript has filed puts nothing back on the card`() {
        val placement = QueuePlacement(deliveredIds = setOf("fu-1"))
        val controls = ConversationControls(queue = listOf(first, second), steers = mapOf("fu-1" to SteerPhase.STEERED))
        assertThat(controls.placed(placement).queue.map { it.id }).containsExactly("fu-2")
    }
}
