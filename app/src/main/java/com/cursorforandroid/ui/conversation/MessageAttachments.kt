package com.cursorforandroid.ui.conversation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.icon
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.ThumbnailSlot
import com.cursorforandroid.ui.media.rememberThumbnailSlot
import com.cursorforandroid.ui.media.thumbnailSlot
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The images of a prompt, shown inside its bubble: 88dp-tall thumbnails at their own aspect ratio (clamped so a
 * phone screenshot still reads as a tall strip and a panorama cannot swallow the bubble), wrapping onto further
 * lines when they do not fit. Tapping one opens it full screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachments(attachments: List<MessageAttachment>, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val images = attachments.filterNot { it.isFile }
    val files = attachments.filter { it.isFile }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { attachment -> AttachmentThumbnail(attachment, alpha = alpha) }
            }
        }
        // Files of any type, one card each — the desktop's `context-pill` for a document, with its size and a real
        // open: a picture or a recording into the media viewer, anything else to whatever app handles its type.
        files.forEach { attachment -> AttachmentFileCard(attachment, alpha = alpha) }
    }
}

/** The `file://` reference the transcript's media list knows the attachment by (see [com.cursorforandroid.ui.media.ConversationMedia]). */
private val MessageAttachment.src: String get() = "file://$path"

/**
 * Opens the media viewer on [attachment], grown out of [slot], among the conversation's media; false when there is
 * no viewer to open (a component test, a preview).
 */
@Composable
private fun rememberOpenInViewer(attachment: MessageAttachment, slot: ThumbnailSlot, seen: () -> ImageBitmap?): (() -> Boolean) {
    val viewer = LocalMediaViewer.current
    val media = LocalMarkdownMedia.current
    return {
        if (viewer == null) {
            false
        } else {
            val kind = if (attachment.kind == PromptFileKind.Video) MediaEntry.Kind.Video else MediaEntry.Kind.Image
            val fallback = MediaEntry(attachment.src, kind, fileName = attachment.name ?: attachment.path.substringAfterLast('/'), mimeType = attachment.mimeType)
            media?.onBeforeOpen?.invoke()
            viewer.open(media?.agentId, media?.entries?.invoke().orEmpty(), attachment.src, slot, seen = seen(), fallback = fallback, autoplay = kind == MediaEntry.Kind.Video)
            true
        }
    }
}

/**
 * A file the prompt carried: its kind's glyph, its name and its size, on the bubble's own surface. Tapping hands the
 * on-device copy to the system viewer through the app's `FileProvider` (`files/attachments/…`); a copy that is gone
 * — cleared with the account, or never kept — says so instead of opening nothing.
 */
@Composable
internal fun AttachmentFileCard(attachment: MessageAttachment, alpha: Float = 1f, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    val shape = CursorTheme.shapes.lg
    val name = attachment.name ?: "Document"
    val kind = attachment.kind
    var missing by remember(attachment.path) { mutableStateOf(false) }
    // A picture or a recording opens in the viewer, out of this card; the viewer says so itself when the copy is gone.
    val viewable = kind == PromptFileKind.Image || kind == PromptFileKind.Video
    val slot = rememberThumbnailSlot(attachment.src, shape, crop = true)
    val openInViewer = rememberOpenInViewer(attachment, slot) { null }
    Row(
        modifier
            .thumbnailSlot(slot)
            .widthIn(min = 160.dp, max = 320.dp)
            .cursorSurface(colors.fill.faded(alpha), colors.stroke.faded(alpha), shape)
            .pressable({ if (!(viewable && File(attachment.path).isFile && openInViewer()) && !openAttachedFile(context, attachment)) missing = true }, shape)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("attachment-file-card")
            .semantics { contentDescription = "Attached file $name, ${kind.label}, ${PromptFile.formatSize(attachment.sizeBytes)}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kind.icon(), null, tint = colors.iconSecondary.faded(alpha), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = type.base, color = colors.textPrimary.faded(alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (missing) "This file is no longer on this device." else "${kind.label} · ${PromptFile.formatSize(attachment.sizeBytes)}",
                style = type.small,
                color = (if (missing) colors.red else colors.textTertiary).faded(alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Opens the copy at [attachment]'s path with the system's handler for its type. False when the copy is gone; a
 * device with nothing to open the type shows the chooser's own word for that.
 */
internal fun openAttachedFile(context: Context, attachment: MessageAttachment): Boolean {
    val file = File(attachment.path)
    if (!file.isFile) return false
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (_: IllegalArgumentException) {
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType?.takeIf { it.isNotBlank() } ?: PromptFile.OCTET_STREAM)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, attachment.name ?: "Open file").apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** One picture of the prompt, cropped into its strip; a tap opens the viewer, which grows the whole picture out of the crop. */
@Composable
private fun AttachmentThumbnail(attachment: MessageAttachment, alpha: Float) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val width = (THUMB_HEIGHT * attachment.aspectRatio).coerceIn(THUMB_MIN_WIDTH, THUMB_MAX_WIDTH)
    // Decoded at twice the slot so the crop stays sharp on dense screens without paying for the full file.
    val targetPx = with(LocalDensity.current) { maxOf(width, THUMB_HEIGHT).roundToPx() * 2 }
    val image = rememberAttachmentImage(attachment.path, targetPx)
    val slot = rememberThumbnailSlot(attachment.src, shape, crop = true)
    val open = rememberOpenInViewer(attachment, slot) { (image as? LoadedImage.Ready)?.bitmap }
    Box(
        Modifier
            .thumbnailSlot(slot)
            .size(width, THUMB_HEIGHT)
            .cursorSurface(colors.fill.faded(alpha), colors.stroke.faded(alpha), shape)
            .pressable({ open() }, shape, enabled = image is LoadedImage.Ready),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            is LoadedImage.Ready -> Image(image.bitmap, contentDescription = "Attached image", contentScale = ContentScale.Crop, alpha = alpha, modifier = Modifier.fillMaxSize())
            LoadedImage.Missing -> Icon(CursorIcons.File, "Attached image unavailable", tint = colors.iconTertiary.faded(alpha), modifier = Modifier.size(16.dp))
            LoadedImage.Loading -> Unit
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
