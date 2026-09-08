package com.cursorforandroid.ui.conversation

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The images of a prompt, shown inside its bubble: 88dp-tall thumbnails at their own aspect ratio (clamped so a
 * phone screenshot still reads as a tall strip and a panorama cannot swallow the bubble), wrapping onto further
 * lines when they do not fit. Tapping one opens it full screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachments(attachments: List<MessageAttachment>, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<MessageAttachment?>(null) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            AttachmentThumbnail(attachment, onClick = { viewing = attachment })
        }
    }
    viewing?.let { AttachmentViewer(it, onDismiss = { viewing = null }) }
}

@Composable
private fun AttachmentThumbnail(attachment: MessageAttachment, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val width = (THUMB_HEIGHT * attachment.aspectRatio).coerceIn(THUMB_MIN_WIDTH, THUMB_MAX_WIDTH)
    // Decoded at twice the slot so the crop stays sharp on dense screens without paying for the full file.
    val targetPx = with(LocalDensity.current) { maxOf(width, THUMB_HEIGHT).roundToPx() * 2 }
    val image = rememberAttachmentImage(attachment.path, targetPx)
    Box(
        Modifier
            .size(width, THUMB_HEIGHT)
            .cursorSurface(colors.fill, colors.stroke, shape)
            .pressable(onClick, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            is LoadedImage.Ready -> Image(image.bitmap, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            LoadedImage.Missing -> Icon(CursorIcons.File, "Attached image unavailable", tint = colors.iconTertiary, modifier = Modifier.size(16.dp))
            LoadedImage.Loading -> Unit
        }
    }
}

/** Full-screen view of one attachment on a near-black scrim; tapping anywhere or the close button dismisses it. */
@Composable
private fun AttachmentViewer(attachment: MessageAttachment, onDismiss: () -> Unit) {
    // Twice the screen's long edge is more than a pinch can show; the file itself may be any size at all (a GIF
    // skips the capture-time downscale entirely), and decoding that is how the viewer used to run out of memory.
    val configuration = LocalConfiguration.current
    val targetPx = with(LocalDensity.current) { maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx() * 2 }
    // One of these is the size of every thumbnail in the chat put together, so it stays out of their cache.
    val image = rememberAttachmentImage(attachment.path, targetPx, cache = false)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            when (image) {
                is LoadedImage.Ready -> Image(image.bitmap, contentDescription = "Attached image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                LoadedImage.Missing -> Text("This image is no longer on this device.", style = CursorTheme.typography.base, color = Color.White.copy(alpha = 0.7f))
                LoadedImage.Loading -> SpinnerRing(size = 18.dp, color = Color.White)
            }
            FlatIconButton(
                CursorIcons.Close,
                "Close",
                onClick = onDismiss,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            )
        }
    }
}

private sealed interface LoadedImage {
    data object Loading : LoadedImage
    data object Missing : LoadedImage
    class Ready(val bitmap: ImageBitmap) : LoadedImage
}

/**
 * Decodes the file at [path] off the main thread, sampled down to roughly [targetEdgePx] on its long side (0 for the
 * full file), and keeps the result in a small in-memory cache so scrolling a list of bubbles does not decode twice.
 * A decode too big for that cache to hold sensibly passes [cache] as false and is dropped as soon as it is unused.
 */
@Composable
private fun rememberAttachmentImage(path: String, targetEdgePx: Int, cache: Boolean = true): LoadedImage {
    val key = "$path@$targetEdgePx"
    return produceState<LoadedImage>(initialValue = AttachmentImages.get(key)?.let { LoadedImage.Ready(it) } ?: LoadedImage.Loading, key) {
        if (value is LoadedImage.Loading) {
            val decoded = withContext(Dispatchers.IO) { decodeSampled(path, targetEdgePx) }
            value = if (decoded == null) LoadedImage.Missing else LoadedImage.Ready(decoded.also { if (cache) AttachmentImages.put(key, it) })
        }
    }.value
}

/**
 * [targetEdgePx] is what the caller can show; [MAX_DECODE_BYTES] is what this process will spend on one bitmap
 * whatever the caller asked for, since a stored attachment's dimensions are only capped for the formats the
 * capture-time downscale handles. Running out of memory here throws an `Error`, which no `runCatching` upstream
 * would catch, so a file too big to decode is reported as a missing one instead of taking the app down.
 */
internal fun decodeSampled(path: String, targetEdgePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    if (targetEdgePx > 0) {
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdgePx) sample *= 2
    }
    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) * 4 > MAX_DECODE_BYTES) sample *= 2
    return try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** Decoded attachment bitmaps, bounded by pixel bytes (24 MB), keyed by path and requested size. */
internal object AttachmentImages {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }

    /** Signing out deletes the attachment files; the pixels decoded from them must not outlive the account either. */
    fun clear() {
        cache.evictAll()
    }
}

/** 32 MB of pixels: over the largest bitmap a phone will render happily, under what a decode should risk. */
private const val MAX_DECODE_BYTES = 32L * 1024 * 1024

private val THUMB_HEIGHT = 88.dp
private val THUMB_MIN_WIDTH = 44.dp
private val THUMB_MAX_WIDTH = 160.dp
