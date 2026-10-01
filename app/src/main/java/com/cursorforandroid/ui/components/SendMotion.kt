package com.cursorforandroid.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

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
 *
 * The queue card over the composer is the same flight's other end ([SendLanding]): a message sent while the agent is on
 * a turn lifts off the composer the same way and lands in its row on the card, the row undrawn until the hand-over;
 * and when the run takes a queued message, its row lifts off the card ([QueueDeliveries]) — the card's surface with
 * it — and flies into the message's bubble. Several can be in the air at once ([flights]): a queued send and a
 * delivery, or two deliveries back to back.
 */
@Stable
class SendMotion(
    /** Whether the system lets anything animate; read at each send, so the setting is followed as it changes. */
    private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    private val inAir = mutableStateListOf<SendFlight>()

    /** The messages on their way, oldest first. */
    val flights: List<SendFlight> get() = inAir

    /** The latest message on its way, if one is. */
    val flight: SendFlight? get() = inAir.lastOrNull()

    /**
     * The composer (or queued row) at [takeoff] sent [text], which will land in a bubble or a queue row, as [landing]
     * allows, that is none of [excluded] (what the transcript and the card showed before the send): the text is lifted
     * off now. Null — nothing to fly — for a takeoff with nothing on it, or with animations off.
     */
    fun depart(
        takeoff: Takeoff?,
        text: String,
        excluded: Set<String> = emptySet(),
        holdMillis: Long = HoldMillis,
        landing: SendLanding = SendLanding.Bubble,
    ): SendFlight? {
        if (takeoff == null || (text.isBlank() && takeoff.attachments.isEmpty()) || !animatorsEnabled()) return null
        // A second send from the same composer takes the first one's copy down: one composer, one text in the air.
        inAir.removeAll { it.takeoff.anchor === takeoff.anchor }
        return SendFlight(takeoff, text.trim(), excluded, holdMillis, landing).also { inAir += it }
    }

    /** The flight from the New Chat composer is the chat [agentId]'s: its screen glides its composer in from there. */
    fun bind(flight: SendFlight?, agentId: String) {
        if (flight != null && inAir.any { it === flight }) flight.agentId = agentId
    }

    /** Takes [flight] down at once: the send it was for did not go out after all. */
    fun cancel(flight: SendFlight?) {
        if (flight != null) inAir.removeAll { it === flight }
    }

    internal fun finish(flight: SendFlight) {
        inAir.removeAll { it === flight }
    }

    /** Takes down a flight landing on the queue row [id], which has left the card before it arrived. */
    internal fun abandonRow(id: String) {
        inAir.removeAll { it.landedOn == SendLanding.Queue && it.targetId == id }
    }

    /** The New Chat composer's box as it stood at the tap, when this chat is the one it just started. */
    fun arrivalFor(agentId: String): Rect? = inAir.lastOrNull { it.agentId == agentId }?.takeoff?.composer

    /** Whether a bubble (or, by [landing], a queue row) for [id] saying [text] is one on its way, not yet handed over, and so not drawn. */
    fun hides(id: String, text: String, landing: SendLanding = SendLanding.Bubble): Boolean =
        inAir.any { it.accepts(id, text, landing) && it.phase != SendFlight.Phase.Fading && it.progress.value < HandOff }

    /** Whether a flight is still on its way into the bubble for [id] saying [text], its copy not yet gone from over it. */
    fun landingOn(id: String, text: String): Boolean =
        inAir.any { it.accepts(id, text, SendLanding.Bubble) && it.phase != SendFlight.Phase.Fading }

    /** The placeholder of the composer at [anchor], held back while the text it held is still over it. */
    fun placeholderAlpha(anchor: ComposerAnchor): Float {
        // Attachments sent alone left the placeholder where it was: there was no text over it.
        val f = inAir.lastOrNull { it.takeoff.anchor === anchor && it.text.isNotEmpty() } ?: return 1f
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
        val f = inAir.lastOrNull { it.takeoff.anchor === anchor } ?: return 1f
        val shown = anchor.layout?.invoke()?.layoutInput?.text?.text ?: return 1f
        return if (shown.trim() == f.takeoff.layout.layoutInput.text.text.trim()) 0f else 1f
    }

    /**
     * The attachment [key] of the composer at [anchor], undrawn while its copy is on its way to the bubble: a composer
     * emptied a frame late would show it twice, and a refused send brings it back with the flight gone.
     */
    fun attachmentAlpha(anchor: ComposerAnchor, key: String): Float =
        if (inAir.any { f -> f.takeoff.anchor === anchor && f.takeoff.attachments.any { it.key == key } }) 0f else 1f

    /** The bubble or queue row ([landing]) for [id] saying [text] has placed its [part]; see [sendTarget]. */
    internal fun place(id: String, text: String, landing: SendLanding, part: SendTargetPart, coordinates: LayoutCoordinates, look: SendTargetLook) {
        for (f in inAir) if (f.accepts(id, text, landing)) f.place(id, landing, part, coordinates, look)
    }

    /** The bubble or queue row for [id] saying [text] has laid out its attachment [ordinal] (its place in the prompt's attachments) at [coordinates]. */
    internal fun placeAttachment(id: String, text: String, landing: SendLanding, ordinal: Int, coordinates: LayoutCoordinates) {
        for (f in inAir) if (f.accepts(id, text, landing)) f.targetFor(id, landing).attachments[ordinal] = coordinates
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
        /**
         * The same for a queued message the run has taken: its row has left the card, and the transcript files it under
         * the run as the server's word comes back, which the card does not wait for.
         */
        const val DeliveryHoldMillis = 1_200L
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
    /** Where it may land. */
    val landing: SendLanding = SendLanding.Bubble,
) {
    enum class Phase { Holding, Flying, Fading }

    var phase by mutableStateOf(Phase.Holding)
        internal set

    /** The chat a New Chat composer's flight started, once it is open. */
    var agentId by mutableStateOf<String?>(null)
        internal set

    /** 0 at the composer, 1 in the bubble: the flight's time, not yet eased. */
    val progress = Animatable(0f)

    /** 0 while the text stands, 1 once a flight that found no bubble has faded out. */
    val fade = Animatable(0f)

    /** True once both of the target's boxes have been placed. */
    var targetPlaced by mutableStateOf(false)
        internal set

    /** What it is landing on — a bubble or a queue row — once that is placed. */
    var landedOn by mutableStateOf<SendLanding?>(null)
        internal set

    /** The id of the bubble or row it is landing on, once that is placed. */
    var targetId: String? = null
        internal set

    /**
     * Whether the attachments' copies have been laid out. A queued row that leaves goes in the frame its flight is
     * started, after the host has composed that frame's copies; until they are up, the overlay draws the row's tiles.
     */
    internal var copiesPlaced = false

    /**
     * One place the flight could land, as laid out: its surface and text boxes, how each looks, and its attachments by
     * their place in the prompt's (the ones a scrolling row has not reached are not there). Kept per id: a bubble filed
     * under the run's id replaces the staged one mid-flight, and its boxes are not to be mixed with the old one's.
     */
    internal class Target(val landing: SendLanding) {
        var surface: LayoutCoordinates? = null
        var textBox: LayoutCoordinates? = null
        var look = SendTargetLook.Bubble
        val attachments = HashMap<Int, LayoutCoordinates>()
    }

    private val targets = HashMap<String, Target>()

    internal fun targetFor(id: String, landing: SendLanding): Target = targets.getOrPut(id) { Target(landing) }

    /** The target being flown to: the last one whose boxes were both placed. */
    internal val target: Target? get() = targetId?.let(targets::get)

    internal fun place(id: String, landing: SendLanding, part: SendTargetPart, coordinates: LayoutCoordinates, look: SendTargetLook) {
        val t = targetFor(id, landing)
        when (part) {
            SendTargetPart.Surface -> t.surface = coordinates
            SendTargetPart.Text -> t.textBox = coordinates
        }
        t.look = t.look.merge(part, look)
        if (t.surface != null && (t.textBox != null || text.isEmpty())) {
            targetId = id
            landedOn = landing
            targetPlaced = true
        }
    }

    /** Whether the bubble or queue row ([kind]) for [id] saying [text] is one this flight may land on. */
    internal fun accepts(id: String, text: String, kind: SendLanding): Boolean = landing.admits(kind) && matches(id, text)

    /**
     * Whether the bubble (or row) for [id] saying [text] is this flight's. Attachments sent alone are said in the
     * bubble in words the composer never held ("See the attached image."), so for them any new bubble is. Words are
     * compared with their whitespace folded, as the card, the transcript and the account compare them.
     */
    fun matches(id: String, text: String): Boolean =
        id !in excluded && (this.text.isEmpty() || text.trim() == this.text || words(text) == folded)

    private val folded = words(text)

    /** The flying copies' opacity: whole until the hand-over, then fading off the bubble drawn under them. */
    internal fun copyAlpha(): Float {
        val t = progress.value
        return if (t < SendMotion.HandOff) 1f else 1f - (t - SendMotion.HandOff) / (1f - SendMotion.HandOff)
    }

    /** Where [attachment] is laid out in the target (window coordinates), once it is. */
    internal fun targetOf(attachment: AttachmentTakeoff): Rect? = placeOf(attachment)?.takeIf { it.isAttached }?.windowRect()

    private fun placeOf(attachment: AttachmentTakeoff): LayoutCoordinates? = target?.attachments?.get(attachment.look.ordinal)

    /** Where [attachment]'s copy is drawn now (window coordinates): where it stood until it flies, then on its way to the bubble. */
    internal fun boxOf(attachment: AttachmentTakeoff): Rect {
        if (phase != Phase.Flying) return attachment.rect
        val target = targetOf(attachment) ?: return attachment.rect
        return lerpRect(attachment.rect, target, SendMotion.Emphasized.transform(progress.value))
    }

    /** [attachment]'s copy's opacity now: arriving at the target's own fade, or fading where it stood with nowhere to go. */
    internal fun alphaOf(attachment: AttachmentTakeoff): Float {
        if (phase != Phase.Flying) return 1f - fade.value
        val e = SendMotion.Emphasized.transform(progress.value)
        if (targetOf(attachment) == null) return 1f - e
        return copyAlpha() * lerp(1f, target?.look?.fade ?: 1f, e)
    }
}

/**
 * Where a flight lands: the message's bubble in the transcript, its row on the queue card, or whichever of the two shows
 * it (a queued message the run took lands in its bubble; one another queue took, in that queue's row).
 */
enum class SendLanding {
    Bubble,
    Queue,
    Either,
    ;

    internal fun admits(kind: SendLanding): Boolean = this == Either || this == kind
}

/**
 * How a flight's target is drawn at rest, for the copy to arrive at it: its surface (null: a bubble's, from the theme)
 * and the fade it is drawn at, its text as laid out (null: measured to the box, in the bubble's style) and the colour
 * of that text (null: the theme's), which is drawn at [fade] too. A target whose fade moves — a queued card behind the
 * front of a stacked queue, its face fading as it springs, or a bubble coming up from its sending fade as its message
 * is filed mid-flight — says so with [fading], read at each frame. Each part says what it knows; [merge] keeps both.
 */
@Immutable
class SendTargetLook(
    val surface: SendSurface? = null,
    private val fixedFade: Float = 1f,
    val layout: (() -> TextLayoutResult?)? = null,
    val textColor: Color? = null,
    private val fading: (() -> Float)? = null,
    /** Where, in the window, something stands in front of the target (the front card of a stacked queue): the copy goes behind it. */
    val cover: (() -> Rect?)? = null,
) {
    val fade: Float get() = fading?.invoke() ?: fixedFade

    internal fun merge(part: SendTargetPart, other: SendTargetLook): SendTargetLook = when (part) {
        SendTargetPart.Surface -> SendTargetLook(other.surface, other.fixedFade, layout, textColor, other.fading, other.cover)
        SendTargetPart.Text -> SendTargetLook(surface, fixedFade, other.layout, other.textColor, fading, cover)
    }

    companion object {
        val Bubble = SendTargetLook()
    }
}

/**
 * A card's surface as a flight draws it: [fill] over [page] — laid under a translucent fill, so what the copy crosses
 * does not show through it — edged with [stroke].
 */
@Immutable
class SendSurface(val fill: Color, val stroke: Color, val page: Color = Color.Transparent)

/** The words of a message as they compare across the composer, the card and the transcript: whitespace folded. */
private fun words(text: String): String = text.replace(Whitespace, " ").trim()

private val Whitespace = Regex("\\s+")

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
    /** What was attached and in view in the composer's row, left to right. */
    val attachments: List<AttachmentTakeoff> = emptyList(),
    /**
     * The surface lifted off with the text, when that is a queued row's card leaving the card stack: the flight starts
     * as that card, in [composer]'s box, and becomes the bubble. Null for a composer, which stays where it is.
     */
    val card: SendSurface? = null,
)

