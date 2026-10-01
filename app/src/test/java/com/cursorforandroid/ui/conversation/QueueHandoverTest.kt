package com.cursorforandroid.ui.conversation

import com.cursorforandroid.domain.QueuedFollowUp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [QueueHandover]'s rule on its own: a row sent from the card leaves it in the frame whose placement files its bubble,
 * whichever of the two publications came first; any other row goes as the repository drops it.
 */
class QueueHandoverTest {

    private val head = QueuedFollowUp("q-1", "Also check the release build", queuedAtMillis = 0L, isSending = true)
    private val next = QueuedFollowUp("q-2", "Then write the release notes", queuedAtMillis = 0L)

    private fun QueueHandover.stand(queue: List<QueuedFollowUp>, presented: Set<String> = emptySet(), filed: Set<String> = emptySet()) =
        standing(queue, presented) { filed }.also(::composed)

    @Test
    fun `nothing handed over stands the queue as the repository has it`() {
        val handover = QueueHandover()
        val queue = listOf(head, next)
        assertThat(handover.stand(queue)).isSameInstanceAs(queue)
    }

    @Test
    fun `a row the queue drops before the frame files it stands at the head until that frame`() {
        val handover = QueueHandover()
        handover.stand(listOf(head, next))
        assertThat(handover.stand(listOf(next), filed = setOf("q-1"))).containsExactly(head, next).inOrder()
        // Frames on, the presenter not yet there: still standing, the same list.
        val held = handover.stand(listOf(next), filed = setOf("q-1"))
        assertThat(handover.stand(listOf(next), filed = setOf("q-1"))).isSameInstanceAs(held)
        assertThat(handover.stand(listOf(next), presented = setOf("q-1"), filed = setOf("q-1"))).containsExactly(next)
    }

    @Test
    fun `a frame that files the bubble before the queue drops the row takes the card down in that frame`() {
        val handover = QueueHandover()
        handover.stand(listOf(head, next))
        assertThat(handover.stand(listOf(head, next), presented = setOf("q-1"), filed = setOf("q-1"))).containsExactly(next)
        // The queue's drop, later, changes nothing on the card.
        assertThat(handover.stand(listOf(next), presented = setOf("q-1"), filed = setOf("q-1"))).containsExactly(next)
    }

    @Test
    fun `a row removed, not sent, goes at once`() {
        val handover = QueueHandover()
        handover.stand(listOf(head, next))
        assertThat(handover.stand(listOf(head))).containsExactly(head)
    }

    @Test
    fun `a held row the chat stops filing is let go`() {
        val handover = QueueHandover()
        handover.stand(listOf(head, next))
        handover.stand(listOf(next), filed = setOf("q-1"))
        assertThat(handover.stand(listOf(next))).containsExactly(next)
    }
}
