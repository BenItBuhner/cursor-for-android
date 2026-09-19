package com.cursorforandroid.ui.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.icon
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.delay

/**
 * The follow-ups waiting for the agent's turn to end, stacked above the composer in the order they will go out. Each
 * is one line in the composer's own surface — the message verbatim, trailing off where the line ends, with the
 * images it carries as small tiles before it — and three small glyphs on the right: remove, edit, send now. Nothing
 * else: a queue should read as a list of what is about to be said, not as a stack of forms. A message that could not
 * be sent shows a warning where its tiles would be and the reason under the message, in red; send-now then retries
 * it. One on its way out shows a ring instead of the glyphs.
 */
@Composable
fun QueuedFollowUps(
    queue: List<QueuedFollowUp>,
    thumbnails: Map<String, ImageBitmap>,
    onEdit: (QueuedFollowUp) -> Unit,
    onSteer: (QueuedFollowUp) -> Unit,
    onRemove: (QueuedFollowUp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        queue.forEachIndexed { index, item ->
            QueuedFollowUpRow(
                item = item,
                position = index + 1,
                count = queue.size,
                thumbnails = thumbnails,
                onEdit = { onEdit(item) },
                onSteer = { onSteer(item) },
                onRemove = { onRemove(item) },
            )
        }
    }
}

@Composable
private fun QueuedFollowUpRow(
    item: QueuedFollowUp,
    position: Int,
    count: Int,
    thumbnails: Map<String, ImageBitmap>,
    onEdit: () -> Unit,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, colors.strokeSubtle, CursorTheme.shapes.xl)
            .heightIn(min = RowHeight)
            .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 6.dp)
            .semantics {
                contentDescription = when (val note = item.warning) {
                    null -> "Queued follow-up $position of $count"
                    else -> "Queued follow-up $position of $count, not sent: $note"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.warning != null) {
            Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(8.dp))
        } else if (item.images.isNotEmpty() || item.files.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                item.images.forEach { image ->
                    Box(Modifier.size(Tile).cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.sm)) {
                        val bitmap = thumbnails[image.id]
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(CursorIcons.Image, null, tint = colors.iconTertiary, modifier = Modifier.size(10.dp).align(Alignment.Center))
                        }
                    }
                }
                // A file keeps its glyph where an image has its tile; its name follows the message line below.
                item.files.forEach { file ->
                    Box(Modifier.size(Tile).cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.sm).semantics { contentDescription = "Attached file ${file.file.name}" }) {
                        Icon(file.file.kind.icon(), null, tint = colors.iconTertiary, modifier = Modifier.size(10.dp).align(Alignment.Center))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        // A message the server keeps refusing as busy reads as waiting, steadily — one line, the time waited on it —
        // whether or not an attempt happens to be in flight this instant: the attempts are brief and the pauses
        // between them long, and a card that read "sending" for each would flicker between the two for as long as
        // the server took (see QueuedFollowUp.isHeld). The ring is for a first send only.
        val sending = item.isSending && !item.isHeld
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                item.previewText,
                style = type.input,
                color = if (sending) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.files.isNotEmpty()) AttachedFileNames(item.files.map { it.file.name })
            // Why it did not go, in the server's words or the connection's: without it the warning is only a riddle.
            item.warning?.let { note ->
                Text(note, style = type.small, color = colors.red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.isHeld) HeldNote(item)
        }
        Spacer(Modifier.width(8.dp))
        if (sending) {
            Box(Modifier.size(Glyph + 10.dp).semantics { contentDescription = "Sending" }, contentAlignment = Alignment.Center) {
                SpinnerRing(size = 11.dp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(CursorIcons.Trash, "Remove queued follow-up", colors.iconTertiary, onRemove)
                GlyphButton(CursorIcons.Pencil, "Edit queued follow-up", colors.iconTertiary, onEdit)
                GlyphButton(CursorIcons.ArrowUp, if (item.warning != null) "Retry sending" else "Send now", colors.iconPrimary, onSteer)
            }
        }
    }
}

/**
 * The line under a message the server keeps refusing as busy while nothing here calls the agent busy: what is being
 * waited for and for how long, ticking by the second, and — from the third refusal on — the server's own words for
 * it, so a wait of minutes is never a riddle. Quiet, not red: nothing has failed.
 */
@Composable
private fun HeldNote(item: QueuedFollowUp) {
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
    val waited = TimeFormat.duration((now - since).coerceAtLeast(0L))
    Text(
        // What is waited for: the agent's turn, or — the server having asked every caller to slow down — the wait it named.
        listOfNotNull(item.holdReason ?: QueuedFollowUp.WAITING_FOR_AGENT, waited).joinToString(" \u00B7 "),
        style = type.small.copy(fontFeatureSettings = "tnum"),
        color = colors.textQuaternary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("queued-held"),
    )
    item.serverReason?.let { reason ->
        Text("Cursor says: $reason", style = type.small, color = colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("queued-held-reason"))
    }
}

/**
 * The account's queue for the chat (Extended mode), in the same rows as the device's: what the desktop and the web
 * show above their composers, in the order the server will send it. Each row's glyphs are remove, edit and send
 * now — and, while a turn is under way and steering is on, steer now, which delivers the message into the running
 * turn instead of after it (`InjectBackgroundComposerContext` with the queued message promoted). Edit opens the row
 * into a line of its own with save and cancel, the account told meanwhile that the message is being reworded
 * (`MarkFollowupEditing`). With more than one message queued, a row's menu moves it up or down the order
 * (`ReorderPendingFollowup`). A row the account has in flight from here shows a ring instead.
 */
