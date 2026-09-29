package com.cursorforandroid.ui.conversation

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.composerDockInset
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.pressable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalDensity
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a queued card is told by the stack it stands in: how much of its face — its line, tiles and glyphs — shows (all
 * of it, but at the back of a stacked queue, where only the card's edge peeks out; read at each frame, as it springs),
 * and where in the window the cards in front of it stand, if it is behind them, for a send's copy to go behind.
 */
@Stable
class QueueCardFace internal constructor(val contentAlpha: () -> Float, val cover: () -> Rect? = { null }) {
    companion object {
        /** A card standing alone or in a plain list: its whole face, always, and nothing in front of it. */
        val Plain = QueueCardFace({ 1f })
    }
}

/** Where each card is laid out, for the cards behind it: the window's box over those in front of one, scale and all. */
private class Placed {
    val coordinates = HashMap<String, LayoutCoordinates>()

    fun over(ids: List<String>): Rect? = ids.mapNotNull { id ->
        coordinates[id]?.takeIf { it.isAttached }?.let { Rect(it.localToWindow(Offset.Zero), it.localToWindow(Offset(it.size.width.toFloat(), it.size.height.toFloat()))) }
    }.reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
}

/** One card's place in the stack as it springs there: the top of its card from the stack's foot, and how deep it stands. */
private class CardMotion {
    var y: Animatable<Float, AnimationVector1D>? = null
    var depth: Animatable<Float, AnimationVector1D>? = null
    /** The card's height as last laid out: what its surface keeps as it leaves. */
    var height = 0

    fun depthNow(): Float = depth?.value ?: 0f
}

/** A card gone from the stack's keys, still drawn as it goes: [progress] runs from 0 (where it stood) to 1 (gone). */
private class Exit {
    val progress = Animatable(0f)
}

/**
 * The cards on their way out, by key, in the order they left. Kept off the snapshot — composition and layout read it —
 * with [tick] to compose the stack again once one is gone.
 */
private class Exits {
    val leaving = LinkedHashMap<String, Exit>()
    var previous: List<String> = emptyList()
    var tick by mutableIntStateOf(0)

    /** The keys that left since the last composition start their exit; a key back in [keys] is simply a card again. */
    fun follow(keys: List<String>, live: Boolean, known: Set<String>) {
        val now = keys.toHashSet()
        leaving.keys.removeAll(now)
        if (!live) leaving.clear() else for (id in previous) if (id !in now && id in known && id !in leaving) leaving[id] = Exit()
        previous = keys
    }
}

/**
 * The queued follow-ups over the composer as a deck of cards once there are more than a couple of them (Bennett's
 * 2026-09-27 frame: five full rows over the box, half the screen gone to what is waiting). Stacked, the next message
 * stands in front, whole, on the box it came from; the next two peek out above it, each narrower by a step and its face
 * hidden, like cards behind it; the rest wait behind those. A line over the deck says how many are queued, with a
 * chevron that opens it; a tap anywhere on the deck opens it too, and the front card's own glyphs — remove, edit, send
 * now — work where they are. Opened, the cards stand in the list they always were, oldest first, every glyph and menu
 * on each, and the same line closes them back into the deck.
 *
 * [stacked] is the reader's choice, remembered on the device: a long queue stacks until it is opened, and stays open
 * everywhere until it is stacked again. Two or fewer never stack — a deck of two hides as much as it saves — and show
 * no line over them.
 *
 * Every change of place is one spring, per card, from wherever the card is drawn now: opening and closing, the front
 * card lifting off into its bubble and the next one coming forward, a send joining the back, a card moved up the order.
 * Only placement and drawing read the springs, so nothing is recomposed for their frames; a card seen for the first
 * time stands where it belongs at once, so nothing replays when the screen is composed again, and with animations off
 * every card simply stands there. A card's box is where it is drawn, scale and all, so a send's flight lands on the
 * card at the back of the deck — its face fading as it sinks in — and a delivery lifts off the front card (see
 * `SendMotion`). [card] draws the card at an index of [keys], told its [QueueCardFace].
 *
 * A card that leaves [keys] does not blink out: its words are the delivery's flight (see `SendMotion`), and its surface
 * stays where it stood, fading and folding down onto its foot over [QueueExitMillis] while the cards around it spring
 * into their new places, so neither the stack nor the transcript over it jumps. [gapBelow] is the space under the
 * stack while it holds anything, folded away with the last card.
 */
@Composable
fun QueueStack(
    keys: List<String>,
    stacked: Boolean,
    onStackedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    animate: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
    /** How each change of place moves; the stack's spring but for a frame a test catches part of the way. */
    animationSpec: AnimationSpec<Float> = StackSpring,
    gapBelow: Dp = 0.dp,
    card: @Composable (index: Int, face: QueueCardFace) -> Unit,
) {
    val count = keys.size
    val stackable = count >= StackFrom
    val collapsed = stacked && stackable
    val scope = rememberCoroutineScope()
    val motions = remember { HashMap<String, CardMotion>() }
    val placed = remember { Placed() }
    val exits = remember { Exits() }
    exits.tick
    exits.follow(keys, animate(), motions.keys)
    val leaving = exits.leaving.keys.toList()
    val handleShare = remember { Animatable(if (stackable) 1f else 0f) }
    val turn = remember { Animatable(if (collapsed) 0f else 1f) }
    LaunchedEffect(stackable) { settle(handleShare, if (stackable) 1f else 0f, animate(), animationSpec) }
    LaunchedEffect(collapsed) { settle(turn, if (collapsed) 0f else 1f, animate(), animationSpec) }
    val toggle by rememberUpdatedState { onStackedChange(!collapsed) }
    val open by rememberUpdatedState { if (collapsed) onStackedChange(false) }
    val density = LocalDensity.current
    val gap = with(density) { Gap.roundToPx() }
    val foot = with(density) { gapBelow.roundToPx() }
    val peek = with(density) { Peek.toPx() }

    Layout(
        content = {
            StackHandle(count, collapsed, visible = stackable, turn = { turn.value }, onToggle = { toggle() })
            keys.forEachIndexed { index, id ->
                key(id) {
                    val motion = remember { motions.getOrPut(id) { CardMotion() } }
                    val behind = collapsed && index > 0
                    val ahead by rememberUpdatedState(if (behind) keys.subList(0, index).toList() else emptyList())
                    val face = remember(motion) {
                        QueueCardFace(contentAlpha = { 1f - motion.depthNow().coerceIn(0f, 1f) }, cover = { placed.over(ahead) })
                    }
                    Box(
                        when {
                            // A card behind the front one shows only its edge: a touch on it opens the deck, and never
                            // reaches the glyphs it hides; nor does a reader hear what the deck does not show.
                            behind -> Modifier
                                .clearAndSetSemantics {}
                                .pointerInput(Unit) { openOnTap(swallow = true) { open() } }
                            collapsed -> Modifier.pointerInput(Unit) { openOnTap(swallow = false) { open() } }
                            else -> Modifier
                        }.onPlaced { placed.coordinates[id] = it },
                    ) { card(index, face) }
                }
            }
            for (id in leaving) {
                key(id) {
                    val exit = exits.leaving[id]
                    LaunchedEffect(exit) {
                        exit?.progress?.animateTo(1f, tween(QueueExitMillis, easing = FastOutSlowInEasing))
                        exits.leaving.remove(id)
                        exits.tick++
                    }
                    // Only the surface: the words went with the delivery's flight. Nothing on it is heard or pressed.
                    Box(Modifier.clearAndSetSemantics {}.pointerInput(Unit) { openOnTap(swallow = true) {} }.dockedCard())
                }
            }
        },
        modifier = modifier
            .testTag(QueueStackTag)
            .semantics {
                if (stackable) stateDescription = if (collapsed) "Stacked" else "Expanded"
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val handle = measurables.first().measure(Constraints(maxWidth = width))
        val cards = measurables.subList(1, 1 + keys.size).map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        val n = cards.size
        val gone = leaving
        val ghosts = measurables.subList(1 + keys.size, measurables.size).mapIndexed { g, m ->
            m.measure(Constraints.fixed(width, motions[gone.getOrNull(g)]?.height ?: 0))
        }
        val live = animate()
        // Where each card belongs now: its top from the stack's foot (negative, up), and its depth in the deck.
        val targetY = FloatArray(n)
        val targetDepth = FloatArray(n)
        if (collapsed) {
            val front = cards[0].height.toFloat()
            for (k in 0 until n) {
                targetY[k] = -front - peek * min(k, Peeks)
                targetDepth[k] = min(k, Peeks + 1).toFloat()
            }
        } else {
            var foot = 0f
            for (k in n - 1 downTo 0) {
                targetY[k] = foot - cards[k].height
                foot = targetY[k] - gap
            }
        }
        val ids = keys.toList()
        val motionOf = { k: Int -> motions.getOrPut(ids[k]) { CardMotion() } }
        val y = FloatArray(n) { k -> if (live) motionOf(k).y?.value ?: targetY[k] else targetY[k] }
        val depth = FloatArray(n) { k -> if (live) motionOf(k).depth?.value ?: targetDepth[k] else targetDepth[k] }
        var top = 0f
        for (k in 0 until n) if (shown(depth[k]) > 0f) top = minOf(top, y[k])
        // A leaving card holds the deck's top where it stood, and lets it down with its fold: its own height and the
        // gap over the card under it.
        var stays = if (n > 0) 1f else 0f
        for ((g, id) in gone.withIndex()) {
            val m = motions[id] ?: continue
            val e = exits.leaving[id]?.progress?.value ?: 1f
            if (shown(m.depthNow()) > 0f) top = minOf(top, (m.y?.value ?: 0f) + (ghosts[g].height + gap) * e)
            stays = maxOf(stays, 1f - e)
        }
        val deck = ceil(-top).toInt().coerceAtLeast(0)
        val share = handleShare.value
        val lead = (handle.height * share).roundToInt()
        val below = (foot * stays).roundToInt()
        val height = deck + lead + below
        layout(width, height) {
            motions.keys.retainAll(ids.toSet() + gone)
            placed.coordinates.keys.retainAll(ids.toSet())
            for (k in 0 until n) {
                val m = motionOf(k)
                m.height = cards[k].height
                m.y = m.y.springTo(targetY[k], live, scope, animationSpec)
                m.depth = m.depth.springTo(targetDepth[k], live, scope, animationSpec)
            }
            handle.placeWithLayer(width - handle.width, lead - handle.height) { alpha = share }
            val frontTop = if (n > 0) y[0] else 0f
            val frontHeight = if (n > 0) cards[0].height.toFloat() else 0f
            for (k in 0 until n) {
                val d = depth[k]
                val layer = d.coerceIn(0f, Peeks.toFloat())
                val scale = 1f - ScaleStep * layer
                // Behind the front card, a card taller than it would show its foot under the front's: it is cut, flat,
                // where the front card covers it, and nowhere it could be seen.
                val cut = if (k > 0 && y[k] < frontTop) (frontTop - y[k] + frontHeight / 2f) / scale else Float.MAX_VALUE
                val clipped = cut < cards[k].height
                cards[k].placeWithLayer(0, height - below + y[k].roundToInt(), zIndex = (n - k).toFloat()) {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = scale
                    scaleY = scale
                    alpha = shown(d)
                    clip = clipped
                    shape = if (clipped) TopClip(cut) else RectangleShape
                }
            }
            for ((g, id) in gone.withIndex()) {
                val m = motions[id] ?: continue
                val exit = exits.leaving[id] ?: continue
                val d = m.depthNow()
                val scale = 1f - ScaleStep * d.coerceIn(0f, Peeks.toFloat())
                // Over the cards, as it stood in front of whatever comes forward into its place, fading as it folds.
                ghosts[g].placeWithLayer(0, height - below + (m.y?.value ?: 0f).roundToInt(), zIndex = (n + 1).toFloat()) {
                    val e = exit.progress.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = scale
                    scaleY = scale * (1f - e)
                    alpha = shown(d) * (1f - e)
                }
            }
        }
    }
}

/** How much of a card at depth [d] is drawn: the front and the two peeking behind it wholly, the rest not at all. */
private fun shown(d: Float): Float = (Peeks + 1 - d).coerceIn(0f, 1f)

private suspend fun settle(value: Animatable<Float, AnimationVector1D>, target: Float, live: Boolean, spec: AnimationSpec<Float>) {
    if (live) value.animateTo(target, spec) else value.snapTo(target)
}

/** This spring, sent on to [target] when that has moved; a new one standing at [target] when there was none. */
private fun Animatable<Float, AnimationVector1D>?.springTo(
    target: Float,
    live: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    spec: AnimationSpec<Float>,
): Animatable<Float, AnimationVector1D> {
    val spring = this ?: return Animatable(target)
    if (spring.targetValue != target || (!live && spring.value != target)) {
        scope.launch { if (live) spring.animateTo(target, spec) else spring.snapTo(target) }
    }
    return spring
}

/**
 * A tap on the deck opens it. [swallow]: on a card behind the front one, the touch is taken before anything on the
 * card sees it; on the front card, only a touch its own glyphs leave is.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.openOnTap(swallow: Boolean, onTap: () -> Unit) {
    val pass = if (swallow) PointerEventPass.Initial else PointerEventPass.Main
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = !swallow, pass = pass)
        if (swallow) down.consume()
        val up = waitForUpOrCancellation(pass) ?: return@awaitEachGesture
        up.consume()
        onTap()
    }
}

/** The top [height] px of a card: what a card behind the front one keeps of itself. */
private class TopClip(private val height: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, 0f, size.width, height.coerceIn(0f, size.height)))
}

