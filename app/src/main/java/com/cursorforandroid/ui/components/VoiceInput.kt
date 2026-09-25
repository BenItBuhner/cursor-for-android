package com.cursorforandroid.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.MediaRecorderCapture
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a dictation stands; anything but [Idle] shows in the composer's footer ([VoiceStatus]). */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Recording(val startedAtMillis: Long) : VoiceState
    data object Transcribing : VoiceState
    /** [opensSettings]: the microphone permission is off for good, and the message opens the app's settings page. */
    data class Failed(val message: String, val opensSettings: Boolean = false) : VoiceState
}

/** What the composer's send slot holds. */
enum class SendSlot { CancelSend, Busy, Stop, Send, Mic }

/** The send slot, and whether the microphone sits just left of it. */
data class ComposerButtons(val main: SendSlot, val micBeside: Boolean)

/**
 * The send slot as a function of the composer, with voice input on or off. Off, it is what it always was. On, the
 * microphone joins whatever the slot holds: beside Send while there is something to send, beside Stop while the agent
 * runs, and — with nothing typed or attached and nothing running — the white disc itself is the microphone, so an
 * empty composer offers one main action instead of a Send that cannot be pressed. A prompt in flight has no microphone.
 */
fun composerButtons(
    isSending: Boolean,
    cancelOffered: Boolean,
    canStop: Boolean,
    canSend: Boolean,
    hasContent: Boolean,
    voice: Boolean,
): ComposerButtons = when {
    isSending && cancelOffered -> ComposerButtons(SendSlot.CancelSend, micBeside = false)
    isSending -> ComposerButtons(SendSlot.Busy, micBeside = false)
    canStop && !canSend -> ComposerButtons(SendSlot.Stop, micBeside = voice)
    voice && !hasContent && !canSend -> ComposerButtons(SendSlot.Mic, micBeside = false)
    else -> ComposerButtons(SendSlot.Send, micBeside = voice)
}

/** The field's text with a dictation put in, and where the caret goes after it. */
data class Dictated(val text: String, val cursor: Int)

/**
 * Puts [dictated] into [text] over [selection] — at the caret, or in place of the selected words — with a space
 * either side where it would otherwise run into a word, and the caret after it, so the next dictation or keystroke
 * carries on the sentence.
 */
fun insertDictation(text: String, selection: TextRange, dictated: String): Dictated {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    val words = dictated.trim()
    if (words.isEmpty()) return Dictated(text, end)
    val before = text.getOrNull(start - 1)
    val after = text.getOrNull(end)
    val lead = if (before != null && !before.isWhitespace()) " " else ""
    val trail = if (after != null && !after.isWhitespace() && after !in ClosingPunctuation) " " else ""
    val piece = lead + words + trail
    return Dictated(text.substring(0, start) + piece + text.substring(end), start + lead.length + words.length)
}

private const val ClosingPunctuation = ".,;:!?)]}\"'"

/**
 * One composer's dictation: [start] opens the microphone ([Recording], its [levels] and [elapsedMillis] moving),
 * [stop] closes it and sends the clip to Cursor's `TranscribeAudio` ([Transcribing]), and the words come back through
 * [onTranscript] for the composer to put in at the caret — never sent by themselves. [cancel] throws the recording or
 * the request away. As on the desktop, a clip under half a second is dropped without a request, and one that reaches
 * five minutes stops itself and is transcribed.
 */
