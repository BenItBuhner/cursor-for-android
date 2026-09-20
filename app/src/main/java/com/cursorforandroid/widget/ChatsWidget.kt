package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.ChatsWidgetSettings
import com.cursorforandroid.domain.WidgetAppearance
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.domain.WidgetTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.flow.first

/**
 * The "Chats" home-screen widget: recent, running, pinned or one Project's chats as sidebar rows. What it lists and
 * how it looks is per instance ([ChatsWidgetSettings], kept in the widget's own preferences under [SETTINGS_KEY]);
 * the rows come from the same repository, filters and pins
 * the app renders, restored from disk first so a fresh process shows the last known list at once, and re-rendered
 * by [WidgetSync] whenever any of that changes while the app is alive.
 *
 * A render's own composition follows the repository too ([collectAsState] below), but only for as long as Glance
 * keeps the session — 45 s after the first frame, 5 s once the device is idle — so a page that lands after that, a
 * run that finishes an hour later, reaches the widget through [WidgetSync] or not at all. That is why following is
 * installed from the application (see `CursorApp`) and not only from the app's screens.
 */
class ChatsWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** One arrangement per cell size ([WidgetSizes]); the launcher shows the largest that fits without a render. */
    override val sizeMode: SizeMode = SizeMode.Responsive(WidgetSizes.all)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        WidgetData.prepare(graph, deviceRefreshBudget(context))
        // This widget exists, so from now on the app's changes must reach it (a no-op once following).
        WidgetSync.follow(context, graph)
        val snapshots = WidgetData.snapshots(graph)
        val initial = snapshots.first()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val settings = WidgetSettingsState.read(currentState())
            val refreshing = WidgetRefresh.isRefreshing(currentState(REFRESHING_SINCE_KEY), AppClock.now())
            // Keeps following the repository for as long as the render session lives (a refresh landing behind a
            // cache-first render, a run finishing); after that, WidgetSync starts a new one.
            val snapshot by snapshots.collectAsState(initial)
            ChatsWidgetContent(snapshot, settings, appWidgetId, refreshing)
        }
    }

    /**
     * The launcher's live preview (Android 15+): the widget with sample rows. Follows the system theme like the
     * picker around it does, and reads nothing of the account — it has to work before the app was ever opened.
     */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { ChatsWidgetContent(WidgetData.sample(ThemeMode.System), ChatsWidgetSettings(appearance = WidgetAppearance(theme = WidgetTheme.System)), AppWidgetManager.INVALID_APPWIDGET_ID) }
    }

    companion object {
        /** The [WidgetMode] name of one widget instance, as builds before [SETTINGS_KEY] kept it; read as a fallback. */
        val MODE_KEY = stringPreferencesKey("mode")

        /** One widget instance's [ChatsWidgetSettings], as JSON. */
        val SETTINGS_KEY = stringPreferencesKey("settings")

        /**
         * When this instance's refresh button was last tapped and the page it asked for has not landed yet; absent
         * (or older than [WidgetRefresh.FLAG_TTL_MS]) otherwise. Read by the render to show the spinner in the
         * button's place.
         */
        val REFRESHING_SINCE_KEY = longPreferencesKey("refreshing_since")
    }
}

/** One widget's settings in its Glance state: the JSON under [ChatsWidget.SETTINGS_KEY], else what an older build kept. */
object WidgetSettingsState {
    fun read(prefs: Preferences): ChatsWidgetSettings =
        prefs[ChatsWidget.SETTINGS_KEY]?.let { ChatsWidgetSettings.decode(it) } ?: ChatsWidgetSettings.fromLegacyMode(prefs[ChatsWidget.MODE_KEY])

    suspend fun read(context: Context, id: GlanceId): ChatsWidgetSettings = read(getAppWidgetState(context, PreferencesGlanceStateDefinition, id))

    suspend fun write(context: Context, id: GlanceId, settings: ChatsWidgetSettings) {
        updateAppWidgetState(context, id) {
            it[ChatsWidget.SETTINGS_KEY] = settings.encode()
            it[ChatsWidget.MODE_KEY] = settings.mode.name
        }
    }
}

/**
 * The refresh button's state, kept as a timestamp rather than a flag so that a refresh the process did not live to
 * finish — killed mid-fetch, or a worker that never ran — cannot leave a spinner turning for ever: a render treats
 * the flag as spent once it is [FLAG_TTL_MS] old.
 */
internal object WidgetRefresh {
    /** Longer than the fetch the worker gives up on ([WidgetData.FORCED_REFRESH_TIMEOUT_MS]) plus a render behind it. */
    const val FLAG_TTL_MS = 30_000L

    fun isRefreshing(since: Long?, nowMillis: Long): Boolean = since != null && nowMillis - since in 0 until FLAG_TTL_MS
}

/**
 * The header's refresh button. Two things, in this order: the widget is told it is refreshing and re-rendered, so
 * the spinner replaces the button at once; then the fetch is handed to [WidgetRefreshWork], which clears the flag and
 * renders every widget again when the page has landed. Nothing of the network runs here: an action callback is a
 * broadcast receiver's handful of seconds, and a fetch that outlived it would be cut off mid-page.
 */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[ChatsWidget.REFRESHING_SINCE_KEY] = AppClock.now() }
        ChatsWidget().update(context, glanceId)
        WidgetRefreshWork.enqueueNow(context)
    }
}

class ChatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChatsWidget()

    /** The first widget was placed: from here the list is revalidated on a timer while the app is not around. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWork.schedulePeriodic(context)
    }

    /** The last widget was removed: there is nothing left for [WidgetSync] or the periodic refresh to keep in step. */
    override fun onDisabled(context: Context) {
        WidgetRefreshWork.cancelPeriodic(context)
        WidgetSync.stop()
        super.onDisabled(context)
    }
}

/** What the widget's taps launch. Each is distinct by action or data, so their pending intents never merge. */
internal object WidgetIntents {

    fun openApp(context: Context): Intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's `https://cursor.com/agents/<id>` deep link, as the live notification uses it. */
    fun openAgent(context: Context, agentId: String): Intent =
        Intent(Intent.ACTION_VIEW, CursorEndpoints.webUrl(agentId).toUri(), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun newChat(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_CHAT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The sidebar with its search field open and focused. */
    fun search(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Reopens the placement-time choice for one widget; the data URI keeps each widget's intent its own. */
    fun configure(context: Context, appWidgetId: Int): Intent =
        Intent(context, WidgetConfigureActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setData("cursor-widget://configure/$appWidgetId".toUri())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
