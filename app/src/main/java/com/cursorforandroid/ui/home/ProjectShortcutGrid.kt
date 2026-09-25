package com.cursorforandroid.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.ChatRowMenu
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import com.cursorforandroid.ui.components.onContextClick
import com.cursorforandroid.ui.components.isContextPress

/** What the Project shortcuts' reordering says to accessibility services. */
object ProjectShortcutCopy {
    const val SHOW_ACTIONS = "Show actions"
    const val MOVE_EARLIER = "Move earlier"
    const val MOVE_LATER = "Move later"
    const val ARRANGING = "Rearranging"
}

/**
 * The New Chat page's Project shortcuts as they are being handled: the one whose long-press menu is open, whether they
 * are being arranged, and the one lifted under the finger. The page holds it, so that a tap anywhere on it ends the
 * arranging ([endsArrangingOnTap]).
 */
@Stable
internal class ProjectGridState {
    /** The shortcut whose long-press menu is open, by id. */
    var menuFor by mutableStateOf<String?>(null)
        private set

    /** Whether the shortcuts are being arranged: each shows its grip, and a drag moves one. */
    var arranging by mutableStateOf(false)
        private set

    /** The shortcut under the finger, by id. */
    var lifted by mutableStateOf<String?>(null)
        private set

    /** Where [lifted]'s top-left corner is, in the grid's coordinates. */
    internal var liftedAt by mutableStateOf(Offset.Zero)

    /** The order last arranged, ids first to last, shown until the page's own order has caught up with it. */
    internal var arrangement by mutableStateOf<List<String>?>(null)

    /** The pointer that lifted a shortcut: its release is the shortcut's drop, not a tap that ends the arranging. */
    internal var holding: PointerId? = null
        private set

    private var beforeLift: List<String>? = null

    /** Where a right-click opened [menuFor]'s menu, in window coordinates; null for a long press, beside the shortcut. */
    var menuAt by mutableStateOf<IntOffset?>(null)
        private set

    internal fun openMenu(id: String, at: IntOffset? = null) {
        menuFor = id
        menuAt = at
    }

    internal fun closeMenu() {
        menuFor = null
    }

    internal fun lift(id: String, at: Offset, pointer: PointerId) {
        menuFor = null
        arranging = true
        beforeLift = arrangement
        lifted = id
        liftedAt = at
        holding = pointer
    }

    /** Sets the lifted shortcut down where it is, the arranging going on. */
    internal fun settle() {
        lifted = null
        beforeLift = null
    }

    /** Sets the lifted shortcut down in its new place, which ends the arranging. */
    internal fun drop() {
        settle()
        arranging = false
    }

    /** Ends the arranging; a drag it cuts short leaves the order as it was before the shortcut was lifted. */
    fun stopArranging() {
        if (lifted != null) arrangement = beforeLift
        settle()
        arranging = false
    }
}

/**
 * The Project shortcuts, two to a row on a phone and three once the column is wide enough for three to keep their names,
 * in the order the reader arranged them.
 *
 * A tap opens the Project. A press held for the long-press timeout opens the menu a Project's row has in the sidebar
 * ([ChatRowMenu] with [actions]); held as long again, the menu gives way to arranging: the shortcut lifts under the
 * finger and moves where it is dragged, the others making room. Its drop hands the new order to [onReorder] and ends
 * the arranging; released without being moved, it stays lifted in spirit — each shortcut shows its grip, a drag moves
 * one — until a drop, a tap anywhere, or back. Each step is felt as well as seen.
 *
 * Without [onReorder] (the Settings miniature) the shortcuts are only shown, their order the one they came in.
 */
