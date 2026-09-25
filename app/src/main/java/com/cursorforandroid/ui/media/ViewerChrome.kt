package com.cursorforandroid.ui.media

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.ProgressRing
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * The viewer's top row: close at the start, the page's place in the conversation's media in the middle, the
 * actions at the end. Drawn on a gradient so it reads over a bright picture, inset from the status bar and the
 * cutout, and inside whatever the display's rounded corners take.
 */
@Composable
internal fun ViewerTopBar(
    index: Int,
    count: Int,
    entry: MediaEntry,
    onClose: () -> Unit,
    onShare: () -> Unit,
    save: MediaSaves.State,
    onSave: () -> Unit,
    onOpenWith: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("viewer-top-bar"),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatIconButton(CursorIcons.Close, "Close", onClick = onClose, tint = Color.White, modifier = Modifier.testTag("viewer-close"))
            Spacer(Modifier.weight(1f))
            FlatIconButton(CursorIcons.Share, "Share", onClick = onShare, tint = Color.White, modifier = Modifier.testTag("viewer-share"))
            SaveButton(save, onSave)
            if (onOpenWith != null) FlatIconButton(CursorIcons.ExternalLink, "Open with", onClick = onOpenWith, tint = Color.White, modifier = Modifier.testTag("viewer-open-with"))
        }
        if (count > 1) {
            Text(
                "${index + 1} of $count",
                style = CursorTheme.typography.small,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).padding(top = 0.dp).testTag("viewer-index").semantics { contentDescription = "${entry.title}, ${index + 1} of $count" },
            )
        }
    }
}

/**
 * The viewer's bottom: a recording's controls when the page is one, then the caption — the alt text or description
 * over the file's name, or the name alone — inset from the navigation bar and the cutout.
 */
@Composable
internal fun ViewerBottomBar(entry: MediaEntry, playback: VideoPlayback?, muted: Boolean, onMute: (Boolean) -> Unit, onInteract: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(top = 16.dp)
            .testTag("viewer-bottom-bar"),
    ) {
        if (playback != null) {
            VideoControls(playback, muted, onMute, onInteract, Modifier.padding(horizontal = 8.dp), showSpeed = entry.isAudio)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = if (playback != null) 4.dp else 0.dp, bottom = 16.dp)) {
            Text(
                entry.title,
                style = CursorTheme.typography.base,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("viewer-caption"),
            )
            val detail = listOfNotNull(entry.fileName.takeIf { it != entry.title }, entry.kindLabel).joinToString(" · ")
            Text(detail, style = CursorTheme.typography.small, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Play or pause, the elapsed time, the scrubber, the length, the speed for a sound, and the sound: one row over a recording. */
@Composable
internal fun VideoControls(playback: VideoPlayback, muted: Boolean, onMute: (Boolean) -> Unit, onInteract: () -> Unit, modifier: Modifier = Modifier, showSpeed: Boolean = false) {
    val type = CursorTheme.typography
    val duration = playback.durationMs
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        val playing = playback.isPlaying || (playback.player.playWhenReady && !playback.isEnded)
        FlatIconButton(
            icon = when {
                playback.isEnded -> CursorIcons.Refresh
                playing -> CursorIcons.PauseFilled
                else -> CursorIcons.Play
            },
            contentDescription = when {
                playback.isEnded -> "Replay"
                playing -> "Pause"
                else -> "Play"
            },
            onClick = { playback.togglePlay(); onInteract() },
            tint = Color.White,
            modifier = Modifier.testTag("viewer-play-pause"),
        )
        Text(TimeFormat.clock(playback.shownPositionMs), style = type.code, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.End, modifier = Modifier.width(44.dp).testTag("viewer-position"))
        Scrubber(
            position = playback.shownPositionMs,
            duration = duration,
            buffered = playback.bufferedMs,
            onScrub = { fraction -> playback.scrubTo(fraction); onInteract() },
            onScrubEnd = { playback.endScrub(); onInteract() },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("viewer-scrubber"),
        )
        Text(TimeFormat.clock(duration), style = type.code, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.width(44.dp).testTag("viewer-duration"))
        if (showSpeed) {
            Text(
                VideoPlayback.speedLabel(playback.speed),
                style = type.small,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(44.dp)
                    .pressable({ playback.cycleSpeed(); onInteract() }, CursorTheme.shapes.full)
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = "Playback speed ${VideoPlayback.speedLabel(playback.speed)}" }
                    .testTag("viewer-speed"),
            )
        }
        FlatIconButton(
            icon = if (muted) CursorIcons.VolumeOff else CursorIcons.Volume,
            contentDescription = if (muted) "Unmute" else "Mute",
            onClick = { onMute(!muted); onInteract() },
            tint = Color.White,
            modifier = Modifier.testTag("viewer-mute"),
        )
    }
}

/**
 * The timeline of a recording: the played part in white over a faint track, the buffered part between, a thumb at
 * the position. A tap or a drag anywhere along it scrubs; the seek is sent when the finger lifts.
 */
