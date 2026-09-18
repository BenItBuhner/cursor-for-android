package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.SidebarTags
import com.cursorforandroid.ui.settings.SettingsCopy
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.settings.WhatsNewCopy
import com.cursorforandroid.ui.settings.WhatsNewTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The What's new surfaces through the shell, on a window wide enough for the sidebar to stand beside the pane: the
 * card in the sidebar's slot and the row in Settings show the installed version's notes until the page has been
 * opened, from either of them; both are gone for that version afterwards; and both are back when the next version
 * is installed — a fresh graph over the same device preferences, which is what the next build's process is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h720dp-night-420dpi")
class WhatsNewFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = org.junit.rules.TemporaryFolder()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { PreferencesStore(context) }
    private var graph by mutableStateOf<AppGraph?>(null)

    /** A process of the build [version] is running, with that version's notes on its disk (or none). */
    private fun install(version: String, withNotes: Boolean = true): AppGraph {
        val notes = WhatsNewFixtures.repository(prefs, folder.newFolder(), versionName = version, notes = if (withNotes) WhatsNewFixtures.notes(version) else null)
        val installed = AppGraph(context, releaseNotes = notes)
        runBlocking { installed.session.enterDemo() }
        return installed
    }

    private fun showShell(first: AppGraph) {
        graph = first
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                // Keyed on the graph: swapping it is the next build's process starting over the same preferences.
                graph?.let { current ->
                    key(current) {
                        AppShell(
                            graph = current,
                            user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                            isDemo = true,
                            wide = true,
                            deepLinkAgentId = null,
                            onDeepLinkConsumed = {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun readVersion(): String? = runBlocking { prefs.whatsNewReadVersion.first() }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Account").performClick()
        compose.waitUntil(10_000) { onScreen(SettingsCopy.GROUP_UPDATES) }
    }

    @Test
    fun `both surfaces show the installed version's notes until the page is opened, then again for the next version`() {
        showShell(install("0.3.37"))

        // The card, in the slot the update hint uses.
        compose.waitUntil(10_000) { shown(SidebarTags.WHATS_NEW_HINT) }
        compose.onNodeWithText(WhatsNewCopy.title("0.3.37")).assertIsDisplayed()
        assertThat(readVersion()).isNull()

        // Tapping it opens the page; opening the page reads the notes, so the card goes while the page is up.
        compose.onNodeWithTag(SidebarTags.WHATS_NEW_HINT).performClick()
        compose.waitUntil(10_000) { shown(WhatsNewTags.PAGE) }
        compose.waitUntil(10_000) { readVersion() == "0.3.37" }
        compose.waitUntil(10_000) { !shown(SidebarTags.WHATS_NEW_HINT) }
        compose.waitForIdle()
        compose.onNodeWithText(WhatsNewCopy.title("0.3.37")).assertIsDisplayed()
        compose.onNodeWithText("Goals").assertIsDisplayed()

        // Done leaves the page for where it was opened from: home.
        compose.onNodeWithTag(WhatsNewTags.DONE).performClick()
        compose.waitUntil(10_000) { !shown(WhatsNewTags.PAGE) }
        assertThat(onScreen(HOME_PLACEHOLDER)).isTrue()

        // Settings has no row to offer for a version that has been read.
        openSettings()
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).performScrollTo()
        compose.waitForIdle()
        assertThat(shown(SettingsTags.WHATS_NEW_ROW)).isFalse()
        compose.onNodeWithText("Check for updates").assertExists()

        // The next release installs: its notes are unread, on both surfaces, though 0.3.37's were read.
        compose.runOnIdle { graph = install("0.3.38") }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(10_000) { shown(SidebarTags.WHATS_NEW_HINT) }
        compose.onNodeWithText(WhatsNewCopy.title("0.3.38")).assertIsDisplayed()
        assertThat(readVersion()).isEqualTo("0.3.37")

        // This time from Settings: the row sits beneath the version, and opens the same page.
        openSettings()
        compose.waitUntil(10_000) { shown(SettingsTags.WHATS_NEW_ROW) }
        compose.onNodeWithTag(SettingsTags.WHATS_NEW_ROW).performScrollTo().performClick()
        compose.waitUntil(10_000) { shown(WhatsNewTags.PAGE) }
        compose.waitUntil(10_000) { readVersion() == "0.3.38" }
        compose.waitUntil(10_000) { !shown(SidebarTags.WHATS_NEW_HINT) && !shown(SettingsTags.WHATS_NEW_ROW) }
        compose.waitForIdle()
        compose.onNodeWithText(WhatsNewCopy.title("0.3.38")).assertIsDisplayed()

        // Back (the header's chevron) returns to Settings, where the row is gone.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(10_000) { !shown(WhatsNewTags.PAGE) }
        compose.waitUntil(10_000) { onScreen(SettingsCopy.GROUP_UPDATES) }
        assertThat(shown(SettingsTags.WHATS_NEW_ROW)).isFalse()
    }

    @Test
    fun `a version with no notes on the device shows neither surface`() {
        showShell(install("0.3.37", withNotes = false))

        compose.waitForIdle()
        assertThat(shown(SidebarTags.WHATS_NEW_HINT)).isFalse()
        openSettings()
        compose.onNodeWithText(SettingsCopy.DISCLAIMER).performScrollTo()
        compose.waitForIdle()
        assertThat(shown(SettingsTags.WHATS_NEW_ROW)).isFalse()
        assertThat(readVersion()).isNull()
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    }
}
