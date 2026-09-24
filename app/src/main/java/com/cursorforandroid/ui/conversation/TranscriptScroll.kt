package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.overscroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.indexForKey
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import com.cursorforandroid.domain.TranscriptRow
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Told when the reader taps a dropdown among the transcript's rows, before it opens or closes (see
 * [TranscriptScroll.toggling]); nothing where the rows are drawn without the conversation's list around them.
 */
internal fun interface DisclosureTaps {
    fun toggling(opening: Boolean)
}

internal val LocalDisclosureTaps = staticCompositionLocalOf { DisclosureTaps { } }

/**
 * The transcript's two ways of holding still, and which one it is in.
 *
 * Following, the list is bottom-anchored (`reverseLayout`): the newest row is index 0, and whatever grows — a thought
 * streaming, a tool call landing, a reply being written — grows upward from the composer, so the newest line stays in
 * view with nothing to scroll, and the keyboard carries the newest turn on its edge. That is right only for a reader
 * at the bottom. A bottom-anchored list keeps its bottom-most visible row where it is, so a reader scrolled up into an
 * open live stretch — the stretch then being that row — saw every streamed line push everything on screen up by the
 * line's height, and a dropdown opened anywhere above the bottom row pushed the row that was tapped up by the height
 * of what opened (Bennett, 2026-09-22).
 *
 * Pinned, the same rows are laid out top-down: the list keeps its top-most visible row where it is, by key, and what
 * grows below that row's top — inside an open group, in the live turn, off the bottom edge — moves nothing the reader
 * is looking at. The reader leaves following by scrolling off the bottom (any scroll they make: a drag, a fling, a
 * wheel, an accessibility action) or by opening a dropdown, which then opens under the finger; a scroll that comes to
 * rest at the bottom, or the jump button, follows again. Closing a dropdown while following stays following: at the
 * bottom there is nothing below the row to fill the space, and the bottom-anchored list closing it is what a
 * top-anchored one would do once it came back to its end.
 *
 * Switching is exact: the rows, their keys and their compositions are the same in both orders, and the scroll
 * position of the new order is the one that puts a row seen on screen where it already is (see [orient]). It happens
 * mid-gesture, the reader's first pixel of scroll being what sets it off, so the reader's scroll is not the list's own
 * but one that reads the same in both orders (see [ScreenScroll]).
 */
@Stable
internal class TranscriptScroll(val list: LazyListState, private val followingState: MutableState<Boolean>) : DisclosureTaps {
    /** Whether the list follows the newest row, bottom-anchored; else it holds what is on screen, top-anchored. */
    var following: Boolean
        get() = followingState.value
        private set(value) { followingState.value = value }

    /** The order the list was last composed in; a new one is positioned as it is composed (see [orient]). */
    private var composedFollowing = followingState.value
    private var composedOrder: TranscriptOrder? = null

    /** The jump button's own scroll is under way: a scroll the reader did not make. */
    var isJumping by mutableStateOf(false)
        private set

    fun pin() {
        following = false
    }

    fun follow() {
        following = true
    }

    override fun toggling(opening: Boolean) {
        if (opening) pin()
    }

    /** The jump button: follow again, then scroll to the newest row from where the reader is. */
    suspend fun jumpToBottom() {
        isJumping = true
        try {
            list.stopScroll(MutatePriority.PreventUserInput)
            follow()
            snapshotFlow { list.layoutInfo.reverseLayout }.first { it }
            list.animateScrollToItem(0)
        } finally {
            isJumping = false
        }
    }

    /**
     * After the list is composed in [composed] order: positions a list that has just changed order so the rows on
     * screen stay exactly where they are; and, pinned, holds the rows being read when rows land above them in a way
     * the list's own keyed anchoring would not follow (see [holdAboveInsert]).
     */
    fun orient(composed: Boolean, order: TranscriptOrder) {
        composedOrder = order
        val info = list.layoutInfo
        if (composed != composedFollowing) {
            composedFollowing = composed
            if (info.reverseLayout == composed) return
            val position = if (composed) followingPosition(info, order) else pinnedPosition(info, order)
            position?.let { (index, offset) -> list.requestScrollToItem(index, offset) }
        } else if (!composed && !info.reverseLayout) {
            holdAboveInsert(info, order)
        }
    }