@Composable
internal fun ProjectShortcutGrid(
    rows: List<AgentRow>,
    modifier: Modifier = Modifier,
    onOpen: ((AgentRow) -> Unit)? = null,
    actions: AgentRowActions? = null,
    state: ProjectGridState? = null,
    onReorder: ((List<String>) -> Unit)? = null,
) {
    val grid = state ?: remember { ProjectGridState() }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.xl
    val canArrange = onReorder != null && rows.size > 1
    val shown = remember(rows, grid.arrangement) { AgentListOrganizer.arranged(rows, grid.arrangement.orEmpty()) }
    val haptics = rememberShortcutHaptics()
    val input = rememberUpdatedState(GridInput(rows, onOpen, actions, if (canArrange) onReorder else null, haptics))
    val geometry = remember { GridGeometry() }
    val motion = remember { SlotMotion() }
    val scope = rememberCoroutineScope()
    val sources = remember { HashMap<String, MutableInteractionSource>() }

    // Once the page lists the Projects in the arranged order, its order is the one shown again.
    LaunchedEffect(rows, grid.arrangement, grid.arranging) {
        if (!grid.arranging && grid.arrangement != null && shown.map { it.agent.id } == rows.map { it.agent.id }) grid.arrangement = null
    }
    if (canArrange) BackHandler(enabled = grid.arranging) { grid.stopArranging() }
    DisposableEffect(grid) {
        onDispose {
            grid.stopArranging()
            grid.closeMenu()
        }
    }

    fun move(from: Int, to: Int) {
        val order = shown.map { it.agent.id }.toMutableList().apply { add(to, removeAt(from)) }
        grid.arrangement = order
        onReorder?.invoke(order)
    }

    Layout(
        modifier = modifier.pointerInput(grid, geometry) { shortcutGestures(grid, geometry, input) { id -> sources.getOrPut(id) { MutableInteractionSource() } } },
        content = {
            if (grid.lifted != null) {
                Box(Modifier.layoutId(SlotMark).background(colors.fillFaint, shape).border(CursorDimens.hairline, colors.strokeSubtle, shape))
            }
            shown.forEachIndexed { index, row ->
                val id = row.agent.id
                key(id) {
                    val lifted = grid.lifted == id
                    val scale by animateFloatAsState(if (lifted) LIFTED_SCALE else 1f, label = "shortcut-lift")
                    val withMenu = hasMenu(row, actions)
                    Box(
                        Modifier
                            .layoutId(id)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                if (lifted) {
                                    shadowElevation = LiftedElevation.toPx()
                                    this.shape = shape
                                    ambientShadowColor = colors.shadow
                                    spotShadowColor = colors.shadow
                                }
                            }
                            .testTag(NewChatHomeTags.PROJECT_SHORTCUT)
                            .onContextClick(enabled = withMenu && !grid.arranging) { at -> grid.openMenu(id, at) }
                            .semantics(mergeDescendants = true) {
                                role = Role.Button
                                if (grid.arranging) stateDescription = ProjectShortcutCopy.ARRANGING
                                onClick {
                                    if (grid.arranging) grid.stopArranging() else onOpen?.invoke(row)
                                    true
                                }
                                if (withMenu) onLongClick(ProjectShortcutCopy.SHOW_ACTIONS) { grid.openMenu(id); true }
                                if (canArrange) {
                                    customActions = listOfNotNull(
                                        CustomAccessibilityAction(ProjectShortcutCopy.MOVE_EARLIER) { move(index, index - 1); true }.takeIf { index > 0 },
                                        CustomAccessibilityAction(ProjectShortcutCopy.MOVE_LATER) { move(index, index + 1); true }.takeIf { index < shown.lastIndex },
                                    )
                                }
                            },
                    ) {
                        ProjectShortcut(
                            row,
                            arranging = grid.arranging,
                            lifted = lifted,
                            modifier = Modifier.clip(shape).indication(sources.getOrPut(id) { MutableInteractionSource() }, ripple(color = colors.base)),
                        )
                        if (actions != null) ChatRowMenu(row = row, expanded = grid.menuFor == id, onDismiss = grid::closeMenu, actions = actions, at = grid.menuAt)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else FallbackWidth.roundToPx()
        val columns = if (width >= ThreeColumnWidth.roundToPx()) 3 else 2
        val gap = ShortcutGap.roundToPx()
        val cellWidth = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val tile = Constraints(minWidth = cellWidth, maxWidth = cellWidth)
        val ids = shown.map { it.agent.id }
        val placeables = ids.map { id -> measurables.first { it.layoutId == id }.measure(tile) }
        val cellHeight = placeables.maxOfOrNull { it.height } ?: 0
        val lines = (ids.size + columns - 1) / columns
        geometry.update(columns, cellWidth, cellHeight, gap, width, layoutDirection == LayoutDirection.Rtl)
        val mark = measurables.firstOrNull { it.layoutId == SlotMark }?.measure(Constraints.fixed(cellWidth, cellHeight))
        motion.retain(ids)
        layout(width, if (lines == 0) 0 else lines * cellHeight + (lines - 1) * gap) {
            val liftedId = grid.lifted
            if (mark != null && liftedId != null) {
                val index = ids.indexOf(liftedId)
                if (index >= 0) mark.place(geometry.origin(index))
            }
            placeables.forEachIndexed { index, placeable ->
                val id = ids[index]
                val slot = geometry.origin(index)
                if (id == liftedId) {
                    val at = grid.liftedAt.round()
                    motion.lifted(id, slot, at)
                    placeable.place(at, zIndex = 2f)
                } else {
                    val offset = motion.offset(id, slot, scope)
                    placeable.place(slot + offset, zIndex = if (offset != IntOffset.Zero) 1f else 0f)
                }
            }
        }
    }
}

/**
 * Ends [state]'s arranging on a tap anywhere under this: on the page around the shortcuts or between them. A scroll or a
 * shortcut's drag leaves it be; the lifted shortcut's release is its drop, which the grid sees to.
 */
internal fun Modifier.endsArrangingOnTap(state: ProjectGridState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!state.arranging) return@awaitEachGesture
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) {
                if (state.holding != down.id) state.stopArranging()
                return@awaitEachGesture
            }
            if (change.isConsumed) return@awaitEachGesture
        }
    }
}

