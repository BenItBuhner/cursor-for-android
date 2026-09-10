package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Capabilities
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The setting itself: the acknowledgment it takes, the hooks it runs, and the one-time step an upgraded install goes through. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExtendedModeTest {

    private lateinit var prefs: PreferencesStore
    private var now = 1_800_000_000_000L
    private var hadAccount = false
    private val disabledRuns = mutableListOf<Boolean>()
    private var enabledRuns = 0

    @Before
    fun setUp() {
        prefs = PreferencesStore(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun mode(): ExtendedMode = ExtendedMode(prefs, hadAccount = { hadAccount }, now = { now }).also { mode ->
        // What the setting reads at the moment the wipe runs: it must already be off.
        mode.onDisabled = { disabledRuns += prefs.extendedMode.first() }
        mode.onEnabled = { enabledRuns++ }
    }

    @Test
    fun `off by default, with the documented capabilities`() = runBlocking<Unit> {
        val mode = mode()

        assertThat(mode.isEnabled()).isFalse()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(mode.capabilities().anyExtended).isFalse()
        assertThat(mode.acknowledgedAt.first()).isNull()
    }

    @Test
    fun `cannot be turned on until the warning has been acknowledged`() = runBlocking<Unit> {
        val mode = mode()

        assertThat(mode.enable()).isFalse()
        assertThat(mode.isEnabled()).isFalse()
        assertThat(enabledRuns).isEqualTo(0)

        assertThat(mode.acknowledge()).isTrue()
        assertThat(mode.acknowledgedAt.first()).isEqualTo(now)
        assertThat(mode.enable()).isTrue()
        assertThat(mode.isEnabled()).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        assertThat(enabledRuns).isEqualTo(1)
        // Turning it on again does nothing more.
        assertThat(mode.enable()).isTrue()
        assertThat(enabledRuns).isEqualTo(1)
    }

    @Test
    fun `turning it on owes the account a first pin sync, so pins made here are pushed rather than replaced`() = runBlocking<Unit> {
        val mode = mode()
        prefs.setPinsMigrated(true)
        mode.acknowledge()

        mode.enable()

        assertThat(prefs.pinsMigrated.first()).isFalse()
    }

    @Test
    fun `turning it off writes the setting before the wipe runs, and runs the wipe once`() = runBlocking<Unit> {
        val mode = mode()
        mode.acknowledge()
        mode.enable()

        assertThat(mode.disable()).isTrue()

        assertThat(mode.isEnabled()).isFalse()
        assertThat(disabledRuns).containsExactly(false)
        // The acknowledgment stands: the next time it is turned on, the warning is not shown again.
        assertThat(mode.acknowledgedAt.first()).isEqualTo(now)
        // Already off: nothing to wipe again.
        assertThat(mode.disable()).isTrue()
        assertThat(disabledRuns).hasSize(1)
    }

    @Test
    fun `a wipe that fails does not undo the setting`() = runBlocking<Unit> {
        val mode = mode()
        mode.onDisabled = { throw IllegalStateException("disk") }
        mode.acknowledge()
        mode.enable()

        assertThat(mode.disable()).isTrue()

        assertThat(mode.isEnabled()).isFalse()
    }

    @Test
    fun `a fresh install records the setting and owes no notice`() = runBlocking<Unit> {
        val mode = mode()

        mode.migrateInstall()

        assertThat(prefs.extendedModeIntroduced.first()).isTrue()
        assertThat(mode.noticePending.first()).isFalse()
        assertThat(disabledRuns).isEmpty()
        assertThat(mode.isEnabled()).isFalse()
    }

    @Test
    fun `an install that was signed in starts off, is wiped like a turn-off, and is owed the notice once`() = runBlocking<Unit> {
        hadAccount = true
        val mode = mode()

        mode.migrateInstall()

        assertThat(mode.isEnabled()).isFalse()
        assertThat(mode.noticePending.first()).isTrue()
        assertThat(disabledRuns).containsExactly(false)

        // The second launch of the build finds the step done.
        mode.migrateInstall()
        assertThat(disabledRuns).hasSize(1)

        assertThat(mode.dismissNotice()).isTrue()
        assertThat(mode.noticePending.first()).isFalse()
        mode().migrateInstall()
        assertThat(mode.noticePending.first()).isFalse()
    }

    @Test
    fun `the capabilities follow the setting`() = runBlocking<Unit> {
        val mode = mode()
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.DOCUMENTED)

        mode.acknowledge()
        mode.enable()
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.EXTENDED)
        assertThat(mode.enabled.first()).isTrue()

        mode.disable()
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.DOCUMENTED)
    }

    @Test
    fun `settling the pins folds the changes the server never took into the device's own set and forgets the sync`() = runBlocking<Unit> {
        prefs.setPinnedIds(setOf("bc-1", "bc-2"))
        prefs.setPendingPinChange("bc-3", pinned = true)
        prefs.setPendingPinChange("bc-2", pinned = false)
        prefs.setPinsMigrated(true)

        assertThat(prefs.settlePinsLocally()).isTrue()

        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-1", "bc-3")
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(prefs.pinsMigrated.first()).isFalse()
    }
}
