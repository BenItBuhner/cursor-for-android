package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.SystemNotification
import com.cursorforandroid.domain.TranscriptRow
import com.cursorforandroid.domain.UserMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When the transcript asks for older turns (see [OlderPaging]): by the height the list draws, not by turns. */
class OlderPagingTest {

    private class Item(override val index: Int, override val offset: Int, override val size: Int) : LazyListItemInfo {
        override val key: Any get() = index
        override val contentType: Any? get() = null
    }

    /**
     * A bottom-anchored list (the transcript following) of [total] items in a [viewport] px viewport, the newest
     * [shown] of them in view at [size] px each: whatever of them does not fit is cut off at the top edge.
     */
    private fun following(total: Int, shown: Int, size: Int = 200, viewport: Int = 1_000): LazyListLayoutInfo = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = (0 until shown).map { i -> Item(i, offset = i * size, size = size) }
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
    fun `at rest a few pages are asked for, and the reader's scroll asks again`() {
        val paging = OlderPaging()
        val info = following(total = 3, shown = 3)
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
}
