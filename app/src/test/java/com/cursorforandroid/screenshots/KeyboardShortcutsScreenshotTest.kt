package com.cursorforandroid.screenshots

import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.settings.KeyboardShortcutsScreen
import com.cursorforandroid.ui.settings.KeyboardShortcutsTags
import com.cursorforandroid.ui.shortcuts.CommandPalette
import com.cursorforandroid.ui.shortcuts.ConflictResolution
import com.cursorforandroid.ui.shortcuts.KeyChord
import com.cursorforandroid.ui.shortcuts.KeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.PaletteState
import com.cursorforandroid.ui.shortcuts.Shortcut
import com.cursorforandroid.ui.shortcuts.ShortcutBindings
import com.cursorforandroid.ui.shortcuts.ShortcutsCopy
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings › Keyboard shortcuts, each changeable shortcut showing its keys in an outlined field: the page as it comes,
 * a field waiting for its new keys (dark and light), keys another shortcut is on (swap or replace), keys Android keeps,
 * and moved shortcuts with their resets and an empty "Not set" field, in light; the Composer and Media groups; and the
 * Ctrl+/ menu with the same groups, on a phone and a tablet.
 * The keys reach the page as a hardware keyboard's do, through the shell's reader. Written to `screenshots/`; CI
 * compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class KeyboardShortcutsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private lateinit var keys: KeyboardShortcuts

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(initial: ShortcutBindings = ShortcutBindings.Defaults, mode: ThemeMode = ThemeMode.Dark) {
        keys = KeyboardShortcuts(compose.activity.lifecycleScope)
        compose.setContent {
            var bindings by remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalKeyboardShortcuts provides keys, LocalRippleConfiguration provides null) {
                CursorTheme(mode = mode) {
                    KeyboardShortcutsScreen(bindings = bindings, onChange = { bindings = it }, onBack = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private var eventTime = 0L

    /** Ctrl held, [code] down and up, as a hardware keyboard sends them. */
    private fun chord(code: Int, shift: Boolean = false) {
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        compose.runOnUiThread {
            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                val at = ++eventTime
                keys.onKeyEvent(KeyEvent(at, at, action, code, 0, meta, 7, 0, 0, InputDevice.SOURCE_KEYBOARD))
            }
        }
        compose.waitForIdle()
    }

    private fun tap(shortcut: Shortcut) {
        compose.onNodeWithTag(KeyboardShortcutsTags.row(shortcut)).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun keyboardShortcutsSection() {
        show()
        capture("700_keyboard_shortcuts_section")
    }

    @Test
    fun keyboardShortcutsCapture() {
        show()
        tap(Shortcut.ToggleSidebar)
        capture("701_keyboard_shortcuts_capture")
    }

    @Test
    fun keyboardShortcutsCaptureLight() {
        show(mode = ThemeMode.Light)
        tap(Shortcut.NewChat)
        capture("705_keyboard_shortcuts_capture_light")
    }

    @Test
    fun keyboardShortcutsConflict() {
        show()
        tap(Shortcut.CatchUp)
        chord(KeyEvent.KEYCODE_B)
        capture("702_keyboard_shortcuts_conflict")
    }

    @Test
    fun keyboardShortcutsBlocked() {
        show()
        tap(Shortcut.NewChat)
        chord(KeyEvent.KEYCODE_C)
        capture("703_keyboard_shortcuts_blocked")
    }

    @Test
    fun keyboardShortcutsCustomizedLight() {
        val moved = ShortcutBindings.Defaults
            .assign(Shortcut.ToggleSidebar, KeyChord.ctrl(KeyEvent.KEYCODE_J))
            .assign(Shortcut.TogglePanel, KeyChord(KeyEvent.KEYCODE_P, alt = true))
            .assign(Shortcut.ExpandComposer, KeyChord.ctrl(KeyEvent.KEYCODE_R), ConflictResolution.Replace)
        show(moved, ThemeMode.Light)
        compose.onNodeWithTag(KeyboardShortcutsTags.row(Shortcut.ExpandComposer)).performScrollTo()
        capture("704_keyboard_shortcuts_customized_light")
    }

    @Test
    fun keyboardShortcutsComposerGroup() {
        show()
        compose.onNodeWithTag(KeyboardShortcutsTags.group(ShortcutsCopy.COMPOSER)).performScrollTo()
        capture("991_keyboard_shortcuts_composer_media")
    }

    @Test
    fun keyboardShortcutsMediaGroupLight() {
        show(mode = ThemeMode.Light)
        compose.onNodeWithTag(KeyboardShortcutsTags.group(ShortcutsCopy.MEDIA)).performScrollTo()
        compose.onNodeWithText("Close the viewer").performScrollTo()
        capture("992_keyboard_shortcuts_media_light")
    }

    /** The Ctrl+/ menu over a plain page, as the shell draws it: General first, then Composer and Media. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun menu(mode: ThemeMode = ThemeMode.Dark) {
        val palette = PaletteState().apply { openShortcuts() }
        compose.setContent {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                CursorTheme(mode = mode) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        CommandPalette(palette, emptyList(), emptyList(), emptyMap(), readingTranscripts = false, glyph = {}, onOpen = { _, _ -> }, onDismiss = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun shortcutsMenu() {
        menu()
        capture("993_shortcuts_menu")
    }

    @Test
    fun shortcutsMenuMedia() {
        menu()
        compose.onNodeWithText("Close the viewer").performScrollTo()
        capture("994_shortcuts_menu_media")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun shortcutsMenuTabletLight() {
        menu(ThemeMode.Light)
        compose.onNodeWithText("Close the viewer").performScrollTo()
        capture("995_shortcuts_menu_tablet_light")
    }
}
