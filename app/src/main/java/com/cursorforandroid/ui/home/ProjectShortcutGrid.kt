package com.cursorforandroid.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toOffset
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ProjectArrangement
import com.cursorforandroid.domain.ProjectSections
import com.cursorforandroid.domain.ProjectSections.Place
import com.cursorforandroid.domain.ProjectSections.Section
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.ChatRowMenu
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.isContextPress
import com.cursorforandroid.ui.components.onContextClick
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the Project shortcuts' arranging says, on screen and to accessibility services. */
object ProjectShortcutCopy {
    const val SHOW_ACTIONS = "Show actions"
    const val MOVE_EARLIER = "Move earlier"
    const val MOVE_LATER = "Move later"
    const val ARRANGING = "Rearranging"

    /** The line the hidden shortcuts sit under while they are arranged, and what each of them is then. */
    const val HIDDEN = "Hidden"
    const val HIDE = "Hide from New Chat"
    const val SHOW = "Show on New Chat"

    /** Where the shortcuts were, once every one is hidden: the way back to them. */
    fun allHidden(count: Int): String = "$count hidden"
    const val SHOW_HIDDEN = "Show hidden Projects"
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

    /** Whether the shortcuts are being arranged: each shows its grip, the hidden ones are out under their line, and a drag moves one. */
    var arranging by mutableStateOf(false)
        private set

    /** The shortcut under the finger, by id. */
    var lifted by mutableStateOf<String?>(null)
        private set

    /** Where [lifted]'s top-left corner is, in the grid's coordinates. */
    internal var liftedAt by mutableStateOf(Offset.Zero)

    /** The sections last arranged, shown until the page's own have caught up with them. */
    internal var arrangement by mutableStateOf<ProjectSections?>(null)

    /** The pointer that lifted a shortcut: its release is the shortcut's drop, not a tap that ends the arranging. */
    internal var holding: PointerId? = null
        private set

    private var beforeLift: ProjectSections? = null

    /** The page the shortcuts are on, which a shortcut carried into its top or bottom edge scrolls; null, nothing scrolls. */
    internal var page: ScrollableState? = null

    /** The part of the window [page] shows, in window coordinates. */
    internal var pageBounds: Rect? = null

    internal var coordinates: LayoutCoordinates? = null

    /** How deep into [page]'s top (below 0) or bottom (above 0) edge the finger carrying a shortcut is, from -1 to 1. */
    internal var edge by mutableFloatStateOf(0f)
        private set

    /** Where on the page the finger carrying a shortcut is: [position] in the grid, an edge [zone] pixels deep. */
    internal fun reach(position: Offset, zone: Float) {
        val bounds = pageBounds
        val at = coordinates?.takeIf { it.isAttached }?.localToWindow(position)
        edge = if (bounds == null || at == null || zone <= 0f || page == null) {
            0f
        } else {
            when {
                at.y < bounds.top + zone -> (at.y - bounds.top - zone) / zone
                at.y > bounds.bottom - zone -> (at.y - bounds.bottom + zone) / zone
                else -> 0f
            }.coerceIn(-1f, 1f)
        }
    }

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

    /** Arranges the shortcuts without lifting one: the way back to Projects that are all hidden. */
    internal fun startArranging() {
        menuFor = null
        arranging = true
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
        edge = 0f
    }

    /** Ends the arranging; a drag it cuts short leaves the shortcuts as they were before that one was lifted. */
    fun stopArranging() {
        if (lifted != null) arrangement = beforeLift
        settle()
        arranging = false
    }
}

