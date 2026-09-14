package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope

/**
 * Prefetching for the bottom-anchored transcript: the default strategy (which composes the next row ahead of a
 * scroll, and is kept for that) plus the [aheadCount] items past the last visible one whenever the list comes to
 * rest, whether or not the reader is scrolling.
 *
 * In a reversed list those are the turns just above the top edge, and they are what the keyboard uncovers. Hiding it
 * grows the viewport by the keyboard's height over about 150 ms — a frame budget of 8 ms at 120 Hz — and every turn
 * the growing viewport reaches has to be composed and measured on the frame it first fits: a markdown reply with a
 * code block or a table is several milliseconds of that, which is a dropped frame while the composer is riding the
 * keyboard's edge. Composed in idle frame time while the keyboard is still up, the same turns are a placement when
 * their moment comes. The composed-but-unused items are the ones the prefetcher holds for the lazy layout; they are
 * released when they are no longer the next in line.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class TranscriptPrefetchStrategy(private val aheadCount: Int = AHEAD_COUNT) : LazyListPrefetchStrategy {
    private val scrolling = LazyListPrefetchStrategy()
    private val ahead = LinkedHashMap<Int, LazyLayoutPrefetchState.PrefetchHandle>()

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        with(scrolling) { onScroll(delta, layoutInfo) }
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        with(scrolling) { onVisibleItemsUpdated(layoutInfo) }
        val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val wanted = if (last == null) emptyList() else (last + 1..minOf(last + aheadCount, layoutInfo.totalItemsCount - 1)).toList()
        // Handles for items that have scrolled into view, or that are no longer next in line, are let go; disposing
        // one whose item the layout has since adopted is a no-op, so a visible item is never composed twice.
        ahead.keys.filter { it !in wanted }.forEach { index -> ahead.remove(index)?.cancel() }
        wanted.forEach { index -> if (index !in ahead) ahead[index] = schedulePrefetch(index) }
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        with(scrolling) { onNestedPrefetch(firstVisibleItemIndex) }
    }

    private companion object {
        /** Two turns: a user prompt and the reply above it cover a keyboard's worth of transcript in the usual chat. */
        const val AHEAD_COUNT = 2
    }
}
