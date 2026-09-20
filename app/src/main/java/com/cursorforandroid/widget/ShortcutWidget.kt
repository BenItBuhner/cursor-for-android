package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.cursorforandroid.MainActivity
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.ui.quick.QuickComposerActivity
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** One shortcut widget's choices, kept in its own Glance preferences and made when it is placed or reconfigured. */
data class ShortcutConfig(val style: ShortcutStyle = ShortcutStyle.Default, val target: ShortcutTarget = ShortcutTarget.Default) {

    fun writeTo(prefs: MutablePreferences) {
        prefs[STYLE_KEY] = style.name
        prefs[TARGET_KIND_KEY] = ShortcutTarget.kindOf(target)
        prefs[TARGET_ID_KEY] = target.agentId.orEmpty()
        prefs[TARGET_NAME_KEY] = (target as? ShortcutTarget.Chat)?.name ?: (target as? ShortcutTarget.Project)?.name.orEmpty()
    }

    companion object {
        val Default = ShortcutConfig()

        val STYLE_KEY = stringPreferencesKey("style")
        val TARGET_KIND_KEY = stringPreferencesKey("target_kind")
        val TARGET_ID_KEY = stringPreferencesKey("target_id")
        val TARGET_NAME_KEY = stringPreferencesKey("target_name")

        /** The stored choices; a widget placed without a visit to the configuration screen is the default. */
        fun read(prefs: Preferences): ShortcutConfig = ShortcutConfig(
            style = ShortcutStyle.parse(prefs[STYLE_KEY]),
            target = ShortcutTarget.parse(prefs[TARGET_KIND_KEY], prefs[TARGET_ID_KEY], prefs[TARGET_NAME_KEY]),
        )
    }
}

/**
 * The shapes the shortcut widget takes, decided by the cells it is given: one cell is the round button; two or more
 * across is the compose bar, a single line of the app's composer; and a bar two or more cells tall is the composer
 * box itself, placeholder above its footer. Resizing the widget moves between them.
 */
enum class ShortcutVariant {
    Button, Bar, TallBar;

    companion object {
        /** Below this the launcher has given one cell; a two-cell widget is at least 110dp on every grid Android ships. */
        private val BAR_MIN_WIDTH = 110.dp
        /** A bar this tall has room for the composer's text line above its footer. */
        private val TALL_MIN_HEIGHT = 96.dp

        fun forSize(size: DpSize): ShortcutVariant = when {
            size.width < BAR_MIN_WIDTH -> Button
            size.height >= TALL_MIN_HEIGHT -> TallBar
            else -> Bar
        }
    }
}

/**
 * The shortcut widget: a round new-chat button on the home screen, or, given the width, a compose bar that looks like
 * the app's composer. Tapping it opens the quick composer over the launcher ([QuickComposerActivity]) — or, when the
 * widget was pointed elsewhere in its configuration, the sidebar's search, one chat, or a Project. Its look and
 * target are per instance ([ShortcutConfig]); its shape follows the cells the launcher gives it ([ShortcutVariant]).
 * Nothing of the account is read for a render: only the theme, which the app-tinted look follows.
 *
 * Two receivers share it so the picker offers both shapes by name; [previewVariant] is the one each shows there.
 */
abstract class ShortcutWidget(private val previewVariant: ShortcutVariant) : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** One composition per size the launcher reports, so the button and the bar can each be laid out for their cells. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = context.appGraph
        val appearance = combine(graph.prefs.themeMode, graph.prefs.oledBlack, ::Pair)
        val initial = appearance.first()
        // A theme change while the app is alive reaches this widget the way it reaches the Chats one.
        WidgetSync.follow(context, graph)
        provideContent {
            val config = ShortcutConfig.read(currentState())
            val current by appearance.collectAsState(initial)
            ShortcutWidgetContent(config, ShortcutVariant.forSize(LocalSize.current), theme = current.first, oledBlack = current.second)
        }
    }

    /** The launcher's live preview (Android 15+): the default look, following the system theme like the picker. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { ShortcutWidgetContent(ShortcutConfig.Default, previewVariant, ThemeMode.System, oledBlack = false) }
    }
}

/** The 1x1 button; grows into the bar when stretched. */
class ShortcutButtonWidget : ShortcutWidget(ShortcutVariant.Button)

/** The compose bar; shrinks to the button when squeezed to one cell. */
class ComposeBarWidget : ShortcutWidget(ShortcutVariant.Bar)

class ShortcutButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutButtonWidget()

    /** The last button was removed: [WidgetSync] keeps following only for a widget of another kind. */
    override fun onDisabled(context: Context) {
        WidgetSync.stopIfNoneLeft(context, ShortcutButtonWidget::class.java)
        super.onDisabled(context)
    }
}

class ComposeBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ComposeBarWidget()

    override fun onDisabled(context: Context) {
        WidgetSync.stopIfNoneLeft(context, ComposeBarWidget::class.java)
        super.onDisabled(context)
    }
}

/** What a shortcut — a widget's tap, a launcher shortcut — launches for each [ShortcutTarget]. */
internal object ShortcutIntents {

    /** The quick composer over the launcher; its own task, so the launcher is what a dismissal returns to. */
    fun quickComposer(context: Context): Intent =
        Intent(Intent.ACTION_MAIN, null, context, QuickComposerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The app with the sidebar's search open. */
    fun search(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun forTarget(context: Context, target: ShortcutTarget): Intent = when (target) {
        ShortcutTarget.NewChat -> quickComposer(context)
        ShortcutTarget.Search -> search(context)
        is ShortcutTarget.Chat -> WidgetIntents.openAgent(context, target.id)
        is ShortcutTarget.Project -> WidgetIntents.openAgent(context, target.id)
    }

    /** Reopens one shortcut widget's configuration; the data URI keeps each widget's intent its own. */
    fun configure(context: Context, appWidgetId: Int): Intent =
        Intent(context, ShortcutWidgetConfigureActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setData("cursor-widget://configure-shortcut/$appWidgetId".toUri())
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
