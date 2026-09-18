package com.cursorforandroid.ui.components

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.repo.AttachmentUploads
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A file of any type the user attached to the composer (Extended mode): a document from the Files picker, or a
 * picture or video from the gallery, as it is. The id follows it to disk and back; an image carries a small
 * [thumbnail] for its chip.
 */
class PendingFile(val id: String, val file: PromptFile, val thumbnail: ImageBitmap? = null) {
    /**
     * Whether the prompt will name it as an image (`selected_images[]`, one of the documented image types) and it
     * counts against the image slots; everything else — a video, a HEIC, a PDF — is a document and counts against the file slots.
     */
    val isImage: Boolean get() = PromptImage.isSupported(file.mimeType)

    companion object {
        /** Decodes the thumbnail of an image file, so not for the main thread; a file of any other kind has none. */
        fun of(file: PromptFile, id: String = "file@" + UUID.randomUUID()): PendingFile =
            PendingFile(id, file, thumbnail = if (PromptImage.isSupported(file.mimeType)) runCatching { thumbnailOf(PromptImage(file.bytes, file.mimeType)) }.getOrNull() else null)
    }
}

/**
 * Where one file's upload stands, from the moment it is attached: how much of it is up, that it is up ([done]) and the
 * prompt will carry its reference, or that it did not get there.
 */
data class FileUploadState(val progress: Float = 0f, val failed: Boolean = false, val done: Boolean = false) {
    val isUploading: Boolean get() = !failed && !done

    companion object {
        val DONE = FileUploadState(progress = 1f, done = true)

        fun of(status: AttachmentUploads.Status): FileUploadState = when (status) {
            is AttachmentUploads.Status.Uploading -> FileUploadState(progress = status.progress)
            is AttachmentUploads.Status.Done -> DONE
            is AttachmentUploads.Status.Failed -> FileUploadState(failed = true)
        }
    }
}

/** Images (the strip's, and the image files) and other files attached so far, for the pickers' slot counts. */
data class AttachmentCounts(val images: Int, val files: Int) {
    companion object {
        fun of(attachments: List<PendingAttachment>, files: List<PendingFile>): AttachmentCounts =
            AttachmentCounts(images = attachments.size + files.count { it.isImage }, files = files.count { !it.isImage })
    }
}

/**
 * [this] cut to what a prompt may carry, in order: the first [PromptImage.MAX_COUNT] images counting [imagesElsewhere]
 * already attached (the strip's), and the first [PromptFile.MAX_COUNT] other files.
 */
fun List<PendingFile>.withinSlots(imagesElsewhere: Int = 0): List<PendingFile> {
    var images = imagesElsewhere
    var others = 0
    return filter { file ->
        if (file.isImage) images++ < PromptImage.MAX_COUNT else others++ < PromptFile.MAX_COUNT
    }
}

/**
 * Launches the Android photo picker — several at once — and turns the selection into attachments. In the default
 * mode ([extended] false) it is filtered to images and they become [PendingAttachment]s, the documented
 * `prompt.images[]`, as they always have. In Extended mode it offers videos too and everything it returns is a real
 * file, a [PendingFile], uploaded as the gallery holds it: an image with a thumbnail on its chip, a video by its
 * name. Enforces the API's five images and the five files of a prompt, and [PromptFile.MAX_BYTES] each, before any
 * byte is uploaded. Returns a function that opens the picker.
 */
@Composable
fun rememberMediaPicker(
    extended: Boolean,
    counts: AttachmentCounts,
    onPickedImages: (List<PendingAttachment>) -> Unit,
    onPickedFiles: (List<PendingFile>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The slots left decide the picker's own limit: every image the picker may return has to have somewhere to go.
    val remaining = if (extended) (PromptImage.MAX_COUNT - counts.images + PromptFile.MAX_COUNT - counts.files).coerceAtLeast(1) else (PromptImage.MAX_COUNT - counts.images).coerceAtLeast(1)
    // Remembered: the contract has no equals, and rememberLauncherForActivityResult keys its DisposableEffect on it,
    // so a fresh one unregisters and re-registers the launcher on every recomposition — which is every SSE delta on
    // the conversation screen.
    val contract = remember(remaining) { ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(remaining, 2)) }
    val singleContract = remember { ActivityResultContracts.PickVisualMedia() }
    val deliver: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importMedia(context, uris, extended, counts) }
                if (imported.images.isNotEmpty()) onPickedImages(imported.images)
                if (imported.files.isNotEmpty()) onPickedFiles(imported.files)
                imported.error?.let(onError)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uris -> deliver(uris) }
    // PickMultipleVisualMedia insists on a limit above one, so with a single slot left it would let the user choose
    // two and then discard one of them after the fact. The single-item picker asks for exactly what will fit.
    val single = rememberLauncherForActivityResult(singleContract) { uri -> deliver(listOfNotNull(uri)) }
    return {
        val request = PickVisualMediaRequest(if (extended) ActivityResultContracts.PickVisualMedia.ImageAndVideo else ActivityResultContracts.PickVisualMedia.ImageOnly)
        val full = if (extended) counts.images >= PromptImage.MAX_COUNT && counts.files >= PromptFile.MAX_COUNT else counts.images >= PromptImage.MAX_COUNT
        when {
            full -> onError(if (extended) fileLimitMessage() else attachmentLimitMessage())
            remaining == 1 -> single.launch(request)
            else -> launcher.launch(request)
        }
    }
}

