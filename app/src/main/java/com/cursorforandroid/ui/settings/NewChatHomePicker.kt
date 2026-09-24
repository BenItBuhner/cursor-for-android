package com.cursorforandroid.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
    val maxWidth = miniatureMaxWidth(pageSize)
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
                    maxWidth = maxWidth,
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
 * One layout to choose: its miniature, at most [maxWidth] wide and ringed in the accent while chosen with a
 * hairline's gap between ring and page, then its name and a round check under it. The whole column is the radio
 * button, read as its name.
 */
@Composable
private fun PickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    maxWidth: Dp,
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
        Box(
            Modifier
                .widthIn(max = maxWidth + RingGap * 2 + RingWidth * 2)
                .fillMaxWidth()
                .border(RingWidth, ring, RoundedCornerShape(MiniatureRadius + RingGap + RingWidth))
                .padding(RingWidth + RingGap),
        ) {
            val page = RoundedCornerShape(MiniatureRadius)
            miniature(
                Modifier
                    .fillMaxWidth()
                    .clip(page)
                    .border(CursorDimens.hairline, colors.strokeSubtle, page)
                    .clearAndSetSemantics { },
            )
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
 * A miniature's widest: a phone's pane at a little under two fifths, and a wider pane's at three tenths, so a
 * tablet's page is drawn no smaller for its size than a phone's — whatever the width the card gives it.
 */
internal fun miniatureMaxWidth(page: DpSize): Dp = maxOf(PhoneMiniatureWidth, page.width * 0.3f)

private val PhoneMiniatureWidth = 156.dp
private val MiniatureRadius = 12.dp
private val RingWidth = 2.dp
private val RingGap = 3.dp

/** The pane's proportions for a miniature: its width, and its height up to 1.75 times that — the first screenful. */
internal fun miniaturePage(pane: DpSize): DpSize {
    val width = pane.width.coerceAtLeast(320.dp)
    return DpSize(width, pane.height.coerceIn(width * 0.9f, width * 1.75f))
}
