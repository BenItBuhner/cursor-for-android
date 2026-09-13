package com.cursorforandroid.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ProjectAppearance
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.home.SheetRow
import com.cursorforandroid.ui.home.SheetSearchField
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ProjectPalette

/** Which of the Project screen's sheets is open; a `Serializable`, so `rememberSaveable` keeps it up across a rotation. */
sealed interface ProjectSheet : java.io.Serializable {
    data object NewWorker : ProjectSheet { private fun readResolve(): Any = NewWorker }
    data object Adopt : ProjectSheet { private fun readResolve(): Any = Adopt }
    data object NewSideChat : ProjectSheet { private fun readResolve(): Any = NewSideChat }
    data object Appearance : ProjectSheet { private fun readResolve(): Any = Appearance }
    data class Steer(val agentId: String, val name: String) : ProjectSheet
    data class Move(val agentId: String, val name: String) : ProjectSheet
}

/** A new primary: what to tell it, and what to call it. The repository and branch are the coordinator's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewWorkerSheet(root: Agent?, onLaunch: (prompt: String, name: String?) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var prompt by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("New primary")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetField(value = prompt, onValueChange = { prompt = it }, placeholder = "What should this agent do?", minLines = 4, label = "Prompt")
            SheetField(value = name, onValueChange = { name = it }, placeholder = "Name (optional)", minLines = 1, label = "Name")
            val where = listOfNotNull(root?.repoSlug, root?.startingRef?.takeIf { it.isNotBlank() }).joinToString(" \u00B7 ")
            Text(
                if (where.isNotEmpty()) "Runs in $where, like the coordinator, and reports to it." else "Runs where the coordinator runs and reports to it.",
                style = type.small, color = colors.textQuaternary,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                CursorButton("Cancel", onClick = dismiss)
                Spacer(Modifier.width(8.dp))
                CursorButton("Start", primary = true, enabled = prompt.isNotBlank(), onClick = { onLaunch(prompt, name); dismiss() })
            }
        }
    }
}

/** A chat of the account's to bring into the Project, searched by name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdoptSheet(candidates: List<Agent>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("Adopt a chat")
        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Search your chats")
        Spacer(Modifier.height(6.dp))
        val visible = candidates.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.repoSlug?.contains(query.trim(), ignoreCase = true) == true }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(visible, key = { it.id }) { agent ->
                SheetRow(title = agent.name, subtitle = listOfNotNull(agent.repoShortName, agent.branchName).joinToString(" \u00B7 ").ifBlank { null }, checked = false, icon = CursorIcons.Layers) {
                    onPick(agent.id)
                    dismiss()
                }
            }
            if (visible.isEmpty()) {
                item("none") { Text(if (query.isBlank()) "No chats to adopt." else "No chats match \u201C$query\u201D", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
            }
            item("note") {
                Text("The chat becomes one of this Project's primaries: the coordinator can message it and read its transcript.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

/** Another Project to move a primary under, as its cloud subagent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveSheet(workerName: String, projects: List<Agent>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("Move $workerName under\u2026")
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(projects, key = { it.id }) { project ->
                SheetRow(title = project.name, subtitle = project.repoSlug, checked = false, icon = CursorIcons.project(project.projectAppearance?.icon)) {
                    onPick(project.id)
                    dismiss()
                }
            }
            if (projects.isEmpty()) {
                item("none") { Text("No other Project to move it under.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }
            }
        }
    }
}

/** One line of text with a confirm button: a side chat's name, and the like. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NameSheet(title: String, placeholder: String, action: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader(title)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetField(value = value, onValueChange = { value = it }, placeholder = placeholder, minLines = 1, label = null)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                CursorButton("Cancel", onClick = dismiss)
                Spacer(Modifier.width(8.dp))
                CursorButton(action, primary = true, onClick = { onConfirm(value.takeIf { it.isNotBlank() }); dismiss() })
            }
        }
    }
}

/** A steer: a few words the running agent reads at its next step, without ending its turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteerSheet(workerName: String, onSteer: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var text by rememberSaveable { mutableStateOf("") }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("Steer $workerName")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetField(value = text, onValueChange = { text = it }, placeholder = "Change course without stopping the turn\u2026", minLines = 3, label = null)
            Text("Delivered into the running turn at the agent's next step; a follow-up would wait for the turn to end.", style = type.small, color = colors.textQuaternary)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                CursorButton("Cancel", onClick = dismiss)
                Spacer(Modifier.width(8.dp))
                CursorButton("Steer", primary = true, enabled = text.isNotBlank(), onClick = { onSteer(text); dismiss() })
            }
        }
    }
}

/**
 * The Project's icon and colour, with the whole catalog the Agents Window offers: its ten tones in a row, and every
 * icon in sections, searched the way the desktop searches them (see [ProjectIcons.search]). The grid is tinted with
 * the chosen tone so each candidate is seen as it would sit in the sidebar; the header previews the pair. An icon
 * the account set that this build cannot draw is kept as it is unless another is chosen, so recolouring never
 * replaces a newer Cursor's icon with an older one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(current: ProjectAppearance?, onPick: (ProjectAppearance) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var icon by rememberSaveable { mutableStateOf(ProjectIcons.canonical(current?.icon) ?: current?.icon ?: ProjectIcons.PICKER_DEFAULT_ICON) }
    var colorId by rememberSaveable { mutableStateOf(current?.colorId?.takeIf(ProjectPalette::isKnown) ?: ProjectPalette.DEFAULT_ID) }
    var query by rememberSaveable { mutableStateOf("") }
    val tone = colors.projectTone(colorId)
    val sections = remember(query) {
        ProjectIcons.groups.map { group -> group.copy(ids = ProjectIcons.search(query, within = group.ids)) }.filter { it.ids.isNotEmpty() }
    }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("Icon and colour")
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(tone.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CursorIcons.project(icon), "Chosen icon", tint = tone, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ProjectIcons.label(icon), style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(ProjectPalette.label(colorId), if (ProjectIcons.isKnown(icon)) null else "as set on desktop").joinToString(" \u00B7 "),
                    style = type.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Ten 30 dp swatches fit a 360 dp phone with room to spare; narrower ones scroll.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProjectPalette.tones.forEach { candidate ->
                val selected = candidate.id == colorId
                val swatch = colors.projectTone(candidate.id)
                Box(
                    Modifier
                        .size(30.dp)
                        .background(swatch.copy(alpha = if (selected) 1f else 0.55f), CircleShape)
                        .then(if (selected) Modifier.border(2.dp, colors.textPrimary, CircleShape) else Modifier)
                        .pressable({ colorId = candidate.id }, CircleShape)
                        .semantics { contentDescription = "Colour ${candidate.label}"; this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(CursorIcons.Check, null, tint = if (candidate.id == ProjectPalette.DEFAULT_ID) colors.canvas else Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SheetSearchField(value = query, onValueChange = { query = it }, placeholder = "Search ${ProjectIcons.ids.size} icons")
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 44.dp),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).semantics { contentDescription = "Icons" },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (sections.isEmpty()) {
                item("none", span = { GridItemSpan(maxLineSpan) }) {
                    Text("No icons match \u201C${query.trim()}\u201D", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
                }
            }
            sections.forEach { section ->
                item("header:${section.label}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(section.label, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp))
                }
                items(section.ids, key = { it }) { candidate ->
                    val selected = candidate == icon
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(if (selected) colors.fillMedium else Color.Transparent, CircleShape)
                            .then(if (selected) Modifier.border(CursorDimens.hairline, tone, CircleShape) else Modifier)
                            .pressable({ icon = candidate }, CircleShape)
                            .semantics { contentDescription = "Icon ${ProjectIcons.label(candidate)}"; this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CursorIcons.project(candidate), null, tint = tone, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shows on desktop and cursor.com as well as here.", style = type.small, color = colors.textQuaternary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            CursorButton("Cancel", onClick = dismiss)
            Spacer(Modifier.width(8.dp))
            CursorButton("Save", primary = true, onClick = { onPick(ProjectAppearance(icon, colorId)); dismiss() })
        }
    }
}

/** A file of the Project's shared context, as text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextFileSheet(file: OpenContextFile, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorSheet(onDismiss = onDismiss) { _ ->
        SheetHeader(file.entry.name)
        Text(file.entry.relativePath, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
        HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        SelectionContainer {
            Text(
                file.text.ifBlank { "(empty file)" },
                style = type.code,
                color = colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            )
        }
    }
}

/** The sheets' text field: a labelled box in the composer's idiom. */
@Composable
private fun SheetField(value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int, label: String?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Column {
        if (label != null) Text(label, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.fillFaint, shape)
                .border(CursorDimens.hairline, colors.strokeSubtle, shape)
                .heightIn(min = 38.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = minLines,
                textStyle = type.base.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary); inner() }
                },
            )
        }
    }
}
