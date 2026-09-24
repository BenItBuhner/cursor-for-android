package com.cursorforandroid.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.ui.components.PaneResizeEdge
import com.cursorforandroid.ui.components.PaneResizeEdgeWidth
import com.cursorforandroid.ui.components.PaneSide
import com.cursorforandroid.ui.components.backGestureEdges
import com.cursorforandroid.ui.components.rememberBackGestureEdges
import com.cursorforandroid.ui.panel.LocalPinnedPanel
import com.cursorforandroid.ui.panel.PaneWidthClass
import com.cursorforandroid.ui.panel.PaneWidths
import com.cursorforandroid.ui.panel.PinnedPanel
import com.cursorforandroid.ui.panel.PinnedPanelDefaultWidth
import com.cursorforandroid.ui.panel.PinnedPanelMinWidth
import com.cursorforandroid.ui.panel.RailMaxWidth
import com.cursorforandroid.ui.panel.RailMinWidth
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The wide window's width as the sidebar rail, the chat and a conversation panel pinned beside it share it
 * ([PaneWidths]), with what the reader has made of it: the widths the rail and the panel were dragged to, and the panel
 * open or shut for each window size class, all kept for the device ([PreferencesStore]) and read once as the shell
 * starts. It is every chat's [PinnedPanel], so the panel left open beside one chat stands open beside the next.
 */
@Stable
internal class ShellPanes(
    private val prefs: PreferencesStore,
    private val scope: CoroutineScope,
    private val railExpanded: () -> Boolean,
    private val chatOnTop: () -> Boolean,
) : PinnedPanel {
    private var configured by mutableStateOf(0.dp)
    private var measured by mutableStateOf<Dp?>(null)
    private var railWant by mutableStateOf<Dp?>(null)
    private var panelWant by mutableStateOf<Dp?>(null)
    private var openMedium by mutableStateOf<Boolean?>(null)
    private var openExpanded by mutableStateOf<Boolean?>(null)

    /** The shell's width: as last measured, and the configuration's from a change until the shell is measured again. */
    val window: Dp get() = measured ?: configured

    val widths: PaneWidths by derivedStateOf {
        PaneWidths.of(
            window = window,
            railExpanded = railExpanded(),
            railWidth = railWant ?: CursorDimens.sidebarWidth,
            panelOpen = chatOnTop() && open == true,
            panelWidth = panelWant ?: PinnedPanelDefaultWidth,
        )
    }

    /** The rail over the chat, where the window has no room for it beside the chat: as wide as it was dragged to. */
    val flyoutWidth: Dp get() = (railWant ?: CursorDimens.sidebarWidth).coerceIn(RailMinWidth, RailMaxWidth)

    /** A plain field, not state: [railColumn] writes it at layout, where a state write would invalidate that layout. */
    private var railStood: Dp? = null

    /**
     * The rail column's width, read at layout: as [widths] has it while the rail stands beside the chat, and the width it
     * last stood at while it is away. The rail's room goes in the same write that sends it away (a panel pinned beside the
     * chat leaves it none), and it slides off as it stood rather than narrowed to that room first. Read from the same
     * snapshot as its room, it holds whether the frame lays the rail out before or after the rail is told to go.
     */
    fun railColumn(): Dp {
        val widths = widths
        val stood = railStood
        if (!widths.railShown && stood != null) return stood
        railStood = widths.rail
        return widths.rail
    }

    override val open: Boolean?
        get() = when (PaneWidthClass.of(window)) {
            PaneWidthClass.Medium -> openMedium
            PaneWidthClass.Expanded -> openExpanded
        }

    override val width: Dp get() = widths.panel

    /** The configuration's screen width, read in composition: a change drops the old measurement until the next. */
    fun configure(width: Dp) {
        if (width == configured) return
        configured = width
        measured = null
    }

    fun measure(width: Dp) {
        measured = width
    }

    override fun setOpen(open: Boolean) {
        val widthClass = PaneWidthClass.of(window)
        if (this.open == open) return
        when (widthClass) {
            PaneWidthClass.Medium -> openMedium = open
            PaneWidthClass.Expanded -> openExpanded = open
        }
        scope.launch { prefs.setPanelOpen(widthClass, open) }
    }

    override fun resize(width: Dp) {
        panelWant = width.coerceIn(PinnedPanelMinWidth, widths.panelMax)
    }

    override fun resizeDone() {
        val width = widths.panel
        scope.launch { prefs.setPanelWidthDp(width.value.roundToInt()) }
    }

    fun resizeRail(width: Dp) {
        railWant = width.coerceIn(RailMinWidth, maxOf(widths.railMax, RailMinWidth))
    }

    fun resizeRailDone() {
        val width = widths.rail
        scope.launch { prefs.setRailWidthDp(width.value.roundToInt()) }
    }

    /** What the device kept, read once; anything the reader has changed since the shell started stays as they left it. */
    suspend fun load() {
        val rail = prefs.railWidthDp.first()
        val panel = prefs.panelWidthDp.first()
        val medium = prefs.panelOpen(PaneWidthClass.Medium).first()
        val expanded = prefs.panelOpen(PaneWidthClass.Expanded).first()
        if (railWant == null) railWant = rail?.dp
        if (panelWant == null) panelWant = panel?.dp
        if (openMedium == null) openMedium = medium
        if (openExpanded == null) openExpanded = expanded
    }
}

/**
 * The wide window's row: the rail, while it stands beside the chat, at the width it was dragged to, its edge draggable
 * to resize it; then the detail pane, which a chat shares with its panel wherever the window has room to pin it
 * ([LocalPinnedPanel]).
 */
@Composable
internal fun WidePanes(
    panes: ShellPanes,
    railShown: Boolean,
    pinnable: Boolean,
    rail: @Composable () -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val edges = rememberBackGestureEdges()
    // The rail's edge as it is drawn, sliding in and out included, for the resize strip to ride.
    var railEdge by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(CursorTheme.colors.canvas)
            .onSizeChanged { panes.measure(with(density) { it.width.toDp() }) }
            .backGestureEdges(edges),
    ) {
        Row(Modifier.fillMaxSize()) {
            SidebarRail(expanded = railShown, width = { panes.railColumn() }, modifier = Modifier.onSizeChanged { railEdge = it.width }, content = rail)
            CompositionLocalProvider(LocalPinnedPanel provides if (pinnable) panes else null) {
                detail(Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (railShown) {
            PaneResizeEdge(
                side = PaneSide.Start,
                paneWidth = { panes.widths.rail },
                onResize = panes::resizeRail,
                onResizeDone = panes::resizeRailDone,
                contentDescription = RESIZE_SIDEBAR,
                edges = edges,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(railEdge - PaneResizeEdgeWidth.roundToPx() / 2, 0) },
            )
        }
    }
}

private const val RESIZE_SIDEBAR = "Resize sidebar"
