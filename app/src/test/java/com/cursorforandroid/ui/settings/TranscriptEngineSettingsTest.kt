package com.cursorforandroid.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
import com.cursorforandroid.domain.TranscriptEngine
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
 * The transcript engine is one switch beside Extended mode — on is Beta, off is Stable, the default — stored under
 * the preference it always was. With the mode off it does nothing, so it is dimmed with the reason and takes no tap,
 * while still showing what is stored.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class TranscriptEngineSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        graph.extendedMode.onEnabled = {}
        compose.setContent {
            // As the screen lays them out: the mode, and beside it the engine, both reading the one collected value.
            val extended by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false, context = Dispatchers.Main.immediate)
            CursorTheme(mode = ThemeMode.Dark) {
                Column {
                    ExtendedModeRow(graph, enabled = extended)
                    TranscriptEngineRow(graph, extendedMode = extended)
                }
            }
        }
    }

    private fun engine() = runBlocking { graph.extendedMode.engine() }

    private val engineSwitch get() = compose.onNodeWithTag(ExtendedModeTags.ENGINE_TOGGLE)

    private fun engineReads(on: Boolean, enabled: Boolean) =
        compose.onAllNodes(hasTestTag(ExtendedModeTags.ENGINE_TOGGLE) and (if (on) isOn() else isOff()) and (if (enabled) isEnabled() else isNotEnabled()))
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
    fun `with the mode off the switch is dimmed with the reason, and a tap changes nothing`() {
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.NEEDS_MODE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_DETAIL).assertDoesNotExist()
        engineSwitch.assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_ROW).assertIsNotEnabled()

        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).performClick()
        compose.waitForIdle()
        assertThat(engine()).isEqualTo(TranscriptEngine.STABLE)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isFalse()
    }

    @Test
    fun `with the mode on the switch is Beta when on and Stable when off, Stable to begin with`() {
        turnModeOn()
        compose.waitUntil(10_000) { engineReads(on = false, enabled = true) }
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_DETAIL).assertIsDisplayed()
        compose.onNodeWithTag(ExtendedModeTags.ENGINE_ROW).assertIsEnabled()
        assertThat(engine()).isEqualTo(TranscriptEngine.STABLE)

        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).performClick()
        compose.waitUntil(10_000) { engine() == TranscriptEngine.BETA }
        compose.waitUntil(10_000) { engineReads(on = true, enabled = true) }
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isTrue()

        engineSwitch.performClick()
        compose.waitUntil(10_000) { engine() == TranscriptEngine.STABLE }
        compose.waitUntil(10_000) { engineReads(on = false, enabled = true) }
    }

    @Test
    fun `Beta chosen and the mode turned off, the switch still shows Beta, dimmed, and nothing reads the record`() {
        turnModeOn()
        compose.onNodeWithText(ExtendedModeCopy.ENGINE_TITLE).performClick()
        compose.waitUntil(10_000) { engine() == TranscriptEngine.BETA }

        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { !runBlocking { graph.extendedMode.isEnabled() } }
        compose.waitUntil(10_000) { engineReads(on = true, enabled = false) }
        compose.onNodeWithText(ExtendedModeCopy.NEEDS_MODE).assertIsDisplayed()
        assertThat(engine()).isEqualTo(TranscriptEngine.BETA)
        assertThat(runBlocking { graph.extendedMode.capabilities() }.accountTranscript).isFalse()
    }
}