/**
 * The line over a stack of more than a couple: how many are queued, and a chevron that turns as the deck opens (up:
 * open it; down: stack it). Lined up with the cards' glyphs, at their end.
 */
@Composable
private fun StackHandle(count: Int, collapsed: Boolean, visible: Boolean, turn: () -> Float, onToggle: () -> Unit) {
    val colors = CursorTheme.colors
    val end = composerDockInset(CursorTheme.shapes.xl).end + CursorDimens.composerPadding - 6.dp
    val action = if (collapsed) "Show all $count queued follow-ups" else "Stack queued follow-ups"
    Box(Modifier.padding(end = end)) {
        if (!visible) return@Box
        Box(
            Modifier
                .height(HandleHeight)
                .pressable(onToggle, CircleShape)
                .testTag(QueueStackHandleTag)
                .clearAndSetSemantics {
                    contentDescription = action
                    role = Role.Button
                    onClick(label = action) { onToggle(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$count queued", style = CursorTheme.typography.small, color = colors.textTertiary, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                Icon(
                    CursorIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.iconTertiary,
                    modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = lerp(180f, 0f, turn()) },
                )
            }
        }
    }
}

/** A queue of this many or more stacks (unless the reader opened it). */
const val StackFrom = 3
/** The cards that peek out behind the front one; any further back wait unseen behind those. */
private const val Peeks = 2
/** How much narrower each card behind is than the one in front of it. */
private const val ScaleStep = 0.045f
/** How far each card behind shows above the one in front of it. */
private val Peek = 6.dp
/** The gap between cards in the opened list: the dock's own. */
private val Gap = 4.dp
private val HandleHeight = 24.dp

/** How long a card that left the stack takes to fade and fold away. */
internal const val QueueExitMillis = 240

/** Opening, closing and every change of place: a touch of overshoot, settled in about a third of a second. */
internal val StackSpring = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

const val QueueStackTag = "queue-stack"
const val QueueStackHandleTag = "queue-stack-handle"
