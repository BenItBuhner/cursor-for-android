package com.cursorforandroid.ui.settings

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.components.RunStopCopy
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings list is the essentials, in a fixed order, and nothing else; what was cut is either behind a tap on
 * the account row (the key) or behind a long press on the version row (the diagnostics, About, the credits). The
 * whole screen is a scrolling Column, not a lazy list, so existence is the question for every row, on screen or not.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun composeSettings(isDemo: Boolean) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SettingsScreen(graph, USER, isDemo = isDemo, onOpenSidebar = null, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    /** Where [text] first appears down the page (unclipped, so rows below the fold count); a group label always precedes the rows it heads. */
    private fun top(text: String): Float =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().also { check(it.isNotEmpty()) { "\"$text\" is not on the screen." } }.minOf { it.positionInRoot.y }

    private fun assertAbsent(vararg texts: String) {
        texts.forEach { compose.onAllNodes(hasText(it)).assertCountEquals(0) }
    }

    private fun debugSheetShown() = compose.onAllNodes(hasTestTag(SettingsTags.DEBUG_SHEET)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the list is the essentials in order, and none of what was cut`() {
        composeSettings(isDemo = false)

        // Account, with the way out of it, first; then appearance, the chats (whether stopping asks first, which chats
        // may show as unread), notifications, Extended mode and beside it the transcript engine, the version with its
        // updater and the crash report consent; the one-line disclaimer last.
        val order = listOf(
            SettingsCopy.GROUP_ACCOUNT, SettingsCopy.SIGN_OUT, SettingsCopy.GROUP_APPEARANCE, SettingsCopy.GROUP_CHATS, RunStopCopy.SETTING_TITLE,
            SettingsCopy.UNREAD_THIS_PHONE, SettingsCopy.GROUP_NOTIFICATIONS,
            SettingsCopy.GROUP_ADVANCED, ExtendedModeCopy.SETTING_TITLE, ExtendedModeCopy.ENGINE_TITLE,
            SettingsCopy.GROUP_UPDATES, "Version ${BuildConfig.VERSION_NAME}", CrashReportCopy.TITLE, SettingsCopy.DISCLAIMER,
        )
        val tops = order.map(::top)
        assertThat(tops).isInOrder()

        // The essentials themselves.
        listOf("Match system", "Cursor Dark", "Cursor Light", "OLED black", "Live notifications", "Check for updates", "Automatic updates", "Include pre-releases")
            .forEach { compose.onNodeWithText(it).assertExists() }
        listOf("notif-count-project-agents", "notif-project-coordinators", "notif-project-members", ExtendedModeTags.TOGGLE, ExtendedModeTags.ENGINE_TOGGLE, SettingsTags.VERSION_ROW, SettingsTags.SIGN_OUT)
            .forEach { compose.onNodeWithTag(it).assertExists() }

        // The key details wait behind the account row; the acknowledgment, the pin sync and its status are gone
        // with the mode following the switch alone; the exports, About and the credits wait behind the version row;
        // the engine's header, its two choices and their footnote are one switch now. No label heads a single row
        // with that row's own name.
        assertAbsent(
            "Signed in with", "Key expires", "Key storage", "Manage this app's key", "Manage API keys", "Open cursor.com/agents",
            "Warning acknowledged", "Sync pinned chats", "Last synced",
            ProjectDiagnosticsCopy.TITLE, TranscriptDiagnosticsCopy.TITLE, SendDiagnosticsCopy.TITLE, SettingsDebugCopy.API_DOCS, SettingsDebugCopy.SOURCE,
            SettingsDebugCopy.GROUP_ABOUT, SettingsDebugCopy.GROUP_DIAGNOSTICS, SettingsDebugCopy.GROUP_CREDITS, SettingsCopy.CREDITS, "Privacy", "Updates",
            "Transcript engine", "Stable", "Beta", "Takes effect the next time a chat is opened.",
        )
        compose.onAllNodes(hasText(ExtendedModeCopy.SETTING_TITLE)).assertCountEquals(1)
        // No explanatory paragraph but the disclaimer: nothing on the page mentions a license.
        compose.onAllNodes(hasText("License", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Anysphere", substring = true)).assertCountEquals(1)
        assertThat(debugSheetShown()).isFalse()
        // Without the installed version's notes there is no What's new row to offer.
        compose.onAllNodes(hasTestTag(SettingsTags.WHATS_NEW_ROW)).assertCountEquals(0)
    }

    @Test
    fun `the What's new row sits directly beneath the version row while the notes are unread, opens them, and goes once they are read`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val version = BuildConfig.VERSION_NAME
        val notes = WhatsNewFixtures.repository(PreferencesStore(context), folder.newFolder(), versionName = version, notes = WhatsNewFixtures.notes(version))
        graph = AppGraph(context, releaseNotes = notes)
        var opened = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                SettingsScreen(graph, USER, isDemo = false, onOpenSidebar = null, onBack = {}, onOpenWhatsNew = { opened++ })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.WHATS_NEW_ROW)).fetchSemanticsNodes().isNotEmpty() }

        val title = WhatsNewCopy.title(version)
        compose.onNodeWithText(title).assertExists()
        // The lead line is the row's detail.
        compose.onNodeWithText(WhatsNewFixtures.LEAD).assertExists()
        assertThat(top("Version $version")).isLessThan(top(title))
        assertThat(top(title)).isLessThan(top("Automatic updates"))

        compose.onNodeWithTag(SettingsTags.WHATS_NEW_ROW).assertHasClickAction().performScrollTo().performClick()
        assertThat(opened).isEqualTo(1)

        // Opening the page reads the notes (the page does that); here the read is what the row follows.
        runBlocking { notes.markRead() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.WHATS_NEW_ROW)).fetchSemanticsNodes().isEmpty() }
        assertAbsent(title)
        compose.onNodeWithText("Check for updates").assertExists()
    }

    @Test
    fun `the demo leaves rather than signs out, and has no Extended mode to switch`() {
        composeSettings(isDemo = true)

        compose.onNodeWithText(SettingsCopy.LEAVE_DEMO).assertExists()
        // Every chat in the demo is this phone's own: the unread switch would change nothing, so it is not offered.
        assertAbsent(SettingsCopy.SIGN_OUT, SettingsCopy.GROUP_ADVANCED, ExtendedModeCopy.SETTING_TITLE, ExtendedModeCopy.ENGINE_TITLE, SettingsCopy.UNREAD_THIS_PHONE)
        compose.onNodeWithText(RunStopCopy.SETTING_TITLE).assertExists()
        compose.onAllNodes(hasTestTag(ExtendedModeTags.TOGGLE)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(ExtendedModeTags.ENGINE_TOGGLE)).assertCountEquals(0)
        // The demo has no key to describe, so its account row is not a control.
        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).assertHasNoClickAction()
        assertThat(top(SettingsCopy.GROUP_ACCOUNT)).isLessThan(top(SettingsCopy.GROUP_APPEARANCE))
        assertThat(top(SettingsCopy.GROUP_NOTIFICATIONS)).isLessThan(top(SettingsCopy.GROUP_UPDATES))
    }

    /** On by default; the row is the switch, and what it writes is the device's setting and nothing else. */
    @Test
    fun `the unread switch is on by default and the row turns it off and on`() {
        composeSettings(isDemo = false)
        val toggle = isToggleable() and hasAnyAncestor(hasTestTag(SettingsTags.UNREAD_THIS_PHONE))
        compose.onNodeWithText(SettingsCopy.UNREAD_THIS_PHONE_DETAIL).assertExists()
        compose.onNode(toggle).assertIsOn()

        compose.onNodeWithText(SettingsCopy.UNREAD_THIS_PHONE).performClick()
        compose.waitUntil(10_000) { runBlocking { !graph.prefs.unreadOnlyTouchedHere.first() } }
        compose.waitForIdle()
        compose.onNode(toggle).assertIsOff()
        assertThat(runBlocking { graph.prefs.localAgentState.first() }.let { it.readMarkers.isEmpty() && it.touchedHereIds.isEmpty() }).isTrue()

        compose.onNodeWithText(SettingsCopy.UNREAD_THIS_PHONE).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.unreadOnlyTouchedHere.first() } }
        compose.waitForIdle()
        compose.onNode(toggle).assertIsOn()
    }

    private fun confirmStopSwitch() = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(SettingsTags.CONFIRM_STOP)))

    @Test
    fun `Confirm before stopping reads on for an install upgraded from a build without it, and a tap turns it off`() {
        // What an earlier build leaves behind: its own settings written, this one never.
        runBlocking {
            graph.prefs.setLiveNotifications(false)
            graph.prefs.setOledBlack(true)
        }
        composeSettings(isDemo = false)

        compose.onNodeWithText(RunStopCopy.SETTING_DETAIL).assertExists()
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasAnyAncestor(hasTestTag(SettingsTags.CONFIRM_STOP))).fetchSemanticsNodes().isNotEmpty() }
        confirmStopSwitch().assertIsOn()
        // The earlier build's own values were read from the same file.
        compose.onNode(isToggleable() and hasAnyAncestor(hasText("Live notifications"))).assertIsOff()

        compose.onNodeWithTag(SettingsTags.CONFIRM_STOP).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isOff() and hasAnyAncestor(hasTestTag(SettingsTags.CONFIRM_STOP))).fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { graph.prefs.confirmStop.first() }).isFalse()
        // A device preference: a sign-out leaves it as the user set it.
        runBlocking { graph.prefs.clearSession() }
        assertThat(runBlocking { graph.prefs.confirmStop.first() }).isFalse()
    }

    @Test
    fun `the account row opens onto the key`() {
        composeSettings(isDemo = false)

        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).assertHasClickAction().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.ACCOUNT_SHEET)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Manage API keys").assertExists()
        compose.onNodeWithText("Open cursor.com/agents").assertExists()
    }

    @Test
    fun `a long press on the version row opens the debug sheet where the exports still work, and a tap does not`() {
        composeSettings(isDemo = false)
        // Below the fold on a phone: a touch lands only on a row that is on screen.
        val version = compose.onNodeWithTag(SettingsTags.VERSION_ROW).performScrollTo()

        version.performClick()
        compose.waitForIdle()
        assertThat(debugSheetShown()).isFalse()

        version.performTouchInput { longClick() }
        compose.waitUntil(10_000) { debugSheetShown() }
        listOf(
            SettingsDebugCopy.TITLE, SettingsDebugCopy.GROUP_DIAGNOSTICS, ProjectDiagnosticsCopy.TITLE, TranscriptDiagnosticsCopy.TITLE, SendDiagnosticsCopy.TITLE,
            SettingsDebugCopy.GROUP_ABOUT, SettingsDebugCopy.API_DOCS, SettingsDebugCopy.SOURCE, SettingsDebugCopy.GROUP_CREDITS, SettingsCopy.CREDITS,
        ).forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Cloud Agents v1 · v0 transcript").assertExists()

        // The export is one tap to the share sheet, as it was on the list: the report goes out as text.
        compose.onNodeWithTag(ProjectDiagnosticsCopy.TAG).performClick()
        val application = ApplicationProvider.getApplicationContext<Application>()
        compose.waitUntil(10_000) { shadowOf(application).peekNextStartedActivity() != null }
        val chooser = shadowOf(application).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).startsWith("Cursor for Android ")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).contains("Project diagnostics")
    }

    private companion object {
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
