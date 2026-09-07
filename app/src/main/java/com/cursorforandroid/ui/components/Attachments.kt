package com.cursorforandroid.ui.components

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

/** An image the user attached to the composer, kept with a small decoded thumbnail for the strip. */
class PendingAttachment(
    val id: String,
    val image: PromptImage,
    val thumbnail: ImageBitmap?,
)

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
            val result = withContext(Dispatchers.IO) { uris.take(remaining).map { loadAttachment(context, it) } }
            val ok = result.mapNotNull { it.getOrNull() }
            result.firstOrNull { it.isFailure }?.exceptionOrNull()?.message?.let(onError)
            if (uris.size > remaining) onError("Only ${PromptImage.MAX_COUNT} images can be attached to a prompt.")
            if (ok.isNotEmpty()) onPicked(ok)
        }
    }
    return {
        if (currentCount >= PromptImage.MAX_COUNT) onError("Only ${PromptImage.MAX_COUNT} images can be attached to a prompt.")
        else launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

private fun loadAttachment(context: Context, uri: Uri): Result<PendingAttachment> = runCatching {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri)?.lowercase() ?: "image/jpeg"
    if (!PromptImage.isSupported(mime)) error("Unsupported image type ($mime). Use PNG, JPEG, GIF or WebP.")
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the selected image.")
    if (bytes.size > PromptImage.MAX_BYTES) error("Images must be 15 MB or smaller.")
    // Downscaled here, once, so the upload — and the request the composer retries — carries only what the model uses.
    val image = AttachmentImages.prepare(bytes, mime)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image.bytes, 0, image.sizeBytes, bounds)
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 160)
    val thumb = BitmapFactory.decodeByteArray(image.bytes, 0, image.sizeBytes, BitmapFactory.Options().apply { inSampleSize = sample })
    PendingAttachment(id = uri.toString() + "@" + System.nanoTime(), image = image, thumbnail = thumb?.asImageBitmap())
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
