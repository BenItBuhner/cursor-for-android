package com.cursorforandroid.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.cursorforandroid.R
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.RowElement
import com.cursorforandroid.domain.WidgetLayout
import com.cursorforandroid.util.TimeFormat

/** A Project in the one-line strip: an 18dp icon in a tap target this wide. */
private val STRIP_SLOT = 30.dp

/** Fewer Projects than this on the line and it says what it holds in words instead (see [projectSlots]). */
internal const val MIN_STRIP_SLOTS = 2

/** A Glance Row holds ten children at most and drops the rest, so the strip's own Row is kept to that. */
private const val MAX_STRIP_SLOTS = 10

/**
 * How many Projects the one-line strip has room for at [width]: what is left once the cube, its gap and the corner
 * button (or the line's end padding) have theirs, in [STRIP_SLOT]s — the icon's slot is its own margin.
 */
internal fun projectSlots(width: Dp, corner: CornerButtonSpec?): Int {
    val taken = 12.dp + 18.dp + 6.dp + if (corner != null) corner.size + corner.inset else 12.dp
    return ((width - taken) / STRIP_SLOT).toInt().coerceIn(0, MAX_STRIP_SLOTS)
}

/**
 * The second line of a Project's two-line row: what is at work under it, how many chats it holds, and the row's
 * metadata — "2 running · 5 chats · cesium · 1h". A stand-in's time is not known until its row has been fetched.
 */
internal fun projectDetail(row: AgentRow, settings: ChatsWidgetSettings, nowMillis: Long): String = buildList {
    val running = row.descendants().count { it.indicator == AgentIndicator.Running }
    if (settings.shows(RowElement.Status) && running > 0) add("$running running")
    row.shownCount.takeIf { it > 0 }?.let { add(if (it == 1) "1 chat" else "$it chats") }
    if (settings.shows(RowElement.Repo)) row.agent.repoShortName?.let { add(it) }
    if (settings.shows(RowElement.Time) && !row.isStandIn) add(TimeFormat.relativeShort(row.agent.listedAtMillis, nowMillis))
}.joinToString(" · ")

/**
 * A Project's row, drawn as the sidebar draws it (`AgentRowItem`): the Project's icon in its colour — the working
 * dots in that colour while the coordinator runs, an unread or error badge on the icon — its name, then its age and
 * the count of its chats, beside which the working dots stand while any of them runs. The name is the shortcut, so
 * on one line the repository gives way to it (a Project's name is often its repository's, and a Project may span
 * several); two lines for [WidgetLayout.Large], the counts and the repository in words under the name. Tapping opens
 * the coordinator, the Project's own chat.
 */
@Composable
internal fun ProjectRowItem(row: AgentRow, settings: ChatsWidgetSettings, layout: WidgetLayout, palette: WidgetPalette, nowMillis: Long) {
    val context = LocalContext.current
    val agent = row.agent
    val twoLines = layout == WidgetLayout.Large
    val rowHeight = settings.density.rowHeightDp.dp + if (twoLines) 14.dp else 0.dp
    val titleSize = settings.density.titleSp.sp
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
            ProjectGlyph(row, settings, palette, iconSize = 16.dp)
            // The glyph's box is 2dp wider than the 16dp slot for the badge, so the name starts where a chat's does.
            Spacer(GlanceModifier.width(8.dp))
            if (twoLines) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(agent.name, style = TextStyle(color = palette.textPrimary, fontSize = titleSize), maxLines = 1)
                    val detail = projectDetail(row, settings, nowMillis)
                    if (detail.isNotEmpty()) {
                        Text(detail, style = TextStyle(color = palette.textQuaternary, fontSize = (settings.density.titleSp - 1).sp), maxLines = 1)
                    }
                }
            } else {
                Text(agent.name, style = TextStyle(color = palette.textPrimary, fontSize = titleSize), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (settings.shows(RowElement.Time) && !row.isStandIn) {
                    Spacer(GlanceModifier.width(8.dp))
                    Text(TimeFormat.relativeShort(agent.listedAtMillis, nowMillis), style = TextStyle(color = palette.textQuaternary, fontSize = titleSize), maxLines = 1)
                }
                ChatCount(row, settings, palette)
            }
        }
    }
}

/**
 * The count of chats under a Project, as the sidebar's collapsed Project shows it: 12sp at 36 %, with the working
 * dots before it in the tertiary icon tone while any of those chats runs. Nothing for a Project with no chats yet.
 */