/** A step of a shortcut's hold and drag, each felt as its own kind of tick. */
internal enum class ShortcutHaptic(val haptic: Haptic) { Menu(Haptic.LongPress), Lift(Haptic.DragStart), Slot(Haptic.SlotTick), Drop(Haptic.GestureEnd) }

@Composable
private fun rememberShortcutHaptics(): (ShortcutHaptic) -> Unit {
    val haptics = rememberHaptics()
    return remember(haptics) { { step -> haptics.perform(step.haptic) } }
}

/** What the grid's gestures read at the moment they act, rather than when the gesture began; never read while composing. */
private class GridInput(
    val rows: List<AgentRow>,
    val open: ((AgentRow) -> Unit)?,
    val actions: AgentRowActions?,
    val reorder: ((List<String>) -> Unit)?,
    val haptics: (ShortcutHaptic) -> Unit,
) {
    fun order(arrangement: List<String>?): List<String> = AgentListOrganizer.arranged(rows, arrangement.orEmpty()).map { it.agent.id }

    fun row(id: String): AgentRow? = rows.firstOrNull { it.agent.id == id }

    fun hasMenu(row: AgentRow): Boolean = hasMenu(row, actions)
}

/** A stand-in for a Project not loaded yet has nothing to act on, as in the sidebar. */
private fun hasMenu(row: AgentRow, actions: AgentRowActions?): Boolean = actions != null && !row.isStandIn && !row.isPlaceholder

/** The grid's cells as last laid out, for the gestures to find a shortcut and a slot under the finger. */
private class GridGeometry {
    private var columns = 2
    private var cellWidth = 0
    private var cellHeight = 0
    private var gap = 0
    private var width = 0
    private var rtl = false

    fun update(columns: Int, cellWidth: Int, cellHeight: Int, gap: Int, width: Int, rtl: Boolean) {
        this.columns = columns
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        this.gap = gap
        this.width = width
        this.rtl = rtl
    }

    /** The top-left corner of slot [index]. */
    fun origin(index: Int): IntOffset {
        val column = index % columns
        val start = column * (cellWidth + gap)
        return IntOffset(if (rtl) width - start - cellWidth else start, index / columns * (cellHeight + gap))
    }

