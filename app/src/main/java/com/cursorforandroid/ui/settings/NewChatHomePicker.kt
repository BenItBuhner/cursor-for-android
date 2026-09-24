package com.cursorforandroid.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.home.NewChatPageMiniature
import com.cursorforandroid.ui.home.rememberComposerChips
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NewChatHomePickerCopy {
    const val GROUP = "New chat page"
    const val RECENT = "Recent agents"
    const val PROJECTS = "Projects"
    const val COMPOSER = "Composer only"
    const val NEEDS_MODE = "Projects need Extended mode"
    const val NEEDS_MODE_DETAIL = "Until it is on, the Projects page lists your recent agents under a note."

    fun label(home: NewChatHome): String = when (home) {
        NewChatHome.RECENT -> RECENT
        NewChatHome.PROJECTS -> PROJECTS
        NewChatHome.COMPOSER -> COMPOSER
    }
}

object NewChatHomePickerTags {
    const val RECENT = "settings_new_chat_recent"
    const val PROJECTS = "settings_new_chat_projects"
    const val COMPOSER = "settings_new_chat_composer"

    fun of(home: NewChatHome): String = when (home) {
        NewChatHome.RECENT -> RECENT
        NewChatHome.PROJECTS -> PROJECTS
        NewChatHome.COMPOSER -> COMPOSER
    }
}

/**
 * Settings › New chat page, as the light/dark appearance pickers of iOS and One UI lay a choice out: each layout of
 * the New Chat pane side by side in miniature — the pane's own composables, drawn from the live list and scaled down
 * ([NewChatPageMiniature]) — its name under it, and the chosen one ringed and checked. A tap chooses, is written at
 * once, and the pane lays itself out that way the next time it is on screen. [pageSize] is the pane's (this screen
 * fills the same one), so a miniature has the page's proportions on a phone and on a tablet alike; [withHeader] is
 * whether the pane has its 44dp header, as it does wherever this screen has one.
 *
 * Without Projects to pin — Extended mode off, outside the demo — Projects can still be chosen, and the row under the
 * miniatures says what the page shows until the mode is on.
 */
@Composable
internal fun NewChatHomeCard(
    graph: AppGraph,
    list: AgentListUiState,
    projectsAvailable: Boolean,
    withHeader: Boolean,
    pageSize: DpSize,
) {
    val scope = rememberCoroutineScope()
    // On the main dispatcher for the reason the Extended mode switch is (see SettingsScreen).
    val chosen by graph.prefs.newChatHome.collectAsStateWithLifecycle(initialValue = null, context = Dispatchers.Main.immediate)
    val chips = rememberComposerChips(graph)
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().selectableGroup().padding(horizontal = RowInset, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NewChatHome.entries.forEach { option ->
                PickerOption(
                    label = NewChatHomePickerCopy.label(option),
                    selected = chosen == option,
                    onClick = { scope.launch { graph.prefs.setNewChatHome(option) } },
                    pageSize = pageSize,
                    modifier = Modifier.weight(1f).testTag(NewChatHomePickerTags.of(option)),
                ) { miniatureModifier ->
                    NewChatPageMiniature(
                        home = option,
                        list = list,
                        projectsAvailable = projectsAvailable,
                        chips = chips,
                        withHeader = withHeader,
                        pageSize = pageSize,
                        modifier = miniatureModifier,
                    )
                }
            }
        }
        if (!projectsAvailable) {
            HairlineDivider()
            SettingsRow(
                title = NewChatHomePickerCopy.NEEDS_MODE,
                description = NewChatHomePickerCopy.NEEDS_MODE_DETAIL,
                role = null,
                leading = { RowGlyph(CursorIcons.project(null)) },
            )
        }
    }
}

/**
 * One layout to choose: its miniature of the page at [pageSize], sized to the column ([miniatureSize]) and ringed in
 * the accent while chosen with a hairline's gap between ring and page, then its name and a round check under it. The
 * whole column is the radio button, read as its name.
 */
@Composable
private fun PickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    pageSize: DpSize,
    modifier: Modifier = Modifier,
    miniature: @Composable (Modifier) -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val ring by animateColorAsState(if (selected) colors.accent else Color.Transparent, tween(160), label = "ring")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(CursorTheme.shapes.xl)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = ripple(color = colors.base),
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val size = miniatureSize(pageSize, maxWidth - RingInset * 2)
            val radius = miniatureRadius(size.width)
            Box(
                Modifier
                    .width(size.width + RingInset * 2)
                    .border(RingWidth, ring, RoundedCornerShape(radius + RingInset))
                    .padding(RingInset),
            ) {
                val page = RoundedCornerShape(radius)
                miniature(
                    Modifier
                        .size(size)
                        .clip(page)
                        .border(CursorDimens.hairline, colors.strokeSubtle, page)
                        .clearAndSetSemantics { },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = type.base,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        CheckCircle(selected)
    }
}

/** The round check under a choice: filled in the accent with a check while chosen, an empty hairline circle otherwise. */
@Composable
private fun CheckCircle(checked: Boolean) {
    val colors = CursorTheme.colors
    val fill by animateColorAsState(if (checked) colors.accent else Color.Transparent, tween(160), label = "check")
    Box(
        Modifier
            .size(20.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, if (checked) Color.Transparent else colors.strokeStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(CursorIcons.Check, null, tint = colors.onAccent, modifier = Modifier.size(13.dp))
    }
}

/**
 * A miniature's size in [room] (its option's width, less the ring): nine tenths of it, so the three stand apart, but
 * never wider than [MiniatureMaxWidth] nor taller than [MiniatureMaxHeight] — a large phone's, a foldable's and a
 * tablet's thirds are wider than a phone's, and an upright page is tall, which drawn at the full width made each
 * miniature loom over the card. Its height is the [page]'s at that width.
 */
internal fun miniatureSize(page: DpSize, room: Dp): DpSize {
    val aspect = page.height / page.width
    val width = minOf(room * 0.9f, MiniatureMaxWidth, MiniatureMaxHeight / aspect).coerceAtLeast(0.dp)
    return DpSize(width, width * aspect)
}

/**
 * A miniature's corner: a twentieth of its width, between 4dp and 6dp — a screen's corner at the scale it is drawn.
 * The ring's corner is this plus the gap and the ring, so the two stay concentric and the ring is no rounder than the
 * card around it.
 */
internal fun miniatureRadius(width: Dp): Dp = (width * 0.05f).coerceIn(4.dp, 6.dp)

internal val MiniatureMaxWidth = 132.dp
internal val MiniatureMaxHeight = 172.dp
private val RingWidth = 2.dp
private val RingGap = 2.dp
private val RingInset = RingWidth + RingGap

/**
 * The page a miniature draws: the pane's width up to the New Chat page's column and its gutters, and its height up
 * to 1.75 times that width — the first screenful. A pane wider than that column only adds empty margin either side
 * of the same column, which scaled down left the column a sliver in the middle of a mostly empty miniature.
 */
internal fun miniaturePage(pane: DpSize): DpSize {
    val width = pane.width.coerceIn(320.dp, MiniaturePageMaxWidth)
    return DpSize(width, pane.height.coerceIn(width * 0.9f, width * 1.75f))
}

private val MiniaturePageMaxWidth = CursorDimens.composerMaxWidth + CursorDimens.pageGutter * 2