    /**
     * Pinned, the list keeps its first visible item by key, which is right for every row but the items above the
     * transcript's own — "Older messages", the trace line, which go as well as come — and for a row that moved further
     * than the list looks for it. Under one of those, an older page landing would push the rows being read down by the
     * page; the first row on screen is held instead.
     */
    private fun holdAboveInsert(info: LazyListLayoutInfo, order: TranscriptOrder) {
        val visible = info.visibleItemsInfo
        val first = visible.firstOrNull() ?: return
        if (order.keyAt(first.index, following = false) == first.key && first.key !in order.above) return
        val held = if (order.isRow(first.key)) first else visible.firstOrNull { order.isRow(it.key) } ?: return
        val index = order.index(held.key, following = false) ?: return
        if (index == held.index || (held === first && abs(index - held.index) < KEYED_REACH)) return
        list.requestScrollToItem(index, topPadding(info) - top(held, info))
    }

    /** The item's index counted from the top, whichever order the list is in: what accessibility services are given. */
    fun topDownIndex(key: Any): Int = composedOrder?.index(key, following = false) ?: -1

    val itemCount: Int get() = composedOrder?.size ?: 0

    suspend fun scrollToTopDown(index: Int) {
        val order = composedOrder ?: return
        list.scrollToItem(if (composedFollowing) order.size - 1 - index else index)
    }

    /** How far the list is scrolled from its top, in the screen's terms: zero at the top, rising toward the newest row. */
    fun screenOffset(screen: ScrollableState): Float {
        val info = list.layoutInfo
        if (!screen.canScrollBackward) return 0f
        val item = info.visibleItemsInfo.minByOrNull { top(it, info) } ?: return 0f
        return (itemsAbove(info) * ESTIMATED_ITEM_PX + (topPadding(info) - top(item, info)).coerceAtLeast(0) + 1).toFloat()
    }

    /** Top-down: the item crossing the viewport's top edge (or the first below it), at its top as it is now. */
    private fun pinnedPosition(info: LazyListLayoutInfo, order: TranscriptOrder): Pair<Int, Int>? {
        val edge = topPadding(info)
        val item = info.visibleItemsInfo.filter { top(it, info) + it.size > edge }.minByOrNull { top(it, info) } ?: return null
        val index = order.index(item.key, following = false) ?: return null
        return index to edge - top(item, info)
    }

    /** Bottom-up: the item crossing the viewport's bottom edge (or the last above it), at its bottom as it is now. */
    private fun followingPosition(info: LazyListLayoutInfo, order: TranscriptOrder): Pair<Int, Int>? {
        val edge = info.viewportSize.height - bottomPadding(info)
        val item = info.visibleItemsInfo.filter { top(it, info) < edge }.maxByOrNull { top(it, info) + it.size } ?: return null
        val index = order.index(item.key, following = true) ?: return null
        return index to top(item, info) + item.size - edge
    }

    companion object {
        /** How far (px) the newest row may be scrolled past before the reader counts as having left the bottom. */
        const val BOTTOM_TOLERANCE_PX = 48

        /**
         * How many places a row may move before the list's keyed anchoring no longer finds it: it looks for the key
         * only among the items near where it was (`LazyLayoutNearestRangeState`, 100 either side of a 30-item window).
         */
        private const val KEYED_REACH = 90

        /** What an item above the viewport counts for in [screenOffset], as the lazy list's own estimate counts it. */
        private const val ESTIMATED_ITEM_PX = 500

        /** The item's top edge in the list's own box, in either order: a reversed list measures offsets from its bottom. */
        fun top(item: LazyListItemInfo, info: LazyListLayoutInfo): Int =
            if (info.reverseLayout) info.viewportSize.height - info.beforeContentPadding - item.offset - item.size else item.offset + info.beforeContentPadding

        fun topPadding(info: LazyListLayoutInfo): Int = if (info.reverseLayout) info.afterContentPadding else info.beforeContentPadding

        fun bottomPadding(info: LazyListLayoutInfo): Int = if (info.reverseLayout) info.beforeContentPadding else info.afterContentPadding

        /** Whether the newest row is in view, within [tolerance] px of the bottom edge, in either order. */
        fun atBottom(info: LazyListLayoutInfo, tolerance: Int = BOTTOM_TOLERANCE_PX): Boolean {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return true
            if (info.reverseLayout) {
                val first = items.first()
                return first.index == 0 && -first.offset <= tolerance
            }
            val last = items.last()
            return last.index == info.totalItemsCount - 1 && top(last, info) + last.size <= info.viewportSize.height - bottomPadding(info) + tolerance
        }

        /** Whether the list is scrolled off its bottom at all: following, the reader's first pixel of scroll away. */
        fun offBottom(info: LazyListLayoutInfo): Boolean = !atBottom(info, tolerance = 0)

        /** How many items lie above the top-most one in view, in either order: the older turns are asked for as this nears zero. */
        fun itemsAbove(info: LazyListLayoutInfo): Int {
            val items = info.visibleItemsInfo
            if (items.isEmpty()) return 0
            return if (info.reverseLayout) info.totalItemsCount - 1 - items.last().index else items.first().index
        }
    }
}

