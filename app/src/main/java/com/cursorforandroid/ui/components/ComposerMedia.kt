package com.cursorforandroid.ui.components

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.media.ThumbnailSlot
import com.cursorforandroid.ui.media.rememberThumbnailSlot
import com.cursorforandroid.ui.media.thumbnailSlot
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A picture or a recording attached to the composer, whichever way it arrived — the photo picker, the share sheet,
 * the clipboard, a drag, the camera — as the row shows it: one presentation, by what the thing is rather than how it
 * came in. Bennett's two frames: the same picture pasted was a bare thumbnail with a cross, and picked through
 * "Images and videos" a file chip with a name and "Image · 582 KB"; it is the first, always, and a file of any other
 * kind keeps the chip with its name, kind and size.
 */
class ComposerMediaItem(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    /** The file's own name for the viewer's title and a saved copy; null for a pasted or shared-in picture, which has none. */
    val name: String?,
    val thumbnail: ImageBitmap?,
    val isVideo: Boolean,
    val durationMs: Long?,
    /** Where its upload stands (a picked file, Extended mode); null for a pasted picture, which travels inline. */
    val upload: FileUploadState? = null,
) {
    companion object {
        fun of(attachment: PendingAttachment): ComposerMediaItem =
            ComposerMediaItem(attachment.id, attachment.image.bytes, attachment.image.mimeType, name = null, thumbnail = attachment.thumbnail, isVideo = false, durationMs = null)

        fun of(file: PendingFile, upload: FileUploadState?): ComposerMediaItem =
            ComposerMediaItem(file.id, file.file.bytes, file.file.mimeType, name = file.file.name, thumbnail = file.thumbnail, isVideo = file.isVideo, durationMs = file.durationMs, upload = upload)
    }

    /** A sound picked as a file: a chip, opening the viewer's player. */
    val isAudio: Boolean get() = !isVideo && mimeType.startsWith("audio/", ignoreCase = true)
}

/**
 * The on-device copies the viewer opens a composer's media from: the bytes are in memory until the message is sent,
 * and the viewer reads a `file://` reference, so a tap writes the picture or recording under the app's cache once
 * and opens that. The path is a function of the chip's id, so a tile can register its thumbnail for the transition
 * under the reference before the copy exists. Copies are the cache's to drop; anything older than a day goes when
 * the next one is written.
 */
class ComposerMediaPreviews(private val dir: File) {
    /** The `file://` reference the tile registers and the viewer opens. */
    fun src(id: String, mimeType: String): String = Uri.fromFile(file(id, mimeType)).toString()

    /** The copy on disk, written if it is not there yet; the reference the viewer opens. */
    suspend fun ensure(id: String, mimeType: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val target = file(id, mimeType)
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            dir.mkdirs()
            val temp = File(dir, target.name + ".part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) target.writeBytes(bytes)
        }
        pruneStale(keep = target)
        Uri.fromFile(target).toString()
    }

    private fun file(id: String, mimeType: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(dir, "m-$digest.${extensionFor(mimeType)}")
    }

    /** `png`, `mp4`…: the platform's word for the type, else the type's own subtype; the viewer reads the kind, not this. */
    private fun extensionFor(mimeType: String): String =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.lowercase())
            ?: mimeType.substringAfter('/', "bin").takeWhile { it.isLetterOrDigit() }.ifEmpty { "bin" }

    private fun pruneStale(keep: File) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        dir.listFiles()?.forEach { f -> if (f != keep && f.lastModified() < cutoff) f.delete() }
    }

    private companion object {
        val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(1)
    }
}

/** A recording's poster frame and length, as the platform reads them off a copy. */
internal object VideoPreview {
    /** From the provider's copy (a picker's URI), while the grant lasts. */
    fun of(context: Context, uri: Uri): Pair<ImageBitmap?, Long?>? = probe { it.setDataSource(context, uri) }

