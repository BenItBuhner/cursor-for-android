package com.cursorforandroid.data.repo

import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Extended mode setting: whether the app may call Cursor's undocumented `api2` endpoints at all, and the
 * [Capabilities] every private surface reads before it makes a call. Off by default; turning it on takes an explicit
 * acknowledgment of what that involves ([acknowledge], recorded with the time), and turning it off hands the graph
 * [onDisabled], where it forgets the account session and everything only those calls could have produced — so that
 * default mode never shows, or leans on, anything a documented-API-only install could not have.
 *
 * Every install that predates the setting ran the private calls by default. [migrateInstall] runs once on such an
 * install's first launch of this build: the mode stays off, the same wipe runs, the pins are made the device's own,
 * and a one-time notice is owed ([noticePending]) so the features that moved behind the setting do not simply vanish.
 */
class ExtendedMode(
    private val prefs: PreferencesStore,
    /** Whether this install was signed in to an account before the setting existed; only those are told what changed. */
    private val hadAccount: suspend () -> Boolean = { false },
    private val now: () -> Long = AppClock::now,
    /** Where the hooks run: theirs rather than the caller's, so a screen leaving mid-wipe does not leave the wipe half done. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Runs after the mode has been turned off, and once for an upgraded install: the graph's wipe of the private state. */
    var onDisabled: suspend () -> Unit = {}

    /** Runs after the mode has been turned on: the graph starts what the private calls add (a pin sync, the picture). */
    var onEnabled: suspend () -> Unit = {}

    private val switching = Mutex()

    val enabled: Flow<Boolean> = prefs.extendedMode.distinctUntilChanged()

    val acknowledgedAt: Flow<Long?> = prefs.extendedModeAcknowledgedAt

    val noticePending: Flow<Boolean> = prefs.extendedModeNoticePending

    val capabilities: Flow<Capabilities> = enabled.map(Capabilities::of).distinctUntilChanged()

    /** What the private surfaces may do right now; read at the start of every call that could reach `api2`. */
    suspend fun capabilities(): Capabilities = Capabilities.of(prefs.extendedMode.first())

    suspend fun isEnabled(): Boolean = prefs.extendedMode.first()

    /** The warning has been read; only after this can [enable] succeed. False when it could not be saved. */
    suspend fun acknowledge(): Boolean = prefs.setExtendedModeAcknowledgedAt(now())

    /**
     * Turns the mode on. Refused (false) until [acknowledge] has been recorded, and when the setting could not be
     * written. The account's first pin sync is owed again first, so the pins made here while the account was out of
     * reach are pushed up rather than replaced by its list.
     */
    suspend fun enable(): Boolean = switch {
        if (prefs.extendedModeAcknowledgedAt.first() == null) return@switch false
        if (prefs.extendedMode.first()) return@switch true
        prefs.setPinsMigrated(false)
        if (!prefs.setExtendedMode(true)) return@switch false
        runHook(onEnabled)
        true
    }

    /**
     * Turns the mode off and wipes what it produced. The setting is written first: whatever the wipe manages, no
     * further private call can start once this returns. False when the setting itself could not be written.
     */
    suspend fun disable(): Boolean = switch {
        if (!prefs.extendedMode.first()) return@switch true
        val written = prefs.setExtendedMode(false)
        runHook(onDisabled)
        written
    }

    /** Sets the mode from a toggle; see [enable] and [disable]. */
    suspend fun setEnabled(enabled: Boolean): Boolean = if (enabled) enable() else disable()

    /**
     * The one-time upgrade step, on any launch: nothing happens once it has run. An install that was signed in before
     * the setting existed has been using the private calls until now; the mode starts off for it like for everyone
     * else, so what those calls left on the device is wiped the way turning the mode off wipes it, the pins become the
     * device's own, and the notice about what moved behind the setting is owed. A fresh install merely records that
     * the setting exists.
     */
    suspend fun migrateInstall(): Unit = switch {
        if (prefs.extendedModeIntroduced.first()) return@switch
        val upgraded = !prefs.extendedMode.first() && hadAccount()
        if (!prefs.setExtendedModeIntroduced(noticePending = upgraded)) return@switch
        if (upgraded) runHook(onDisabled)
    }

    /** The upgrade notice has been shown (or acted on). */
    suspend fun dismissNotice(): Boolean = prefs.setExtendedModeNoticePending(false)

    /**
     * One switch at a time, run to completion on this object's scope and awaited: a caller cancelled while waiting
     * (the screen that flipped the toggle going away) leaves the switch — and the wipe that is part of it — running.
     */
    private suspend fun <T> switch(block: suspend () -> T): T = scope.async { switching.withLock { block() } }.await()

    /** A hook that fails does not undo the setting change it follows; the setting is what the next call reads. */
    private suspend fun runHook(hook: suspend () -> Unit) {
        try {
            hook()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }
}
