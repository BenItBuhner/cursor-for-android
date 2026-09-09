package com.cursorforandroid.ui.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.ComposerRoundButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The follow-ups waiting for the agent's turn to end, stacked above the composer in the order they will go out.
 * Each is a card cut like the composer — same surface, stroke, radius and padding, the same round buttons in the
 * footer — so the queue reads as prompts that have not left the box yet, which is what they are. The footer says
 * where the message stands (queued, on its way, or why it did not go) and offers to edit it, send it now (steer:
 * the turn under way is stopped for it), or drop it.
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
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        queue.forEachIndexed { index, item ->
            QueuedFollowUpCard(
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
private fun QueuedFollowUpCard(
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
    val pad = CursorDimens.composerPadding
    Column(
        Modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, colors.strokeSubtle, CursorTheme.shapes.xl)
            .padding(start = pad, end = pad, top = pad, bottom = pad - 2.dp)
            .semantics { contentDescription = "Queued follow-up $position of $count" },
    ) {
        if (item.images.isNotEmpty()) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.images.forEach { image ->
                    Box(Modifier.size(40.dp).cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.base)) {
                        val bitmap = thumbnails[image.id]
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(CursorIcons.Image, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp).align(Alignment.Center))
                        }
                    }
                }
            }
        }
        Text(
            item.previewText,
            style = type.input,
            color = if (item.isSending) colors.textTertiary else colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(CursorDimens.composerFooter), verticalAlignment = Alignment.CenterVertically) {
            // The status takes the composer's chip slot, in its text: the message's place in line and what it will carry.
            Row(Modifier.weight(1f).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.isSending -> {
                        SpinnerRing(size = 11.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Sending…", style = type.base, color = colors.textTertiary, maxLines = 1)
                    }
                    item.error != null -> {
                        Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.error, style = type.small, color = colors.red, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> {
                        val caption = buildList {
                            add(if (count > 1) "Queued · $position of $count" else "Queued")
                            item.modelDisplayName?.let(::add)
                            if (item.planMode == true) add("Plan")
                        }.joinToString(" · ")
                        Text(caption, style = type.base, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (item.isSending) {
                Box(
                    Modifier.size(CursorDimens.roundButton).background(colors.fillSoft, CircleShape).semantics { contentDescription = "Sending" },
                    contentAlignment = Alignment.Center,
                ) {
                    SpinnerRing(color = colors.iconSecondary, size = CursorDimens.roundButtonGlyph - 6.dp, strokeWidth = 1.5.dp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ComposerRoundButton(CursorIcons.Trash, "Remove queued follow-up", onClick = onRemove)
                    ComposerRoundButton(CursorIcons.Pencil, "Edit queued follow-up", onClick = onEdit)
                    ComposerRoundButton(
                        CursorIcons.ArrowUp,
                        if (item.error != null) "Retry sending" else "Send now",
                        onClick = onSteer,
                        prominent = true,
                    )
                }
            }
        }
    }
}
