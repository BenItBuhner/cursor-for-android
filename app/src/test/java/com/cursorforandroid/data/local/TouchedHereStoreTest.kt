package com.cursorforandroid.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the phone records for Settings › "Unread only for chats from this phone": the switch (on by default, the
 * device's) and the chats this phone started or opened (the account's, bounded, kept on disk across restarts).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TouchedHereStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A settings file of this test's own, opened as a fresh process would open it: its own DataStore on its own scope. */
    private class OpenedFile(context: Context, name: String) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { context.preferencesDataStoreFile(name) })

        /** Closes the file as a process's end would: the next [OpenedFile] on it reads it back from disk. */
        suspend fun close() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `the switch is on by default, belongs to the device, and flipping it marks nothing read or unread`() = runBlocking {
        val prefs = PreferencesStore(context)
        assertThat(prefs.unreadOnlyTouchedHere.first()).isTrue()
        assertThat(prefs.localAgentState.first().unreadOnlyTouchedHere).isTrue()

        prefs.markRead("bc-1", 42L)
        prefs.markLaunchedHere("bc-1")
        prefs.markTouchedHere("bc-2")
        val before = prefs.localAgentState.first()

        prefs.setUnreadOnlyTouchedHere(false)
        val off = prefs.localAgentState.first()
        assertThat(off.unreadOnlyTouchedHere).isFalse()
        assertThat(off.copy(unreadOnlyTouchedHere = true)).isEqualTo(before)
        prefs.setUnreadOnlyTouchedHere(true)
        assertThat(prefs.localAgentState.first()).isEqualTo(before)

        // A sign-out takes the account's record of what was touched here and leaves the phone's choice alone.
        prefs.setUnreadOnlyTouchedHere(false)
        prefs.clearSession()
        assertThat(prefs.unreadOnlyTouchedHere.first()).isFalse()
        assertThat(prefs.localAgentState.first().touchedHereIds).isEmpty()
        assertThat(prefs.localAgentState.first().readMarkers).isEmpty()
    }

    @Test
    fun `the demo is this phone's own, so every chat in it may read as unread`() = runBlocking {
        val prefs = PreferencesStore(context)
        prefs.setDemoMode(true)
        assertThat(prefs.unreadOnlyTouchedHere.first()).isTrue()
        assertThat(prefs.localAgentState.first().unreadOnlyTouchedHere).isFalse()
        prefs.clearSession()
        assertThat(prefs.localAgentState.first().unreadOnlyTouchedHere).isTrue()
    }

    @Test
    fun `a chat started here and a chat opened here are both touched, and stay so across a reload of the store`() = runBlocking {
        val first = OpenedFile(context, "touched_reload")
        val before = PreferencesStore(context, first.store)
        before.markLaunchedHere("bc-phone")
        before.markTouchedHere("bc-opened")
        before.setUnreadOnlyTouchedHere(false)
        assertThat(before.localAgentState.first().touchedHereIds).containsExactly("bc-phone", "bc-opened")
        first.close()

        val second = OpenedFile(context, "touched_reload")
        val after = PreferencesStore(context, second.store)
        val local = after.localAgentState.first()
        assertThat(local.touchedHereIds).containsExactly("bc-phone", "bc-opened")
        assertThat(local.launchedHereIds).containsExactly("bc-phone")
        assertThat(after.unreadOnlyTouchedHere.first()).isFalse()
        second.close()
    }

    @Test
    fun `the record is bounded to the chats touched most recently`() = runBlocking {
        val file = OpenedFile(context, "touched_bounded")
        val prefs = PreferencesStore(context, file.store)
        (1..1_000).forEach { prefs.markTouchedHere("t-$it") }
        assertThat(prefs.localAgentState.first().touchedHereIds).hasSize(1_000)

        // Opened again, the oldest chat moves to the newest end; the next new chat pushes out the one now oldest.
        prefs.markTouchedHere("t-1")
        prefs.markTouchedHere("t-1001")
        val touched = prefs.localAgentState.first().touchedHereIds
        assertThat(touched).hasSize(1_000)
        assertThat(touched).containsAtLeast("t-1", "t-3", "t-1000", "t-1001")
        assertThat(touched).doesNotContain("t-2")
        file.close()
    }

    @Test
    fun `an install from before the record starts with every chat it launched`() = runBlocking {
        val file = OpenedFile(context, "touched_upgrade")
        file.store.edit { it[stringSetPreferencesKey("launched_here_ids")] = setOf("bc-old-1", "bc-old-2") }
        val prefs = PreferencesStore(context, file.store)
        assertThat(prefs.localAgentState.first().touchedHereIds).containsExactly("bc-old-1", "bc-old-2")

        prefs.markTouchedHere("bc-opened")
        assertThat(prefs.localAgentState.first().touchedHereIds).containsExactly("bc-old-1", "bc-old-2", "bc-opened")
        file.close()
    }
}