/**
 * Launches the system document picker for files of any type — several at once, as the desktop's cloud picker does
 * (`<input type=file multiple>` with no `accept`) — and turns the selection into [PendingFile]s, an image among them
 * included, with its thumbnail. Enforces the same slots as [rememberMediaPicker] and [PromptFile.MAX_BYTES] each,
 * before any byte is uploaded. Returns a function that opens the picker. Extended mode only.
 */
@Composable
fun rememberFilePicker(
    counts: AttachmentCounts,
    onPickedFiles: (List<PendingFile>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contract = remember { ActivityResultContracts.OpenMultipleDocuments() }
    val launcher = rememberLauncherForActivityResult(contract) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { importFiles(context, uris, counts) }
                if (imported.files.isNotEmpty()) onPickedFiles(imported.files)
                imported.error?.let(onError)
            }
        }
    }
    return {
        if (counts.files >= PromptFile.MAX_COUNT && counts.images >= PromptImage.MAX_COUNT) onError(fileLimitMessage()) else launcher.launch(arrayOf("*/*"))
    }
}

/** What an import made of a selection: the files and the images that loaded, and the first reason the rest did not. */
internal class FileImport(
    val files: List<PendingFile>,
    val images: List<PendingAttachment> = emptyList(),
    val error: String? = null,
)

internal fun fileLimitMessage(): String = "Only ${PromptFile.MAX_COUNT} files can be attached to a prompt."

/**
 * The photo picker's selection: in the default mode the images become inline attachments through [importAttachments];
 * in Extended mode every pick — image or video — becomes a [PendingFile] as the gallery holds it, through [importFiles].
 */
internal fun importMedia(context: Context, uris: List<Uri>, extended: Boolean, counts: AttachmentCounts): FileImport {
    if (!extended) {
        val imported = importAttachments(context, uris, counts.images)
        return FileImport(emptyList(), imported.attachments, imported.error)
    }
    return importFiles(context, uris, counts)
}

/**
 * Reads the picked documents as [PendingFile]s with the provider's display name and type — an image with its
 * thumbnail, counted against the image slots; everything else against the file slots. A selection over the size cap
 * is refused before it is all in memory (see [readBoundedBytes]), in the words of its kind.
 */
internal fun importFiles(context: Context, uris: List<Uri>, counts: AttachmentCounts): FileImport {
    if (uris.isEmpty()) return FileImport(emptyList())
    val resolver = context.contentResolver
    val files = ArrayList<PendingFile>()
    var error: String? = null
    var fileSlots = (PromptFile.MAX_COUNT - counts.files).coerceAtLeast(0)
    var imageSlots = (PromptImage.MAX_COUNT - counts.images).coerceAtLeast(0)
    for (uri in uris) {
        val loaded = loadFile(resolver, uri).getOrElse { t ->
            if (error == null) error = t.message ?: "Couldn't read the file."
            continue
        }
        if (loaded.isImage) {
            if (imageSlots > 0) {
                imageSlots--
                files += loaded
            } else if (error == null) {
                error = attachmentLimitMessage()
            }
        } else if (fileSlots > 0) {
            fileSlots--
            files += loaded
        } else if (error == null) {
            error = fileLimitMessage()
        }
    }
    return FileImport(files, error = error)
}

/**
 * One picked item as a file, as it is: the provider's display name (or one made from its kind and the time, as the
 * gallery names an export), its type from the provider or the name, its bytes bounded at [PromptFile.MAX_BYTES]. An
 * image gets its thumbnail decoded here, off the main thread.
 */