/**
 * One attachment as the flight draws it, from what the composer knew of it: a picture's or a recording's tile, or a
 * file's card with its name, kind and size. [ordinal] is its place in the prompt's attachments — the images first,
 * then the files, each in the order attached — which is the order the message's bubble lists them in.
 */
@Immutable
class SendAttachment(
    val ordinal: Int,
    val thumbnail: ImageBitmap?,
    /** A tile (a picture, a recording) rather than a file's card. */
    val media: Boolean,
    val video: Boolean = false,
    val name: String? = null,
    val kind: PromptFileKind = PromptFileKind.Image,
    val sizeBytes: Long = 0L,
)

/** An attachment lifted off the composer: what it is, which chip it was ([key]) and where that stood (window coordinates). */
class AttachmentTakeoff internal constructor(val key: String, val look: SendAttachment, val rect: Rect)

/**
 * Where a composer's text stands: the field and the box, as last placed, and the field's text layout. Plain fields,
 * written as the composer lays out and read at the tap; nothing reads them in composition. A queued row on the card is
 * one too, its line the field and its card the box ([card]), read as it leaves the card (see [QueueDeliveries]).
 */
@Stable
class ComposerAnchor {
    internal var field: LayoutCoordinates? = null
    internal var surface: LayoutCoordinates? = null
    internal var layout: (() -> TextLayoutResult?)? = null
    internal var scroll: () -> Int = { 0 }
    /** The card's surface, for a queued row: it lifts off with the text. */
    internal var card: SendSurface? = null
    /** The chips of the attachment row, by key: what each is and where it was last placed. */
    internal val attachments = HashMap<String, Pair<SendAttachment, LayoutCoordinates?>>()

