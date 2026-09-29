package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
 * The New Chat composer's model picker with no Options section: the sheet opens on "Models", the selected model
 * first with its parameters unfolded, dark (`730`) and light (`731`). Plan mode is the composer's own — the "+" menu
 * still leads with it (`732`), beside `/plan` and Shift+Tab. Driven through the real pane over the demo session.
 * Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class ModelPickerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.catalog.loadRepositories()
            graph.catalog.loadModels()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun showComposer(mode: ThemeMode) {
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        HomeScreen(
                            graph = graph,
                            listState = NewChatHomeFixtures.list(),
                            onOpenSidebar = {},
                            onOpenAgent = {},
                            onLaunchOpen = {},
                            rowActions = ROW_ACTIONS,
                            modifier = Modifier.fillMaxSize(),
                            home = NewChatHome.COMPOSER,
                            projectsAvailable = true,
                            onNewProject = {},
                            onOpenSettings = {},
                        )
                    }
                }
            }
        }
        // The first composition in a cold sandbox loads the native renderer and the fonts.
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { onScreen(MODEL_CHIP) }
    }

    /** The chip opens the sheet; its list is shown from its first row, whatever it scrolled to on opening. */
    private fun modelPicker(mode: ThemeMode, frame: String) {
        showComposer(mode)
        compose.onNodeWithText(MODEL_CHIP).performClick()
        compose.waitUntil(10_000) { onScreen("Models") }
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Models"))).performScrollToIndex(0)
        compose.waitForIdle()
        assertThat(onScreen("Options")).isFalse()
        assertThat(onScreen("Plan mode")).isFalse()
        assertThat(onScreen("Auto-create PR")).isFalse()
        capture(frame)
    }

    @Test
    fun modelPickerDark() = modelPicker(ThemeMode.Dark, "730_model_picker_no_options_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun modelPickerLight() = modelPicker(ThemeMode.Light, "731_model_picker_no_options_light")

    @Test
    fun planModeStaysInThePlusMenu() {
        showComposer(ThemeMode.Dark)
        compose.onNodeWithContentDescription("Add to prompt").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Plan") and !hasContentDescription("Add to prompt")).fetchSemanticsNodes().isNotEmpty() }
        capture("732_composer_plus_menu_plan")
    }

    private companion object {
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})

        /** The chip the demo account's newest chat puts on a new chat's composer: the page has settled once it reads this. */
        const val MODEL_CHIP = "Claude Fable 5.1"
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