internal fun loadFile(resolver: ContentResolver, uri: Uri): Result<PendingFile> = runCatching {
    val declared = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
    val name = displayName(resolver, uri) ?: generatedName(declared)
    val tooLarge = PromptFile.tooLargeMessage(declared ?: PromptFile.resolveMimeType(null, name), name)
    val bytes = readBoundedBytes(declaredSize(resolver, uri), maxBytes = PromptFile.MAX_BYTES, tooLarge = tooLarge) { resolver.openInputStream(uri) }
    // The bytes have the last word on an image's type, as they do for a paste: a gallery export declared as `image/*`.
    val mime = sniffImageMime(bytes) ?: PromptFile.resolveMimeType(declared, name)
    PendingFile.of(PromptFile(bytes, name, mime), id = uri.toString() + "@" + System.nanoTime())
}

/** The provider's display name, else the URI's last segment; null when neither names the file. */
internal fun displayName(resolver: ContentResolver, uri: Uri): String? {
    val queried = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    // The photo picker's URIs end in a bare row id, which is no name; a document provider's end in the file's.
    return queried ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
}

/** `IMG_20260917_074100.jpg` / `VID_….mp4` / `FILE_….bin`, the way a gallery names an export, for a pick the provider left nameless. */
internal fun generatedName(mimeType: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMillis))
    val extension = mimeType?.substringAfter('/', "")?.takeIf { it.isNotEmpty() && it != "*" }?.let { subtype ->
        when (subtype) {
            "jpeg" -> "jpg"
            "quicktime" -> "mov"
            "x-matroska" -> "mkv"
            "svg+xml" -> "svg"
            else -> subtype.substringAfterLast('.').takeIf { it.length <= 5 } ?: "bin"
        }
    } ?: "bin"
    val prefix = when {
        mimeType?.startsWith("image/") == true -> "IMG"
        mimeType?.startsWith("video/") == true -> "VID"
        mimeType?.startsWith("audio/") == true -> "AUD"
        else -> "FILE"
    }
    return "${prefix}_$stamp.$extension"
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
 * The composer's chip for an attached file, one per file in the attachment row ([ComposerAttachments]): its kind's
 * glyph — an image's own thumbnail — its name and its size, and a remove cross — as the desktop's `context-pill`
 * names a document, with the size the desktop's guard checks made visible. From the moment a file is attached its
 * glyph is a ring filling with the upload, the cross cancelling it; up, the chip is the file at rest; a file that did
 * not get up shows a warning and a retry of the upload.
 */
@Composable
internal fun FileChip(file: PendingFile, upload: FileUploadState?, onRemove: () -> Unit, onRetry: (() -> Unit)?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val kind = file.file.kind
    val size = PromptFile.formatSize(file.file.sizeBytes.toLong())
    // At rest — nothing known of an upload, or one that is done — the chip is the file: glyph or thumbnail, kind, size.
    val atRest = upload == null || upload.done
    val failed = upload?.failed == true
    val status = when {
        atRest -> null
        failed -> "not uploaded"
        else -> "uploading ${(upload!!.progress * 100).toInt()}%"
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
        Box(Modifier.size(if (file.thumbnail != null && atRest) 28.dp else 18.dp), contentAlignment = Alignment.Center) {
            when {
                atRest && file.thumbnail != null -> Image(
                    file.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(CursorTheme.shapes.sm),
                )
                atRest -> Icon(kind.icon(), null, tint = colors.iconSecondary, modifier = Modifier.size(16.dp))
                failed -> Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(15.dp))
                else -> ProgressRing(progress = upload!!.progress, size = 15.dp, color = colors.accent)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false).padding(vertical = 4.dp)) {
            Text(file.file.name, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    atRest -> "${kind.label} · $size"
                    failed -> "Upload failed · tap to retry"
                    else -> "Uploading · ${(upload!!.progress * 100).toInt()}%"
                },
                style = type.small,
                color = if (failed) colors.red else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        if (failed && onRetry != null) {
            TouchTarget(size = 24.dp, touchSize = 36.dp, shape = CircleShape, onClick = onRetry) {
                Icon(CursorIcons.Refresh, "Retry upload", tint = colors.iconPrimary, modifier = Modifier.size(14.dp))
            }
        }
        // The cross stays through the upload: taking the file off cancels it.
        TouchTarget(size = 24.dp, touchSize = 36.dp, shape = CircleShape, onClick = onRemove) {
            Icon(CursorIcons.Close, "Remove attachment", tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
        }
    }
}

private val ChipHeight = 40.dp
private val ChipMaxWidth = 320.dp
