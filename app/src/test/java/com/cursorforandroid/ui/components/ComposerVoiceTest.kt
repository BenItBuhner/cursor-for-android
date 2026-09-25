package com.cursorforandroid.ui.components

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer with voice input, rendered: the send slot's three states and the mic's place in each, and a dictation
 * end to end on a fake microphone and `TranscribeAudio` — the words in at the caret and not sent, the permission asked
 * for and refused, and cancel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerVoiceTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeCapture : AudioCapture {
        @Volatile var started = false
        @Volatile var cancelled = false
        override fun start() { started = true }
        override fun level() = 0.6f
        override fun stop(): RecordedClip = RecordedClip(byteArrayOf(9, 9), "audio/webm", 1_500)
        override fun cancel() { cancelled = true }
    }

    private class FakeTranscription(private val words: String) : TranscriptionApi {
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?): Transcription {
            calls += mimeType
            return Transcription(words, 300)
        }
    }

    private val capture = FakeCapture()
    private val transcription = FakeTranscription("login")
    private val sent = CopyOnWriteArrayList<String>()

    /** A registry that answers every permission request at once with [grant], as the system dialog would. */
    private fun permissionAnswer(grant: Boolean) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, grant)
            }
        }
    }

    private fun grantMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Composable
    private fun Host(value: MutableState<String>, running: Boolean = false, voiceOn: Boolean = true) {
        val scope = rememberCoroutineScope()
        val voice = remember { VoiceInput(scope, transcription, { capture }) }
        CursorTheme(mode = ThemeMode.Dark) {
            ComposerBox(
                value = value.value,
                onValueChange = { value.value = it },
                placeholder = "Follow up…",
                onSend = { sent += value.value },
                isRunning = running,
                onStop = {},
                voice = voice.takeIf { voiceOn },
            )
        }
    }

    private fun show(text: String = "", running: Boolean = false, voiceOn: Boolean = true, registry: ActivityResultRegistryOwner? = null): MutableState<String> {
        val value = mutableStateOf(text)
        compose.setContent {
            if (registry != null) CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { Host(value, running, voiceOn) }
            else Host(value, running, voiceOn)
        }
        compose.waitForIdle()
        return value
    }

    private fun bounds(description: String) = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun count(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    @Test
    fun `voice input off shows no microphone anywhere`() {
        show(voiceOn = false)
        assertThat(count("Send")).isEqualTo(1)
        assertThat(count("Voice input")).isEqualTo(0)
    }

    @Test
    fun `empty and idle, the main button is the microphone and there is no Send and no second mic`() {
        show()
        compose.onAllNodesWithContentDescription("Voice input").assertCountEquals(1)
        assertThat(count("Send")).isEqualTo(0)
        assertThat(count("Stop")).isEqualTo(0)
    }

    @Test
    fun `with text the main button is Send with the microphone just left of it`() {
        show(text = "Fix the bug")
        val mic = bounds("Voice input")
        val send = bounds("Send")
        assertThat(mic.right).isAtMost(send.left)
        // The descriptions sit on the 17dp glyphs: the 10dp gap between the discs plus each disc's 3.5dp inset.
        assertThat(send.left - mic.right).isWithin(2f).of(17f * 2.625f)
        assertThat(mic.center.y).isWithin(1f).of(send.center.y)
    }

    @Test
    fun `running with an empty composer the main button is Stop with the microphone just left of it`() {
        show(running = true)
        val mic = bounds("Voice input")
        val stop = bounds("Stop")
        assertThat(mic.right).isAtMost(stop.left)
        assertThat(count("Send")).isEqualTo(0)
    }

    @Test
    fun `typing into an empty composer turns the microphone back into Send, with the mic moving beside it`() {
        show()
        assertThat(count("Send")).isEqualTo(0)
        compose.onNode(hasSetTextAction()).performTextInput("a")
        compose.waitForIdle()
        assertThat(count("Send")).isEqualTo(1)
        assertThat(bounds("Voice input").right).isAtMost(bounds("Send").left)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a dictation goes in at the caret and is not sent`() {
        grantMicrophone()
        val value = show(text = "Fix the bug")
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(3))
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.waitUntil(5_000) { count("Stop recording") == 1 }
        assertThat(capture.started).isTrue()
        compose.onNode(hasTestTag("voice-status")).assertExists()

        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(5_000) { value.value == "Fix login the bug" }
        compose.waitUntil(5_000) { count("Voice input") == 1 }
        assertThat(transcription.calls).containsExactly("audio/webm")
        assertThat(sent).isEmpty()
        compose.onNode(hasTestTag("voice-status")).assertDoesNotExist()
    }

    @Test
    fun `without the permission the tap asks for it, and a grant starts recording`() {
        show(registry = permissionAnswer(grant = true))
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.waitUntil(5_000) { count("Stop recording") == 1 }
        assertThat(capture.started).isTrue()
    }

    @Test
    fun `a refused permission says so, offers Settings, and records nothing`() {
        show(registry = permissionAnswer(grant = false))
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Microphone access is off. Tap to open Settings.")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(capture.started).isFalse()
        // The composer stays usable: the microphone is still the main button, and the error can be dismissed.
        assertThat(count("Voice input")).isEqualTo(1)
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("voice-error")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `cancel while recording throws the clip away and transcribes nothing`() {
        grantMicrophone()
        val value = show()
        compose.onNodeWithContentDescription("Voice input").performClick()
        compose.waitUntil(5_000) { count("Stop recording") == 1 }
        compose.onNode(hasContentDescription("Cancel voice input")).performClick()
        compose.waitUntil(5_000) { count("Voice input") == 1 }
        assertThat(capture.cancelled).isTrue()
        assertThat(transcription.calls).isEmpty()
        assertThat(value.value).isEmpty()
    }
}
