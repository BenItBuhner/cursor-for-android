package com.cursorforandroid.notifications

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.SpotlightTarget
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which chat or Project is in the Spotlight: one at most, so spotlighting another replaces it. Held in memory only —
 * the [SpotlightService] keeps the process alive while there is one, and a process that dies ends it with the
 * service and its notification.
 */
class SpotlightController(private val nowProvider: () -> Long = AppClock::now) {
    private val _target = MutableStateFlow<SpotlightTarget?>(null)
    val target: StateFlow<SpotlightTarget?> = _target.asStateFlow()

    private val _covered = MutableStateFlow<Set<String>>(emptySet())

    /** The chats the Spotlight speaks for right now; the live notification's roster leaves them out. */
    val covered: StateFlow<Set<String>> = _covered.asStateFlow()

    fun isSpotlit(agentId: String): Boolean = _target.value?.agentId == agentId

    /** Puts [agent] in the Spotlight, in place of whatever was there. */
    fun spotlight(agent: Agent): SpotlightTarget {
        val target = SpotlightTarget(agent.id, isProject = agent.isProjectRoot, startedAtMillis = nowProvider())
        _covered.value = setOf(agent.id)
        _target.value = target
        return target
    }

    fun stop() {
        _target.value = null
        _covered.value = emptySet()
    }

    /** Ends [target]'s Spotlight when it is still the one shown; a newer Spotlight is left alone. */
    fun end(target: SpotlightTarget) {
        if (_target.compareAndSet(target, null)) _covered.value = emptySet()
    }

    fun cover(target: SpotlightTarget, ids: Set<String>) {
        if (_target.value == target) _covered.value = ids
    }
}
