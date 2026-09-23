package com.cursorforandroid.ui.components

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.media.MediaLoader
import com.cursorforandroid.domain.PromptFileKind
import com.cursorforandroid.ui.media.LocalMediaViewer
import java.io.File

/**
 * One row for everything attached to a prompt, scrolling sideways once it runs past its width — where a strip of
 * pictures over a column of chips used to stack into a block as tall as the message itself. Each end dissolves into
 * what is behind it while there is more past it ([horizontalScrollEdgeFade]: painted in [surface] when the row sits
 * on a flat colour, an offscreen dissolve otherwise), so a chip cut by the edge reads as more to come and never as
 * the last one; the fade eases back to a hard edge as the last chip comes fully into view. A fling decays as the
 * platform's does and settles with a chip's start at the edge ([SnapPosition.Start]) rather than on half a chip;
 * a drag that does not carry snaps to the nearest. The row is nothing at all when nothing is attached: its callers
 * leave it out, and it takes no height.
 *
 * The composer's attachments ([ComposerAttachments]) and a transcript prompt's, once it carries more than two
 * ([com.cursorforandroid.ui.conversation.MessageAttachments]), are both this.
 *
 * @param state the row's own, hoisted so an owner — or a test — can read where it stands; `canScrollBackward` /
 *   `canScrollForward` are what fade the start and the end.
 */
@Composable
fun AttachmentCarousel(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    surface: Color = Color.Unspecified,
    verticalAlignment: Alignment.Vertical = Alignment.Bottom,
    spacing: Dp = 8.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        modifier = modifier.horizontalScrollEdgeFade(state, surface = surface).testTag("attachment-row"),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = verticalAlignment,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Start),
        content = content,
    )
}

/**
 * The composer's attachments in one [AttachmentCarousel], by what they are rather than how they came in: every
 * picture and recording — pasted, shared in, picked from the gallery or the Files picker — as its own tile
 * ([MediaChip], no name, no size, a tap opening it in the app's viewer out of the tile), and a file of any other
 * kind as its chip ([FileChip]) with name, kind and size — the upload ring, the retry and the remove all working
 * wherever the row has been scrolled to. Bottom-aligned, so the tiles' badges overhang above the chips rather than
 * push them down. [surface] is the composer's own colour, which the fades are painted in.
 */
@Composable
fun ComposerAttachments(
    images: List<PendingAttachment>,
    onRemoveImage: (PendingAttachment) -> Unit,
    files: List<PendingFile>,
    onRemoveFile: (PendingFile) -> Unit,
    surface: Color,
    modifier: Modifier = Modifier,
    uploads: Map<String, FileUploadState> = emptyMap(),
    onRetryFile: ((PendingFile) -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    /** The chat the composer belongs to, for the viewer; null in the New Chat composer. */
    agentId: String? = null,
    /** Reads a restored recording's poster and length off its copy; null leaves such a tile with its play glyph. */
    media: MediaLoader? = null,
) {
    val context = LocalContext.current
    val previews = remember(context) { ComposerMediaPreviews(File(context.cacheDir, "composer-media")) }
    // Everything that is a picture or a recording, whichever way it came in, in the order attached: pasted and
    // shared-in images first (the strip's), then the picked files that are media. The viewer pages through these.
    val mediaItems = remember(images, files, uploads) {
        images.map { ComposerMediaItem.of(it) } + files.filter { it.isMedia }.map { ComposerMediaItem.of(it, uploads[it.id]) }
    }
    // A sound stays a chip, and opens the viewer's player out of it.
    val sounds = remember(files) { files.filter { !it.isMedia && it.file.kind == PromptFileKind.Audio }.associate { it.id to ComposerMediaItem.of(it, null) } }
    val openable = mediaItems + sounds.values
    val opener = rememberComposerMediaOpener(mediaItems, openable, previews, agentId)
    // Every copy the viewer can page to is written as soon as its tile is shown, so a page reached by a swipe never
    // finds its file missing; keyed by what is attached, not by an upload's progress.
    if (LocalMediaViewer.current != null) {
        LaunchedEffect(openable.map { it.id }) { runCatching { previews.ensureAll(openable) } }
    }
    val imageById = remember(images) { images.associateBy { it.id } }
    val fileById = remember(files) { files.associateBy { it.id } }
    AttachmentCarousel(modifier, state = state, surface = surface, verticalAlignment = Alignment.Bottom) {
        items(mediaItems, key = { "media:${it.id}" }) { item ->
            val file = fileById[item.id]
            // A recording that came back from disk without its poster gets one read off its copy.
            val preview = rememberVideoPreview(item, previews, media)
            val shown = if (preview != null) ComposerMediaItem(item.id, item.bytes, item.mimeType, item.name, preview.first ?: item.thumbnail, item.isVideo, preview.second ?: item.durationMs, item.upload) else item
            MediaChip(
                shown,
                onOpen = { slot -> opener.open(shown, slot) },
                onRemove = { imageById[item.id]?.let(onRemoveImage) ?: file?.let(onRemoveFile) },
                onRetry = if (file != null && onRetryFile != null) ({ onRetryFile(file) }) else null,
                src = previews.src(item.id, item.mimeType),
                onPress = { opener.warm(shown) },
            )
        }
        items(files.filterNot { it.isMedia }, key = { "file:${it.id}" }) { file ->
            val sound = sounds[file.id]
            FileChip(
                file,
                upload = uploads[file.id],
                onRemove = { onRemoveFile(file) },
                onRetry = onRetryFile?.let { retry -> { retry(file) } },
                onOpen = sound?.let { item -> { slot -> opener.open(item, slot) } },
                openSrc = sound?.let { previews.src(it.id, it.mimeType) },
            )
        }
    }
}
