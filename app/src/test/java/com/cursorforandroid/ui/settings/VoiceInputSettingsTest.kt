package com.cursorforandroid.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Experimental › Voice input: off until turned on, and only live in Extended mode — dimmed with the reason
 * and reading off without it, whatever is stored. What the composers read ([AppGraph.voiceInput]) is on only with the
 * switch, the mode, and outside the demo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class VoiceInputSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.extendedMode.onEnabled = {}
        compose.setContent {
            val extended by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false, context = Dispatchers.Main.immediate)
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    ExtendedModeRow(graph, enabled = extended)
                    VoiceInputRow(graph, extendedMode = extended)
                }
            }
        }
    }

    private fun stored() = runBlocking { graph.prefs.voiceInput.first() }

    private fun active() = runBlocking { graph.voiceInput.first() }

    private fun voiceReads(on: Boolean, enabled: Boolean) =
        compose.onAllNodes(hasTestTag(SettingsTags.VOICE_INPUT_TOGGLE) and (if (on) isOn() else isOff()) and (if (enabled) isEnabled() else isNotEnabled()))
            .fetchSemanticsNodes().isNotEmpty()

    private fun turnModeOn() {
        runBlocking {
            graph.extendedMode.acknowledge()
            graph.extendedMode.enable()
            graph.extendedMode.enabled.first { it }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(ExtendedModeTags.TOGGLE) and isOn()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `off by default, and without Extended mode dimmed with the reason and inert`() {
        compose.onNodeWithText(SettingsCopy.VOICE_INPUT).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.NEEDS_MODE).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.VOICE_INPUT_TOGGLE).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(SettingsCopy.VOICE_INPUT).performClick()
        compose.waitForIdle()
        assertThat(stored()).isFalse()
        assertThat(active()).isFalse()
    }

    @Test
    fun `in Extended mode it starts off and the switch turns it on and off`() {
        turnModeOn()
        compose.waitUntil(10_000) { voiceReads(on = false, enabled = true) }
        compose.onNodeWithText(SettingsCopy.VOICE_INPUT_DETAIL).assertIsDisplayed()
        assertThat(active()).isFalse()

        compose.onNodeWithTag(SettingsTags.VOICE_INPUT_TOGGLE).performClick()
        compose.waitUntil(10_000) { stored() }
        compose.waitUntil(10_000) { voiceReads(on = true, enabled = true) }
        assertThat(active()).isTrue()

        compose.onNodeWithText(SettingsCopy.VOICE_INPUT).performClick()
        compose.waitUntil(10_000) { !stored() }
        compose.waitUntil(10_000) { voiceReads(on = false, enabled = true) }
        assertThat(active()).isFalse()
    }

    @Test
    fun `Extended mode going off turns voice input off without forgetting the choice`() {
        turnModeOn()
        compose.onNodeWithTag(SettingsTags.VOICE_INPUT_TOGGLE).performClick()
        compose.waitUntil(10_000) { active() }

        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { runBlocking { !graph.extendedMode.isEnabled() } }
        compose.waitUntil(10_000) { voiceReads(on = false, enabled = false) }
        assertThat(active()).isFalse()
        assertThat(stored()).isTrue()

        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { runBlocking { graph.extendedMode.isEnabled() } }
        compose.waitUntil(10_000) { voiceReads(on = true, enabled = true) }
        assertThat(active()).isTrue()
    }

    @Test
    fun `the demo never has voice input, whatever is stored`() {
        turnModeOn()
        runBlocking { graph.prefs.setVoiceInput(true) }
        assertThat(active()).isTrue()
        runBlocking { graph.prefs.setDemoMode(true) }
        assertThat(active()).isFalse()
    }
}
