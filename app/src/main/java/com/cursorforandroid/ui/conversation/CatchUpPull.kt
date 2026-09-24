package com.cursorforandroid.ui.conversation

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
 * ([distance]), armed once that passes [thresholdPx]. Fed by [CatchUpOverscroll]; the list is lifted by [revealPx]
 * over the indicator beneath it while the finger holds (see [CatchUpIndicator]).
 */
@Stable
class CatchUpPull(val thresholdPx: Float, private val maxRevealPx: Float) {
    var distance by mutableFloatStateOf(0f)
        private set
    /** A finger is on the pull: from its first pixel past the edge to its release. */
    var holding by mutableStateOf(false)
        private set
    val armed: Boolean get() = holding && distance >= thresholdPx
    val progress: Float get() = (distance / thresholdPx).coerceIn(0f, 1f)
    /** How far the list stands lifted: with the finger at first, slower and slower past the threshold. */
    val revealPx: Float get() = if (holding) maxRevealPx * (1f - exp(-distance / thresholdPx)) else 0f
    /** The lift the list keeps while the pull's answer shows: the gap the threshold opened, the pill in it, the newest message above it. */
    val answerGapPx: Float get() = maxRevealPx * (1f - exp(-1f))

    /** How far the list stands lifted with [status] the pull's answer: with the finger, then the answer's gap, then none. */
    fun liftPx(status: CatchUpStatus): Float = when {
        holding -> revealPx
        status !is CatchUpStatus.Idle -> answerGapPx
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
        return was
    }
}

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
 * The pull's indicator, at the bottom of the transcript: while the finger holds, in the gap the lifted list leaves
 * under the newest message, fading and growing in with the pull ("Pull to catch up", then "Release to catch up"
 * once armed, with the platform's threshold haptic either way across it); once let go, in the gap the list keeps
 * while the answer shows — "Catching up…", the server's pause being waited out, "Up to date", "N new", or the
 * failure in the server's own words, which a tap puts away.
 */
@Composable
internal fun CatchUpIndicator(pull: CatchUpPull, status: CatchUpStatus, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val view = LocalView.current
    LaunchedEffect(pull) {
        snapshotFlow { pull.armed }.drop(1).collect { armed -> view.thresholdHaptic(armed) }
    }
    LaunchedEffect(status) {
        when (status) {
            is CatchUpStatus.Checking -> view.confirmHaptic()
            is CatchUpStatus.Failed -> view.rejectHaptic()
            else -> Unit
        }
    }
    val pulling = pull.holding && pull.distance > 0f
    val answering = status !is CatchUpStatus.Idle
    var pillHeight by remember { mutableFloatStateOf(0f) }
    // Centred in the gap the lifted list leaves (see [CatchUpPull.liftPx]) — the finger's, then the answer's — or sunk
    // out of sight with nothing to say.
    val target = when {
        pulling || answering -> pillHeight / 2f - pull.liftPx(status) / 2f
        else -> pillHeight
    }
    val lift by animateFloatAsState(target, if (pulling) snap() else spring(stiffness = Spring.StiffnessMediumLow), label = "catch-up lift")
    val shown by animateFloatAsState(
        when {
            pulling -> pull.progress
            answering -> 1f
            else -> 0f
        },
        if (pulling) snap() else spring(stiffness = Spring.StiffnessMedium),
        label = "catch-up shown",
    )
    if (shown <= 0.01f && !pulling && !answering) return
    val failed = status as? CatchUpStatus.Failed
    val dismissable = !pulling && (failed != null || status is CatchUpStatus.Done)
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 420.dp)
            .onSizeChanged { pillHeight = it.height.toFloat() }
            .graphicsLayer {
                translationY = lift
                alpha = shown
                val scale = 0.85f + 0.15f * shown
                scaleX = scale
                scaleY = scale
            }
            .cursorSurface(colors.elevated, colors.strokeStrong, CursorTheme.shapes.full)
            .pressable(onDismiss, CursorTheme.shapes.full, enabled = dismissable, role = if (dismissable) Role.Button else null)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(CATCH_UP_TEST_TAG)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            pulling || status is CatchUpStatus.Idle -> {
                Icon(
                    if (pull.armed) CursorIcons.Refresh else CursorIcons.ArrowUp,
                    null,
                    tint = if (pull.armed) colors.textPrimary else colors.iconTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(if (pull.armed) "Release to catch up" else "Pull to catch up", style = type.small, color = if (pull.armed) colors.textPrimary else colors.textTertiary)
            }
            status is CatchUpStatus.Checking -> {
                SpinnerRing(size = 12.dp)
                Spacer(Modifier.width(7.dp))
                Text("Catching up…", style = type.small, color = colors.textSecondary)
            }
            status is CatchUpStatus.Waiting -> {
                val seconds by produceState(secondsLeft(status.untilMillis), status) {
                    while (value > 0) {
                        delay(1_000)
                        value = secondsLeft(status.untilMillis)
                    }
                }
                Icon(CursorIcons.Clock, null, tint = colors.iconTertiary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text("Cursor asked for a pause · catching up in $seconds s", style = type.small, color = colors.textSecondary)
            }
            status is CatchUpStatus.Done -> {
                Icon(CursorIcons.Check, null, tint = if (status.newMessages > 0) colors.green else colors.iconTertiary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text(status.label(), style = type.small, color = if (status.newMessages > 0) colors.textPrimary else colors.textSecondary)
            }
            failed != null -> {
                Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Text(failed.message, style = type.small, color = colors.red, maxLines = 3, overflow = TextOverflow.Ellipsis)
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

private fun View.thresholdHaptic(armed: Boolean) {
    val constant = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> if (armed) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
        else -> if (armed) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(constant)
}

private fun View.confirmHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

private fun View.rejectHaptic() {
    performHapticFeedback(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
}

/** How far the finger travels past the newest message before the pull arms. */
internal val CatchUpPullThreshold = 88.dp
/** The most the list is lifted off the bottom by a pull: room for the indicator, however far the finger goes. */
internal val CatchUpPullReveal = 52.dp

internal const val CATCH_UP_TEST_TAG = "catch-up-indicator"
