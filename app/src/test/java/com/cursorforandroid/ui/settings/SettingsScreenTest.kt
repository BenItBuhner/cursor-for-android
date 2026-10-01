package com.cursorforandroid.ui.settings

import android.app.Application
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isSelected
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
import com.cursorforandroid.ui.shortcuts.ShortcutsCopy
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
 * The settings list is the essentials, in a fixed order, and nothing else; what was kept of what was cut is behind a
 * long press on the version row (the diagnostics, About, the credits). The
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
    private lateinit var view: View

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun composeSettings(isDemo: Boolean) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                view = LocalView.current
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

        // Account, with the way out of it at the row's end, first; then appearance (the theme switch and OLED black
        // alone), what the New Chat pane lists, the chats (which may show as unread, how long the sidebar's Projects
        // list runs, the keyboard shortcuts), notifications, Extended mode, the version with its updater; the
        // one-line disclaimer last.
        val order = listOf(
            SettingsCopy.GROUP_ACCOUNT, SettingsCopy.SIGN_OUT, SettingsCopy.GROUP_APPEARANCE, ThemeSwitchCopy.TITLE, SettingsCopy.OLED_BLACK,
            NewChatHomePickerCopy.GROUP, NewChatHomePickerCopy.NEEDS_MODE, SettingsCopy.GROUP_CHATS,
            SettingsCopy.UNREAD_THIS_PHONE, SettingsCopy.SHORTEN_PROJECTS, ShortcutsCopy.TITLE, SettingsCopy.GROUP_NOTIFICATIONS,
            SettingsCopy.GROUP_ADVANCED, ExtendedModeCopy.SETTING_TITLE,
            SettingsCopy.GROUP_UPDATES, "Version ${BuildConfig.VERSION_NAME}", "Check for updates automatically", SettingsCopy.DISCLAIMER,
        )
        val tops = order.map(::top)
        assertThat(tops).isInOrder()

        // The essentials themselves.
        listOf("Auto", "Light", "Dark", "OLED black", "Live notifications", "Check for updates", "Check for updates automatically")
            .forEach { compose.onNodeWithText(it).assertExists() }
        listOf("notif-count-project-agents", "notif-project-coordinators", "notif-project-members", ExtendedModeTags.TOGGLE, SettingsTags.VERSION_ROW, SettingsTags.SIGN_OUT, ThemeSwitchTags.SWITCH)
            .forEach { compose.onNodeWithTag(it).assertExists() }
        // Appearance is the theme and OLED black, nothing more: the sidebar's list length is a chats setting.
        assertThat(top(SettingsCopy.SHORTEN_PROJECTS)).isGreaterThan(top(SettingsCopy.GROUP_CHATS))

        // The account page is gone, and with it the sign-in method, the key's expiry, its management and the link
        // out; the acknowledgment, the pin sync and its status are gone with the mode following the switch alone;
        // the exports, About and the credits wait behind the version row. What is always on now has no switch:
        // stopping always asks, chats are always kept live, the full transcript is always read. Crash reports are
        // not offered. No label heads a single row with that row's own name. Updates are stable releases only, and
        // haptics follow the system's switch alone. The keyboard shortcuts row says nothing its title does not.
        assertAbsent(
            "Include pre-releases", "Haptic feedback", "Feedback",
            "Signed in with", "Key expires", "Key storage", "Manage this app's key", "Manage API keys", "Open cursor.com/agents",
            "Warning acknowledged", "Sync pinned chats", "Last synced",
            ProjectDiagnosticsCopy.TITLE, TranscriptDiagnosticsCopy.TITLE, SendDiagnosticsCopy.TITLE, SettingsDebugCopy.API_DOCS, SettingsDebugCopy.SOURCE,
            SettingsDebugCopy.GROUP_ABOUT, SettingsDebugCopy.GROUP_DIAGNOSTICS, SettingsDebugCopy.GROUP_CREDITS, SettingsCopy.CREDITS, "Privacy", "Updates",
            "Transcript engine", "Stable", "Beta", "Takes effect the next time a chat is opened.",
            "Confirm before stopping", "Keep chats live", "Full transcript history", "Send crash reports", "Not available in this build.",
            "Match system", "Cursor Dark", "Cursor Light",
        )
        compose.onAllNodes(hasText("hardware keyboard", substring = true)).assertCountEquals(0)
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
        assertThat(top(title)).isLessThan(top("Check for updates automatically"))

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
        assertAbsent(SettingsCopy.SIGN_OUT, SettingsCopy.GROUP_ADVANCED, ExtendedModeCopy.SETTING_TITLE, SettingsCopy.UNREAD_THIS_PHONE)
        compose.onNodeWithText(SettingsCopy.SHORTEN_PROJECTS).assertExists()
        compose.onAllNodes(hasTestTag(ExtendedModeTags.TOGGLE)).assertCountEquals(0)
        // The way out is the button at the row's end; the row itself is not a control.
        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).assertHasNoClickAction()
        compose.onNodeWithTag(SettingsTags.SIGN_OUT).assertHasClickAction()
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

        compose.onNodeWithText(SettingsCopy.UNREAD_THIS_PHONE).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !graph.prefs.unreadOnlyTouchedHere.first() } }
        compose.waitUntil(10_000) { compose.onAllNodes(toggle and isOff()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(toggle).assertIsOff()
        assertThat(runBlocking { graph.prefs.localAgentState.first() }.let { it.readMarkers.isEmpty() && it.touchedHereIds.isEmpty() }).isTrue()

        compose.onNodeWithText(SettingsCopy.UNREAD_THIS_PHONE).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.unreadOnlyTouchedHere.first() } }
        compose.waitUntil(10_000) { compose.onAllNodes(toggle and isOn()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(toggle).assertIsOn()
    }

    /** On by default, in the demo too; a device setting the sidebar reads, which a sign-out leaves as the user set it. */
    @Test
    fun `the shorten Projects switch is on by default and the row turns it off and on`() {
        composeSettings(isDemo = true)
        val toggle = isToggleable() and hasAnyAncestor(hasTestTag(SettingsTags.SHORTEN_PROJECTS))
        compose.onNodeWithText(SettingsCopy.SHORTEN_PROJECTS_DETAIL).assertExists()
        compose.onNode(toggle).assertIsOn()
        assertThat(top(SettingsCopy.GROUP_CHATS)).isLessThan(top(SettingsCopy.SHORTEN_PROJECTS))
        assertThat(top(SettingsCopy.SHORTEN_PROJECTS)).isLessThan(top(SettingsCopy.GROUP_NOTIFICATIONS))

        compose.onNodeWithTag(SettingsTags.SHORTEN_PROJECTS).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { !graph.prefs.shortenSidebarLists.first() } }
        compose.waitUntil(10_000) { compose.onAllNodes(toggle and isOff()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(toggle).assertIsOff()
        runBlocking { graph.prefs.clearSession() }
        assertThat(runBlocking { graph.prefs.shortenSidebarLists.first() }).isFalse()

        compose.onNodeWithTag(SettingsTags.SHORTEN_PROJECTS).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.shortenSidebarLists.first() } }
        compose.waitUntil(10_000) { compose.onAllNodes(toggle and isOn()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(toggle).assertIsOn()
    }

    @Test
    fun `a switch is felt each way it flips, with no app setting in front of the system's`() {
        composeSettings(isDemo = false)
        val off = isOff() and hasAnyAncestor(hasTestTag(SettingsTags.SHORTEN_PROJECTS))
        val on = isOn() and hasAnyAncestor(hasTestTag(SettingsTags.SHORTEN_PROJECTS))

        compose.onNodeWithTag(SettingsTags.SHORTEN_PROJECTS).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(off).fetchSemanticsNodes().isNotEmpty() }
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.TOGGLE_OFF)

        compose.onNodeWithTag(SettingsTags.SHORTEN_PROJECTS).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(on).fetchSemanticsNodes().isNotEmpty() }
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.TOGGLE_ON)
    }

    @Test
    fun `the account row opens nothing, and Sign out is the button at its end`() {
        composeSettings(isDemo = false)

        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).assertHasNoClickAction()
        compose.onNodeWithText(USER.email!!).assertExists()
        compose.onNodeWithTag(SettingsTags.SIGN_OUT).assertHasClickAction()
        compose.onAllNodes(hasText(SettingsCopy.SIGN_OUT)).assertCountEquals(1)
        // On the account row itself, not on a row of its own beneath it.
        compose.onNode(hasTestTag(SettingsTags.SIGN_OUT) and hasAnyAncestor(hasTestTag(SettingsTags.ACCOUNT_ROW))).assertExists()
        assertThat(top(SettingsCopy.SIGN_OUT)).isLessThan(top(SettingsCopy.GROUP_APPEARANCE))
    }

    @Test
    fun `the theme switch reads Auto first, and a tap on a segment writes that theme and is felt`() {
        composeSettings(isDemo = false)
        val auto = compose.onNodeWithTag(ThemeSwitchTags.of(ThemeMode.System))
        val light = compose.onNodeWithTag(ThemeSwitchTags.of(ThemeMode.Light))
        val dark = compose.onNodeWithTag(ThemeSwitchTags.of(ThemeMode.Dark))
        auto.assertIsSelected()
        light.assertIsNotSelected()
        dark.assertIsNotSelected()
        assertThat(compose.onNodeWithText("Auto").fetchSemanticsNode().positionInRoot.x).isLessThan(compose.onNodeWithText("Light").fetchSemanticsNode().positionInRoot.x)
        assertThat(compose.onNodeWithText("Light").fetchSemanticsNode().positionInRoot.x).isLessThan(compose.onNodeWithText("Dark").fetchSemanticsNode().positionInRoot.x)

        dark.performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Dark }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(ThemeSwitchTags.of(ThemeMode.Dark)) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        auto.assertIsNotSelected()
        assertThat(shadowOf(view).lastHapticFeedbackPerformed()).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)

        light.performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.Light }
        // OLED black only does anything while dark is on, so Light dims it rather than hiding it.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.OLED_BLACK) and isNotEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(SettingsCopy.OLED_BLACK).assertExists()

        auto.performClick()
        compose.waitUntil(10_000) { runBlocking { graph.prefs.themeMode.first() } == ThemeMode.System }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(SettingsTags.OLED_BLACK) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
