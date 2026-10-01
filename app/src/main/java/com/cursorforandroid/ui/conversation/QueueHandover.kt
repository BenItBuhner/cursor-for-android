package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Stable
import com.cursorforandroid.domain.QueuedFollowUp

/**
 * The device's queued cards as the screen stands them, read off the transcript's own frame: a message the run takes
 * leaves the card in the frame that shows its bubble, not a frame before or after it.
 *
 * The card and the bubble come from two publications: `FollowUpRepository` drops the row once the server has the
 * message, and the transcript that files its bubble is presented off the main thread (see
 * [ConversationViewModel.presented]). Read as each came, the card closed a frame before the bubble opened, or after,
 * and the transcript over the dock moved twice for the one message. The frame's placement names the rows its items
 * show as bubbles ([com.cursorforandroid.domain.QueuePlacement.filedQueueIds]), so the handover is decided by it alone:
 *  - a row the frame names is gone from the card, whether or not the repository has dropped it yet;
 *  - a row the repository dropped that the chat has filed ([standing]'s `filed`: the repository's placement now,
 *    ahead of the frame on screen) stands where it was until the frame that names it.
 * Any other row that goes — removed, taken back to edit, handed to the account's queue — goes at once, as before.
 *
 * Plain fields: [composed] records what the last composition stood, which only [standing] reads.
 */
@Stable
internal class QueueHandover {
    private var shown: List<QueuedFollowUp> = emptyList()

    /**
     * The cards to stand now: [queue] as the repository has it, less what [presented] (the frame's filed rows) shows as
     * bubbles, and with the rows the last composition stood that the chat has [filed] but the frame does not show yet,
     * where they stood (at the head: the queue sends its head). The [queue] itself when nothing is handed over.
     */
    fun standing(queue: List<QueuedFollowUp>, presented: Set<String>, filed: () -> Set<String>): List<QueuedFollowUp> {
        val gone = shown.filter { s -> s.id !in presented && queue.none { it.id == s.id } }
        val held = if (gone.isEmpty()) gone else filed().let { now -> gone.filter { it.id in now } }
        val kept = if (presented.isNotEmpty() && queue.any { it.id in presented }) queue.filterNot { it.id in presented } else queue
        if (held.isEmpty()) return kept
        val stood = held + kept
        return if (stood == shown) shown else stood
    }

    /** What this composition stands: what the next one's [standing] hands over from. */
    fun composed(cards: List<QueuedFollowUp>) {
        shown = cards
    }
}
