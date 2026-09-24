package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.cursorforandroid.R
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.CornerAction
import com.cursorforandroid.domain.CornerStyle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.HeaderElement
import com.cursorforandroid.domain.RowElement
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

/**
 * The sizes the widget's arrangements start at (see [WidgetLayout]). The widget is composed at the sizes the
 * launcher reports for it ([WidgetPlacement]), not at these: an arrangement picked from a fixed set of buckets is
 * picked by the launcher's host by nearest distance, which put a 4x2 widget on one line and gave a 4x4 one another
 * arrangement than its settings screen showed.
 */
object WidgetSizes {
    /** A 2x1 cell: one line. */
    val SMALL = DpSize(110.dp, 40.dp)
    /** The 4x2 default: header and rows. */
    val MEDIUM = DpSize(180.dp, 110.dp)
    /** 4x4 and up: header and two-line rows. */
    val LARGE = DpSize(250.dp, 230.dp)

    /**
     * Below this height the header row gives its room to the rows while [ChatsWidgetSettings.headerAutoHide] is on:
     * the 40dp header over two regular rows and a scrap of a third. A 4x2 cell in portrait keeps it; the same cell
     * in landscape, and a 2-row cell on a dense grid, do not.
     */
    val HEADER_MIN_HEIGHT = 140.dp

    /** Whether a widget of [size] draws its header under [settings]. */
    fun showsHeader(size: DpSize, settings: ChatsWidgetSettings): Boolean =
        settings.headerParts.isNotEmpty() && !(settings.headerAutoHide && size.height < HEADER_MIN_HEIGHT)

    /** The arrangement for [size] under [WidgetLayout.Auto]. */
    fun layoutFor(size: DpSize): WidgetLayout = when {
        size.height < MEDIUM.height || size.width < MEDIUM.width -> WidgetLayout.Small
        size.height >= LARGE.height && size.width >= LARGE.width -> WidgetLayout.Large
        else -> WidgetLayout.Medium
    }

    /** The size a forced [layout] is best shown at (the preview's frame, and a test's). */
    fun sizeFor(layout: WidgetLayout): DpSize = when (layout) {
        WidgetLayout.Small -> DpSize(320.dp, 60.dp)
        WidgetLayout.Auto, WidgetLayout.Medium -> DpSize(320.dp, 158.dp)
        WidgetLayout.Large -> DpSize(320.dp, 330.dp)
    }
}

/**
 * The widget, drawn in the sidebar's proportions on the sidebar's surface (`CursorDimens`, `AgentRowItem`), in one
 * of three arrangements ([WidgetLayout]): a single line for a 2x1 cell; the 40dp header — the list's title as a
 * picker, refresh, "+", and the cube if it is shown ([Header]) — over one-line rows; or the same header over two-line
 * chat rows for a tall placement. The header is left out when none of its parts is shown, and under
 * [WidgetSizes.HEADER_MIN_HEIGHT] while it hides itself there. A round action button sits in the bottom-right corner,
 * concentric with the widget's own corner (see [CornerButton]). The whole widget is one tap deep: a row opens its
 * chat, the cube opens the app, the title opens the settings.
 *
 * The same composition draws the home-screen widget and its settings screen's preview; both compose it at the size
 * the launcher reports for the placement ([WidgetPlacement]), so the arrangement chosen for one is the other's.
 *
 * [appWidgetId] is [AppWidgetManager.INVALID_APPWIDGET_ID] for the launcher's preview, which has nothing to configure
 * and nothing to refresh. [refreshing] is the widget's own refresh (the header button, or a periodic pass) being under
 * way: the button gives way to a spinner for as long as it is.
 */
