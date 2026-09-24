package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.PullRefreshHaptics
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.util.AppClock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/** Where the reader's pull to catch up stands, from the pull to its answer (see [ConversationViewModel.catchUp]). */
sealed interface CatchUpStatus {
    /** Nothing asked, or the last answer told. */
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
 * The reader's pull past the newest message, as the sidebar's pull to refresh is a [PullToRefreshState]: how far the
 * finger has travelled past the transcript's bottom edge ([distance]), armed once that passes [thresholdPx]. Fed by
 * [CatchUpOverscroll], drawn by [CatchUpIndicator].
 *
 * [distanceFraction] is the finger's while it holds — 1 at the threshold, then Material's own tension past it
 * ([catchUpTension]) — and, once let go, where the indicator is sprung from wherever the finger left it: to the
 * threshold while the pull is answered, home otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class CatchUpPull(val thresholdPx: Float) : PullToRefreshState {
    var distance by mutableFloatStateOf(0f)
        private set
    /** A finger is on the pull: from its first pixel past the edge to its release. */
    var holding by mutableStateOf(false)
        private set
    /** How many releases were armed, and so asked for a catch-up. */
    var pulls by mutableIntStateOf(0)
        private set
    /** An armed release its answer has not taken up yet: the indicator is held at the threshold, spinning, meanwhile. */
    var awaiting by mutableStateOf(false)
        internal set
    val armed: Boolean get() = holding && distance >= thresholdPx

    private val sprung = Animatable(0f)
    /** The fraction the finger left the indicator at, until [sprung] takes it from there on the next frame. */
    private var left by mutableFloatStateOf(0f)
    private var handedOver by mutableStateOf(true)

    override val distanceFraction: Float
        get() = when {
            holding -> catchUpTension(distance / thresholdPx)
            !handedOver -> left
            else -> sprung.value
        }

    /**
     * Anything but the finger moves the indicator once it is let go — sprung, held at the threshold, at rest — so
     * [PullRefreshHaptics] plays the threshold for the finger's crossings alone, however the frame orders the answer
     * landing and the spring starting.
     */
    override val isAnimating: Boolean get() = !holding

    override suspend fun animateToThreshold() = springTo(1f)

    override suspend fun animateToHidden() = springTo(0f)

    override suspend fun snapTo(targetValue: Float) {
        sprung.snapTo(targetValue)
        handedOver = true
    }

    private suspend fun springTo(target: Float) {
        if (!handedOver) snapTo(left)
        sprung.animateTo(target)
    }

    fun stretch(px: Float) {
        holding = true
        distance += px
    }

    fun relax(px: Float) {
        if (holding) distance = (distance - px).coerceAtLeast(0f)
    }

    /** The finger lifted: whether the pull was armed. It is let go either way, the indicator where the finger left it. */
    fun release(): Boolean {
        val was = armed
        if (holding) {
            left = distanceFraction
            handedOver = false
        }
        holding = false
        distance = 0f
        if (was) {
            pulls++
            awaiting = true
        }
        return was
    }
}

/**
 * Material's pull-to-refresh tension, as the sidebar's indicator is given it: the finger's own [fraction] of the
 * threshold up to it, then slower and slower past it, never beyond twice the threshold.
 */
internal fun catchUpTension(fraction: Float): Float {
    if (fraction <= 1f) return fraction.coerceAtLeast(0f)
    val over = (fraction - 1f).coerceAtMost(2f)
    return 1f + over - over * over / 4f
}

/**
 * The platform's overscroll with the pull to catch up read off it: every delta goes to [platform] as it would
 * without this — so the transcript's stretch, its feel and its release are Android's own — and what the reader's
 * drag leaves over past the bottom edge (a scroll toward the newest row the list could not take) is the pull's too.
 * Only the finger's: a fling that reaches the bottom, the jump button's scroll, never arm it. [enabled] is asked at
 * the pull's first pixel: a chat still loading, or a pull already being answered, stretches without pulling.
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

/** Where the indicator is sprung once the finger is off it. */
private sealed interface CatchUpRest {
    /** At the threshold, spinning: the pull is being answered. */
    data object Threshold : CatchUpRest

