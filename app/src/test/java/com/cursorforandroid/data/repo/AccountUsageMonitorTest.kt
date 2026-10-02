package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.AccountUsageApi
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.CreditGrants
import com.cursorforandroid.data.api.PeriodUsage
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.domain.AccountUsage
import com.cursorforandroid.domain.UsageReset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fetch cadence, credit-grant fallback, and the always-on reset-to-100% crossing. */
class AccountUsageMonitorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a first snapshot already at 100 does not notify, a later return to 100 does`() = runBlocking<Unit> {
        val resets = mutableListOf<UsageReset>()
        val api = ScriptedUsageApi(
            PeriodUsage(autoPercentUsed = 0.0, apiPercentUsed = 17.0, billingCycleEndMs = null),
            PeriodUsage(autoPercentUsed = 20.0, apiPercentUsed = 17.0, billingCycleEndMs = null),
            PeriodUsage(autoPercentUsed = 0.0, apiPercentUsed = 0.0, billingCycleEndMs = null),
        )
        var now = 0L
        val monitor = monitor(api = api, onReset = { resets += it }, now = { now })

        val first = monitor.refresh()!!
        assertThat(first.includedRemainingPercent).isEqualTo(100)
        assertThat(first.apiRemainingPercent).isEqualTo(83)
        assertThat(resets).isEmpty()

        now = 6 * 60_000L
        val mid = monitor.refresh()!!
        assertThat(mid.includedRemainingPercent).isEqualTo(80)
        assertThat(resets).isEmpty()

        now = 12 * 60_000L
        val reset = monitor.refresh()!!
        assertThat(reset.includedRemainingPercent).isEqualTo(100)
        assertThat(reset.apiRemainingPercent).isEqualTo(100)
        assertThat(resets).containsExactly(UsageReset(included = true, api = true))
        assertThat(api.periodCalls).isEqualTo(3)
        assertThat(api.grantCalls).isEqualTo(3)
    }

    @Test
    fun `demo sample never hits the account, and a refresh younger than five minutes is skipped`() = runBlocking<Unit> {
        val api = ScriptedUsageApi(PeriodUsage(autoPercentUsed = 50.0, apiPercentUsed = 50.0, billingCycleEndMs = null))
        val demo = monitor(api = api, isDemo = true)
        assertThat(demo.snapshot.value).isEqualTo(AccountUsage.SAMPLE)
        assertThat(demo.refresh()).isEqualTo(AccountUsage.SAMPLE)
        assertThat(api.periodCalls).isEqualTo(0)

        var now = 10_000L
        val live = monitor(api = api, now = { now })
        live.refresh()
        now = 10_000L + AccountUsageMonitor.MIN_INTERVAL_MS - 1
        live.refresh()
        assertThat(api.periodCalls).isEqualTo(1)
        now += 2
        live.refresh()
        assertThat(api.periodCalls).isEqualTo(2)
        live.refresh(force = true)
        assertThat(api.periodCalls).isEqualTo(3)
    }

    @Test
    fun `extended mode off, a throttle pause, and a missing client all leave the last snapshot`() = runBlocking<Unit> {
        val api = ScriptedUsageApi(PeriodUsage(autoPercentUsed = 17.0, apiPercentUsed = 0.0, billingCycleEndMs = null))
        var allowed = true
        var pause: Long? = null
        var now = 0L
        val monitor = monitor(api = api, sessionAllowed = { allowed }, pauseUntil = { pause }, now = { now })
        assertThat(monitor.refresh()!!.includedRemainingPercent).isEqualTo(83)

        allowed = false
        now = 10 * 60_000L
        monitor.refresh()
        assertThat(api.periodCalls).isEqualTo(1)

        allowed = true
        pause = now + 60_000L
        monitor.refresh()
        assertThat(api.periodCalls).isEqualTo(1)

        pause = null
        val silent = AccountUsageMonitor(api = { null }, sessionAllowed = { true }, now = { now + 10 * 60_000L })
        silent.show(AccountUsage.SAMPLE)
        assertThat(silent.refresh()).isEqualTo(AccountUsage.SAMPLE)
    }

    @Test
    fun `an unimplemented grants call is skipped from then on, and a period failure keeps the last numbers`() = runBlocking<Unit> {
        val api = ScriptedUsageApi(
            PeriodUsage(autoPercentUsed = 17.0, apiPercentUsed = 0.0, billingCycleEndMs = null),
            grants = { throw ConnectRpcException(404, "unimplemented", "no grants") },
        )
        var now = 0L
        val monitor = monitor(api = api, now = { now })
        val first = monitor.refresh()!!
        assertThat(first.includedRemainingPercent).isEqualTo(83)
        assertThat(first.creditBalanceCents).isNull()
        assertThat(api.grantCalls).isEqualTo(1)

        now = 6 * 60_000L
        monitor.refresh()
        assertThat(api.grantCalls).isEqualTo(1)
        assertThat(api.periodCalls).isEqualTo(2)

        api.period = { throw SessionUnavailableException("Not signed in.", SessionUnavailableException.KEY_REJECTED) }
        now = 12 * 60_000L
        assertThat(monitor.refresh()!!.includedRemainingPercent).isEqualTo(83)
    }

    @Test
    fun `restore reads the disk snapshot and never notifies`() = runBlocking<Unit> {
        val resets = mutableListOf<UsageReset>()
        val cache = JsonDiskCache(folder.newFolder())
        cache.write(AccountUsageMonitor.KEY, AccountUsage.serializer(), AccountUsageMonitor.VERSION, AccountUsage.SAMPLE)
        val monitor = monitor(api = ScriptedUsageApi(), cache = cache, onReset = { resets += it })
        monitor.restore()
        assertThat(monitor.snapshot.value).isEqualTo(AccountUsage.SAMPLE)
        assertThat(resets).isEmpty()
    }

    @Test
    fun `reset drops a live snapshot and restores the demo sample`() {
        val live = monitor(api = ScriptedUsageApi())
        live.show(AccountUsage.SAMPLE)
        live.reset()
        assertThat(live.snapshot.value).isNull()

        val demo = monitor(api = ScriptedUsageApi(), isDemo = true)
        demo.reset()
        assertThat(demo.snapshot.value).isEqualTo(AccountUsage.SAMPLE)
    }

    @Test
    fun `crossedToFull is a return from below 100, not a stay or a first 100`() {
        assertThat(AccountUsageMonitor.crossedToFull(null, 100)).isFalse()
        assertThat(AccountUsageMonitor.crossedToFull(100, 100)).isFalse()
        assertThat(AccountUsageMonitor.crossedToFull(83, 100)).isTrue()
        assertThat(AccountUsageMonitor.crossedToFull(0, 100)).isTrue()
        assertThat(AccountUsageMonitor.crossedToFull(83, 90)).isFalse()
        assertThat(AccountUsageMonitor.crossedToFull(100, 83)).isFalse()
    }

    private fun monitor(
        api: AccountUsageApi,
        cache: JsonDiskCache? = null,
        isDemo: Boolean = false,
        sessionAllowed: suspend () -> Boolean = { true },
        pauseUntil: () -> Long? = { null },
        now: () -> Long = { 0L },
        onReset: (UsageReset) -> Unit = {},
    ) = AccountUsageMonitor(
        api = { api },
        cache = cache,
        isDemo = { isDemo },
        sessionAllowed = sessionAllowed,
        pauseUntil = pauseUntil,
        now = now,
        onReset = onReset,
    )

    private class ScriptedUsageApi(
        vararg periods: PeriodUsage,
        private val grants: () -> CreditGrants? = {
            CreditGrants(hasCreditGrants = true, creditBalanceCents = 90_000L, totalCents = 90_000L, usedCents = 0L)
        },
    ) : AccountUsageApi {
        private val queue = ArrayDeque(periods.toList())
        private val lastQueued = periods.lastOrNull()
            ?: PeriodUsage(autoPercentUsed = 0.0, apiPercentUsed = 0.0, billingCycleEndMs = null)
        var period: suspend () -> PeriodUsage = {
            queue.removeFirstOrNull() ?: lastQueued
        }
        var periodCalls = 0
        var grantCalls = 0

        override suspend fun currentPeriod(): PeriodUsage {
            periodCalls++
            return period()
        }

        override suspend fun creditGrants(): CreditGrants? {
            grantCalls++
            return grants()
        }
    }
}
