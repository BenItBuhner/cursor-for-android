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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
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
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

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

/** The Project's icon and colour: the Agents Window's icon names and its ten tones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSheet(current: ProjectAppearance?, onPick: (ProjectAppearance) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var icon by rememberSaveable { mutableStateOf(current?.icon ?: PROJECT_ICONS.first()) }
    var colorId by rememberSaveable { mutableStateOf(current?.colorId ?: PROJECT_COLORS.first()) }
    CursorSheet(onDismiss = onDismiss) { dismiss ->
        SheetHeader("Icon and colour")
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROJECT_ICONS.forEach { name ->
                    val selected = name == icon
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(if (selected) colors.fillMedium else colors.fillFaint, CircleShape)
                            .then(if (selected) Modifier.border(CursorDimens.hairline, colors.projectTone(colorId), CircleShape) else Modifier)
                            .pressable({ icon = name }, CircleShape)
                            .semantics { contentDescription = "Icon $name" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CursorIcons.project(name), null, tint = colors.projectTone(colorId), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROJECT_COLORS.forEach { id ->
                    val selected = id == colorId
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(colors.projectTone(id).copy(alpha = if (selected) 1f else 0.55f), CircleShape)
                            .then(if (selected) Modifier.border(2.dp, colors.textPrimary, CircleShape) else Modifier)
                            .pressable({ colorId = id }, CircleShape)
                            .semantics { contentDescription = "Colour $id" },
                    )
                }
            }
            Text("Shows on desktop and cursor.com as well as here.", style = type.small, color = colors.textQuaternary)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                CursorButton("Cancel", onClick = dismiss)
                Spacer(Modifier.width(8.dp))
                CursorButton("Save", primary = true, onClick = { onPick(ProjectAppearance(icon, colorId)); dismiss() })
            }
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

/** The icon names the Agents Window offers a Project (the ones this app draws; see `CursorIcons.project`). */
internal val PROJECT_ICONS = listOf(
    "lightning", "rocket", "star", "flag", "code", "database", "shield", "heart", "moon", "server", "calendar", "link", "bug",
    "folder", "terminal", "git-branch", "book-open", "file-text", "globe", "target", "cloud", "image", "sparkle", "layers",
)

/** The ten tones of the Projects palette, by `colorId`. */
internal val PROJECT_COLORS = listOf("default", "green", "cyan", "blue", "purple", "magenta", "orange", "yellow", "red", "brand")