/**
 * The transcript's items top to bottom as the reader sees them: the rows, the items [above] them (the loading and
 * empty states, "Older messages", the trace line) and [below] them (the working caption). A following list is
 * declared bottom-up, so an item's index depends on the order (see [index]).
 */
internal class TranscriptOrder(val above: List<String>, val rows: List<TranscriptRow>, val below: List<String>) {
    val size: Int get() = above.size + rows.size + below.size

    private val rowIndex: Map<String, Int> by lazy(LazyThreadSafetyMode.NONE) { HashMap<String, Int>(rows.size * 2).also { map -> rows.forEachIndexed { i, row -> map[row.key] = i } } }

    fun index(key: Any, following: Boolean): Int? {
        val topDown = above.indexOf(key).takeIf { it >= 0 }
            ?: rowIndex[key]?.plus(above.size)
            ?: below.indexOf(key).takeIf { it >= 0 }?.plus(above.size + rows.size)
            ?: return null
        return if (following) size - 1 - topDown else topDown
    }

    fun isRow(key: Any): Boolean = key in rowIndex

    fun keyAt(index: Int, following: Boolean): Any? {
        val topDown = if (following) size - 1 - index else index
        return when {
            topDown < 0 || topDown >= size -> null
            topDown < above.size -> above[topDown]
            topDown < above.size + rows.size -> rows[topDown - above.size].key
            else -> below[topDown - above.size - rows.size]
        }
    }
}

/**
 * The reader's scrolling of the transcript, in the screen's terms whichever order the list is in: forward is toward
 * the newest row. The list's own gesture handling takes its direction from `reverseLayout`, and a scrollable whose
 * direction changes drops the gesture under way — the reader's drag would stop dead at the switch its own first pixel
 * sets off. So the list's is off and this one drives it (see [readerScrolling]), each delta mapped onto the list as it
 * was last laid out.
 */
internal class ScreenScroll(private val list: LazyListState) : ScrollableState {
    private val reversed: Boolean get() = list.layoutInfo.reverseLayout
    private val sign: Float get() = if (reversed) -1f else 1f

    override fun dispatchRawDelta(delta: Float): Float = sign.let { s -> list.dispatchRawDelta(delta * s) * s }

    // Flings too are the reader's: the list re-anchoring as it changes order (`requestScrollToItem`) cancels any scroll
    // of default priority, and a flick off the bottom would stop dead at the switch it sets off.
    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
        list.scroll(if (scrollPriority == MutatePriority.Default) MutatePriority.UserInput else scrollPriority) {
            val inner = this
            val screen = object : ScrollScope {
                override fun scrollBy(pixels: Float): Float = sign.let { s -> inner.scrollBy(pixels * s) * s }
            }
            screen.block()
        }
    }

    override val isScrollInProgress: Boolean get() = list.isScrollInProgress
    override val canScrollForward: Boolean get() = if (reversed) list.canScrollBackward else list.canScrollForward
    override val canScrollBackward: Boolean get() = if (reversed) list.canScrollForward else list.canScrollBackward
    override val lastScrolledForward: Boolean get() = if (reversed) list.lastScrolledBackward else list.lastScrolledForward
    override val lastScrolledBackward: Boolean get() = if (reversed) list.lastScrolledForward else list.lastScrolledBackward
}

/**
 * [screen] for a transcript with a pull to catch up. The platform's scrollable hands a drag to the overscroll only
 * while its state can scroll some way, and a transcript short enough to fit the screen scrolls neither: the pull on
 * a one-turn chat would never arm. Such a list is said to scroll toward its newest row, so the drag it cannot take
 * is the overscroll's, and the pull's.
 */
private class PullableScroll(private val screen: ScreenScroll) : ScrollableState by screen {
    override val canScrollForward: Boolean get() = screen.canScrollForward || !screen.canScrollBackward
}