    /** Home, under the bottom edge, and then [told] is the reader's (see [CatchUpStatus.word]). */
    data class Home(val told: CatchUpStatus) : CatchUpRest
}

/**
 * The pull's indicator: the sidebar's own — Material's pull-to-refresh indicator, its colours, its arrow and its
 * spinner — turned upside down, so it rises out of the transcript's bottom edge, just above the composer's stack,
 * where the sidebar's drops from its top. Its own clip is that edge. With the finger it follows [pull]; let go armed,
 * it springs to the threshold and spins until [status] answers, then springs home, and only once it is home is the
 * answer told ([onSettled]), so the word never lands on the indicator. As it starts to rise, [onRise]: whatever
 * word is up gives way to it. Every frame of all this is the indicator's layer; nothing is recomposed for it.
 *
 * The finger feels it as it does the sidebar's ([PullRefreshHaptics], so only as Settings › Haptic feedback
 * allows), and more: a confirm as an armed pull is let go, the lightest tick for the answer, a reject for a failure —
 * not for an answer already there when the screen came back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatchUpIndicator(
    pull: CatchUpPull,
    status: StateFlow<CatchUpStatus>,
    onSettled: (CatchUpStatus) -> Unit,
    modifier: Modifier = Modifier,
    onRise: () -> Unit = {},
) {
    val answer = status.collectAsStateWithLifecycle()
    val refreshing = pull.awaiting || answer.value is CatchUpStatus.Checking
    PullRefreshHaptics(pull, refreshing)
    CatchUpHapticCues(pull, answer)
    val settled by rememberUpdatedState(onSettled)
    val rise by rememberUpdatedState(onRise)
    LaunchedEffect(pull) {
        snapshotFlow { answer.value }.collect { if (it !is CatchUpStatus.Idle) pull.awaiting = false }
    }
    LaunchedEffect(pull) {
        snapshotFlow {
            val shown = answer.value
            when {
                pull.holding -> null
                pull.awaiting || shown is CatchUpStatus.Checking -> CatchUpRest.Threshold
                else -> CatchUpRest.Home(shown)
            }
        }.distinctUntilChanged().collectLatest { rest ->
            when (rest) {
                null -> Unit
                CatchUpRest.Threshold -> pull.animateToThreshold()
                is CatchUpRest.Home -> {
                    pull.animateToHidden()
                    if (rest.told.word() != null) settled(rest.told)
                }
            }
        }
    }
    LaunchedEffect(pull) {
        snapshotFlow { pull.distanceFraction > 0f }.distinctUntilChanged().filter { it }.collect { rise() }
    }
    PullToRefreshDefaults.Indicator(
        state = pull,
        isRefreshing = refreshing,
        modifier = modifier
            .graphicsLayer {
                // The half turn as two exact scales: under rotationZ = 180f the matrix keeps sine's rounding, and the
                // indicator's clip to its edge (a rect out to ±Float.MAX_VALUE) comes out empty — nothing is drawn.
                scaleX = -1f
                scaleY = -1f
            }
            .testTag(CATCH_UP_TEST_TAG),
    )
}

/** The answer's words: the new messages counted, else whether anything moved at all. */
internal fun CatchUpStatus.Done.label(): String = when {
    newMessages > 0 -> "$newMessages new"
    changed -> "Updated"
    else -> "Up to date"
}

/** What the reader is told once the indicator is home: the answer, the failure in the server's words, or the pause being waited out. */
internal fun CatchUpStatus.word(): String? = when (this) {
    is CatchUpStatus.Done -> label()
    is CatchUpStatus.Failed -> message
    is CatchUpStatus.Waiting -> "Cursor asked for a pause · catching up in ${secondsLeft(untilMillis)} s"
    CatchUpStatus.Checking, CatchUpStatus.Idle -> null
}

private fun secondsLeft(untilMillis: Long): Int = ((untilMillis - AppClock.now()) / 1_000.0).roundToInt().coerceAtLeast(0)

/**
 * What the finger feels beyond the sidebar's threshold pair: a [Haptic.Confirm] for each armed release, and each
 * answer as it arrives, [Haptic.Subtle] for one and [Haptic.Reject] for a failure — not one already showing when the
 * screen came back.
 */
@Composable
private fun CatchUpHapticCues(pull: CatchUpPull, answer: State<CatchUpStatus>) {
    val haptics = rememberHaptics()
    val play by rememberUpdatedState(haptics)
    LaunchedEffect(pull) {
        snapshotFlow { pull.pulls }.drop(1).collect { play.perform(Haptic.Confirm) }
    }
    LaunchedEffect(pull) {
        snapshotFlow { answer.value }.drop(1).collect { shown ->
            when (shown) {
                is CatchUpStatus.Done -> play.perform(Haptic.Subtle)
                is CatchUpStatus.Failed -> play.perform(Haptic.Reject)
                else -> Unit
            }
        }
    }
}

/** How far the finger travels past the newest message before the pull arms. */
internal val CatchUpPullThreshold = 88.dp

internal const val CATCH_UP_TEST_TAG = "catch-up-indicator"
