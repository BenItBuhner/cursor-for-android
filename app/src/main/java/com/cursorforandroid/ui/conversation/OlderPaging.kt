package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptRow
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * When the turns before the oldest one shown are paged in: by what the list draws, not by how many turns it holds.
 *
 * A chat opens on its newest turns, and a turn is not a row. A stretch of hundreds of tool calls is one line, and so
 * is a run of injected turns — a worker's reports, a pull request's changes, a goal's continuations — that the agent
 * answered without a word. Counted in turns, a chat whose newest ten turns were such events opened on one collapsed
 * line under "Older messages", its newest reply a tap away, or several (Bennett, 2026-09-28).
 *
 * So older pages are asked for while the viewport is not filled with **visible** content — a collapsed tool group
 * counts as its header, not as the hidden steps behind it, even when the lazy item still measures those steps
 * (Bennett, 2026-10-02) — and, once it is filled, while less than [PREFETCH_SCREENS] of the list's height lies above
 * what is on screen (the rows off screen estimated at the visible size of the ones in view). Pages are also asked
 * for while no reply is among the rows at all: at rest as the chat opens, and ahead of the reader as they scroll up.
 *
 * At rest, once the viewport is filled, [UNATTENDED_PAGES] pages at most are asked for until the reader scrolls
 * again, so a Project whose hundreds of turns fold into a few rows is not paged in whole behind the reader's back,
 * every page's logs replayed and again on every reopen (0.3.47). Until the viewport is filled with visible content
 * that cap does not apply: giving up left "Older messages" over an empty screen whose only row was a collapsed
 * stretch (Bennett, 2026-10-02).
 */
@Stable
internal class OlderPaging {
    /** Pages asked for since the reader last scrolled. Read only when the list's layout is, never drawn. */
    private var unattended = 0

    fun scrolled() {
        unattended = 0
    }

    /**
     * Whether a page is wanted with the list laid out as [info]. [areaHeight] is the chat area the list sits in: a
     * short wrap-content list reports a viewport as tall as its rows, which is not the empty screen around them.
     * [collapsedCapPx] is how tall a collapsed group is allowed to count; [rows] name which visible items those are.
     */
    fun wants(
        info: LazyListLayoutInfo,
        scrolling: Boolean,
        replyShown: Boolean,
        rows: List<TranscriptRow> = emptyList(),
        collapsedCapPx: Int = Int.MAX_VALUE,
        areaHeight: Int = 0,
    ): Boolean {
        if (info.totalItemsCount == 0 || info.visibleItemsInfo.isEmpty()) return false
        val viewport = viewportHeight(info, areaHeight)
        if (viewport <= 0) return false
        if (!viewportFilled(info, rows, collapsedCapPx, areaHeight)) return true
        if (!scrolling && unattended >= UNATTENDED_PAGES) return false
        return !replyShown || contentAbove(info, rows, collapsedCapPx) < viewport * PREFETCH_SCREENS
    }

    fun asked(scrolling: Boolean) {
        if (scrolling) unattended = 0 else unattended++
    }

