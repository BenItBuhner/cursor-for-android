package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.rememberHaptics

/** What the chat on screen feels as its own news, apart from the reader's taps: see [ChatHaptics]. */
internal object ChatHapticCues {
    /**
     * A run going from [before] to [after]: the lightest tick when it finished, a reject when it ended in an error or
     * ran out of time, nothing for a stop the reader asked for (their tap was felt already) or a status merely learned.
     */
    fun runEnded(before: RunStatus?, after: RunStatus?): Haptic? {
        if (before?.isActive != true || after == null || after.isActive) return null
        return when (after) {
            RunStatus.FINISHED -> Haptic.Subtle
            RunStatus.ERROR, RunStatus.EXPIRED -> Haptic.Reject
            else -> null
        }
    }

    /** Whether a message on its way out failed between [before] and [after]; one already failed does not fail again. */
    fun sendFailed(before: Map<String, OutgoingStatus>, after: Map<String, OutgoingStatus>): Boolean =
        after.any { (id, status) -> status is OutgoingStatus.Failed && before[id] !is OutgoingStatus.Failed }
}

/**
 * Plays [ChatHapticCues] for the chat [agentId] while it is the screen in front: [runStatus] ending, and a message in
 * [outgoing] failing. A chat in the background plays nothing, and neither does a chat that opens on a run already over.
 */
@Composable
internal fun ChatHaptics(agentId: String, runStatus: RunStatus?, outgoing: Map<String, OutgoingStatus>) {
    val haptics = rememberHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lastStatus by remember(agentId) { mutableStateOf(runStatus) }
    var lastOutgoing by remember(agentId) { mutableStateOf(outgoing) }
    LaunchedEffect(agentId, runStatus) {
        val cue = ChatHapticCues.runEnded(lastStatus, runStatus)
        lastStatus = runStatus
        if (cue != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.perform(cue)
    }
    LaunchedEffect(agentId, outgoing) {
        val failed = ChatHapticCues.sendFailed(lastOutgoing, outgoing)
        lastOutgoing = outgoing
        if (failed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) haptics.perform(Haptic.Reject)
    }
}
