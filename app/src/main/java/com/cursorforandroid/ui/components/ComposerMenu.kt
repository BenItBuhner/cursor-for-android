package com.cursorforandroid.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.BuiltInSkills
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpServerForm
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.Skill
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import java.util.UUID

/** What the composer's "+" menu can reach. Screens wire these to their view model and stores. */
class ComposerMenuActions(
    /** "Files": opens the system picker (the API takes images only). */
    val onPickFiles: () -> Unit,
    /** Project / synced skill names typed before, most recent first. */
    val recentSkills: List<String> = emptyList(),
    /** Called with a project / synced skill name the user picked, so it can be remembered. */
    val onSkillUsed: (String) -> Unit = {},
    val mcpServers: List<McpServer> = emptyList(),
    val onToggleMcpServer: (McpServer, Boolean) -> Unit = { _, _ -> },
    val onSaveMcpServer: (McpServer) -> Unit = {},
    val onDeleteMcpServer: (McpServer) -> Unit = {},
)

private enum class MenuPage { Root, Skills, McpServers }

/**
 * The menu behind the composer's "+" as it appears on cursor.com/agents: Multitask, then Files, Skills › and
 * MCP Servers ›. The web's flyout submenus become pages that slide in over the root; Multitask and skills toggle a
 * slash command at the front of the prompt, Files opens the picker, MCP servers are managed here and sent inline.
 */
