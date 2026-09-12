package com.cursorforandroid.ui.panel

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.lineStatsOf
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.conversation.DiffBlock
import com.cursorforandroid.ui.conversation.LineCounts
import com.cursorforandroid.ui.theme.CursorTheme

/**
 * The panel's file viewer: a header naming the file (back arrow, path, size), then the file itself — code with line
 * numbers in monospace scrolling both ways, markdown rendered, an image shown, a diff in the git colours — or the
 * reason it could not be shown.
 */
@Composable
internal fun FileViewerScreen(view: FileView, onBack: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxSize().testTag("file-viewer")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatIconButton(CursorIcons.ChevronLeft, "Back to the panel", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(ToolNames.basename(view.path), style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitleOf(view), style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            (view as? FileView.Repository)?.file?.downloadUrl?.let { url ->
                FlatIconButton(CursorIcons.ExternalLink, "Open in browser", onClick = { onOpenUrl(url) })
            }
        }
        HairlineDivider()
        when (view) {
            is FileView.Loading -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 13.dp)
                Spacer(Modifier.width(9.dp))
                Text("Loading…", style = type.base, color = colors.textQuaternary)
            }
            is FileView.Failed -> StateRow(CursorIcons.Warning, "Couldn't open this file", view.message, tint = colors.red)
            is FileView.Transcript -> TextFile(view.content.content, view.content.truncated)
            is FileView.Changes -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(view.change.diffs) { diff -> DiffBlock(diff, showHeader = false) }
            }
            is FileView.Repository -> RepoFileBody(view.file)
            is FileView.Workspace -> RepoFileBody(view.file)
            is FileView.BranchDiff -> {
                val file = view.file
                val patch = file.patch
                when {
                    patch != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                        item { DiffBlock(ToolPayload.FileDiff(file.path, patch, file.additions, file.deletions), showHeader = false) }
                    }
                    file.modifiedContent != null -> TextFile(file.modifiedContent, truncated = false)
                    file.originalContent != null -> TextFile(file.originalContent, truncated = false)
                    else -> StateRow(CursorIcons.File, "Nothing to show", "${file.status.label}; the account sent no hunks for this file.")
                }
            }
        }
    }
}

private fun subtitleOf(view: FileView): String = when (view) {
    is FileView.Loading -> view.path
    is FileView.Failed -> view.path
    is FileView.Transcript -> listOfNotNull(
        if (view.content.kind == ToolPayload.FileContent.Kind.Written) "As the agent wrote it" else "As the agent read it",
        view.content.totalLines?.let { "$it lines" },
    ).joinToString(" · ")
    is FileView.Changes -> listOfNotNull("${view.change.diffs.size} ${if (view.change.diffs.size == 1) "edit" else "edits"}", view.change.lineStats).joinToString(" · ")
    is FileView.Repository -> listOfNotNull(view.file.path, formatBytes(view.file.sizeBytes)).joinToString(" · ")
    is FileView.Workspace -> listOfNotNull("From the agent's workspace", formatBytes(view.file.sizeBytes)).joinToString(" · ")
    is FileView.BranchDiff -> listOfNotNull(
        if (view.file.patch != null) "The branch's diff" else if (view.file.modifiedContent != null) "As it is on the branch" else "As it was on the base",
        view.file.previousPath?.let { "was $it" },
        lineStatsOf(view.file.additions, view.file.deletions),
    ).joinToString(" · ")
}

@Composable
private fun RepoFileBody(file: RepoFile) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    when (file.kind) {
        RepoFile.Kind.Markdown -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp).testTag("markdown-file")) {
            MarkdownText(file.text, style = type.message)
        }
        RepoFile.Kind.Image -> {
            val bitmap = remember(file) { runCatching { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size) }.getOrNull() }
            if (bitmap == null) {
                StateRow(CursorIcons.Image, "Couldn't decode this image", file.name, tint = colors.red)
            } else {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.TopCenter) {
                    Image(bitmap.asImageBitmap(), contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
                }
            }
        }
        RepoFile.Kind.Svg, RepoFile.Kind.Binary -> StateRow(
            CursorIcons.File,
            if (file.kind == RepoFile.Kind.Svg) "SVG files show in the browser" else "This is a binary file",
            listOfNotNull(file.name, formatBytes(file.sizeBytes)).joinToString(" · "),
        )
        RepoFile.Kind.Code -> TextFile(file.text, truncated = false)
    }
}

/**
 * Code with a line-number gutter in monospace, scrolling sideways rather than wrapping. The list is lazy so a long
 * file costs its screen, and every row is as wide as the file's longest line — measured once — so the column does
 * not change width as rows of different lengths scroll into view.
 */
@Composable
internal fun TextFile(text: String, truncated: Boolean, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val lines = remember(text) { text.replace("\r\n", "\n").lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    val gutter = lines.size.toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rowWidth = remember(lines, type.code, density) {
        val longest = lines.maxByOrNull { it.length }.orEmpty()
        val sample = "9".repeat(gutter) + "    " + longest + "  "
        with(density) { measurer.measure(AnnotatedString(sample), type.code).size.width.toDp() } + 24.dp
    }
    val scroll = rememberScrollState()
    LazyColumn(modifier.fillMaxSize().horizontalScroll(scroll).testTag("text-file"), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(lines) { index, line ->
            Row(Modifier.width(rowWidth).padding(horizontal = 12.dp)) {
                Text((index + 1).toString().padStart(gutter), style = type.code, color = colors.textQuaternary, softWrap = false)
                Spacer(Modifier.width(12.dp))
                Text(line.ifEmpty { " " }, style = type.code, color = colors.textSecondary, softWrap = false)
            }
        }
        if (truncated) {
            item { Text("… cut short by the stream", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
    }
}

/** A pull request's changed file as a row that opens onto its patch. */
@Composable
internal fun PatchHeaderCounts(additions: Int?, deletions: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineCounts(additions, deletions)
        Spacer(Modifier.width(4.dp))
        Icon(CursorIcons.ChevronRight, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(14.dp))
    }
}

/** "1.2 MB", "340 KB", "912 B". */
internal fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes < 0) return null
    return when {
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)).replace(".0 MB", " MB")
        bytes >= 1L shl 10 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