    /** The text and the attachments as they stand now, lifted off for a flight; null when there is nothing to lift. */
    fun takeoff(): Takeoff? {
        val field = field?.takeIf { it.isAttached } ?: return null
        val surface = surface?.takeIf { it.isAttached } ?: return null
        val layout = layout?.invoke() ?: return null
        // A chip scrolled out of the row's view has nothing to lift off; one cut by its edge flies whole.
        val attached = attachments.mapNotNull { (key, entry) ->
            val coordinates = entry.second?.takeIf { it.isAttached && !it.boundsInWindow().isEmpty } ?: return@mapNotNull null
            AttachmentTakeoff(key, entry.first, coordinates.windowRect())
        }.sortedBy { it.rect.left }
        if (layout.layoutInput.text.isBlank() && attached.isEmpty()) return null
        val corner = field.positionInWindow()
        return Takeoff(
            anchor = this,
            layout = layout,
            origin = corner - Offset(0f, scroll().toFloat()),
            viewport = Rect(corner, Size(field.size.width.toFloat(), field.size.height.toFloat())),
            composer = surface.windowRect(),
            attachments = attached,
            card = card,
        )
    }
}

/**
 * The composer's attachment chip [key], showing [look], as something a send lifts off: where it is placed is noted for
 * the tap, and while its copy is on its way to the bubble the chip itself is undrawn ([SendMotion.attachmentAlpha]).
 */