/**
 * The Project shortcuts, two to a row on a phone and three once the column is wide enough for three to keep their names,
 * in the order the reader arranged them; the ones in [hidden] are not on the page at all.
 *
 * A tap opens the Project. A press held for the long-press timeout opens the menu a Project's row has in the sidebar
 * ([ChatRowMenu] with [actions]); held as long again, the menu gives way to arranging: the shortcut lifts under the
 * finger and moves where it is dragged, the others making room, and a "Hidden" line opens under them with the hidden
 * shortcuts beneath it. Dragged below the line a shortcut is hidden, above it shown again. Each drop hands the
 * arrangement to [onArrange], and the arranging goes on — each shortcut showing its grip, a drag moving one — until a
 * tap anywhere or back, when the line and what is under it fold away. Each step is felt as well as seen, crossing the
 * line most of all.
 *
 * Without [onArrange] (the Settings miniature) the shortcuts are only shown, their order the one they came in.
 */
@Composable
internal fun ProjectShortcutGrid(
    rows: List<AgentRow>,
    modifier: Modifier = Modifier,
    hidden: Set<String> = emptySet(),
    onOpen: ((AgentRow) -> Unit)? = null,
    actions: AgentRowActions? = null,
    state: ProjectGridState? = null,
    onArrange: ((ProjectArrangement) -> Unit)? = null,
) {
    val grid = state ?: remember { ProjectGridState() }
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.xl
    val canArrange = onArrange != null && rows.isNotEmpty()
    val ids = remember(rows) { rows.map { it.agent.id } }
    val byId = remember(rows) { rows.associateBy { it.agent.id } }
    val sections = remember(ids, hidden, grid.arrangement) { ProjectSections.of(ids, hidden, grid.arrangement) }
    val haptics = rememberShortcutHaptics()
    val input = rememberUpdatedState(GridInput(rows, ids, hidden, onOpen, actions, if (canArrange) onArrange else null, haptics))
    val geometry = remember { GridGeometry() }
    val motion = remember { SlotMotion() }
    val height = remember { HeightMotion() }
    val scope = rememberCoroutineScope()
    val sources = remember { HashMap<String, MutableInteractionSource>() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // How far the "Hidden" line and what is under it are out: 1 while arranging, folding to 0 after.
    val reveal = remember { Animatable(if (grid.arranging) 1f else 0f) }
    LaunchedEffect(grid.arranging) { reveal.animateTo(if (grid.arranging) 1f else 0f, RevealSpring) }
    val hiddenOut by remember { derivedStateOf { grid.arranging || reveal.value > 0f } }

    // Once the page's own sections are the arranged ones, its are the ones shown again.
    LaunchedEffect(ids, hidden, grid.arrangement, grid.lifted) {
        if (grid.lifted == null && grid.arrangement != null && ProjectSections.of(ids, hidden) == sections) grid.arrangement = null
    }
    if (canArrange) BackHandler(enabled = grid.arranging) { grid.stopArranging() }
    DisposableEffect(grid) {
        onDispose {
            grid.stopArranging()
            grid.closeMenu()
        }
    }

    fun arrange(next: ProjectSections) {
        grid.arrangement = next
        onArrange?.invoke(next.arrangement(ids))
    }

    val density = LocalDensity.current
    LaunchedEffect(grid, geometry, density) { scrollAtEdges(grid, geometry, input, density) }

    val liftedSection = grid.lifted?.let { sections.placeOf(it)?.section }
    val composed = if (hiddenOut) sections.shown + sections.hidden else sections.shown
    Layout(
        modifier = modifier.onGloballyPositioned { grid.coordinates = it }.pointerInput(grid, geometry) { shortcutGestures(grid, geometry, input) { id -> sources.getOrPut(id) { MutableInteractionSource() } } },
        content = {
            if (grid.lifted != null) {
                Box(Modifier.layoutId(SlotMark).background(colors.fillFaint, shape).border(CursorDimens.hairline, colors.strokeSubtle, shape))
            }
            if (sections.shown.isEmpty() && sections.hidden.isNotEmpty()) {
                AllHiddenTile(
                    count = sections.hidden.size,
                    arranging = grid.arranging,
                    shape = shape,
                    onShow = if (canArrange) grid::startArranging else null,
                    modifier = Modifier.layoutId(ShownEmpty),
                )
            }
            if (hiddenOut) {
                HiddenLine(
                    active = liftedSection == Section.Hidden,
                    reveal = { reveal.value },
                    rtl = rtl,
                    modifier = Modifier.layoutId(Line).graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * -LineDrop.toPx()
                    },
                )
                if (sections.hidden.isEmpty()) DropTarget(shape, reveal = { reveal.value }, modifier = Modifier.layoutId(HiddenEmpty))
            }
            composed.forEach { id ->
                val row = byId[id]
                val place = sections.placeOf(id)
                if (row != null && place != null) key(id) {
                    val inHidden = place.section == Section.Hidden
                    val lifted = grid.lifted == id
                    val scale by animateFloatAsState(if (lifted) LIFTED_SCALE else 1f, label = "shortcut-lift")
                    val dim by animateFloatAsState(
                        if (inHidden && !lifted) HIDDEN_ALPHA else 1f,
                        DimSpring,
                        label = "shortcut-dim",
                    )
                    val withMenu = hasMenu(row, actions)
                    // A hidden shortcut is on the page only while it is arranged; folding away, it is already gone.
                    val listed = !inHidden || grid.arranging
                    Box(
                        Modifier
                            .layoutId(id)
                            .graphicsLayer {
                                val out = if (inHidden && !lifted) reveal.value else 1f
                                alpha = dim * out
                                val resting = scale * (HIDDEN_REST_SCALE + (1f - HIDDEN_REST_SCALE) * out)
                                scaleX = resting
                                scaleY = resting
                                if (lifted) {
                                    shadowElevation = LiftedElevation.toPx()
                                    this.shape = shape
                                    ambientShadowColor = colors.shadow
                                    spotShadowColor = colors.shadow
                                }
                            }
                            .then(
                                if (!listed) {
                                    Modifier.clearAndSetSemantics {}
                                } else {
                                    Modifier
                                        .testTag(NewChatHomeTags.PROJECT_SHORTCUT)
                                        .onContextClick(enabled = withMenu && !grid.arranging) { at -> grid.openMenu(id, at) }
                                        .semantics(mergeDescendants = true) {
                                            role = Role.Button
                                            if (grid.arranging) stateDescription = if (inHidden) ProjectShortcutCopy.HIDDEN else ProjectShortcutCopy.ARRANGING
                                            onClick {
                                                if (grid.arranging) grid.stopArranging() else onOpen?.invoke(row)
                                                true
                                            }
                                            if (withMenu) onLongClick(ProjectShortcutCopy.SHOW_ACTIONS) { grid.openMenu(id); true }
                                            if (canArrange) {
                                                val section = sections[place.section]
                                                customActions = listOfNotNull(
                                                    CustomAccessibilityAction(ProjectShortcutCopy.MOVE_EARLIER) {
                                                        arrange(sections.moved(id, place.copy(index = place.index - 1))); true
                                                    }.takeIf { place.index > 0 },
                                                    CustomAccessibilityAction(ProjectShortcutCopy.MOVE_LATER) {
                                                        arrange(sections.moved(id, place.copy(index = place.index + 1))); true
                                                    }.takeIf { place.index < section.lastIndex },
                                                    CustomAccessibilityAction(if (inHidden) ProjectShortcutCopy.SHOW else ProjectShortcutCopy.HIDE) {
                                                        arrange(sections.toggled(id)); true
                                                    },
                                                )
                                            }
                                        }
                                },
                            ),
                    ) {
                        ProjectShortcut(
                            row,
                            arranging = grid.arranging,
                            lifted = lifted,
                            modifier = Modifier.clip(shape).indication(sources.getOrPut(id) { MutableInteractionSource() }, ripple(color = colors.base)),
                        )
                        if (actions != null && grid.menuFor == id) ChatRowMenu(row = row, expanded = true, onDismiss = grid::closeMenu, actions = actions, at = grid.menuAt)
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
        val byLayoutId = measurables.associateBy { it.layoutId }
        val placeables = HashMap<String, Placeable>(composed.size)
        composed.forEach { id -> byLayoutId[id]?.let { placeables[id] = it.measure(tile) } }
        val cellHeight = placeables.values.maxOfOrNull { it.height } ?: 0
        val arranging = grid.arranging
        val wide = Constraints(minWidth = width, maxWidth = width)
        // Arranged, an empty section is a shortcut's room to drop into; otherwise the empty top is a single quiet row.
        fun emptyRoom(id: String, asCell: Boolean) =
            byLayoutId[id]?.measure(if (asCell && cellHeight > 0) Constraints.fixed(width, cellHeight) else wide)
        val shownEmpty = emptyRoom(ShownEmpty, asCell = arranging)
        val line = byLayoutId[Line]?.measure(wide)
        val hiddenEmpty = emptyRoom(HiddenEmpty, asCell = true)
        val mark = byLayoutId[SlotMark]?.measure(Constraints.fixed(cellWidth, cellHeight))

        fun sectionHeight(count: Int, empty: Placeable?): Int {
            if (count == 0) return empty?.height ?: 0
            val lines = (count + columns - 1) / columns
            return lines * cellHeight + (lines - 1) * gap
        }
        val hiddenCount = if (hiddenOut) sections.hidden.size else 0
        val lineTop = sectionHeight(sections.shown.size, shownEmpty)
        val hiddenTop = lineTop + (line?.height ?: 0)
        val full = hiddenTop + sectionHeight(hiddenCount, hiddenEmpty)
        geometry.update(columns, cellWidth, cellHeight, gap, width, layoutDirection == LayoutDirection.Rtl, lineTop, line?.height ?: 0, hiddenTop)
        motion.retain(composed + FollowedRooms)
        val laidOut = height.follow(if (arranging && line != null) full else lineTop, scope)
        layout(width, laidOut) {
            val liftedId = grid.lifted
            fun glide(id: String, placeable: Placeable, slot: IntOffset) {
                val offset = motion.offset(id, slot, scope)
                placeable.place(slot + offset, zIndex = if (offset != IntOffset.Zero) 1f else 0f)
            }
            if (mark != null && liftedId != null) sections.placeOf(liftedId)?.let { mark.place(geometry.origin(it)) }
            shownEmpty?.let { glide(ShownEmpty, it, IntOffset.Zero) }
            line?.let { glide(Line, it, IntOffset(0, lineTop)) }
            hiddenEmpty?.let { glide(HiddenEmpty, it, IntOffset(0, hiddenTop)) }
            composed.forEach { id ->
                val placeable = placeables[id] ?: return@forEach
                val place = sections.placeOf(id) ?: return@forEach
                val slot = geometry.origin(place)
                if (id == liftedId) {
                    val at = grid.liftedAt.round()
                    motion.lifted(id, slot, at)
                    placeable.place(at, zIndex = 2f)
                } else {
                    glide(id, placeable, slot)
                }
            }
        }
    }
}

/**
 * The "Hidden" line, drawn out from its label as it opens, and stronger while a lifted shortcut is under it ([active]).
 */
@Composable
private fun HiddenLine(active: Boolean, reveal: () -> Float, rtl: Boolean, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val label by animateColorAsState(if (active) colors.textSecondary else colors.textTertiary, ColorSpring, label = "hidden-line-label")
    val stroke by animateColorAsState(if (active) colors.strokeStrong else colors.strokeSubtle, ColorSpring, label = "hidden-line")
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp).testTag(NewChatHomeTags.HIDDEN_LINE).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ProjectShortcutCopy.HIDDEN, style = CursorTheme.typography.small, color = label, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(CursorDimens.hairline)
                .graphicsLayer {
                    scaleX = reveal()
                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0.5f)
                }
                .background(stroke),
        )
    }
}

