package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.ModePills
import com.cursorforandroid.ui.conversation.QueuedFollowUps
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A `/command` wherever the reader's own words are drawn, in one frame: the `/goal` prompt as its bubble in the
 * transcript, a `/review` follow-up on its queued card, and a `/review` still in the composer beside the Plan pill.
 * The command tokens take the desktop's command yellow, the Plan pill's (`CommandTints`), and the rest of each text
 * stays as it is, so the three surfaces and the pill read as one thing; once in the dark theme, once in the light.
 * Then the modes' own tokens among them — `/multitask` and `/debug` in a sent prompt, `/ask` on a queued card — each
 * in its mode's colour beside a `/review` in the yellow. Same device qualifiers
 * as [AppScreenshotTest]; the layout is the chat's own — the transcript's page over the dock, the composer's gutter
 * at each side, the card docked over the box.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SlashHighlightScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private val prompt = UserMessage("m1", "/goal Make me a million dollars. Make no mistakes", 1_789_380_000_000L)
    private val queued = listOf(QueuedFollowUp("q-1", "/review Check the tax treatment before you wire anything", queuedAtMillis = 1_000L))
    private val modesPrompt =
        UserMessage("m2", "/multitask Split the payout fix across the ledger and the wire step, /review each half, then /debug what still fails", 1_789_380_000_000L)
    private val modesQueued = listOf(QueuedFollowUp("q-2", "/ask Why does the wire step round the fee down?", queuedAtMillis = 1_000L))

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Scene(mode: ThemeMode, prompt: UserMessage = this.prompt, queued: List<QueuedFollowUp> = this.queued) {
        CursorTheme(mode = mode) {
            // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                Column(Modifier.fillMaxWidth().background(CursorTheme.colors.canvas).testTag("scene")) {
                    // The transcript's page, as the chat lays its list out: the prompt as its bubble.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        TimelineItemView(prompt)
                    }
                    Spacer(Modifier.height(24.dp))
                    // The dock: the queued card over the composer, the gutter at each side.
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = CursorDimens.composerGutter).padding(bottom = CursorDimens.composerBottomGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QueuedFollowUps(
                            queue = queued,
                            thumbnails = emptyMap(),
                            onEdit = {},
                            onSteer = {},
                            onRemove = {},
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp),
                        )
                        ComposerBox(
                            value = "/review Wire the payout once the checks are green",
                            onValueChange = {},
                            placeholder = "Follow up (sends when the turn ends)…",
                            onSend = {},
                            isRunning = true,
                            onStop = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modePill = ModePills.Pill.Plan,
                            onModePill = {},
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        // The field adopts the owner's value a frame after it is set; wait for the text before the frame is taken.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Wire the payout", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("scene").captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun darkTheme() {
        compose.setContent { Scene(ThemeMode.Dark) }
        capture("110_slash_highlight_dark")
    }

    @Test
    fun lightTheme() {
        compose.setContent { Scene(ThemeMode.Light) }
        capture("111_slash_highlight_light")
    }

    @Test
    fun modeTokensDark() {
        compose.setContent { Scene(ThemeMode.Dark, modesPrompt, modesQueued) }
        capture("499_slash_highlight_modes_dark")
    }

    @Test
    fun modeTokensLight() {
        compose.setContent { Scene(ThemeMode.Light, modesPrompt, modesQueued) }
        capture("517_slash_highlight_modes_light")
    }
}
