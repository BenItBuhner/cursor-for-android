package com.cursorforandroid.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PreferencesStoreTest {

    @Test
    fun `oled black defaults off and persists`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.oledBlack.first()).isFalse()
        prefs.setOledBlack(true)
        assertThat(prefs.oledBlack.first()).isTrue()
        prefs.setThemeMode(ThemeMode.Light)
        assertThat(prefs.oledBlack.first()).isTrue()
        prefs.setOledBlack(false)
        assertThat(prefs.oledBlack.first()).isFalse()
    }

    @Test
    fun `signing out drops the account's state and keeps the device's`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        prefs.setDemoMode(true)
        prefs.setCachedUser(CursorUser("key", "a@b.c", "A", "B", 7))
        prefs.setCredentialInfo(CredentialInfo(SignInMethod.Cursor, expiresAtMs = 1L))
        prefs.setPinsMigrated(true)
        prefs.togglePinnedAwaitingServer("bc-1", recordPending = true)
        prefs.markRead("bc-1", 42L)
        prefs.markLaunchedHere("bc-1")
        // Device-level, so a sign-out leaves it alone.
        prefs.setOledBlack(true)
        prefs.setAutoUpdate(false)
        prefs.setPinSyncEnabled(false)

        prefs.clearSession()

        assertThat(prefs.demoMode.first()).isFalse()
        assertThat(prefs.cachedUser.first()).isNull()
        assertThat(prefs.credentialInfo.first()).isNull()
        assertThat(prefs.pinsMigrated.first()).isFalse()
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        val local = prefs.localAgentState.first()
        assertThat(local.pinnedIds).isEmpty()
        assertThat(local.readMarkers).isEmpty()
        assertThat(local.launchedHereIds).isEmpty()

        assertThat(prefs.oledBlack.first()).isTrue()
        assertThat(prefs.autoUpdate.first()).isFalse()
        assertThat(prefs.pinSyncEnabled.first()).isFalse()
    }

    @Test
    fun `a pin the settings could not save is not reported as saved`() = runBlocking<Unit> {
        val store = FailingWrites(ApplicationProvider.getApplicationContext())
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext(), store)

        store.failing = true
        assertThat(prefs.togglePinnedAwaitingServer("bc-1", recordPending = true)).isNull()
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()

        store.failing = false
        assertThat(prefs.togglePinnedAwaitingServer("bc-1", recordPending = true)).isTrue()
        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-1")
    }

    /**
     * The sign-out cleanup can be the write that fails. Reporting it as done would leave the previous account's
     * pins, read markers and identity to be read for the rest of the process and adopted by whoever signs in next.
     */
    @Test
    fun `a sign-out cleanup that could not be written shows as cleared and is written later`() = runBlocking<Unit> {
        val store = FailingWrites(ApplicationProvider.getApplicationContext())
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext(), store)
        prefs.setCachedUser(CursorUser("key", "a@b.c", "A", "B", 7))
        prefs.togglePinnedAwaitingServer("bc-1", recordPending = true)
        prefs.markRead("bc-1", 42L)

        store.failing = true
        assertThat(prefs.clearSession()).isFalse()

        // Nothing of the account is readable although it is all still in the file.
        assertThat(prefs.cachedUser.first()).isNull()
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()
        assertThat(prefs.localAgentState.first().readMarkers).isEmpty()
        assertThat(prefs.pendingPinChanges.first()).isEmpty()
        assertThat(store.data.first()[stringSetPreferencesKey("pinned_ids")]).containsExactly("bc-1")

        // The next account's sign-in finishes the removal before its own state lands, so nothing is inherited.
        store.failing = false
        prefs.setCachedUser(CursorUser("next", "d@e.f", "D", "E", 8))
        assertThat(store.data.first()[stringSetPreferencesKey("pinned_ids")]).isNull()
        assertThat(prefs.cachedUser.first()?.email).isEqualTo("d@e.f")
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()
        prefs.togglePinnedAwaitingServer("bc-2", recordPending = false)
        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-2")
    }

    /** The real store, with its writes refused on demand: what a full disk or an unreadable file does. */
    private class FailingWrites(context: Context) : DataStore<Preferences> {
        private val delegate = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("failing_writes") },
        )

        @Volatile var failing = false

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failing) throw IOException("No space left on device")
            return delegate.updateData(transform)
        }
    }

    @Test
    fun `a corrupt settings file reads as defaults and is replaced by the next write`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "datastore/cursor_settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })

        val prefs = PreferencesStore(context)
        assertThat(prefs.demoMode.first()).isFalse()
        assertThat(prefs.themeMode.first()).isEqualTo(ThemeMode.System)
        assertThat(prefs.localAgentState.first().pinnedIds).isEmpty()
        assertThat(prefs.autoUpdate.first()).isTrue()

        // The file was replaced from empty rather than being left broken, so settings stick again.
        prefs.setThemeMode(ThemeMode.Light)
        assertThat(prefs.themeMode.first()).isEqualTo(ThemeMode.Light)
    }

    @Test
    fun `snooze persists until unsnoozed`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.localAgentState.first().snoozedUntil).isEmpty()
        prefs.snooze("bc-1", 1_800_000_000_000L, nowMillis = 1_700_000_000_000L)
        assertThat(prefs.localAgentState.first().snoozedUntil).containsExactly("bc-1", 1_800_000_000_000L)
        assertThat(prefs.localAgentState.first().snoozedAt).containsExactly("bc-1", 1_700_000_000_000L)
        prefs.snooze("bc-2", Long.MAX_VALUE, nowMillis = 1_700_000_000_100L)
        assertThat(prefs.localAgentState.first().snoozedUntil).containsExactly("bc-1", 1_800_000_000_000L, "bc-2", Long.MAX_VALUE)
        prefs.unsnooze("bc-1")
        assertThat(prefs.localAgentState.first().snoozedUntil).containsExactly("bc-2", Long.MAX_VALUE)
        assertThat(prefs.localAgentState.first().snoozedAt).containsExactly("bc-2", 1_700_000_000_100L)
        prefs.unsnooze("bc-2")
        assertThat(prefs.localAgentState.first().snoozedUntil).isEmpty()
        assertThat(prefs.localAgentState.first().snoozedAt).isEmpty()
    }

    @Test
    fun `timed snoozes drop when the clock runs out`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        prefs.snooze("bc-1", 1_800_000_000_000L, nowMillis = 1_700_000_000_000L)
        prefs.snooze("bc-2", Long.MAX_VALUE, nowMillis = 1_700_000_000_100L)
        prefs.expireSnoozes(nowMillis = 1_800_000_000_000L)
        assertThat(prefs.localAgentState.first().snoozedUntil.keys).isEqualTo(setOf("bc-2"))
        assertThat(prefs.localAgentState.first().snoozedAt.keys).isEqualTo(setOf("bc-2"))
    }

    @Test
    fun `remembering a model persists it without touching the other launch defaults`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        prefs.setComposerDefaults(
            repoUrl = "https://github.com/acme/app",
            ref = "main",
            modelId = "auto-smart",
            params = emptyMap(),
            autoCreatePr = true,
        )
        prefs.rememberModel("composer-2.5", mapOf("fast" to "false"))
        val defaults = prefs.composerDefaults.first()
        assertThat(defaults.modelId).isEqualTo("composer-2.5")
        assertThat(defaults.modelParams).containsExactly("fast", "false")
        assertThat(defaults.modelChosen).isTrue()
        assertThat(defaults.repoUrl).isEqualTo("https://github.com/acme/app")
        assertThat(defaults.ref).isEqualTo("main")
        assertThat(defaults.autoCreatePr).isTrue()
    }

    @Test
    fun `pinned models persist most-recently-pinned first`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.pinnedModelIds.first()).isEmpty()
        prefs.togglePinnedModel("composer-2")
        prefs.togglePinnedModel("cursor-grok-4.6")
        assertThat(prefs.pinnedModelIds.first()).containsExactly("cursor-grok-4.6", "composer-2").inOrder()
        prefs.togglePinnedModel("composer-2")
        assertThat(prefs.pinnedModelIds.first()).containsExactly("cursor-grok-4.6").inOrder()
        prefs.togglePinnedModel("cursor-grok-4.6")
        assertThat(prefs.pinnedModelIds.first()).isEmpty()
    }
}
