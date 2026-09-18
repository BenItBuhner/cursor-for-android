package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.data.update.WhatsNewFixtures
import com.cursorforandroid.domain.ReleaseNotes
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * The What's new page on its own: the version and the release date in the header, the curated notes as markdown and
 * nothing of what surrounds them on GitHub, the two actions, and the read state — written on arrival, and by Done.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WhatsNewScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var prefs: PreferencesStore
    private val opened = mutableListOf<String>()
    private var backs = 0

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
        prefs = PreferencesStore(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun graph(notes: ReleaseNotes?): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return AppGraph(context, releaseNotes = WhatsNewFixtures.repository(prefs, folder.newFolder(), notes = notes))
    }

    private fun show(graph: AppGraph) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                    WhatsNewScreen(graph, onBack = { backs++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun readVersion(): String? = runBlocking { prefs.whatsNewReadVersion.first() }

    private fun assertAbsent(vararg texts: String) {
        texts.forEach { compose.onAllNodes(hasText(it, substring = true)).assertCountEquals(0) }
    }

    @Test
    fun `the header names the version and its release date, the notes are the curated markdown, and arriving reads them`() {
        val graph = graph(WhatsNewFixtures.notes())
        assertThat(readVersion()).isNull()

        show(graph)

        compose.onNodeWithText(WhatsNewCopy.title("0.3.37")).assertIsDisplayed()
        compose.onNodeWithText("Released Jan 14").assertIsDisplayed()
        // The heading, the lead, a bullet — each its own block of the transcript's markdown.
        compose.onNodeWithText("Goals").assertIsDisplayed()
        compose.onNodeWithText(WhatsNewFixtures.LEAD, substring = true).assertExists()
        // The code span, padded the way the transcript pads its inline code.
        compose.onNodeWithText(" /goal … ", substring = true).assertExists()
        compose.onNodeWithText("Goal strip.", substring = true).assertExists()
        compose.onNodeWithText("Default mode reads only documented data", substring = true).assertExists()
        // Nothing of the header table, the install line, the reinstall notice or the generated commit list.
        assertAbsent("versionCode", "Install:", "Signing certificate", "Upgrading from v0.1.0", "uninstall Cursor", "What's Changed", "Full Changelog")
        compose.onNodeWithTag(WhatsNewTags.GITHUB).assertIsDisplayed()
        compose.onNodeWithTag(WhatsNewTags.DONE).assertIsDisplayed()

        // Opening the page is what reads it: the row and the card are gone for this version from here on.
        compose.waitUntil(10_000) { readVersion() == "0.3.37" }
        assertThat(runBlocking { graph.whatsNew.unread.first() }).isNull()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `View on GitHub opens the release page, and Done reads the notes and leaves`() {
        val graph = graph(WhatsNewFixtures.notes())
        show(graph)

        compose.onNodeWithTag(WhatsNewTags.GITHUB).performClick()
        assertThat(opened).containsExactly(WhatsNewFixtures.HTML_URL)
        assertThat(backs).isEqualTo(0)

        compose.onNodeWithTag(WhatsNewTags.DONE).performClick()
        compose.waitUntil(10_000) { backs == 1 }
        compose.waitUntil(10_000) { readVersion() == "0.3.37" }
        assertThat(runBlocking { graph.whatsNew.unread.first() }).isNull()
    }

    @Test
    fun `without notes the page says so, points at the releases, and reads nothing`() {
        val graph = graph(notes = null)
        show(graph)

        compose.onNodeWithText(WhatsNewCopy.title(WhatsNewFixtures.VERSION)).assertIsDisplayed()
        compose.onNodeWithText(WhatsNewCopy.unavailable(WhatsNewFixtures.VERSION)).assertIsDisplayed()
        compose.onNodeWithText(WhatsNewCopy.UNAVAILABLE_HINT).assertIsDisplayed()
        assertAbsent("Released")

        compose.onNodeWithTag(WhatsNewTags.GITHUB).performClick()
        assertThat(opened).containsExactly(GitHubReleasesClient.releasesPageUrl(BuildConfig.GITHUB_REPO))
        // There was nothing to have read; the version's notes stay unread for when they arrive.
        compose.waitForIdle()
        assertThat(readVersion()).isNull()

        compose.onNodeWithTag(WhatsNewTags.DONE).performClick()
        compose.waitUntil(10_000) { backs == 1 }
    }

    private companion object {
        val FIXED_NOW: Long = Instant.parse("2025-01-15T14:00:00Z").toEpochMilli()
    }
}
