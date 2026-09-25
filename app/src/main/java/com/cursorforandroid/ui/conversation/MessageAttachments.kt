package com.cursorforandroid.ui.conversation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.ui.components.AttachmentCarousel
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.domain.MediaRef
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
import com.cursorforandroid.util.TimeFormat
import com.cursorforandroid.util.ioThenMain
import java.io.File

/**
 * The attachments of a prompt, shown inside its bubble, by what they are — the composer's rule ([ComposerAttachments]):
 * 88dp-tall thumbnails at their own aspect ratio for the pictures (clamped so a phone screenshot still reads as a
 * tall strip and a panorama cannot swallow the bubble), a recording as its poster under a play glyph with its
 * length, and a card per file of any other kind. Up to two of them lay out as they always have — media wrapping
 * onto further lines, cards one under another; more than two go into one row that scrolls sideways with its ends
 * fading ([AttachmentCarousel], the composer's row), rather than a block of cards as tall as the bubble. Tapping a
 * picture or a recording opens it full screen, out of its thumbnail.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageAttachments(
    attachments: List<MessageAttachment>,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    /** For each attachment, by its place in [attachments]: where a send's flight lands it (see `sendAttachmentTarget`). */
    target: (Int) -> Modifier = { Modifier },
) {
    val ordinals = remember(attachments) { attachments.withIndex().associate { (index, attachment) -> attachment.path to index } }
    val targetOf = { attachment: MessageAttachment -> ordinals[attachment.path]?.let(target) ?: Modifier }
    val media = attachments.filter { !it.isFile || it.kind == PromptFileKind.Video }
    val files = attachments.filter { it.isFile && it.kind != PromptFileKind.Video }
    if (attachments.size > CAROUSEL_FROM) {
        // The pictures set the row's height, a card beside them fills it; among files alone a card keeps its own.
        // The fade dissolves offscreen: the bubble's fill is translucent, so there is no flat colour to paint it in.
        val withMedia = media.isNotEmpty()
        AttachmentCarousel(if (withMedia) modifier.height(THUMB_HEIGHT) else modifier, verticalAlignment = Alignment.CenterVertically, spacing = 6.dp) {
            items(media, key = { it.path }) { attachment -> AttachmentMedia(attachment, alpha = alpha, modifier = targetOf(attachment)) }
            items(files, key = { it.path }) { attachment -> AttachmentFileCard(attachment, alpha = alpha, modifier = targetOf(attachment).then(if (withMedia) Modifier.fillMaxHeight() else Modifier)) }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (media.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                media.forEach { attachment -> AttachmentMedia(attachment, alpha = alpha, modifier = targetOf(attachment)) }
            }
        }
        // Files of any type but a picture or a recording, one card each — the desktop's `context-pill` for a document,
        // with its size and a real open: a picture the API took as a document into the media viewer, anything else to
        // whatever app handles its type.
        files.forEach { attachment -> AttachmentFileCard(attachment, alpha = alpha, modifier = targetOf(attachment)) }
    }
}