/** An empty hidden section while arranging: a dashed room a shortcut can be dropped into, which fades in as it empties. */
@Composable
private fun DropTarget(shape: Shape, reveal: () -> Float, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, RevealSpring) }
    Box(
        modifier
            .graphicsLayer { alpha = reveal() * appear.value }
            .dashedOutline(shape, CursorTheme.colors.stroke)
            .testTag(NewChatHomeTags.HIDDEN_DROP_TARGET)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(CursorIcons.EyeOff, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
}

/**
 * Where the shortcuts were, once every one is hidden. On the page, a quiet row saying how many are hidden, whose tap
 * arranges them ([onShow]) so they can be dragged back; while arranging, the room above the line to drop one into.
 */
@Composable
private fun AllHiddenTile(count: Int, arranging: Boolean, shape: Shape, onShow: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val label = ProjectShortcutCopy.allHidden(count)
    Box(
        modifier
            .clip(shape)
            .dashedOutline(shape, colors.stroke)
            .then(
                if (onShow != null && !arranging) {
                    Modifier.clickable(onClickLabel = ProjectShortcutCopy.SHOW_HIDDEN, role = Role.Button, onClick = onShow)
                } else {
                    Modifier
                },
            )
            .testTag(NewChatHomeTags.ALL_HIDDEN)
            .semantics { if (!arranging) contentDescription = label else stateDescription = ProjectShortcutCopy.ARRANGING },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(if (arranging) CursorIcons.Eye else CursorIcons.EyeOff, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
            if (!arranging) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = CursorTheme.typography.small, color = colors.textTertiary, maxLines = 1)
            }
        }
    }
}

