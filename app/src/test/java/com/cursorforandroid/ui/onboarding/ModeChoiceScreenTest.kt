package com.cursorforandroid.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.ui.settings.ExtendedModeCopy
import com.cursorforandroid.ui.settings.ExtendedModeTags
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
 * The choice screen on its own: what it says, what is selected to begin with, and what each way through it leaves
 * behind — the same setting and the same acknowledgment the Settings toggle writes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ModeChoiceScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var enabledRuns = 0

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Context>())
        // Turning the mode on would reach for the account (its picture, the pins' first round); there is none here.
        graph.extendedMode.onEnabled = { enabledRuns++ }
        runBlocking { graph.onboarding.signedIn() }
    }

    private fun show() {
        compose.setContent { CursorTheme(mode = ThemeMode.Dark) { ModeChoiceScreen(graph) } }
    }

    private fun dialogShown() = compose.onAllNodes(hasTestTag(ExtendedModeTags.DIALOG)).fetchSemanticsNodes().isNotEmpty()

    private fun pending() = graph.onboarding.modeChoicePending.value

    private fun enabled() = runBlocking { graph.extendedMode.isEnabled() }

    private fun acknowledgedAt() = runBlocking { graph.extendedMode.acknowledgedAt.first() }

    @Test
    fun `SDK only is selected to begin with, both options read in full, and Continue with it settles at once`() {
        show()

        compose.onNodeWithText(ModeChoiceCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.LEAD).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.SDK_ONLY_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.SDK_ONLY_BODY).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.EXTENDED_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.EXTENDED_BODY).assertIsDisplayed()
        // Two options, both radio choices, SDK only the selected one; no note until something has been declined.
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).assertIsNotSelected()
        compose.onAllNodes(hasTestTag(ModeChoiceTags.NOTE)).assertCountEquals(0)
        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).assertIsEnabled()
        assertThat(pending()).isTrue()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()

        compose.waitUntil(10_000) { pending() == false }
        assertThat(enabled()).isFalse()
        assertThat(acknowledgedAt()).isNull()
        assertThat(dialogShown()).isFalse()
    }

    @Test
    fun `Extended mode runs the acknowledgment dialog verbatim, and declining it falls back to SDK only with the note`() {
        show()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).assertIsSelected()
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsNotSelected()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()
        compose.waitUntil(10_000) { dialogShown() }

        // The Settings dialog, word for word, and as unskippable as it is there.
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_INTRO).assertIsDisplayed()
        ExtendedModeCopy.DIALOG_POINTS.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CLOSING).assertIsDisplayed()
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CHECKBOX).assertIsDisplayed()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).assertIsNotEnabled()
        Espresso.pressBack()
        compose.waitForIdle()
        assertThat(dialogShown()).isTrue()
        assertThat(pending()).isTrue()
        assertThat(enabled()).isFalse()

        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CANCEL).performClick()

        compose.waitUntil(10_000) { !dialogShown() }
        compose.onNodeWithTag(ModeChoiceTags.SDK_ONLY).assertIsSelected()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).assertIsNotSelected()
        compose.onNodeWithTag(ModeChoiceTags.NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModeChoiceCopy.DECLINED_NOTE).assertIsDisplayed()
        // Declining decides nothing by itself: the screen is still here, with SDK only selected, until Continue.
        assertThat(pending()).isTrue()
        assertThat(acknowledgedAt()).isNull()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()

        compose.waitUntil(10_000) { pending() == false }
        assertThat(enabled()).isFalse()
        assertThat(acknowledgedAt()).isNull()
        assertThat(enabledRuns).isEqualTo(0)
    }

    @Test
    fun `accepting the warning turns Extended mode on and records the acknowledgment, as Settings does`() {
        show()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()
        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()
        compose.waitUntil(10_000) { dialogShown() }

        compose.onNode(hasTestTag(ExtendedModeTags.DIALOG_CHECKBOX) and isToggleable()).performClick()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).assertIsEnabled()
        compose.onNodeWithTag(ExtendedModeTags.DIALOG_CONFIRM).performClick()

        compose.waitUntil(10_000) { pending() == false }
        compose.waitUntil(10_000) { !dialogShown() }
        assertThat(enabled()).isTrue()
        assertThat(acknowledgedAt()).isNotNull()
        assertThat(enabledRuns).isEqualTo(1)
        assertThat(runBlocking { graph.prefs.modeChoicePending.first() }).isFalse()
    }

    @Test
    fun `an acknowledgment already on record is not asked for again, as Settings does not ask again`() {
        runBlocking { graph.extendedMode.acknowledge() }
        show()
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()

        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()

        compose.waitUntil(10_000) { pending() == false }
        assertThat(dialogShown()).isFalse()
        assertThat(enabled()).isTrue()
        assertThat(enabledRuns).isEqualTo(1)
    }

    @Test
    fun `the selection and a declined warning survive a recreation of the screen`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CursorTheme(mode = ThemeMode.Dark) { ModeChoiceScreen(graph) } }
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()
        compose.onNodeWithTag(ModeChoiceTags.CONTINUE).performClick()
        compose.waitUntil(10_000) { dialogShown() }
        compose.onNodeWithText(ExtendedModeCopy.DIALOG_CANCEL).performClick()
        compose.waitUntil(10_000) { !dialogShown() }
        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(ModeChoiceTags.EXTENDED).assertIsSelected()
        compose.onNodeWithTag(ModeChoiceTags.NOTE).assertIsDisplayed()
        assertThat(pending()).isTrue()
    }
}