@Composable
fun ChatsWidgetContent(
    snapshot: WidgetSnapshot,
    settings: ChatsWidgetSettings,
    appWidgetId: Int,
    refreshing: Boolean = false,
    nowMillis: Long = AppClock.now(),
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val palette = remember(snapshot.theme, snapshot.oledBlack, settings.appearance) { WidgetPalette.forAppearance(settings.appearance, snapshot.theme, snapshot.oledBlack) }
    val rows = remember(snapshot, settings.mode, settings.projectId, nowMillis) { snapshot.rows(settings.mode, nowMillis, settings.projectId) }
    val layout = if (settings.layout == WidgetLayout.Auto) WidgetSizes.layoutFor(size) else settings.layout
    val title = remember(settings.mode, settings.projectId, snapshot) { snapshot.title(settings) }
    val corner = CornerButtonSpec.of(context, settings, layout)

    val logo = settings.shows(HeaderElement.Logo)
    // The Projects list on one line is a strip of Project icons, when the line has room for more than one.
    val stripSlots = if (layout == WidgetLayout.Small && settings.mode == WidgetMode.Projects && !snapshot.isSignedOut && rows.isNotEmpty()) projectSlots(size.width, corner, logo) else 0

    Box(GlanceModifier.fillMaxSize().appWidgetBackground().surface(palette)) {
        when (layout) {
            WidgetLayout.Small -> if (stripSlots >= MIN_STRIP_SLOTS) {
                ProjectStrip(rows, settings, palette, corner, appWidgetId, refreshing, stripSlots)
            } else {
                SmallLine(title, smallDetail(context, snapshot, rows, settings, refreshing), palette, corner, appWidgetId, refreshing, logo)
            }
            else -> Column(GlanceModifier.fillMaxSize()) {
                if (WidgetSizes.showsHeader(size, settings)) {
                    Header(title, palette, appWidgetId, refreshing, settings.headerParts)
                } else {
                    // The rows' own top inset, where the header would have ended.
                    Spacer(GlanceModifier.height(8.dp))
                }
                // Whatever follows the header takes the rest of the widget.
                val body = GlanceModifier.fillMaxWidth().defaultWeight()
                when {
                    snapshot.isSignedOut -> Notice(context.getString(R.string.widget_sign_in), palette, body)
                    settings.mode == WidgetMode.Project && settings.projectId == null -> Notice(context.getString(R.string.widget_pick_project), palette, body, WidgetIntents.configure(context, appWidgetId).takeIf { appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID })
                    // Nothing on disk and nothing fetched yet: while a fetch is on its way the widget says so, otherwise
                    // it asks for the app, whose start is what fills the cache.
                    rows.isEmpty() && !snapshot.hasLoaded && refreshing -> Notice(context.getString(R.string.widget_loading), palette, body)
                    rows.isEmpty() && !snapshot.hasLoaded -> Notice(context.getString(R.string.widget_open_app), palette, body)
                    rows.isEmpty() -> Notice(emptyText(context, snapshot, settings.mode), palette, body)
                    else -> LazyColumn(body.padding(horizontal = 8.dp)) {
                        itemsIndexed(rows, itemId = { index, row -> (index.toLong() shl 32) or (row.agent.id.hashCode().toLong() and 0xFFFFFFFFL) }) { _, row ->
                            if (settings.mode == WidgetMode.Projects) ProjectRowItem(row, settings, palette, nowMillis) else AgentRowItem(row, settings, layout, palette, nowMillis)
                        }
                        // Room for the last row to scroll clear of the corner button.
                        if (corner != null) item { Spacer(GlanceModifier.height(corner.size + corner.inset)) }
                    }
                }
            }
        }
        if (corner != null && layout != WidgetLayout.Small) {
            Box(GlanceModifier.fillMaxSize().padding(corner.inset), contentAlignment = Alignment.BottomEnd) {
                CornerButton(corner, palette, appWidgetId, refreshing)
            }
        }
    }
}

/**
 * `--cursor-sidebar` behind everything. From Android 12 the launcher rounds widgets to one system radius and the
 * colour can be a plain background; before that the corners come from a shape drawable tinted to the surface colour.
 */
private fun GlanceModifier.surface(palette: WidgetPalette): GlanceModifier =
    if (Build.VERSION.SDK_INT >= 31) {
        background(palette.surface).cornerRadius(R.dimen.widget_corner_radius)
    } else {
        background(ImageProvider(R.drawable.widget_surface), ContentScale.FillBounds, ColorFilter.tint(palette.surface))
    }