@Composable
fun AccountQueueRows(
    queue: List<PendingFollowup>,
    inFlightIds: Set<String>,
    onSendNow: (PendingFollowup) -> Unit,
    onRemove: (PendingFollowup) -> Unit,
    onUpdate: (PendingFollowup, String) -> Unit,
    onEditing: (PendingFollowup, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Delivers a queued message into the turn under way as a steer; null while nothing is running, or the surface is off. */
    onSteerNow: ((PendingFollowup) -> Unit)? = null,
    /** Moves a queued message one place earlier (`up`) or later; null when the order cannot be changed from here. */
    onMove: ((PendingFollowup, up: Boolean) -> Unit)? = null,
) {
    Column(modifier.animateContentSize().testTag("account-queue"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        queue.forEachIndexed { index, item ->
            key(item.id) {
                AccountQueueRow(
                    item = item,
                    position = index + 1,
                    count = queue.size,
                    inFlight = item.id in inFlightIds,
                    onSendNow = { onSendNow(item) },
                    onSteerNow = onSteerNow?.let { steer -> { steer(item) } },
                    onMove = onMove?.takeIf { queue.size > 1 }?.let { move -> { up -> move(item, up) } },
                    onRemove = { onRemove(item) },
                    onUpdate = { text -> onUpdate(item, text) },
                    onEditing = { editing -> onEditing(item, editing) },
                )
            }
        }
    }
}

@Composable
private fun AccountQueueRow(
    item: PendingFollowup,
    position: Int,
    count: Int,
    inFlight: Boolean,
    onSendNow: () -> Unit,
    onSteerNow: (() -> Unit)?,
    onMove: ((Boolean) -> Unit)?,
    onRemove: () -> Unit,
    onUpdate: (String) -> Unit,
    onEditing: (Boolean) -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    // The caret starts at the end of the message, where a rewording most often continues.
    var text by rememberSaveable(item.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
    Row(
        Modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, colors.strokeSubtle, CursorTheme.shapes.xl)
            .heightIn(min = RowHeight)
            .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding - 6.dp)
            .semantics { contentDescription = "Queued on your account, $position of $count" }
            .testTag("account-queue-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Cloud, null, tint = colors.iconQuaternary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = type.input.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp).testTag("account-queue-edit"),
            )
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(CursorIcons.Close, "Cancel editing", colors.iconTertiary) { editing = false; text = TextFieldValue(item.text, TextRange(item.text.length)); onEditing(false) }
                GlyphButton(CursorIcons.Check, "Save queued follow-up", colors.iconPrimary) { editing = false; onUpdate(text.text) }
            }
        } else {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    item.previewText,
                    style = type.input,
                    color = if (inFlight) colors.textTertiary else colors.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                // What the account says the message carries (`selected_context`): its files by name, its images by count.
                if (item.files.isNotEmpty() || item.imageCount > 0) {
                    val images = when (item.imageCount) {
                        0 -> emptyList()
                        1 -> listOf("1 image")
                        else -> listOf("${item.imageCount} images")
                    }
                    AttachedFileNames(item.files.map { it.name } + images)
                }
                if (item.isEditing) Text("Being edited on another device", style = type.small, color = colors.textQuaternary, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            if (inFlight) {
                Box(Modifier.size(Glyph + 10.dp).semantics { contentDescription = "Sending" }, contentAlignment = Alignment.Center) {
                    SpinnerRing(size = 11.dp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onMove != null) {
                        Box {
                            GlyphButton(CursorIcons.More, "Reorder queued follow-up", colors.iconTertiary) { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
                                MenuItem("Move up", CursorIcons.ArrowUp, enabled = position > 1) { menuOpen = false; onMove(true) }
                                MenuItem("Move down", CursorIcons.ArrowDown, enabled = position < count) { menuOpen = false; onMove(false) }
                            }
                        }
                    }
                    if (onSteerNow != null) GlyphButton(CursorIcons.Target, "Steer now", colors.iconTertiary, onSteerNow)
                    GlyphButton(CursorIcons.Trash, "Remove queued follow-up", colors.iconTertiary, onRemove)
                    GlyphButton(CursorIcons.Pencil, "Edit queued follow-up", colors.iconTertiary) { text = TextFieldValue(item.text, TextRange(item.text.length)); editing = true; onEditing(true) }
                    GlyphButton(CursorIcons.ArrowUp, "Send now", colors.iconPrimary, onSendNow)
                }
            }
        }
    }
}

/** The attachments a queued row carries, named in one small line under its text: `report.pdf · trace.zip · 2 images`. */
@Composable
private fun AttachedFileNames(names: List<String>) {
    Text(
        names.joinToString(" · "),
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("queued-attachments"),
    )
}

/** One line of the reorder menu: the direction's glyph and word, dimmed at the end of the queue it cannot move past. */
@Composable
private fun MenuItem(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val tint = if (enabled) colors.textPrimary else colors.textQuaternary
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.base, color = tint) },
        leadingIcon = { Icon(icon, null, tint = if (enabled) colors.iconSecondary else colors.iconQuaternary, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(start = 12.dp, end = 20.dp),
        modifier = Modifier.height(40.dp),
    )
}

/** A bare glyph the size of the composer's chevrons, on a ripple disc no bigger than the row is tall. */
@Composable
private fun GlyphButton(icon: ImageVector, contentDescription: String, tint: Color, onClick: () -> Unit) {
    TouchTarget(size = Glyph + 10.dp, touchSize = 36.dp, shape = CircleShape, onClick = onClick) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(Glyph))
    }
}

/** One line of composer text plus the composer's vertical padding, so a row reads as a single-line composer. */
private val RowHeight = 40.dp
private val Tile = 18.dp
private val Glyph = 14.dp
