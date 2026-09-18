package com.cursorforandroid.ui.settings

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
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

        // Account, with the way out of it, first; then appearance, notifications, the Extended mode switch, the
        // version with its updater and the crash report consent; the one-line disclaimer last.
        val order = listOf(
            SettingsCopy.GROUP_ACCOUNT, SettingsCopy.SIGN_OUT, SettingsCopy.GROUP_APPEARANCE, SettingsCopy.GROUP_NOTIFICATIONS,
            ExtendedModeCopy.SETTING_TITLE, SettingsCopy.GROUP_UPDATES, "Version ${BuildConfig.VERSION_NAME}", CrashReportCopy.TITLE, SettingsCopy.DISCLAIMER,
        )
        val tops = order.map(::top)
        assertThat(tops).isInOrder()

        // The essentials themselves.
        listOf("Match system", "Cursor Dark", "Cursor Light", "OLED black", "Live notifications", "Check for updates", "Automatic updates", "Include pre-releases")
            .forEach { compose.onNodeWithText(it).assertExists() }
        listOf("notif-count-project-agents", "notif-project-coordinators", "notif-project-members", ExtendedModeTags.TOGGLE, SettingsTags.VERSION_ROW, SettingsTags.SIGN_OUT)
            .forEach { compose.onNodeWithTag(it).assertExists() }

        // The key details wait behind the account row; the acknowledgment, the pin sync and its status are gone
        // with the mode following the switch alone; the exports, About and the credits wait behind the version row.
        assertAbsent(
            "Signed in with", "Key expires", "Key storage", "Manage this app's key", "Manage API keys", "Open cursor.com/agents",
            "Warning acknowledged", "Sync pinned chats", "Last synced",
            ProjectDiagnosticsCopy.TITLE, TranscriptDiagnosticsCopy.TITLE, SendDiagnosticsCopy.TITLE, SettingsDebugCopy.API_DOCS, SettingsDebugCopy.SOURCE,
            SettingsDebugCopy.GROUP_ABOUT, SettingsDebugCopy.GROUP_DIAGNOSTICS, SettingsDebugCopy.GROUP_CREDITS, SettingsCopy.CREDITS, "Advanced", "Privacy", "Updates",
        )
        // No explanatory paragraph but the disclaimer: nothing on the page mentions a license.
        compose.onAllNodes(hasText("License", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("Anysphere", substring = true)).assertCountEquals(1)
        assertThat(debugSheetShown()).isFalse()
    }

    @Test
    fun `the demo leaves rather than signs out, and has no Extended mode to switch`() {
        composeSettings(isDemo = true)

        compose.onNodeWithText(SettingsCopy.LEAVE_DEMO).assertExists()
        assertAbsent(SettingsCopy.SIGN_OUT, ExtendedModeCopy.SETTING_TITLE)
        compose.onAllNodes(hasTestTag(ExtendedModeTags.TOGGLE)).assertCountEquals(0)
        // The demo has no key to describe, so its account row is not a control.
        compose.onNodeWithTag(SettingsTags.ACCOUNT_ROW).assertHasNoClickAction()
        assertThat(top(SettingsCopy.GROUP_ACCOUNT)).isLessThan(top(SettingsCopy.GROUP_APPEARANCE))
        assertThat(top(SettingsCopy.GROUP_NOTIFICATIONS)).isLessThan(top(SettingsCopy.GROUP_UPDATES))
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
        val version = compose.onNodeWithTag(SettingsTags.VERSION_ROW)

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