/**
 * The sidebar header and its "Chats" label folded into one row, drawing the [parts] it is set to
 * ([ChatsWidgetSettings.headerParts]): the cube where the sidebar has it (14dp in, a 22dp slot), the list's name in
 * the group-label voice (12sp at 60 %) with the picker chevron the filter sheet's "Group by" row uses — 16dp in
 * without the cube, over the rows' glyphs — then the flat icon buttons: refresh, which becomes a spinner while the
 * refresh it asked for runs, and "+". Without the name, the gap it filled keeps the buttons at the end.
 */
@Composable
private fun Header(title: String, palette: WidgetPalette, appWidgetId: Int, refreshing: Boolean, parts: Set<HeaderElement>) {
    val context = LocalContext.current
    val logo = HeaderElement.Logo in parts
    Row(
        GlanceModifier.fillMaxWidth().height(40.dp).padding(start = if (logo) 14.dp else 16.dp, end = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (logo) {
            Image(
                provider = ImageProvider(R.drawable.widget_cube),
                contentDescription = context.getString(R.string.app_name),
                modifier = GlanceModifier.size(22.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
                colorFilter = ColorFilter.tint(palette.iconPrimary),
            )
            Spacer(GlanceModifier.width(10.dp))
        }
        val titleSlot = GlanceModifier.defaultWeight().fillMaxHeight()
        if (HeaderElement.Title in parts) {
            Row(
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) titleSlot.clickable(actionStartActivity(WidgetIntents.configure(context, appWidgetId))) else titleSlot,
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(title, style = TextStyle(color = palette.textTertiary, fontSize = 12.sp), maxLines = 1)
                Spacer(GlanceModifier.width(3.dp))
                Image(
                    provider = ImageProvider(R.drawable.widget_chevron_down),
                    contentDescription = context.getString(R.string.widget_choose_list),
                    modifier = GlanceModifier.size(15.dp),
                    colorFilter = ColorFilter.tint(palette.iconQuaternary),
                )
            }
        } else {
            Spacer(titleSlot)
        }
        if (HeaderElement.Refresh in parts) RefreshButton(palette, appWidgetId, refreshing)
        if (HeaderElement.NewChat in parts) {
            Box(
                GlanceModifier.size(32.dp).cornerRadius(8.dp).clickable(actionStartActivity(WidgetIntents.newChat(context))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.widget_plus),
                    contentDescription = context.getString(R.string.widget_new_chat),
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(palette.iconSecondary),
                )
            }
        }
    }
}

/**
 * The header's refresh control: the refresh glyph in a 32dp flat button, or — from the moment it is tapped until the
 * page it asked for has landed — a 16dp indeterminate spinner in the same slot, so the tap is answered on the next
 * frame and the widget never looks like it ignored it. The spinner is a ProgressBar, which animates in RemoteViews
 * where a drawable cannot (see [WorkingGlyph]).
 */
@Composable
private fun RefreshButton(palette: WidgetPalette, appWidgetId: Int, refreshing: Boolean) {
    val context = LocalContext.current
    val slot = GlanceModifier.size(32.dp).cornerRadius(8.dp)
    if (refreshing) {
        Box(slot, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(GlanceModifier.size(16.dp), color = palette.iconSecondary)
        }
        return
    }
    Box(
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) slot.clickable(actionRunCallback<RefreshWidgetAction>()) else slot,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_refresh),
            contentDescription = context.getString(R.string.widget_refresh),
            modifier = GlanceModifier.size(16.dp),
            colorFilter = ColorFilter.tint(palette.iconSecondary),
        )
    }
}

/**
 * The one-line arrangement of a 2x1 cell: the cube while the [logo] is shown, the list's name with what it holds
 * ("3 running", "2 unread"), and the corner action at the end. The line itself opens the app. The words take what
 * the cube and the corner button leave — a LinearLayout measures its weighted child last — so in the narrowest cell
 * they give way to the button rather than push it off the end.
 */
