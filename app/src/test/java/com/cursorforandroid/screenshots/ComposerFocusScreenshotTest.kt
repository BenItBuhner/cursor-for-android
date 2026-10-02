package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.conversation.QueuedFollowUps
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The composer with and without the caret, under a queued card: the two wear the same surface and the same 8 % stroke
 * whether or not the field has focus, so the box reads as one of the family docked over it rather than lighting up
 * when tapped. The caret in the focused frame is what says the field is taken. Dark and light on a phone, dark on a
 * tablet; same device qualifiers as [AttachmentHeightsScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComposerFocusScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private val queue = listOf(QueuedFollowUp("q-1", "Then run the light theme through the screenshot suite", queuedAtMillis = 1_000L))

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 12.dp).padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val pane = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
                        QueuedFollowUps(
                            queue = queue,
                            thumbnails = emptyMap(),
                            onEdit = {},
                            onSteer = {},
                            onRemove = {},
                            modifier = pane.padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "Also fix the flaky queue test while the run is going",
                            onValueChange = {},
                            placeholder = "Follow up (sends when the turn ends)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = pane,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun unfocused(mode: ThemeMode, name: String) {
        show(mode)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        capture(name)
    }

    private fun focused(mode: ThemeMode, name: String) {
        show(mode)
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        capture(name)
    }

    @Test
    fun phoneDarkUnfocused() = unfocused(ThemeMode.Dark, "940_composer_focus_phone_dark_unfocused")

    @Test
    fun phoneDarkFocused() = focused(ThemeMode.Dark, "941_composer_focus_phone_dark_focused")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun phoneLightUnfocused() = unfocused(ThemeMode.Light, "942_composer_focus_phone_light_unfocused")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun phoneLightFocused() = focused(ThemeMode.Light, "943_composer_focus_phone_light_focused")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun tabletDarkUnfocused() = unfocused(ThemeMode.Dark, "944_composer_focus_tablet_dark_unfocused")

    @Test
    @Config(sdk = [35], qualifiers = TABLET)
    fun tabletDarkFocused() = focused(ThemeMode.Dark, "945_composer_focus_tablet_dark_focused")
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET = "w1000dp-h720dp-night-320dpi"
