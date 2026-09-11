package com.cursorforandroid.ui.theme

import android.app.Application
import android.app.UiModeManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The window behind the first frame is drawn before any of this app's code runs, from resources the platform
 * resolves with the night mode it has on file for the package. Telling it the app's own mode is what stops a Light
 * app on a dark phone from opening on a black window.
 */
@RunWith(AndroidJUnit4::class)
class AppNightModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val uiMode get() = shadowOf(app.getSystemService(UiModeManager::class.java))

    @Test
    @Config(sdk = [35])
    fun `each theme preference becomes the matching per-application night mode`() {
        AppNightMode.apply(app, ThemeMode.Light)
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_NO)

        AppNightMode.apply(app, ThemeMode.Dark)
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_YES)

        // Neither YES nor NO: the service turns this into UI_MODE_NIGHT_UNDEFINED, dropping the package's override
        // so that the app follows the phone again.
        AppNightMode.apply(app, ThemeMode.System)
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
    }

    @Test
    @Config(sdk = [30])
    fun `below API 31 there is no per-application night mode to set`() {
        AppNightMode.apply(app, ThemeMode.Light)
        AppNightMode.apply(app, ThemeMode.Dark)
        // Nothing thrown, and nothing claimed: the launch window follows the system on these versions.
        assertThat(uiMode.applicationNightMode).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
    }
}
