package com.cursorforandroid.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.PullRefreshHaptics
import com.cursorforandroid.ui.components.RefreshIndicator
import com.cursorforandroid.ui.components.RefreshIndicatorContent
import com.cursorforandroid.ui.components.RefreshIndicatorSize
import com.cursorforandroid.ui.components.refreshDiscOutline
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.util.AppClock
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
 * The reader's pull past the newest message. The finger's travel past the transcript's bottom edge ([distance]) lifts
 * the transcript off the composer ([lift], drawn by [catchUpLift]) against a rubber band's resistance
 * ([catchUpRubberBand]): all but glued to the finger at first, heavier and heavier, never past [reachPx]. The pull arms
 * once the finger has travelled [thresholdPx], where the transcript stands exactly [liftPx] up; [CatchUpIndicator]
 * sits in the middle of the gap that opens, so it travels half as far as the transcript.
 *
 * Let go, the lift is sprung from wherever the finger left it: to [heldPx] while the pull is answered, home otherwise.
 * As a [PullToRefreshState] (for [PullRefreshHaptics] and the indicator's arrow), [distanceFraction] is the lift over
 * [liftPx]: 1 at the threshold, slower and slower past it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class CatchUpPull(val thresholdPx: Float, val liftPx: Float, val heldPx: Float) : PullToRefreshState {
    init {
        require(CatchUpGive * thresholdPx > liftPx) { "The lift at the threshold must be short of the finger's give" }
        require(heldPx in 0f..liftPx) { "The transcript is held no higher than the threshold lifts it" }
    }

    /** Where [catchUpRubberBand] tends to, set so the finger's [thresholdPx] lifts the transcript exactly [liftPx]. */
    val reachPx: Float = liftPx * CatchUpGive * thresholdPx / (CatchUpGive * thresholdPx - liftPx)

    var distance by mutableFloatStateOf(0f)
        private set
    /** A finger is on the pull: from its first pixel past the edge to its release. */
    var holding by mutableStateOf(false)
        private set
    /** How many releases were armed, and so asked for a catch-up. */
    var pulls by mutableIntStateOf(0)
        private set
    /** An armed release its answer has not taken up yet: the transcript is held, the indicator spinning, meanwhile. */
    var awaiting by mutableStateOf(false)
        internal set
    /** The pull is being answered and the transcript held at [heldPx] for it (see [CatchUpIndicator]). */
    var held by mutableStateOf(false)
        internal set

    /** Whether this hold can arm: not one taken up over a pull already being answered, which only moves the transcript. */
    private var armable = true
    /** How far a drag back takes the finger's travel: home for a pull, the held gap for one taken up over an answer. */
    private var floor = 0f

    val armed: Boolean get() = holding && armable && distance >= thresholdPx

    /** The finger is on a pull that has lifted the transcript beyond where it took hold. */
    val engaged: Boolean get() = holding && distance > floor

    private val sprung = Animatable(0f)
    /** Where the finger left the transcript, until [sprung] takes it from there on the next frame. */
    private var left by mutableFloatStateOf(0f)
    private var handedOver by mutableStateOf(true)
    /** The last spring's speed, carried into the next when an answer turns it round before it settles. */
    private var carried = 0f

    /** How far the transcript is lifted off the composer, in px. */
    val lift: Float
        get() = when {
            holding -> catchUpRubberBand(distance, reachPx)
            !handedOver -> left
            else -> sprung.value.coerceAtLeast(0f)
        }

    override val distanceFraction: Float get() = lift / liftPx

    /**
     * Anything but the finger moves the transcript once it is let go — sprung, held, at rest — so [PullRefreshHaptics]
     * plays the threshold for the finger's crossings alone, however the frame orders the answer landing and the spring
     * starting.
     */
    override val isAnimating: Boolean get() = !holding

    override suspend fun animateToThreshold() = springTo(heldPx, HeldSpring)

    override suspend fun animateToHidden() = springTo(0f, HomeSpring)

    override suspend fun snapTo(targetValue: Float) {
        sprung.snapTo(targetValue * liftPx)
        handedOver = true
    }

    private suspend fun springTo(target: Float, spec: SpringSpec<Float>) {
        if (!handedOver) snapTo(left / liftPx)
        sprung.animateTo(target, spec, initialVelocity = carried) { carried = velocity }
        carried = 0f
    }

    /**
     * The finger takes hold past the edge, from wherever the transcript stands: [armable] for a chat that can be caught
     * up now; otherwise only over a pull being answered ([held]), where it moves the transcript and never arms.
     */
    fun grab(armable: Boolean): Boolean {
        if (holding) return true
        if (!armable && !held) return false
        val from = catchUpFingerFor(lift, reachPx)
        this.armable = armable
        floor = if (armable) 0f else minOf(from, catchUpFingerFor(heldPx, reachPx))
        distance = from
        holding = true
        return true
    }

    fun stretch(px: Float) {
        grab(armable = true)
        distance += px
    }

    /** The finger came back [px]: how much of it the pull took, before anything else is offered the rest. */
    fun relax(px: Float): Float {
        if (!holding) return 0f
        val before = distance
        distance = (distance - px).coerceAtLeast(floor)
        return (before - distance).coerceAtLeast(0f)
    }

    /** The finger lifted: whether the pull was armed. It is let go either way, the transcript where the finger left it. */
    fun release(): Boolean {
        val was = armed
        if (holding) {
            left = lift
            handedOver = false
            carried = 0f
        }
        holding = false
        distance = 0f
        floor = 0f
        armable = true
        if (was) {
            pulls++
            awaiting = true
        }
        return was
    }
}

/** The pull for this screen's [density]: armed at [CatchUpPullThreshold], lifting [CatchUpPullLift] there and held at [CatchUpHeldLift]. */
internal fun catchUpPullFor(density: Density): CatchUpPull = with(density) {
    CatchUpPull(CatchUpPullThreshold.toPx(), CatchUpPullLift.toPx(), CatchUpHeldLift.toPx())
}

/**
 * The transcript's lift for the finger's [travel] past the edge: [CatchUpGive] of the finger at first, slower and
 * slower, never reaching [reach].
 */
internal fun catchUpRubberBand(travel: Float, reach: Float): Float {
    val given = CatchUpGive * travel.coerceAtLeast(0f)
    return reach * given / (reach + given)
}

/** The finger's travel that lifts the transcript [lift]: [catchUpRubberBand] the other way round. */
internal fun catchUpFingerFor(lift: Float, reach: Float): Float {
    val l = lift.coerceIn(0f, reach * 0.999f)
    return reach * l / (CatchUpGive * (reach - l))
}

/**
 * The platform's overscroll with the pull to catch up read off the reader's drag. Every delta goes through [platform],
 * so a stretch past the oldest message is still Android's own, and it relaxes before anything else when the finger
 * turns round; but the drag toward the newest row that the list leaves over is the pull's, never the platform's, so
 * no stretch is drawn at the bottom edge. A fling's leftover toward the newest row is put down there too: the bottom
 * edge only ever moves with the finger. Only the finger's drag pulls: a fling that reaches the bottom, the jump
 * button's scroll, never lift it. [enabled] is asked at the pull's first pixel: a chat still loading stretches the
 * platform's way, and a pull already being answered is moved without arming (see [CatchUpPull.grab]).
 *
 * Taken back, the pull is the finger's first, as the sidebar's is: the list scrolls only once it is home.
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
        return platform.applyToScroll(delta, source) { available ->
            // Screen terms: the finger moving up is a negative delta.
            val y = available.y
            when {
                y > 0f -> {
                    val back = pull.relax(y)
                    val scrolled = if (y > back) performScroll(available.copy(y = y - back)) else Offset.Zero
                    scrolled.copy(y = scrolled.y + back)
                }
                y < 0f && pull.engaged -> {
                    pull.stretch(-y)
                    available
                }
                else -> {
                    val scrolled = performScroll(available)
                    val over = y - scrolled.y
                    if (over < 0f && (pull.holding || pull.grab(enabled()))) {
                        pull.stretch(-over)
                        available
                    } else {
                        scrolled
                    }
                }
            }
        }
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pull.release()) onPulled()
        platform.applyToFling(velocity) { offered ->
            // What is returned is what was consumed: the platform stretches by the rest, so none is left toward the newest.
            val consumed = performFling(offered)
            if (offered.y - consumed.y < 0f) consumed.copy(y = offered.y) else consumed
        }
    }

    override val isInProgress: Boolean get() = platform.isInProgress || pull.holding

    override val effectModifier: Modifier get() = platform.effectModifier
}

/**
 * The transcript lifted off the composer by [pull]: moved as a layer, so the rows hit-test where they are drawn and
 * nothing is recomposed or measured again for a frame of it, and clipped to its own top and bottom edges, so what
 * rises past the top goes under the edge's fade rather than over the header. Placed inside the reader's scroll: the
 * drag's own coordinates must not move with the rows it moves.
 */
internal fun Modifier.catchUpLift(pull: CatchUpPull): Modifier = this
    .drawWithContent {
        clipRect(left = -Float.MAX_VALUE, right = Float.MAX_VALUE) { this@drawWithContent.drawContent() }
    }
    .graphicsLayer { translationY = -pull.lift }

/** Where the indicator is sprung once the finger is off it. */
private sealed interface CatchUpRest {
    /** Held up, spinning: the pull is being answered. */
    data object Threshold : CatchUpRest

    /** Home, and then [told] is the reader's (see [CatchUpStatus.word]). */
    data class Home(val told: CatchUpStatus) : CatchUpRest
}

/**
 * The pull's indicator: the sidebar's own ([RefreshIndicator] — Material's pull-to-refresh indicator, its colours,
 * its arrow and its spinner, in the app's outlined, shadowless disc) standing in the middle of the gap the lifted
 * transcript opens over the composer, half as far up as the transcript and as far from each. [edgeGap] is the room
 * already there at rest, between the newest row and the area's bottom edge (the list's bottom padding), so the middle
 * is the whole gap's. It grows into the gap rather than covering either side of it, and it is drawn whole once the gap
 * has room for it. With the finger it follows [pull]; let go armed, the transcript is sprung to the held gap and the
 * indicator spins there until [status] answers (through any pause the server asked for), then both go home, and only
 * once home is a failure told ([onSettled]), so the word never lands on the indicator. As it starts to rise, [onRise]:
 * whatever word is up gives way to it. Every frame of all this is the indicator's layer; nothing is recomposed for it.
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
    edgeGap: Dp = 0.dp,
    onRise: () -> Unit = {},
) {
    val answer = status.collectAsStateWithLifecycle()
    val refreshing = pull.awaiting || answer.value.underWay()
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
                pull.awaiting || shown.underWay() -> CatchUpRest.Threshold
                else -> CatchUpRest.Home(shown)
            }
        }.distinctUntilChanged().collectLatest { rest ->
            when (rest) {
                null -> Unit
                CatchUpRest.Threshold -> {
                    pull.held = true
                    pull.animateToThreshold()
                }
                is CatchUpRest.Home -> {
                    pull.held = false
                    pull.animateToHidden()
                    if (rest.told.word() != null) settled(rest.told)
                }
            }
        }
    }
    LaunchedEffect(pull) {
        snapshotFlow { pull.lift > 0f }.distinctUntilChanged().filter { it }.collect { rise() }
    }
    Box(
        modifier
            .size(RefreshIndicatorSize)
            .graphicsLayer {
                val gap = edgeGap.toPx() + pull.lift
                val scale = catchUpIndicatorScale(gap, size.height, CatchUpIndicatorMargin.toPx())
                translationY = (size.height - gap) / 2f
                // The half turn as two scales, the way the pull goes: the arrow comes round from below.
                scaleX = -scale
                scaleY = -scale
                alpha = if (scale > 0f) 1f else 0f
                shape = CircleShape
                clip = true
            }
            .background(PullToRefreshDefaults.containerColor, CircleShape)
            .refreshDiscOutline()
            .testTag(CATCH_UP_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        RefreshIndicatorContent(refreshing, progress = { pull.distanceFraction })
    }
}

/**
 * How large the indicator is drawn in a [gap] of that many px: nothing until the gap has [margin] of room on each
 * side, then growing with it, whole ([size] across) once the gap holds it with [margin] to spare above and below.
 */
internal fun catchUpIndicatorScale(gap: Float, size: Float, margin: Float): Float = ((gap - 2f * margin) / size).coerceIn(0f, 1f)

/**
 * What the reader is told once the indicator is home: only a failure, in the server's words. The answer is the
 * indicator's own going home, with its tick; a pause the server asked for is spun through, held.
 */
internal fun CatchUpStatus.word(): String? = (this as? CatchUpStatus.Failed)?.message

/** The pull is being answered, the indicator spinning in the held gap: checking, or waiting out a pause the server asked for. */
private fun CatchUpStatus.underWay(): Boolean = this is CatchUpStatus.Checking || this is CatchUpStatus.Waiting

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

/**
 * How far the finger travels past the newest message before the pull arms: well past an overshoot at the end of a
 * scroll, so a reload is only ever asked for on purpose.
 */
internal val CatchUpPullThreshold = 200.dp

/** How far the transcript stands lifted at the threshold: the indicator whole in the gap, with room above and below it. */
internal val CatchUpPullLift = 104.dp

/** How far the transcript is held lifted while the pull is answered, the indicator spinning in the gap. */
internal val CatchUpHeldLift = 80.dp

/** The share of the finger the transcript follows at the pull's first pixel, before the rubber band tightens. */
internal const val CatchUpGive = 0.8f

/** Released short of the threshold, or answered: home, decelerating into place with no bounce past the composer. */
private val HomeSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** Released armed: down to the held gap, settling with the least give. */
private val HeldSpring: SpringSpec<Float> = spring(dampingRatio = 0.75f, stiffness = 450f)

/** The least room kept between the indicator and the transcript or the composer while it grows into the gap. */
private val CatchUpIndicatorMargin = 8.dp

internal const val CATCH_UP_TEST_TAG = "catch-up-indicator"
