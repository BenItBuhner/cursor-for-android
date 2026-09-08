package com.cursorforandroid.ui.navigation

import android.app.Activity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Renders the top of a [NavStack] and animates every change to it, including the Android 14+ predictive back
 * gesture, with one transform that is seeked by the finger and finished (or rewound) on release.
 *
 * At most two entries are on screen: the resident one and, during a transition, the one beneath it. A single
 * `progress` (0 = the top screen is at rest, 1 = it is gone) drives both: the top screen shrinks to 90 %, rounds its
 * corners, slides in the direction of the swipe and dissolves over the last third, while the screen underneath
 * grows from 96 % and sheds a scrim. Pushes play the same transform backwards, so forward and back share one
 * vocabulary. Because the gesture's [BackEventCompat] is read here, the direction follows the edge the swipe came
 * from and the card trails the finger vertically — the two things a transition inside a navigation library cannot
 * know.
 *
 * Each entry gets its own `rememberSaveable` scope and [ViewModelStoreOwner]; both are released once the entry has
 * left the stack and finished animating out.
 */
@Composable
fun CursorNavHost(
    stack: NavStack,
    modifier: Modifier = Modifier,
    content: @Composable (Screen) -> Unit,
) {
    val stores: NavEntryStores = viewModel()
    val stateHolder = rememberSaveableStateHolder()
    val scene = remember { NavScene(stack.top) }
    val scope = rememberCoroutineScope()
    val colors = CursorTheme.colors
    val activity = LocalContext.current as? Activity

    // The host leaving composition while the activity stays (sign-out) abandons the whole stack; release every
    // entry's view models. A configuration change also disposes the host, but there the entries come back.
    DisposableEffect(stack) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.clearAll() }
    }

    val desired = stack.top
    LaunchedEffect(desired) {
        try {
            scene.moveTo(desired, stack)
        } catch (_: CancellationException) {
            // Superseded: a gesture took over the animation, or the stack changed again.
        }
    }

    PredictiveBackHandler(enabled = stack.canPop) { events ->
        val under = stack.underTop
        if (under == null) {
            // The stack emptied out in the frame before the callback's enabled state caught up. The gesture is ours all
            // the same, and its flow must be consumed to the end — returning early is an error in the handler — so it is
            // seen through with nothing to show for it.
            try {
                events.collect { }
            } catch (_: CancellationException) {
                return@PredictiveBackHandler
            }
            stack.pop()
            return@PredictiveBackHandler
        }
        // The token is taken before anything can suspend: a gesture cancelled while the scene is still being handed
        // over must be able to release it, or no navigation would ever move the scene again.
        val gesture = scene.claimGesture()
        var originY: Float? = null
        try {
            scene.beginGesture(under)
            events.collect { event ->
                if (originY == null) {
                    originY = event.touchY
                    scene.edge = event.swipeEdge
                }
                scene.seek(event.progress, event.touchY - (originY ?: event.touchY))
            }
        } catch (_: CancellationException) {
            // Cancelled by the system, or superseded by a newer gesture that now owns the scene.
            if (scene.endGesture(gesture)) {
                scope.launch {
                    try {
                        scene.settle(reveal = false)
                        // The stack may have moved while the finger was down (a deep link); catch the scene up.
                        scene.moveTo(stack.top, stack)
                    } catch (_: CancellationException) {
                        // Another navigation or gesture started before the rewind finished; it owns the scene now.
                    }
                }
            }
            return@PredictiveBackHandler
        }
        // Committed: pop now and let the stack observer finish the animation from where the finger left it.
        scene.endGesture(gesture)
        if (!stack.pop()) {
            // Nothing left to pop: the stack moved while the finger was down and the observer, finding the gesture in
            // charge, left the scene alone. It has nothing new to observe now, so the scene is caught up by hand.
            scope.launch {
                try {
                    scene.moveTo(stack.top, stack)
                } catch (_: CancellationException) {
                    // Another navigation or gesture took the scene over first.
                }
            }
        }
    }

    val density = LocalDensity.current
    val cornerPx = with(density) { CardCorner.toPx() }
    val maxDragPx = with(density) { MaxDragFollow.toPx() }
    scene.maxDragPx = maxDragPx

    Box(modifier.fillMaxSize()) {
        val under = scene.under
        val panes = if (under != null) listOf(under, scene.top) else listOf(scene.top)
        for (entry in panes) {
            key(entry.id) {
                val isTop = entry.id == scene.top.id
                val paneModifier = if (isTop) Modifier.topPane(scene, cornerPx, colors.strokeStrong) else Modifier.underPane(scene)
                EntryHost(entry, stack, stores, stateHolder, paneModifier) { content(entry.screen) }
            }
        }
    }
}

