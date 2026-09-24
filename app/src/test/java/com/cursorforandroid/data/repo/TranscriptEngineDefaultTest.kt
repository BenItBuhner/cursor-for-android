package com.cursorforandroid.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Beta transcript engine is the default (2026-09-24): for a fresh install, and for every upgrade that never chose
 * an engine, while an install that chose Stable keeps it. The preference tells the two apart without a migration —
 * only the Settings switch (and the Stable / Beta picker before it) ever wrote it, so an absent key is "never chosen"
 * and a stored `stable` is the reader's own opt-out. Beta reads a private surface, so the default applies in Extended
 * mode alone: default mode stays on the documented API whatever the preference says.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TranscriptEngineDefaultTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The settings file an earlier build left: its own keys written, through a DataStore of its own that is closed
     * before the app's opens the file, as a process that has since been replaced would have (see PreferencesStoreTest).
     */
    private fun earlierBuild(write: (MutablePreferences) -> Unit) = runBlocking {
        val process = Job()
        val earlier = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + process),
            produceFile = { context.preferencesDataStoreFile("cursor_settings") },
        )
        earlier.edit(write)
        process.cancelAndJoin()
    }

    /** An install that had turned Extended mode on under an earlier build, with the setting introduced and its warning read. */
    private fun MutablePreferences.extendedModeOn() {
        this[booleanPreferencesKey("extended_mode")] = true
        this[longPreferencesKey("extended_mode_acknowledged_at")] = 1_790_000_000_000L
        this[booleanPreferencesKey("extended_mode_introduced")] = true
        this[stringPreferencesKey("theme_mode")] = "Dark"
    }

    private fun mode(prefs: PreferencesStore) = ExtendedMode(prefs).also { it.onEnabled = {} }

    @Test
    fun `a fresh install is on Beta, and reads the record only once Extended mode is on`() = runBlocking<Unit> {
        val prefs = PreferencesStore(context)
        val mode = mode(prefs)
        mode.migrateInstall()

        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(prefs.transcriptEngine.first()).isEqualTo(TranscriptEngine.BETA)
        // Default mode: the documented API only, exactly as before the default changed.
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)

        assertThat(mode.acknowledge()).isTrue()
        assertThat(mode.enable()).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        assertThat(mode.capabilities().accountTranscript).isTrue()
        assertThat(mode.capabilities().accountGoal).isTrue()
    }

    @Test
    fun `an upgrade that never chose an engine moves to Beta`() = runBlocking<Unit> {
        // Up to 0.3.89 such an install rendered with Stable, the default then; nothing of the engine was written.
        earlierBuild { it.extendedModeOn() }

        val prefs = PreferencesStore(context)
        val mode = mode(prefs)
        mode.migrateInstall()

        assertThat(mode.isEnabled()).isTrue()
        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        // Not a notice-owing upgrade: the Extended mode step ran under the earlier build and does not run again.
        assertThat(mode.noticePending.first()).isFalse()
    }

    @Test
    fun `an upgrade that had turned the engine off stays on Stable, through the mode going off and on and a sign-out`() = runBlocking<Unit> {
        earlierBuild {
            it.extendedModeOn()
            it[stringPreferencesKey("transcript_engine")] = "stable"
        }

        val prefs = PreferencesStore(context)
        val mode = mode(prefs)
        mode.migrateInstall()

        assertThat(mode.engine()).isEqualTo(TranscriptEngine.STABLE)
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED_STABLE)
        assertThat(mode.capabilities().accountTranscript).isFalse()

        assertThat(mode.disable()).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(mode.enable()).isTrue()
        assertThat(mode.engine()).isEqualTo(TranscriptEngine.STABLE)
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED_STABLE)

        prefs.clearSession()
        assertThat(PreferencesStore(context).transcriptEngine.first()).isEqualTo(TranscriptEngine.STABLE)
        // And the switch still goes back to Beta.
        assertThat(mode.setEngine(TranscriptEngine.BETA)).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
    }

    @Test
    fun `an upgrade that had chosen Beta stays on Beta`() = runBlocking<Unit> {
        earlierBuild {
            it.extendedModeOn()
            it[stringPreferencesKey("transcript_engine")] = "beta"
        }

        val mode = mode(PreferencesStore(context))
        mode.migrateInstall()

        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
    }

    @Test
    fun `default mode reads nothing private whatever the engine says`() = runBlocking<Unit> {
        val prefs = PreferencesStore(context)
        val mode = mode(prefs)
        assertThat(mode.isEnabled()).isFalse()

        // Never chosen (Beta by default), then each engine explicitly.
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(mode.capabilities.first()).isEqualTo(Capabilities.DOCUMENTED)
        for (engine in TranscriptEngine.entries) {
            assertThat(mode.setEngine(engine)).isTrue()
            assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
            assertThat(mode.capabilities.first()).isEqualTo(Capabilities.DOCUMENTED)
            assertThat(mode.capabilities().anyExtended).isFalse()
        }
    }

    @Test
    fun `nothing but the switch writes the engine, so a never-chosen install keeps following the default`() = runBlocking<Unit> {
        val key = stringPreferencesKey("transcript_engine")
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("engine_default_untouched") },
        )
        val prefs = PreferencesStore(context, store)
        val mode = mode(prefs)

        // Every path an install goes through without touching the switch: the first launch's step, reading the
        // engine and the capabilities, the mode on (its first pin sync owed) and off (the wipe), a sign-out.
        mode.migrateInstall()
        mode.engine()
        mode.capabilities()
        mode.acknowledge()
        mode.enable()
        mode.capabilities.first()
        mode.disable()
        prefs.clearSession()
        assertThat(store.data.first()[key]).isNull()
        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)

        // The switch, turned off: an explicit Stable, stored as such.
        assertThat(mode.setEngine(TranscriptEngine.STABLE)).isTrue()
        assertThat(store.data.first()[key]).isEqualTo("stable")
    }
}
