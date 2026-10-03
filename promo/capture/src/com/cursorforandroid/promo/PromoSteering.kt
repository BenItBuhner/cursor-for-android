package com.cursorforandroid.promo

import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerOutcome

/**
 * The account's steering as the capture's scripted run takes it: a queued card's steer is handed off and promoted
 * as Extended mode hands it to the account, each step as long as the account's round trip, and the message waits
 * here for the run's next step. The run then files it with the conversation as the account would ([deliver]): the
 * user's message in the chat's transcript and the account's queue read empty, from which the app places the message
 * in the turn under way itself.
 *
 * The demo has no account, so the app offers no steer of its own; the capture presses the card's arrow for the look of
 * it and asks the repository for the steer directly.
 */
internal class PromoSteering(private val store: DemoStore) : FollowUpRepository.AccountSteering {

    private val handedOff = HashMap<String, String>()
    private val waiting = HashMap<String, ArrayDeque<String>>()
    private var counter = 0

    /** Tells the app the account's queue no longer holds what [deliver] filed; set once the graph stands. */
    var queueRead: (agentId: String) -> Unit = {}

    /** Whether the app has placed everything steered into [agentId]'s turn, card and all. */
    var placed: (agentId: String) -> Boolean = { true }

    override suspend fun handOff(agentId: String, item: QueuedFollowUp): FollowUpRepository.AccountHandoff {
        VirtualTime.scripted { VirtualTime.sleep(HAND_OFF_MS) }
        val id = synchronized(this) { "fu-promo-${++counter}".also { handedOff[it] = item.text } }
        return FollowUpRepository.AccountHandoff(runId = null, followupId = id)
    }

    override suspend fun promote(agentId: String, followupId: String): SteerOutcome {
        VirtualTime.scripted { VirtualTime.sleep(PROMOTE_MS) }
        synchronized(this) { handedOff.remove(followupId)?.let { waiting.getOrPut(agentId) { ArrayDeque() }.addLast(it) } }
        return SteerOutcome.QUEUED
    }

    /** The next message steered into [agentId]'s run and not yet taken, if any. */
    fun take(agentId: String): String? = synchronized(this) { waiting[agentId]?.removeFirstOrNull() }

    /** [text] filed with [agentId]'s conversation where the run reads it, and the account's queue read without it. */
    fun deliver(agentId: String, text: String) {
        synchronized(store) {
            transcripts(store).getOrPut(agentId) { mutableListOf() } += V0ConversationMessageDto(store.nextId("msg"), "user_message", text)
        }
        queueRead(agentId)
    }

    /** Stands in for the account's steps in [repository], which the demo builds without any. */
    fun install(repository: FollowUpRepository) {
        FollowUpRepository::class.java.getDeclaredField("accountSteering").apply { isAccessible = true }.set(repository, this)
    }

    private companion object {
        const val HAND_OFF_MS = 300L
        const val PROMOTE_MS = 350L

        @Suppress("UNCHECKED_CAST")
        fun transcripts(store: DemoStore): MutableMap<String, MutableList<V0ConversationMessageDto>> =
            DemoStore::class.java.getDeclaredField("transcripts").apply { isAccessible = true }.get(store) as MutableMap<String, MutableList<V0ConversationMessageDto>>
    }
}
