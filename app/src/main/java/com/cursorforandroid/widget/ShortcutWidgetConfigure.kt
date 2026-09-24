package com.cursorforandroid.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.R
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.ShortcutStyle
import com.cursorforandroid.domain.ShortcutTarget
import com.cursorforandroid.domain.ShortcutWidgetSettings
import com.cursorforandroid.domain.WidgetMode
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDarkColors
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorLightColors
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/**
 * The shortcut widget's entries in [WidgetKinds], one per receiver so the picker's two shapes each open the shared
 * settings screen with the right preview: the button, and the compose bar. Both edit the same
 * [ShortcutWidgetSettings]; the appearance section is the one every widget of this app carries.
 */
abstract class ShortcutWidgetKind(private val variant: ShortcutVariant) : WidgetKind {
    override val subtitle: String get() = "What it opens and how it looks"

    @OptIn(FlowPreview::class)
    @Composable
    override fun Configure(scope: WidgetConfigureScope) {
        val graph = scope.graph
        // Nothing is drawn until the stored settings are known, so the screen never opens on defaults that snap away.
        var settings by remember { mutableStateOf<ShortcutWidgetSettings?>(null) }
        LaunchedEffect(Unit) {
            settings = ShortcutSettingsState.read(scope.context, scope.glanceId)
            // The chats and Projects a widget can be pointed at come from the list; a fresh process restores it first.
            WidgetData.prepare(graph, deviceRefreshBudget(scope.context))
        }
        val snapshot by remember { WidgetData.snapshots(graph) }.collectAsStateWithLifecycle(initialValue = null)
        val appMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
        val appOledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
        val current = settings ?: return
        val latest by rememberUpdatedState(current)
        // Every change lands in the widget's own state at once; the widget itself is redrawn a moment after the last.
        LaunchedEffect(Unit) {
            snapshotFlow { latest }.drop(1).collect { ShortcutSettingsState.write(scope.context, scope.glanceId, it) }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { latest }.drop(1).debounce(400).collect { WidgetSync.render(scope.context, scope.glanceId, ShortcutButtonWidget()) }
        }
        val placement = remember { WidgetPlacement.of(scope.context, scope.appWidgetId) }
        ShortcutWidgetOptions(
            settings = current,
            variant = variant,
            targets = snapshot?.let { ShortcutTargets.from(it) } ?: ShortcutTargets(),
            appMode = appMode,
            appOledBlack = appOledBlack,
            onChange = { settings = it },
            placement = placement,
        )
    }
}

object ShortcutButtonWidgetKind : ShortcutWidgetKind(ShortcutVariant.Button) {
    override val receiver: Class<out GlanceAppWidgetReceiver> = ShortcutButtonWidgetReceiver::class.java
    override val title: String get() = "New chat button"
}

object ComposeBarWidgetKind : ShortcutWidgetKind(ShortcutVariant.Bar) {
    override val receiver: Class<out GlanceAppWidgetReceiver> = ComposeBarWidgetReceiver::class.java
    override val title: String get() = "Compose bar"
}

/** The chats a shortcut widget can be pointed at: the sidebar's pinned chats and its Projects. */
data class ShortcutTargets(val pinned: List<AgentRow> = emptyList(), val projects: List<AgentRow> = emptyList()) {
    companion object {
        fun from(snapshot: WidgetSnapshot): ShortcutTargets = ShortcutTargets(
            pinned = snapshot.rows(WidgetMode.Pinned).filterNot { it.isPlaceholder },
            projects = snapshot.projects.map { com.cursorforandroid.domain.AgentListOrganizer.toRow(it, snapshot.local) },
        )
    }
}

/**
 * The screen's body for one shortcut widget: the live preview on its stage, then the option groups — what a tap
 * opens, the button's look, and the appearance every widget shares. Stateless, so the screenshot tests can draw
 * it with any settings.
 *
 * [placement] is the size the widget is drawn at on the home screen ([WidgetPlacement]): the preview is composed at
 * it, in the shape that size takes, as the widget itself is. Without one, [variant] at the size it is previewed at.
 */
