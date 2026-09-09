package com.cursorforandroid.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** An image the user attached to the composer, kept with a small decoded thumbnail for the strip. */
class PendingAttachment(
    val id: String,
    val image: PromptImage,
    val thumbnail: ImageBitmap?,
) {
    companion object {
        /**
         * The strip's entry for an image that went out with a draft the composer takes back: the same bytes, a
         * thumbnail decoded from them again. Decodes a bitmap, so not for the main thread.
         */
        fun of(image: PromptImage): PendingAttachment =
            PendingAttachment(id = "returned@" + UUID.randomUUID(), image = image, thumbnail = thumbnailOf(image))
    }
}

private fun thumbnailOf(image: PromptImage): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image.bytes, 0, image.sizeBytes, bounds)
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 160)
    return BitmapFactory.decodeByteArray(image.bytes, 0, image.sizeBytes, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * Launches the system photo picker and converts the selection into [PendingAttachment]s, enforcing the API's
 * limits (5 images, 15 MB each, png / jpeg / gif / webp). Returns a function that opens the picker.
 */
@Composable
fun rememberImagePicker(
    currentCount: Int,
    onPicked: (List<PendingAttachment>) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remaining = (PromptImage.MAX_COUNT - currentCount).coerceAtLeast(1)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(remaining, 2))) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) { importAttachments(context, uris, currentCount) }
            if (imported.attachments.isNotEmpty()) onPicked(imported.attachments)
            imported.error?.let(onError)
        }
    }
    return {
        if (currentCount >= PromptImage.MAX_COUNT) onError(attachmentLimitMessage())
        else launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** Bytes read from a picker or the clipboard, still identified by the URI they came from. */
internal class ImagePayload(
    val id: String,
    val bytes: ByteArray,
    val declaredMime: String?,
)

/** What [importAttachments] / [importPayloads] made of a batch: the ones that loaded, and the first reason the rest did not. */
internal class AttachmentImport(
    val attachments: List<PendingAttachment>,
    val error: String? = null,
)

internal fun attachmentLimitMessage(): String = "Only ${PromptImage.MAX_COUNT} images can be attached to a prompt."

/**
 * Clipboard and IME image pastes often arrive as a wildcard image type or with no type at all. Magic bytes decide
 * when the declared type is missing or too vague, so a screenshot from the Android clipboard still becomes a prompt
 * image.
 */
internal fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 6 &&
        bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
    ) {
        return "image/gif"
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    ) {
        return "image/webp"
    }
    return null
}

internal fun resolveImageMime(declared: String?, bytes: ByteArray): String? {
    val mime = declared?.substringBefore(';')?.trim()?.lowercase()
    if (PromptImage.isSupported(mime)) return mime
    val sniffed = sniffImageMime(bytes)
    return sniffed.takeIf { PromptImage.isSupported(it) }
}

internal fun clipLooksLikeImage(description: ClipDescription): Boolean {
    for (i in 0 until description.mimeTypeCount) {
        if (description.getMimeType(i).lowercase().startsWith("image/")) return true
    }
    return false
}

internal fun isImageUri(resolver: ContentResolver, uri: Uri, clipIsImage: Boolean): Boolean {
    if (clipIsImage) return true
    val mime = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return PromptImage.isSupported(mime) || mime == "image/*" || mime.startsWith("image/")
}

internal fun imageUrisFromClip(resolver: ContentResolver, clip: ClipData): List<Uri> {
    val clipIsImage = clipLooksLikeImage(clip.description)
    return buildList {
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            if (isImageUri(resolver, uri, clipIsImage)) add(uri)
        }
    }
}

internal fun loadAttachment(bytes: ByteArray, declaredMime: String?, id: String): Result<PendingAttachment> = runCatching {
    if (bytes.size > PromptImage.MAX_BYTES) error("Images must be 15 MB or smaller.")
    val mime = resolveImageMime(declaredMime, bytes)
        ?: error("Unsupported image type (${declaredMime ?: "unknown"}). Use PNG, JPEG, GIF or WebP.")
    // Downscaled here, once, so the upload — and the request the composer retries — carries only what the model uses.
    val image = AttachmentImages.prepare(bytes, mime)
    PendingAttachment(id = id, image = image, thumbnail = thumbnailOf(image))
}

internal fun loadAttachment(context: Context, uri: Uri): Result<PendingAttachment> {
    val resolver = context.contentResolver
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        ?: return Result.failure(IllegalStateException("Couldn't read the image."))
    return loadAttachment(bytes, resolver.getType(uri), uri.toString() + "@" + System.nanoTime())
}

internal fun importAttachments(context: Context, uris: List<Uri>, currentCount: Int): AttachmentImport {
    if (uris.isEmpty()) return AttachmentImport(emptyList())
    val remaining = (PromptImage.MAX_COUNT - currentCount).coerceAtLeast(0)
    if (remaining == 0) return AttachmentImport(emptyList(), attachmentLimitMessage())
    val overflow = uris.size > remaining
    return attachmentImport(uris.take(remaining).map { loadAttachment(context, it) }, overflow)
}

internal fun importPayloads(payloads: List<ImagePayload>, currentCount: Int): AttachmentImport {
    if (payloads.isEmpty()) return AttachmentImport(emptyList())
    val remaining = (PromptImage.MAX_COUNT - currentCount).coerceAtLeast(0)
    if (remaining == 0) return AttachmentImport(emptyList(), attachmentLimitMessage())
    val overflow = payloads.size > remaining
    return attachmentImport(payloads.take(remaining).map { loadAttachment(it.bytes, it.declaredMime, it.id) }, overflow)
}

private fun attachmentImport(results: List<Result<PendingAttachment>>, overflow: Boolean): AttachmentImport {
    val ok = results.mapNotNull { it.getOrNull() }
    val error = results.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
        ?: if (overflow) attachmentLimitMessage() else null
    return AttachmentImport(ok, error)
}

/** 40px thumbnails with a remove control, shown above the composer text once something is attached. */
@Composable
fun AttachmentStrip(attachments: List<PendingAttachment>, onRemove: (PendingAttachment) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Row(modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        attachments.forEach { attachment ->
            Box(Modifier.size(44.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .align(Alignment.BottomStart)
                        .cursorSurface(colors.fill, colors.stroke, CursorTheme.shapes.base),
                ) {
                    if (attachment.thumbnail != null) {
                        Image(attachment.thumbnail, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(CursorIcons.File, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp).align(Alignment.Center))
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .background(colors.textPrimary, CircleShape)
                        .pressable({ onRemove(attachment) }, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CursorIcons.Close, "Remove attachment", tint = colors.canvas, modifier = Modifier.size(9.dp).offset(0.dp, 0.dp))
                }
            }
        }
    }
}