/**
 * The reader's scroll of the transcript's list, which is composed with `userScrollEnabled = false`: drags, flings,
 * wheels, keys and the accessibility actions, with the platform's overscroll, through [ScreenScroll] — given to the
 * list ([readerScrolling]), and to the space a short list leaves below it ([readerBackdrop]).
 *
 * With [pull], the reader's drag past the bottom edge is also a pull to catch up (see [CatchUpOverscroll]), let go
 * armed into [onCatchUp] — which accessibility services are offered as an action of their own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class ReaderScroll(
    val scroll: TranscriptScroll,
    val pull: CatchUpPull?,
    platform: OverscrollEffect,
    val canCatchUp: () -> Boolean,
    val onCatchUp: () -> Unit,
) {
    val screen = ScreenScroll(scroll.list)
    val dragged: ScrollableState = if (pull != null) PullableScroll(screen) else screen
    val overscroll: OverscrollEffect = pull?.let {
        CatchUpOverscroll(platform, it, atNewest = { !screen.canScrollForward }, enabled = canCatchUp, onPulled = onCatchUp)
    } ?: platform
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberReaderScroll(
    scroll: TranscriptScroll,
    pull: CatchUpPull? = null,
    canCatchUp: () -> Boolean = { false },
    onCatchUp: () -> Unit = {},
): ReaderScroll {
    val platform = ScrollableDefaults.overscrollEffect()
    val catchUp by rememberUpdatedState(onCatchUp)
    val allowed by rememberUpdatedState(canCatchUp)
    return remember(scroll, pull, platform) { ReaderScroll(scroll, pull, platform, canCatchUp = { allowed() }, onCatchUp = { catchUp() }) }
}

/**
 * [reader]'s drag for what lies behind the transcript's list and fills the area it sits in. A chat too short to fill
 * the area is sized to its rows, and is dragged — and pulled to catch up — from the space below its newest message
 * all the same. The list is over it everywhere else and takes the finger there itself; accessibility services are
 * told of the list alone.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.readerBackdrop(reader: ReaderScroll): Modifier = this
    .clearAndSetSemantics {}
    .scrollable(reader.dragged, Orientation.Vertical, overscrollEffect = reader.overscroll, reverseDirection = true)

/**
 * [reader] for the list itself, its stretch drawn on the rows. What accessibility services are told is the screen's
 * too: one top-to-bottom axis, its items indexed top-down. The list's own words would flip with its order, and a
 * service scrolling "forward" would turn round at every switch — back to the bottom, which follows again and flips
 * it back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.readerScrolling(reader: ReaderScroll): Modifier {
    val scroll = reader.scroll
    val screen = reader.screen
    val scope = rememberCoroutineScope()
    val axis = remember(screen) {
        ScrollAxisRange(
            value = { scroll.screenOffset(screen) },
            maxValue = { scroll.screenOffset(screen) + if (screen.canScrollForward) 100f else 0f },
        )
    }
    return this
        .semantics {
            verticalScrollAxisRange = axis
            indexForKey { key -> scroll.topDownIndex(key) }
            scrollToIndex { index ->
                val count = scroll.itemCount
                require(index in 0 until count) { "Can't scroll to index $index, it is out of bounds [0, $count)" }
                scope.launch { scroll.scrollToTopDown(index) }
                true
            }
            if (reader.pull != null) {
                customActions = listOf(CustomAccessibilityAction("Catch up") { if (reader.canCatchUp()) reader.onCatchUp(); true })
            }
        }
        .overscroll(reader.overscroll)
        .scrollable(reader.dragged, Orientation.Vertical, overscrollEffect = reader.overscroll, reverseDirection = true)
}

/** The transcript's scroll for [list], remembered per chat ([key]) with whether it was following, so a restored position is read in its own order. */
@Composable
internal fun rememberTranscriptScroll(list: LazyListState, key: Any): TranscriptScroll {
    val following = rememberSaveable(key) { mutableStateOf(true) }
    val scroll = remember(list, following) { TranscriptScroll(list, following) }
    // Following, the reader's own scroll off the bottom pins what they scrolled to, from its first pixel: a new row
    // arriving mid-gesture must not snap the list back under their finger.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.following && list.isScrollInProgress && !scroll.isJumping && list.layoutInfo.let { it.reverseLayout && TranscriptScroll.offBottom(it) } }
            .collect { away -> if (away) scroll.pin() }
    }
    // Pinned, a scroll that comes to rest at the bottom follows again.
    LaunchedEffect(scroll) {
        var scrolled = false
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                scrolled = true
            } else if (scrolled) {
                scrolled = false
                if (!scroll.following && TranscriptScroll.atBottom(list.layoutInfo)) scroll.follow()
            }
        }
    }
    return scroll
}