@Composable
internal fun Modifier.sendAttachmentSource(motion: SendMotion?, anchor: ComposerAnchor?, key: String, look: SendAttachment): Modifier {
    if (motion == null || anchor == null) return this
    SideEffect { anchor.attachments[key] = look to anchor.attachments[key]?.second }
    DisposableEffect(anchor, key) { onDispose { anchor.attachments.remove(key) } }
    return onPlaced { anchor.attachments[key] = (anchor.attachments[key]?.first ?: look) to it }
        .graphicsLayer { alpha = motion.attachmentAlpha(anchor, key) }
}

/**
 * A bubble's (or, by [landing], a queue row's) attachment [ordinal] as a send's target: it says where it is laid out to
 * a flight bound for it.
 */
internal fun Modifier.sendAttachmentTarget(motion: SendMotion?, id: String, text: String, ordinal: Int, landing: SendLanding = SendLanding.Bubble): Modifier =
    if (motion == null) this else onPlaced { motion.placeAttachment(id, text, landing, ordinal, it) }

/** The composition's [SendMotion]; null outside the shell (a screen drawn alone, as the screenshot tests draw them). */
val LocalSendMotion = staticCompositionLocalOf<SendMotion?> { null }

internal enum class SendTargetPart { Surface, Text }

/**
 * A bubble's [part] as a send's target: it says where it is laid out to a flight bound for it, and a bubble that is
 * that flight's is drawn only from the hand-over on. [fade] is the bubble's own fade at rest. A queue row is one too
 * ([landing] [SendLanding.Queue]), saying how it looks ([look]): its card, and its one line as laid out.
 */
internal fun Modifier.sendTarget(
    motion: SendMotion?,
    id: String,
    text: String,
    part: SendTargetPart,
    fade: Float = 1f,
    landing: SendLanding = SendLanding.Bubble,
    look: SendTargetLook = if (fade == 1f) SendTargetLook.Bubble else SendTargetLook(fixedFade = fade),
): Modifier {
    if (motion == null) return this
    val placed = onPlaced { motion.place(id, text, landing, part, it, look) }
    return if (part == SendTargetPart.Surface) placed.drawWithContent { if (!motion.hides(id, text, landing)) drawContent() } else placed
}

/** Whether the composer at [anchor] reads as empty yet: its placeholder waits for the text it held to leave. */
internal fun Modifier.sendPlaceholder(motion: SendMotion?, anchor: ComposerAnchor?): Modifier =
    if (motion == null || anchor == null) this else graphicsLayer { alpha = motion.placeholderAlpha(anchor) }

/** The field of the composer at [anchor]: see [SendMotion.fieldAlpha]. */
internal fun Modifier.sendSource(motion: SendMotion?, anchor: ComposerAnchor?): Modifier =
    if (motion == null || anchor == null) this else graphicsLayer { alpha = motion.fieldAlpha(anchor) }

/**
 * Provides [LocalSendMotion] to [content] and draws the flight over it: the whole window's width and height, so the
 * text crosses from one screen to the next and past the panes of a wide window. Nothing here takes a touch. Inside
 * another host, and given no [motion] of its own, it leaves the flight to that one.
 */
@Composable
fun SendMotionHost(motion: SendMotion? = null, content: @Composable () -> Unit) {
    val outer = LocalSendMotion.current
    if (motion == null && outer != null) {
        content()
        return
    }
    FlightHost(motion ?: remember { SendMotion() }, content)
}

