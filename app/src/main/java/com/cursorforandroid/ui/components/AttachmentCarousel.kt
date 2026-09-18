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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * The composer's attachments in one [AttachmentCarousel], images and files together in the order attached: a pasted
 * or shared-in image as its thumbnail with its remove badge ([PendingImageThumbnail]), a file of any type as its chip
 * ([FileChip]) — the upload ring filling, the retry and the cross all working wherever the row has been scrolled to.
 * Bottom-aligned, so the thumbnails' badges overhang above the chips rather than push them down. [surface] is the
 * composer's own colour, which the fades are painted in.
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
) {
    AttachmentCarousel(modifier, state = state, surface = surface, verticalAlignment = Alignment.Bottom) {
        items(images, key = { "image:${it.id}" }) { image -> PendingImageThumbnail(image, onRemove = { onRemoveImage(image) }) }
        items(files, key = { "file:${it.id}" }) { file ->
            FileChip(file, upload = uploads[file.id], onRemove = { onRemoveFile(file) }, onRetry = onRetryFile?.let { retry -> { retry(file) } })
        }
    }
}