    /** The slot of the shortcut under [position], if there is one of [count] there rather than a gap. */
    fun indexAt(position: Offset, count: Int): Int? {
        if (cellWidth <= 0 || cellHeight <= 0) return null
        val x = if (rtl) width - position.x else position.x
        if (x < 0 || position.y < 0) return null
        val column = (x / (cellWidth + gap)).toInt()
        val line = (position.y / (cellHeight + gap)).toInt()
        if (column >= columns || x - column * (cellWidth + gap) > cellWidth || position.y - line * (cellHeight + gap) > cellHeight) return null
        return (line * columns + column).takeIf { it < count }
    }

    /** The slot, of [count], nearest a lifted shortcut whose top-left corner is at [corner]. */
    fun slotFor(corner: Offset, count: Int): Int {
        val center = corner + Offset(cellWidth / 2f, cellHeight / 2f)
        val x = if (rtl) width - center.x else center.x
        val lines = (count + columns - 1) / columns
        val column = (x / (cellWidth + gap)).toInt().coerceIn(0, columns - 1)
        val line = (center.y / (cellHeight + gap)).toInt().coerceIn(0, (lines - 1).coerceAtLeast(0))
        return (line * columns + column).coerceIn(0, (count - 1).coerceAtLeast(0))
    }
}

/**
 * Where each shortcut is drawn relative to its slot: when its slot changes, or it is set down from under the finger,
 * it glides from where it was drawn into the new one.
 */
private class SlotMotion {
    private class Track(var slot: IntOffset, var liftedAt: IntOffset? = null) {
        val offset = Animatable(IntOffset.Zero, IntOffset.VectorConverter)
    }

    private val tracks = HashMap<String, Track>()

    fun offset(id: String, slot: IntOffset, scope: CoroutineScope): IntOffset {
        val track = tracks[id] ?: return IntOffset.Zero.also { tracks[id] = Track(slot) }
        val from = track.liftedAt ?: (track.slot + track.offset.value)
        if (track.liftedAt == null && track.slot == slot) return track.offset.value
        val start = from - slot
        track.slot = slot
        track.liftedAt = null
        // Snapped to its start in this very pass, so a second pass before the next frame does not draw it in its slot.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { glide(track.offset, start) }
        return start
    }

    fun lifted(id: String, slot: IntOffset, at: IntOffset) {
        val track = tracks.getOrPut(id) { Track(slot) }
        track.slot = slot
        track.liftedAt = at
    }

    fun retain(ids: List<String>) {
        if (tracks.size > ids.size) tracks.keys.retainAll(ids.toSet())
    }

    private suspend fun glide(offset: Animatable<IntOffset, AnimationVector2D>, start: IntOffset) {
        offset.snapTo(start)
        offset.animateTo(IntOffset.Zero, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1)))
    }
}

/** How a pointer held on a shortcut ended its wait: lifted, moved away, taken by something else (a scroll), or held throughout. */
private enum class Hold { Released, Moved, Taken, Held }

/**
 * Follows [down]'s pointer for [millis]. With [claim], the grid keeps every change from what holds it (the list's scroll,
 * the drawer), the one that moves past [slop] included.
 */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange, slop: Float, millis: Long, claim: Boolean): Hold =
    withTimeoutOrNull(millis) {
        var outcome: Hold? = null
        while (outcome == null) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            outcome = when {
                change == null -> Hold.Taken
                !change.pressed -> Hold.Released.also { change.consume() }
                change.isConsumed -> Hold.Taken
                else -> {
                    if (claim) change.consume()
                    if ((change.position - down.position).getDistance() > slop) Hold.Moved else null
                }
            }
        }
        outcome
    } ?: Hold.Held

/** Keeps [pointer]'s changes from everything else until it lifts. */
private suspend fun AwaitPointerEventScope.consumeUntilUp(pointer: PointerId) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

