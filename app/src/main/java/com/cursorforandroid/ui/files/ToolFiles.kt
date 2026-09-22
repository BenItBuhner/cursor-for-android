package com.cursorforandroid.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.FileFormat
import com.cursorforandroid.domain.FileOpenRequest
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.LocalTranscriptControls
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.ThumbnailSlot
import com.cursorforandroid.ui.media.rememberThumbnailSlot
import com.cursorforandroid.ui.media.thumbnailSlot
import com.cursorforandroid.ui.theme.CursorTheme

/** Keeps an open [FileOpenRequest] across a rotation and a process death: its path, its call, its line. */
val FileOpenRequestSaver = listSaver<FileOpenRequest?, Any?>(
    save = { request -> if (request == null) emptyList() else listOf(request.path, request.callId, request.line) },
    restore = { saved -> if (saved.isEmpty()) null else FileOpenRequest(saved[0] as String, saved[1] as String?, saved[2] as Int?) },
)

/** The file a tool call's row opens on a tap of its path: a read's, an edit's or a write's; none for a deletion or a search. */
val ToolCall.openablePath: String?
    get() = when (kind) {
        ToolKind.Read, ToolKind.Edit, ToolKind.Create -> touchedPath?.takeIf { it.isNotBlank() }
        else -> null
    }

/**
 * What a tap on a file's path does, from a tool call's row or a search's hit: a picture, a recording or a sound
 * opens the media viewer out of the path itself — the picture the transcript carried when a read did, else the
 * file read from the agent's workspace or repository — and anything else opens the full-file viewer. [slot] is the
 * box the viewer grows out of; the caller marks the path's node with it.
 */
class FileOpener(val slot: ThumbnailSlot?, val open: () -> Unit, val label: String)

/** The opener for [path] as named by [call] (null for a hit), or null when nothing here can open it. */
@Composable
fun rememberFileOpener(path: String, call: ToolCall? = null, line: Int? = null, carried: String? = null): FileOpener? {
    val controls = LocalTranscriptControls.current
    val viewer = LocalMediaViewer.current
    val media = LocalMarkdownMedia.current
    val format = FileFormat.ofName(path)
    val kind = MediaEntry.kindOf(format)
    val name = ToolNames.basename(path)
    if (kind != null && viewer != null && media != null) {
        // A picture the read carried is what the agent saw; otherwise the path itself, read where the file is.
        val src = carried ?: (call?.payload as? ToolPayload.ReadMedia)?.src ?: path
        val slot = rememberThumbnailSlot(src, CursorTheme.shapes.base, crop = true)
        val entry = MediaEntry(src, kind, fileName = name, mimeType = format?.mimeType)
        return FileOpener(slot, { viewer.open(media.agentId, listOf(entry), src, slot, fallback = entry, autoplay = entry.isPlayable) }, "Open $name")
    }
    // A file of an Agent Store (a Project's context) is the store sheet's: it reads the store, not the workspace.
    val store = StorePath.parse(path)
    val onStore = media?.onOpenStorePath
    if (store != null && onStore != null) return FileOpener(null, { onStore(store) }, "Open $name")
    val onOpen = controls.onOpenFile ?: return null
    return FileOpener(null, { onOpen(FileOpenRequest(path, call?.callId, line)) }, "Open $name")
}

/**
 * The mark of a path that opens: a dotted underline in the row's faintest ink, the tap target the text itself —
 * inside the row's own, which still opens the call's details — and the node the media viewer grows out of.
 */
@Composable
fun Modifier.fileLink(opener: FileOpener): Modifier {
    val ink = CursorTheme.colors.textQuaternary
    return this
        .then(if (opener.slot != null) Modifier.thumbnailSlot(opener.slot) else Modifier)
        .pressable(opener.open, CursorTheme.shapes.sm)
        .semantics { onClick(opener.label) { opener.open(); true } }
        .drawBehind { dottedUnderline(ink) }
        .testTag("file-link")
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dottedUnderline(ink: Color) {
    val y = size.height - 1.dp.toPx()
    drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
}

/**
 * The files a search or a listing found, one per line — the file's name, its folder dimmed, the line of the hit —
 * each opening the file where it was found, at that line.
 */
@Composable
fun FileHitsList(hits: ToolPayload.FileHits, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth().testTag("file-hits"), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        hits.hits.forEach { hit ->
            val opener = rememberFileOpener(hit.path, line = hit.line)
            val name = ToolNames.basename(hit.path)
            val folder = hit.path.removeSuffix(name).trimEnd('/').takeIf { it.isNotEmpty() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp)
                    .then(if (opener != null) Modifier.then(if (opener.slot != null) Modifier.thumbnailSlot(opener.slot) else Modifier).pressable(opener.open, CursorTheme.shapes.base) else Modifier)
                    .padding(horizontal = 2.dp)
                    .testTag("file-hit"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CursorIcons.File, null, tint = colors.iconQuaternary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(name + (hit.line?.let { ":$it" } ?: ""), style = type.code, color = if (opener != null) colors.textSecondary else colors.textTertiary, maxLines = 1)
                folder?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (hits.truncated) {
            Text("More results than the stream carried", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        }
    }
}
