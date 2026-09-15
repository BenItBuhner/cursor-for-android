package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The first run: whether the account signed in on this device still owes the choice between SDK only and Extended
 * mode, and what each answer does.
 *
 * The choice is owed by a sign-in through the sign-in screen ([signedIn], from `SessionManager.onSignedIn`) and
 * settled by the choice screen ([chooseSdkOnly], [chooseExtended]). It is never owed by a session restored from a
 * stored key — an install that already had an account when the choice arrived goes straight to the app, as it always
 * did — and it goes with the account on sign-out ([signedOut]; the stored flag is one of `PreferencesStore`'s session
 * keys), so the next sign-in is asked again. The demo signs nothing in and is never asked.
 *
 * The answer is kept in memory, written through to the preferences: in memory so that the screen reading it sees a
 * sign-in the moment the session does, with no frame in between where the app could show; on disk so that a process
 * that dies between the sign-in and the choice asks on its next start rather than deciding by default. Constructing
 * this reads nothing (the graph is built in `Application.onCreate`); [load] reads the stored flag, once, where the
 * session is restored.
 */
class Onboarding(
    private val prefs: PreferencesStore,
    private val extendedMode: ExtendedMode,
) {
    private val _modeChoicePending = MutableStateFlow<Boolean?>(null)

    /** True while the choice is owed, false once it is made or was never owed; null until [load] has read the stored flag. */
    val modeChoicePending: StateFlow<Boolean?> = _modeChoicePending.asStateFlow()

    /**
     * Reads the flag an earlier process left behind, into a state nothing has set yet: a sign-in that lands first is
     * the newer word, and this must not undo it. Nothing happens once the state is known. A store that cannot be
     * read owes no choice, as the session it belongs to has settled to signed-out by then.
     */
    suspend fun load() {
        if (_modeChoicePending.value != null) return
        val stored = runCatching { prefs.modeChoicePending.first() }.getOrDefault(false)
        _modeChoicePending.compareAndSet(null, stored)
    }

    /** A sign-in through the sign-in screen: the choice is owed. Written before the session is published. */
    suspend fun signedIn() {
        prefs.setModeChoicePending(true)
        _modeChoicePending.value = true
    }

    /** The account is gone, and with it the choice it owed; the stored flag goes with the account's other keys. */
    fun signedOut() {
        _modeChoicePending.value = false
    }

    /**
     * SDK only: the documented API alone. Extended mode is left off — and turned off, with the wipe that goes with
     * it, if an earlier sign-in on this device had left it on: the screen said SDK only, and the app is what it said.
     */
    suspend fun chooseSdkOnly() {
        extendedMode.disable()
        settle()
    }

    /**
     * Extended mode, on the acknowledgment dialog's confirm: exactly what the Settings toggle does. The acknowledgment
     * is recorded now unless one is already on record — the dialog is skipped then, as Settings skips it — and the
     * mode comes on only once it is; a refused write leaves the mode off, which Settings shows as it is.
     */
    suspend fun chooseExtended() {
        val acknowledged = extendedMode.acknowledgedAt.first() != null || extendedMode.acknowledge()
        if (acknowledged) extendedMode.enable()
        settle()
    }

    private suspend fun settle() {
        prefs.setModeChoicePending(false)
        _modeChoicePending.value = false
    }
}
