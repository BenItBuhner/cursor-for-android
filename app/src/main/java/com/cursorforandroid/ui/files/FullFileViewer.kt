package com.cursorforandroid.ui.files

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.data.repo.AgentFileRepository
import com.cursorforandroid.data.repo.FileRead
import com.cursorforandroid.domain.CarriedFile
import com.cursorforandroid.domain.DiffLines
import com.cursorforandroid.domain.FileBytes
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.MediaKind
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.DiffBlock
import com.cursorforandroid.ui.media.FileHandoff
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CancellationException
import java.io.File

/** What the full-file viewer has of the file. */
sealed interface FullFile {
    data object Loading : FullFile

    /**
     * The file's text, numbered from [firstLine]: the whole file, or the part the transcript carried. [highlighted]
     * are the lines to mark (what an edit changed, the range a read took, a search's hit), [scrollTo] the line to
     * open at, [source] where the text is from, [notice] what is missing and why, [webUrl] the file on its host.
     */
    data class Text(
        val text: String,
        val source: String,
        val firstLine: Int = 1,
        val highlighted: Set<Int> = emptySet(),
        val scrollTo: Int? = null,
        val notice: String? = null,
        val webUrl: String? = null,
    ) : FullFile {
        val lines: List<String> by lazy { text.replace("\r\n", "\n").lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } }
    }

    /** An edit the transcript carried, as its diff, when the whole file cannot be read from here. */
    data class Diff(val diffs: List<ToolPayload.FileDiff>, val notice: String, val webUrl: String? = null) : FullFile

    /** A file that is not text — a PDF, an archive — kept on the device ([keptPath]) to hand to another app. */
    data class Binary(val format: FileFormat?, val sizeBytes: Long, val keptPath: String, val source: String) : FullFile

    data class Failed(val message: String, val retryable: Boolean, val webUrl: String? = null) : FullFile
}

/**
 * Finds the text of a file a tool call names, in the order the reader is owed it: what the transcript already
 * carries (a write's `fileContentAfterWrite`, a read's `content`) when that is the whole file; else, with the
 * agent's workspace readable (Extended mode), the file as it is now, from the workspace or the repository at the
 * agent's branch; else what the transcript carried — a part of the file, or an edit's diff — with a plain word that
 * the whole file needs Extended mode.
 */
class FullFileResolver(
    private val files: AgentFileRepository?,
    private val workspaceReadable: suspend () -> Boolean,
    private val keep: suspend (ByteArray, String) -> String,
) {
    suspend fun resolve(agentId: String, request: FileOpenRequest, carried: CarriedFile, force: Boolean = false): FullFile {
        val content = carried.content
        val fromEdit = carried.tappedKind == ToolKind.Edit
        val changed = carried.tappedDiff?.let { DiffLines.changedLines(it.diff) }.orEmpty()
        val webUrl = files?.webUrl(agentId, request.path)
        // What was read, by the lines the read took: the range to mark once the whole file is shown.
        val readRange = content?.takeIf { it.kind == ToolPayload.FileContent.Kind.Read && carried.tappedKind == ToolKind.Read && !it.isWhole }
            ?.let { (it.startLine ?: 1) until (it.startLine ?: 1) + it.content.trimEnd('\n').lines().size }
        if (content != null && content.isWhole && !fromEdit) {
            return FullFile.Text(content.content, sourceOf(content), highlighted = request.line?.let(::setOf).orEmpty(), scrollTo = request.line)
        }
        var readFailure: String? = null
        if (files != null && workspaceReadable()) {
            when (val read = files.read(agentId, request.path, force)) {
                is FileRead.Loaded -> {
                    val marked = when {
                        request.line != null -> setOf(request.line)
                        fromEdit -> changed
                        readRange != null -> readRange.toSet()
                        else -> emptySet()
                    }
                    return shown(read.file.bytes, read.file.name, read.file.sizeBytes, "${read.source.label}, as it is now", marked, request.line ?: marked.minOrNull(), webUrl)
                }
                is FileRead.NotReadable -> Unit
                is FileRead.Failed -> readFailure = read.message
            }
        }
        val missing = readFailure ?: NEEDS_EXTENDED
        return when {
            content != null && !fromEdit -> FullFile.Text(
                content.content,
                sourceOf(content),
                firstLine = content.startLine ?: 1,
                highlighted = request.line?.let(::setOf).orEmpty(),
                scrollTo = request.line,
                notice = if (content.isWhole) null else missing,
                webUrl = webUrl,
            )
            carried.tappedDiff != null || carried.diffs.isNotEmpty() -> FullFile.Diff(listOfNotNull(carried.tappedDiff).ifEmpty { carried.diffs }, missing, webUrl)
            content != null -> FullFile.Text(content.content, sourceOf(content), firstLine = content.startLine ?: 1, notice = missing, webUrl = webUrl)
            else -> FullFile.Failed(readFailure ?: "The transcript carried no copy of this file. $NEEDS_EXTENDED", retryable = readFailure != null, webUrl = webUrl)
        }
    }

    private suspend fun shown(bytes: ByteArray, name: String, size: Long, source: String, marked: Set<Int>, scrollTo: Int?, webUrl: String?): FullFile {
        val read = FileBytes.of(bytes, name)
        val plain = read as? FileBytes.Plain
        val isText = plain != null && (plain.format?.kind == MediaKind.Text || plain.format == null && FileBytes.looksLikeText(plain.bytes))
        if (isText) return FullFile.Text(bytes.toString(Charsets.UTF_8), source, highlighted = marked, scrollTo = scrollTo, webUrl = webUrl)
        return FullFile.Binary(plain?.format, size, keep(plain?.bytes ?: bytes, name).removePrefix("file://"), source)
    }

    private fun sourceOf(content: ToolPayload.FileContent): String = when (content.kind) {
        ToolPayload.FileContent.Kind.Written -> "As the agent wrote it"
        ToolPayload.FileContent.Kind.Read -> if (content.isWhole) "As the agent read it" else "The part the agent read"
    }

    companion object {
        const val NEEDS_EXTENDED = "The whole file needs Extended mode, which reads the agent's workspace."

        /** The resolver the app runs: the agent's file reads, gated on the workspace capability, and the loader's cache for binaries. */
        fun of(files: AgentFileRepository, capabilities: suspend () -> Boolean, loader: MediaLoader): FullFileResolver =
            FullFileResolver(files, capabilities) { bytes, name -> loader.keep(bytes, name) }

        /** A file for the full-file viewer rather than the media viewer: none of a picture, a recording or a sound. */
        fun opensAsText(path: String): Boolean = FileFormat.ofName(path)?.isMedia != true
    }
}

