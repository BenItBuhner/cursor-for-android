package com.cursorforandroid.ui.conversation

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The follow-ups waiting for the agent's turn to end, stacked above the composer in the order they will go out. Each
 * is one line in the composer's own surface — the message verbatim, trailing off where the line ends, with the
 * images it carries as small tiles before it — and three small glyphs on the right: remove, edit, send now. Nothing
 * else: a queue should read as a list of what is about to be said, not as a stack of forms. A message that could not
 * be sent shows a warning where its tiles would be; send-now then retries it. One on its way out shows a ring instead
 * of the glyphs.
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
            .padding(start = CursorDimens.composerPadding, end = CursorDimens.composerPadding - 6.dp)
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
        } else if (item.images.isNotEmpty()) {
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
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.previewText,
                style = type.input,
                color = if (item.isSending) colors.textTertiary else colors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            item.warning?.let { note ->
                Text(note, style = type.small, color = colors.red, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (item.isSending) {
            Box(Modifier.size(Glyph + 10.dp).semantics { contentDescription = "Sending" }, contentAlignment = Alignment.Center) {
                SpinnerRing(size = 11.dp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(CursorIcons.Trash, "Remove queued follow-up", colors.iconTertiary, onRemove)
                GlyphButton(CursorIcons.Pencil, "Edit queued follow-up", colors.iconTertiary, onEdit)
                GlyphButton(CursorIcons.ArrowUp, if (item.warning != null) "Retry sending" else "Send now", colors.iconPrimary, onSteer)
            }
        }
    }
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