    companion object {
        /** How much of the list's height lies ready above the viewport before the reader gets there. */
        const val PREFETCH_SCREENS = 1.5f

        /** Pages asked for at rest, once the viewport is filled, before the reader's next scroll. */
        const val UNATTENDED_PAGES = 6

        /**
         * How far (px) the list's content reaches above the viewport's top edge, in either order: the part of the
         * top-most row cut off by the edge, and every item above it at the average visible size of the items in view.
         * A collapsed group is that average as its header, not as the hidden steps the item may still measure.
         */
        fun contentAbove(
            info: LazyListLayoutInfo,
            rows: List<TranscriptRow> = emptyList(),
            collapsedCapPx: Int = Int.MAX_VALUE,
        ): Int {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return 0
            val byKey = index(rows)
            val topmost = items.minBy { TranscriptScroll.top(it, info) }
            val shown = visibleSize(topmost, byKey, collapsedCapPx)
            val overlap = overlap(topmost, info)
            val rawCut = (TranscriptScroll.topPadding(info) - TranscriptScroll.top(topmost, info)).coerceAtLeast(0)
            val cut = rawCut.coerceAtMost((shown - min(shown, overlap)).coerceAtLeast(0))
            val average = items.sumOf { visibleSize(it, byKey, collapsedCapPx).coerceAtLeast(1) } / items.size + info.mainAxisItemSpacing
            return cut + TranscriptScroll.itemsAbove(info) * average
        }

        /**
         * How many pixels of **visible** transcript the list holds: the items on screen at their visible size
         * (collapsed groups capped at [collapsedCapPx], chrome counted as nothing) plus the items off screen at
         * that same average. A collapsed stretch that still measures thousands of pixels of hidden steps is one
         * header. The empty chat area around a wrap-content list is [areaHeight] in [viewportFilled], not here.
         */
        fun visibleContentHeight(
            info: LazyListLayoutInfo,
            rows: List<TranscriptRow> = emptyList(),
            collapsedCapPx: Int = Int.MAX_VALUE,
        ): Int {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return 0
            val byKey = index(rows)
            val sizes = items.map { visibleSize(it, byKey, collapsedCapPx) }
            val onScreen = sizes.sum()
            val counted = sizes.filter { it > 0 }
            val average = if (counted.isEmpty()) 0 else counted.sum() / counted.size + info.mainAxisItemSpacing
            return onScreen + (TranscriptScroll.itemsAbove(info) + itemsBelow(info)) * average
        }

        /**
         * How many pixels of visible transcript sit in the viewport: each item's overlap with the viewport,
         * collapsed groups capped at [collapsedCapPx], chrome counted as nothing.
         */
        fun visibleFill(
            info: LazyListLayoutInfo,
            rows: List<TranscriptRow> = emptyList(),
            collapsedCapPx: Int = Int.MAX_VALUE,
        ): Int {
            val byKey = index(rows)
            return info.visibleItemsInfo.sumOf { item ->
                min(overlap(item, info), visibleSize(item, byKey, collapsedCapPx))
            }
        }

        /** Whether the chat area is packed with visible transcript, not merely with a collapsed group's measured size. */
        fun viewportFilled(
            info: LazyListLayoutInfo,
            rows: List<TranscriptRow> = emptyList(),
            collapsedCapPx: Int = Int.MAX_VALUE,
            areaHeight: Int = 0,
        ): Boolean {
            val viewport = viewportHeight(info, areaHeight)
            if (viewport <= 0) return false
            val usable = (viewport - TranscriptScroll.topPadding(info) - TranscriptScroll.bottomPadding(info)).coerceAtLeast(0)
            return visibleContentHeight(info, rows, collapsedCapPx) >= usable
        }

        private fun itemsBelow(info: LazyListLayoutInfo): Int {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return 0
            return if (info.reverseLayout) {
                items.minOf { it.index }
            } else {
                (info.totalItemsCount - 1 - items.maxOf { it.index }).coerceAtLeast(0)
            }
        }

        /** Whether [rows] hold a reply: an agent's message, or a coordinator's message to the user. */
        fun replyShown(rows: List<TranscriptRow>): Boolean =
            rows.any { it is TranscriptRow.Message || (it is TranscriptRow.Item && it.item is AssistantMessage) }

        /** A collapsed disclosure: its header is what the reader sees; the steps behind it do not fill the page. */
        fun isCollapsedGroup(row: TranscriptRow): Boolean = when (row) {
            is TranscriptRow.Stretch -> true
            is TranscriptRow.Events -> !row.startsOpen
            is TranscriptRow.Item -> (row.item as? ActivityGroup)?.isWorkGrouped == true
            else -> false
        }

        /** Items that are not transcript: they must not count as filling the viewport. */
        fun isChrome(key: Any): Boolean = key in CHROME_KEYS

        private val CHROME_KEYS = setOf("older", "traces", "loading", "empty", "working", "end")

        private fun index(rows: List<TranscriptRow>): Map<Any, TranscriptRow> {
            if (rows.isEmpty()) return emptyMap()
            return HashMap<Any, TranscriptRow>(rows.size * 2).also { map -> rows.forEach { map[it.key] = it } }
        }

        private fun visibleSize(item: LazyListItemInfo, byKey: Map<Any, TranscriptRow>, collapsedCapPx: Int): Int {
            if (isChrome(item.key)) return 0
            val size = item.size
            val row = byKey[item.key]
            return if (row != null && isCollapsedGroup(row)) min(size, collapsedCapPx) else size
        }

        private fun overlap(item: LazyListItemInfo, info: LazyListLayoutInfo): Int {
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            return (min(item.offset + item.size, end) - max(item.offset, start)).coerceAtLeast(0)
        }

        private fun viewportHeight(info: LazyListLayoutInfo, areaHeight: Int): Int =
            max(info.viewportSize.height, max(info.viewportEndOffset - info.viewportStartOffset, areaHeight))
    }
}

/**
 * Pages older turns into the transcript [list] as [OlderPaging] says, through [loadOlder], while [canPage] (the chat
 * has older turns, rows are shown, and no page is on its way). [rows] are the transcript's rows as drawn, oldest first.
 * [areaHeight] is the chat area in px: a wrap-content list is as tall as its rows, and that is not the empty screen.
 *
 * Each page landed is asked about afresh, by the oldest row drawn: the page's loading is read off a conflated
 * presentation, which can go from one page to the next without ever showing it loading. Keyed on [canPage] alone, a
 * page that landed with the reader already at the top left the list wanting one more page all along, and the wish,
 * never having lapsed, was never asked again: the reader was left at "Older messages" (Bennett, 2026-09-29).
 */
@Composable
internal fun OlderPagingEffect(
    key: Any,
    list: LazyListState,
    rows: List<TranscriptRow>,
    canPage: Boolean,
    areaHeight: Int = 0,
    loadOlder: () -> Unit,
) {
    val paging = remember(key) { OlderPaging() }
    val load by rememberUpdatedState(loadOlder)
    val latestRows by rememberUpdatedState(rows)
    val latestArea by rememberUpdatedState(areaHeight)
    val collapsedCapPx = with(LocalDensity.current) { (DisclosureRowHeight + TranscriptItemSpacing).roundToPx() }
    val replyShown = remember(rows) { OlderPaging.replyShown(rows) }
    val oldest = rows.firstOrNull()?.key
    LaunchedEffect(paging, list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (scrolling) paging.scrolled() }
    }
    LaunchedEffect(paging, list, canPage, replyShown, oldest, collapsedCapPx) {
        if (!canPage) return@LaunchedEffect
        snapshotFlow {
            paging.wants(list.layoutInfo, list.isScrollInProgress, replyShown, latestRows, collapsedCapPx, latestArea)
        }
            .distinctUntilChanged()
            .collect { wanted ->
                if (!wanted) return@collect
                paging.asked(list.isScrollInProgress)
                load()
            }
    }
}