@Composable
private fun SmallLine(title: String, detail: String, palette: WidgetPalette, corner: CornerButtonSpec?, appWidgetId: Int, refreshing: Boolean, logo: Boolean) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxSize().padding(start = if (logo) 12.dp else 16.dp, end = if (corner != null) corner.inset else 12.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (logo) {
            Image(
                provider = ImageProvider(R.drawable.widget_cube),
                contentDescription = context.getString(R.string.app_name),
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(palette.iconPrimary),
            )
            Spacer(GlanceModifier.width(10.dp))
        }
        Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(title, style = TextStyle(color = palette.textTertiary, fontSize = 12.sp), maxLines = 1)
            Spacer(GlanceModifier.width(8.dp))
            Text(detail, style = TextStyle(color = palette.textPrimary, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        }
        if (corner != null) CornerButton(corner, palette, appWidgetId, refreshing)
    }
}

/** The one-line arrangement's word on the list: the same notices the rows' arrangement shows, else [smallDetail]. */
private fun smallDetail(context: Context, snapshot: WidgetSnapshot, rows: List<AgentRow>, settings: ChatsWidgetSettings, refreshing: Boolean): String = when {
    snapshot.isSignedOut -> context.getString(R.string.widget_sign_in)
    settings.mode == WidgetMode.Project && settings.projectId == null -> context.getString(R.string.widget_pick_project)
    rows.isEmpty() && !snapshot.hasLoaded && refreshing -> context.getString(R.string.widget_loading)
    rows.isEmpty() && !snapshot.hasLoaded -> context.getString(R.string.widget_open_app)
    rows.isEmpty() -> emptyText(context, snapshot, settings.mode, oneLine = true)
    else -> smallDetail(rows, settings.mode)
}

/**
 * What an empty list says. A Projects list without Extended mode is empty because nothing documented says which
 * chats are Projects, so it says how to get them rather than that there are none — in fewer words on [oneLine].
 */
private fun emptyText(context: Context, snapshot: WidgetSnapshot, mode: WidgetMode, oneLine: Boolean = false): String = when {
    mode != WidgetMode.Projects || snapshot.projectsAvailable -> mode.emptyText
    oneLine -> context.getString(R.string.widget_projects_extended_mode_short)
    else -> context.getString(R.string.widget_projects_extended_mode)
}

/**
 * What the one-line arrangement says beside the list's name: for the Running list the count of agents (the name
 * already says they run); for the Projects list how many are at work and how many there are; for the rest what
 * stands out — "1 running · 3 unread" — else the count of chats.
 */
internal fun smallDetail(rows: List<AgentRow>, mode: WidgetMode): String {
    if (mode == WidgetMode.Running) return if (rows.size == 1) "1 agent" else "${rows.size} agents"
    if (mode == WidgetMode.Projects) {
        val working = rows.count { it.indicator == AgentIndicator.Running || it.hasRunningDescendant }
        return buildList {
            if (working > 0) add("$working running")
            add(if (rows.size == 1) "1 Project" else "${rows.size} Projects")
        }.joinToString(" · ")
    }
    val running = rows.count { it.indicator == AgentIndicator.Running }
    val unread = rows.count { it.indicator == AgentIndicator.Unread }
    return buildList {
        if (running > 0) add("$running running")
        if (unread > 0) add("$unread unread")
        if (isEmpty()) add(if (rows.size == 1) "1 chat" else "${rows.size} chats")
    }.joinToString(" · ")
}

/**
 * The corner button's geometry. The disc is placed so its arc is concentric with the widget's own corner: the
 * launcher rounds the widget to R (the system radius from Android 12, the app's 12dp before), the disc has radius r,
 * and an inset of R − r puts both arcs on one centre — a constant ring of surface between the two, no tight corner
 * fighting the circle. Kept between 8 and 20dp so a launcher's radius neither crowds nor strands the button.
 */
internal class CornerButtonSpec(val action: CornerAction, val style: CornerStyle, val size: Dp, val inset: Dp) {
    companion object {
        fun of(context: Context, settings: ChatsWidgetSettings, layout: WidgetLayout): CornerButtonSpec? {
            if (settings.cornerAction == CornerAction.None) return null
            val size = if (layout == WidgetLayout.Small) 28.dp else 40.dp
            val density = context.resources.displayMetrics.density
            val radius = runCatching { context.resources.getDimension(R.dimen.widget_corner_radius) / density }.getOrDefault(12f)
            val inset = (radius - size.value / 2f).coerceIn(8f, 20f)
            return CornerButtonSpec(settings.cornerAction, settings.cornerStyle, size, inset.dp)
        }
    }
}