/** Composes one entry inside its own saveable-state scope and view-model store. */
@Composable
private fun EntryHost(
    entry: NavEntry,
    stack: NavStack,
    stores: NavEntryStores,
    stateHolder: SaveableStateHolder,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val owner = remember(entry.id) { stores.owner(entry.id) }
    DisposableEffect(entry.id) {
        onDispose {
            // Only an entry that was popped is finished with; one that leaves because the host itself is being torn
            // down (activity recreation) keeps its state for the rebuild.
            if (!stack.contains(entry)) {
                stores.clear(entry.id)
                stateHolder.removeState(entry.id)
            }
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        stateHolder.SaveableStateProvider(entry.id) {
            Box(modifier.fillMaxSize()) { content() }
        }
    }
}

/**
 * Holds one [ViewModelStore] per live entry. Being a view model itself, it outlives configuration changes together
 * with the activity, so screens keep their view models across rotation exactly as they did with NavHost.
 */
class NavEntryStores : ViewModel() {
    private val stores = HashMap<String, EntryStoreOwner>()

    fun owner(id: String): ViewModelStoreOwner = stores.getOrPut(id) { EntryStoreOwner() }

    fun clear(id: String) {
        stores.remove(id)?.viewModelStore?.clear()
    }

    fun clearAll() {
        stores.values.forEach { it.viewModelStore.clear() }
        stores.clear()
    }

    override fun onCleared() = clearAll()

    private class EntryStoreOwner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }
}

/** What is on screen and how far along the transition between the two panes is. */
@Stable
private class NavScene(initialTop: NavEntry) {
    var top by mutableStateOf(initialTop)
    var under by mutableStateOf<NavEntry?>(null)

    /** 0: the top pane is at rest and alone on screen. 1: it has fully given way to [under]. */
    val progress = Animatable(0f)

    /** Vertical trail behind the finger during a gesture, in px; returns to zero as the transition settles. */
    val dragY = Animatable(0f)
    var maxDragPx = 0f

    var edge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)

    /** Generation of the gesture that currently owns the scene; 0 when none does. */
    private var gesture = 0
    private var gestureCount = 0
    val gestureActive: Boolean get() = gesture != 0

    /**
     * A back gesture takes the scene over: until [endGesture] is called with the returned token, no navigation moves
     * it. Synchronous on purpose, so the caller holds the token before anything that can be cancelled runs.
     */
    fun claimGesture(): Int {
        gesture = ++gestureCount
        return gesture
    }

    /**
     * The gesture that claimed the scene began over [top]; [revealed] is what it uncovers. An in-flight transition
     * toward the same pair hands over its progress (and is interrupted, so it cannot finish underneath the finger).
     */
    suspend fun beginGesture(revealed: NavEntry) {
        if (under?.id != revealed.id) {
            under = revealed
            progress.snapTo(0f)
            dragY.snapTo(0f)
        } else {
            progress.snapTo(progress.value)
            dragY.snapTo(dragY.value)
        }
    }

    /** Ends the gesture with this token; false if a newer gesture has already taken the scene over. */
    fun endGesture(token: Int): Boolean {
        if (gesture != token) return false
        gesture = 0
        return true
    }

    suspend fun seek(fraction: Float, fingerDy: Float) {
        progress.snapTo(fraction.coerceIn(0f, 1f))
        dragY.snapTo((fingerDy * DragFollow).coerceIn(-maxDragPx, maxDragPx))
    }

    /** Brings the scene in line with the stack's new [desired] top. */
    suspend fun moveTo(desired: NavEntry, stack: NavStack) {
        if (gestureActive) return
        when {
            under == null && desired.id == top.id -> return
            desired.id == top.id -> settle(reveal = false)
            desired.id == under?.id -> settle(reveal = true)
            stack.contains(top) -> {
                // Push: the resident screen drops underneath and the new one arrives, playing the pop backwards.
                under = top
                top = desired
                edge = BackEventCompat.EDGE_LEFT
                progress.snapTo(1f)
                dragY.snapTo(0f)
                settle(reveal = false)
            }
            else -> {
                // Pop to something that was not directly beneath (pop to root, or a second back mid-animation):
                // whatever is leaving keeps leaving, and the new destination is what it uncovers.
                under = desired
                settle(reveal = true)
            }
        }
    }

    /** Animates the rest of the way, then leaves a single resident pane: [under] if [reveal], else [top]. */
    suspend fun settle(reveal: Boolean) {
        val target = if (reveal) 1f else 0f
        val distance = abs(target - progress.value)
        if (distance > 0.001f || dragY.value != 0f) {
            val millis = (SettleMillis * distance).roundToInt().coerceIn(MinSettleMillis, SettleMillis)
            coroutineScope {
                launch { dragY.animateTo(0f, tween(millis, easing = SettleEasing)) }
                progress.animateTo(target, tween(millis, easing = SettleEasing))
            }
        }
        if (reveal) under?.let { top = it }
        under = null
        progress.snapTo(0f)
        dragY.snapTo(0f)
    }
}

