package com.cursorforandroid.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

/**
 * The moments the app marks with a haptic, named for what happened rather than for the constant that plays it
 * ([constant] picks the platform's own, newest first). One UI and Pixel keep touch feedback to a few crisp clicks where
 * something commits; nothing here plays while a list scrolls or a stream writes.
 */
enum class Haptic {
    /** A press held long enough to open a menu. */
    LongPress,

    /** An action went through: a message sent or queued, a run stopped, something copied. */
    Confirm,

    /** Something did not go through: a send that failed, a run that ended in an error, a launch refused. */
    Reject,

    /** A held item lifts to be dragged. */
    DragStart,

    /** A dragged item takes the next slot. */
    SlotTick,

    /** A dragged item is set down; a back gesture commits. */
    GestureEnd,

    /** A drag crosses the point past which letting go commits it. */
    ThresholdActivate,

    /** The same drag back under that point, where letting go would no longer commit. */
    ThresholdDeactivate,

    ToggleOn,
    ToggleOff,

    /** A choice picked from a list: a slash command. */
    Select,

    /** A text selection handle moving by a character, as Compose's text fields ask for it. */
    TextHandleMove,

    /** The lightest there is: a run finishing in the chat on screen. */
    Subtle,
}

// Every newer constant is behind a check of `sdk`, which lint's InlinedApi does not follow for a parameter.
/** The [HapticFeedbackConstants] value [Haptic] plays on [sdk]: API 34's and API 30's named constants where they exist, the nearest older one before. */
@SuppressLint("InlinedApi")
fun Haptic.constant(sdk: Int = Build.VERSION.SDK_INT): Int = when (this) {
    Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
    Haptic.Confirm -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    Haptic.Reject -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    Haptic.DragStart -> when {
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.DRAG_START
        sdk >= Build.VERSION_CODES.R -> HapticFeedbackConstants.GESTURE_START
        else -> HapticFeedbackConstants.LONG_PRESS
    }
    Haptic.SlotTick -> when {
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.SEGMENT_TICK
        sdk >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    Haptic.GestureEnd -> when {
        sdk >= Build.VERSION_CODES.R -> HapticFeedbackConstants.GESTURE_END
        sdk >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    }
    Haptic.ThresholdActivate ->
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.ThresholdDeactivate ->
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    Haptic.ToggleOn -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.ToggleOff -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
    Haptic.Select -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.TextHandleMove -> if (sdk >= Build.VERSION_CODES.O_MR1) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK
    Haptic.Subtle -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
}

/**
 * Plays [Haptic]s on a view, unless Settings › Haptic feedback is off. The system's own touch feedback switch has the
 * last word either way: the view is never asked to ignore it (`FLAG_IGNORE_GLOBAL_SETTING`, which Android 13 reserves
 * for privileged apps anyway), and a view whose haptics are disabled plays nothing.
 */
@Stable
class Haptics internal constructor(private val view: View, private val enabled: () -> Boolean) {
    /** Plays [haptic] if the app's setting allows it; answers whether the view played it. */
    fun perform(haptic: Haptic): Boolean = enabled() && play(haptic)

    /** Plays [haptic] whatever the app's setting says: only for that setting's own switch, felt as it turns on. */
    internal fun preview(haptic: Haptic): Boolean = play(haptic)

    /** A switch just flipped to [on]. */
    fun toggle(on: Boolean): Boolean = perform(if (on) Haptic.ToggleOn else Haptic.ToggleOff)

    private fun play(haptic: Haptic): Boolean = view.isHapticFeedbackEnabled && view.performHapticFeedback(haptic.constant())
}

/** Settings › Haptic feedback, as the theme at the root of each window provides it (see [ProvideHaptics]). On where nothing provides it. */
val LocalHapticsEnabled = compositionLocalOf { true }

/** The [Haptics] of the view this is composed in, following Settings › Haptic feedback as it changes. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = rememberUpdatedState(LocalHapticsEnabled.current)
    return remember(view, enabled) { Haptics(view) { enabled.value } }
}

/**
 * Provides Settings › Haptic feedback to everything under it, and puts the two platform locals that play haptics of
 * their own behind the same setting: Compose's [LocalHapticFeedback] (text selection handles, and every long press that
 * asks it) and [LocalClipboardManager], whose every copy is felt as a [Haptic.Confirm]. The app has no copy that is not
 * the reader's own tap, so the clipboard is where a copy is felt, rather than at each of the places that copy.
 */
@Composable
fun ProvideHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHapticsEnabled provides enabled) {
        val haptics = rememberHaptics()
        val clipboard = LocalClipboardManager.current
        val feedback = remember(haptics) { SettingHapticFeedback(haptics) }
        val felt = remember(clipboard, haptics) { if (clipboard is FeltClipboard) clipboard else FeltClipboard(clipboard, haptics) }
        CompositionLocalProvider(LocalHapticFeedback provides feedback, LocalClipboardManager provides felt, content = content)
    }
}

/** Compose's haptic requests, played through [Haptics] so they follow the app's setting. */
internal class SettingHapticFeedback(private val haptics: Haptics) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        when (hapticFeedbackType) {
            HapticFeedbackType.LongPress -> haptics.perform(Haptic.LongPress)
            HapticFeedbackType.TextHandleMove -> haptics.perform(Haptic.TextHandleMove)
        }
    }
}

/** The clipboard, with each copy onto it felt. Reading it is the platform's, untouched. */
internal class FeltClipboard(private val clipboard: ClipboardManager, private val haptics: Haptics) : ClipboardManager by clipboard {
    override fun setText(annotatedString: AnnotatedString) {
        clipboard.setText(annotatedString)
        haptics.perform(Haptic.Confirm)
    }

    override fun setClip(clipEntry: ClipEntry?) {
        clipboard.setClip(clipEntry)
        haptics.perform(Haptic.Confirm)
    }
}

/**
 * Whether a sheet that rests [restingOpen] (or shut) and has been dragged to [fraction] of the way open would change
 * sides if let go now, as the drawer and the side panel settle a drag with no fling: past half-way, it would.
 */
fun pastHalfway(fraction: Float, restingOpen: Boolean): Boolean = (fraction >= 0.5f) != restingOpen

/** The threshold haptic a drag from [before] to [after] of the way open plays, if it crossed half-way either way; see [pastHalfway]. */
fun halfwayCrossing(before: Float, after: Float, restingOpen: Boolean): Haptic? {
    val was = pastHalfway(before, restingOpen)
    val now = pastHalfway(after, restingOpen)
    return when {
        was == now -> null
        now -> Haptic.ThresholdActivate
        else -> Haptic.ThresholdDeactivate
    }
}

/**
 * A predictive back gesture's events, with its commit felt as a [Haptic.GestureEnd]: the flow completing after the
 * finger scrubbed it at least once. A cancelled gesture plays nothing, and neither does a back that was never scrubbed
 * (3-button navigation's back key, which the system already clicks).
 */
fun Flow<BackEventCompat>.feltOnCommit(haptics: Haptics): Flow<BackEventCompat> {
    var scrubbed = false
    return onEach { scrubbed = true }.onCompletion { cause -> if (cause == null && scrubbed) haptics.perform(Haptic.GestureEnd) }
}
