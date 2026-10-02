package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When the transcript asks for older turns (see [OlderPaging]): by the height the list draws, not by turns. */
class OlderPagingTest {

    private class Item(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
        override val key: Any = index,
    ) : LazyListItemInfo {
        override val contentType: Any? get() = null
    }

    /**
     * A bottom-anchored list (the transcript following) of [total] items in a [viewport] px viewport, the newest
     * [shown] of them in view at [size] px each: whatever of them does not fit is cut off at the top edge.
     * [sizes] and [keys], when given, are the visible items newest-first (index 0).
     */
    private fun following(
        total: Int,
        shown: Int,
        size: Int = 200,
        viewport: Int = 1_000,
        sizes: List<Int>? = null,
        keys: List<Any>? = null,
    ): LazyListLayoutInfo = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = run {
            var offset = 0
            (0 until shown).map { i ->
                val h = sizes?.getOrNull(i) ?: size
                Item(i, offset = offset, size = h, key = keys?.getOrNull(i) ?: i).also { offset += h }
            }
        }
        override val viewportStartOffset: Int = 0
        override val viewportEndOffset: Int = viewport
        override val totalItemsCount: Int = total
        override val viewportSize: IntSize = IntSize(400, viewport)
        override val orientation: Orientation = Orientation.Vertical
        override val reverseLayout: Boolean = true
        override val beforeContentPadding: Int = 0
        override val afterContentPadding: Int = 0
        override val mainAxisItemSpacing: Int = 0
    }

    private fun stretch(id: String) = TranscriptRow.Stretch(
        listOf(TranscriptRow.Entry.Call(ToolCall(id, "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "$id.kt"), id)),
    )

    private fun events(id: String, startsOpen: Boolean = false) = TranscriptRow.Events(
        listOf(
            TranscriptRow.Event(
                SystemNotification(id = id, kind = SystemNotification.Kind.entries.first(), title = "#4 synchronize", raw = ""),
            ),
        ),
        startsOpen = startsOpen,
    )

    @Test
    fun `a list whose oldest row is in view has nothing above it and wants a page`() {
        val info = following(total = 3, shown = 3)
        assertThat(OlderPaging.contentAbove(info)).isEqualTo(0)
        assertThat(OlderPaging().wants(info, scrolling = false, replyShown = true)).isTrue()
    }

    @Test
    fun `rows off screen count at the size of the ones in view, the cut part of the top-most row with them`() {
        // Six rows of 200 px in a 1,000 px viewport: the top-most is cut by 200 px; four more rows lie above it.
        val info = following(total = 10, shown = 6)
        assertThat(OlderPaging.contentAbove(info)).isEqualTo(200 + 4 * 200)
        // 1,000 px above: short of a screen and a half.
        assertThat(OlderPaging().wants(info, scrolling = false, replyShown = true)).isTrue()
        // Twelve more rows above: nearly four screens; nothing is wanted.
        assertThat(OlderPaging().wants(following(total = 22, shown = 6), scrolling = false, replyShown = true)).isFalse()
    }

    @Test
    fun `with no reply among the rows a page is wanted however tall the list`() {
        val info = following(total = 40, shown = 6)
        assertThat(OlderPaging().wants(info, scrolling = false, replyShown = true)).isFalse()
        assertThat(OlderPaging().wants(info, scrolling = false, replyShown = false)).isTrue()
    }

    @Test
    fun `at rest a few pages are asked for once the viewport is filled, and the reader's scroll asks again`() {
        val paging = OlderPaging()
        // Six 200 px rows fill a 1,000 px viewport, with plenty more above so prefetch is satisfied.
        val info = following(total = 40, shown = 6)
        repeat(OlderPaging.UNATTENDED_PAGES) {
            assertThat(paging.wants(info, scrolling = false, replyShown = false)).isTrue()
            paging.asked(scrolling = false)
        }
        assertThat(paging.wants(info, scrolling = false, replyShown = false)).isFalse()
        // Scrolling, the list keeps asking as rows come into reach.
        assertThat(paging.wants(info, scrolling = true, replyShown = false)).isTrue()
        paging.scrolled()
        assertThat(paging.wants(info, scrolling = false, replyShown = false)).isTrue()
    }

    @Test
    fun `an empty or unlaid list wants nothing`() {
        val empty = object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = emptyList()
            override val viewportStartOffset: Int = 0
            override val viewportEndOffset: Int = 0
            override val totalItemsCount: Int = 0
        }
        assertThat(OlderPaging().wants(empty, scrolling = true, replyShown = false)).isFalse()
    }

    @Test
    fun `a reply is an agent's message or a coordinator's, not a prompt or an event`() {
        val prompt = TranscriptRow.Item(UserMessage("u", "Do the thing"))
        val event = TranscriptRow.Event(SystemNotification(id = "n", kind = SystemNotification.Kind.entries.first(), title = "#4 synchronize", raw = ""))
        assertThat(OlderPaging.replyShown(listOf(prompt, event))).isFalse()
        assertThat(OlderPaging.replyShown(listOf(prompt, TranscriptRow.Item(AssistantMessage("a", "Done."))))).isTrue()
    }

    @Test
    fun `a collapsed tool group does not fill the viewport, however tall the item measures`() {
        val stretch = stretch("work")
        val info = following(total = 2, shown = 2, viewport = 1_000, sizes = listOf(8_000, 40), keys = listOf(stretch.key, "older"))
        val rows = listOf(stretch)
        assertThat(OlderPaging.isCollapsedGroup(stretch)).isTrue()
        assertThat(OlderPaging.visibleFill(info, rows, collapsedCapPx = 80)).isEqualTo(80)
        assertThat(OlderPaging.viewportFilled(info, rows, collapsedCapPx = 80)).isFalse()
        val paging = OlderPaging()
        repeat(OlderPaging.UNATTENDED_PAGES) {
            assertThat(paging.wants(info, scrolling = false, replyShown = true, rows = rows, collapsedCapPx = 80)).isTrue()
            paging.asked(scrolling = false)
        }
        // Giving up after a few unattended pages is what left "Older messages" over an empty screen.
        assertThat(paging.wants(info, scrolling = false, replyShown = true, rows = rows, collapsedCapPx = 80)).isTrue()
    }

    @Test
    fun `a collapsed events line does not fill the page, an open one does by what it draws`() {
        val closed = events("closed", startsOpen = false)
        val open = events("open", startsOpen = true)
        val closedInfo = following(total = 1, shown = 1, viewport = 1_000, sizes = listOf(4_000), keys = listOf(closed.key))
        val openInfo = following(total = 1, shown = 1, viewport = 1_000, sizes = listOf(4_000), keys = listOf(open.key))
        assertThat(OlderPaging.viewportFilled(closedInfo, listOf(closed), collapsedCapPx = 80)).isFalse()
        assertThat(OlderPaging.viewportFilled(openInfo, listOf(open), collapsedCapPx = 80)).isTrue()
        assertThat(OlderPaging().wants(closedInfo, scrolling = false, replyShown = true, rows = listOf(closed), collapsedCapPx = 80)).isTrue()
        assertThat(OlderPaging().wants(openInfo, scrolling = false, replyShown = true, rows = listOf(open), collapsedCapPx = 80)).isFalse()
    }

    @Test
    fun `Older messages and the working caption do not count as filling the viewport`() {
        val stretch = stretch("live")
        val info = following(
            total = 3,
            shown = 3,
            viewport = 1_000,
            sizes = listOf(28, 40, 40),
            keys = listOf(stretch.key, "older", "working"),
        )
        assertThat(OlderPaging.visibleFill(info, listOf(stretch), collapsedCapPx = 80)).isEqualTo(28)
        assertThat(OlderPaging.viewportFilled(info, listOf(stretch), collapsedCapPx = 80)).isFalse()
    }

    @Test
    fun `a wrap-content list as tall as its rows does not fill the empty chat area around it`() {
        val stretch = stretch("work")
        val info = following(total = 2, shown = 2, viewport = 80, sizes = listOf(40, 40), keys = listOf(stretch.key, "older"))
        assertThat(OlderPaging.viewportFilled(info, listOf(stretch), collapsedCapPx = 80, areaHeight = 1_000)).isFalse()
        val paging = OlderPaging()
        repeat(OlderPaging.UNATTENDED_PAGES) { paging.asked(scrolling = false) }
        assertThat(
            paging.wants(info, scrolling = false, replyShown = true, rows = listOf(stretch), collapsedCapPx = 80, areaHeight = 1_000),
        ).isTrue()
    }

    @Test
    fun `unattended pages are not a reason to stop while the viewport is still empty`() {
        val paging = OlderPaging()
        val info = following(total = 3, shown = 3)
        repeat(OlderPaging.UNATTENDED_PAGES + 4) {
            assertThat(paging.wants(info, scrolling = false, replyShown = true)).isTrue()
            paging.asked(scrolling = false)
        }
        assertThat(OlderPaging.viewportFilled(info)).isFalse()
        assertThat(paging.wants(info, scrolling = false, replyShown = true)).isTrue()
    }

    @Test
    fun `scrolling past the loaded range still asks for a page once the viewport is filled`() {
        val paging = OlderPaging()
        val filled = following(total = 40, shown = 6)
        repeat(OlderPaging.UNATTENDED_PAGES) { paging.asked(scrolling = false) }
        assertThat(paging.wants(filled, scrolling = false, replyShown = false)).isFalse()
        assertThat(paging.wants(filled, scrolling = true, replyShown = false)).isTrue()
        paging.scrolled()
        // Oldest of the loaded window in view: prefetch short of a screen and a half, so a page is wanted
        // whether the reader is scrolling or has just stopped.
        val nearTop = following(total = 10, shown = 6)
        assertThat(paging.wants(nearTop, scrolling = true, replyShown = true)).isTrue()
        assertThat(paging.wants(nearTop, scrolling = false, replyShown = true)).isTrue()
    }
}
