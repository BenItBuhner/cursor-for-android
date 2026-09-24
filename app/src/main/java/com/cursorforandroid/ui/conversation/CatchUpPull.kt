package com.cursorforandroid.ui.conversation

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Where the reader's pull to catch up stands, from the pull to its answer (see [ConversationViewModel.catchUp]). */
sealed interface CatchUpStatus {
    /** Nothing asked, or the last answer put away. */
    data object Idle : CatchUpStatus
    /** The server asked the account's calls to pause (a `429`, see `ApiThrottle`): the pull is answered at [untilMillis], on [AppClock]. */
    data class Waiting(val untilMillis: Long) : CatchUpStatus
    data object Checking : CatchUpStatus
    /** Answered: [newMessages] the screen did not have; [changed] when anything else moved. */
    data class Done(val newMessages: Int, val changed: Boolean) : CatchUpStatus
    /** The read failed; [message] is the server's own words. */
    data class Failed(val message: String) : CatchUpStatus
}

/**
 * The reader's pull past the newest message: how far the finger has travelled past the transcript's bottom edge
 * ([distance]), armed once that passes [thresholdPx]. Fed by [CatchUpOverscroll]. What the pull draws out is the tab
 * under the composer's top edge (see [CatchUpIndicator]), and the list is lifted clear of it: [revealPx] while the
 * finger holds, the tab's own height ([tabPx]) while the answer shows (see [rememberCatchUpReveal]).
 */
@Stable
class CatchUpPull(val thresholdPx: Float, private val maxRevealPx: Float) {
    var distance by mutableFloatStateOf(0f)
        private set
    /** A finger is on the pull: from its first pixel past the edge to its release. */
    var holding by mutableStateOf(false)
        private set
    /** How many releases were armed, and so asked for a catch-up. */
    var pulls by mutableIntStateOf(0)
        private set
    val armed: Boolean get() = holding && distance >= thresholdPx
    val progress: Float get() = (distance / thresholdPx).coerceIn(0f, 1f)
    /** How far the pull reveals: the finger's own travel at first, so the tab comes out with it, then slower and slower, never past [maxRevealPx]. */
    val revealPx: Float get() = if (holding) maxRevealPx * (1f - exp(-distance / maxRevealPx)) else 0f
    /** The part of the tab that stands over the composer once it is all out; measured by [CatchUpIndicator], guessed until it has been. */
    var tabPx by mutableFloatStateOf(maxRevealPx * (1f - exp(-1f)))
        internal set

    /** How far the pull stands revealed with [status] the pull's answer: with the finger, then the whole tab, then none. */
    fun liftPx(status: CatchUpStatus): Float = when {
        holding -> revealPx
        status !is CatchUpStatus.Idle -> tabPx
        else -> 0f
    }

    fun stretch(px: Float) {
        holding = true
        distance += px
    }

    fun relax(px: Float) {
        if (holding) distance = (distance - px).coerceAtLeast(0f)
    }

    /** The finger lifted: whether the pull was armed. It is let go either way. */
    fun release(): Boolean {
        val was = armed
        holding = false
        distance = 0f
        if (was) pulls++
        return was
    }
}

/**
 * How far [pull] stands revealed, in pixels: with the finger while it holds, the tab's height while [status] is an
 * answer, none otherwise, sprung between them so a release settles the tab rather than dropping it. Read it where it
 * is drawn, and the pull recomposes nothing.
 */
