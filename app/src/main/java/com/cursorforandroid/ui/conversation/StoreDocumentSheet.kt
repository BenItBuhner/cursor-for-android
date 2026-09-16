package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.repo.StoreFileRepository
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CancellationException

/** What the sheet has of the document. */
internal sealed interface StoreDocument {
    data object Loading : StoreDocument
    data class Text(val text: String) : StoreDocument
    data class Failed(val message: String) : StoreDocument
}

/**
 * A file of a Project's context opened from a chat (`/cursor/stores/<mount>/…`): a markdown document rendered as
 * it reads — Preview — or as written — Source — an image or a recording as the figure it is, any other text file
 * as its text, read through the account's store reads (Extended mode) and kept on the device once read. The
 * header names the file and its path and opens the Project on cursor.com, where Cursor's own client shows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreDocumentSheet(ref: MediaRef.Store, files: StoreFileRepository, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val uriHandler = LocalUriHandler.current
    val path = ref.path
    CursorSheet(onDismiss = onDismiss) { _ ->
        SheetHeader(
            path.fileName,
            trailing = {
                Text(
                    "Open on cursor.com",
                    style = type.small,
                    color = colors.link,
                    modifier = Modifier.pressable({ runCatching { uriHandler.openUri(ref.webUrl) } }, CursorTheme.shapes.base).padding(horizontal = 6.dp, vertical = 4.dp).testTag("store-open-web"),
                )
            },
        )
        Text(path.text, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
        HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when {
            path.isImage -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) { ImageBlock(path.text, alt = path.fileName) }
            path.isVideo -> Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) { VideoBlock(path.text, poster = null) }
            else -> StoreTextDocument(ref, files)
        }
    }
}

@Composable
private fun ColumnScope.StoreTextDocument(ref: MediaRef.Store, files: StoreFileRepository) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val path = ref.path
    var attempt by remember(ref) { mutableIntStateOf(0) }
    var document by remember(ref) { mutableStateOf<StoreDocument>(StoreDocument.Loading) }
    // Markdown opens on its rendering; the source is a tap away. Anything else has only its text.
    var preview by rememberSaveable(ref.cacheKey) { mutableStateOf(true) }
    LaunchedEffect(ref, attempt) {
        document = StoreDocument.Loading
        document = try {
            StoreDocument.Text(files.readText(ref, refresh = attempt > 0))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            StoreDocument.Failed(t.userMessage())
        }
    }
    if (path.isMarkdown) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SegmentButton("Preview", selected = preview, tag = "store-preview") { preview = true }
            SegmentButton("Source", selected = !preview, tag = "store-source") { preview = false }
        }
    }
    when (val doc = document) {
        StoreDocument.Loading -> Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SpinnerRing(size = 13.dp)
            Spacer(Modifier.width(9.dp))
            Text("Loading…", style = type.base, color = colors.textQuaternary)
        }
        is StoreDocument.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CursorIcons.Warning, null, tint = colors.orange, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(doc.message, style = type.base, color = colors.textSecondary, modifier = Modifier.testTag("store-document-error"))
            }
            CursorButton("Retry", onClick = { attempt++ })
        }
        is StoreDocument.Text -> if (path.isMarkdown && preview) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp)
                    .testTag("store-document-preview"),
            ) {
                MarkdownText(doc.text.ifBlank { "*(empty document)*" }, style = type.message)
            }
        } else {
            SelectionContainer {
                Text(
                    doc.text.ifBlank { "(empty file)" },
                    style = type.code,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                        .testTag("store-document-source"),
                )
            }
        }
    }
}

/** One of the sheet's two views, as a small toggle: filled when it is the one showing. */
@Composable
private fun SegmentButton(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.full
    Box(
        Modifier
            .height(26.dp)
            .background(if (selected) colors.fillMedium else colors.fillFaint, shape)
            .pressable(onClick, shape)
            .padding(horizontal = 11.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CursorTheme.typography.small, color = if (selected) colors.textPrimary else colors.textTertiary)
    }
}

/** The store file [path] names as read in [chatAgentId]'s chat, or null when the mount names no store this chat can read. */
internal fun storeRef(path: StorePath, chatAgentId: String?): MediaRef.Store? {
    val owner = path.ownerId(chatAgentId) ?: return null
    return MediaRef.Store(owner, path.relativePath)
}
