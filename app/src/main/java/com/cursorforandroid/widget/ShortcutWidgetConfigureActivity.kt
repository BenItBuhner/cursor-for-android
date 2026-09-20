package com.cursorforandroid.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.cursorforandroid.AppGraph
import com.cursorforandroid.R
import com.cursorforandroid.appGraph
import com.cursorforandroid.domain.AgentListOrganizer
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Chooses how one shortcut widget looks and what it opens. The launcher opens this when the widget is placed (and
 * from its reconfigure affordance); each pick applies to the widget at once, and Done hands the widget back to the
 * launcher. Backing out of a placement leaves the widget unplaced, as the platform expects. Deliberately small: the
 * agents widget's redesigned settings are where both widgets' options are meant to end up, as one section each.
 */
class ShortcutWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val graph = appGraph
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)

        setContent {
            val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
            // Null until the widget's stored choices have been read: a fresh widget starts on the defaults.
            var config by remember { mutableStateOf<ShortcutConfig?>(null) }
            LaunchedEffect(Unit) {
                config = ShortcutConfig.read(getAppWidgetState(this@ShortcutWidgetConfigureActivity, PreferencesGlanceStateDefinition, glanceId))
            }
            // The chats a widget may be pointed at: the sidebar's Pinned and Projects groups, from the list on disk.
            val choices by produceState(ShortcutChoices()) { value = shortcutChoices(graph) }

            fun apply(next: ShortcutConfig) {
                config = next
                lifecycleScope.launch {
                    updateAppWidgetState(this@ShortcutWidgetConfigureActivity, glanceId) { next.writeTo(it) }
                    ShortcutButtonWidget().update(this@ShortcutWidgetConfigureActivity, glanceId)
                }
            }

            CursorTheme(mode = themeMode, oledBlack = oledBlack) {
                ShortcutWidgetConfigureScreen(
                    config = config,
                    choices = choices,
                    onStyle = { style -> apply((config ?: ShortcutConfig.Default).copy(style = style)) },
                    onTarget = { target -> apply((config ?: ShortcutConfig.Default).copy(target = target)) },
                    onDone = {
                        lifecycleScope.launch {
                            // A widget placed straight through Done keeps its defaults on record, like one that was reconfigured.
                            val chosen = config ?: ShortcutConfig.Default
                            updateAppWidgetState(this@ShortcutWidgetConfigureActivity, glanceId) { chosen.writeTo(it) }
                            ShortcutButtonWidget().update(this@ShortcutWidgetConfigureActivity, glanceId)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}

/** The chats a shortcut widget can be pointed at: the sidebar's Pinned group and its Projects group, in their order. */
data class ShortcutChoices(val pinned: List<AgentRow> = emptyList(), val projects: List<AgentRow> = emptyList())

/** Reads [ShortcutChoices] from the list as the widget's render would: restored from disk, revalidated when allowed. */
private suspend fun shortcutChoices(graph: AppGraph): ShortcutChoices {
    WidgetData.prepare(graph, WidgetRefreshBudget(connected = true, batteryLow = false))
    val snapshot = WidgetData.snapshot(graph)
    if (snapshot.isSignedOut) return ShortcutChoices()
    val sections = AgentListOrganizer.organize(snapshot.agents, snapshot.prefs, snapshot.local, knownRoots = graph.agents.knownRoots.value)
    return ShortcutChoices(
        pinned = sections.firstOrNull { it.key == AgentListOrganizer.PINNED_KEY }?.rows.orEmpty().filterNot { it.isPlaceholder },
        projects = sections.firstOrNull { it.key == AgentListOrganizer.PROJECTS_KEY }?.rows.orEmpty().filterNot { it.isPlaceholder },
    )
}

/**
 * Settings-page idiom, as the Chats widget's screen: header, 12sp group labels, bordered cards of rows with the
 * accent check on the chosen one. Two groups — the look, with a swatch of each disc, and what a tap opens.
 */
@Composable
fun ShortcutWidgetConfigureScreen(
    config: ShortcutConfig?,
    choices: ShortcutChoices,
    onStyle: (ShortcutStyle) -> Unit,
    onTarget: (ShortcutTarget) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = stringResource(R.string.shortcut_widget_configure_title),
            subtitle = stringResource(R.string.shortcut_widget_configure_subtitle),
            leading = { FlatIconButton(CursorIcons.Close, "Close", onClick = onClose) },
            trailing = { CursorButton("Done", onClick = onDone, primary = true, height = 30.dp, modifier = Modifier.padding(end = 8.dp)) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            GroupTitle(stringResource(R.string.shortcut_widget_configure_look))
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                ShortcutStyle.entries.forEachIndexed { index, style ->
                    ChoiceRow(
                        title = style.label,
                        detail = style.detail,
                        checked = config?.style == style,
                        leading = { StyleSwatch(style) },
                        onClick = { onStyle(style) },
                    )
                    if (index != ShortcutStyle.entries.lastIndex) HairlineDivider(Modifier.padding(horizontal = 14.dp))
                }
            }
            GroupTitle(stringResource(R.string.shortcut_widget_configure_opens))
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                ChoiceRow(
                    title = ShortcutTarget.NewChat.label,
                    detail = stringResource(R.string.shortcut_widget_new_chat_detail),
                    checked = config?.target == ShortcutTarget.NewChat,
                    leading = { Icon(CursorIcons.Plus, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp)) },
                    onClick = { onTarget(ShortcutTarget.NewChat) },
                )
                HairlineDivider(Modifier.padding(horizontal = 14.dp))
                ChoiceRow(
                    title = ShortcutTarget.Search.label,
                    detail = stringResource(R.string.shortcut_widget_search_detail),
                    checked = config?.target == ShortcutTarget.Search,
                    leading = { Icon(CursorIcons.Search, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp)) },
                    onClick = { onTarget(ShortcutTarget.Search) },
                )
            }
            if (choices.projects.isNotEmpty()) {
                GroupTitle("Projects")
                CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    choices.projects.forEachIndexed { index, row ->
                        val target = ShortcutTarget.Project(row.agent.id, row.agent.name)
                        ChoiceRow(
                            title = row.agent.name,
                            detail = row.agent.repoShortName,
                            checked = config?.target?.agentId == row.agent.id,
                            leading = {
                                Icon(CursorIcons.project(row.agent.projectAppearance?.icon), null, tint = colors.projectTone(row.agent.projectAppearance?.colorId), modifier = Modifier.size(17.dp))
                            },
                            onClick = { onTarget(target) },
                        )
                        if (index != choices.projects.lastIndex) HairlineDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
            if (choices.pinned.isNotEmpty()) {
                GroupTitle("Pinned")
                CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    choices.pinned.forEachIndexed { index, row ->
                        val target = ShortcutTarget.Chat(row.agent.id, row.agent.name)
                        ChoiceRow(
                            title = row.agent.name,
                            detail = row.agent.repoShortName,
                            checked = config?.target?.agentId == row.agent.id,
                            leading = { Icon(CursorIcons.Sparkle, null, tint = colors.iconSecondary, modifier = Modifier.size(17.dp)) },
                            onClick = { onTarget(target) },
                        )
                        if (index != choices.pinned.lastIndex) HairlineDivider(Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
            Text(
                stringResource(R.string.shortcut_widget_configure_note),
                style = type.small,
                color = colors.textQuaternary,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp))
}

/**
 * The disc as the style would paint it, at the sidebar glyph's size: a white disc, the app's surface with its
 * hairline, a wash of the foreground, or the bare "+". Drawn with the app's own tokens, so it is the widget in
 * miniature and not an illustration of it.
 */
@Composable
private fun StyleSwatch(style: ShortcutStyle) {
    val colors = CursorTheme.colors
    val (fill, ring, glyph) = when (style) {
        ShortcutStyle.White -> Triple(Color.White, Color(0xFF141414).copy(alpha = 0.08f), Color(0xFF141414))
        ShortcutStyle.Tinted -> Triple(colors.elevated, colors.strokeSubtle, colors.iconPrimary)
        ShortcutStyle.Glass -> Triple(colors.base.copy(alpha = 0.14f), colors.base.copy(alpha = 0.20f), colors.iconPrimary)
        ShortcutStyle.IconOnly -> Triple(Color.Transparent, Color.Transparent, colors.iconPrimary)
    }
    Box(
        Modifier.size(22.dp).background(fill, CircleShape).then(if (ring.alpha > 0f) Modifier.border(CursorDimens.hairline, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CursorIcons.Plus, null, tint = glyph, modifier = Modifier.size(12.dp))
    }
}

/** A sheet-style row: a 17dp glyph slot, label with a 12sp detail line, accent check when chosen. */
@Composable
private fun ChoiceRow(title: String, detail: String?, checked: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, CursorTheme.shapes.lg)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}