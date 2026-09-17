package com.cursorforandroid.ui.components

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A file of any type the user attached to the composer (Extended mode); the id follows it to disk and back. */
class PendingFile(val id: String, val file: PromptFile) {
    companion object {
        fun of(file: PromptFile, id: String = "file@" + UUID.randomUUID()): PendingFile = PendingFile(id, file)
    }
}

/** Where one file's upload stands while the prompt is being sent: how much of it is up, or that it did not get there. */
data class FileUploadState(val progress: Float = 0f, val failed: Boolean = false) {
    val isUploading: Boolean get() = !failed
}

/**
 * Launches the system document picker for files of any type — several at once, as the desktop's cloud picker does
 * (`<input type=file multiple>` with no `accept`) — and turns the selection into [PendingFile]s, or [PendingAttachment]s
 * for the images among them, which stay images so they render inline as they always have. Enforces
 * [PromptFile.MAX_COUNT] files and [PromptFile.MAX_BYTES] each, before any byte is uploaded. Returns a function that
 * opens the picker.
 */
@Composable
fun rememberFilePicker(
    currentFileCount: Int,
    currentImageCount: Int,
    onPickedFiles: (List<PendingFile>) -> Unit,
    onPickedImages: (List<PendingAttachment>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contract = remember { ActivityResultContracts.OpenMultipleDocuments() }
    val launcher = rememberLauncherForActivityResult(contract) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importFiles(context, uris, currentFileCount, currentImageCount) }
                if (imported.files.isNotEmpty()) onPickedFiles(imported.files)
                if (imported.images.isNotEmpty()) onPickedImages(imported.images)
                imported.error?.let(onError)
            }
        }
    }
    return {
        if (currentFileCount >= PromptFile.MAX_COUNT) onError(fileLimitMessage()) else launcher.launch(arrayOf("*/*"))
    }
}

/** What [importFiles] made of a selection: the files and the images that loaded, and the first reason the rest did not. */
internal class FileImport(
    val files: List<PendingFile>,
    val images: List<PendingAttachment> = emptyList(),
    val error: String? = null,
)

internal fun fileLimitMessage(): String = "Only ${PromptFile.MAX_COUNT} files can be attached to a prompt."

/**
 * Reads the picked documents: images (by the provider's type or the bytes) join the image strip through the same
 * path as the photo picker, everything else becomes a [PendingFile] with the provider's display name and type. A
 * selection over the size cap is refused before it is all in memory (see [readBoundedBytes]).
 */
internal fun importFiles(context: Context, uris: List<Uri>, currentFileCount: Int, currentImageCount: Int): FileImport {
    if (uris.isEmpty()) return FileImport(emptyList())
    val resolver = context.contentResolver
    val files = ArrayList<PendingFile>()
    val images = ArrayList<PendingAttachment>()
    var error: String? = null
    var fileSlots = (PromptFile.MAX_COUNT - currentFileCount).coerceAtLeast(0)
    var imageSlots = (PromptImage.MAX_COUNT - currentImageCount).coerceAtLeast(0)
    for (uri in uris) {
        val result = loadFile(resolver, uri)
        val loaded = result.getOrElse { t ->
            if (error == null) error = t.message ?: "Couldn't read the file."
            continue
        }
        when (loaded) {
            is LoadedPick.Image -> if (imageSlots > 0) {
                imageSlots--
                images += loaded.attachment
            } else if (error == null) {
                error = attachmentLimitMessage()
            }
            is LoadedPick.File -> if (fileSlots > 0) {
                fileSlots--
                files += loaded.file
            } else if (error == null) {
                error = fileLimitMessage()
            }
        }
    }
    return FileImport(files, images, error)
}

internal sealed interface LoadedPick {
    class Image(val attachment: PendingAttachment) : LoadedPick
    class File(val file: PendingFile) : LoadedPick
}

