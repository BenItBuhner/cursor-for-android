package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import com.google.common.truth.Truth.assertThat
import com.cursorforandroid.domain.TranscriptEngine
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
import com.cursorforandroid.ui.settings.ProjectDiagnosticsCopy
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsDebugCopy
import com.cursorforandroid.ui.settings.SettingsScreen
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Settings on a signed-in install — the demo walkthrough in [AppScreenshotTest] never shows the account's own rows
 * or the Extended mode section. Four frames: the whole list on one tall canvas, so its structure reads in one
 * image; the bottom of the page with the mode off and with it on (the section is the one switch either way); and
 * the debug sheet a long press on the version row opens, with the exports, About and the credits that left the
 * list. Same device qualifiers as [AppScreenshotTest], but for the tall frame.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SettingsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        // The updater's status line and the diagnostics carry dates; the clock, zone and locale are pinned so they are the same everywhere.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        // Turning the mode on would reach for the account (its picture, the pins' first round); there is none here.
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
        // The first composition in a cold sandbox loads the native renderer and the fonts; a slow runner must not
        // read as the list never appearing.
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.VERSION_ROW)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Turns the mode on and waits until the flow the screen collects says so, so the frame's collectors start from an on state. */
    private fun turnExtendedModeOn() {
        runBlocking {
            check(graph.extendedMode.acknowledge()) { "The acknowledgment could not be written." }
            check(graph.extendedMode.enable()) { "Extended mode could not be turned on." }
            withTimeout(10_000) { graph.extendedMode.enabled.first { it } }
        }
    }

    private fun modeReadsOn() = compose.onAllNodes(isOn() and hasTestTag(ExtendedModeTags.TOGGLE)).fetchSemanticsNodes().isNotEmpty()

    /** The bottom of the page: the Extended mode section, the version with its updater and the crash report consent, the disclaimer. */
    private fun scrollToBottom() {
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).performScrollTo()
        compose.waitForIdle()
    }

    /** The whole list at once, on a canvas tall enough to hold it: the account first and the disclaimer last. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1360dp-night-420dpi")
    fun settingsEssentials() {
        composeSettings()
        compose.onNodeWithText(SettingsCopy.GROUP_ACCOUNT).assertIsDisplayed()
        compose.onNodeWithText(SettingsCopy.SIGN_OUT).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).assertIsDisplayed()
        capture("65_settings_essentials")
    }

    @Test
    fun extendedModeOff() {
        composeSettings()
        scrollToBottom()
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()
        capture("66_settings_extended_off")
    }

    @Test
    fun extendedModeOn() {
        turnExtendedModeOn()
        composeSettings()
        compose.waitUntil(30_000) { modeReadsOn() }
        scrollToBottom()
        compose.onNodeWithText(ExtendedModeCopy.SETTING_ON).assertIsDisplayed()
        // The transcript engine under the switch, Stable chosen — the default, for every install.
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_STABLE).assertIsSelected()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_BETA).assertIsNotSelected()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_STABLE_DETAIL).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_BETA_DETAIL).assertIsDisplayed()
        capture("67_settings_extended_on")
    }

    /** Beta chosen: the tap writes the setting, the check moves, and the footnote says when it takes effect. */
    @Test
    fun transcriptEngineBeta() {
        turnExtendedModeOn()
        composeSettings()
        compose.waitUntil(30_000) { modeReadsOn() }
        scrollToBottom()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_BETA).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isSelected() and hasTestTag(ExtendedModeTags.ENGINE_BETA)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_STABLE).assertIsNotSelected()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_FOOTNOTE).assertIsDisplayed()
        assertThat(runBlocking { graph.extendedMode.engine() }).isEqualTo(TranscriptEngine.BETA)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isTrue()
        capture("123_settings_transcript_engine_beta")
    }

    /** With the mode off the engine chooses nothing, and the rows are not shown. */
    @Test
    fun transcriptEngineHiddenWithModeOff() {
        composeSettings()
        scrollToBottom()
        compose.onAllNodesWithText(ExtendedModeCopy.ENGINE_TITLE).assertCountEquals(0)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isFalse()
    }

    /**
     * A long press on the version row: the exports, the About rows (the API row names the account service while the
     * mode is on) and the credits. The sheet is opened through the row's long-press action, as TalkBack would, rather
     * than a touch held on the row: no press is in flight — and nothing of it mid-fade — while the sheet comes up;
     * the touch path is SettingsScreenTest's. Everything the sheet shows it takes from the screen (the mode, already
     * read on the switch above) or reads at once (the updater's state), so once the sheet exists and the harness is
     * idle — its enter animation played out — the frame is settled: what follows asserts, and does not wait.
     */
    @Test
    fun debugSheet() {
        turnExtendedModeOn()
        composeSettings()
        compose.waitUntil(30_000) { modeReadsOn() }
        scrollToBottom()
        compose.onNodeWithTag(SettingsTags.VERSION_ROW).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.DEBUG_SHEET)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Cloud Agents v1 · v0 transcript · Cursor account service").assertIsDisplayed()
        compose.onNodeWithText(SettingsDebugCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText(ProjectDiagnosticsCopy.TITLE).assertIsDisplayed()
        capture("68_settings_debug_sheet")
    }

    private companion object {
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
