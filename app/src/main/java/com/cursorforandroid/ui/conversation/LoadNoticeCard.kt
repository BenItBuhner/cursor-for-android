package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A notice about how the transcript loaded — a refresh that failed, the account's record refused with the documented
 * endpoints standing in — as a card of its own over the composer, in the family of the queued follow-ups and the
 * goal strip rather than as bare lines between them (Bennett's 2026-09-20 frame: the record's notice as unstyled
 * text sat directly over a queued card and the box, "these errors should be contained in their own pill or bar like
 * queued messages, goals, etc."). The same surface, stroke and corners as the queue's cards, stood in from the
 * composer's sides by the same [dockedCard] inset so its corners are concentric with the box's; the warning glyph
 * tinted by [tone]; a [title] of a line or two; [detail] under it when there is more to say; and the ways out —
 * Retry, the diagnostics — as compact buttons inside the card ([NoticeAction]), where the bare words used to be.
 *
 * Not [docked], the card stands at its parent's own margins with no inset: a failure that is the whole screen, in
 * the transcript's place, has no composer edge to line up with. The words are the caller's; this is the container.
 */
@Composable
internal fun LoadNoticeCard(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: NoticeTone = NoticeTone.Error,
    docked: Boolean = true,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val tint = when (tone) {
        NoticeTone.Error -> colors.red
        NoticeTone.Warning -> colors.orange
        NoticeTone.Success -> colors.green
        NoticeTone.Neutral -> colors.iconTertiary
    }
    val surface = if (docked) Modifier.dockedCard() else Modifier.cursorSurface(colors.elevated, colors.strokeSubtle, CursorTheme.shapes.xl)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(surface)
                .testTag("load-notice")
                // The text starts where the composer's and the queue's does; the glyph sits in that margin's place.
                .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = CursorDimens.composerPadding, top = 10.dp, bottom = 10.dp),
        ) {
            // Centred on the first line of the title, whatever the title wraps to.
            Icon(CursorIcons.Warning, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.base, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = type.small, color = colors.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
}

/** One of a notice's ways out: the desktop's ghost button, at the height of the composer footer's chips. */
@Composable
internal fun NoticeAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CursorButton(label, onClick, modifier = modifier, height = CursorDimens.composerFooter)
}
