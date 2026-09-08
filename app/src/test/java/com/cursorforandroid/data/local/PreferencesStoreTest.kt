package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
