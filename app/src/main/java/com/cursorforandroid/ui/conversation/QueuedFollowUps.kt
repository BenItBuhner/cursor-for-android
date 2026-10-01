package com.cursorforandroid.ui.conversation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.SteerPhase
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.produceState
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.ui.components.StylusTextInput
import com.cursorforandroid.util.ioThenMain
import com.cursorforandroid.ui.components.ComposerAnchor
import com.cursorforandroid.ui.components.LocalSendMotion
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendAttachment
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendSurface
import com.cursorforandroid.ui.components.queueCard
import com.cursorforandroid.ui.components.queueLine
import com.cursorforandroid.ui.components.sendAttachmentSource
import com.cursorforandroid.ui.components.sendAttachmentTarget
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.ImeEnterFallback
import com.cursorforandroid.ui.components.SlashCommandVisualTransformation
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.commandTints
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.highlightSlashCommands
import com.cursorforandroid.ui.components.icon
import com.cursorforandroid.ui.components.sendOnHardwareEnter
import com.cursorforandroid.ui.components.stylusWriting
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset
import com.cursorforandroid.ui.components.onContextClick

/**
 * The follow-ups waiting for the agent's turn to end, stacked above the composer in the order they will go out. Each
 * is one line in the composer's own surface ([dockedCard]: stood in from the box's sides so its corners are concentric
 * with the box's, however many are stacked) — the message verbatim, trailing off where the line ends, its
 * `/commands` painted as the composer painted them ([commandTints]), with the images it carries as small tiles
 * before it — and three small glyphs on the right: remove, edit, and the up arrow. Nothing else: a queue should read
 * as a list of what is about to be said, not as a stack of forms. While a turn is under way ([steers]) the up arrow
 * steers the message into that turn — never stopping it; with nothing running it sends the message next. A message
 * that could not be sent shows a warning where its tiles would be and the reason under the message, in red; the up
 * arrow then retries it. One on its way out shows a ring instead of the glyphs; a held one whose retry is out keeps
 * its glyphs, dimmed, until the server answers.
 *
 * A message steered into the running turn is on its way the same way: its glyphs dim, and it stays on its card
 * until the transcript shows it — "Steering…" on its second line, where a held card keeps its wait, while the
 * account takes it, then "Steered", the message dimmed throughout. A steer that did not go through leaves the card as it was,
 * the reason under the line in red, and the up arrow tries again.
 *
 * With [flights], each row is an end of the send's flight (see `SendMotion`): a message sent while the agent is busy
 * lands in its row from the composer, and a row the run takes lifts off the card into its bubble.
 */
@Composable
fun QueuedFollowUps(
    queue: List<QueuedFollowUp>,
    thumbnails: Map<String, ImageBitmap>,
    onEdit: (QueuedFollowUp) -> Unit,
    onSteer: (QueuedFollowUp) -> Unit,
    onRemove: (QueuedFollowUp) -> Unit,
    modifier: Modifier = Modifier,
    flights: QueueFlights? = null,
    /** A turn is under way: the up arrow steers into it (and says so) rather than sending next. */
    steers: Boolean = false,
    /** The row whose glyph tap was just refused, its message being on its way: its second line says so for a moment. */
    refusedId: String? = null,
) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        queue.forEachIndexed { index, item ->
            key(item.id) {
                QueuedFollowUpCard(item, index + 1, queue.size, thumbnails, onEdit, onSteer, onRemove, flights, steers = steers, refused = item.id == refusedId)
            }
        }
    }
}

/** One card of [QueuedFollowUps], alone: as a [QueueStack] draws it, told its [face]. */
@Composable
internal fun QueuedFollowUpCard(
    item: QueuedFollowUp,
    position: Int,
    count: Int,
    thumbnails: Map<String, ImageBitmap>,
    onEdit: (QueuedFollowUp) -> Unit,
    onSteer: (QueuedFollowUp) -> Unit,
    onRemove: (QueuedFollowUp) -> Unit,
    flights: QueueFlights?,
    face: QueueCardFace = QueueCardFace.Plain,
    steers: Boolean = false,
    refused: Boolean = false,
) {
    QueuedFollowUpRow(
        item = item,
        position = position,
        count = count,
        thumbnails = thumbnails,
        onEdit = { onEdit(item) },
        onSteer = { onSteer(item) },
        onRemove = { onRemove(item) },
        motion = LocalSendMotion.current,
        anchor = flights?.anchor(item.id),
        face = face,
        steers = steers,
        refused = refused,
    )
}

