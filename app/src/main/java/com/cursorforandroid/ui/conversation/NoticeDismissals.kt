package com.cursorforandroid.ui.conversation

import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.repo.ConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The notices about one chat's load the reader has closed (the X on a [LoadNoticeCard]), and when a closed one
 * comes back. Bennett's 2026-09-20 frame: the record's notice standing over the composer with no way to put it away,
 * "we do not want it permanently there as that takes up a lot of the screen real estate".
 *
 * Closed means hidden from the dock, nothing more: the condition it named stands, the diagnostics the menu shares
 * still carry it (they are read from the chat's state, which this never touches), and Refresh and Share diagnostics
 * stay in the chat's menu. A notice stays closed for as long as it says the same thing — the same words after a
 * reopen, a restart or a refresh that ends the same way are the same notice — and comes back on either of two
 * changes: its words change (a different reason from the server is a different notice, see [LoadNotice.identity]),
 * or its condition clears and then recurs. The clearing is read from a state that has settled with a transcript on
 * screen and no such notice (see [LoadNotices.isSettled]); the closing is then forgotten, so the next time the
 * record refuses, or a refresh fails, the reader is told again.
 *
 * What is closed is kept on the device per chat ([PreferencesStore.dismissedNotices]), so a reopen — of the screen
 * or of the app — does not bring a closed notice back. [hidden] is null until that record has been read, for the one
 * frame it takes: the dock shows no notice meanwhile rather than one that is about to be hidden.
 */
class NoticeDismissals(
    private val agentId: String,
    private val prefs: PreferencesStore,
    states: Flow<ConversationState>,
    private val scope: CoroutineScope,
) {
    /** Closed on this screen, before the device's record has caught up: the card goes the moment the X is tapped. */
    private val closedHere = MutableStateFlow<Set<String>>(emptySet())

    /** The identities of the notices closed over this chat ([LoadNotice.identity]); null until the device's record has been read. */
    val hidden: StateFlow<Set<String>?> = combine(prefs.dismissedNotices.map { it[agentId] ?: emptySet() }, closedHere) { kept, here -> kept + here }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            // A settled transcript without a closed notice's condition: it has cleared, and the closing is forgotten.
            // Read against the state as it stands when either side changes — never against an earlier settled state:
            // the record's refusal is published while its load is still under way, and a notice closed in that
            // window would otherwise be compared with the state from before the load, which had no such notice.
            combine(states, hidden) { state, closed ->
                if (closed == null || !LoadNotices.isSettled(state)) emptySet() else closed - LoadNotices.of(state).map { it.identity }.toSet()
            }
                .filter { it.isNotEmpty() }
                .collect { cleared ->
                    closedHere.update { it - cleared }
                    cleared.forEach { prefs.setNoticeDismissed(agentId, it, dismissed = false) }
                }
        }
    }

    /** The reader closed [notice]: hidden now, and remembered on the device for this chat. */
    fun dismiss(notice: LoadNotice) {
        closedHere.update { it + notice.identity }
        scope.launch { prefs.setNoticeDismissed(agentId, notice.identity, dismissed = true) }
    }
}