/**
 * The round action button: [CornerStyle.White] is a white disc with a dark glyph, the launcher's shortcut idiom;
 * [CornerStyle.Tinted] is the app's accent; [CornerStyle.Glass] a translucent disc over the surface. The glyph is
 * the action's; while a refresh this button asked for runs, the refresh glyph is a spinner.
 */
@Composable
internal fun CornerButton(spec: CornerButtonSpec, palette: WidgetPalette, appWidgetId: Int, refreshing: Boolean) {
    val context = LocalContext.current
    val glyphSize = spec.size * 0.5f
    val disc = GlanceModifier.size(spec.size).background(palette.cornerFill(spec.style)).cornerRadius(spec.size / 2)
    val live = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
    val action: Action? = when (spec.action) {
        CornerAction.NewChat -> actionStartActivity(WidgetIntents.newChat(context))
        CornerAction.Refresh -> if (live) actionRunCallback<RefreshWidgetAction>() else null
        CornerAction.Search -> actionStartActivity(WidgetIntents.search(context))
        CornerAction.OpenApp -> actionStartActivity(WidgetIntents.openApp(context))
        CornerAction.None -> null
    }
    Box(if (action != null) disc.clickable(action) else disc, contentAlignment = Alignment.Center) {
        if (spec.action == CornerAction.Refresh && refreshing) {
            CircularProgressIndicator(GlanceModifier.size(glyphSize), color = palette.cornerGlyph(spec.style))
        } else {
            Image(
                provider = ImageProvider(spec.action.glyph()),
                contentDescription = spec.action.label,
                modifier = GlanceModifier.size(glyphSize),
                colorFilter = ColorFilter.tint(palette.cornerGlyph(spec.style)),
            )
        }
    }
}

private fun CornerAction.glyph(): Int = when (this) {
    CornerAction.NewChat -> R.drawable.widget_plus
    CornerAction.Refresh -> R.drawable.widget_refresh
    CornerAction.Search -> R.drawable.widget_search
    CornerAction.OpenApp, CornerAction.None -> R.drawable.widget_cube
}

/**
 * A sidebar row: selection inset, glyph slot, title, metadata — on one line for [WidgetLayout.Medium], the metadata
 * under the title for [WidgetLayout.Large]. The row's pitch follows the widget's density. Tapping opens the chat
 * through the app's deep link.
 */
@Composable
private fun AgentRowItem(row: AgentRow, settings: ChatsWidgetSettings, layout: WidgetLayout, palette: WidgetPalette, nowMillis: Long) {
    val context = LocalContext.current
    val agent = row.agent
    val twoLines = layout == WidgetLayout.Large
    val rowHeight = settings.density.rowHeightDp.dp + if (twoLines) 14.dp else 0.dp
    val titleSize = settings.density.titleSp.sp
    val glyphSlot = settings.shows(RowElement.Status) || settings.shows(RowElement.UnreadDot)
    val trailing = buildList {
        if (settings.shows(RowElement.Repo)) agent.repoShortName?.let { add(it) }
        if (settings.shows(RowElement.Time)) add(TimeFormat.relativeShort(agent.listedAtMillis, nowMillis))
    }.joinToString(" · ")
    val titleColor = if (agent.isArchived) palette.textTertiary else palette.textPrimary
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            GlanceModifier
                .fillMaxWidth()
                .height(rowHeight)
                .cornerRadius(6.dp)
                .clickable(actionStartActivity(WidgetIntents.openAgent(context, agent.id)))
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            if (glyphSlot) {
                Box(GlanceModifier.size(16.dp), contentAlignment = Alignment.Center) { StateGlyph(row, settings, palette) }
                Spacer(GlanceModifier.width(10.dp))
            }
            if (twoLines) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(agent.name, style = TextStyle(color = titleColor, fontSize = titleSize), maxLines = 1)
                    if (trailing.isNotEmpty()) {
                        Text(trailing, style = TextStyle(color = palette.textQuaternary, fontSize = (settings.density.titleSp - 1).sp), maxLines = 1)
                    }
                }
            } else {
                Text(agent.name, style = TextStyle(color = titleColor, fontSize = titleSize), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (trailing.isNotEmpty()) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(trailing, style = TextStyle(color = palette.textQuaternary, fontSize = titleSize), maxLines = 1)
                }
            }
            if (settings.shows(RowElement.Status) && agent.envType == EnvType.MACHINE) {
                Spacer(GlanceModifier.width(8.dp))
                Image(
                    provider = ImageProvider(R.drawable.widget_desktop),
                    contentDescription = "Self-hosted machine",
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = ColorFilter.tint(palette.iconTertiary),
                )
            }
        }
    }
}