@Composable
internal fun Scrubber(position: Long, duration: Long, buffered: Long, onScrub: (Float) -> Unit, onScrubEnd: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0L) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .height(44.dp)
            .semantics { contentDescription = "Seek" }
            .pointerInput(duration) {
                if (duration <= 0L) return@pointerInput
                detectTapGestures(onTap = { offset ->
                    onScrub(offset.x / size.width)
                    onScrubEnd()
                })
            }
            .pointerInput(duration) {
                if (duration <= 0L) return@pointerInput
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        x = offset.x
                        onScrub(x / size.width)
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                    onHorizontalDrag = { change, delta ->
                        x = (x + delta).coerceIn(0f, size.width.toFloat())
                        onScrub(x / size.width)
                        change.consume()
                    },
                )
            },
    ) {
        val track = 3.dp.toPx()
        val y = size.height / 2f
        val radius = 6.dp.toPx()
        val start = radius
        val end = size.width - radius
        val span = (end - start).coerceAtLeast(1f)
        drawLine(Color.White.copy(alpha = 0.25f), Offset(start, y), Offset(end, y), track, StrokeCap.Round)
        if (bufferedFraction > 0f) drawLine(Color.White.copy(alpha = 0.35f), Offset(start, y), Offset(start + span * bufferedFraction, y), track, StrokeCap.Round)
        if (fraction > 0f) drawLine(Color.White, Offset(start, y), Offset(start + span * fraction, y), track, StrokeCap.Round)
        drawCircle(Color.White, radius, Offset(start + span * fraction, y))
    }
}

/**
 * Save, as the page's item stands: the download glyph; while saving a ring — filling, with the percentage in it, when
 * the size is known, spinning when not; a green check once saved (a tap saves another copy); a red retry after a
 * failure. Always enabled: a tap while saving is the save's to ignore, and a disabled button would let the tap fall
 * through to the page, which toggles the chrome away.
 */
@Composable
internal fun SaveButton(state: MediaSaves.State, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val description = when (state) {
        MediaSaves.State.Idle -> "Save"
        is MediaSaves.State.Working -> state.fraction?.let { "Saving, ${percentOf(it)}%" } ?: "Saving"
        is MediaSaves.State.Saved -> "Saved. Save again"
        is MediaSaves.State.Failed -> "Couldn't save. Retry"
    }
    TouchTarget(
        size = CursorDimens.iconButton,
        touchSize = CursorDimens.touchTarget,
        shape = CursorTheme.shapes.lg,
        onClick = onClick,
        contentDescription = description,
        modifier = modifier.testTag("viewer-save"),
    ) {
        // Faded between phases only: a percent more is the same ring drawn further, not a new one crossfaded in.
        val phase = when (state) {
            MediaSaves.State.Idle -> SavePhase.Idle
            is MediaSaves.State.Working -> SavePhase.Working
            is MediaSaves.State.Saved -> SavePhase.Saved
            is MediaSaves.State.Failed -> SavePhase.Failed
        }
        val fraction = (state as? MediaSaves.State.Working)?.fraction
        Crossfade(targetState = phase, animationSpec = tween(160), label = "save") { shown ->
            Box(Modifier.size(CursorDimens.iconButton), contentAlignment = Alignment.Center) {
                when (shown) {
                    SavePhase.Idle -> Icon(CursorIcons.Download, null, tint = Color.White, modifier = Modifier.size(CursorDimens.headerIcon))
                    SavePhase.Working -> if (fraction == null) {
                        SpinnerRing(color = Color.White, size = 24.dp, strokeWidth = 2.dp, modifier = Modifier.testTag("viewer-save-spinner"))
                    } else {
                        ProgressRing(fraction, color = Color.White, size = 28.dp, strokeWidth = 2.dp, modifier = Modifier.testTag("viewer-save-progress"))
                        Text(
                            "${percentOf(fraction)}%",
                            style = TextStyle(fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                    SavePhase.Saved -> Icon(CursorIcons.Check, null, tint = colors.green, modifier = Modifier.size(CursorDimens.headerIcon).testTag("viewer-save-done"))
                    SavePhase.Failed -> Icon(CursorIcons.Refresh, null, tint = colors.red, modifier = Modifier.size(CursorDimens.headerIcon).testTag("viewer-save-failed"))
                }
            }
        }
    }
}

private enum class SavePhase { Idle, Working, Saved, Failed }

private fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

/**
 * A word from the viewer to the reader — saved, couldn't share — as a pill over the bottom of the page, with an
 * [action] (Retry) at its end when there is something to do about it.
 */
@Composable
internal fun ViewerNotice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.7f), CursorTheme.shapes.full)
            .padding(start = 14.dp, end = if (action != null) 4.dp else 14.dp)
            .height(IntrinsicSize.Min)
            .testTag("viewer-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = CursorTheme.typography.small, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp).weight(1f, fill = false))
        if (action != null) {
            Text(
                action,
                style = CursorTheme.typography.small.copy(fontWeight = FontWeight.Medium),
                color = CursorTheme.colors.accent,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .pressable(onAction, CursorTheme.shapes.full)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("viewer-notice-action"),
            )
        }
    }
}
