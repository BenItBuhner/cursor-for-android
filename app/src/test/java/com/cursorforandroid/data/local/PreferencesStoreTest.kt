package com.cursorforandroid.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    }

    @Test
    fun `the Projects keep the order they were dropped in, the ones not shown after them, until sign-out`() = runBlocking {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.localAgentState.first().projectOrder).isEmpty()

        prefs.setProjectOrder(listOf("bc-c", "bc-a", "bc-b"))
        assertThat(prefs.localAgentState.first().projectOrder).containsExactly("bc-c", "bc-a", "bc-b").inOrder()

        // Arranged again while one was not on the page (archived, filtered out): it keeps its place behind the ones that were.
        prefs.setProjectOrder(listOf("bc-a", "bc-c", "bc-a"))
        assertThat(prefs.localAgentState.first().projectOrder).containsExactly("bc-a", "bc-c", "bc-b").inOrder()

        // Bounded at 500 ids, the ones furthest down forgotten first.
        prefs.setProjectOrder((0 until 520).map { "bc-p$it" })
        val bounded = prefs.localAgentState.first().projectOrder
        assertThat(bounded).hasSize(500)
        assertThat(bounded.first()).isEqualTo("bc-p0")
        assertThat(bounded.last()).isEqualTo("bc-p499")

        prefs.clearSession()
        assertThat(prefs.localAgentState.first().projectOrder).isEmpty()
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

    /**
     * A store whose flow never pushes an update to a collector already collecting — what DataStore 1.1's `data` does
     * to a collector whose collection raced the write (b/431787506, fixed upstream only from 1.3.0-alpha03) — while a
     * fresh read returns the latest, as the real store's does.
     */
    private class DroppedPushes(context: Context) : DataStore<Preferences> {
        private val delegate = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("dropped_pushes") },
        )

        override val data: Flow<Preferences> get() = kotlinx.coroutines.flow.flow { emit(delegate.data.first()); kotlinx.coroutines.awaitCancellation() }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = delegate.updateData(transform)
    }

    @Test
    fun `a collector sees every write this process makes, even when the store's flow drops the push`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesStore(context, DroppedPushes(context))
        val seen = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val collecting = launch(Dispatchers.Default) { prefs.localAgentState.collect { seen += it.pinnedIds } }
        kotlinx.coroutines.withTimeout(5_000) { while (seen.isEmpty()) kotlinx.coroutines.yield() }
        assertThat(seen.last()).isEmpty()

        // The store's flow will never say so; the write itself is what the collector is told.
        assertThat(prefs.setPinnedIds(setOf("bc-9"))).isTrue()
        kotlinx.coroutines.withTimeout(5_000) { while (seen.none { "bc-9" in it }) kotlinx.coroutines.yield() }
        assertThat(prefs.localAgentState.first().pinnedIds).containsExactly("bc-9")
        // And a later collector starts from what was written, not from what the store's flow last pushed.
        assertThat(prefs.togglePinnedAwaitingServer("bc-10", recordPending = false)).isTrue()
        kotlinx.coroutines.withTimeout(5_000) { while (seen.none { "bc-10" in it }) kotlinx.coroutines.yield() }
        assertThat(seen.last()).containsExactly("bc-9", "bc-10")
        collecting.cancel()
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
    fun `confirm before stopping is on for a fresh install`() = runBlocking<Unit> {
        assertThat(PreferencesStore(ApplicationProvider.getApplicationContext()).confirmStop.first()).isTrue()
    }

    @Test
    fun `an install upgraded from a build without Confirm before stopping reads it as on, and keeps what the user sets through a sign-out`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The file an earlier build left: its own settings written, this one never. Written through a DataStore of its
        // own, closed before the app's opens the file, as a process that has since been replaced would have.
        val earlierBuild = Job()
        val earlier = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + earlierBuild),
            produceFile = { context.preferencesDataStoreFile("cursor_settings") },
        )
        earlier.edit { p ->
            p[stringPreferencesKey("theme_mode")] = ThemeMode.Light.name
            p[booleanPreferencesKey("live_notifications")] = false
        }
        earlierBuild.cancelAndJoin()

        val prefs = PreferencesStore(context)
        assertThat(prefs.themeMode.first()).isEqualTo(ThemeMode.Light)
        assertThat(prefs.liveNotifications.first()).isFalse()
        assertThat(prefs.confirmStop.first()).isTrue()

        prefs.setConfirmStop(false)
        assertThat(prefs.confirmStop.first()).isFalse()
        // The device's preference, not the account's.
        prefs.clearSession()
        assertThat(prefs.confirmStop.first()).isFalse()
        prefs.setConfirmStop(true)
        assertThat(prefs.confirmStop.first()).isTrue()
    }

    @Test
    fun `the pre-release, haptic and voice input switches an earlier build stored are deleted, and nothing else is`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("retired_settings") },
        )
        store.edit { p ->
            p[booleanPreferencesKey("update_include_pre_releases")] = true
            p[booleanPreferencesKey("haptic_feedback")] = false
            p[booleanPreferencesKey("voice_input")] = true
            p[booleanPreferencesKey("auto_update")] = false
            p[stringPreferencesKey("theme_mode")] = ThemeMode.Light.name
        }
        val prefs = PreferencesStore(context, store)

        assertThat(prefs.forgetRetiredSettings()).isTrue()
        val left = store.data.first().asMap().keys.map { it.name }
        assertThat(left).containsExactly("auto_update", "theme_mode")
        assertThat(prefs.autoUpdate.first()).isFalse()
        assertThat(prefs.themeMode.first()).isEqualTo(ThemeMode.Light)
        // Once they are gone there is nothing to write.
        assertThat(prefs.forgetRetiredSettings()).isTrue()
    }

    @Test
    fun `crash reports are off until asked for, and the answer belongs to the device, not the account`() = runBlocking<Unit> {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.crashReports.first()).isFalse()
        prefs.setCrashReports(true)
        assertThat(prefs.crashReports.first()).isTrue()
        // A consent given on this phone is not withdrawn by signing out of an account.
        prefs.clearSession()
        assertThat(prefs.crashReports.first()).isTrue()
        prefs.setCrashReports(false)
        assertThat(prefs.crashReports.first()).isFalse()
    }

    @Test
    fun `the new chat page lists recent chats until another layout is chosen, and the choice is the device's`() = runBlocking<Unit> {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.RECENT)
        prefs.setNewChatHome(NewChatHome.PROJECTS)
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.PROJECTS)
        prefs.clearSession()
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.PROJECTS)
        prefs.setNewChatHome(NewChatHome.COMPOSER)
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.COMPOSER)
        prefs.clearSession()
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.COMPOSER)
        prefs.setNewChatHome(NewChatHome.RECENT)
        assertThat(prefs.newChatHome.first()).isEqualTo(NewChatHome.RECENT)
    }

    @Test
    fun `the wide layout's pane widths and each size class's panel are kept for the device`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesStore(context)
        assertThat(prefs.panelWidthDp.first()).isNull()
        assertThat(prefs.railWidthDp.first()).isNull()
        assertThat(prefs.panelOpen(PaneWidthClass.Medium).first()).isFalse()
        assertThat(prefs.panelOpen(PaneWidthClass.Expanded).first()).isFalse()
        prefs.setPanelWidthDp(512)
        prefs.setRailWidthDp(240)
        prefs.setPanelOpen(PaneWidthClass.Expanded, true)
        // Each class keeps its own: opened on a tablet on its side, still shut upright.
        assertThat(prefs.panelOpen(PaneWidthClass.Expanded).first()).isTrue()
        assertThat(prefs.panelOpen(PaneWidthClass.Medium).first()).isFalse()
        // The device's, not the account's: a sign-out leaves them, and the next start reads them back.
        prefs.clearSession()
        val restarted = PreferencesStore(context)
        assertThat(restarted.panelWidthDp.first()).isEqualTo(512)
        assertThat(restarted.railWidthDp.first()).isEqualTo(240)
        assertThat(restarted.panelOpen(PaneWidthClass.Expanded).first()).isTrue()
        restarted.setPanelOpen(PaneWidthClass.Expanded, false)
        assertThat(restarted.panelOpen(PaneWidthClass.Expanded).first()).isFalse()
    }

    @Test
    fun `a new chat page value this build does not know reads as Recent`() {
        assertThat(NewChatHome.parse("pinned")).isEqualTo(NewChatHome.RECENT)
        assertThat(NewChatHome.parse(null)).isEqualTo(NewChatHome.RECENT)
        assertThat(NewChatHome.parse(NewChatHome.PROJECTS.key)).isEqualTo(NewChatHome.PROJECTS)
        assertThat(NewChatHome.parse("composer")).isEqualTo(NewChatHome.COMPOSER)
    }

    @Test
    fun `mark all read writes every stamp and does not regress a newer marker`() = runBlocking<Unit> {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        prefs.markRead("already", 2_000L)
        prefs.markAllRead(mapOf("a" to 100L, "b" to 200L, "already" to 1_000L, "skip" to 0L))
        assertThat(prefs.localAgentState.first().readMarkers).containsExactly("a", 100L, "b", 200L, "already", 2_000L)
        prefs.markAllRead(emptyMap())
        assertThat(prefs.localAgentState.first().readMarkers).containsExactly("a", 100L, "b", 200L, "already", 2_000L)
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

    /**
     * A notice closed over a chat's composer is remembered by chat and by its identity, forgotten on request, and
     * bounded: eight per chat, the oldest dropped, and two hundred chats, the ones least recently written dropped.
     * The account's: a sign-out takes them.
     */
    @Test
    fun `closed notices persist per chat, are forgotten on request, stay bounded and go with the account`() = runBlocking<Unit> {
        val prefs = PreferencesStore(ApplicationProvider.getApplicationContext())
        assertThat(prefs.dismissedNotices.first()).isEmpty()
        prefs.setNoticeDismissed("bc-1", "aaaa", dismissed = true)
        prefs.setNoticeDismissed("bc-1", "bbbb", dismissed = true)
        prefs.setNoticeDismissed("bc-2", "aaaa", dismissed = true)
        assertThat(prefs.dismissedNotices.first()).containsExactly("bc-1", setOf("aaaa", "bbbb"), "bc-2", setOf("aaaa"))
        // Closing the same notice twice is one entry; forgetting it leaves the chat's other one.
        prefs.setNoticeDismissed("bc-1", "aaaa", dismissed = true)
        prefs.setNoticeDismissed("bc-1", "aaaa", dismissed = false)
        assertThat(prefs.dismissedNotices.first()["bc-1"]).containsExactly("bbbb")
        // A chat with nothing closed any more leaves the record.
        prefs.setNoticeDismissed("bc-2", "aaaa", dismissed = false)
        assertThat(prefs.dismissedNotices.first().keys).containsExactly("bc-1")
        // A notice whose words change on every read cannot grow the file: the newest eight stay.
        (1..10).forEach { prefs.setNoticeDismissed("bc-1", "n$it", dismissed = true) }
        assertThat(prefs.dismissedNotices.first()["bc-1"]).containsExactly("n3", "n4", "n5", "n6", "n7", "n8", "n9", "n10")
        // Nor can the chats: the two hundred most recently written keep theirs.
        (1..205).forEach { prefs.setNoticeDismissed("chat-$it", "x", dismissed = true) }
        val chats = prefs.dismissedNotices.first().keys
        assertThat(chats).hasSize(200)
        assertThat(chats).doesNotContain("bc-1")
        assertThat(chats).containsAtLeast("chat-6", "chat-205")
        assertThat(chats).doesNotContain("chat-5")
        prefs.clearSession()
        assertThat(prefs.dismissedNotices.first()).isEmpty()
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
