package com.cursorforandroid.ui.conversation

import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerPhase

/**
 * Where a device card being steered stands against the account's rows: from the moment the account holds the message
 * the card stands for its row ([QueuedFollowUp.steerFollowupId]), so the row is not shown beside it; once steered, the
 * card goes in the frame the transcript's placement stops holding the message as waiting — the frame it is filed as a
 * bubble — so the message is in exactly one place on screen, and its flight lifts off into that bubble.
 */
internal object SteeredCards {
    /** The device's queue as the screen shows it: a steered card whose message the transcript has filed is gone. */
    fun standing(queue: List<QueuedFollowUp>, placement: QueuePlacement): List<QueuedFollowUp> {
        if (queue.none { it.steer == SteerPhase.STEERED }) return queue
        return queue.filterNot { card ->
            val followupId = card.steerFollowupId
            card.steer == SteerPhase.STEERED && followupId != null && placement.waiting.none { it.id == followupId }
        }
    }

    /** The account's rows as the screen shows them: any a device card stands for are left to the card. */
    fun accountRows(rows: List<PendingFollowup>, queue: List<QueuedFollowUp>): List<PendingFollowup> {
        val held = queue.mapNotNullTo(HashSet()) { it.steerFollowupId }
        return if (held.isEmpty()) rows else rows.filterNot { it.id in held }
    }
}
