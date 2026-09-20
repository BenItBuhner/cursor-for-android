package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat

/**
 * The widget, drawn in the sidebar's proportions on the sidebar's surface (`CursorDimens`, `AgentRowItem`): a 40dp
 * header with the cube, the list's title as a picker and the "+" new-chat button, then 36dp rows on a 38dp pitch —
 * the 16dp state-glyph slot, 10dp, the 13sp title, and the trailing workspace / age / machine metadata the Chats
 * filter menu toggles. The whole widget is one tap deep: a row opens its chat, the cube opens the app, the title
 * switches lists, "+" starts a chat.
 *
 * [appWidgetId] is [AppWidgetManager.INVALID_APPWIDGET_ID] for the launcher's preview, which has nothing to configure
 * and nothing to refresh. [refreshing] is the widget's own refresh (the header button, or a periodic pass) being under
 * way: the button gives way to a spinner for as long as it is.
 */
@Composable
fun ChatsWidgetContent(snapshot: WidgetSnapshot, mode: WidgetMode, appWidgetId: Int, refreshing: Boolean = false, nowMillis: Long = AppClock.now()) {
    val context = LocalContext.current
    val palette = remember(snapshot.theme, snapshot.oledBlack) { WidgetPalette.forMode(snapshot.theme, snapshot.oledBlack) }
    val rows = remember(snapshot, mode, nowMillis) { snapshot.rows(mode, nowMillis) }

    Column(GlanceModifier.fillMaxSize().appWidgetBackground().surface(palette)) {
        Header(mode, palette, appWidgetId, refreshing)
        // Whatever follows the header takes the rest of the widget.
        val body = GlanceModifier.fillMaxWidth().defaultWeight()
        when {
            snapshot.isSignedOut -> Notice(context.getString(R.string.widget_sign_in), palette, body)
            // Nothing on disk and nothing fetched yet: while a fetch is on its way the widget says so, otherwise it
            // asks for the app, whose start is what fills the cache.
            rows.isEmpty() && !snapshot.hasLoaded && refreshing -> Notice(context.getString(R.string.widget_loading), palette, body)
            rows.isEmpty() && !snapshot.hasLoaded -> Notice(context.getString(R.string.widget_open_app), palette, body)
            rows.isEmpty() -> Notice(mode.emptyText, palette, body)
            else -> LazyColumn(body.padding(horizontal = 8.dp)) {
                itemsIndexed(rows, itemId = { index, row -> (index.toLong() shl 32) or (row.agent.id.hashCode().toLong() and 0xFFFFFFFFL) }) { _, row ->
                    AgentRowItem(row, snapshot.prefs, palette, nowMillis)
                }
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
 * The sidebar header and its "Chats" label folded into one row: the cube where the sidebar has it (14dp in, a
 * 22dp slot), the list's name in the group-label voice (12sp at 60 %) with the picker chevron the filter sheet's
 * "Group by" row uses, then two flat icon buttons — refresh, which becomes a spinner while the refresh it asked for
 * runs, and "+".
 */
@Composable
private fun Header(mode: WidgetMode, palette: WidgetPalette, appWidgetId: Int, refreshing: Boolean) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().height(40.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_cube),
            contentDescription = context.getString(R.string.app_name),
            modifier = GlanceModifier.size(22.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
            colorFilter = ColorFilter.tint(palette.iconPrimary),
        )
        Spacer(GlanceModifier.width(10.dp))
        val title = GlanceModifier.defaultWeight().fillMaxHeight()
        Row(
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) title.clickable(actionStartActivity(WidgetIntents.configure(context, appWidgetId))) else title,
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(mode.title, style = TextStyle(color = palette.textTertiary, fontSize = 12.sp), maxLines = 1)
            Spacer(GlanceModifier.width(3.dp))
            Image(
                provider = ImageProvider(R.drawable.widget_chevron_down),
                contentDescription = context.getString(R.string.widget_choose_list),
                modifier = GlanceModifier.size(15.dp),
                colorFilter = ColorFilter.tint(palette.iconQuaternary),
            )
        }
        RefreshButton(palette, appWidgetId, refreshing)
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

/** A sidebar row: selection inset, glyph slot, title, metadata. Tapping opens the chat through the app's deep link. */
@Composable
private fun AgentRowItem(row: AgentRow, prefs: ListPreferences, palette: WidgetPalette, nowMillis: Long) {
    val context = LocalContext.current
    val agent = row.agent
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            GlanceModifier
                .fillMaxWidth()
                .height(36.dp)
                .cornerRadius(6.dp)
                .clickable(actionStartActivity(WidgetIntents.openAgent(context, agent.id)))
                .padding(start = 8.dp, end = 10.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(GlanceModifier.size(16.dp), contentAlignment = Alignment.Center) { StateGlyph(row, palette) }
            Spacer(GlanceModifier.width(10.dp))
            Text(
                agent.name,
                style = TextStyle(color = if (agent.isArchived) palette.textTertiary else palette.textPrimary, fontSize = 13.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            val trailing = buildList {
                if (prefs.showWorkspace) agent.repoShortName?.let { add(it) }
                if (prefs.showRuntime) add(TimeFormat.relativeShort(agent.listedAtMillis, nowMillis))
            }
            if (trailing.isNotEmpty()) {
                Spacer(GlanceModifier.width(8.dp))
                Text(trailing.joinToString(" · "), style = TextStyle(color = palette.textQuaternary, fontSize = 13.sp), maxLines = 1)
            }
            if (prefs.showBranchStatus && agent.envType == EnvType.MACHINE) {
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
 * known) once it has a PR, else the neutral branch glyph.
 */
@Composable
private fun StateGlyph(row: AgentRow, palette: WidgetPalette) {
    val slot = GlanceModifier.size(16.dp)
    when (row.indicator) {
        AgentIndicator.Running -> WorkingGlyph(palette, slot)
        AgentIndicator.Unread -> Image(ImageProvider(R.drawable.widget_dot), "Unread", slot, colorFilter = ColorFilter.tint(palette.unreadDot))
        AgentIndicator.Error -> Image(ImageProvider(R.drawable.widget_dot), "Failed", slot, colorFilter = ColorFilter.tint(palette.red))
        AgentIndicator.Read -> when {
            row.agent.hasPullRequest -> Image(ImageProvider(R.drawable.widget_git_pull_request), row.pullRequest?.label ?: "Pull request", slot, colorFilter = ColorFilter.tint(palette.pullRequestTint(row.pullRequest)))
            row.agent.hasBranch -> Image(ImageProvider(R.drawable.widget_git_branch), "Branch", slot, colorFilter = ColorFilter.tint(palette.iconTertiary))
        }
        AgentIndicator.Archived -> Image(ImageProvider(R.drawable.widget_archive), "Archived", slot, colorFilter = ColorFilter.tint(palette.iconQuaternary))
        AgentIndicator.Snoozed -> Image(ImageProvider(R.drawable.widget_clock), "Snoozed", slot, colorFilter = ColorFilter.tint(palette.iconQuaternary))
    }
}

/**
 * The sidebar's "working" glyph, stepping through its eight arrangements like `RunningGlyph` does in the app.
 * RemoteViews can hand a view a drawable but can never call `start()` on it, so an `Image` of the frame animation
 * would sit on its first frame for good; a `ProgressBar` starts whatever `Animatable` it holds as its indeterminate
 * drawable the first time it draws, and keeps it running for as long as the row is on screen. The layout carries the
 * palette's tint (see [WidgetPalette.workingIndicatorLayout]).
 */
@Composable
private fun WorkingGlyph(palette: WidgetPalette, modifier: GlanceModifier) {
    val context = LocalContext.current
    AndroidRemoteViews(RemoteViews(context.packageName, palette.workingIndicatorLayout), modifier)
}

/** The sidebar's empty-list line — 12sp at 36 % — centred in the space the rows would take. Tapping opens the app. */
@Composable
private fun Notice(text: String, palette: WidgetPalette, modifier: GlanceModifier) {
    val context = LocalContext.current
    Box(
        modifier.padding(horizontal = 16.dp, vertical = 12.dp).clickable(actionStartActivity(WidgetIntents.openApp(context))),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(color = palette.textQuaternary, fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}
