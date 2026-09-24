package com.cursorforandroid.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.abs
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

    /** A dragged item is set down; a back gesture commits; a sheet a swipe opened or closed lands ([SheetLanding]). */
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
 * Plays [Haptic]s on a view. The system's touch feedback switch is the only one there is: the view is never asked to
 * ignore it (`FLAG_IGNORE_GLOBAL_SETTING`, which Android 13 reserves for privileged apps anyway), and a view whose
 * haptics are disabled plays nothing.
 */
@Stable
class Haptics internal constructor(private val view: View) {
    /** Plays [haptic]; answers whether the view played it. */
    fun perform(haptic: Haptic): Boolean = view.isHapticFeedbackEnabled && view.performHapticFeedback(haptic.constant())

    /** A switch just flipped to [on]. */
    fun toggle(on: Boolean): Boolean = perform(if (on) Haptic.ToggleOn else Haptic.ToggleOff)
}

/** The [Haptics] of the view this is composed in. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/**
 * Plays the two platform locals that have haptics of their own through [Haptics]: Compose's [LocalHapticFeedback]
 * (text selection handles, and every long press that asks it) and [LocalClipboardManager], whose every copy is felt as
 * a [Haptic.Confirm]. The app has no copy that is not the reader's own tap, so the clipboard is where a copy is felt,
 * rather than at each of the places that copy.
 */
@Composable
fun ProvideHaptics(content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val clipboard = LocalClipboardManager.current
    val feedback = remember(haptics) { ViewHapticFeedback(haptics) }
    val felt = remember(clipboard, haptics) { if (clipboard is FeltClipboard) clipboard else FeltClipboard(clipboard, haptics) }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, LocalClipboardManager provides felt, content = content)
}

/** Compose's haptic requests, played through [Haptics] so they take the app's constants. */
internal class ViewHapticFeedback(private val haptics: Haptics) : HapticFeedback {
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
 * The one haptic a sheet — the sidebar drawer, the side panel — plays each time a gesture opens or closes it: a
 * [Haptic.GestureEnd] as the sheet lands, fully open or fully shut, on the other side from where it rested. Where the
 * sheet is decides it, not how far the finger went: a short flick whose fling carries the sheet the rest of the way
 * lands like a slow drag all the way across, and a drag let go short of committing springs back without a sound
 * however far it went, as does one taken past half-way and back. A sheet moved without a finger (a button, a
 * shortcut, a back gesture, which is felt as it commits: [feltOnCommit]) plays nothing. The side panel pinned beside
 * the chat has no swipe, so the one way a hand moves it, its button's slide, lands here as a release would.
 *
 * A sheet caught on its way and sent on the same way still lands once. Caught and sent back, it lands, felt, where it
 * started: the release had committed it, and the one that sends it back commits it again.
 */
class SheetLanding {
    /** The end, 0 or 1, a release committed the sheet to that it has not reached yet; NaN for none. */
    private var heading = Float.NaN

    /** A finger has moved the sheet since the last release. */
    private var held = false

    /** A finger moved the sheet from [before]. A gesture that takes it from rest forgets a landing a slide or a jump took over. */
    fun dragged(before: Float) {
        if (!held && (before == 0f || before == 1f)) heading = Float.NaN
        held = true
    }

    /** The finger let go of a sheet committed to [wasOpen] (or shut), and it is settling [open] (or shut). */
    fun released(wasOpen: Boolean, open: Boolean) {
        held = false
        val end = if (open) 1f else 0f
        heading = if (open != wasOpen || heading == end) end else Float.NaN
    }

    /** The sheet is at [fraction] of the way open: the haptic, if that is it landing where a release sent it. */
    fun at(fraction: Float): Haptic? {
        if (heading.isNaN() || abs(fraction - heading) > LandedWithin) return null
        heading = Float.NaN
        return Haptic.GestureEnd
    }
}

/** How near its end a settling sheet has landed: the spring's last stretch is too slight to see, and is not waited for. */
private const val LandedWithin = 0.01f

/**
 * A predictive back gesture's events, with its commit felt as a [Haptic.GestureEnd]: the flow completing after the
 * finger scrubbed it at least once. A cancelled gesture plays nothing, and neither does a back that was never scrubbed
 * (3-button navigation's back key, which the system already clicks).
 */
fun Flow<BackEventCompat>.feltOnCommit(haptics: Haptics): Flow<BackEventCompat> {
    var scrubbed = false
    return onEach { scrubbed = true }.onCompletion { cause -> if (cause == null && scrubbed) haptics.perform(Haptic.GestureEnd) }
}