/** One picked document as a file, or as an image when its type or its bytes say so. */
internal fun loadFile(resolver: ContentResolver, uri: Uri): Result<LoadedPick> = runCatching {
    val name = displayName(resolver, uri)
    val declared = resolver.getType(uri)
    val bytes = readBoundedBytes(declaredSize(resolver, uri), maxBytes = PromptFile.MAX_BYTES, tooLarge = PromptFile.TOO_LARGE_MESSAGE) { resolver.openInputStream(uri) }
    val imageMime = resolveImageMime(declared, bytes)
    if (imageMime != null) {
        return@runCatching LoadedPick.Image(loadAttachment(bytes, imageMime, uri.toString() + "@" + System.nanoTime()).getOrThrow())
    }
    LoadedPick.File(PendingFile.of(PromptFile(bytes, name, PromptFile.resolveMimeType(declared, name)), id = uri.toString() + "@" + System.nanoTime()))
}

/** The provider's display name, else the URI's last segment, else a placeholder: what the agent will know the file as. */
internal fun displayName(resolver: ContentResolver, uri: Uri): String {
    val queried = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return queried ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.trim()?.takeIf { it.isNotEmpty() } ?: "attachment"
}

/** The glyph a file's kind is shown with, in the chips and the transcript's cards. */
fun PromptFileKind.icon(): ImageVector = when (this) {
    PromptFileKind.Image -> CursorIcons.Image
    PromptFileKind.Video -> CursorIcons.Video
    PromptFileKind.Audio -> CursorIcons.Music
    PromptFileKind.Pdf, PromptFileKind.Text -> CursorIcons.FileText
    PromptFileKind.Code -> CursorIcons.Code
    PromptFileKind.Archive -> CursorIcons.FileArchive
    PromptFileKind.Other -> CursorIcons.File
}

/**
 * The composer's chips for attached files, one per file: its kind's glyph, its name and its size, and a remove
 * cross — as the desktop's `context-pill` names a document, with the size the desktop's guard checks made visible.
 * While the prompt is going out the glyph gives way to a ring filling with the upload; a file that did not get up
 * shows a warning and turns its cross into a retry.
 */
@Composable
fun FileChips(
    files: List<PendingFile>,
    onRemove: (PendingFile) -> Unit,
    modifier: Modifier = Modifier,
    uploads: Map<String, FileUploadState> = emptyMap(),
    onRetry: ((PendingFile) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        files.forEach { file ->
            FileChip(file, upload = uploads[file.id], onRemove = { onRemove(file) }, onRetry = onRetry?.let { retry -> { retry(file) } })
        }
    }
}

@Composable
private fun FileChip(file: PendingFile, upload: FileUploadState?, onRemove: () -> Unit, onRetry: (() -> Unit)?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val kind = file.file.kind
    val size = PromptFile.formatSize(file.file.sizeBytes.toLong())
    val status = when {
        upload == null -> null
        upload.failed -> "not uploaded"
        else -> "uploading ${(upload.progress * 100).toInt()}%"
    }
    Row(
        Modifier
            .widthIn(max = ChipMaxWidth)
            .cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.lg)
            .heightIn(min = ChipHeight)
            .padding(start = 8.dp, end = 4.dp)
            .testTag("file-chip")
            .semantics { contentDescription = listOfNotNull("Attached file ${file.file.name}", "${kind.label}, $size", status).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                upload == null -> Icon(kind.icon(), null, tint = colors.iconSecondary, modifier = Modifier.size(16.dp))
                upload.failed -> Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(15.dp))
                else -> ProgressRing(progress = upload.progress, size = 15.dp, color = colors.accent)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false).padding(vertical = 4.dp)) {
            Text(file.file.name, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    upload == null -> "${kind.label} · $size"
                    upload.failed -> "Upload failed · tap to retry"
                    else -> "Uploading · ${(upload.progress * 100).toInt()}%"
                },
                style = type.small,
                color = if (upload?.failed == true) colors.red else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (upload?.failed == true && onRetry != null) {
            TouchTarget(size = 24.dp, touchSize = 36.dp, shape = CircleShape, onClick = onRetry) {
                Icon(CursorIcons.Refresh, "Retry upload", tint = colors.iconPrimary, modifier = Modifier.size(14.dp))
            }
        }
        if (upload == null || upload.failed) {
            TouchTarget(size = 24.dp, touchSize = 36.dp, shape = CircleShape, onClick = onRemove) {
                Icon(CursorIcons.Close, "Remove attachment", tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

private val ChipHeight = 40.dp
private val ChipMaxWidth = 320.dp