/** [shape]'s edge in [color], dashed: a room something can go into rather than a thing. */
private fun Modifier.dashedOutline(shape: Shape, color: Color): Modifier = drawBehind {
    val width = CursorDimens.hairline.toPx().coerceAtLeast(1f)
    drawOutline(
        shape.createOutline(size, layoutDirection, this),
        color = color,
        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
    )
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

/** A step of a shortcut's hold and drag, each felt as its own kind of tick; crossing the "Hidden" line the firmest. */
internal enum class ShortcutHaptic(val haptic: Haptic) {
    Menu(Haptic.LongPress),
    Lift(Haptic.DragStart),
    Slot(Haptic.SlotTick),
    Hide(Haptic.ThresholdActivate),
    Show(Haptic.ThresholdDeactivate),
    Drop(Haptic.GestureEnd),
}

@Composable
private fun rememberShortcutHaptics(): (ShortcutHaptic) -> Unit {
    val haptics = rememberHaptics()
    return remember(haptics) { { step -> haptics.perform(step.haptic) } }
}

/** What the grid's gestures read at the moment they act, rather than when the gesture began; never read while composing. */
private class GridInput(
    val rows: List<AgentRow>,
    val ids: List<String>,
    val hidden: Set<String>,
    val open: ((AgentRow) -> Unit)?,
    val actions: AgentRowActions?,
    val arrange: ((ProjectArrangement) -> Unit)?,
    val haptics: (ShortcutHaptic) -> Unit,
) {
    fun sections(arrangement: ProjectSections?): ProjectSections = ProjectSections.of(ids, hidden, arrangement)

    fun row(id: String): AgentRow? = rows.firstOrNull { it.agent.id == id }

    fun hasMenu(row: AgentRow): Boolean = hasMenu(row, actions)
}

/** A stand-in for a Project not loaded yet has nothing to act on, as in the sidebar. */
private fun hasMenu(row: AgentRow, actions: AgentRowActions?): Boolean = actions != null && !row.isStandIn && !row.isPlaceholder

/** The grid's cells and its "Hidden" line as last laid out, for the gestures to find a shortcut and a slot under the finger. */
private class GridGeometry {
    private var columns = 2
    private var cellWidth = 0
    private var cellHeight = 0
    private var gap = 0
    private var width = 0
    private var rtl = false
    private var lineTop = 0
    private var lineHeight = 0
    private var hiddenTop = 0

    fun update(columns: Int, cellWidth: Int, cellHeight: Int, gap: Int, width: Int, rtl: Boolean, lineTop: Int, lineHeight: Int, hiddenTop: Int) {
        this.columns = columns
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        this.gap = gap
        this.width = width
        this.rtl = rtl
        this.lineTop = lineTop
        this.lineHeight = lineHeight
        this.hiddenTop = hiddenTop
    }

    private fun top(section: Section): Int = if (section == Section.Shown) 0 else hiddenTop

    /** The top-left corner of [place]'s slot. */
    fun origin(place: Place): IntOffset {
        val column = place.index % columns
        val start = column * (cellWidth + gap)
        return IntOffset(if (rtl) width - start - cellWidth else start, top(place.section) + place.index / columns * (cellHeight + gap))
    }

    /** The shortcut of [sections] under [position], if there is one there rather than a gap; a hidden one only while [arranging]. */
    fun placeAt(position: Offset, sections: ProjectSections, arranging: Boolean): Place? {
        if (cellWidth <= 0 || cellHeight <= 0) return null
        val section = if (arranging && lineHeight > 0 && position.y >= hiddenTop) Section.Hidden else Section.Shown
        val x = if (rtl) width - position.x else position.x
        val y = position.y - top(section)
        if (x < 0 || y < 0) return null
        val column = (x / (cellWidth + gap)).toInt()
        val line = (y / (cellHeight + gap)).toInt()
        if (column >= columns || x - column * (cellWidth + gap) > cellWidth || y - line * (cellHeight + gap) > cellHeight) return null
        return (line * columns + column).takeIf { it < sections[section].size }?.let { Place(section, it) }
    }

    /**
     * The slot nearest a lifted shortcut, now at [from], whose top-left corner is at [corner]: in the hidden section once
     * its middle is past the "Hidden" line, else among the shown ones. Crossing moves the line away from the shortcut —
     * the section it left loses a slot, the one it entered gains one — so it does not cross back until dragged back.
     */
    fun slotFor(corner: Offset, sections: ProjectSections, from: Place): Place {
        val center = corner + Offset(cellWidth / 2f, cellHeight / 2f)
        val section = if (lineHeight > 0 && center.y > lineTop + lineHeight / 2f) Section.Hidden else Section.Shown
        val count = sections[section].size + if (section == from.section) 0 else 1
        val x = if (rtl) width - center.x else center.x
        val lines = (count + columns - 1) / columns
        val column = (x / (cellWidth + gap)).toInt().coerceIn(0, columns - 1)
        val line = ((center.y - top(section)) / (cellHeight + gap)).toInt().coerceIn(0, (lines - 1).coerceAtLeast(0))
        return Place(section, (line * columns + column).coerceIn(0, (count - 1).coerceAtLeast(0)))
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
        val sections = input.value.sections(grid.arrangement)
        val place = geometry.placeAt(down.position, sections, grid.arranging) ?: return@awaitEachGesture
        val id = sections[place.section][place.index]
        val row = input.value.row(id) ?: return@awaitEachGesture
        val corner = geometry.origin(place).toOffset()

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
                        if (!menu && input.value.arrange == null) input.value.open?.invoke(row)
                    }
                    Hold.Moved, Hold.Taken -> {
                        source(id).tryEmit(PressInteraction.Cancel(press))
                        if (next == Hold.Moved) consumeUntilUp(down.id)
                    }
                    Hold.Held -> {
                        source(id).tryEmit(PressInteraction.Cancel(press))
                        if (input.value.arrange == null) {
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
 * Carries the lifted shortcut [id] under [down]'s pointer, the others making room as it passes their slots and the
 * "Hidden" line moving out of its way as it crosses it, until the pointer lifts: moved, it is dropped there and the new
 * arrangement goes out; either way it is set down and the arranging goes on.
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
    val before = input.value.sections(grid.arrangement)
    var moved = false
    var ended = false
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            if (grid.lifted != id) continue
            grid.liftedAt = change.position - grab
            grid.reach(change.position, EdgeZone.toPx())
            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
            reslot(id, grid, geometry, input.value)
        }
        ended = true
    } finally {
        if (grid.lifted == id) {
            val after = input.value.sections(grid.arrangement)
            grid.settle()
            if (ended && moved) {
                input.value.haptics(ShortcutHaptic.Drop)
                if (after != before) input.value.arrange?.invoke(after.arrangement(input.value.ids))
            }
        }
    }
}

/** Moves the lifted shortcut [id] into the slot nearest where it is now, the haptic saying whether it crossed the line. */
private fun reslot(id: String, grid: ProjectGridState, geometry: GridGeometry, input: GridInput) {
    val sections = input.sections(grid.arrangement)
    val from = sections.placeOf(id) ?: return
    val to = geometry.slotFor(grid.liftedAt, sections, from)
    if (to == from) return
    grid.arrangement = sections.moved(id, to)
    input.haptics(
        when {
            to.section == from.section -> ShortcutHaptic.Slot
            to.section == Section.Hidden -> ShortcutHaptic.Hide
            else -> ShortcutHaptic.Show
        },
    )
}

/**
 * Scrolls [grid]'s page while the finger carrying a shortcut is in its top or bottom edge, faster the deeper in, so a
 * long list's far end — the "Hidden" line below the fold — can be reached; the shortcut stays under the finger.
 */
private suspend fun scrollAtEdges(grid: ProjectGridState, geometry: GridGeometry, input: State<GridInput>, density: Density) {
    val speed = with(density) { EdgeScrollSpeed.toPx() }
    snapshotFlow { grid.edge != 0f }.collectLatest { atEdge ->
        val page = grid.page
        if (!atEdge || page == null) return@collectLatest
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val id = grid.lifted ?: break
            val depth = grid.edge
            val scrolled = page.scrollBy(depth * abs(depth) * speed * (now - last) / 1_000_000_000f)
            last = now
            if (scrolled != 0f) {
                grid.liftedAt += Offset(0f, scrolled)
                reslot(id, grid, geometry, input.value)
            }
        }
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

/**
 * The grid's height as the page sees it: first laid out at its height, after that springing to each new one — the
 * "Hidden" section opening and folding, a shortcut taking a new row in it — so what is under the grid never jumps.
 */
private class HeightMotion {
    private var height: Animatable<Float, AnimationVector1D>? = null
    private var target = 0

    fun follow(target: Int, scope: CoroutineScope): Int {
        val height = height ?: return target.also {
            this.height = Animatable(target.toFloat())
            this.target = target
        }
        if (target != this.target) {
            this.target = target
            scope.launch(start = CoroutineStart.UNDISPATCHED) { height.animateTo(target.toFloat(), HeightSpring) }
        }
        return height.value.roundToInt()
    }
}

private const val SlotMark = "project-shortcut-slot"
private const val ShownEmpty = "project-shortcut-shown-empty"
private const val Line = "project-shortcut-hidden-line"
private const val HiddenEmpty = "project-shortcut-hidden-empty"
private val FollowedRooms = listOf(ShownEmpty, Line, HiddenEmpty)
private const val LIFTED_SCALE = 1.04f

/** A hidden shortcut while arranged, dimmed; under the finger it is whole, so nothing it passes over shows through it. */
private const val HIDDEN_ALPHA = 0.5f

/** How far down a hidden shortcut is scaled as its section folds away. */
private const val HIDDEN_REST_SCALE = 0.94f
private val LiftedElevation = 12.dp

/** How deep the page's top and bottom edges are that scroll it under a carried shortcut, and how far a second at the deepest. */
private val EdgeZone = 72.dp
private val EdgeScrollSpeed = 1400.dp
private val ShortcutGap = 10.dp
private val ThreeColumnWidth = 520.dp
private val FallbackWidth = 360.dp

/** How far above its place the "Hidden" line starts as it opens. */
private val LineDrop = 10.dp

private val RevealSpring = spring<Float>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
private val HeightSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)
private val DimSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val ColorSpring = spring<Color>(stiffness = Spring.StiffnessMedium)