/** The full-file viewer, full screen in its own window over the chat; back and the arrow close it. */
@Composable
fun FullFileDialog(request: FileOpenRequest, load: suspend (force: Boolean) -> FullFile, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullFileScreen(request, load, onClose)
    }
}

/**
 * A file a tool call named, whole: its name, where the text is from, Copy and Share in the header; then the text in
 * monospace with the file's line numbers, scrolling sideways rather than wrapping, the lines an edit changed (or a
 * read took, or a search hit) marked, and opened at the first of them. What the viewer could not have — the file
 * past the part the transcript carried — is said in a line above it, with the file on its host.
 */
@Composable
fun FullFileScreen(request: FileOpenRequest, load: suspend (force: Boolean) -> FullFile, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var attempt by remember(request) { mutableIntStateOf(0) }
    var file by remember(request) { mutableStateOf<FullFile>(FullFile.Loading) }
    LaunchedEffect(request, attempt) {
        file = FullFile.Loading
        file = try {
            load(attempt > 0)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            FullFile.Failed(MediaLoader.problemOf(t).title, retryable = true)
        }
    }
    BackHandler(onBack = onClose)
    val name = ToolNames.basename(request.path)
    Column(modifier.fillMaxSize().background(colors.canvas).testTag("full-file")) {
        CursorHeader(
            title = name,
            subtitle = subtitleOf(file, request.path),
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onClose) },
            trailing = {
                val text = (file as? FullFile.Text)?.text
                if (text != null) {
                    FlatIconButton(CursorIcons.Copy, "Copy file", onClick = {
                        clipboard.setText(AnnotatedString(text))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.testTag("full-file-copy"))
                    FlatIconButton(CursorIcons.Share, "Share file", onClick = { shareText(context, name, text) }, modifier = Modifier.testTag("full-file-share"))
                }
            },
        )
        HairlineDivider()
        when (val shown = file) {
            FullFile.Loading -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 13.dp)
                Spacer(Modifier.width(9.dp))
                Text("Reading ${request.path}\u2026", style = CursorTheme.typography.base, color = colors.textQuaternary)
            }
            is FullFile.Text -> {
                shown.notice?.let { Notice(it, shown.webUrl) }
                CodeLines(shown)
            }
            is FullFile.Diff -> {
                Notice(shown.notice, shown.webUrl)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(shown.diffs) { _, diff -> DiffBlock(diff, showHeader = false) }
                }
            }
            is FullFile.Binary -> BinaryRow(shown, name)
            is FullFile.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp).testTag("full-file-failed"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(CursorIcons.Warning, null, tint = colors.orange, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(shown.message, style = CursorTheme.typography.base, color = colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.retryable) CursorButton("Retry", onClick = { attempt++ })
                    shown.webUrl?.let { url -> OpenOnHost(url) }
                }
            }
        }
    }
}