@Stable
class VoiceInput(
    private val scope: CoroutineScope,
    private val transcription: TranscriptionApi,
    private val newCapture: () -> AudioCapture,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Where the recorder is stopped and its file read back; the tests' own dispatcher there. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set
    /** The most recent meter readings, oldest first, for the footer's bars. */
    val levels: SnapshotStateList<Float> = mutableStateListOf<Float>().apply { repeat(LevelHistory) { add(0f) } }
    var elapsedMillis: Long by mutableLongStateOf(0L)
        private set

    /** The words of a finished dictation; set by the composer that owns this. */
    var onTranscript: (String) -> Unit = {}
    /** A haptic for each turn the dictation takes by itself or at a tap; set by the composer. */
    var onFeedback: (Haptic) -> Unit = {}

    private var capture: AudioCapture? = null
    private var job: Job? = null

    val isRecording: Boolean get() = state is VoiceState.Recording

    fun start() {
        if (state is VoiceState.Recording || state is VoiceState.Transcribing) return
        job?.cancel()
        val next = newCapture()
        try {
            next.start()
        } catch (e: Exception) {
            fail("Couldn't open the microphone. Another app may be using it.")
            return
        }
        capture = next
        levels.indices.forEach { levels[it] = 0f }
        val startedAt = clock()
        elapsedMillis = 0L
        state = VoiceState.Recording(startedAt)
        onFeedback(Haptic.ToggleOn)
        job = scope.launch {
            while (isActive) {
                delay(LevelTickMillis)
                elapsedMillis = clock() - startedAt
                levels.removeAt(0)
                levels.add(next.level())
                if (elapsedMillis >= MaxRecordingMillis) {
                    stop()
                    break
                }
            }
        }
    }

    /** Ends a recording and transcribes it; does nothing unless one is under way. */
    fun stop() {
        val recording = capture ?: return
        if (state !is VoiceState.Recording) return
        capture = null
        job?.cancel()
        state = VoiceState.Transcribing
        onFeedback(Haptic.ToggleOff)
        job = scope.launch {
            try {
                val clip = withContext(io) { recording.stop() }
                if (clip == null || clip.durationMillis < MinClipMillis) {
                    state = VoiceState.Idle
                    return@launch
                }
                val words = transcription.transcribe(clip.audio, clip.mimeType, language = null).text.trim()
                if (words.isEmpty()) {
                    fail("Didn't catch any words. Try again.")
                    return@launch
                }
                state = VoiceState.Idle
                onTranscript(words)
                onFeedback(Haptic.Confirm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(messageFor(e))
            }
        }
    }

    /** Throws away a recording or a transcription under way, or dismisses an error. */
    fun cancel() {
        val wasActive = state is VoiceState.Recording || state is VoiceState.Transcribing
        job?.cancel()
        job = null
        capture?.let { runCatching { it.cancel() } }
        capture = null
        state = VoiceState.Idle
        elapsedMillis = 0L
        if (wasActive) onFeedback(Haptic.ToggleOff)
    }

    /** The microphone permission was refused; [permanently] when Android will no longer ask, so only Settings can grant it. */
    fun permissionDenied(permanently: Boolean) {
        fail(
            if (permanently) "Microphone access is off. Tap to open Settings." else "Voice input needs microphone access.",
            opensSettings = permanently,
        )
    }

    /** Shows [state] as it is, with no microphone or request behind it: for the screenshot tests. */
    internal fun show(state: VoiceState, levels: List<Float> = emptyList(), elapsedMillis: Long = 0L) {
        this.state = state
        this.elapsedMillis = elapsedMillis
        val padded = List((LevelHistory - levels.size).coerceAtLeast(0)) { 0f } + levels.takeLast(LevelHistory)
        padded.forEachIndexed { i, level -> this.levels[i] = level }
    }

    private fun fail(message: String, opensSettings: Boolean = false) {
        state = VoiceState.Failed(message, opensSettings)
        onFeedback(Haptic.Reject)
        job = scope.launch {
            delay(ErrorShownMillis)
            if (state is VoiceState.Failed) state = VoiceState.Idle
        }
    }

    companion object {
        const val MinClipMillis = 500L
        const val MaxRecordingMillis = 300_000L
        internal const val LevelHistory = 48
        private const val LevelTickMillis = 80L
        private const val ErrorShownMillis = 6_000L

        /** A failed transcription in the footer's few words; the server's own text only when it names the problem. */
        internal fun messageFor(e: Throwable): String = when {
            e is SessionUnavailableException -> e.message ?: "Couldn't start a Cursor session."
            e is ConnectRpcException && e.isUnauthenticated -> "Cursor didn't accept the session. Sign in again."
            e is ConnectRpcException && e.isRateLimited -> "Too many transcriptions just now. Try again shortly."
            e is ConnectRpcException && e.httpCode == 413 -> "That recording is too long to transcribe."
            e is ConnectRpcException -> "Cursor couldn't transcribe that (${e.code ?: "HTTP ${e.httpCode}"})."
            e is IOException -> "Couldn't reach Cursor. Check the connection and try again."
            else -> "Transcription failed. Try again."
        }
    }
}

/** A [VoiceInput] for a composer while [transcription] is given (voice input on); null otherwise. */
@Composable
fun rememberVoiceInput(transcription: TranscriptionApi?): VoiceInput? {
    if (transcription == null) return null
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val voice = remember(transcription) { VoiceInput(scope, transcription, { MediaRecorderCapture(context) }) }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    return voice
}

/**
 * Wires [voice] to its composer: the words go to [onTranscript], the haptics to [haptics], a recording left running
 * when the app goes to the background is stopped and transcribed (Android would only feed it silence), and the returned
 * tap asks for the microphone first when it has not been granted.
 */
@Composable
internal fun rememberMicTap(voice: VoiceInput, haptics: Haptics, onTranscript: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestTranscript by rememberUpdatedState(onTranscript)
    SideEffect {
        voice.onTranscript = { latestTranscript(it) }
        voice.onFeedback = { haptics.perform(it) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(voice, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voice.start()
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            voice.permissionDenied(permanently = !canAskAgain)
        }
    }
    return remember(voice, permission) {
        {
            when (val state = voice.state) {
                is VoiceState.Recording -> voice.stop()
                VoiceState.Transcribing -> Unit
                is VoiceState.Failed -> if (state.opensSettings) openAppSettings(context) else startOrAsk(context, voice) { permission.launch(it) }
                VoiceState.Idle -> startOrAsk(context, voice) { permission.launch(it) }
            }
        }
    }
}

private fun startOrAsk(context: Context, voice: VoiceInput, ask: (String) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start()
    else ask(Manifest.permission.RECORD_AUDIO)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The microphone in the send slot or beside it: the composer's round button with the mic glyph ([prominent] as the
 * white main disc, the 8 % fill beside Send or Stop). Recording, it is a red disc whose halo swells with the voice;
 * transcribing, a spinner in the same disc.
 */
@Composable
internal fun VoiceMicButton(voice: VoiceInput, prominent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    when (voice.state) {
        is VoiceState.Recording -> {
            val level by animateFloatAsState(voice.levels.lastOrNull() ?: 0f, tween(90), label = "micLevel")
            val halo = colors.red
            TouchTarget(size = CursorDimens.roundButton, touchSize = 40.dp, shape = CircleShape, onClick = onClick, modifier = modifier.testTag("voice-mic")) {
                Box(
                    Modifier
                        .size(CursorDimens.roundButton)
                        .drawBehind {
                            val radius = size.minDimension / 2
                            drawCircle(halo.copy(alpha = 0.28f), radius = radius + 1.dp.toPx() + level * 5.dp.toPx())
                        }
                        .background(colors.red, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CursorIcons.Mic, "Stop recording", tint = Color.White, modifier = Modifier.size(CursorDimens.roundButtonGlyph))
                }
            }
        }
        VoiceState.Transcribing -> Box(
            modifier
                .testTag("voice-mic")
                .size(CursorDimens.roundButton)
                .background(if (prominent) colors.textPrimary else colors.fill, CircleShape)
                .semantics { contentDescription = "Transcribing" },
            contentAlignment = Alignment.Center,
        ) {
            SpinnerRing(color = if (prominent) colors.canvas else colors.iconSecondary, size = CursorDimens.roundButtonGlyph, strokeWidth = 1.5.dp)
        }
        else -> ComposerRoundButton(CursorIcons.Mic, "Voice input", onClick = onClick, prominent = prominent, modifier = modifier.testTag("voice-mic"))
    }
}

/**
 * The footer's middle while a dictation is under way, in place of the pills and the model chip: a pulsing red dot,
 * the time, and the voice's level as bars while recording; "Transcribing…" after; the problem when one came up. The
 * cross at the end cancels ([VoiceInput.cancel]).
 */
@Composable
internal fun RowScope.VoiceStatus(voice: VoiceInput, onTap: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val state = voice.state
    Row(Modifier.weight(1f).testTag("voice-status"), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is VoiceState.Recording -> {
                val pulse = rememberInfiniteTransition(label = "recDot")
                val dotAlpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recDotAlpha")
                Box(Modifier.size(7.dp).alpha(dotAlpha).background(colors.red, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(formatElapsed(voice.elapsedMillis), style = type.small, color = colors.textSecondary, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                VoiceLevelBars(voice.levels, colors.textSecondary, Modifier.weight(1f).height(16.dp))
            }
            VoiceState.Transcribing -> {
                SpinnerRing(size = 11.dp)
                Spacer(Modifier.width(7.dp))
                Text("Transcribing…", style = type.small, color = colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            }
            is VoiceState.Failed -> Text(
                state.message,
                style = type.small,
                color = colors.red,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(if (state.opensSettings) Modifier.clickable(onClick = onTap) else Modifier)
                    .testTag("voice-error"),
            )
            VoiceState.Idle -> Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(6.dp))
        ComposerRoundButton(CursorIcons.Close, if (state is VoiceState.Failed) "Dismiss" else "Cancel voice input", onClick = { voice.cancel() }, modifier = Modifier.testTag("voice-cancel"))
    }
}

/** The recent levels as thin rounded bars, newest on the right, as many as the width holds. */
@Composable
private fun VoiceLevelBars(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.padding(horizontal = 2.dp)) {
        val bar = 2.dp.toPx()
        val step = bar + 2.dp.toPx()
        val count = (size.width / step).toInt().coerceAtMost(levels.size)
        val floor = 2.dp.toPx()
        val shown = levels.takeLast(count)
        shown.forEachIndexed { i, level ->
            val h = floor + (size.height - floor) * level.coerceIn(0f, 1f)
            val x = size.width - (shown.size - i) * step
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

internal fun formatElapsed(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
