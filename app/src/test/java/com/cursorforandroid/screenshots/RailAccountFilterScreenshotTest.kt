package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.navigation.SidebarRail
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The rail with the filter button in the account row, where the overflow button was, and none in the header: on a
 * phone, an unfolded foldable and a tablet held sideways, with the button at rest, accent-tinted under a filter, and
 * in the light theme.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class RailAccountFilterScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun rail(frame: String, mode: ThemeMode = ThemeMode.Dark, prefs: ListPreferences = ListPreferences()) {
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Row(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        SidebarRail(expanded = true) {
                            Sidebar(
                                state = NewChatHomeFixtures.list().copy(prefs = prefs),
                                user = DEMO_USER,
                                isDemo = true,
                                selectedAgentId = null,
                                selectedDestination = null,
                                onQueryChange = {},
                                callbacks = SidebarCallbacks(
                                    onNewChat = {},
                                    onSettings = {},
                                    onCustomize = {},
                                    onToggleSidebar = {},
                                    onRefresh = {},
                                    rowActions = ROW_ACTIONS,
                                    onNewProject = {},
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Demo User")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$frame.png").path, RoborazziOptions())
    }

    @Test
    fun phone() = rail("540_rail_account_filter_phone")

    @Test
    fun phoneFiltered() = rail("541_rail_account_filter_active_phone", prefs = ListPreferences(sources = setOf(SourceFilter.Desktop)))

    @Test
    @Config(sdk = [35], qualifiers = FOLDABLE)
    fun foldable() = rail("542_rail_account_filter_foldable")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun tablet() = rail("543_rail_account_filter_tablet")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun phoneLight() = rail("544_rail_account_filter_light_phone", mode = ThemeMode.Light)

    private companion object {
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"

/** An unfolded book-style foldable, held as it opens: Material's 840dp expanded width. */
private const val FOLDABLE = "w840dp-h700dp-night-320dpi"

/** A 11" tablet held sideways (SM-X700: 2560×1600 at xhdpi). */
private const val TABLET = "w1280dp-h800dp-night-320dpi"