@Composable
fun ComposerPlusMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    actions: ComposerMenuActions,
) {
    val colors = CursorTheme.colors
    var page by remember(expanded) { mutableStateOf(MenuPage.Root) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServer?>(null) }

    fun toggleCommand(name: String) {
        onPromptChange(SlashCommands.toggle(prompt, name))
        onDismiss()
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 4.dp),
        shape = CursorTheme.shapes.lg,
        containerColor = colors.elevated,
        border = BorderStroke(CursorDimens.hairline, colors.stroke),
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val slide = tween<IntOffset>(PageMillis, easing = LinearOutSlowInEasing)
                val pages =
                    if (targetState != MenuPage.Root) slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it }
                    else slideInHorizontally(slide) { -it } togetherWith slideOutHorizontally(slide) { it }
                pages using SizeTransform(clip = true) { _, _ -> tween(PageMillis, easing = LinearOutSlowInEasing) }
            },
            label = "plus-menu",
        ) { current ->
            Column(Modifier.width(MenuWidth)) {
                when (current) {
                    MenuPage.Root -> RootPage(
                        multitaskOn = SlashCommands.has(prompt, SlashCommands.MULTITASK),
                        onMultitask = { toggleCommand(SlashCommands.MULTITASK) },
                        onFiles = { onDismiss(); actions.onPickFiles() },
                        onSkills = { page = MenuPage.Skills },
                        onMcpServers = { page = MenuPage.McpServers },
                    )
                    MenuPage.Skills -> SkillsPage(
                        prompt = prompt,
                        recent = actions.recentSkills,
                        onBack = { page = MenuPage.Root },
                        onSelect = { skill ->
                            if (BuiltInSkills.byName(skill.name) == null) actions.onSkillUsed(skill.name)
                            toggleCommand(skill.name)
                        },
                    )
                    MenuPage.McpServers -> McpServersPage(
                        servers = actions.mcpServers,
                        onBack = { page = MenuPage.Root },
                        onToggle = actions.onToggleMcpServer,
                        onAdd = { onDismiss(); editing = null; editorOpen = true },
                        onEdit = { onDismiss(); editing = it; editorOpen = true },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        val server = editing
        McpServerSheet(
            server = server,
            others = actions.mcpServers,
            onSave = { actions.onSaveMcpServer(it); editorOpen = false },
            onDelete = server?.let { s -> { actions.onDeleteMcpServer(s); editorOpen = false } },
            onDismiss = { editorOpen = false },
        )
    }
}

private val MenuWidth = 280.dp
private val PageListMaxHeight = 300.dp
private const val PageMillis = 220

@Composable
private fun RootPage(multitaskOn: Boolean, onMultitask: () -> Unit, onFiles: () -> Unit, onSkills: () -> Unit, onMcpServers: () -> Unit) {
    val colors = CursorTheme.colors
    MenuRow(
        icon = CursorIcons.Multitask,
        label = "Multitask",
        subtitle = "Orchestrate multiple subagents in parallel",
        onClick = onMultitask,
        trailing = { if (multitaskOn) Icon(CursorIcons.Check, "On", tint = colors.accent, modifier = Modifier.size(16.dp)) },
    )
    HairlineDivider(Modifier.padding(vertical = 4.dp))
    MenuRow(CursorIcons.Paperclip, "Files", onClick = onFiles)
    MenuRow(CursorIcons.Book, "Skills", onClick = onSkills, trailing = { Chevron() })
    MenuRow(CursorIcons.Plug, "MCP Servers", onClick = onMcpServers, trailing = { Chevron() })
}

@Composable
private fun SkillsPage(prompt: String, recent: List<String>, onBack: () -> Unit, onSelect: (Skill) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by remember { mutableStateOf("") }
    val results = remember(query, recent) { BuiltInSkills.search(query, recent) }

    PageHeader("Skills", onBack)
    MenuSearchField(query, onValueChange = { query = it }, placeholder = "Search or type a skill name")
    HairlineDivider(Modifier.padding(top = 6.dp, bottom = 2.dp))
    Column(Modifier.heightIn(max = PageListMaxHeight).verticalScroll(rememberScrollState())) {
        if (results.isEmpty()) {
            Text("Skill names use lowercase letters, digits and hyphens.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        results.forEach { skill ->
            val on = SlashCommands.has(prompt, skill.name)
            MenuRow(
                icon = null,
                label = skill.command,
                subtitle = skill.description,
                onClick = { onSelect(skill) },
                trailing = { if (on) Icon(CursorIcons.Check, "On", tint = colors.accent, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun McpServersPage(
    servers: List<McpServer>,
    onBack: () -> Unit,
    onToggle: (McpServer, Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (McpServer) -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    PageHeader("MCP Servers", onBack)
    HairlineDivider(Modifier.padding(bottom = 2.dp))
    if (servers.isEmpty()) {
        Text(
            "No MCP servers yet. Add an HTTP or stdio server here and every prompt from this app carries it inline.",
            style = type.small,
            color = colors.textQuaternary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    } else {
        Column(Modifier.heightIn(max = PageListMaxHeight).verticalScroll(rememberScrollState())) {
            servers.forEach { server ->
                MenuRow(
                    icon = CursorIcons.Plug,
                    label = server.name,
                    subtitle = server.summary.ifBlank { server.transport.label },
                    onClick = { onEdit(server) },
                    trailing = {
                        Spacer(Modifier.width(8.dp))
                        CursorToggle(server.enabled, onCheckedChange = { onToggle(server, it) })
                    },
                )
            }
        }
        Text(
            "Enabled servers are sent inline with each prompt. Tap a server to edit it.",
            style = type.small,
            color = colors.textQuaternary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    HairlineDivider(Modifier.padding(vertical = 2.dp))
    MenuRow(CursorIcons.Plus, "Add MCP server", onClick = onAdd)
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = CursorDimens.headerHeight).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack)
        Spacer(Modifier.width(2.dp))
        Text(title, style = CursorTheme.typography.title, color = CursorTheme.colors.textPrimary)
    }
}

@Composable
private fun Chevron() {
    Icon(CursorIcons.ChevronRight, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(16.dp))
}

/** A menu row: optional 18dp glyph, label with optional second line, optional trailing control. */
@Composable
private fun MenuRow(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, RectangleShape)
            .heightIn(min = 34.dp)
            .padding(horizontal = 12.dp, vertical = if (subtitle != null) 7.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = type.small, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun MenuSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(colors.fillFaint, CursorTheme.shapes.base)
            .heightIn(min = 30.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.base.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = type.base, color = colors.textQuaternary, maxLines = 1); inner() } },
        )
    }
}

/**
 * Editor for one MCP server (new when [server] is null). Fields follow the API's inline shape: HTTP servers take a
 * URL and headers, stdio servers a command, arguments and environment variables. Saved definitions live in the
 * encrypted store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerSheet(
    server: McpServer?,
    others: List<McpServer>,
    onSave: (McpServer) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var name by remember { mutableStateOf(server?.name ?: "") }
    var transport by remember { mutableStateOf(server?.transport ?: McpTransport.Http) }
    var url by remember { mutableStateOf(server?.url ?: "") }
    var headers by remember { mutableStateOf(server?.let { McpServerForm.formatHeaders(it.headers) } ?: "") }
    var command by remember { mutableStateOf(server?.command ?: "") }
    var args by remember { mutableStateOf(server?.let { McpServerForm.formatArgs(it.args) } ?: "") }
    var env by remember { mutableStateOf(server?.let { McpServerForm.formatEnv(it.env) } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val parsedHeaders = McpServerForm.parseHeaders(headers).getOrElse { error = it.message; return }
        val parsedEnv = McpServerForm.parseEnv(env).getOrElse { error = it.message; return }
        val candidate = McpServer(
            id = server?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            transport = transport,
            url = url.trim(),
            headers = parsedHeaders,
            command = command.trim(),
            args = McpServerForm.parseArgs(args),
            env = parsedEnv,
            enabled = server?.enabled ?: true,
        )
        McpServerForm.validate(candidate, others)?.let { error = it; return }
        onSave(candidate)
    }

    CursorSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = CursorDimens.headerHeight).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (server == null) "New MCP server" else "Edit MCP server", style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (onDelete != null) {
                    Text("Delete", style = type.base, color = colors.red, modifier = Modifier.pressable(onDelete, CursorTheme.shapes.base).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }

            FieldLabel("Name")
            SheetField(name, { name = it; error = null }, placeholder = "linear", singleLine = true)

            FieldLabel("Transport")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpTransport.entries.forEach { option ->
                    CursorButton(option.label, onClick = { transport = option; error = null }, primary = transport == option)
                }
            }
            Text(
                when (transport) {
                    McpTransport.Http -> "Calls are proxied by Cursor; the agent never sees the headers."
                    McpTransport.Stdio -> "Starts inside the agent's VM, so the command has to be available there."
                },
                style = type.small,
                color = colors.textQuaternary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            when (transport) {
                McpTransport.Http -> {
                    FieldLabel("URL")
                    SheetField(url, { url = it; error = null }, placeholder = "https://mcp.linear.app/mcp", singleLine = true, keyboardType = KeyboardType.Uri)
                    FieldLabel("Headers (one \"Name: value\" per line)")
                    SheetField(headers, { headers = it; error = null }, placeholder = "Authorization: Bearer …", mono = true)
                }
                McpTransport.Stdio -> {
                    FieldLabel("Command")
                    SheetField(command, { command = it; error = null }, placeholder = "npx", singleLine = true, mono = true)
                    FieldLabel("Arguments (one per line)")
                    SheetField(args, { args = it; error = null }, placeholder = "-y\n@modelcontextprotocol/server-github", mono = true)
                    FieldLabel("Environment (one \"NAME=value\" per line)")
                    SheetField(env, { env = it; error = null }, placeholder = "GITHUB_TOKEN=…", mono = true)
                }
            }

            error?.let { Text(it, style = type.small, color = colors.red, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Text(
                "Stored encrypted on this device and sent inline with each prompt while enabled.",
                style = type.small,
                color = colors.textQuaternary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                CursorButton("Cancel", onClick = onDismiss)
                CursorButton("Save", onClick = ::submit, primary = true)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    mono: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val style = if (mono) type.code.copy(fontSize = type.base.fontSize, lineHeight = type.base.lineHeight) else type.base
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        textStyle = style.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.textPrimary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(colors.fillFaint, CursorTheme.shapes.base)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = colors.textQuaternary); inner() } },
    )
}