@Composable
private fun FlightHost(motion: SendMotion, content: @Composable () -> Unit) {
    FlightRunner(motion)
    DisposableEffect(motion) { onDispose { motion.flights.toList().forEach(motion::finish) } }
    CompositionLocalProvider(LocalSendMotion provides motion) {
        Box(Modifier.fillMaxSize()) {
            content()
            // Always there, reading the flights as it draws: composed only with a flight, it would first draw a frame
            // after the composer had let the text go.
            FlightOverlay(motion)
            // Composed with each flight, in the frame of the tap: the chips it lifts off are undrawn from that frame's draw.
            for (flight in motion.flights) key(flight) { AttachmentFlights(flight) }
        }
    }
}

/** Each flight's time: held for its target, then flown there — or, with none in time, faded where it stood. */
@Composable
private fun FlightRunner(motion: SendMotion) {
    for (flight in motion.flights) {
        key(flight) {
            LaunchedEffect(flight) {
                val placed = withTimeoutOrNull(flight.holdMillis) { snapshotFlow { flight.targetPlaced }.first { it } }
                if (placed == null) {
                    flight.phase = SendFlight.Phase.Fading
                    flight.fade.animateTo(1f, tween(SendMotion.FadeMillis))
                } else {
                    flight.phase = SendFlight.Phase.Flying
                    // Linear in time: the drawing eases it (Emphasized), and the hand-over is a point in time.
                    flight.progress.animateTo(1f, tween(SendMotion.FlightMillis, easing = LinearEasing))
                }
                motion.finish(flight)
            }
        }
    }
}

/**
 * The attachments on their way: each chip lifted off the composer is drawn where it stood, then travels — growing or
 * shrinking, re-laid out at every size, a picture re-cropped rather than stretched — to its thumbnail or card in the
 * bubble, and fades off it from the hand-over on, as the text does. One whose place in the bubble is out of view (a
 * scrolling row past its end) fades where it stood. Only placement and drawing read the flight's frames.
 */
@Composable
private fun AttachmentFlights(flight: SendFlight) {
    if (flight.takeoff.attachments.isEmpty()) return
    val host = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(Modifier.fillMaxSize().onPlaced { host[0] = it; flight.copiesPlaced = true }) {
        for (attachment in flight.takeoff.attachments) {
            key(attachment.key) {
                Box(
                    Modifier
                        .layout { measurable, _ ->
                            val box = flight.boxOf(attachment)
                            val origin = host[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
                            val width = box.width.roundToInt().coerceAtLeast(1)
                            val height = box.height.roundToInt().coerceAtLeast(1)
                            val placeable = measurable.measure(Constraints.fixed(width, height))
                            layout(width, height) { placeable.place((box.left - origin.x).roundToInt(), (box.top - origin.y).roundToInt()) }
                        }
                        .testTag(FlyingAttachmentTag),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                val box = flight.boxOf(attachment)
                                behind(flight.target?.look?.cover?.invoke()?.translate(-box.topLeft)) { this@drawWithContent.drawContent() }
                            }
                            .graphicsLayer { alpha = flight.alphaOf(attachment) },
                    ) { AttachmentFace(attachment.look) }
                }
            }
        }
    }
}

