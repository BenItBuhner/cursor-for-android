package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.AgentSection
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.components.REFRESH_INDICATOR_TEST_TAG
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The sidebar's pull to refresh as the finger draws it down: the indicator's arrow filling under the header, whole
 * and solid once the pull is armed, then let go and spinning at the threshold while the list is refreshed — the same
 * outlined, shadowless disc the chat's pull to catch up shows (`CatchUpScreenshotTest`), in the dark theme and the
 * light, where the disc is the sidebar's own colour and the hairline is what shows it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class RefreshIndicatorScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private var refreshing by mutableStateOf(false)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun agent(id: String, name: String, ageMinutes: Long, running: Boolean, appearance: ProjectAppearance? = null) = Agent(
        id = id,
        name = name,
        lifecycle = if (running) AgentLifecycle.ACTIVE else AgentLifecycle.IDLE,
        runStatus = if (running) RunStatus.RUNNING else RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = NOW - (ageMinutes + 40) * 60_000L,
        updatedAtMillis = NOW - ageMinutes * 60_000L,
        latestRunId = null,
        repoUrl = "https://github.com/acme/$id",
        startingRef = "main",
        branches = emptyList(),
        isProject = appearance != null,
        projectAppearance = appearance,
    )

    private fun chat(id: String, name: String, ageMinutes: Long, indicator: AgentIndicator = AgentIndicator.Read, pinned: Boolean = false) =
        AgentRow(agent = agent(id, name, ageMinutes, running = indicator == AgentIndicator.Running), indicator = indicator, isPinned = pinned, isUnread = indicator == AgentIndicator.Unread, launchedFromThisDevice = false)

    private val sections = listOf(
        AgentSection(
            AgentListOrganizer.PROJECTS_KEY, "Projects",
            listOf(AgentRow(agent("cesium", "Cesium billing launch", 65, running = false, appearance = ProjectAppearance("robot", "purple")), indicator = AgentIndicator.Unread, isPinned = false, isUnread = true, launchedFromThisDevice = false, memberCount = 3)),
        ),
        AgentSection(AgentListOrganizer.PINNED_KEY, "Pinned", listOf(chat("strategy", "Cesium Revenue Strategy", 26 * 60, pinned = true))),
        AgentSection(
            "date:Today", "Today",
            listOf(
                chat("cli", "Cli exploration", 12, AgentIndicator.Unread),
                chat("house", "House environment overhaul", 34, AgentIndicator.Running),
                chat("replay", "Market replay engine", 51, AgentIndicator.Error),
                chat("release", "Latest release process", 2 * 60),
                chat("city", "Realistic city apartment scene", 3 * 60),
            ),
        ),
        AgentSection("date:Yesterday", "Yesterday", listOf(chat("onboarding", "Onboarding copy pass", 26 * 60), chat("flaky", "Flaky transcription test", 30 * 60))),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode) {
        refreshing = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize()) {
                        Sidebar(
                            state = AgentListUiState(sections = sections, hasLoaded = true, prefs = ListPreferences(), nowMillis = NOW, isRefreshing = refreshing),
                            user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
                            isDemo = false,
                            selectedAgentId = "cli",
                            selectedDestination = null,
                            onQueryChange = {},
                            callbacks = SidebarCallbacks(
                                onNewChat = {},
                                onSettings = {},
                                onCustomize = {},
                                onToggleSidebar = {},
                                onRefresh = { refreshing = true },
                                rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                                onNewProject = {},
                            ),
                            extendedMode = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The clock is stopped (the spinner never idles): what was just set is taken up, then composed in a frame. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** The finger comes down on the list at [startY]. */
    private fun fingerDown(startY: Float) = compose.onRoot().performTouchInput { down(Offset(centerX, startY)) }

    /** The finger on the list travels [px] further down it, in the steps a finger takes. */
    private fun fingerBy(px: Float) = compose.onRoot().performTouchInput { repeat(10) { moveBy(Offset(0f, px / 10)) } }

    private fun states(mode: ThemeMode, suffix: String) {
        show(mode)
        val density = compose.density.density
        // From a row well under the header; Material takes half the finger (its drag multiplier), so the 80dp threshold
        // is 160dp of travel after the touch slop.
        fingerDown(startY = 300f * density)
        fingerBy(130f * density)
        frame()
        compose.mainClock.advanceTimeBy(400L)
        frame()
        capture("950_sidebar_refresh_pulling_$suffix")

        fingerBy(110f * density)
        frame()
        compose.mainClock.advanceTimeBy(400L)
        frame()
        capture("951_sidebar_refresh_armed_$suffix")

        compose.onRoot().performTouchInput { up() }
        // Let go armed, Material springs the disc to the threshold and only then asks for the refresh: a second on,
        // the spring has settled and the list's state has turned the arrow into the spinner.
        frame()
        compose.mainClock.advanceTimeBy(1_000L)
        frame()
        assertThat(refreshing).isTrue()
        compose.mainClock.advanceTimeBy(500L)
        frame()
        compose.onNodeWithTag(REFRESH_INDICATOR_TEST_TAG).assertExists()
        capture("952_sidebar_refresh_refreshing_$suffix")
    }

    @Test
    fun dark() = states(ThemeMode.Dark, "dark")

    @Test
    fun light() = states(ThemeMode.Light, "light")

    private companion object {
        /** Wednesday 2025-01-15 14:00 UTC, the walkthrough's clock. */
        const val NOW = 1_736_949_600_000L
    }
}