/** A queued row's card as a flight draws it: the card's own surface. */
@Composable
private fun queueCardSurface(): SendSurface = CursorTheme.colors.let { SendSurface(it.elevated, it.strokeSubtle) }

@Composable
private fun QueuedFollowUpRow(
    item: QueuedFollowUp,
    position: Int,
    count: Int,
    thumbnails: Map<String, ImageBitmap>,
    onEdit: () -> Unit,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
    motion: SendMotion?,
    anchor: ComposerAnchor?,
    face: QueueCardFace,
    steers: Boolean,
    refused: Boolean,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val words = item.previewText
    Row(
        Modifier
            .fillMaxWidth()
            // The card's own surface, stood in from the composer's sides so its corners are concentric with the box's.
            // The description sits on the surface, so the row's node is the card as drawn.
            .dockedCard(surface = Modifier.queueCard(motion, anchor, item.id, words, queueCardSurface(), face.contentAlpha, face.cover))
            .semantics { contentDescription = QueueCardWords.description(item, position, count) }
            .heightIn(min = RowHeight)
            .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 6.dp)
            .faceOf(face),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.warning != null) {
            Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(8.dp))
        } else if (item.images.isNotEmpty() || item.files.isNotEmpty()) {
            // The images first, then the files (a file keeps its glyph where an image has its tile; its name follows the message line below).
            val looks = item.images.mapIndexed { ordinal, image -> SendAttachment(ordinal, thumbnails[image.id], media = true) } +
                item.files.mapIndexed { index, file ->
                    SendAttachment(item.images.size + index, thumbnail = null, media = false, name = file.file.name, kind = file.file.kind, sizeBytes = file.file.sizeBytes.toLong())
                }
            QueueTiles(looks, motion, anchor, item.id, words)
            Spacer(Modifier.width(8.dp))
        }
        // A message the server keeps refusing as busy reads as waiting, steadily — one line, the time waited on it —
        // whether or not an attempt happens to be in flight this instant: the attempts are brief and the pauses
        // between them long, and a card that read "sending" for each would flicker between the two for as long as
        // the server took (see QueuedFollowUp.isHeld). The ring is for a first send only.
        val steer = item.steer
        val sending = item.isSending && !item.isHeld && steer == null
        // A card shown already steering has nothing to fade in from; one the reader steers eases its note in.
        val steeringAtFirst = remember { steer != null }
        Column(Modifier.weight(1f).padding(vertical = 4.dp).animateContentSize(tween(NoteFadeMs))) {
            // The commands dim with the rest of the line while it goes out.
            val textColor by animateColorAsState(if (sending || steer != null) colors.textTertiary else colors.textPrimary, tween(NoteFadeMs), label = "queue-line")
            QueueLine(words, textColor, motion, anchor, item.id)
            if (item.files.isNotEmpty()) AttachedFileNames(item.files.map { it.file.name })
            // Why it did not go, in the server's words or the connection's: without it the warning is only a riddle.
            item.warning?.let { note ->
                Text(note, style = type.small, color = colors.red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // A refused tap says why on the one second line the card has: a steer's note, else a held message's wait.
            val saysRefused = refused && item.isOnItsWay
            if (steer != null) {
                SteerNote(steer, refused = saysRefused, fadeIn = !steeringAtFirst)
            } else {
                item.steerError?.let { reason ->
                    Text(reason, style = type.small, color = colors.red, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(QueueSteerErrorTag))
                }
            }
            if (item.isHeld) HeldNote(item, refused = saysRefused && steer == null)
        }
        Spacer(Modifier.width(8.dp))
        // A message on its way — a held message's retry out, or a steer the account has not yet shown in the
        // transcript — keeps its glyphs, dimmed, for as long as that lasts: its request cannot be called back, so
        // a tap then is refused and the card's second line says why (see RefusableLine).
        val onItsWay = item.isOnItsWay
        val quiet by animateColorAsState(if (onItsWay) colors.iconQuaternary else colors.iconTertiary, tween(NoteFadeMs), label = "queue-glyphs")
        val arrow by animateColorAsState(if (onItsWay) colors.iconQuaternary else colors.iconPrimary, tween(NoteFadeMs), label = "queue-arrow")
        Crossfade(sending, animationSpec = tween(NoteFadeMs), label = "queue-ring") { ring ->
            if (ring) {
                Box(Modifier.size(Glyph + 10.dp).semantics { contentDescription = "Sending" }, contentAlignment = Alignment.Center) {
                    SpinnerRing(size = 11.dp)
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (onItsWay) Modifier.testTag(QueueGlyphs.ON_ITS_WAY_TAG).semantics { stateDescription = QueueGlyphs.ON_ITS_WAY } else Modifier,
                ) {
                    GlyphButton(CursorIcons.Trash, "Remove queued follow-up", quiet, onRemove)
                    GlyphButton(CursorIcons.Pencil, "Edit queued follow-up", quiet, onEdit)
                    GlyphButton(CursorIcons.ArrowUp, QueueGlyphs.upArrow(steers, retry = item.warning != null), arrow, onSteer)
                }
            }
        }
    }
}

