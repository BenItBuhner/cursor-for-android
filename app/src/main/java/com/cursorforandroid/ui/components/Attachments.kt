package com.cursorforandroid.ui.components

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.runtime.remember
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
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    // Remembered: the contract has no equals, and rememberLauncherForActivityResult keys its DisposableEffect on it,
    // so a fresh one unregisters and re-registers the launcher on every recomposition — which is every SSE delta on
    // the conversation screen.
    val contract = remember(remaining) { ActivityResultContracts.PickMultipleVisualMedia(maxItems = maxOf(remaining, 2)) }
    val singleContract = remember { ActivityResultContracts.PickVisualMedia() }
    val deliver: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { uris.take(remaining).map { loadAttachment(context, it) } }
                val ok = result.mapNotNull { it.getOrNull() }
                result.firstOrNull { it.isFailure }?.exceptionOrNull()?.message?.let(onError)
                if (uris.size > remaining) onError("Only ${PromptImage.MAX_COUNT} images can be attached to a prompt.")
                if (ok.isNotEmpty()) onPicked(ok)
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uris -> deliver(uris) }
    // PickMultipleVisualMedia insists on a limit above one, so with a single slot left it would let the user choose
    // two and then discard one of them after the fact. The single-item picker asks for exactly what will fit.
    val single = rememberLauncherForActivityResult(singleContract) { uri -> deliver(listOfNotNull(uri)) }
    return {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when {
            currentCount >= PromptImage.MAX_COUNT -> onError("Only ${PromptImage.MAX_COUNT} images can be attached to a prompt.")
            remaining == 1 -> single.launch(request)
            else -> launcher.launch(request)
        }
    }
}

private const val TooLargeMessage = "Images must be 15 MB or smaller."

private fun loadAttachment(context: Context, uri: Uri): Result<PendingAttachment> = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
    if (!PromptImage.isSupported(mime)) error("Unsupported image type ($mime). Use PNG, JPEG, GIF or WebP.")
    val bytes = readBoundedBytes(declaredSize(resolver, uri)) { resolver.openInputStream(uri) }
    // Downscaled here, once, so the upload — and the request the composer retries — carries only what the model uses.
    val image = AttachmentImages.prepare(bytes, mime)
    PendingAttachment(id = uri.toString() + "@" + System.nanoTime(), image = image, thumbnail = thumbnailOf(image))
}

/** What the provider says the selection weighs, or -1 when it will not say — which a picker is free to do. */
private fun declaredSize(resolver: ContentResolver, uri: Uri): Long {
    val queried = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1L
        }
    }.getOrNull() ?: -1L
    if (queried >= 0) return queried
    return runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
        .getOrNull()
        ?.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH } ?: -1L
}

/**
 * The selection's bytes, refused before they are all in memory when there are more than [PromptImage.MAX_BYTES] of
 * them. A declared size is only a hint — a provider may give none at all, and a document provider streaming a
 * 100 MB original is allowed to be wrong about it — so the copy stops one byte past the cap regardless. Reading the
 * whole stream first risked an OutOfMemoryError, which `runCatching` does not make safe, in place of the size
 * message the user is promised.
 */
internal fun readBoundedBytes(declaredSize: Long, open: () -> InputStream?): ByteArray {
    if (declaredSize > PromptImage.MAX_BYTES) error(TooLargeMessage)
    val stream = open() ?: error("Couldn't read the selected image.")
    val limit = PromptImage.MAX_BYTES + 1
    val out = ByteArrayOutputStream(if (declaredSize in 1..PromptImage.MAX_BYTES) declaredSize.toInt() else 256 * 1024)
    stream.use { input ->
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
    }
    if (out.size() > PromptImage.MAX_BYTES) error(TooLargeMessage)
    return out.toByteArray()
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
