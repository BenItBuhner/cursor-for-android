package com.cursorforandroid.ui.panel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.components.horizontalScrollEdgeFade
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * A tab's second row, as the web's document tab draws it: back at the start, where the tab's content stands in the
 * middle, the tab's own controls at the end. No rule under it; the content starts under the row.
 */
@Composable
internal fun TabBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = TabBarHeight,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(height).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onClick = onBack)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
        trailing()
    }
}

/**
 * Where a tab's file stands, as the web's breadcrumb puts it: [root], then each folder down to the file, the file
 * itself in the text's colour. A path too long for the row scrolls sideways, and starts scrolled to its end, so the
 * file's own name is the part that shows; the start dissolves while there is more before it.
 */
@Composable
internal fun Breadcrumb(root: String?, segments: List<String>, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, segments) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    val parts = listOfNotNull(root) + segments
    Row(
        modifier.horizontalScrollEdgeFade(scroll.canScrollBackward, scroll.canScrollForward).horizontalScroll(scroll).testTag("breadcrumb"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.padding(horizontal = 3.dp).size(11.dp))
            Text(part, style = type.base, color = if (index == parts.lastIndex) colors.textPrimary else colors.textTertiary, maxLines = 1)
        }
    }
}

/** A path's folders and file, for [Breadcrumb]. */
internal fun pathSegments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }.ifEmpty { listOf(path) }

/**
 * A file the chat's sections opened — one the agent touched, one of the repository or the workspace, one of the
 * branch's diff — as its own tab: the path, a line on where the copy shown comes from, then the file itself or the
 * reason it could not be shown.
 */
@Composable
internal fun FileTab(tab: PanelTab.File, state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val view = state.file(tab) ?: FileView.Failed(tab.path, "Open it again from the chat's sections.", title = "This file is no longer open")
    Column(modifier.fillMaxSize().testTag("file-viewer")) {
        TabBar(
            onBack = actions::back,
            trailing = {
                (view as? FileView.Repository)?.file?.downloadUrl?.let { url ->
                    FlatIconButton(CursorIcons.ExternalLink, "Open in browser", onClick = { actions.openUrl(url) }, size = 32.dp, iconSize = 14.dp, tint = colors.iconSecondary)
                }
            },
        ) {
            Breadcrumb(root = null, segments = pathSegments(tab.path), modifier = Modifier.weight(1f, fill = false))
        }
        fileCaption(view)?.let { caption ->
            Text(
                caption,
                style = CursorTheme.typography.small,
                color = colors.textQuaternary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = TabBarContentStart, end = 16.dp, bottom = 4.dp).testTag("file-caption"),
            )
        }
        FileViewerBody(view, actions::openUrl, actions::retryFile, actions::askToCopyFile)
    }
}

/** Where the copy a file tab shows comes from, and its size or length; nothing while it loads or when it failed. */
internal fun fileCaption(view: FileView): String? = when (view) {
    is FileView.Loading, is FileView.Failed -> null
    is FileView.Repository -> listOfNotNull("From the repository", formatBytes(view.file.sizeBytes)).joinToString(" · ")
    else -> subtitleOf(view).ifBlank { null }
}

/** A picture or a recording as its own tab: its name, then the figure as large as the tab, opening the viewer on a tap. */
@Composable
internal fun MediaTab(tab: PanelTab.Media, actions: PanelActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("media-tab")) {
        TabBar(onBack = actions::back) {
            Text(tab.name, style = CursorTheme.typography.base, color = CursorTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), contentAlignment = Alignment.TopCenter) {
            val cap = maxHeight
            if (tab.isVideo) VideoBlock(tab.src, poster = null, heightCap = cap) else ImageBlock(tab.src, alt = tab.name, heightCap = cap)
        }
    }
}

/** The height of a tab's second row, and where its content starts: past the back arrow. */
internal val TabBarHeight = 40.dp
internal val TabBarContentStart = 36.dp