@Composable
private fun ChatCount(row: AgentRow, settings: ChatsWidgetSettings, palette: WidgetPalette) {
    val count = row.shownCount
    if (count == 0) return
    Spacer(GlanceModifier.width(8.dp))
    if (settings.shows(RowElement.Status) && row.hasRunningDescendant) {
        WorkingGlyph(palette, GlanceModifier.size(12.dp), palette.iconTertiaryTints)
        Spacer(GlanceModifier.width(4.dp))
    }
    Text(count.toString(), style = TextStyle(color = palette.textQuaternary, fontSize = 12.sp), maxLines = 1)
}

/**
 * The sidebar's `ProjectGlyph` at [iconSize]: the icon in the Project's tone, or while the coordinator runs the
 * working dots in that tone; unread (blue) or failed (red) as an 8dp badge on the icon's top-end corner, 2dp out
 * from it and haloed in the sidebar colour. The box is 2dp wider and 4dp taller than the icon to hold the badge.
 */
@Composable
private fun ProjectGlyph(row: AgentRow, settings: ChatsWidgetSettings, palette: WidgetPalette, iconSize: Dp) {
    val colorId = row.agent.projectAppearance?.colorId
    val working = row.indicator == AgentIndicator.Running && settings.shows(RowElement.Status)
    val badge: ColorProvider? = when (row.indicator) {
        AgentIndicator.Unread -> palette.unreadDot.takeIf { settings.shows(RowElement.UnreadDot) }
        AgentIndicator.Error -> palette.red.takeIf { settings.shows(RowElement.Status) }
        else -> null
    }
    Box(GlanceModifier.width(iconSize + 2.dp).height(iconSize + 4.dp)) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            if (working) {
                WorkingGlyph(palette, GlanceModifier.size(iconSize), palette.projectToneTints(colorId))
            } else {
                ProjectIcon(row, palette, iconSize)
            }
        }
        if (badge != null) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Box(GlanceModifier.size(8.dp), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.widget_circle), null, GlanceModifier.size(8.dp), colorFilter = ColorFilter.tint(palette.halo))
                    Image(ImageProvider(R.drawable.widget_circle), if (row.indicator == AgentIndicator.Error) "Failed" else "Unread", GlanceModifier.size(5.dp), colorFilter = ColorFilter.tint(badge))
                }
            }
        }
    }
}

/** The Project's icon at [size], drawn for the screen (see [ProjectIconBitmaps]) and tinted in the Project's tone. */
@Composable
private fun ProjectIcon(row: AgentRow, palette: WidgetPalette, size: Dp) {
    val context = LocalContext.current
    val appearance = row.agent.projectAppearance
    Image(
        provider = ImageProvider(ProjectIconBitmaps.bitmap(context, appearance?.icon, size.value)),
        contentDescription = row.agent.name,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(palette.projectTone(appearance?.colorId)),
    )
}

/**
 * The Projects list on one line: the cube, then a Project icon for each of the first [slots] Projects — each one
 * tap from its coordinator, working and badged as its row would be — and "+N" for the rest when they do not all
 * fit, then the corner action. The line around them opens the app.
 */
@Composable
internal fun ProjectStrip(rows: List<AgentRow>, settings: ChatsWidgetSettings, palette: WidgetPalette, corner: CornerButtonSpec?, appWidgetId: Int, refreshing: Boolean, slots: Int) {
    val context = LocalContext.current
    val overflow = rows.size > slots
    val shown = rows.take(if (overflow) slots - 1 else slots)
    Row(
        GlanceModifier.fillMaxSize().padding(start = 12.dp, end = if (corner != null) corner.inset else 12.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_cube),
            contentDescription = context.getString(R.string.app_name),
            modifier = GlanceModifier.size(18.dp),
            colorFilter = ColorFilter.tint(palette.iconPrimary),
        )
        Spacer(GlanceModifier.width(6.dp))
        // A Row of their own: with the cube, the spacers and the corner button beside them they would pass ten.
        Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            shown.forEach { row ->
                Box(
                    GlanceModifier.width(STRIP_SLOT).height(32.dp).cornerRadius(8.dp).clickable(actionStartActivity(WidgetIntents.openAgent(context, row.agent.id))),
                    contentAlignment = Alignment.Center,
                ) {
                    ProjectGlyph(row, settings, palette, iconSize = 18.dp)
                }
            }
            if (overflow) {
                Box(GlanceModifier.width(STRIP_SLOT).height(32.dp), contentAlignment = Alignment.Center) {
                    Text("+${rows.size - shown.size}", style = TextStyle(color = palette.textTertiary, fontSize = 12.sp), maxLines = 1)
                }
            }
        }
        if (corner != null) CornerButton(corner, palette, appWidgetId, refreshing)
    }
}
