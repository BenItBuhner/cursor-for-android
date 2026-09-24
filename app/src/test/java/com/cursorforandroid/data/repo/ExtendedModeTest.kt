package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TranscriptEngine
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
    fun `the transcript engine is Beta for everyone until Stable is chosen, and only Beta reads the record`() = runBlocking<Unit> {
        val mode = mode()
        // The default, for a fresh install and an upgrade alike: nothing on the device says otherwise.
        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(mode.engine.first()).isEqualTo(TranscriptEngine.BETA)
        // With the mode off the engine chooses nothing: the documented API alone, as before the default changed.
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(mode.acknowledge()).isTrue()
        assertThat(mode.enable()).isTrue()
        // Extended mode on, never chosen: Beta, what 0.3.58 did, the record included.
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.EXTENDED)
        // Stable chosen: every private surface but the record read and the goal it carries; persisted, and read by
        // the flow the screens collect.
        assertThat(mode.setEngine(TranscriptEngine.STABLE)).isTrue()
        assertThat(mode.engine()).isEqualTo(TranscriptEngine.STABLE)
        val stable = mode.capabilities()
        assertThat(stable).isEqualTo(Capabilities.EXTENDED_STABLE)
        assertThat(stable.accountTranscript).isFalse()
        assertThat(stable.accountGoal).isFalse()
        assertThat(stable.accountQueue).isTrue()
        assertThat(stable.steering).isTrue()
        assertThat(stable.projects).isTrue()
        assertThat(stable.pinSync).isTrue()
        assertThat(stable.anyExtended).isTrue()
        assertThat(stable.copy(accountTranscript = true, accountGoal = true)).isEqualTo(Capabilities.EXTENDED)
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.EXTENDED_STABLE)
        assertThat(mode().engine()).isEqualTo(TranscriptEngine.STABLE)
        // Back to Beta.
        assertThat(mode.setEngine(TranscriptEngine.BETA)).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        // With the mode off the engine chooses nothing: the documented API alone, whatever it says.
        assertThat(mode.disable()).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(Capabilities.of(false, TranscriptEngine.BETA)).isEqualTo(Capabilities.DOCUMENTED)
        // The one-argument form is the Beta set: the tests' shorthand for every private surface at once.
        assertThat(Capabilities.of(true)).isEqualTo(Capabilities.EXTENDED)
        assertThat(TranscriptEngine.parse("beta")).isEqualTo(TranscriptEngine.BETA)
        assertThat(TranscriptEngine.parse("stable")).isEqualTo(TranscriptEngine.STABLE)
        assertThat(TranscriptEngine.parse("nonsense")).isEqualTo(TranscriptEngine.BETA)
        assertThat(TranscriptEngine.parse(null)).isEqualTo(TranscriptEngine.BETA)
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
        // Every private surface, the record read included: the Beta transcript engine is the default.
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
