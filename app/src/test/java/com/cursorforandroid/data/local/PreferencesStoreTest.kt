package com.cursorforandroid.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