    private fun probe(open: (MediaMetadataRetriever) -> Unit): Pair<ImageBitmap?, Long?>? {
        val retriever = MediaMetadataRetriever()
        return try {
            open(retriever)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            // A frame a moment in, not the black first frame recordings tend to start on.
            val timeUs = (durationMs?.let { minOf(it / 3, 1_000L) } ?: 0L) * 1_000
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, POSTER_PX, POSTER_PX)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
            if (frame == null && durationMs == null) null else frame?.asImageBitmap() to durationMs
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val POSTER_PX = 192
}

/**
 * The tile for a picture or a recording in the composer's attachment row: the picture itself, cropped square, a
 * recording with its poster under a play glyph and its length in the corner — no name, no size — and a remove
 * badge over its top-end corner. Upload under way, a ring over the middle; failed, a warning, the retry in the menu.
 *
 * The geometry, chosen so that a tap meant to open the picture cannot take it off by mistake (Bennett: "the X is
 * too easy to hit when trying to open the chip"). The tile is [MediaTile] (48dp) square at the bottom-start of a
 * [MediaSlot] (60dp) slot, so the tile's top-end corner is 12dp in from the slot's. The badge is [MediaBadge] (18dp)
 * across with its centre [MediaBadgeOut] (3dp) outside that corner on both axes: it spans 42..60 × 0..18 of the
 * slot and overlaps the picture only in a 6dp corner square — nine tenths of it lie outside the picture. Its hit
 * target is the [MediaBadgeHit] (20dp) square in the slot's top-end corner, 40..60 × 0..20: it covers the whole badge
 * and reaches just 8dp into the tile along each axis, under the badge's own pixels; every other point of the
 * picture opens the viewer. A long press anywhere on the tile offers Open and Remove (and Retry upload when one
 * failed), as the transcript's media offer their actions on a hold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaChip(
    item: ComposerMediaItem,
    /** A tap on the picture, with the tile's slot for the viewer to grow out of. */
    onOpen: (ThumbnailSlot) -> Unit,
    onRemove: () -> Unit,
    onRetry: (() -> Unit)?,
    /** The reference the viewer will open the item under (see [ComposerMediaPreviews.src]); the tile registers for it. */
    src: String,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val upload = item.upload
    val uploading = upload?.isUploading == true
    val failed = upload?.failed == true
    val what = if (item.isVideo) "Attached video" else "Attached image"
    val status = when {
        failed -> "not uploaded"
        uploading -> "uploading ${(upload!!.progress * 100).toInt()}%"
        else -> null
    }
    val slot = rememberThumbnailSlot(src, shape, crop = true)
    Box(modifier.size(MediaSlot).testTag("media-chip")) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .thumbnailSlot(slot)
                .size(MediaTile)
                .cursorSurface(colors.fill, colors.stroke, shape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = colors.base),
                    onClickLabel = "Open",
                    onLongClickLabel = "Attachment actions",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = { onOpen(slot) },
                )
                .semantics { contentDescription = listOfNotNull(what, item.name, item.durationMs?.let(TimeFormat::clock), status).joinToString(", ") }
                .testTag("media-tile"),
        ) {
            if (item.thumbnail != null) {
                Image(item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!item.isVideo) {
                Icon(CursorIcons.Image, null, tint = colors.iconTertiary, modifier = Modifier.size(16.dp).align(Alignment.Center))
            }
            when {
                uploading -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    ProgressRing(progress = upload!!.progress, size = 16.dp, color = Color.White, strokeWidth = 2.dp)
                }
                failed -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(16.dp))
                }
                item.isVideo -> Box(Modifier.align(Alignment.Center).size(22.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(CursorIcons.Play, null, tint = Color.White, modifier = Modifier.size(11.dp).offset(x = 1.dp).testTag("media-play"))
                }
            }
            if (item.isVideo && item.durationMs != null && !uploading && !failed) {
                Text(
                    TimeFormat.clock(item.durationMs),
                    style = type.small.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        .testTag("media-duration"),
                )
            }
        }
        // The remove badge: three quarters outside the picture, its own small target in the slot's corner.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(MediaBadgeHit)
                .testTag("media-remove")
                .pressable(onRemove, RoundedCornerShape(4.dp), role = androidx.compose.ui.semantics.Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            // From the target's centre to the badge's: (+1, -1)dp with the sizes above.
            val badgeShift = MediaTile + MediaBadgeOut - (MediaSlot - MediaBadgeHit / 2)
            Box(
                Modifier
                    .offset(x = badgeShift, y = -badgeShift)
                    .size(MediaBadge)
                    .background(colors.textPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CursorIcons.Close, "Remove attachment", tint = colors.canvas, modifier = Modifier.size(10.dp))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = colors.elevated, shape = CursorTheme.shapes.lg) {
            MediaMenuItem("Open", CursorIcons.Eye) { menuOpen = false; onOpen(slot) }
            if (failed && onRetry != null) MediaMenuItem("Retry upload", CursorIcons.Refresh) { menuOpen = false; onRetry() }
            MediaMenuItem("Remove", CursorIcons.Trash, tint = colors.red) { menuOpen = false; onRemove() }
        }
    }
}

