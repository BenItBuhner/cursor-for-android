package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.VoiceInput
import com.cursorforandroid.ui.components.VoiceState
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
import kotlin.math.abs
import kotlin.math.sin

/**
 * The composer's send slot with voice input on, one frame per state: Send with the mic beside it, Stop with the mic
 * beside it, the empty composer's white main button as the mic; a dictation recording (as the main button and beside
 * Send), transcribing, and the microphone permission refused; the main mic in light. The dictation states are shown
 * as they are, with no microphone or request behind them, and the clock is held so the pulsing dot and the spinner
 * are caught at the same instant every run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h220dp-night-420dpi")
class VoiceInputScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun frame(
        name: String,
        mode: ThemeMode = ThemeMode.Dark,
        value: String = "",
        running: Boolean = false,
        state: VoiceState = VoiceState.Idle,
        elapsedMillis: Long = 0L,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    VoiceComposer(value = value, running = running, state = state, elapsedMillis = elapsedMillis)
                }
            }
        }
        compose.mainClock.advanceTimeBy(HeldMillis)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun sendWithMic() = frame("570_voice_composer_send_mic", value = "Fix the login redirect after the session expires")

    @Test
    fun stopWithMic() = frame("571_voice_composer_stop_mic", running = true)

    @Test
    fun micAsMainButton() = frame("572_voice_composer_mic_main")

    @Test
    fun recording() = frame("573_voice_composer_recording", state = VoiceState.Recording(0L), elapsedMillis = 7_300L)

    @Test
    fun recordingBesideSend() =
        frame("574_voice_composer_recording_beside_send", value = "Fix the login redirect", state = VoiceState.Recording(0L), elapsedMillis = 42_000L)

    @Test
    fun transcribing() = frame("575_voice_composer_transcribing", state = VoiceState.Transcribing)

    @Test
    fun permissionDenied() =
        frame("576_voice_composer_permission_denied", state = VoiceState.Failed("Microphone access is off. Tap to open Settings.", opensSettings = true))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h220dp-notnight-420dpi")
    fun micAsMainButtonLight() = frame("577_voice_composer_mic_main_light", mode = ThemeMode.Light)

    private companion object {
        const val HeldMillis = 400L

        /** A speaking voice's meter: syllables of varying loudness over a floor of room noise. */
        val Levels: List<Float> = List(48) { i ->
            (0.12 + 0.75 * abs(sin(i * 0.55)) * (0.55 + 0.45 * sin(i * 0.21))).toFloat().coerceIn(0f, 1f)
        }
    }

    @Composable
    private fun VoiceComposer(value: String, running: Boolean, state: VoiceState, elapsedMillis: Long) {
        val scope = rememberCoroutineScope()
        val voice = remember {
            VoiceInput(scope, { _, _, _ -> Transcription("", null) }, { NoMicrophone }).apply {
                show(state, levels = if (state is VoiceState.Recording) Levels else emptyList(), elapsedMillis = elapsedMillis)
            }
        }
        Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(horizontal = 16.dp, vertical = 24.dp)) {
            ComposerBox(
                value = value,
                onValueChange = {},
                placeholder = if (running) "Follow up…" else "Ask Cursor to build, fix bugs, explore",
                onSend = {},
                isRunning = running,
                onStop = {},
                plusMenu = ComposerMenuActions(onPickMedia = {}),
                modelLabel = "Claude Fable 5.1",
                onModel = {},
                minLines = 2,
                voice = voice,
            )
        }
    }
}

private object NoMicrophone : AudioCapture {
    override fun start() = error("The screenshots never open the microphone.")
    override fun level() = 0f
    override fun stop(): RecordedClip? = null
    override fun cancel() = Unit
}
