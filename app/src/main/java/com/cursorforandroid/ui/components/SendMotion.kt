package com.cursorforandroid.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sent text's way from the composer into its bubble, as one motion rather than a composer that empties and a
 * bubble that appears somewhere else a moment later.
 *
 * At the tap the composer's text is lifted off the field ([ComposerAnchor.takeoff]) and drawn over the whole window
 * by [SendMotionHost] exactly where it stood, while the composer empties under it (its placeholder held back until
 * the text has left). The bubble the transcript then shows for the message stays undrawn ([sendTarget]) and says
 * where it is laid out; the text travels there, its bubble's surface growing around it, re-wrapped to the bubble's
 * width on the way, and hands over to the bubble on the last frames. A new chat is the same flight from the New
 * Chat composer, with the chat's composer gliding down from where that one stood as the chat fades in over the pane
 * ([rememberArrivalGlide]).
 *
 * Only what is drawn moves: the flight is read in draw and placement alone, so the transcript is not recomposed for
 * any of its frames. A bubble that does not appear in time (the message queued after all, a transcript still loading)
 * leaves the text to fade where it stands and the bubble to appear as it always has; with animations off (the
 * system's animator scale at 0, as "Remove animations" sets it) there is no flight at all.
 */
@Stable
class SendMotion(
    /** Whether the system lets anything animate; read at each send, so the setting is followed as it changes. */
    private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    /** The message on its way, if one is. */
    var flight by mutableStateOf<SendFlight?>(null)
        private set

    /**
     * The composer at [takeoff] sent [text], which the transcript will show in a bubble that is none of [excluded]
     * (the chat's messages before the send): the text is lifted off the composer now. Null — nothing to fly — for a
     * composer with no text on it, or with animations off.
     */
    fun depart(takeoff: Takeoff?, text: String, excluded: Set<String> = emptySet(), holdMillis: Long = HoldMillis): SendFlight? {
        if (takeoff == null || text.isBlank() || !animatorsEnabled()) return null
        return SendFlight(takeoff, text.trim(), excluded, holdMillis).also { flight = it }
    }

    /** The flight from the New Chat composer is the chat [agentId]'s: its screen glides its composer in from there. */
    fun bind(flight: SendFlight?, agentId: String) {
        if (flight != null && this.flight === flight) flight.agentId = agentId
    }

    /** Takes [flight] down at once: the send it was for did not go out after all. */
    fun cancel(flight: SendFlight?) {
        if (flight != null && this.flight === flight) this.flight = null
    }

    internal fun finish(flight: SendFlight) {
        if (this.flight === flight) this.flight = null
    }

    /** The New Chat composer's box as it stood at the tap, when this chat is the one it just started. */
    fun arrivalFor(agentId: String): Rect? = flight?.takeIf { it.agentId == agentId }?.takeoff?.composer

    /** Whether a bubble for [id] saying [text] is the one on its way, not yet handed over, and so not drawn. */
    fun hides(id: String, text: String): Boolean {
        val f = flight ?: return false
        return f.matches(id, text) && f.phase != SendFlight.Phase.Fading && f.progress.value < HandOff
    }

    /** The placeholder of the composer at [anchor], held back while the text it held is still over it. */
    fun placeholderAlpha(anchor: ComposerAnchor): Float {
        val f = flight?.takeIf { it.takeoff.anchor === anchor } ?: return 1f
        return when (f.phase) {
            SendFlight.Phase.Holding -> 0f
            SendFlight.Phase.Fading -> 1f - f.fade.value
            SendFlight.Phase.Flying -> ((f.progress.value - PlaceholderFrom) / (HandOff - PlaceholderFrom)).coerceIn(0f, 1f)
        }
    }

    /**
     * The field of the composer at [anchor], undrawn while it still shows the text that was lifted off it: a composer
     * emptied a frame or two after the tap (New Chat clears its draft as the launch is packed) would draw it twice.
     */
    fun fieldAlpha(anchor: ComposerAnchor): Float {
        val f = flight?.takeIf { it.takeoff.anchor === anchor } ?: return 1f
        val shown = anchor.layout?.invoke()?.layoutInput?.text?.text ?: return 1f
        return if (shown.trim() == f.takeoff.layout.layoutInput.text.text.trim()) 0f else 1f
    }

    /** The bubble for [id] saying [text] has been placed: [surface] is its box, [textBox] its text's. */
    internal fun place(id: String, text: String, part: SendTargetPart, coordinates: LayoutCoordinates, fade: Float) {
        val f = flight ?: return
        if (!f.matches(id, text)) return
        when (part) {
            SendTargetPart.Surface -> f.surface = coordinates
            SendTargetPart.Text -> f.textBox = coordinates
        }
        f.targetFade = fade
        if (f.surface != null && f.textBox != null) f.targetPlaced = true
    }

    companion object {
        /** How long the text travels, composer to bubble (Material's emphasized durations are 300–500 ms; this is the quick end). */
        const val FlightMillis = 340
        /** How long a chat's composer takes to glide down from the New Chat composer, with the pane's fade. */
        const val GlideMillis = 320
        /** How long the text waits over the composer for its bubble before fading where it stands. */
        const val HoldMillis = 600L
        /** The same for a new chat, whose screen is still being built when the text leaves. */
        const val LaunchHoldMillis = 1_200L
        const val FadeMillis = 150
        /** From here the bubble is drawn and the flying copy over it fades: the two are in the same place by now. */
        const val HandOff = 0.85f
        /** Where the emptied composer's placeholder starts coming back. */
        const val PlaceholderFrom = 0.35f
        /** Material 3's emphasized easing (the cubic form of `EasingEmphasizedCubicBezier`). */
        val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

/** One message's flight. Positions are read live, from the layouts they belong to, on every frame it is drawn. */
@Stable
class SendFlight internal constructor(
    val takeoff: Takeoff,
    /** What the bubble says. */
    val text: String,
    private val excluded: Set<String>,
    val holdMillis: Long,
) {
    enum class Phase { Holding, Flying, Fading }

    var phase by mutableStateOf(Phase.Holding)
        internal set

    /** The chat a New Chat composer's flight started, once it is open. */
    var agentId by mutableStateOf<String?>(null)
        internal set

    /** 0 at the composer, 1 in the bubble. */
    val progress = Animatable(0f)

    /** 0 while the text stands, 1 once a flight that found no bubble has faded out. */
    val fade = Animatable(0f)

    /** True once both of the bubble's boxes have been placed. */
    var targetPlaced by mutableStateOf(false)
        internal set

    internal var surface: LayoutCoordinates? = null
    internal var textBox: LayoutCoordinates? = null

    /** The bubble's own fade (a message not yet filed is drawn faded); the copy arrives at it. */
    internal var targetFade = 1f

    fun matches(id: String, text: String): Boolean = id !in excluded && text.trim() == this.text
}

/** The text on a composer at the tap, and where it stood (window coordinates). */
class Takeoff internal constructor(
    internal val anchor: ComposerAnchor,
    internal val layout: TextLayoutResult,
    /** Where the field's text layout has its origin: the field's corner, less how far the field had scrolled. */
    internal val origin: Offset,
    /** The field's visible box. */
    internal val viewport: Rect,
    /** The composer's box. */
    val composer: Rect,
)

/**
 * Where a composer's text stands: the field and the box, as last placed, and the field's text layout. Plain fields,
 * written as the composer lays out and read at the tap; nothing reads them in composition.
 */
@Stable
class ComposerAnchor {
    internal var field: LayoutCoordinates? = null
    internal var surface: LayoutCoordinates? = null
    internal var layout: (() -> TextLayoutResult?)? = null
    internal var scroll: () -> Int = { 0 }

    /** The text as it stands now, lifted off for a flight; null when there is none to lift. */
    fun takeoff(): Takeoff? {
        val field = field?.takeIf { it.isAttached } ?: return null
        val surface = surface?.takeIf { it.isAttached } ?: return null
        val layout = layout?.invoke() ?: return null
        if (layout.layoutInput.text.isBlank()) return null
        val corner = field.positionInWindow()
        return Takeoff(
            anchor = this,
            layout = layout,
            origin = corner - Offset(0f, scroll().toFloat()),
            viewport = Rect(corner, Size(field.size.width.toFloat(), field.size.height.toFloat())),
            composer = surface.windowRect(),
        )
    }
}

/** The composition's [SendMotion]; null outside the shell (a screen drawn alone, as the screenshot tests draw them). */
val LocalSendMotion = staticCompositionLocalOf<SendMotion?> { null }

internal enum class SendTargetPart { Surface, Text }

/**
 * A bubble's [part] as a send's target: it says where it is laid out to a flight bound for it, and a bubble that is
 * that flight's is drawn only from the hand-over on. [fade] is the bubble's own fade at rest.
 */
internal fun Modifier.sendTarget(motion: SendMotion?, id: String, text: String, part: SendTargetPart, fade: Float = 1f): Modifier {
    if (motion == null) return this
    val placed = onPlaced { motion.place(id, text, part, it, fade) }
    return if (part == SendTargetPart.Surface) placed.drawWithContent { if (!motion.hides(id, text)) drawContent() } else placed
}

/** Whether the composer at [anchor] reads as empty yet: its placeholder waits for the text it held to leave. */
internal fun Modifier.sendPlaceholder(motion: SendMotion?, anchor: ComposerAnchor?): Modifier =
    if (motion == null || anchor == null) this else graphicsLayer { alpha = motion.placeholderAlpha(anchor) }

/** The field of the composer at [anchor]: see [SendMotion.fieldAlpha]. */
internal fun Modifier.sendSource(motion: SendMotion?, anchor: ComposerAnchor?): Modifier =
    if (motion == null || anchor == null) this else graphicsLayer { alpha = motion.fieldAlpha(anchor) }

/**
 * Provides [LocalSendMotion] to [content] and draws the flight over it: the whole window's width and height, so the
 * text crosses from one screen to the next and past the panes of a wide window. Nothing here takes a touch.
 */
@Composable
fun SendMotionHost(motion: SendMotion = remember { SendMotion() }, content: @Composable () -> Unit) {
    val flight = motion.flight
    LaunchedEffect(flight) {
        val f = flight ?: return@LaunchedEffect
        val placed = withTimeoutOrNull(f.holdMillis) { snapshotFlow { f.targetPlaced }.first { it } }
        if (placed == null) {
            f.phase = SendFlight.Phase.Fading
            f.fade.animateTo(1f, tween(SendMotion.FadeMillis))
        } else {
            f.phase = SendFlight.Phase.Flying
            f.progress.animateTo(1f, tween(SendMotion.FlightMillis, easing = SendMotion.Emphasized))
        }
        motion.finish(f)
    }
    DisposableEffect(motion) { onDispose { motion.flight?.let(motion::finish) } }
    CompositionLocalProvider(LocalSendMotion provides motion) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (flight != null) FlightOverlay(flight)
        }
    }
}

@Composable
private fun FlightOverlay(flight: SendFlight) {
    val colors = CursorTheme.colors
    val style = CursorTheme.typography.message
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Canvas(Modifier.fillMaxSize().onPlaced { holder[0] = it }) {
        val own = holder[0]?.takeIf { it.isAttached } ?: return@Canvas
        val shift = -own.positionInWindow()
        val takeoff = flight.takeoff
        when (flight.phase) {
            SendFlight.Phase.Holding, SendFlight.Phase.Fading -> {
                val alpha = 1f - flight.fade.value
                clipTo(takeoff.viewport.translate(shift).inflate(ClipSlack.toPx())) {
                    drawText(takeoff.layout, color = colors.textPrimary, topLeft = takeoff.origin + shift, alpha = alpha)
                }
            }
            SendFlight.Phase.Flying -> drawFlight(flight, shift, measurer, style, colors.textPrimary, colors.fillFaint, colors.stroke)
        }
    }
}

private fun DrawScope.drawFlight(
    flight: SendFlight,
    shift: Offset,
    measurer: TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    fill: Color,
    stroke: Color,
) {
    val takeoff = flight.takeoff
    val surface = flight.surface?.takeIf { it.isAttached }?.windowRect() ?: return
    val textBox = flight.textBox?.takeIf { it.isAttached }?.windowRect() ?: return
    val t = flight.progress.value
    val e = SendMotion.Emphasized.transform(t)
    // Past the hand-over the bubble itself is drawn under the copy, which fades off it.
    val alpha = if (t < SendMotion.HandOff) 1f else 1f - (t - SendMotion.HandOff) / (1f - SendMotion.HandOff)
    val fade = flight.targetFade
    val padH = BubblePadH.toPx()
    val padV = BubblePadV.toPx()
    val from = takeoff.viewport.let { Rect(it.left - padH, it.top - padV, it.right + padH, it.top + takeoff.layout.size.height.coerceAtMost(it.height.toInt()) + padV) }
    val box = lerpRect(from, surface, e).translate(shift)
    val radius = CornerRadius(BubbleRadius.toPx())
    val grown = (e / SurfaceGrow).coerceIn(0f, 1f)
    drawRoundRect(fill.copy(alpha = fill.alpha * fade * grown), box.topLeft, box.size, radius, alpha = alpha)
    val hairline = 1.dp.toPx()
    drawRoundRect(
        stroke.copy(alpha = stroke.alpha * fade * grown),
        box.topLeft + Offset(hairline / 2, hairline / 2),
        Size(box.width - hairline, box.height - hairline),
        radius,
        style = Stroke(hairline),
        alpha = alpha,
    )
    val origin = lerpOffset(takeoff.origin, textBox.topLeft, e) + shift
    val color = lerp(textColor, textColor.copy(alpha = textColor.alpha * fade), e)
    val source = takeoff.layout
    val target = measurer.measure(flight.text, style, constraints = Constraints(maxWidth = textBox.width.toInt().coerceAtLeast(1)))
    val clip = lerpRect(takeoff.viewport, Rect(textBox.topLeft, Size(textBox.width, target.size.height.toFloat())), e).translate(shift).inflate(ClipSlack.toPx())
    clipTo(clip) {
        if (sameLines(source, target)) {
            drawText(target, color = color, topLeft = origin, alpha = alpha)
        } else {
            // Re-wrapped on the way: the field's lines give way to the bubble's over the middle of the flight.
            val swap = ((e - RewrapFrom) / (RewrapTo - RewrapFrom)).coerceIn(0f, 1f)
            translate(origin.x, origin.y) {
                if (swap < 1f) drawText(source, color = color, alpha = alpha * (1f - swap))
                if (swap > 0f) drawText(target, color = color, alpha = alpha * swap)
            }
        }
    }
}

/** Whether two layouts of the text break it into the same lines, so one can stand in for the other unseen. */
private fun sameLines(a: TextLayoutResult, b: TextLayoutResult): Boolean {
    if (a.layoutInput.text.text.trim() != b.layoutInput.text.text.trim() || a.lineCount != b.lineCount) return false
    for (line in 0 until a.lineCount) if (a.getLineEnd(line, visibleEnd = true) != b.getLineEnd(line, visibleEnd = true)) return false
    return true
}

private inline fun DrawScope.clipTo(rect: Rect, block: DrawScope.() -> Unit) = clipRect(rect.left, rect.top, rect.right, rect.bottom, block = block)

private fun LayoutCoordinates.windowRect(): Rect = Rect(positionInWindow(), Size(size.width.toFloat(), size.height.toFloat()))

private fun lerpOffset(a: Offset, b: Offset, f: Float) = Offset(lerp(a.x, b.x, f), lerp(a.y, b.y, f))

private fun lerpRect(a: Rect, b: Rect, f: Float) = Rect(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))

