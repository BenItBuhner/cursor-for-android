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
 * The Beta transcript engine is the only one Extended mode renders with (2026-10-01, "Full transcript history" always
 * on): for a fresh install, for every upgrade that never chose an engine, and for one that had turned the switch off —
 * its stored `stable` is no longer read, and the retired-settings sweep deletes it. Beta reads a private surface, so
 * it applies in Extended mode alone: default mode stays on the documented API whatever the engine.
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
    fun `an upgrade that had turned the engine off is on Beta, and the stored choice is swept away`() = runBlocking<Unit> {
        earlierBuild {
            it.extendedModeOn()
            it[stringPreferencesKey("transcript_engine")] = "stable"
        }

        val prefs = PreferencesStore(context)
        val mode = mode(prefs)
        mode.migrateInstall()

        assertThat(mode.engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(mode.capabilities()).isEqualTo(Capabilities.EXTENDED)
        assertThat(mode.capabilities().accountTranscript).isTrue()

        assertThat(prefs.forgetRetiredSettings()).isTrue()
        assertThat(mode.disable()).isTrue()
        assertThat(mode.capabilities()).isEqualTo(Capabilities.DOCUMENTED)
        assertThat(mode.enable()).isTrue()
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
    fun `nothing writes the engine, so no install can be left on Stable`() = runBlocking<Unit> {
        val key = stringPreferencesKey("transcript_engine")
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("engine_default_untouched") },
        )
        val prefs = PreferencesStore(context, store)
        val mode = mode(prefs)

        // Every path an install goes through: the first launch's step, reading the engine and the capabilities, the
        // mode on (its first pin sync owed) and off (the wipe), a sign-out — and the tests' seam swapping the engine.
        mode.migrateInstall()
        mode.engine()
        mode.capabilities()
        mode.acknowledge()
        mode.enable()
        mode.capabilities.first()
        mode.disable()
        prefs.clearSession()
        assertThat(mode.setEngine(TranscriptEngine.STABLE)).isTrue()
        assertThat(store.data.first()[key]).isNull()
        // A new process starts on Beta again.
        assertThat(mode(prefs).engine()).isEqualTo(TranscriptEngine.BETA)
    }
}