@Composable
private fun MediaMenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = CursorTheme.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = CursorTheme.typography.base, color = tint) },
        leadingIcon = { Icon(icon, null, tint = if (tint == CursorTheme.colors.textPrimary) CursorTheme.colors.iconSecondary else tint, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 20.dp),
        modifier = Modifier.size(width = 200.dp, height = 40.dp),
    )
}

/** The slot a media tile takes in the row: the tile, and room above and to its end for the badge to overhang. */
val MediaSlot: Dp = 60.dp
/** The picture itself, square. */
val MediaTile: Dp = 48.dp
/** The remove badge's diameter. */
val MediaBadge: Dp = 18.dp
/** How far the badge's centre sits outside the tile's top-end corner, on each axis. */
val MediaBadgeOut: Dp = 3.dp
/** The badge's hit target, a square in the slot's top-end corner. */
val MediaBadgeHit: Dp = 20.dp

/**
 * Opens the app's media viewer on one of the composer's media, out of its tile and among the rest of the row's, the
 * way a transcript's picture opens: the tile's picture drawn first, the page grown out of the tile's box, and shrunk
 * back into it on dismiss. The keyboard steps aside first — the viewer covers everything. The copy the viewer reads
 * is written on the way if it is not there yet; a tap where no viewer is hosted (a preview, a test) does nothing.
 */
@Composable
internal fun rememberOpenComposerMedia(
    items: List<ComposerMediaItem>,
    previews: ComposerMediaPreviews,
    agentId: String?,
): (ComposerMediaItem, ThumbnailSlot) -> Unit {
    val viewer = LocalMediaViewer.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    return { item, slot ->
        if (viewer != null && !viewer.isOpen) {
            val src = previews.src(item.id, item.mimeType)
            val entries = items.map { it.entry(previews) }
            scope.launch {
                runCatching { previews.ensure(item.id, item.mimeType, item.bytes) }
                keyboard?.hide()
                focus.clearFocus(force = true)
                viewer.open(agentId, entries, src, slot = slot, seen = item.thumbnail, fallback = item.entry(previews), autoplay = item.isVideo || item.isAudio)
            }
        }
    }
}

/** The viewer's page for one of the composer's media: titled by the file's name, or "Pasted image" for a picture that has none. */
internal fun ComposerMediaItem.entry(previews: ComposerMediaPreviews): MediaEntry = MediaEntry(
    src = previews.src(id, mimeType),
    kind = when {
        isVideo -> MediaEntry.Kind.Video
        isAudio -> MediaEntry.Kind.Audio
        else -> MediaEntry.Kind.Image
    },
    caption = if (name == null) PASTED_IMAGE else null,
    fileName = name ?: PASTED_IMAGE,
    mimeType = mimeType,
    durationMs = durationMs,
)

private const val PASTED_IMAGE = "Pasted image"


/**
 * A recording restored without its poster (a draft read back from disk): the poster and length read off the copy
 * the viewer would open, once, off the main thread; null until then and for a copy that will not say.
 */
@Composable
internal fun rememberVideoPreview(item: ComposerMediaItem, previews: ComposerMediaPreviews, media: MediaLoader?): Pair<ImageBitmap?, Long?>? {
    var preview by remember(item.id) { mutableStateOf<Pair<ImageBitmap?, Long?>?>(null) }
    LaunchedEffect(item.id, media) {
        if (!item.isVideo || (item.thumbnail != null && item.durationMs != null) || media == null) return@LaunchedEffect
        val src = runCatching { previews.ensure(item.id, item.mimeType, item.bytes) }.getOrNull() ?: return@LaunchedEffect
        val ref = MediaRef.parse(src, null) ?: return@LaunchedEffect
        val poster = runCatching { media.videoPoster(ref, POSTER_TARGET_PX) }.getOrNull() ?: return@LaunchedEffect
        preview = poster.frame?.asImageBitmap() to poster.durationMs
    }
    return preview
}

private const val POSTER_TARGET_PX = 192
