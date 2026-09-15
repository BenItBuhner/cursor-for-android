package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The first-run choice as state: owed by a sign-in, never by a restore, settled by either answer exactly the way the
 * Settings toggle settles it, and gone with the account.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OnboardingTest {

    private lateinit var prefs: PreferencesStore
    private lateinit var extendedMode: ExtendedMode
    private var now = 1_800_000_000_000L
    private var enabledRuns = 0
    private var disabledRuns = 0

    @Before
    fun setUp() {
        prefs = PreferencesStore(ApplicationProvider.getApplicationContext<Context>())
        extendedMode = ExtendedMode(prefs, now = { now }).also {
            it.onEnabled = { enabledRuns++ }
            it.onDisabled = { disabledRuns++ }
        }
    }

    private fun onboarding() = Onboarding(prefs, extendedMode)

    /** What a new process knows once it has read the stored flag; unknown (null) until then. */
    private suspend fun Onboarding.loaded(): Boolean? {
        assertThat(modeChoicePending.value).isNull()
        load()
        return modeChoicePending.value
    }

    @Test
    fun `nothing is owed until a sign-in, and a restore owes nothing`() = runBlocking<Unit> {
        // What a restored session is to this: no call at all. An install that already had its account is not asked.
        assertThat(onboarding().loaded()).isFalse()
        assertThat(prefs.modeChoicePending.first()).isFalse()
    }

    @Test
    fun `a sign-in owes the choice, in memory at once and on disk for the next process`() = runBlocking<Unit> {
        val first = onboarding()

        first.signedIn()

        assertThat(first.modeChoicePending.value).isTrue()
        assertThat(prefs.modeChoicePending.first()).isTrue()
        // A process that died between the sign-in and the choice: its successor reads the flag and asks.
        assertThat(onboarding().loaded()).isTrue()
    }

    @Test
    fun `reading the stored flag never undoes what this process already knows`() = runBlocking<Unit> {
        val onboarding = onboarding()
        onboarding.signedIn()
        // The disk says owed (the clear that follows a sign-out has yet to land); this process has just signed out.
        onboarding.signedOut()

        onboarding.load()

        assertThat(onboarding.modeChoicePending.value).isFalse()
    }

    @Test
    fun `SDK only settles the choice and leaves Extended mode off`() = runBlocking<Unit> {
        val onboarding = onboarding()
        onboarding.signedIn()

        onboarding.chooseSdkOnly()

        assertThat(onboarding.modeChoicePending.value).isFalse()
        assertThat(prefs.modeChoicePending.first()).isFalse()
        assertThat(extendedMode.isEnabled()).isFalse()
        assertThat(extendedMode.acknowledgedAt.first()).isNull()
        // Off already: no wipe ran for nothing.
        assertThat(disabledRuns).isEqualTo(0)
    }

    @Test
    fun `SDK only turns Extended mode off when an earlier sign-in on this device had left it on`() = runBlocking<Unit> {
        extendedMode.acknowledge()
        extendedMode.enable()
        val onboarding = onboarding()
        onboarding.signedIn()

        onboarding.chooseSdkOnly()

        assertThat(extendedMode.isEnabled()).isFalse()
        assertThat(disabledRuns).isEqualTo(1)
        assertThat(onboarding.modeChoicePending.value).isFalse()
    }

    @Test
    fun `Extended mode records the acknowledgment and turns the mode on, as the Settings toggle's confirm does`() = runBlocking<Unit> {
        val onboarding = onboarding()
        onboarding.signedIn()

        onboarding.chooseExtended()

        assertThat(extendedMode.isEnabled()).isTrue()
        assertThat(extendedMode.acknowledgedAt.first()).isEqualTo(now)
        assertThat(enabledRuns).isEqualTo(1)
        assertThat(onboarding.modeChoicePending.value).isFalse()
        assertThat(prefs.modeChoicePending.first()).isFalse()
    }

    @Test
    fun `an acknowledgment already on record is kept, not re-dated`() = runBlocking<Unit> {
        extendedMode.acknowledge()
        val acknowledgedAt = now
        now += 60_000
        val onboarding = onboarding()
        onboarding.signedIn()

        onboarding.chooseExtended()

        assertThat(extendedMode.isEnabled()).isTrue()
        assertThat(extendedMode.acknowledgedAt.first()).isEqualTo(acknowledgedAt)
    }

    @Test
    fun `a sign-out takes the owed choice with the account, so the next sign-in is asked again`() = runBlocking<Unit> {
        val onboarding = onboarding()
        onboarding.signedIn()

        // What the session does on sign-out: the graph's hook, then the account's keys cleared.
        onboarding.signedOut()
        prefs.clearSession()

        assertThat(onboarding.modeChoicePending.value).isFalse()
        assertThat(prefs.modeChoicePending.first()).isFalse()
        assertThat(onboarding().loaded()).isFalse()

        onboarding.signedIn()
        assertThat(onboarding.modeChoicePending.value).isTrue()
    }

    @Test
    fun `the acknowledgment is the device's and outlives the account`() = runBlocking<Unit> {
        val onboarding = onboarding()
        onboarding.signedIn()
        onboarding.chooseExtended()

        onboarding.signedOut()
        prefs.clearSession()

        // Like the Settings toggle: acknowledged once on this device, it is not asked for again.
        assertThat(extendedMode.acknowledgedAt.first()).isEqualTo(now)
    }
}