/**
 * The row's leading glyph with the sidebar's semantics (`StateGlyph` in Primitives.kt): the stepping dot grid while
 * working, the blue unread dot, the red error dot, the archive box, nothing for a plain read chat, and for a read
 * chat that pushed, the pull-request glyph in the colour of the state GitHub reports (neutral while it is not
 * known) once it has a PR, else the neutral branch glyph. Each is drawn only while its [RowElement] is shown.
 */
@Composable
private fun StateGlyph(row: AgentRow, settings: ChatsWidgetSettings, palette: WidgetPalette) {
    val slot = GlanceModifier.size(16.dp)
    val status = settings.shows(RowElement.Status)
    when (row.indicator) {
        AgentIndicator.Running -> if (status) WorkingGlyph(palette, slot)
        AgentIndicator.Unread -> if (settings.shows(RowElement.UnreadDot)) Image(ImageProvider(R.drawable.widget_dot), "Unread", slot, colorFilter = ColorFilter.tint(palette.unreadDot))
        AgentIndicator.Error -> if (status) Image(ImageProvider(R.drawable.widget_dot), "Failed", slot, colorFilter = ColorFilter.tint(palette.red))
        AgentIndicator.Read -> if (status) when {
            row.agent.hasPullRequest -> Image(ImageProvider(R.drawable.widget_git_pull_request), row.pullRequest?.label ?: "Pull request", slot, colorFilter = ColorFilter.tint(palette.pullRequestTint(row.pullRequest)))
            row.agent.hasBranch -> Image(ImageProvider(R.drawable.widget_git_branch), "Branch", slot, colorFilter = ColorFilter.tint(palette.iconTertiary))
        }
        AgentIndicator.Archived -> if (status) Image(ImageProvider(R.drawable.widget_archive), "Archived", slot, colorFilter = ColorFilter.tint(palette.iconQuaternary))
        AgentIndicator.Snoozed -> if (status) Image(ImageProvider(R.drawable.widget_clock), "Snoozed", slot, colorFilter = ColorFilter.tint(palette.iconQuaternary))
    }
}

/**
 * The sidebar's "working" glyph, stepping through its eight arrangements like `RunningGlyph` does in the app.
 * RemoteViews can hand a view a drawable but can never call `start()` on it, so an `Image` of the frame animation
 * would sit on its first frame for good; a `ProgressBar` starts whatever `Animatable` it holds as its indeterminate
 * drawable the first time it draws, and keeps it running for as long as the row is on screen. The layout carries the
 * palette's tint (see [WidgetPalette.workingIndicatorLayout]); from Android 12 a [tint] — a day and a night colour,
 * as a Project's own colour is — replaces it, the one way RemoteViews can reach a ProgressBar's tint.
 */
@Composable
internal fun WorkingGlyph(palette: WidgetPalette, modifier: GlanceModifier, tint: Pair<Color, Color>? = null) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, palette.workingIndicatorLayout)
    if (tint != null && Build.VERSION.SDK_INT >= 31) {
        views.setColorStateList(R.id.widget_working_glyph, "setIndeterminateTintList", ColorStateList.valueOf(tint.first.toArgb()), ColorStateList.valueOf(tint.second.toArgb()))
    }
    AndroidRemoteViews(views, modifier)
}

/** The sidebar's empty-list line — 12sp at 36 % — centred in the space the rows would take. Tapping opens the app, or [intent]. */
@Composable
internal fun Notice(text: String, palette: WidgetPalette, modifier: GlanceModifier, intent: android.content.Intent? = null) {
    val context = LocalContext.current
    Box(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable(actionStartActivity(intent ?: WidgetIntents.openApp(context))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = palette.textQuaternary, fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}