/** An attachment as it flies: the bubble's thumbnail for a picture or a recording, its card for any other file. */
@Composable
private fun AttachmentFace(look: SendAttachment) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    if (look.media) {
        // A bubble's thumbnail and a queued row's tile: the corners tighten as the copy comes down to the tile's size.
        val tile = CursorTheme.shapes.sm
        val shape = remember(tile, shape) { NarrowingCorners(tile, shape) }
        Box(Modifier.fillMaxSize().cursorSurface(if (look.video) Color.Black else colors.fill, colors.stroke, shape), contentAlignment = Alignment.Center) {
            if (look.thumbnail != null) {
                Image(look.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!look.video) {
                Icon(CursorIcons.Image, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            }
            if (look.video) {
                Box(Modifier.size(28.dp).background(Color.White.copy(alpha = 0.92f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(CursorIcons.Play, null, tint = Color(0xFF141414), modifier = Modifier.size(15.dp).padding(start = 1.dp))
                }
            }
        }
    } else {
        // A queued row shows a file as a glyph on a small tile, the bubble and the composer as a card with its name: on
        // the way between the two, the card gives way to the tile as the copy narrows (read in draw, from its size).
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().graphicsLayer { alpha = cardShare(size.width, this) }.cursorSurface(colors.fill, colors.stroke, shape).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (look.thumbnail != null) {
                    Image(look.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp).clip(CursorTheme.shapes.sm))
                } else {
                    Icon(look.kind.icon(), null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(look.name ?: "Document", style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${look.kind.label} · ${PromptFile.formatSize(look.sizeBytes)}", style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = 1f - cardShare(size.width, this) }.cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.kind.icon(), null, tint = colors.iconTertiary, modifier = Modifier.fillMaxSize(0.55f))
            }
        }
    }
}

/** How much of a file's flying copy is its card rather than its tile, at [width] px: the card from [CardFrom] wide, the tile under [TileUpTo]. */
private fun cardShare(width: Float, density: androidx.compose.ui.unit.Density): Float = with(density) {
    ((width - TileUpTo.toPx()) / (CardFrom.toPx() - TileUpTo.toPx())).coerceIn(0f, 1f)
}

/**
 * [small]'s corners at a queued row's tile, [large]'s from anything bigger — a composer chip, a bubble's thumbnail however
 * narrow — and between the two on the way: read from the longer side it is laid out at.
 */
private class NarrowingCorners(private val small: Shape, private val large: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline {
        fun radius(shape: Shape) = (shape.createOutline(size, layoutDirection, density) as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        val tight = radius(small)
        val share = with(density) { ((maxOf(size.width, size.height) - TightUpTo.toPx()) / (RoundFrom.toPx() - TightUpTo.toPx())).coerceIn(0f, 1f) }
        val corner = tight + (radius(large) - tight) * share
        return Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(corner)))
    }
}

private val TightUpTo = 18.dp
private val RoundFrom = 40.dp

private val TileUpTo = 32.dp
private val CardFrom = 72.dp

/** The test tag of an attachment's flying copy. */
const val FlyingAttachmentTag = "flying-attachment"

@Composable
private fun FlightOverlay(motion: SendMotion) {
    val colors = CursorTheme.colors
    val style = CursorTheme.typography.message
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tile = CursorTheme.shapes.sm
    Canvas(Modifier.fillMaxSize().onPlaced { holder[0] = it }) {
        val own = holder[0]?.takeIf { it.isAttached } ?: return@Canvas
        val shift = -own.positionInWindow()
        val bubble = SendSurface(colors.fillFaint, colors.stroke, page = colors.canvas)
        for (flight in motion.flights) {
            val takeoff = flight.takeoff
            when (flight.phase) {
                SendFlight.Phase.Holding, SendFlight.Phase.Fading -> {
                    val alpha = 1f - flight.fade.value
                    // A queued row's card stands where it was with its line, as the row it lifted off from goes.
                    takeoff.card?.let { card -> drawSurface(takeoff.composer.translate(shift), card.page, card.fill, card.stroke, alpha) }
                    clipTo(takeoff.viewport.translate(shift).inflate(ClipSlack.toPx())) {
                        drawText(takeoff.layout, color = colors.textPrimary, topLeft = takeoff.origin + shift, alpha = alpha)
                    }
                    if (!flight.copiesPlaced) {
                        for (attachment in takeoff.attachments) drawTile(attachment, shift, tile, colors.fill, colors.stroke, alpha)
                    }
                }
                SendFlight.Phase.Flying -> drawFlight(flight, shift, measurer, style, colors.textPrimary, bubble)
            }
        }
    }
}

/** A queued row's tile as it stood — its picture, or a plain tile for a file — for the frame before its copy is up. */
private fun DrawScope.drawTile(attachment: AttachmentTakeoff, shift: Offset, shape: Shape, fill: Color, stroke: Color, alpha: Float) {
    val box = attachment.rect.translate(shift)
    val outline = shape.createOutline(box.size, layoutDirection, this)
    translate(box.left, box.top) {
        val picture = attachment.look.thumbnail?.takeIf { attachment.look.media }
        if (picture == null) {
            drawOutline(outline, fill, alpha = alpha)
        } else {
            val scale = maxOf(box.width / picture.width, box.height / picture.height)
            val crop = IntSize((box.width / scale).roundToInt().coerceIn(1, picture.width), (box.height / scale).roundToInt().coerceIn(1, picture.height))
            clipPath(Path().apply { addOutline(outline) }) {
                drawImage(
                    picture,
                    srcOffset = IntOffset((picture.width - crop.width) / 2, (picture.height - crop.height) / 2),
                    srcSize = crop,
                    dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
                    alpha = alpha,
                )
            }
        }
        drawOutline(outline, stroke, alpha = alpha, style = Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.drawSurface(box: Rect, page: Color, fill: Color, stroke: Color, alpha: Float) {
    val radius = CornerRadius(BubbleRadius.toPx())
    drawRoundRect(page, box.topLeft, box.size, radius, alpha = alpha)
    drawRoundRect(fill, box.topLeft, box.size, radius, alpha = alpha)
    val hairline = 1.dp.toPx()
    drawRoundRect(
        stroke,
        box.topLeft + Offset(hairline / 2, hairline / 2),
        Size(box.width - hairline, box.height - hairline),
        radius,
        style = Stroke(hairline),
        alpha = alpha,
    )
}

private fun DrawScope.drawFlight(
    flight: SendFlight,
    shift: Offset,
    measurer: TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    bubble: SendSurface,
) {
    val takeoff = flight.takeoff
    val target = flight.target ?: return
    val surface = target.surface?.takeIf { it.isAttached }?.windowRect() ?: return
    val textBox = target.textBox?.takeIf { it.isAttached }?.windowRect()
    if (textBox == null && flight.text.isNotEmpty()) return
    val t = flight.progress.value
    val e = SendMotion.Emphasized.transform(t)
    val alpha = flight.copyAlpha()
    val look = target.look
    val fade = look.fade
    val landing = look.surface ?: bubble
    val card = takeoff.card
    // A composer stays where it is, so the surface grows from around what was lifted off it: the text's lines, and the
    // attachments' chips above them. A queued row's card lifts off whole, and becomes the bubble (or the other row).
    val from = if (card != null) {
        takeoff.composer
    } else {
        val padH = BubblePadH.toPx()
        val padV = BubblePadV.toPx()
        val lifted = takeoff.attachments.map { it.rect } +
            listOfNotNull(takeoff.viewport.takeIf { flight.text.isNotEmpty() }?.let { Rect(it.left, it.top, it.right, it.top + takeoff.layout.size.height.coerceAtMost(it.height.toInt())) })
        lifted.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
            .let { Rect(it.left - padH, it.top - padV, it.right + padH, it.bottom + padV) }
    }
    val box = lerpRect(from, surface, e).translate(shift)
    behind(look.cover?.invoke()?.translate(shift)) { drawCopy(flight, box, textBox, card, landing, fade, alpha, e, shift, measurer, style, textColor) }
}

private fun DrawScope.drawCopy(
    flight: SendFlight,
    box: Rect,
    textBox: Rect?,
    card: SendSurface?,
    landing: SendSurface,
    fade: Float,
    alpha: Float,
    e: Float,
    shift: Offset,
    measurer: TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
    textColor: Color,
) {
    val takeoff = flight.takeoff
    val look = flight.target?.look ?: return
    val grown = if (card != null) 1f else (e / SurfaceGrow).coerceIn(0f, 1f)
    fun tone(start: Color?, end: Color): Color {
        val rest = end.copy(alpha = end.alpha * fade)
        return if (start == null) rest.copy(alpha = rest.alpha * grown) else lerp(start, rest, e)
    }
    // The bubble's fill is a tint of the page; over the transcript it crosses, the page is laid under it first, so
    // what it passes over does not show through.
    drawSurface(box, tone(card?.page, landing.page), tone(card?.fill, landing.fill), tone(card?.stroke, landing.stroke), alpha)
    if (textBox == null || flight.text.isEmpty()) return
    val origin = lerpOffset(takeoff.origin, textBox.topLeft, e) + shift
    val color = lerp(textColor, (look.textColor ?: textColor).let { it.copy(alpha = it.alpha * fade) }, e)
    val source = takeoff.layout
    // A row says how its one line is laid out; a bubble's text is measured to its box, in the bubble's style.
    val landed = look.layout?.invoke()
        ?: measurer.measure(flight.text, style, constraints = Constraints(maxWidth = textBox.width.toInt().coerceAtLeast(1)))
    val clip = lerpRect(takeoff.viewport, Rect(textBox.topLeft, Size(textBox.width, landed.size.height.toFloat())), e).translate(shift).inflate(ClipSlack.toPx())
    clipTo(clip) {
        if (sameLines(source, landed)) {
            drawText(landed, color = color, topLeft = origin, alpha = alpha)
        } else {
            // Re-wrapped on the way: the field's lines give way to the bubble's over the middle of the flight.
            val swap = ((e - RewrapFrom) / (RewrapTo - RewrapFrom)).coerceIn(0f, 1f)
            translate(origin.x, origin.y) {
                if (swap < 1f) drawText(source, color = color, alpha = alpha * (1f - swap))
                if (swap > 0f) drawText(landed, color = color, alpha = alpha * swap)
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

/** [block] drawn everywhere but inside [cover], when there is one: behind what stands there. */
private inline fun DrawScope.behind(cover: Rect?, block: DrawScope.() -> Unit) {
    if (cover == null) block() else clipRect(cover.left, cover.top, cover.right, cover.bottom, ClipOp.Difference, block)
}

private inline fun DrawScope.clipTo(rect: Rect, block: DrawScope.() -> Unit) = clipRect(rect.left, rect.top, rect.right, rect.bottom, block = block)

/** Where this is drawn in the window, through any layer that scales it (a card at the back of a stacked queue). */
private fun LayoutCoordinates.windowRect(): Rect = Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width.toFloat(), size.height.toFloat())))

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
 * The queue card's rows as the send's other end: each row's anchor, by the queued message's id, for the row to lift off
 * from when the run takes the message ([QueueDeliveries]); and the rows the reader took off the card themselves —
 * removed, or taken back into the composer — which go nowhere, so nothing flies from them. Plain fields: written as
 * the rows compose and the deliveries are applied, never read to decide what to compose.
 */
@Stable
class QueueFlights {
    private val anchors = HashMap<String, ComposerAnchor>()
    private val dismissed = HashSet<String>()

    /** The rows as last composed, id to words, and the transcript's user messages then: what a delivery is told apart from. */
    internal var shown: Map<String, String> = emptyMap()
    internal var transcript: Set<String> = emptySet()
    /**
     * How many frames have seen a row leave the card for the transcript: the transcript, whose new bubble lands past
     * its edge in that frame, glides to it rather than jumping (see `TranscriptScroll.settleToNewest`).
     */
    var handovers = 0
        internal set

    /** The anchor of the row for [id]. */
    fun anchor(id: String): ComposerAnchor = anchors.getOrPut(id) { ComposerAnchor() }

    /** The reader took [id] off the card (removed it, or took it back to edit): nothing flies from its row. */
    fun dismiss(id: String) {
        dismissed += id
    }

    internal fun leaving(id: String): ComposerAnchor? = anchors[id]?.takeIf { id !in dismissed }

    internal fun keepOnly(ids: Set<String>) {
        anchors.keys.retainAll(ids)
        dismissed.retainAll(ids)
    }
}

@Composable
fun rememberQueueFlights(key: Any): QueueFlights = remember(key) { QueueFlights() }

private class Departure(val id: String, val text: String, val takeoff: Takeoff)

/**
 * A queued message leaving the card for the transcript, lifted off it the way a send lifts off the composer: its row
 * — card, line and tiles — flies into the message's bubble when the run takes it (or into the row another queue shows
 * it in: [SendLanding.Either]). [rows] are the card's rows, in order, id to the words the message will say; [transcript]
 * the ids of the transcript's user messages. A row that leaves is read here, in the composition of the frame it
 * leaves in: until that frame's changes are applied its layout is still where it was drawn, and after them there is
 * nothing to read. A bubble filed in that same frame is told apart by the ids the last frame had. With the reader
 * off the transcript's newest row ([scrolledAway]) the bubble lands out of view, and the copy fades where it stood
 * rather than waiting for it; with animations off nothing flies (see [SendMotion.depart]).
 */
@Composable
fun QueueDeliveries(flights: QueueFlights, rows: Map<String, String>, transcript: Set<String>, scrolledAway: () -> Boolean) {
    val motion = LocalSendMotion.current
    val leaving = if (motion == null) {
        emptyList()
    } else {
        flights.shown.mapNotNull { (id, text) -> if (id in rows) null else flights.leaving(id)?.takeoff()?.let { Departure(id, text, it) } }
    }
    SideEffect {
        if (motion != null && leaving.isNotEmpty()) {
            val excluded = flights.transcript + flights.shown.keys
            val hold = if (scrolledAway()) 0L else SendMotion.DeliveryHoldMillis
            for (departure in leaving) {
                // A send still landing on the row lands nowhere now; the row's own flight takes over from where it stood.
                motion.abandonRow(departure.id)
                motion.depart(departure.takeoff, departure.text, excluded, hold, SendLanding.Either)
            }
        }
        flights.shown = rows
        flights.transcript = transcript
        flights.keepOnly(rows.keys)
        if (leaving.isNotEmpty()) flights.handovers++
    }
}

/**
 * A queued row's card, between the dock's inset and its surface: where it stands, for it to lift off from, and — for a
 * send landing on the card — the row the copy flies to, undrawn, surface and all, until the hand-over. [fade] is how
 * much of the card's face shows (all of it but at the back of a stacked queue, where a send sinks into the deck), and
 * [cover] where the card in front of it stands, for the copy to go behind.
 */
internal fun Modifier.queueCard(
    motion: SendMotion?,
    anchor: ComposerAnchor?,
    id: String,
    text: String,
    card: SendSurface,
    fade: (() -> Float)? = null,
    cover: (() -> Rect?)? = null,
): Modifier {
    if (motion == null || anchor == null) return this
    anchor.card = card
    return onPlaced { anchor.surface = it }
        .sendTarget(motion, id, text, SendTargetPart.Surface, landing = SendLanding.Queue, look = SendTargetLook(surface = card, fading = fade, cover = cover))
}

/** A queued row's one line, laid out as [layout] says, in [color]: the text a delivery lifts off, and where a queued send's text lands. */
internal fun Modifier.queueLine(motion: SendMotion?, anchor: ComposerAnchor?, id: String, text: String, color: Color, layout: () -> TextLayoutResult?): Modifier {
    if (motion == null || anchor == null) return this
    anchor.layout = layout
    return onPlaced { anchor.field = it }
        .sendTarget(motion, id, text, SendTargetPart.Text, landing = SendLanding.Queue, look = SendTargetLook(layout = layout, textColor = color))
}

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
