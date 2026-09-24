package com.cursorforandroid.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.AccountModel
import com.cursorforandroid.domain.AgentIndicator
import com.cursorforandroid.domain.AgentRow
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.ModelResolution
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Dot
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.RunningGlyph
import com.cursorforandroid.ui.components.SelectorChip
import com.cursorforandroid.ui.components.SelectorRow
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.compose.NewAgentUiState
import com.cursorforandroid.ui.compose.NewAgentViewModel
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.roundToInt

/** The New Chat pane's words that tests and the Settings picker have to agree on. */
object NewChatHomeCopy {
    const val PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
    const val NO_CHATS = "No chats yet"
    const val NEEDS_EXTENDED_TITLE = "Projects need Extended mode"
    const val NEEDS_EXTENDED_DETAIL = "Turn it on in Settings to pin your Projects here. Your recent agents are below until then."
    const val OPEN_SETTINGS = "Open Settings"
    const val NO_PROJECTS_TITLE = "No Projects yet"
    const val NO_PROJECTS_DETAIL = "A Project you create is pinned here. Your recent agents are below until then."
    const val NEW_PROJECT = "New Project"

    /** A shortcut's second line: what is working in the Project, else how many chats it holds. */
    fun status(row: AgentRow): String {
        val working = row.workingCount
        val chats = row.shownCount
        return when {
            working > 0 -> "$working working"
            chats == 1 -> "1 chat"
            chats > 1 -> "$chats chats"
            else -> "Idle"
        }
    }
}

object NewChatHomeTags {
    const val PROJECT_SHORTCUT = "new_chat_project_shortcut"
    const val PROJECTS_NOTE = "new_chat_projects_note"
}

/** What a Projects note says instead of the shortcuts: the mode that has them is off, or there are none yet. */
internal enum class ProjectsNote { NeedsExtendedMode, NoProjects }

/**
 * One piece of what the New Chat pane lists under its composer. The pane's lazy list and the Settings miniature of it
 * are both built from [homeBlocks], so the two can never list different things in a different order.
 */
internal sealed interface HomeBlock {
    val key: String

    data class Chat(val row: AgentRow) : HomeBlock {
        override val key: String get() = row.agent.id
    }

    /** Nothing to list: "No chats yet", or — in red — why the list could not be read. */
    data class Empty(val error: String?) : HomeBlock {
        override val key: String get() = "empty"
    }

    data class Projects(val rows: List<AgentRow>) : HomeBlock {
        override val key: String get() = "projects"
    }

    data class Note(val note: ProjectsNote) : HomeBlock {
        override val key: String get() = "projects-note"
    }
}

/**
 * The pane under its composer for [home]. Recent: the recent chats, or the empty line once the list has loaded.
 * Projects: the Project shortcuts; with none to show — Extended mode off, or none created — a note saying so over the
 * recent chats, so the page is never left bare; a failed first read is the error line, as the recents show it. Null
 * [home] (the preference not read yet) lists nothing, rather than one layout and then the other.
 */
internal fun homeBlocks(home: NewChatHome?, list: AgentListUiState, projectsAvailable: Boolean): List<HomeBlock> {
    fun recent(): List<HomeBlock> = when {
        list.recentRows.isNotEmpty() -> list.recentRows.map(HomeBlock::Chat)
        list.hasLoaded -> listOf(HomeBlock.Empty(list.error))
        else -> emptyList()
    }
    return when {
        home == null -> emptyList()
        home == NewChatHome.RECENT -> recent()
        !projectsAvailable -> listOf(HomeBlock.Note(ProjectsNote.NeedsExtendedMode)) + recent()
        list.projectRows.isNotEmpty() -> listOf(HomeBlock.Projects(list.projectRows))
        !list.hasLoaded -> emptyList()
        list.error != null -> listOf(HomeBlock.Empty(list.error))
        else -> listOf(HomeBlock.Note(ProjectsNote.NoProjects)) + recent()
    }
}

/** What a [HomeBlock] opens. */
internal class HomeBlockActions(
    val onOpenAgent: (AgentRow) -> Unit,
    val rowActions: AgentRowActions?,
    val onNewProject: (() -> Unit)?,
    val onOpenSettings: (() -> Unit)?,
)

@Composable
internal fun HomeBlockView(block: HomeBlock, nowMillis: Long, actions: HomeBlockActions) {
    val colors = CursorTheme.colors
    val column = Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()
    // The Projects and their notes line up with the recent chats' cards, inside the composer's edges.
    val inset = column.padding(horizontal = CursorDimens.recentRowInset)
    when (block) {
        is HomeBlock.Chat -> RecentChatRow(
            block.row,
            onClick = { actions.onOpenAgent(block.row) },
            actions = actions.rowActions,
            modifier = column,
            nowMillis = nowMillis,
        )
        is HomeBlock.Empty -> Text(
            block.error ?: NewChatHomeCopy.NO_CHATS,
            style = CursorTheme.typography.base,
            color = if (block.error != null) colors.red else colors.textQuaternary,
            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(top = 24.dp, start = 7.dp, end = 7.dp),
        )
        is HomeBlock.Projects -> ProjectShortcutGrid(block.rows, onOpen = actions.onOpenAgent, modifier = inset)
        is HomeBlock.Note -> ProjectsNoteCard(
            block.note,
            onAction = when (block.note) {
                ProjectsNote.NeedsExtendedMode -> actions.onOpenSettings
                ProjectsNote.NoProjects -> actions.onNewProject
            },
            modifier = inset.padding(bottom = 12.dp),
        )
    }
}

