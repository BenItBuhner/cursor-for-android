package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptRow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * When the turns before the oldest one shown are paged in: by what the list draws, not by how many turns it holds.
 *
 * A chat opens on its newest turns, and a turn is not a row. A stretch of hundreds of tool calls is one line, and so
 * is a run of injected turns — a worker's reports, a pull request's changes, a goal's continuations — that the agent
 * answered without a word. Counted in turns, a chat whose newest ten turns were such events opened on one collapsed
 * line under "Older messages", its newest reply a tap away, or several (Bennett, 2026-09-28).
 *
 * So older pages are asked for while less than [PREFETCH_SCREENS] of the list's height lies above what is on screen
 * (the rows off screen estimated at the size of the ones in view, a collapsed group one row like any other), and
 * while no reply is among the rows at all: at rest as the chat opens, and ahead of the reader as they scroll up.
 * At rest [UNATTENDED_PAGES] pages at most are asked for until the reader scrolls again, so a Project whose hundreds
 * of turns fold into a few rows is not paged in whole behind the reader's back, every page's logs replayed and again
 * on every reopen (0.3.47).
 */
@Stable
internal class OlderPaging {
    /** Pages asked for since the reader last scrolled. Read only when the list's layout is, never drawn. */
    private var unattended = 0

    fun scrolled() {
        unattended = 0
    }

    /** Whether a page is wanted with the list laid out as [info], the reader scrolling or not, and a reply shown or not. */
    fun wants(info: LazyListLayoutInfo, scrolling: Boolean, replyShown: Boolean): Boolean {
        if (info.totalItemsCount == 0 || info.visibleItemsInfo.isEmpty()) return false
        if (!scrolling && unattended >= UNATTENDED_PAGES) return false
        return !replyShown || contentAbove(info) < info.viewportSize.height * PREFETCH_SCREENS
    }

    fun asked(scrolling: Boolean) {
        if (scrolling) unattended = 0 else unattended++
    }

    companion object {
        /** How much of the list's height lies ready above the viewport before the reader gets there. */
        const val PREFETCH_SCREENS = 1.5f

        /** Pages asked for at rest before the reader's next scroll. */
        const val UNATTENDED_PAGES = 6

        /**
         * How far (px) the list's content reaches above the viewport's top edge, in either order: the part of the
         * top-most row cut off by the edge, and every item above it at the average size of the items in view.
         */
        fun contentAbove(info: LazyListLayoutInfo): Int {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return 0
            val topmost = items.minBy { TranscriptScroll.top(it, info) }
            val cut = (TranscriptScroll.topPadding(info) - TranscriptScroll.top(topmost, info)).coerceAtLeast(0)
            val average = items.sumOf { it.size } / items.size + info.mainAxisItemSpacing
            return cut + TranscriptScroll.itemsAbove(info) * average
        }

        /** Whether [rows] hold a reply: an agent's message, or a coordinator's message to the user. */
        fun replyShown(rows: List<TranscriptRow>): Boolean =
            rows.any { it is TranscriptRow.Message || (it is TranscriptRow.Item && it.item is AssistantMessage) }
    }
}

/**
 * Pages older turns into the transcript [list] as [OlderPaging] says, through [loadOlder], while [canPage] (the chat
 * has older turns, rows are shown, and no page is on its way). [rows] are the transcript's rows as drawn, oldest first.
 *
 * Each page landed is asked about afresh, by the oldest row drawn: the page's loading is read off a conflated
 * presentation, which can go from one page to the next without ever showing it loading. Keyed on [canPage] alone, a
 * page that landed with the reader already at the top left the list wanting one more page all along, and the wish,
 * never having lapsed, was never asked again: the reader was left at "Older messages" (Bennett, 2026-09-29).
 */
@Composable
internal fun OlderPagingEffect(key: Any, list: LazyListState, rows: List<TranscriptRow>, canPage: Boolean, loadOlder: () -> Unit) {
    val paging = remember(key) { OlderPaging() }
    val load by rememberUpdatedState(loadOlder)
    val replyShown = remember(rows) { OlderPaging.replyShown(rows) }
    val oldest = rows.firstOrNull()?.key
    LaunchedEffect(paging, list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling -> if (scrolling) paging.scrolled() }
    }
    LaunchedEffect(paging, list, canPage, replyShown, oldest) {
        if (!canPage) return@LaunchedEffect
        snapshotFlow { paging.wants(list.layoutInfo, list.isScrollInProgress, replyShown) }
            .distinctUntilChanged()
            .collect { wanted ->
                if (!wanted) return@collect
                paging.asked(list.isScrollInProgress)
                load()
            }
    }
}