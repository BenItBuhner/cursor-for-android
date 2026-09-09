package com.cursorforandroid.domain

/**
 * An image in the follow-up composer or a queued follow-up: the bytes the request will carry, under an id that
 * stays with them from the picker to the disk copy, so the same image is written once and recognised on restore.
 */
class DraftImage(val id: String, val image: PromptImage)

/** What the follow-up composer holds for one chat: the text and the images, saved as typed so leaving the chat loses nothing. */
data class FollowUpDraft(
    val text: String = "",
    val images: List<DraftImage> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty()

    companion object {
        val EMPTY = FollowUpDraft()
    }
}

/**
 * A follow-up sent while the agent was still on its previous turn. The Cloud Agents API takes one run at a time —
 * `POST /v1/agents/{id}/runs` answers `409 agent_busy` for anything more, and the SDK's `steer` resolves
 * `revert_to_followup` for cloud runs — so the app keeps the message and sends it the moment the turn ends, in the
 * order it was queued. The model pick and plan-mode flag are the ones in force when the user hit send, so the request
 * that eventually goes out is the one they saw.
 */
data class QueuedFollowUp(
    val id: String,
    val text: String,
    val images: List<DraftImage> = emptyList(),
    val queuedAtMillis: Long,
    val planMode: Boolean? = null,
    val modelId: String? = null,
    val modelParams: List<ModelParam> = emptyList(),
    val modelDisplayName: String? = null,
    /** The request is out and the server has not answered yet. */
    val isSending: Boolean = false,
    /**
     * Steered: it has left the cards and shows in the transcript as a pending prompt while the turn under way is
     * stopped and the request goes out. Still in the queue underneath so a restart finds it and sends it in order.
     */
    val isSteered: Boolean = false,
    /**
     * Why the last attempt to send it failed, for anything other than the agent still being busy. The message stays
     * at the head of the queue and nothing behind it goes out until the user retries it or takes it away.
     */
    val error: String? = null,
) {
    /** The text a card shows: the message itself, or what the request will say for an image-only follow-up. */
    val previewText: String get() = text.ifBlank { IMAGE_ONLY_TEXT }

    companion object {
        /** What an image-only follow-up says in its prompt, the same as when the composer sends one directly. */
        const val IMAGE_ONLY_TEXT = "See the attached image."
    }
}

/** Everything the app holds for one chat's follow-ups that has not reached the server: the draft and the queue. */
data class FollowUpComposerState(
    val draft: FollowUpDraft = FollowUpDraft.EMPTY,
    val queue: List<QueuedFollowUp> = emptyList(),
    /** False until the disk copy has been read; the composer shows nothing of its own before that. */
    val restored: Boolean = false,
)