private fun subtitleOf(file: FullFile, path: String): String = when (file) {
    FullFile.Loading, is FullFile.Failed -> path
    is FullFile.Text -> listOfNotNull(
        file.source,
        if (file.firstLine > 1) "lines ${file.firstLine}\u2013${file.firstLine + file.lines.size - 1}" else "${file.lines.size} ${if (file.lines.size == 1) "line" else "lines"}",
    ).joinToString(" \u00B7 ")
    is FullFile.Diff -> "${file.diffs.size} ${if (file.diffs.size == 1) "edit" else "edits"} the transcript carried"
    is FullFile.Binary -> file.source
}

/** What is missing and why, above the text, with the file on its host when there is a page for it. */
@Composable
private fun Notice(text: String, webUrl: String?) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().background(colors.fillFaint).padding(horizontal = 14.dp, vertical = 8.dp).testTag("full-file-notice"), verticalAlignment = Alignment.CenterVertically) {
        Icon(CursorIcons.FileText, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = CursorTheme.typography.small, color = colors.textTertiary, modifier = Modifier.weight(1f))
        webUrl?.let {
            Spacer(Modifier.width(8.dp))
            OpenOnHost(it)
        }
    }
}

@Composable
private fun OpenOnHost(url: String) {
    val uriHandler = LocalUriHandler.current
    Text(
        if (url.contains("github.com")) "Open on GitHub" else "Open in browser",
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.link,
        modifier = Modifier.pressable({ runCatching { uriHandler.openUri(url) } }, CursorTheme.shapes.base).padding(4.dp).testTag("full-file-open-host"),
    )
}

/**
 * The text with the file's line numbers in a gutter, monospace, one row per line and none wrapped: every row is as
 * wide as the longest line — measured once — so the column scrolls sideways as one; the marked lines sit on the
 * accent's wash with a bar at their edge, and the list opens with the first of them in view.
 */
@Composable
private fun CodeLines(file: FullFile.Text) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val lines = file.lines
    val last = file.firstLine + lines.size - 1
    val gutter = last.toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rowWidth = remember(lines, type.code, density, gutter) {
        val longest = lines.maxByOrNull { it.length }.orEmpty()
        with(density) { measurer.measure(AnnotatedString("9".repeat(gutter) + "    " + longest + "  "), type.code).size.width.toDp() } + 28.dp
    }
    val accent = colors.accent
    val scrollTo = file.scrollTo?.let { (it - file.firstLine).coerceIn(0, (lines.size - 1).coerceAtLeast(0)) }
    // A few lines of what comes before the mark stay in view above it.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = scrollTo?.let { (it - LinesAboveMark).coerceAtLeast(0) } ?: 0)
    val sideways = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().horizontalScroll(sideways).testTag("full-file-lines"), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(lines) { index, line ->
            val number = file.firstLine + index
            val marked = number in file.highlighted
            Row(
                Modifier
                    .width(rowWidth)
                    .then(
                        if (marked) {
                            Modifier
                                .background(colors.accent.copy(alpha = 0.16f))
                                .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
                                .semantics { contentDescription = "Marked line $number" }
                                .testTag("full-file-marked")
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 12.dp, end = 12.dp),
            ) {
                Text(number.toString().padStart(gutter), style = type.code, color = if (marked) colors.textSecondary else colors.textQuaternary, softWrap = false)
                Spacer(Modifier.width(12.dp))
                Text(line.ifEmpty { " " }, style = type.code, color = if (marked) colors.textPrimary else colors.textSecondary, softWrap = false)
            }
        }
    }
}

/** A file that is not text: named, with its size, handed to another app or shared. */
@Composable
private fun BinaryRow(file: FullFile.Binary, name: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    var notice by remember(file) { mutableStateOf<String?>(null) }
    val mime = file.format?.mimeType ?: PromptFile.resolveMimeType(null, name)
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("full-file-binary")) {
        Text(listOfNotNull(file.format?.label ?: "Binary file", PromptFile.formatSize(file.sizeBytes)).joinToString(" \u00B7 "), style = type.base, color = colors.textSecondary)
        Text(notice ?: "This app shows no page for it; open it in another app.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Open with\u2026", style = type.small, color = colors.link, modifier = Modifier.pressable({ FileHandoff.open(context, File(file.keptPath), name, mime).onFailure { notice = it.message } }, CursorTheme.shapes.base).padding(vertical = 3.dp))
            Text("Share", style = type.small, color = colors.link, modifier = Modifier.pressable({ FileHandoff.share(context, File(file.keptPath), name, mime).onFailure { notice = it.message } }, CursorTheme.shapes.base).padding(vertical = 3.dp))
        }
    }
}

/** The file's text to another app, as text: the share sheet's own way for code and notes. */
private fun shareText(context: Context, name: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, name).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
}

/** Lines of what comes before a marked line kept in view above it when the file opens. */
private const val LinesAboveMark = 3
