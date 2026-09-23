package com.cursorforandroid.ui.panel

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.media.MediaProblem
import com.cursorforandroid.domain.FileBytes
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.MediaKind
import com.cursorforandroid.domain.NoticeTone
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.RepoFile
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.lineStatsOf
import com.cursorforandroid.ui.components.AudioChip
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.DiffBlock
import com.cursorforandroid.ui.conversation.LineCounts
import com.cursorforandroid.ui.conversation.LoadNoticeCard
import com.cursorforandroid.ui.conversation.NoticeAction
import com.cursorforandroid.ui.media.FileHandoff
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * The panel's file viewer: a header naming the file (back arrow, path, size), then the file itself — code with line
 * numbers in monospace scrolling both ways, markdown rendered, an image shown, a diff in the git colours — or the
 * reason it could not be shown.
 */
@Composable
internal fun FileViewerScreen(view: FileView, onBack: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier, onRetry: ((wake: Boolean) -> Unit)? = null, onAskToCopy: ((path: String) -> Unit)? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxSize().testTag("file-viewer")) {
        // The pane header every screen wears, so the file's name sits under the status bar wherever the viewer is
        // composed: here inside the panel, whose root has consumed that inset already, and the header adds nothing.
        CursorHeader(
            title = ToolNames.basename(view.path),
            subtitle = subtitleOf(view),
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back to the panel", onClick = onBack) },
            trailing = {
                (view as? FileView.Repository)?.file?.downloadUrl?.let { url ->
                    FlatIconButton(CursorIcons.ExternalLink, "Open in browser", onClick = { onOpenUrl(url) })
                }
            },
        )
        HairlineDivider()
        when (view) {
            is FileView.Loading -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 13.dp)
                Spacer(Modifier.width(9.dp))
                Text("Loading…", style = type.base, color = colors.textQuaternary)
            }
            is FileView.Failed -> LoadNoticeCard(
                title = view.title,
                detail = listOfNotNull(view.message, view.asked?.let { "Asked: $it" }).joinToString("\n"),
                tone = if (view.wakeable) NoticeTone.Warning else NoticeTone.Error,
                docked = false,
                titleTag = "file-viewer-failed-title",
                modifier = Modifier.padding(12.dp).testTag("file-viewer-failed"),
            ) {
                if (onRetry != null && view.wakeable) NoticeAction("Wake the machine", { onRetry(true) }, Modifier.testTag("file-viewer-wake"))
                if (onRetry != null && view.retryable) NoticeAction("Retry", { onRetry(false) }, Modifier.testTag("file-viewer-retry"))
                view.copyablePath?.let { path -> if (onAskToCopy != null) NoticeAction("Ask the agent to copy it into the workspace", { onAskToCopy(path) }, Modifier.testTag("file-viewer-ask-copy")) }
            }
            is FileView.Transcript -> TextFile(view.content.content, view.content.truncated)
            is FileView.Changes -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(view.change.diffs) { diff -> DiffBlock(diff, showHeader = false) }
            }
            is FileView.Repository -> RepoFileBody(view.file, view.file.downloadUrl, onOpenUrl)
            is FileView.Workspace -> RepoFileBody(view.file, null, onOpenUrl)
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

/**
 * A file of the repository or the workspace, by what its bytes are rather than what its name says (see
 * [FileBytes]): a picture, a recording or a sound as the figure the transcript would draw — decoded bounded, and
 * opening the media viewer out of itself — markdown rendered, text and code with line numbers, and anything else as
 * a row that names it and hands it to another app. A wrapper (base64, a `data:` URI, JSON) is taken off first; a
 * Git LFS pointer is named, with the host's page.
 */
