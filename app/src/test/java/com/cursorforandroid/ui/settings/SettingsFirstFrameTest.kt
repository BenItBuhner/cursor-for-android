package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Settings draws the stored values from its very first frame. It is composed inside a frame, as the push to it does,
 * with the clock paused: were a row to start from its default, the first frame would show it (the OLED row under
 * Cursor Light, switches on) and the next would take it back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SettingsFirstFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun toggle(tag: String): ToggleableState? =
        compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.ToggleableState)

    private fun shown(text: String, selected: Boolean? = null): Boolean {
        val matcher = if (selected == null) hasText(text) else hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Selected, selected)
        return compose.onAllNodes(matcher, useUnmergedTree = selected == null).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the first frame shows the stored settings, not the defaults`() {
        val graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        // As MainActivity does when its window opens.
        graph.prefs.warmSettings()
        runBlocking {
            graph.prefs.setThemeMode(ThemeMode.Light)
            graph.prefs.setConfirmStop(false)
            graph.prefs.setShortenSidebarLists(false)
            graph.prefs.setLiveNotifications(false)
            withTimeout(5_000) { graph.prefs.settings.first { it?.themeMode == ThemeMode.Light && it.liveNotifications == false } }
        }
        var show by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Light) {
                if (show) SettingsScreen(graph, CursorUser("Demo", "demo@cursor.local", "Demo", "User", null), isDemo = true, onOpenSidebar = null, onBack = {})
            }
        }
        compose.mainClock.advanceTimeByFrame()
        show = true
        var waited = 0
        do compose.mainClock.advanceTimeByFrame() while (!shown(SettingsCopy.GROUP_APPEARANCE) && ++waited < 10)
        val frames = (1..3).map {
            if (it > 1) compose.mainClock.advanceTimeByFrame()
            listOf(shown("Cursor Light", selected = true), shown("OLED black"), toggle(SettingsTags.CONFIRM_STOP), toggle(SettingsTags.SHORTEN_PROJECTS))
        }
        compose.mainClock.autoAdvance = true

        assertThat(frames.first()).isEqualTo(listOf(true, false, ToggleableState.Off, ToggleableState.Off))
        assertThat(frames.distinct()).hasSize(1)
    }
}