/** The bubble's padding and corner (`HumanMessage`), which the surface grows into from around the text. */
private val BubblePadH = 10.dp
private val BubblePadV = 8.dp
private val BubbleRadius = 12.dp
/** Room past the text's box for the glyphs that reach out of it (descenders, italics). */
private val ClipSlack = 4.dp
/** How far into the flight the surface is fully drawn. */
private const val SurfaceGrow = 0.6f
private const val RewrapFrom = 0.2f
private const val RewrapTo = 0.7f

/**
 * A chat's docked composer, gliding down from where the New Chat composer stood as the chat it just started comes on
 * screen: null offsets for any other chat, and for this one once the glide is over or when animations are off.
 */
@Stable
class ArrivalGlide internal constructor(private val from: Rect?) {
    internal val progress = Animatable(if (from == null) 1f else 0f)
    internal var dock: LayoutCoordinates? = null

    internal fun offsetY(): Float {
        val start = from ?: return 0f
        val p = progress.value
        if (p >= 1f) return 0f
        val dock = dock?.takeIf { it.isAttached } ?: return 0f
        return (start.top - dock.positionInWindow().y) * (1f - SendMotion.Emphasized.transform(p))
    }
}

@Composable
fun rememberArrivalGlide(agentId: String): ArrivalGlide {
    val motion = LocalSendMotion.current
    val glide = remember(agentId) { ArrivalGlide(motion?.arrivalFor(agentId)) }
    LaunchedEffect(glide) { glide.progress.animateTo(1f, tween(SendMotion.GlideMillis, easing = LinearEasing)) }
    return glide
}

/** The docked stack's placement for [glide]: where it is laid out is read before the glide's offset is applied. */
fun Modifier.arrivalGlide(glide: ArrivalGlide): Modifier = onPlaced { glide.dock = it }.graphicsLayer { translationY = glide.offsetY() }
