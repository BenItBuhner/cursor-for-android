package com.cursorforandroid.data.repo

import androidx.annotation.VisibleForTesting
import com.cursorforandroid.data.api.AccountUsageApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.toAccountUsage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.domain.AccountUsage
import com.cursorforandroid.domain.UsageReset
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches and keeps the signed-in account's usage, for Settings and for the reset-to-100% notification.
 *
 * At most two RPCs per refresh (`GetCurrentPeriodUsage`, `GetCreditGrantsBalance`). Nothing goes out while
 * Extended mode is off, while the last successful fetch is younger than [MIN_INTERVAL_MS] (unless [refresh]
 * is forced), or while the account throttle is paused. A first snapshot that is already 100% does not notify;
 * a later fetch that returns a remaining percent to 100% from below it always does.
 */
class AccountUsageMonitor(
    private val api: () -> AccountUsageApi?,
    private val cache: JsonDiskCache? = null,
    private val isDemo: () -> Boolean = { false },
    private val sessionAllowed: suspend () -> Boolean = { true },
    private val pauseUntil: () -> Long? = { null },
    private val now: () -> Long = AppClock::now,
    private val onReset: (UsageReset) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<AccountUsage?>(null)
    val snapshot: StateFlow<AccountUsage?> = _snapshot

    @Volatile private var creditsUnsupported = false
    @Volatile private var lastFetchAtMs = 0L

    init {
        if (isDemo()) {
            _snapshot.value = AccountUsage.SAMPLE
        }
    }

    /** Restores the last snapshot from disk so Settings has numbers before the next refresh. Never notifies. */
    suspend fun restore() {
        if (isDemo()) {
            _snapshot.value = AccountUsage.SAMPLE
            return
        }
        val stored = cache?.read(KEY, AccountUsage.serializer(), VERSION)?.value ?: return
        if (stored.hasMeters) _snapshot.value = stored
    }

    /**
     * Fetches a fresh snapshot when the interval, the mode and the throttle allow it. [force] skips the
     * interval (Extended mode just turned on); it still does nothing in the demo or while the session is refused.
     */
    suspend fun refresh(force: Boolean = false): AccountUsage? = mutex.withLock {
        if (isDemo()) {
            _snapshot.value = AccountUsage.SAMPLE
            return AccountUsage.SAMPLE
        }
        if (!sessionAllowed()) return _snapshot.value
        val nowMs = now()
        if (!force && lastFetchAtMs > 0L && nowMs - lastFetchAtMs < MIN_INTERVAL_MS) return _snapshot.value
        val paused = pauseUntil()
        if (paused != null && paused > nowMs) return _snapshot.value
        val client = api() ?: return _snapshot.value
        val period = runCatching { client.currentPeriod() }.getOrElse { t ->
            if (t is CancellationException) throw t
            if (t is SessionUnavailableException && t.isPermanent) return _snapshot.value
            throwIfUnexpected(t)
            return _snapshot.value
        }
        val grants = if (creditsUnsupported) {
            null
        } else {
            runCatching { client.creditGrants() }.getOrElse { t ->
                if (t is CancellationException) throw t
                if (isCreditsUnavailable(t)) {
                    creditsUnsupported = true
                    null
                } else {
                    throwIfUnexpected(t)
                    null
                }
            }
        }
        val next = period.toAccountUsage(grants, now())
        lastFetchAtMs = now()
        publish(next)
        cache?.write(KEY, AccountUsage.serializer(), VERSION, next)
        next
    }

    /** Sign-out / mode-off: drop the in-memory snapshot so Settings does not keep another account's numbers. */
    fun reset() {
        _snapshot.value = if (isDemo()) AccountUsage.SAMPLE else null
        lastFetchAtMs = 0L
        creditsUnsupported = false
    }

    /** Settings tests: put a snapshot on screen without a network. */
    @VisibleForTesting
    internal fun show(usage: AccountUsage) {
        _snapshot.value = usage
    }

    private fun publish(next: AccountUsage) {
        val previous = _snapshot.value
        _snapshot.value = next
        val reset = UsageReset(
            included = previous != null && crossedToFull(previous.includedRemainingPercent, next.includedRemainingPercent),
            api = previous != null && crossedToFull(previous.apiRemainingPercent, next.apiRemainingPercent),
        )
        if (reset.any) onReset(reset)
    }

    private fun throwIfUnexpected(t: Throwable) {
        if (t is SessionUnavailableException) return
        if (t is ConnectRpcException && (t.isRateLimited || t.isUnauthenticated)) return
        // A refused or unreadable call leaves the last snapshot up; Settings does not toast.
    }

    companion object {
        const val KEY = "account"
        const val VERSION = 1
        /** Shortest gap between successful fetches, matching the foreground poll. */
        const val MIN_INTERVAL_MS = 5 * 60_000L

        internal fun crossedToFull(previous: Int?, next: Int?): Boolean =
            previous != null && previous < 100 && next == 100

        internal fun isCreditsUnavailable(t: Throwable): Boolean {
            val error = t as? ConnectRpcException ?: return false
            return error.httpCode == 404 ||
                error.code == "unimplemented" ||
                error.code == "not_found" ||
                error.isUnreadableAnswer
        }
    }
}
