package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Settings › Advanced on a signed-in install — the one place the Extended mode section is shown; the demo walkthrough
 * in [AppScreenshotTest] never has it. Two frames: the mode off, where the card is the toggle alone and no option
 * that needs an undocumented endpoint is anywhere on the screen, and the mode on, where the note and those options
 * (the pin sync) sit under the toggle. Same device qualifiers as [AppScreenshotTest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SettingsExtendedScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        // The acknowledgment row shows a date; the clock, zone and locale are pinned so it is the same date everywhere.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) })
        // Turning the mode on would reach for the account (its picture, the pins' first round); there is none here,
        // and a refused round would put its refusal under the switch. The frame is about the section, not the sync.
        graph.extendedMode.onEnabled = {}
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun composeSettings() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SettingsScreen(graph = graph, user = USER, isDemo = false, onOpenSidebar = null, onBack = {})
                }
            }
        }
    }

    /** The bottom of the screen: the Advanced group, the notice under it and the sign-out button. */
    private fun scrollToAdvanced() {
        compose.onNodeWithText("Sign out").performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun extendedModeOff() {
        composeSettings()
        scrollToAdvanced()
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()
        compose.onAllNodes(hasTestTag(ExtendedModeTags.PIN_SYNC)).fetchSemanticsNodes().also { check(it.isEmpty()) { "The pin sync option must not be composed while the mode is off." } }
        capture("66_settings_extended_off")
    }

    @Test
    fun extendedModeOn() {
        runBlocking {
            check(graph.extendedMode.acknowledge()) { "The acknowledgment could not be written." }
            check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
        }
        composeSettings()
        // The first composition in a cold sandbox loads the native renderer and the fonts; the walkthrough's waits
        // allow the same, so a slow runner does not read as the option never appearing.
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(ExtendedModeTags.PIN_SYNC)).fetchSemanticsNodes().isNotEmpty() }
        // The About group's API row collects the setting on its own (a second collector of the same flow), and can
        // still read "off" a frame after the pin sync option has appeared; the frame is captured once both have caught up.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Cloud Agents v1 · v0 transcript · Cursor account service").fetchSemanticsNodes().isNotEmpty() }
        scrollToAdvanced()
        compose.onNodeWithText(ExtendedModeCopy.OPTIONS_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.PIN_SYNC_TITLE).assertIsDisplayed()
        capture("67_settings_extended_on")
    }

    private companion object {
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