/** A picture as its thumbnail, a recording as its poster tile. */
@Composable
private fun AttachmentMedia(attachment: MessageAttachment, alpha: Float, modifier: Modifier = Modifier) {
    if (attachment.kind == PromptFileKind.Video && attachment.isFile) AttachmentVideoThumbnail(attachment, alpha, modifier) else AttachmentThumbnail(attachment, alpha, modifier)
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
            val kind = when (attachment.kind) {
                PromptFileKind.Video -> MediaEntry.Kind.Video
                PromptFileKind.Audio -> MediaEntry.Kind.Audio
                else -> MediaEntry.Kind.Image
            }
            val fallback = MediaEntry(attachment.src, kind, fileName = attachment.name ?: attachment.path.substringAfterLast('/'), mimeType = attachment.mimeType)
            media?.onBeforeOpen?.invoke()
            viewer.open(media?.agentId, media?.entries?.invoke().orEmpty(), attachment.src, slot, seen = seen(), fallback = fallback, autoplay = kind != MediaEntry.Kind.Image)
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
    // A picture, a recording or a sound opens in the viewer, out of this card; the viewer says so itself when the copy is gone.
    val viewable = kind == PromptFileKind.Image || kind == PromptFileKind.Video || kind == PromptFileKind.Audio
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
private fun AttachmentThumbnail(attachment: MessageAttachment, alpha: Float, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val width = (THUMB_HEIGHT * attachment.aspectRatio).coerceIn(THUMB_MIN_WIDTH, THUMB_MAX_WIDTH)
    // Decoded at twice the slot so the crop stays sharp on dense screens without paying for the full file.
    val targetPx = with(LocalDensity.current) { maxOf(width, THUMB_HEIGHT).roundToPx() * 2 }
    val image = rememberAttachmentImage(attachment.path, targetPx)
    val slot = rememberThumbnailSlot(attachment.src, shape, crop = true)
    val open = rememberOpenInViewer(attachment, slot) { (image as? LoadedImage.Ready)?.bitmap }
    Box(
        modifier
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

/**
 * One recording of the prompt, as the composer showed it: its poster frame (read off the copy through the app's
 * loader, the way a reply's recording gets its poster — see `VideoBlock`), a play glyph, its length in the corner. A
 * tap opens the viewer on it, playing, grown out of this tile; a copy that is gone says so when the viewer opens.
 */
@Composable
private fun AttachmentVideoThumbnail(attachment: MessageAttachment, alpha: Float, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.lg
    val loader = LocalMarkdownMedia.current?.loader
    var frame by remember(attachment.path) { mutableStateOf<ImageBitmap?>(null) }
    var durationMs by remember(attachment.path) { mutableStateOf<Long?>(null) }
    val posterPx = with(LocalDensity.current) { THUMB_MAX_WIDTH.roundToPx() * 2 }
    LaunchedEffect(attachment.path, loader) {
        val probe = loader?.let { runCatching { it.videoPoster(MediaRef.Local(attachment.path), posterPx) }.getOrNull() } ?: return@LaunchedEffect
        frame = probe.frame?.asImageBitmap()
        durationMs = probe.durationMs
    }
    val aspect = frame?.let { it.width.toFloat() / it.height } ?: VIDEO_THUMB_ASPECT
    val width = (THUMB_HEIGHT * aspect).coerceIn(THUMB_MIN_WIDTH, THUMB_MAX_WIDTH)
    val slot = rememberThumbnailSlot(attachment.src, shape, crop = true)
    val open = rememberOpenInViewer(attachment, slot) { frame }
    val name = attachment.name ?: "Recording"
    Box(
        modifier
            .thumbnailSlot(slot)
            .size(width, THUMB_HEIGHT)
            .cursorSurface(Color.Black.faded(alpha), colors.stroke.faded(alpha), shape)
            .pressable({ open() }, shape)
            .testTag("attachment-video")
            .semantics { contentDescription = listOfNotNull("Attached video $name", durationMs?.let(TimeFormat::clock)).joinToString(", ") },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, alpha = alpha, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.size(28.dp).background(Color.White.copy(alpha = 0.92f * alpha), CircleShape), contentAlignment = Alignment.Center) {
            Icon(CursorIcons.Play, null, tint = Color(0xFF141414).faded(alpha), modifier = Modifier.size(15.dp).padding(start = 1.dp))
        }
        durationMs?.let { ms ->
            Text(
                TimeFormat.clock(ms),
                style = CursorTheme.typography.tiny.copy(lineHeight = 12.sp),
                color = Color.White.faded(alpha),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f * alpha), CursorTheme.shapes.sm)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
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
            // Back on the main thread once decoded: the write below is a snapshot state's, and this effect must not
            // resume on the decoder's thread (see ioThenMain).
            val decoded = ioThenMain { decodeSampled(path, targetEdgePx) }
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
/** A recording's tile before its poster says otherwise: 16:9, as most are. */
private const val VIDEO_THUMB_ASPECT = 16f / 9f
/** Attachments beyond this many go into the scrolling row. */
private const val CAROUSEL_FROM = 2