/** The shortcuts, two to a row on a phone and three once the column is wide enough for three to keep their names. */
@Composable
internal fun ProjectShortcutGrid(rows: List<AgentRow>, onOpen: ((AgentRow) -> Unit)?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val columns = if (maxWidth >= 520.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(ShortcutGap)) {
            rows.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(ShortcutGap)) {
                    line.forEach { row ->
                        ProjectShortcut(row, onClick = onOpen?.let { open -> { open(row) } }, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A Project pinned to the New Chat pane: its icon in its colour on a tint of it, as the Project's own view heads it;
 * its name; and what is working in it — the working glyph in the Project's colour and "2 working" — or how many chats
 * it holds. An unread Project carries the unread dot where the glyph would be, a failed one the red dot.
 */
@Composable
internal fun ProjectShortcut(row: AgentRow, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val agent = row.agent
    val appearance = agent.projectAppearance
    val tone = colors.projectTone(appearance?.colorId)
    val shape = CursorTheme.shapes.xl
    val press = if (onClick != null) Modifier.pressable(onClick, shape) else Modifier
    CursorCard(modifier.testTag(NewChatHomeTags.PROJECT_SHORTCUT).then(press), shape = shape, contentPadding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(tone.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CursorIcons.project(appearance?.icon), null, tint = tone, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            when {
                row.workingCount > 0 -> RunningGlyph(size = 16.dp, color = tone)
                row.indicator == AgentIndicator.Unread -> Dot(colors.unreadDot, size = CursorDimens.recentDot)
                row.indicator == AgentIndicator.Error -> Dot(colors.red, size = CursorDimens.recentDot)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(agent.name, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            NewChatHomeCopy.status(row),
            style = type.small,
            color = if (row.workingCount > 0) colors.textSecondary else colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Why the Projects page lists no shortcuts, and the one thing that would change it, over the recent chats. */
@Composable
private fun ProjectsNoteCard(note: ProjectsNote, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val (title, detail, action) = when (note) {
        ProjectsNote.NeedsExtendedMode -> Triple(NewChatHomeCopy.NEEDS_EXTENDED_TITLE, NewChatHomeCopy.NEEDS_EXTENDED_DETAIL, NewChatHomeCopy.OPEN_SETTINGS)
        ProjectsNote.NoProjects -> Triple(NewChatHomeCopy.NO_PROJECTS_TITLE, NewChatHomeCopy.NO_PROJECTS_DETAIL, NewChatHomeCopy.NEW_PROJECT)
    }
    CursorCard(modifier.testTag(NewChatHomeTags.PROJECTS_NOTE), shape = CursorTheme.shapes.xl, contentPadding = PaddingValues(14.dp)) {
        Row {
            Box(Modifier.size(32.dp).background(colors.fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(CursorIcons.project(null), null, tint = colors.iconTertiary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.rowMedium, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(detail, style = type.small, color = colors.textTertiary)
                if (onAction != null) {
                    Spacer(Modifier.height(10.dp))
                    CursorButton(action, onClick = onAction, icon = if (note == ProjectsNote.NoProjects) CursorIcons.Plus else null)
                }
            }
        }
    }
}

/** The New Chat composer's selectors: the source, the branch while there is a repository, and the device. */
@Composable
internal fun NewChatSelectors(
    repoLabel: String,
    noRepo: Boolean,
    branchLabel: String,
    device: DeviceTarget,
    onRepo: () -> Unit,
    onBranch: () -> Unit,
    onDevice: () -> Unit,
) {
    SelectorRow {
        // The source: a repository, or "Start from scratch" as the web composer names a chat without one.
        SelectorChip(repoLabel, onClick = onRepo, icon = if (noRepo) CursorIcons.Cloud else CursorIcons.Repo, modifier = Modifier.weight(1f, fill = false))
        if (!noRepo) {
            SelectorChip(branchLabel, onClick = onBranch, icon = CursorIcons.GitBranch)
        }
        SelectorChip(device.label, onClick = onDevice, icon = deviceIcon(device))
    }
}

/**
 * What a fresh New Chat composer's chips would say, for a miniature of the page drawn away from it: the last launch's
 * repository (else the catalogue's first), branch and device, and the model a new chat would start on — chosen as
 * [NewAgentViewModel] chooses it, the remembered pick against the account's newest chat's model, then Auto.
 */
@Immutable
internal data class ComposerChips(val repoLabel: String, val noRepo: Boolean, val ref: String, val device: DeviceTarget, val modelLabel: String)

@Composable
internal fun rememberComposerChips(graph: AppGraph): ComposerChips {
    val defaults by graph.prefs.composerDefaults.collectAsStateWithLifecycle(initialValue = null)
    val repositories by graph.catalog.repositories.collectAsStateWithLifecycle()
    val models by graph.catalog.models.collectAsStateWithLifecycle()
    val agents by graph.agents.state.collectAsStateWithLifecycle()
    return remember(defaults, repositories, models, agents.agents) {
        val saved = defaults
        val url = saved?.repoUrl
        val repo = url?.let { repositories.firstOrNull { r -> r.url == it } ?: repositories.firstOrNull { r -> r.isAt(it) } } ?: repositories.firstOrNull()
        val remembered = saved?.takeIf { it.modelChosen }?.modelId?.let { ModelResolution.Candidate.Remembered(it, saved.modelParams, saved.modelChosenAtMillis) }
        val model = ModelResolution.forNewChat(models, listOfNotNull(remembered, ModelResolution.newestAccountModel(agents.agents)), settleOnAuto = true)?.choice?.model
        ComposerChips(
            repoLabel = repo?.shortName ?: "Repository",
            noRepo = false,
            ref = saved?.ref.orEmpty(),
            device = saved?.env ?: DeviceTarget.Cloud,
            modelLabel = model?.displayName ?: AccountModel.AUTO_LABEL,
        )
    }
}

/**
 * The New Chat pane in miniature, for the Settings picker: the page as it is laid out at [pageSize] — header, the
 * composer with [chips] on its selectors, and [homeBlocks] for [home] from the live [list] — drawn from the pane's own
 * composables and scaled down to the width it is given. It takes no focus and no touches (the picker's option does),
 * and says nothing to accessibility services, which hear the option's label instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NewChatPageMiniature(
    home: NewChatHome,
    list: AgentListUiState,
    projectsAvailable: Boolean,
    chips: ComposerChips,
    withHeader: Boolean,
    pageSize: DpSize,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val blocks = remember(home, list, projectsAvailable) {
        // A miniature shows a page's first screenful; nothing past it is drawn.
        homeBlocks(home, list, projectsAvailable).take(MiniatureBlocks).map { if (it is HomeBlock.Projects) HomeBlock.Projects(it.rows.take(MiniatureShortcuts)) else it }
    }
    val menu = remember { ComposerMenuActions(onPickMedia = {}) }
    Box(modifier) {
        ScaledPage(pageSize, Modifier.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup()) {
            Column(Modifier.fillMaxSize().background(colors.canvas).consumeWindowInsets(WindowInsets.systemBars)) {
                if (withHeader) CursorHeader(leading = { FlatIconButton(CursorIcons.Sidebar, null, onClick = {}) })
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (withHeader) 8.dp else 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = CursorDimens.composerMaxWidth).fillMaxWidth()) {
                        NewChatSelectors(chips.repoLabel, chips.noRepo, chips.ref.ifBlank { NewAgentUiState.DEFAULT_BRANCH }, chips.device, onRepo = {}, onBranch = {}, onDevice = {})
                        ComposerBox(
                            value = "",
                            onValueChange = {},
                            placeholder = NewChatHomeCopy.PLACEHOLDER,
                            onSend = {},
                            canSend = false,
                            minLines = 3,
                            plusMenu = menu,
                            modelLabel = chips.modelLabel,
                            onModel = {},
                            onModePill = {},
                        )
                    }
                    Spacer(Modifier.height(ComposerGap))
                    blocks.forEach { HomeBlockView(it, nowMillis = list.nowMillis, actions = MiniatureActions) }
                }
            }
        }
        // Hit first, so no row, chip or field of the page underneath takes the touch; left unconsumed, it goes on to
        // whatever holds the miniature.
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial) } })
    }
}

/**
 * [content] laid out at [pageSize] and drawn scaled to the width this is given, its height following in proportion;
 * whatever the page holds past its bottom edge is cut off there.
 */
@Composable
private fun ScaledPage(pageSize: DpSize, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val pageWidth = pageSize.width.roundToPx().coerceAtLeast(1)
        val pageHeight = pageSize.height.roundToPx().coerceAtLeast(1)
        val page = measurables.single().measure(Constraints.fixed(pageWidth, pageHeight))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else pageWidth
        val scale = width.toFloat() / pageWidth
        layout(width, (pageHeight * scale).roundToInt()) {
            page.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Between the composer and what the pane lists under it. */
internal val ComposerGap = 26.dp
private val ShortcutGap = 10.dp
private const val MiniatureBlocks = 12
private const val MiniatureShortcuts = 12

/** Every action the pane offers, so a miniature draws each button the page does; none is reachable through it. */
private val MiniatureActions = HomeBlockActions(onOpenAgent = {}, rowActions = null, onNewProject = {}, onOpenSettings = {})

/** [NewAgentUiState]'s chips as [NewChatSelectors] draws them. */
@Composable
internal fun NewChatSelectors(state: NewAgentUiState, onRepo: () -> Unit, onBranch: () -> Unit, onDevice: () -> Unit) =
    NewChatSelectors(state.repoLabel, state.noRepo, state.branchLabel, state.selectedDevice, onRepo, onBranch, onDevice)
