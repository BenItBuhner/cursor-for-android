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