/**
 * A steered card's second line, where a held card keeps its wait: "Steering…" while the account takes the message,
 * then "Steered", the one fading into the other in place, the line no taller for it. Quiet, as the wait is. A glyph
 * tap refused meanwhile ([refused]) swaps it for why, as a held card's wait does (see [RefusableLine]).
 */
@Composable
private fun SteerNote(phase: SteerPhase, refused: Boolean, fadeIn: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val steered by animateFloatAsState(if (phase == SteerPhase.STEERED) 1f else 0f, tween(NoteFadeMs), label = "steer-note")
    val shown = remember { Animatable(if (fadeIn) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(NoteFadeMs)) }
    RefusableLine(refused) { faded ->
        Box(faded.fillMaxWidth().graphicsLayer { alpha = shown.value }.testTag(QueueSteerLabelTag).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(
                QueueCardWords.STEERING, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 1f - steered }.then(if (phase == SteerPhase.STEERED) Modifier.clearAndSetSemantics {} else Modifier),
            )
            if (phase == SteerPhase.STEERED || steered > 0f) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = steered }, contentAlignment = Alignment.CenterStart) {
                    Text(QueueCardWords.STEERED, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * A card's second line, and why a glyph tap was just refused ([refused]), the message being on its way: the reason
 * fades in over the [line] in the same place and no taller — the line keeps its room underneath — and then back, so
 * the card never grows for it and nothing pops up elsewhere. [line] takes the modifier that fades it out.
 */
@Composable
private fun RefusableLine(refused: Boolean, line: @Composable (Modifier) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val swap by animateFloatAsState(if (refused) 1f else 0f, tween(NoteFadeMs), label = "refusal")
    Box {
        line(Modifier.graphicsLayer { alpha = 1f - swap })
        if (refused || swap > 0f) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = swap }, contentAlignment = Alignment.CenterStart) {
                Text(
                    QueueGlyphs.REFUSED,
                    style = type.small,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(QueueGlyphs.REFUSED_TAG).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** What a device card says of itself, to the eye and to a screen reader. */
internal object QueueCardWords {
    const val STEERING = "Steering\u2026"
    const val STEERED = "Steered \u00B7 the agent reads it at its next step"

    fun steerLabel(phase: SteerPhase): String = when (phase) {
        SteerPhase.STEERING -> STEERING
        SteerPhase.STEERED -> STEERED
    }

    fun description(item: QueuedFollowUp, position: Int, count: Int): String {
        val base = "Queued follow-up $position of $count"
        val warning = item.warning
        val steer = item.steer
        return when {
            warning != null -> "$base, not sent: $warning"
            steer == SteerPhase.STEERING -> "$base, steering into this turn"
            steer == SteerPhase.STEERED -> "$base, steered; the agent reads it at its next step"
            item.isSending && !item.isHeld -> "$base, sending"
            item.steerError != null -> "$base, not steered: ${item.steerError}"
            else -> base
        }
    }
}

/**
 * The line under a message the server keeps refusing as busy while nothing here calls the agent busy: what is being
 * waited for and for how long, ticking by the second, and — from the third refusal on — the server's own words for
 * it, so a wait of minutes is never a riddle. Quiet, not red: nothing has failed.
 *
 * A glyph tap refused while the retry is out ([refused]) swaps the wait for why (see [RefusableLine]).
 */
@Composable
private fun HeldNote(item: QueuedFollowUp, refused: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val since = item.heldSinceMillis ?: return
    var now by remember { mutableLongStateOf(AppClock.now()) }
    LaunchedEffect(since) {
        while (true) {
            now = AppClock.now()
            delay(1_000L)
        }
    }
    // The time is the tail of the line and the part that moves, so it is never what an ellipsis takes: the separator
    // and the time's own space are non-breaking, and a card too narrow for the whole line (a phone, the card stood in
    // from the composer's sides) breaks before the last word and carries the time down with it.
    val waited = TimeFormat.duration((now - since).coerceAtLeast(0L))?.replace(' ', '\u00A0')
    RefusableLine(refused) { faded ->
        Text(
            // What is waited for: the agent's turn, or — the server having asked every caller to slow down — the wait it named.
            listOfNotNull(item.holdReason ?: QueuedFollowUp.WAITING_FOR_AGENT, waited).joinToString("\u00A0\u00B7\u00A0"),
            style = type.small.copy(fontFeatureSettings = "tnum"),
            color = colors.textQuaternary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("queued-held").then(faded),
        )
    }
    item.serverReason?.let { reason ->
        Text("Cursor says: $reason", style = type.small, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("queued-held-reason"))
    }
}

/**
 * The account's queue for the chat (Extended mode), in the same rows as the device's: what the desktop and the web
 * show above their composers, in the order the server will send it. Each row's glyphs are remove, edit and the up
 * arrow, which — while a turn is under way ([steers]) — steers the message into the running turn instead of after
 * it, never stopping it (`InjectBackgroundComposerContext` with the queued message promoted). Edit opens the row
 * into a line of its own with save and cancel, the account told meanwhile that the message is being reworded
 * (`MarkFollowupEditing`). With more than one message queued, a row's menu moves it up or down the order
 * (`ReorderPendingFollowup`). A row the account has in flight from here shows a ring instead. A row steered from here
 * reads as a steered device card does ([PendingFollowup.steer]): in its place, dimmed, "Steering…" then "Steered"
 * under it, a glyph tap refused, until the transcript shows the message and the card folds away.
 */
@Composable
fun AccountQueueRows(
    queue: List<PendingFollowup>,
    inFlightIds: Set<String>,
    onSteer: (PendingFollowup) -> Unit,
    onRemove: (PendingFollowup) -> Unit,
    onUpdate: (PendingFollowup, String) -> Unit,
    onEditing: (PendingFollowup, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** A turn is under way: the up arrow steers into it (and says so). */
    steers: Boolean = false,
    /** Moves a queued message one place earlier (`up`) or later; null when the order cannot be changed from here. */
    onMove: ((PendingFollowup, up: Boolean) -> Unit)? = null,
    /** The rows as ends of the send's flight, as on [QueuedFollowUps]. */
    flights: QueueFlights? = null,
) {
    Column(modifier.animateContentSize().testTag("account-queue"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        queue.forEachIndexed { index, item ->
            key(item.id) {
                AccountQueueCard(item, index + 1, queue.size, inFlightIds, onSteer, onRemove, onUpdate, onEditing, steers, onMove, flights)
            }
        }
    }
}

/** One card of [AccountQueueRows], alone: as a [QueueStack] draws it, told its [face]. */
@Composable
internal fun AccountQueueCard(
    item: PendingFollowup,
    position: Int,
    count: Int,
    inFlightIds: Set<String>,
    onSteer: (PendingFollowup) -> Unit,
    onRemove: (PendingFollowup) -> Unit,
    onUpdate: (PendingFollowup, String) -> Unit,
    onEditing: (PendingFollowup, Boolean) -> Unit,
    steers: Boolean,
    onMove: ((PendingFollowup, up: Boolean) -> Unit)?,
    flights: QueueFlights?,
    face: QueueCardFace = QueueCardFace.Plain,
    /** The row whose glyph tap was just refused, its message being steered: its second line says so for a moment. */
    refused: Boolean = false,
    /** A glyph of a row being steered was tapped: nothing is done to the message, and the row says why ([refused]). */
    onRefused: (PendingFollowup) -> Unit = {},
) {
    AccountQueueRow(
        motion = LocalSendMotion.current,
        anchor = flights?.anchor(item.id),
        item = item,
        position = position,
        count = count,
        inFlight = item.id in inFlightIds,
        onSteer = { onSteer(item) },
        steers = steers,
        onMove = onMove?.takeIf { count > 1 }?.let { move -> { up -> move(item, up) } },
        onRemove = { onRemove(item) },
        onUpdate = { text -> onUpdate(item, text) },
        onEditing = { editing -> onEditing(item, editing) },
        face = face,
        refused = refused,
        onRefused = { onRefused(item) },
    )
}

@Composable
private fun AccountQueueRow(
    motion: SendMotion?,
    anchor: ComposerAnchor?,
    item: PendingFollowup,
    position: Int,
    count: Int,
    inFlight: Boolean,
    onSteer: () -> Unit,
    steers: Boolean,
    onMove: ((Boolean) -> Unit)?,
    onRemove: () -> Unit,
    onUpdate: (String) -> Unit,
    onEditing: (Boolean) -> Unit,
    face: QueueCardFace,
    refused: Boolean,
    onRefused: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    // A row being steered is on its way, as a steered device card is: it keeps its place and its glyphs, dimmed, and
    // reads "Steering…" then "Steered" until the transcript shows the message; a tap on a glyph meanwhile is refused.
    val steer = item.steer
    val onItsWay = steer != null
    // A row shown already steering has nothing to fade in from; one the reader steers eases its note in.
    val steeringAtFirst = remember { steer != null }
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    // The caret starts at the end of the message, where a rewording most often continues.
    var text by rememberSaveable(item.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
    val words = item.previewText
    Row(
        Modifier
            .fillMaxWidth()
            .dockedCard(surface = Modifier.queueCard(motion, anchor, item.id, words, queueCardSurface(), face.contentAlpha, face.cover))
            .onContextClick(enabled = onMove != null && !editing && !inFlight && !onItsWay) { at -> menuAt = at; menuOpen = true }
            .testTag("account-queue-row")
            .heightIn(min = RowHeight)
            .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 6.dp)
            .semantics { contentDescription = accountRowDescription(position, count, steer) }
            .faceOf(face),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Cloud, null, tint = colors.iconQuaternary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        if (editing) {
            val save = { editing = false; onUpdate(text.text) }
            // A physical Enter saves, as submitting a queued message being edited does on the desktop (it goes back
            // into its place in the queue); a message emptied out is not saved from the keyboard.
            val saveOnEnter = save.takeIf { text.text.isNotBlank() }
            ImeEnterFallback(onEnter = saveOnEnter, composing = { text.composition != null }) {
                StylusTextInput {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = type.input.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.textPrimary),
                        // The commands painted as they are reworded, the way the composer paints them.
                        visualTransformation = SlashCommandVisualTransformation(commandTints()),
                        modifier = Modifier
                            .weight(1f)
                            .stylusWriting()
                            .sendOnHardwareEnter(text, onValueChange = { text = it }, onSend = saveOnEnter)
                            .padding(vertical = 8.dp)
                            .testTag("account-queue-edit"),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(CursorIcons.Close, "Cancel editing", colors.iconTertiary) { editing = false; text = TextFieldValue(item.text, TextRange(item.text.length)); onEditing(false) }
                GlyphButton(CursorIcons.Check, "Save queued follow-up", colors.iconPrimary, save)
            }
        } else {
            val looks = accountTileLooks(item)
            if (looks.isNotEmpty()) {
                QueueTiles(looks, motion, anchor, item.id, words)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp).animateContentSize(tween(NoteFadeMs))) {
                val textColor by animateColorAsState(if (inFlight || onItsWay) colors.textTertiary else colors.textPrimary, tween(NoteFadeMs), label = "account-queue-line")
                QueueLine(words, textColor, motion, anchor, item.id)
                val names = looks.mapNotNull { it.name }
                if (names.isNotEmpty()) AttachedFileNames(names)
                if (item.isEditing) Text("Being edited on another device", style = type.small, color = colors.textQuaternary, maxLines = 1)
                if (steer != null) {
                    SteerNote(steer, refused = refused, fadeIn = !steeringAtFirst)
                } else {
                    // A message put back on the card, or one the account kept rather than steering (see QueuePlacement.returned).
                    item.note?.let { Text(it, style = type.small, color = colors.textQuaternary, maxLines = 2, modifier = Modifier.testTag("account-queue-note")) }
                }
            }
            Spacer(Modifier.width(8.dp))
            val quiet by animateColorAsState(if (onItsWay) colors.iconQuaternary else colors.iconTertiary, tween(NoteFadeMs), label = "account-queue-glyphs")
            val arrow by animateColorAsState(if (onItsWay) colors.iconQuaternary else colors.iconPrimary, tween(NoteFadeMs), label = "account-queue-arrow")
            // A steer's promote is out too, but the row says so in words: the ring is for the queue's other actions.
            Crossfade(inFlight && !onItsWay, animationSpec = tween(NoteFadeMs), label = "account-queue-ring") { ring ->
                if (ring) {
                    Box(Modifier.size(Glyph + 10.dp).semantics { contentDescription = "Sending" }, contentAlignment = Alignment.Center) {
                        SpinnerRing(size = 11.dp)
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (onItsWay) Modifier.testTag(QueueGlyphs.ON_ITS_WAY_TAG).semantics { stateDescription = QueueGlyphs.ON_ITS_WAY } else Modifier,
                    ) {
                        if (onMove != null) {
                            Box {
                                GlyphButton(CursorIcons.More, "Reorder queued follow-up", quiet) { if (onItsWay) onRefused() else { menuAt = null; menuOpen = true } }
                                CursorMenu(expanded = menuOpen && !onItsWay, onDismissRequest = { menuOpen = false }, at = menuAt) {
                                    // Dimmed at the end of the queue it cannot move past.
                                    CursorMenuItem("Move up", CursorIcons.ArrowUp, enabled = position > 1) { menuOpen = false; onMove(true) }
                                    CursorMenuItem("Move down", CursorIcons.ArrowDown, enabled = position < count) { menuOpen = false; onMove(false) }
                                }
                            }
                        }
                        GlyphButton(CursorIcons.Trash, "Remove queued follow-up", quiet) { if (onItsWay) onRefused() else onRemove() }
                        GlyphButton(CursorIcons.Pencil, "Edit queued follow-up", quiet) {
                            if (onItsWay) onRefused() else { text = TextFieldValue(item.text, TextRange(item.text.length)); editing = true; onEditing(true) }
                        }
                        GlyphButton(CursorIcons.ArrowUp, QueueGlyphs.upArrow(steers), arrow) { if (onItsWay) onRefused() else onSteer() }
                    }
                }
            }
        }
    }
}

/** What an account row says of itself to a screen reader: its place, and a steer under way. */
internal fun accountRowDescription(position: Int, count: Int, steer: SteerPhase?): String {
    val base = "Queued on your account, $position of $count"
    return when (steer) {
        SteerPhase.STEERING -> "$base, steering into this turn"
        SteerPhase.STEERED -> "$base, steered; the agent reads it at its next step"
        null -> base
    }
}

/**
 * An account row's tiles, at their places in the prompt's attachments: this device's copies of what a message queued
 * from here carries ([PendingFollowup.attachments]) — its pictures' previews, its files' glyphs — or, for one queued
 * elsewhere or whose copies are gone, the account's word for it: a plain tile for each picture, a glyph for each file.
 */
@Composable
private fun accountTileLooks(item: PendingFollowup): List<SendAttachment> {
    if (item.attachments.isEmpty()) {
        return List(item.imageCount) { ordinal -> SendAttachment(ordinal, thumbnail = null, media = true) } +
            item.files.mapIndexed { index, file -> SendAttachment(item.imageCount + index, thumbnail = null, media = false, name = file.name, kind = file.kind) }
    }
    return item.attachments.mapIndexed { ordinal, attachment ->
        key(attachment.path) {
            if (!attachment.isFile) {
                SendAttachment(ordinal, rememberTileThumbnail(attachment.path), media = true)
            } else {
                // A recording is a tile in the bubble, as it is here: it flies as one.
                val video = attachment.kind == PromptFileKind.Video
                SendAttachment(ordinal, thumbnail = null, media = video, video = video, name = attachment.name, kind = attachment.kind, sizeBytes = attachment.sizeBytes)
            }
        }
    }
}

/**
 * A picture's preview for a tile, decoded off the main thread wherever the file is now ([decodeFollowingMoves]: the
 * copies move under the run the message starts as it is delivered) and big enough for the copy a delivery flies up
 * to the bubble's thumbnail; null until decoded, and for a file that will not decode.
 */
@Composable
private fun rememberTileThumbnail(path: String): ImageBitmap? {
    val key = tileThumbnailKey(path)
    return produceState(AttachmentImages.get(key), key) {
        if (value == null) value = ioThenMain { decodeFollowingMoves(path, TileThumbnailPx) }?.also { AttachmentImages.put(key, it) }
    }.value
}

/** A queued row's attachments as small tiles before its line, in the prompt's order: where a send's copies land, and what a delivery lifts off. */
@Composable
private fun QueueTiles(looks: List<SendAttachment>, motion: SendMotion?, anchor: ComposerAnchor?, id: String, words: String) {
    val colors = CursorTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (look in looks) {
            key(look.ordinal) {
                val description = when {
                    look.video -> "Attached video"
                    look.media -> "Attached image"
                    else -> "Attached file ${look.name.orEmpty()}"
                }
                Box(
                    Modifier
                        .size(Tile)
                        .sendAttachmentSource(motion, anchor, "queued:$id:${look.ordinal}", look)
                        .sendAttachmentTarget(motion, id, words, look.ordinal, SendLanding.Queue)
                        .cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.sm)
                        .semantics { contentDescription = description },
                ) {
                    val picture = look.thumbnail?.takeIf { look.media }
                    if (picture != null) {
                        Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(look.kind.icon(), null, tint = colors.iconTertiary, modifier = Modifier.size(10.dp).align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/**
 * A queued row's message, verbatim, on one line that trails off where it ends, its `/commands` painted as the composer
 * painted them: where a queued send's text lands, and what a delivery lifts off the card.
 */
@Composable
private fun QueueLine(words: String, color: Color, motion: SendMotion?, anchor: ComposerAnchor?, id: String) {
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        highlightSlashCommands(words, commandTints().faded(color.alpha)),
        style = CursorTheme.typography.input,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout[0] = it },
        modifier = Modifier.queueLine(motion, anchor, id, words, color) { layout[0] },
    )
}

/** The attachments a queued row carries, named in one small line under its text: `report.pdf · trace.zip · 2 images`. */
@Composable
private fun AttachedFileNames(names: List<String>, modifier: Modifier = Modifier) {
    Text(
        names.joinToString(" · "),
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag("queued-attachments"),
    )
}

/** What a queued row's up arrow is called — its content description — for what a tap on it does now. */
object QueueGlyphs {
    /** A turn is under way: the message goes into it, and the turn carries on. */
    const val STEER = "Steer: send now into this turn"
    /** Nothing is running: the message goes next. */
    const val SEND = "Send now"
    /** The message could not be sent: the tap tries again. */
    const val RETRY = "Retry sending"
    /** The glyphs' state while the message's request is out (a held message's retry, a steer): dimmed, and a tap is refused. */
    const val ON_ITS_WAY = "Being sent"
    const val ON_ITS_WAY_TAG = "queued-glyphs-on-its-way"
    /** What a card's second line (a held one's wait, a steered one's note) says for a moment when one of its glyphs is tapped while it is [ON_ITS_WAY]. */
    const val REFUSED = "Being sent · can't change it now"
    const val REFUSED_TAG = "queued-held-refused"

    fun upArrow(steers: Boolean, retry: Boolean = false): String = when {
        steers -> STEER
        retry -> RETRY
        else -> SEND
    }
}

/** A bare glyph the size of the composer's chevrons, on a ripple disc no bigger than the row is tall. */
@Composable
private fun GlyphButton(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    TouchTarget(size = Glyph + 10.dp, touchSize = 36.dp, shape = CircleShape, onClick = onClick) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(Glyph))
    }
}

/** A queued card's face — what it draws on its surface — at the share its stack shows of it; a plain card's as ever. */
private fun Modifier.faceOf(face: QueueCardFace): Modifier =
    if (face === QueueCardFace.Plain) this else graphicsLayer { alpha = face.contentAlpha() }

/** One line of composer text plus the composer's vertical padding, so a row reads as a single-line composer. */
private val RowHeight = 40.dp
private val Tile = 18.dp
private val Glyph = 14.dp
/** A card's second line comes in, and one note on it fades into the next, over this long. */
private const val NoteFadeMs = 160
const val QueueSteerLabelTag = "queued-steer"
const val QueueSteerErrorTag = "queued-steer-error"
private const val TileThumbnailPx = 256

/** Where a tile's preview of the picture at [path] is kept once decoded ([AttachmentImages]). */
internal fun tileThumbnailKey(path: String): String = "$path@$TileThumbnailPx"
