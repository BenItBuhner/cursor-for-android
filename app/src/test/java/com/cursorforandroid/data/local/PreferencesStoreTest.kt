package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

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
}