@Composable
private fun RepoFileBody(file: RepoFile, webUrl: String?, onOpenUrl: (String) -> Unit) {
    val type = CursorTheme.typography
    val read = remember(file) { FileBytes.of(file.bytes, file.name) }
    val plain = read as? FileBytes.Plain
    val format = plain?.format
    when {
        read is FileBytes.LfsPointer -> FileProblemRow(MediaProblem.LfsPointer, file, webUrl, onOpenUrl)
        format != null && format.isMedia -> MediaFileBody(file, plain.bytes, format)
        file.kind == RepoFile.Kind.Markdown && plain != null && FileBytes.looksLikeText(plain.bytes) ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp).testTag("markdown-file")) {
                MarkdownText(file.text, style = type.message)
            }
        plain != null && (format?.kind == MediaKind.Text || format == null && FileBytes.looksLikeText(plain.bytes)) -> TextFile(file.text, truncated = false)
        else -> FileProblemRow(null, file, webUrl, onOpenUrl, format = format)
    }
}

/**
 * A picture, a recording or a sound of the repository or the workspace: kept as a file of the cache so the media
 * viewer can open it like any figure, and drawn as the transcript's block for its kind.
 */
@Composable
private fun MediaFileBody(file: RepoFile, bytes: ByteArray, format: FileFormat) {
    val media = LocalMarkdownMedia.current
    val src by produceState<String?>(null, file, media) { value = media?.loader?.keep(bytes, file.name) }
    Box(Modifier.fillMaxWidth().padding(12.dp).testTag("media-file"), contentAlignment = Alignment.TopCenter) {
        val kept = src
        when {
            media == null -> FileProblemRowContent(MediaProblem.NotReadable("${format.label} · ${formatBytes(file.sizeBytes).orEmpty()}", "Open it from the panel to see it."), onActions = emptyList())
            kept == null -> SpinnerRing(size = 13.dp)
            format.isImage -> ImageBlock(kept, alt = file.name, heightCap = 480.dp)
            format.isVideo -> VideoBlock(kept, poster = null, heightCap = 480.dp)
            else -> AudioChip(kept, file.name, subtitle = listOfNotNull(format.label, formatBytes(file.sizeBytes)).joinToString(" · "), modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A file this app shows no page for — a PDF, an archive, a format no decoder here draws, a pointer to Git LFS — named
 * with its size, and handed on: another app on the device, the share sheet, or the host's page in the browser.
 */
@Composable
private fun FileProblemRow(problem: MediaProblem?, file: RepoFile, webUrl: String?, onOpenUrl: (String) -> Unit, format: FileFormat? = null) {
    val context = LocalContext.current
    val media = LocalMarkdownMedia.current
    val scope = rememberCoroutineScope()
    var notice by remember(file) { mutableStateOf<String?>(null) }
    val name = listOfNotNull(format?.label ?: "Binary file", formatBytes(file.sizeBytes)).joinToString(" · ")
    val shown = problem ?: MediaProblem.NotReadable(name, notice ?: "This app shows no page for it; open it in another app.")
    fun hand(open: Boolean) {
        val loader = media?.loader ?: return
        scope.launch {
            val kept = loader.keep(file.bytes, file.name).removePrefix("file://")
            val mime = format?.mimeType ?: PromptFile.resolveMimeType(null, file.name)
            val result = if (open) FileHandoff.open(context, File(kept), file.name, mime) else FileHandoff.share(context, File(kept), file.name, mime)
            result.onFailure { notice = it.message }
        }
    }
    val actions = buildList {
        if (problem !is MediaProblem.LfsPointer && media != null) {
            add("Open with\u2026" to { hand(open = true) })
            add("Share" to { hand(open = false) })
        }
        webUrl?.let { url -> add("Open in browser" to { onOpenUrl(url) }) }
    }
    FileProblemRowContent(if (problem != null && notice != null) MediaProblem.NotReadable(problem.title, notice) else shown, actions)
}

@Composable
private fun FileProblemRowContent(problem: MediaProblem, onActions: List<Pair<String, () -> Unit>>) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("file-problem")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(CursorIcons.File, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(problem.title, style = type.base, color = colors.textSecondary)
        }
        problem.detail?.let { Text(it, style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(start = 25.dp, top = 2.dp)) }
        if (onActions.isNotEmpty()) {
            Row(Modifier.padding(start = 25.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                onActions.forEach { (label, action) ->
                    Text(label, style = type.small, color = colors.link, modifier = Modifier.pressable(action, CursorTheme.shapes.base).padding(vertical = 3.dp).testTag("file-action"))
                }
            }
        }
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
