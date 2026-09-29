package com.cursorforandroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The header row shared by every pane: flat icon buttons at both ends (the first one 6dp from the edge, so its glyph
 * lines up with the sidebar logo) and an optional start-aligned 14sp title with an 11sp detail line beneath it. No
 * centred nav-bar title, no filled buttons, no shadow — the same chrome as the Agents window, at a height a thumb
 * can use.
 */
@Composable
fun CursorHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val caption = LocalCaptionBar.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (caption != null) Modifier.captionRow(caption) else Modifier.windowInsetsPadding(HeaderTopInsets).heightIn(min = CursorDimens.headerHeight))
            .padding(horizontal = 6.dp)
            .testTag("cursor-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Row(Modifier.captionControls(caption), verticalAlignment = Alignment.CenterVertically) { leading() }
        if (title != null) {
            Spacer(Modifier.width(if (leading != null) 4.dp else 10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (trailing != null) Row(Modifier.captionControls(caption), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/**
 * A chat's header: its controls, the buttons laid out as [CursorHeader] lays them, and the chat's [title] on one line
 * right after the [leading] ones — after [titleIcon] when there is one — ellipsized short of the [trailing] ones. No
 * repository or branch line: those are the panel's header. The chat's name is also the row's accessibility label
 * ([label]), so the drawn title is not read a second time.
 *
 * The row is [CursorDimens.chatHeaderHeight] whatever the font: the buttons stand 8dp under the status bar, so a 48dp
 * target centred on each starts at the bar's edge, and the row ends at their bottom edge. The targets reach the
 * other 8dp over the transcript's top edge, and the row is drawn over what follows it so that strip still takes the
 * tap; the buttons in [leading] and [trailing] pass `touchHeight = CursorDimens.minTouchTarget`.
 *
 * The row paints nothing: the band it takes, with the transcript's fade beneath it, is what reads as the header. With
 * a [clearance], each end's controls are measured against the transcript's column, and where both ends stand clear
 * of it the row gives its band back: it still draws the buttons in the same place, over the transcript, and takes
 * only the status bar's height from what follows (see [HeaderClearance]). The title is measured with the start's
 * controls, so a title reaching the column keeps the band.
 *
 * In a desktop window's caption bar ([LocalCaptionBar]) the row is the bar's row instead, its buttons and title
 * centred on the system's controls and kept clear of them, and it keeps its band: the transcript never runs up under
 * the window's controls. Only the buttons claim their touches; the title, like the row's empty stretch, still drags
 * the window.
 */
@Composable
fun ChatHeader(
    label: String,
    modifier: Modifier = Modifier,
    clearance: HeaderClearance? = null,
    title: String? = null,
    titleIcon: (@Composable () -> Unit)? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val caption = LocalCaptionBar.current
    val bandClearance = clearance.takeIf { caption == null }
    val reach = with(LocalDensity.current) { HeaderClearance.ControlReach.toPx() }
    val band = bandClearance?.let { rememberHeaderBandRelease(it) }
    val align = if (caption != null) Alignment.CenterVertically else Alignment.Bottom
    Row(
        modifier
            .zIndex(1f)
            .then(if (band != null) Modifier.headerBand(band) else Modifier)
            .fillMaxWidth()
            .then(if (caption != null) Modifier.captionRow(caption) else Modifier.windowInsetsPadding(HeaderTopInsets).heightIn(min = CursorDimens.chatHeaderHeight))
            .padding(horizontal = 6.dp)
            .semantics {
                contentDescription = label
                heading()
            }
            .testTag("chat-header"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = align,
    ) {
        // Weighted so the end's controls are measured first and the title gets what they leave; not filled, so the
        // start's span is only as wide as what it draws.
        Row(Modifier.weight(1f, fill = false).then(bandClearance?.controls(HeaderClearance.Side.Start, reach) ?: Modifier), verticalAlignment = align) {
            if (caption != null) Row(Modifier.captionControls(caption), verticalAlignment = align) { leading?.invoke(this) } else leading?.invoke(this)
            if (title != null) ChatHeaderTitle(title, titleIcon, Modifier.weight(1f, fill = false))
        }
        Row((bandClearance?.controls(HeaderClearance.Side.End, reach) ?: Modifier).captionControls(caption), verticalAlignment = align) { trailing?.invoke(this) }
    }
}

/**
 * The chat's name in its header, on the buttons' line: as tall as an [CursorDimens.iconButton] and centred on it, in
 * the pane title's type, shrunk only where the font scale would make its line taller than the button so the row
 * never grows. It stands 12dp short of whatever follows it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatHeaderTitle(title: String, icon: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val base = CursorTheme.typography.title
    val line = with(LocalDensity.current) { base.lineHeight.toDp() }
    val style = if (line <= CursorDimens.iconButton) base else (CursorDimens.iconButton / line).let { k -> base.copy(fontSize = base.fontSize * k, lineHeight = base.lineHeight * k) }
    Row(
        modifier
            .testTag("chat-header-title")
            .semantics { invisibleToUser() }
            .height(CursorDimens.iconButton)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = style, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
    }
}