private suspend fun PointerInputScope.shortcutGestures(
    grid: ProjectGridState,
    geometry: GridGeometry,
    input: State<GridInput>,
    source: (String) -> MutableInteractionSource,
) {
    val slop = viewConfiguration.touchSlop
    val hold = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // A right-click is the shortcut's own (its onContextClick): not a tap, a hold or a drag of the grid's.
        if (isContextPress(currentEvent)) return@awaitEachGesture
        val order = input.value.order(grid.arrangement)
        val index = geometry.indexAt(down.position, order.size) ?: return@awaitEachGesture
        val id = order[index]
        val row = input.value.row(id) ?: return@awaitEachGesture
        val corner = geometry.origin(index).toOffset()

        if (grid.arranging) {
            // Arranging: a drag moves the shortcut at once, a hold lifts it to be moved, and a tap ends the arranging.
            when (awaitHold(down, slop, hold, claim = true)) {
                Hold.Released -> grid.stopArranging()
                Hold.Taken -> Unit
                Hold.Moved, Hold.Held -> {
                    input.value.haptics(ShortcutHaptic.Lift)
                    grid.lift(id, corner, down.id)
                    carry(down, corner, id, grid, geometry, input, slop)
                }
            }
            return@awaitEachGesture
        }

        val press = PressInteraction.Press(down.position - corner)
        source(id).tryEmit(press)
        when (awaitHold(down, slop, hold, claim = false)) {
            Hold.Released -> {
                source(id).tryEmit(PressInteraction.Release(press))
                input.value.open?.invoke(row)
            }
            Hold.Moved, Hold.Taken -> source(id).tryEmit(PressInteraction.Cancel(press))
            Hold.Held -> {
                val menu = input.value.hasMenu(row)
                if (menu) {
                    input.value.haptics(ShortcutHaptic.Menu)
                    grid.openMenu(id)
                }
                // The finger is the menu's now: the list does not scroll under it, nor the drawer open.
                when (val next = awaitHold(down, slop, hold, claim = true)) {
                    Hold.Released -> {
                        source(id).tryEmit(PressInteraction.Release(press))
                        if (!menu && input.value.reorder == null) input.value.open?.invoke(row)
                    }
                    Hold.Moved, Hold.Taken -> {
                        source(id).tryEmit(PressInteraction.Cancel(press))
                        if (next == Hold.Moved) consumeUntilUp(down.id)
                    }
                    Hold.Held -> {
                        source(id).tryEmit(PressInteraction.Cancel(press))
                        if (input.value.reorder == null) {
                            consumeUntilUp(down.id)
                        } else {
                            input.value.haptics(ShortcutHaptic.Lift)
                            grid.lift(id, corner, down.id)
                            carry(down, corner, id, grid, geometry, input, slop)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Carries the lifted shortcut [id] under [down]'s pointer, the others making room as it passes their slots, until the
 * pointer lifts: moved, it is dropped there and the new order goes out; not, it is set down and the arranging goes on.
 */
private suspend fun AwaitPointerEventScope.carry(
    down: PointerInputChange,
    corner: Offset,
    id: String,
    grid: ProjectGridState,
    geometry: GridGeometry,
    input: State<GridInput>,
    slop: Float,
) {
    val grab = down.position - corner
    val before = input.value.order(grid.arrangement)
    var moved = false
    var ended = false
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            if (grid.lifted != id) continue
            grid.liftedAt = change.position - grab
            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
            val order = input.value.order(grid.arrangement)
            val from = order.indexOf(id)
            val to = geometry.slotFor(grid.liftedAt, order.size)
            if (from >= 0 && to != from) {
                grid.arrangement = order.toMutableList().apply { add(to, removeAt(from)) }
                input.value.haptics(ShortcutHaptic.Slot)
            }
        }
        ended = true
    } finally {
        if (grid.lifted == id) {
            if (ended && moved) {
                val order = input.value.order(grid.arrangement)
                grid.drop()
                input.value.haptics(ShortcutHaptic.Drop)
                if (order != before) input.value.reorder?.invoke(order)
            } else {
                grid.settle()
            }
        }
    }
}

private const val SlotMark = "project-shortcut-slot"
private const val LIFTED_SCALE = 1.04f
private val LiftedElevation = 12.dp
private val ShortcutGap = 10.dp
private val ThreeColumnWidth = 520.dp
private val FallbackWidth = 360.dp
