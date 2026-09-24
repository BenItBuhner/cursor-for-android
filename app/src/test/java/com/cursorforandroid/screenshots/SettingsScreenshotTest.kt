package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.google.common.truth.Truth.assertThat
import com.cursorforandroid.domain.TranscriptEngine
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
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
import com.cursorforandroid.ui.settings.SendDiagnosticsCopy
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
 * or the Advanced section. The whole list on one tall canvas, dark and light, so its structure reads in one image;
 * its scroll edges on a phone-height frame at the top, the middle and the end, dark and light; the bottom of the page with Extended mode off (the transcript engine beside it dimmed, with the reason) and on
 * (the engine live and on, Beta being the default), and with it switched off; the account sheet, dark and light; and the debug sheet a long
 * press on the version row opens, with the exports, About and the credits that left the list, dark and light. Same
 * device qualifiers as [AppScreenshotTest], but for the tall frames.
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

    private fun composeSettings(mode: ThemeMode = ThemeMode.Dark) {
        compose.setContent {
            CursorTheme(mode = mode) {
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

    private fun openDebugSheet() {
        compose.onNodeWithTag(SettingsTags.VERSION_ROW).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.DEBUG_SHEET)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun openAccountSheet() {
        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(SettingsTags.ACCOUNT_SHEET)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * The bottom of the page: the Extended mode section, the version with its updater and the crash report consent, the
     * disclaimer — and the list's own bottom padding, so nothing is left past the edge to fade.
     */
    private fun scrollToBottom() {
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).performScrollTo()
        scrollListTo(1f)
    }

    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun listRange() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    /** Scrolls the page to [fraction] of the way down its content, by the list's own scroll action. */
    private fun scrollListTo(fraction: Float) {
        val range = listRange()
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() * fraction - range.value()) }
        compose.waitForIdle()
    }

    /** The whole list at once, on a canvas tall enough to hold it: the account first and the disclaimer last. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1880dp-night-420dpi")
    fun settingsEssentials() {
        essentials(ThemeMode.Dark, "65_settings_essentials")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h1880dp-notnight-420dpi")
    fun settingsEssentialsLight() {
        essentials(ThemeMode.Light, "160_settings_essentials_light")
    }

    private fun essentials(mode: ThemeMode, frame: String) {
        composeSettings(mode)
        compose.onNodeWithText(SettingsCopy.GROUP_ACCOUNT).assertIsDisplayed()
        compose.onNodeWithText(SettingsCopy.SIGN_OUT).assertIsDisplayed()
        compose.onNode(hasTestTag(ExtendedModeTags.TOGGLE) and isOff()).assertIsDisplayed()
        compose.onNode(hasTestTag(ExtendedModeTags.ENGINE_TOGGLE) and isNotEnabled()).assertIsDisplayed()
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).assertIsDisplayed()
        // Tall enough that nothing is left past either edge, so neither fades.
        assertThat(listRange().maxValue()).isEqualTo(0f)
        capture(frame)
    }

    /**
     * The page's edges on a phone-height frame, the way the transcript's read: at the top only the bottom edge
     * dissolves (there is more below), in the middle both do, and at the end only the top one, the last row sharp.
     */
    @Test
    fun scrollFadeTop() {
        scrollFade(ThemeMode.Dark, 0f, "199_settings_scroll_fade_top")
    }

    @Test
    fun scrollFadeMiddle() {
        scrollFade(ThemeMode.Dark, 0.5f, "200_settings_scroll_fade_middle")
    }

    @Test
    fun scrollFadeBottom() {
        scrollFade(ThemeMode.Dark, 1f, "201_settings_scroll_fade_bottom")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun scrollFadeTopLight() {
        scrollFade(ThemeMode.Light, 0f, "202_settings_scroll_fade_top_light")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun scrollFadeMiddleLight() {
        scrollFade(ThemeMode.Light, 0.5f, "203_settings_scroll_fade_middle_light")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun scrollFadeBottomLight() {
        scrollFade(ThemeMode.Light, 1f, "204_settings_scroll_fade_bottom_light")
    }

    private fun scrollFade(mode: ThemeMode, fraction: Float, frame: String) {
        composeSettings(mode)
        if (fraction > 0f) scrollListTo(fraction)
        val range = listRange()
        assertThat(range.maxValue()).isGreaterThan(0f)
        when (fraction) {
            0f -> assertThat(range.value()).isEqualTo(0f)
            1f -> assertThat(range.value()).isEqualTo(range.maxValue())
            else -> assertThat(range.value()).apply { isGreaterThan(0f); isLessThan(range.maxValue()) }
        }
        capture(frame)
    }

    @Test
    fun extendedModeOff() {
        composeSettings()
        scrollToBottom()
        compose.onNode(hasTestTag(ExtendedModeTags.TOGGLE) and isOff()).assertIsDisplayed()
        // The engine beside the switch, dimmed and off, saying why it does nothing yet: default mode's row as it always was.
        compose.onNodeWithText(ExtendedModeCopy.NEEDS_MODE).assertIsDisplayed()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_TOGGLE).assertIsNotEnabled().assertIsOff()
        capture("66_settings_extended_off")
    }

    @Test
    fun extendedModeOn() {
        turnExtendedModeOn()
        composeSettings()
        compose.waitUntil(30_000) { modeReadsOn() }
        scrollToBottom()
        // The transcript engine beside the switch, on — Beta, the default for every install that never chose.
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasTestTag(ExtendedModeTags.ENGINE_TOGGLE)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_TOGGLE).assertIsEnabled()
        assertThat(runBlocking { graph.extendedMode.engine() }).isEqualTo(TranscriptEngine.BETA)
        capture("67_settings_extended_on")
    }

    /** Switched off, the way back to the documented path: the flip writes Stable and the switch reads off. */
    @Test
    fun transcriptEngineSwitchedOff() {
        turnExtendedModeOn()
        composeSettings()
        compose.waitUntil(30_000) { modeReadsOn() }
        scrollToBottom()
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasTestTag(ExtendedModeTags.ENGINE_TOGGLE)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isOff() and hasTestTag(ExtendedModeTags.ENGINE_TOGGLE)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { graph.extendedMode.engine() }).isEqualTo(TranscriptEngine.STABLE)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isFalse()
        capture("477_settings_transcript_engine_off")
    }

    /** With the mode off the engine chooses nothing: its switch is shown dimmed and off, and a tap on it changes nothing. */
    @Test
    fun transcriptEngineInertWithModeOff() {
        composeSettings()
        scrollToBottom()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_TOGGLE).assertIsNotEnabled().assertIsOff()
        assertThat(runBlocking { graph.extendedMode.engine() }).isEqualTo(TranscriptEngine.BETA)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isFalse()
    }

    /** The account row's sheet: how the key is kept and the pages where it and the agents are managed. */
    @Test
    fun accountSheet() {
        composeSettings()
        openAccountSheet()
        compose.onNodeWithText("Manage API keys").assertIsDisplayed()
        capture("161_settings_account_sheet")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun accountSheetLight() {
        composeSettings(ThemeMode.Light)
        openAccountSheet()
        compose.onNodeWithText("Manage API keys").assertIsDisplayed()
        capture("162_settings_account_sheet_light")
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
        openDebugSheet()
        compose.onNodeWithText("Cloud Agents v1 · v0 transcript · Cursor account service").assertIsDisplayed()
        compose.onNodeWithText(SettingsDebugCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText(ProjectDiagnosticsCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag(SendDiagnosticsCopy.TAG).assertIsEnabled()
        capture("68_settings_debug_sheet")
    }

    /** Light, with the mode off: sending to the Project is the one row that needs the mode, dimmed with the reason. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h914dp-notnight-420dpi")
    fun debugSheetLight() {
        composeSettings(ThemeMode.Light)
        scrollToBottom()
        openDebugSheet()
        compose.onNodeWithText("Cloud Agents v1 · v0 transcript").assertIsDisplayed()
        compose.onNode(hasTestTag(SendDiagnosticsCopy.TAG) and hasText(ExtendedModeCopy.NEEDS_MODE)).assertIsDisplayed().assertIsNotEnabled()
        capture("163_settings_debug_sheet_light")
    }

    private companion object {
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
