package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.TouchTarget
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
 *
 * With [onDismiss], an X in the top-right corner closes the card (Bennett's 2026-09-20 frame: "the user can actually
 * press an X button in the top right of it to actually close or minimize the error. Otherwise, it is permanently
 * there"): the composer's glyph size on a finger's 44dp touch target, the target wholly inside the card, the disc
 * on the title's first line, after the text's column in the row so a title that wraps to two lines ends before it
 * rather than running under it. What closing means —
 * hidden until the notice changes or its condition clears and recurs, Refresh and Share diagnostics still in the
 * chat's menu — is the caller's ([NoticeDismissals]). The failure that is the whole screen has no X: closing it
 * would leave a blank transcript with nothing to act on, and it takes no room from anything.
 */
@Composable
internal fun LoadNoticeCard(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: NoticeTone = NoticeTone.Error,
    docked: Boolean = true,
    onDismiss: (() -> Unit)? = null,
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
    // The X's disc is raised so its centre is on the title's first line (18sp, at the reader's font scale) rather
    // than on the disc's own 24dp; and its touch target has to lie within the card, whose clipped surface is what
    // takes the touch — a target past the card's edge is a target the finger cannot reach. So with an X the card's
    // top and end margins are what puts the target's edges on the card's: half the target from each, the disc's
    // rise added at the top. Three dp more air over the title than a card without one; the composer's own 12dp
    // margin less two at the end.
    val titleLine = with(LocalDensity.current) { type.base.lineHeight.toDp() }
    val discRise = (DismissDisc - titleLine) / 2
    val targetMargin = (CursorDimens.touchTarget - DismissDisc) / 2
    val topPadding = if (onDismiss != null) maxOf(CardPadding, targetMargin + discRise) else CardPadding
    val endPadding = if (onDismiss != null) targetMargin else CursorDimens.composerPadding
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(surface)
                .testTag("load-notice")
                // The text starts where the composer's and the queue's does; the glyph sits in that margin's place.
                .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = endPadding, top = topPadding, bottom = CardPadding),
        ) {
            // Centred on the first line of the title, whatever the title wraps to.
            Icon(CursorIcons.Warning, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.base, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("load-notice-title"))
                // Four lines: the record's refusal says what was asked (one line, two when the path wraps) and what is on screen because of it.
                detail?.let { Text(it, style = type.small, color = colors.textTertiary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
            if (onDismiss != null) {
                Spacer(Modifier.width(8.dp))
                // Measured before the weighted column, so the title's width never includes the X's: a title that
                // wraps ends beside the disc, never under it.
                TouchTarget(
                    size = DismissDisc,
                    touchSize = CursorDimens.touchTarget,
                    shape = CircleShape,
                    onClick = onDismiss,
                    contentDescription = "Close notice",
                    modifier = Modifier.offset(y = -discRise).testTag("load-notice-dismiss"),
                ) {
                    Icon(CursorIcons.Close, null, tint = colors.iconTertiary, modifier = Modifier.size(DismissGlyph))
                }
            }
        }
    }
}

/**
 * [notice] as the dock shows it: the card, its Retry and Share diagnostics, and its X. The test tags are the ones each
 * kind has always carried, so what reads a load error or the record's row on screen goes on reading it.
 */
@Composable
internal fun LoadNoticeRow(
    notice: LoadNotice,
    onRetry: () -> Unit,
    onShareDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    docked: Boolean = true,
) {
    val (tag, retryTag, shareTag) = when (notice.kind) {
        LoadNotice.Kind.LoadError -> Triple("load-error", "load-retry", "share-diagnostics")
        LoadNotice.Kind.RecordFallback -> Triple("record-fallback", "record-fallback-retry", "record-fallback-diagnostics")
    }
    LoadNoticeCard(title = notice.title, detail = notice.detail, tone = notice.tone, docked = docked, onDismiss = onDismiss, modifier = modifier.testTag(tag)) {
        NoticeAction("Retry", onRetry, Modifier.testTag(retryTag))
        NoticeAction("Share diagnostics", onShareDiagnostics, Modifier.testTag(shareTag))
    }
}

/** The card's air over and under its text. */
private val CardPadding = 10.dp
/** The X's disc: the composer's round buttons' size, the queue's glyph buttons' size. */
private val DismissDisc = 24.dp
private val DismissGlyph = 14.dp

/** One of a notice's ways out: the desktop's ghost button, at the height of the composer footer's chips. */
@Composable
internal fun NoticeAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CursorButton(label, onClick, modifier = modifier, height = CursorDimens.composerFooter)
}
