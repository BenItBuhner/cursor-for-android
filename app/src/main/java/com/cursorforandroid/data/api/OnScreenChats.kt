package com.cursorforandroid.data.api

import java.util.concurrent.ConcurrentHashMap

/**
 * The chats a screen is on right now, as the transcript counts its screens (`ConversationRepository.attach`): what
 * puts a chat's record reads in [ApiThrottle.Lane.STATE_ON_SCREEN], ahead of the kept-live chats nobody is looking at.
 */
object OnScreenChats {
    private val screens = ConcurrentHashMap<String, Int>()

    fun opened(agentId: String) {
        screens.merge(agentId, 1, Int::plus)
    }

    fun closed(agentId: String) {
        screens.computeIfPresent(agentId) { _, n -> (n - 1).takeIf { it > 0 } }
    }

    fun contains(agentId: String): Boolean = screens.containsKey(agentId)

    /** The lane [agentId]'s conversation state and record pages are read in. */
    fun stateLane(agentId: String): ApiThrottle.Lane = if (contains(agentId)) ApiThrottle.Lane.STATE_ON_SCREEN else ApiThrottle.Lane.STATE
}
