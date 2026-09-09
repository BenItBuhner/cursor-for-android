package com.cursorforandroid.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build

/**
 * Tells the platform which night mode this app runs in, so that it resolves the app's *own* resources with it —
 * the launch window above all. Compose picks the theme up only once the process is alive, and until then the window
 * behind the first frame comes from the system's mode: a Light app on a dark phone opened on a black window and then
 * flashed white. The mode is persisted per package, so setting it is what makes the next launch open on the right one.
 *
 * API 31 and up. Below that there is no per-application night mode and the launch window follows the system.
 */
object AppNightMode {

    fun apply(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        manager.setApplicationNightMode(
            when (mode) {
                ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
                ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
                // Clears the override rather than scheduling anything: the service maps everything that is neither
                // YES nor NO to UI_MODE_NIGHT_UNDEFINED, which drops the package's entry and follows the system again.
                ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            },
        )
    }
}
