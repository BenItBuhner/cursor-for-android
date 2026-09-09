package com.cursorforandroid

import android.app.Application
import android.app.UiModeManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** That the stored theme preference reaches the platform, so the next launch's window is resolved with it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityThemeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val uiMode get() = shadowOf(app.getSystemService(UiModeManager::class.java))

    @Test
    fun `starting the app hands the stored theme to the per-application night mode`() {
        runBlocking { app.appGraph.prefs.setThemeMode(ThemeMode.Light) }
        Robolectric.buildActivity(MainActivity::class.java).setup()
        idleUntil { uiMode.applicationNightMode == UiModeManager.MODE_NIGHT_NO }
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_NO)

        // And on a change, not only at startup: the preference is edited from the settings screen.
        runBlocking { app.appGraph.prefs.setThemeMode(ThemeMode.Dark) }
        idleUntil { uiMode.applicationNightMode == UiModeManager.MODE_NIGHT_YES }
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_YES)
    }

    /** The preference is read off the main thread, so the looper has to be pumped while that lands. */
    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