/**
 * The pane on top. Shrinks toward 90 %, rounds its corners within the first quarter of the gesture, slides with the
 * swipe (right for a left-edge gesture and for every programmatic pop, left for a right-edge gesture), trails the
 * finger vertically and dissolves over the last third so it is gone by the time the screen beneath is full size.
 */
private fun Modifier.topPane(scene: NavScene, cornerPx: Float, outline: Color): Modifier = this
    .graphicsLayer {
        val p = if (scene.under == null) 0f else scene.progress.value
        if (p <= 0f) {
            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; alpha = 1f; clip = false
            return@graphicsLayer
        }
        val scale = lerp(1f, TopMinScale, p)
        scaleX = scale
        scaleY = scale
        val direction = if (scene.edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        translationX = direction * p * size.width * TopShift
        translationY = scene.dragY.value
        alpha = 1f - fadeFraction(p)
        shape = RoundedCornerShape(cornerPx * (p / CornerRampEnd).coerceAtMost(1f))
        clip = true
    }
    .drawWithContent {
        drawContent()
        val p = if (scene.under == null) 0f else scene.progress.value
        if (p > 0f) {
            // A hairline keeps the card legible against a same-coloured screen beneath; it fades with the card.
            val radius = cornerPx * (p / CornerRampEnd).coerceAtMost(1f)
            val half = 0.5f
            drawRoundRect(
                color = outline.copy(alpha = outline.alpha * (1f - fadeFraction(p))),
                topLeft = Offset(half, half),
                size = Size(size.width - 2 * half, size.height - 2 * half),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1f),
            )
        }
    }

/** The pane underneath: grows from 96 % to full size while a scrim over it fades out. */
private fun Modifier.underPane(scene: NavScene): Modifier = this
    .graphicsLayer {
        val p = scene.progress.value
        val scale = lerp(UnderMinScale, 1f, p)
        scaleX = scale
        scaleY = scale
    }
    .drawWithContent {
        drawContent()
        val scrim = UnderScrim * (1f - scene.progress.value)
        if (scrim > 0f) drawRect(Color.Black.copy(alpha = scrim))
    }

/** 0 until [FadeStart], then a smooth ramp to 1 at full progress. */
private fun fadeFraction(p: Float): Float {
    val t = ((p - FadeStart) / (1f - FadeStart)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private const val TopMinScale = 0.90f
private const val TopShift = 0.12f
private const val UnderMinScale = 0.96f
private const val UnderScrim = 0.32f
private const val FadeStart = 0.62f
private const val CornerRampEnd = 0.25f
private const val DragFollow = 0.08f
private val CardCorner = 24.dp
private val MaxDragFollow = 28.dp

private const val SettleMillis = 320
private const val MinSettleMillis = 120

/** Fast out of the gate, settling gently: the screen answers the release at once and eases into place. */
private val SettleEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
