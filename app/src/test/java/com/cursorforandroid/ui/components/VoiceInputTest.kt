package com.cursorforandroid.ui.components

import androidx.compose.ui.text.TextRange
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.MediaRecorderCapture
import com.cursorforandroid.data.media.RecordedClip
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The send slot's matrix with voice input on and off, the dictation put in at the caret, and a dictation's turns —
 * recording, transcribing, the words handed back, the short clip dropped, the five-minute cap, cancel, and the
 * errors in the footer's words — on a fake microphone and a fake `TranscribeAudio`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputTest {

    private fun buttons(
        voice: Boolean,
        hasContent: Boolean = false,
        canSend: Boolean = hasContent,
        canStop: Boolean = false,
        isSending: Boolean = false,
        cancelOffered: Boolean = false,
    ) = composerButtons(isSending = isSending, cancelOffered = cancelOffered, canStop = canStop, canSend = canSend, hasContent = hasContent, voice = voice)

    @Test
    fun `voice off leaves the send slot as it was`() {
        assertThat(buttons(voice = false)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = false))
        assertThat(buttons(voice = false, hasContent = true)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = false))
        assertThat(buttons(voice = false, canStop = true)).isEqualTo(ComposerButtons(SendSlot.Stop, micBeside = false))
        assertThat(buttons(voice = false, canStop = true, hasContent = true)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = false))
        assertThat(buttons(voice = false, isSending = true)).isEqualTo(ComposerButtons(SendSlot.Busy, micBeside = false))
        assertThat(buttons(voice = false, isSending = true, cancelOffered = true)).isEqualTo(ComposerButtons(SendSlot.CancelSend, micBeside = false))
    }

    @Test
    fun `with text the main button is Send and the mic sits left of it`() {
        assertThat(buttons(voice = true, hasContent = true)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = true))
        // Text that cannot be sent yet (files still going up on New Chat) is still text: Send, dimmed, with the mic.
        assertThat(buttons(voice = true, hasContent = true, canSend = false)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = true))
        // While the agent runs, a typed follow-up is Send, as before, with the mic beside it.
        assertThat(buttons(voice = true, hasContent = true, canStop = true)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = true))
    }

    @Test
    fun `running with an empty composer the main button is Stop and the mic sits left of it`() {
        assertThat(buttons(voice = true, canStop = true)).isEqualTo(ComposerButtons(SendSlot.Stop, micBeside = true))
    }

    @Test
    fun `empty and idle the main white button is the mic itself, with no second mic`() {
        assertThat(buttons(voice = true)).isEqualTo(ComposerButtons(SendSlot.Mic, micBeside = false))
    }

    @Test
    fun `an attachment alone counts as content, so Send stays the main button`() {
        assertThat(buttons(voice = true, hasContent = false, canSend = true)).isEqualTo(ComposerButtons(SendSlot.Send, micBeside = true))
    }

    @Test
    fun `a prompt in flight has no mic`() {
        assertThat(buttons(voice = true, isSending = true)).isEqualTo(ComposerButtons(SendSlot.Busy, micBeside = false))
        assertThat(buttons(voice = true, isSending = true, cancelOffered = true)).isEqualTo(ComposerButtons(SendSlot.CancelSend, micBeside = false))
        assertThat(buttons(voice = true, isSending = true, hasContent = true)).isEqualTo(ComposerButtons(SendSlot.Busy, micBeside = false))
    }

    @Test
    fun `dictation goes in at the caret with a space either side where it would run into a word`() {
        assertThat(insertDictation("", TextRange(0), " fix the login ")).isEqualTo(Dictated("fix the login", 13))
        assertThat(insertDictation("Please", TextRange(6), "fix it")).isEqualTo(Dictated("Please fix it", 13))
        assertThat(insertDictation("Please ", TextRange(7), "fix it")).isEqualTo(Dictated("Please fix it", 13))
        assertThat(insertDictation("Fix the bug", TextRange(3), "login")).isEqualTo(Dictated("Fix login the bug", 9))
        assertThat(insertDictation("Fixbug", TextRange(3), "the")).isEqualTo(Dictated("Fix the bug", 7))
        // Before closing punctuation no space is added.
        assertThat(insertDictation("Do it.", TextRange(5), "now")).isEqualTo(Dictated("Do it now.", 9))
    }

    @Test
    fun `dictation replaces a selection`() {
        assertThat(insertDictation("Fix the old bug", TextRange(8, 11), "new")).isEqualTo(Dictated("Fix the new bug", 11))
        assertThat(insertDictation("Fix the old bug", TextRange(11, 8), "new")).isEqualTo(Dictated("Fix the new bug", 11))
    }

    @Test
    fun `nothing dictated changes nothing`() {
        assertThat(insertDictation("Keep", TextRange(2), "   ")).isEqualTo(Dictated("Keep", 2))
    }

    private class FakeCapture(
        var clip: RecordedClip? = RecordedClip(byteArrayOf(1, 2, 3), "audio/webm", 2_000),
        val failStart: Boolean = false,
    ) : AudioCapture {
        var started = false
        var stopped = false
        var cancelled = false
        var level = 0.5f
        override fun start() {
            if (failStart) throw IllegalStateException("busy")
            started = true
        }
        override fun level() = level
        override fun stop(): RecordedClip? { stopped = true; return clip }
        override fun cancel() { cancelled = true }
    }

    private class FakeTranscription : TranscriptionApi {
        val calls = mutableListOf<Pair<ByteArray, String>>()
        var answer: suspend () -> Transcription = { Transcription(" fix the login ", 812) }
        override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?): Transcription {
            calls += audio to mimeType
            return answer()
        }
    }

    private class Harness(scope: TestScope, val capture: FakeCapture = FakeCapture()) {
        val transcription = FakeTranscription()
        val transcripts = mutableListOf<String>()
        val haptics = mutableListOf<Haptic>()
        val voice = VoiceInput(
            scope.backgroundScope,
            transcription,
            newCapture = { capture },
            clock = { scope.testScheduler.currentTime },
            io = StandardTestDispatcher(scope.testScheduler),
        ).apply {
            onTranscript = { transcripts += it }
            onFeedback = { haptics += it }
        }
    }

    @Test
    fun `a dictation records, transcribes and hands the trimmed words back without sending`() = runTest {
        val h = Harness(this)
        h.voice.start()
        assertThat(h.voice.state).isInstanceOf(VoiceState.Recording::class.java)
        assertThat(h.capture.started).isTrue()
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(h.voice.elapsedMillis).isAtLeast(900)
        assertThat(h.voice.levels.last()).isEqualTo(0.5f)

        h.voice.stop()
        assertThat(h.voice.state).isEqualTo(VoiceState.Transcribing)
        runCurrent()
        assertThat(h.voice.state).isEqualTo(VoiceState.Idle)
        assertThat(h.transcripts).containsExactly("fix the login")
        assertThat(h.transcription.calls.single().second).isEqualTo("audio/webm")
        assertThat(h.transcription.calls.single().first.toList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(h.haptics).containsExactly(Haptic.ToggleOn, Haptic.ToggleOff, Haptic.Confirm).inOrder()
    }

    @Test
    fun `a clip under half a second is dropped without a request`() = runTest {
        val h = Harness(this, FakeCapture(clip = RecordedClip(byteArrayOf(1), "audio/webm", VoiceInput.MinClipMillis - 1)))
        h.voice.start()
        h.voice.stop()
        runCurrent()
        assertThat(h.voice.state).isEqualTo(VoiceState.Idle)
        assertThat(h.transcription.calls).isEmpty()
        assertThat(h.transcripts).isEmpty()
    }

    @Test
    fun `a recording stops itself at five minutes and is transcribed`() = runTest {
        val h = Harness(this)
        h.voice.start()
        advanceTimeBy(VoiceInput.MaxRecordingMillis + 200)
        runCurrent()
        assertThat(h.capture.stopped).isTrue()
        assertThat(h.transcripts).containsExactly("fix the login")
    }

    @Test
    fun `cancel while recording throws the clip away`() = runTest {
        val h = Harness(this)
        h.voice.start()
        h.voice.cancel()
        runCurrent()
        assertThat(h.capture.cancelled).isTrue()
        assertThat(h.voice.state).isEqualTo(VoiceState.Idle)
        assertThat(h.transcription.calls).isEmpty()
        assertThat(h.haptics.last()).isEqualTo(Haptic.ToggleOff)
    }

    @Test
    fun `cancel while transcribing drops the words`() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Transcription>()
        h.transcription.answer = { gate.await() }
        h.voice.start()
        h.voice.stop()
        runCurrent()
        assertThat(h.voice.state).isEqualTo(VoiceState.Transcribing)
        h.voice.cancel()
        gate.complete(Transcription("too late", null))
        runCurrent()
        assertThat(h.voice.state).isEqualTo(VoiceState.Idle)
        assertThat(h.transcripts).isEmpty()
    }

    @Test
    fun `a microphone that will not open is an error, not a recording`() = runTest {
        val h = Harness(this, FakeCapture(failStart = true))
        h.voice.start()
        val state = h.voice.state as VoiceState.Failed
        assertThat(state.message).contains("microphone")
        assertThat(h.haptics).containsExactly(Haptic.Reject)
    }

    @Test
    fun `a failed transcription says so and clears after a few seconds`() = runTest {
        val h = Harness(this)
        h.transcription.answer = { throw ConnectRpcException(503, "unavailable", "down") }
        h.voice.start()
        h.voice.stop()
        runCurrent()
        assertThat((h.voice.state as VoiceState.Failed).message).isEqualTo("Cursor couldn't transcribe that (unavailable).")
        assertThat(h.haptics.last()).isEqualTo(Haptic.Reject)
        advanceTimeBy(6_500)
        runCurrent()
        assertThat(h.voice.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun `no words heard is an error rather than an empty insert`() = runTest {
        val h = Harness(this)
        h.transcription.answer = { Transcription("   ", null) }
        h.voice.start()
        h.voice.stop()
        runCurrent()
        assertThat(h.voice.state).isInstanceOf(VoiceState.Failed::class.java)
        assertThat(h.transcripts).isEmpty()
    }

    @Test
    fun `a permission refused for good offers Settings`() = runTest {
        val h = Harness(this)
        h.voice.permissionDenied(permanently = true)
        assertThat(h.voice.state).isEqualTo(VoiceState.Failed("Microphone access is off. Tap to open Settings.", opensSettings = true))
        h.voice.permissionDenied(permanently = false)
        assertThat(h.voice.state).isEqualTo(VoiceState.Failed("Voice input needs microphone access.", opensSettings = false))
        assertThat(h.capture.started).isFalse()
    }

    @Test
    fun `errors read in the footer's words`() {
        assertThat(VoiceInput.messageFor(ConnectRpcException(401, "unauthenticated", "no"))).isEqualTo("Cursor didn't accept the session. Sign in again.")
        assertThat(VoiceInput.messageFor(ConnectRpcException(429, "resource_exhausted", "slow"))).isEqualTo("Too many transcriptions just now. Try again shortly.")
        assertThat(VoiceInput.messageFor(ConnectRpcException(413, null, "big"))).isEqualTo("That recording is too long to transcribe.")
        assertThat(VoiceInput.messageFor(ConnectRpcException(500, null, "boom"))).isEqualTo("Cursor couldn't transcribe that (HTTP 500).")
        assertThat(VoiceInput.messageFor(IOException("reset"))).isEqualTo("Couldn't reach Cursor. Check the connection and try again.")
        assertThat(VoiceInput.messageFor(SessionUnavailableException("Extended mode is off, so Cursor's account service isn't used.")))
            .isEqualTo("Extended mode is off, so Cursor's account service isn't used.")
    }

    @Test
    fun `the meter is 0 in a quiet room and 1 at full scale`() {
        assertThat(MediaRecorderCapture.normalizedLevel(0)).isEqualTo(0f)
        assertThat(MediaRecorderCapture.normalizedLevel(100)).isEqualTo(0f)
        assertThat(MediaRecorderCapture.normalizedLevel(32_767)).isEqualTo(1f)
        assertThat(MediaRecorderCapture.normalizedLevel(2_000)).isIn(com.google.common.collect.Range.open(0.4f, 0.6f))
    }

    @Test
    fun `the timer reads minutes and seconds`() {
        assertThat(formatElapsed(0)).isEqualTo("0:00")
        assertThat(formatElapsed(7_900)).isEqualTo("0:07")
        assertThat(formatElapsed(125_000)).isEqualTo("2:05")
    }
}
