package com.cursorforandroid.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Advanced card: the only way the setting comes on is through the warning, read and acknowledged, and the
 * options that need the mode are on the screen exactly while it is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ExtendedModeSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { Column { ExtendedModeRows(graph) } }
        }
    }

    private fun dialogShown() = compose.onAllNodes(hasTestTag(ExtendedModeTags.DIALOG)).fetchSemanticsNodes().isNotEmpty()

    private fun enabled() = runBlocking { graph.extendedMode.isEnabled() }

    @Test
    fun `turning it on the first time shows the warning, which cannot be skipped and enables nothing until it is accepted`() {
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()

        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { dialogShown() }

        // The warning, in full.
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_INTRO).assertIsDisplayed()
        ExtendedModeCopy.DIALOG_POINTS.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CLOSING).assertIsDisplayed()
        assertThat(enabled()).isFalse()

        // Neither the back gesture nor the confirm button gets past it unread.
        Espresso.pressBack()
        compose.waitForIdle()
        assertThat(dialogShown()).isTrue()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).assertIsNotEnabled()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).performClick()
        compose.waitForIdle()
        assertThat(enabled()).isFalse()
        assertThat(dialogShown()).isTrue()

        compose.onNode(hasTestTag(ExtendedModeTags.DIALOG_CHECKBOX) and isToggleable()).performClick()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).assertIsEnabled()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).performClick()

        compose.waitUntil(10_000) { enabled() }
        compose.waitUntil(10_000) { !dialogShown() }
        assertThat(runBlocking { graph.extendedMode.acknowledgedAt.first() }).isNotNull()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(ExtendedModeCopy.SETTING_ON)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(ExtendedModeCopy.ACKNOWLEDGED_LABEL).assertIsDisplayed()
    }

    private fun optionsShown() = compose.onAllNodes(hasTestTag(ExtendedModeTags.PIN_SYNC)).fetchSemanticsNodes().isNotEmpty()

    /** The switch inside the pin sync row, as TalkBack reads it. */
    private fun pinSyncSwitch() = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(ExtendedModeTags.PIN_SYNC)))

    private fun pinSyncPreference() = runBlocking { graph.prefs.pinSyncEnabled.first() }

    @Test
    fun `the options that need the mode are hidden while it is off and shown, under the note, while it is on`() {
        // Off: no note, no pin sync switch, not a word about pins anywhere in the section.
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()
        compose.onAllNodes(hasTestTag(ExtendedModeTags.OPTIONS_NOTE)).assertCountEquals(0)
        compose.onAllNodes(hasTestTag(ExtendedModeTags.PIN_SYNC)).assertCountEquals(0)
        compose.onAllNodes(hasText(ExtendedModeCopy.PIN_SYNC_TITLE)).assertCountEquals(0)

        // The preference under the option is the user's whatever the mode says: switched off before the mode is on,
        // the option appears switched off.
        runBlocking {
            graph.prefs.setPinSyncEnabled(false)
            graph.extendedMode.acknowledge()
            graph.extendedMode.enable()
        }
        compose.waitUntil(10_000) { optionsShown() }
        compose.onNodeWithTag(ExtendedModeTags.OPTIONS_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.OPTIONS_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.PIN_SYNC_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.PIN_SYNC_SUBTITLE).assertIsDisplayed()
        pinSyncSwitch().assertIsOff()

        // The row is the option: tapping it flips the preference.
        compose.onNodeWithTag(ExtendedModeTags.PIN_SYNC).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(isOn() and hasAnyAncestor(hasTestTag(ExtendedModeTags.PIN_SYNC))).fetchSemanticsNodes().isNotEmpty() }
        assertThat(pinSyncPreference()).isTrue()

        // Off again: the options leave the screen with the mode, and the preference stays as it was left.
        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { !enabled() }
        compose.waitUntil(10_000) { !optionsShown() }
        compose.onAllNodes(hasTestTag(ExtendedModeTags.OPTIONS_NOTE)).assertCountEquals(0)
        compose.onAllNodes(hasText(ExtendedModeCopy.PIN_SYNC_TITLE)).assertCountEquals(0)
        compose.onNodeWithText(ExtendedModeCopy.SETTING_OFF).assertIsDisplayed()
        assertThat(pinSyncPreference()).isTrue()

        // On again: the option is back, switched the way it was left.
        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { optionsShown() }
        pinSyncSwitch().assertIsOn()
    }

    @Test
    fun `cancelling the warning leaves it off, and turning it off later is immediate`() {
        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { dialogShown() }

        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CANCEL).performClick()
        compose.waitUntil(10_000) { !dialogShown() }
        assertThat(enabled()).isFalse()
        assertThat(runBlocking { graph.extendedMode.acknowledgedAt.first() }).isNull()

        runBlocking {
            graph.extendedMode.acknowledge()
            graph.extendedMode.enable()
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(ExtendedModeCopy.SETTING_ON)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { !enabled() }
        assertThat(dialogShown()).isFalse()

        // Acknowledged once: turning it on again does not ask again.
        compose.onNodeWithTag(ExtendedModeTags.TOGGLE).performClick()
        compose.waitUntil(10_000) { enabled() }
        assertThat(dialogShown()).isFalse()
    }
}