@Composable
internal fun rememberCatchUpReveal(pull: CatchUpPull, status: CatchUpStatus): State<Float> {
    val reveal = remember(pull) { Animatable(0f) }
    val answer = rememberUpdatedState(status)
    LaunchedEffect(pull, reveal) {
        snapshotFlow { pull.holding to pull.liftPx(answer.value) }.collectLatest { (holding, px) ->
            if (holding) reveal.snapTo(px) else reveal.animateTo(px, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    return reveal.asState()
}

/** How far the transcript is lifted to clear [revealPx] of the tab: only what [roomPx] under a transcript short enough to fit does not already clear. */
internal fun catchUpListLift(revealPx: Float, roomPx: Int): Float = (revealPx - roomPx.coerceAtLeast(0)).coerceAtLeast(0f)

/**
 * The platform's overscroll with the pull to catch up read off it: every delta goes to [platform] as it would
 * without this — so the stretch, its feel and its release are Android's own — and what the reader's drag leaves
 * over past the bottom edge (a scroll toward the newest row the list could not take) is the pull's too. Only the
 * finger's: a fling that reaches the bottom, the jump button's scroll, never arm it. [enabled] is asked at the
 * pull's first pixel: a chat still loading, or a pull already being answered, stretches without pulling.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class CatchUpOverscroll(
    private val platform: OverscrollEffect,
    private val pull: CatchUpPull,
    private val enabled: () -> Boolean,
    private val onPulled: () -> Unit,
) : OverscrollEffect {
    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (source != NestedScrollSource.UserInput) return platform.applyToScroll(delta, source, performScroll)
        // Screen terms: the finger moving up is a negative delta, and at the bottom it is left over.
        if (delta.y > 0f) pull.relax(delta.y)
        return platform.applyToScroll(delta, source) { available ->
            val consumed = performScroll(available)
            val left = available.y - consumed.y
            if (left < 0f && (pull.holding || enabled())) pull.stretch(-left)
            consumed
        }
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pull.release()) onPulled()
        platform.applyToFling(velocity, performFling)
    }

    override val isInProgress: Boolean get() = platform.isInProgress || pull.holding

    override val effectModifier: Modifier get() = platform.effectModifier
}

/**
 * The pull's indicator: a tab tucked under the composer's top edge, drawn out from beneath it by the pull, [reveal]
 * pixels of it — with the finger, so it rises as the finger does — and kept all the way out while the answer shows,
 * then sunk back under. [modifier] gives it the composer's width, at the bottom of a clipped area whose bottom edge is
 * the top of the composer's stack (the queue and goal cards docked over the box included).
 *
 * "Pull to catch up", then "Release to catch up" once armed; let go, "Catching up…", the server's pause being waited
 * out, "Up to date", "N new", or the failure in the server's own words, which a tap puts away, as it does the answer.
 * The finger feels it too ([haptics]): a tick across the threshold either way, a confirm as an armed pull is let go,
 * and a light one for the answer.
 */
@Composable
internal fun CatchUpIndicator(
    pull: CatchUpPull,
    status: CatchUpStatus,
    reveal: State<Float>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: (CatchUpHaptic) -> Unit = rememberCatchUpHaptics(),
) {
    CatchUpHapticCues(pull, status, haptics)
    val pulling by remember(pull) { derivedStateOf { pull.holding && pull.distance > 0f } }
    val armed by remember(pull) { derivedStateOf { pull.armed } }
    val out by remember(reveal) { derivedStateOf { reveal.value > 0.5f } }
    val answering = status !is CatchUpStatus.Idle
    // Sinking back under, the tab keeps the words it had: the answer it showed, or the pull's own.
    val said = remember { mutableStateOf<CatchUpStatus>(CatchUpStatus.Idle) }
    SideEffect {
        if (pulling) said.value = CatchUpStatus.Idle else if (answering) said.value = status
    }
    if (!pulling && !answering && !out) return
    val shown = when {
        pulling -> CatchUpStatus.Idle
        answering -> status
        else -> said.value
    }
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val tuckPx = with(LocalDensity.current) { CatchUpTabTuck.toPx() }
    val dismissable = !pulling && (status is CatchUpStatus.Failed || status is CatchUpStatus.Done)
    val shape = remember { RoundedCornerShape(CatchUpTabTuck) }
    Box(modifier) {
        Row(
            Modifier
                .padding(horizontal = CatchUpTabInset)
                .fillMaxWidth()
                .onSizeChanged { pull.tabPx = (it.height - tuckPx).coerceAtLeast(0f) }
                // Sunk below the clip but for what is revealed, and never past the tuck: the tab's lower corners and
                // stroke stay under the composer's edge, so it reads as coming out from beneath it.
                .graphicsLayer { translationY = size.height - reveal.value.coerceIn(0f, (size.height - tuckPx).coerceAtLeast(0f)) }
                .cursorSurface(colors.elevated, if (armed && pulling) colors.strokeStrong else colors.strokeSubtle, shape)
                .pressable(onDismiss, shape, enabled = dismissable, role = if (dismissable) Role.Button else null)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(CATCH_UP_TEST_TAG)
                .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 9.dp + CatchUpTabTuck),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (shown) {
                is CatchUpStatus.Idle -> {
                    Icon(
                        if (armed) CursorIcons.Refresh else CursorIcons.ArrowUp,
                        null,
                        tint = if (armed) colors.textPrimary else colors.iconTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(if (armed) "Release to catch up" else "Pull to catch up", style = type.small, color = if (armed) colors.textPrimary else colors.textTertiary)
                }
                is CatchUpStatus.Checking -> {
                    SpinnerRing(size = 12.dp)
                    Spacer(Modifier.width(7.dp))
                    Text("Catching up…", style = type.small, color = colors.textSecondary)
                }
                is CatchUpStatus.Waiting -> {
                    val seconds by produceState(secondsLeft(shown.untilMillis), shown) {
                        while (value > 0) {
                            delay(1_000)
                            value = secondsLeft(shown.untilMillis)
                        }
                    }
                    Icon(CursorIcons.Clock, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Cursor asked for a pause · catching up in $seconds s", style = type.small, color = colors.textSecondary)
                }
                is CatchUpStatus.Done -> {
                    Icon(CursorIcons.Check, null, tint = if (shown.newMessages > 0) colors.green else colors.iconTertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(shown.label(), style = type.small, color = if (shown.newMessages > 0) colors.textPrimary else colors.textSecondary)
                }
                is CatchUpStatus.Failed -> {
                    Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(shown.message, style = type.small, color = colors.red, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The answer's words: the new messages counted, else whether anything moved at all. */
internal fun CatchUpStatus.Done.label(): String = when {
    newMessages > 0 -> "$newMessages new"
    changed -> "Updated"
    else -> "Up to date"
}

private fun secondsLeft(untilMillis: Long): Int = ((untilMillis - AppClock.now()) / 1_000.0).roundToInt().coerceAtLeast(0)

/** What the pull says through the finger. */
internal enum class CatchUpHaptic {
    /** The pull crossed the threshold under the finger: let go now and it catches up. */
    Armed,
    /** Back under it before the release. */
    Disarmed,
    /** An armed pull let go: the catch-up is asked for. */
    Released,
    /** The answer came: new messages or none. */
    Answered,
    /** The answer is a failure. */
    Failed,
}

/** The platform's constant for this cue on [sdk]: the gesture-threshold pair from 34 and a clock tick before it, CONFIRM and REJECT from 30. */
@SuppressLint("InlinedApi")
internal fun CatchUpHaptic.feedback(sdk: Int = Build.VERSION.SDK_INT): Int = when (this) {
    CatchUpHaptic.Armed -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    CatchUpHaptic.Disarmed -> if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    CatchUpHaptic.Released -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    CatchUpHaptic.Answered -> HapticFeedbackConstants.CLOCK_TICK
    CatchUpHaptic.Failed -> if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CLOCK_TICK
}

/**
 * The cues played through the view, with no flags: whether they are felt is the system's touch-feedback setting's
 * call (and the view's), never overridden here.
 */
@Composable
internal fun rememberCatchUpHaptics(): (CatchUpHaptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { haptic -> view.performHapticFeedback(haptic.feedback()) } }
}

/**
 * When [haptics] plays: the threshold crossed while the finger holds (a release is not an un-arming), each armed
 * release, and each answer arriving — not one already showing when the screen came back.
 */
@Composable
private fun CatchUpHapticCues(pull: CatchUpPull, status: CatchUpStatus, haptics: (CatchUpHaptic) -> Unit) {
    val play = rememberUpdatedState(haptics)
    val answer = rememberUpdatedState(status)
    LaunchedEffect(pull) {
        snapshotFlow { pull.armed }.drop(1).collect { armed ->
            if (pull.holding) play.value(if (armed) CatchUpHaptic.Armed else CatchUpHaptic.Disarmed)
        }
    }
    LaunchedEffect(pull) {
        snapshotFlow { pull.pulls }.drop(1).collect { play.value(CatchUpHaptic.Released) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { answer.value }.drop(1).collect { shown ->
            when (shown) {
                is CatchUpStatus.Done -> play.value(CatchUpHaptic.Answered)
                is CatchUpStatus.Failed -> play.value(CatchUpHaptic.Failed)
                else -> Unit
            }
        }
    }
}

/** How far the finger travels past the newest message before the pull arms. */
internal val CatchUpPullThreshold = 88.dp
/** The most the pull reveals, however far the finger goes: the tab all the way out, and a little air over it. */
internal val CatchUpPullReveal = 52.dp
/** The tab stands in from the composer's sides by the composer's radius: its sides meet the composer's flat top, where every docked card's flat top begins too. */
private val CatchUpTabInset = CursorDimens.composerRadius
/** What of the tab stays under the composer's edge however far it is drawn out: its corners, the docked cards' radius. */
private val CatchUpTabTuck = CursorDimens.menuRadius

internal const val CATCH_UP_TEST_TAG = "catch-up-indicator"