@Composable
fun ShortcutWidgetOptions(
    settings: ShortcutWidgetSettings,
    variant: ShortcutVariant,
    targets: ShortcutTargets,
    appMode: ThemeMode,
    appOledBlack: Boolean,
    onChange: (ShortcutWidgetSettings) -> Unit,
    modifier: Modifier = Modifier,
    placement: DpSize? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth()) {
        val previewSize = placement ?: ShortcutVariant.previewSize(variant)
        val shape = if (placement != null) ShortcutVariant.forSize(placement) else variant
        WidgetPreview(size = previewSize, key = Triple(settings, appMode, appOledBlack), modifier = Modifier.padding(top = 12.dp), framed = false) {
            ShortcutWidgetContent(settings, shape, appMode, appOledBlack)
        }

        OptionGroup(stringResource(R.string.shortcut_widget_configure_opens)) {
            val target = settings.target
            val kinds = TargetKind.entries
            ChipRow(kinds.map { it.label }, kinds.indexOf(TargetKind.of(target)), onSelect = { index ->
                when (kinds[index]) {
                    TargetKind.NewChat -> onChange(settings.withTarget(ShortcutTarget.NewChat))
                    TargetKind.Search -> onChange(settings.withTarget(ShortcutTarget.Search))
                    // A chat or Project is picked below; until then the first of the list stands, or nothing changes.
                    TargetKind.Chat -> targets.pinned.firstOrNull()?.let { onChange(settings.withTarget(ShortcutTarget.Chat(it.agent.id, it.agent.name))) }
                    TargetKind.Project -> targets.projects.firstOrNull()?.let { onChange(settings.withTarget(ShortcutTarget.Project(it.agent.id, it.agent.name))) }
                }
            }, modifier = Modifier.testTag("option-target"))
            when (target) {
                is ShortcutTarget.Chat -> PickRow(
                    title = stringResource(R.string.shortcut_widget_configure_chat),
                    rows = targets.pinned,
                    chosenId = target.id,
                    emptyText = stringResource(R.string.shortcut_widget_configure_no_pinned),
                    icon = { CursorIcons.Sparkle to colors.iconSecondary },
                    onPick = { onChange(settings.withTarget(ShortcutTarget.Chat(it.agent.id, it.agent.name))) },
                )
                is ShortcutTarget.Project -> PickRow(
                    title = stringResource(R.string.widget_configure_project),
                    rows = targets.projects,
                    chosenId = target.id,
                    emptyText = stringResource(R.string.shortcut_widget_configure_no_projects),
                    icon = { CursorIcons.project(it.agent.projectAppearance?.icon) to colors.projectTone(it.agent.projectAppearance?.colorId) },
                    onPick = { onChange(settings.withTarget(ShortcutTarget.Project(it.agent.id, it.agent.name))) },
                )
                ShortcutTarget.NewChat, ShortcutTarget.Search -> Unit
            }
            if (targets.pinned.isEmpty() && targets.projects.isEmpty() && (target is ShortcutTarget.NewChat || target is ShortcutTarget.Search)) {
                Text(stringResource(R.string.shortcut_widget_configure_targets_hint), style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }

        OptionGroup(stringResource(R.string.shortcut_widget_configure_style)) {
            val glyph = when (settings.target) {
                ShortcutTarget.Search -> CursorIcons.Search
                is ShortcutTarget.Chat -> CursorIcons.Sparkle
                is ShortcutTarget.Project -> CursorIcons.Cube
                ShortcutTarget.NewChat -> CursorIcons.Plus
            }
            val context = LocalContext.current
            val (mode, oledBlack) = settings.appearance.theme.resolve(appMode, appOledBlack)
            val dark = when (mode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> remember(context) { HomeScreenNight.isNight(context) }
            }
            SwatchRow(ShortcutStyle.entries, settings.style, onSelect = { onChange(settings.copy(style = it)) }, label = { it.label }, modifier = Modifier.testTag("option-style")) { style ->
                StyleSwatch(ShortcutTones.of(style, dark, oledBlack, opacity = 1f), glyph, ground = swatchGround(dark, oledBlack).takeIf { dark != colors.isDark })
            }
        }

        OptionGroup(stringResource(R.string.widget_configure_appearance)) {
            WidgetAppearanceOptions(settings.appearance, onChange = { onChange(settings.copy(appearance = it)) })
        }

        Text(stringResource(R.string.shortcut_widget_configure_note), style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
    }
}

/** The four kinds of target as the chip row names them; a chat or Project is then picked from a list. */
private enum class TargetKind(val label: String) {
    NewChat("New chat"), Search("Search"), Chat("Chat"), Project("Project");

    companion object {
        fun of(target: ShortcutTarget): TargetKind = when (target) {
            ShortcutTarget.NewChat -> NewChat
            ShortcutTarget.Search -> Search
            is ShortcutTarget.Chat -> Chat
            is ShortcutTarget.Project -> Project
        }
    }
}

/**
 * The disc one look paints around [glyph], in the [tones] the widget itself takes for its theme ([ShortcutTones]):
 * the solid disc, the composer's surface with its hairline, a wash of the foreground, or the bare glyph. Drawn with
 * the same shapes as the widget, so the swatch is the widget in miniature. A [ground] is a patch of a home screen
 * of the other brightness under it, for a look of the dark side on a light screen or the other way round — glass
 * and the bare glyph are made to be seen on the wallpaper, not on this screen's card.
 */
@Composable
private fun StyleSwatch(tones: ShortcutTones, glyph: ImageVector, ground: Color?) {
    Box(Modifier.size(48.dp).then(if (ground != null) Modifier.background(ground, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        val fill = tones.fill ?: Color.Transparent
        val ring = tones.ring ?: Color.Transparent
        Box(
            Modifier.size(40.dp).background(fill, CircleShape).then(if (ring.alpha > 0f) Modifier.border(CursorDimens.hairline, ring, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, null, tint = tones.glyph, modifier = Modifier.size(20.dp))
        }
    }
}

/** A home screen's ground on the [dark] side or the light one, for [StyleSwatch]. */
private fun swatchGround(dark: Boolean, oledBlack: Boolean): Color = when {
    !dark -> CursorLightColors.canvas
    oledBlack -> Color.Black
    else -> CursorDarkColors.canvas
}

/** The chosen chat or Project: its glyph and name, opening a sheet of [rows] to pick from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickRow(
    title: String,
    rows: List<AgentRow>,
    chosenId: String,
    emptyText: String,
    icon: (AgentRow) -> Pair<ImageVector, Color>,
    onPick: (AgentRow) -> Unit,
) {
    val colors = CursorTheme.colors
    var open by remember { mutableStateOf(false) }
    val chosen = rows.firstOrNull { it.agent.id == chosenId }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable({ open = true }, RoundedCornerShape(WidgetOptionRadii.control))
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("option-pick"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (glyph, tint) = chosen?.let(icon) ?: (CursorIcons.Sparkle to colors.iconQuaternary)
        Icon(glyph, null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            chosen?.agent?.name ?: title,
            style = CursorTheme.typography.base,
            color = if (chosen != null) colors.textPrimary else colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
    if (open) {
        CursorSheet(onDismiss = { open = false }) { dismiss ->
            SheetHeader(title)
            if (rows.isEmpty()) {
                Text(emptyText, style = CursorTheme.typography.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            rows.forEach { row ->
                val (glyph, tint) = icon(row)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .pressable({ onPick(row); dismiss() }, CursorTheme.shapes.base)
                        .heightIn(min = CursorDimens.listRow)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(glyph, null, tint = tint, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(row.agent.name, style = CursorTheme.typography.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row.agent.id == chosenId) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
