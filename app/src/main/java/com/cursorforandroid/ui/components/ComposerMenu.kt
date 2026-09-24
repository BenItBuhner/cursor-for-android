package com.cursorforandroid.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.ConnectorStatus
import com.cursorforandroid.domain.McpConnector
import com.cursorforandroid.domain.McpConnectors
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpServerForm
import com.cursorforandroid.domain.McpTransport
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import com.cursorforandroid.domain.SlashCommands
import com.cursorforandroid.ui.agents.LocalMediaLoader
import com.cursorforandroid.ui.icons.ProjectIcons
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import java.util.UUID

/** What the composer's "+" menu can reach. Screens wire these to their view model and stores. */
class ComposerMenuActions(
    /**
     * "Images and videos" (Extended mode) or "Images" (default): the Android photo picker. In the default mode it
     * offers images alone, since the documented `prompt.images[]` is all the request can carry; in Extended mode it
     * offers videos too, and whatever it returns goes up as a real file.
     */
    val onPickMedia: () -> Unit,
    /**
     * "Files": the system document picker for files of any type — PDFs, archives, code, anything — up to 15 MB each
     * (Extended mode). Null hides the row, as in the default mode.
     */
    val onPickFiles: (() -> Unit)? = null,
    /** Project / synced skill names typed before, most recent first. */
    val recentSkills: List<String> = emptyList(),
    /** Called with a project / synced skill name the user picked, so it can be remembered. */
    val onSkillUsed: (String) -> Unit = {},
    val mcpServers: List<McpServer> = emptyList(),
    val onToggleMcpServer: (McpServer, Boolean) -> Unit = { _, _ -> },
    val onSaveMcpServer: (McpServer) -> Unit = {},
    val onDeleteMcpServer: (McpServer) -> Unit = {},
    /** The account's connectors (Extended mode). When set, the MCP page lists them in place of the servers above. */
    val connectors: ConnectorMenu? = null,
)

/**
 * The MCP page's account list, as the MCP dropdown on cursor.com/agents shows it: every server and connector the
 * account can use, a switch that applies to all of its cloud agents, and a sign-in for the ones that need it.
 */
class ConnectorMenu(
    val connectors: List<McpConnector>,
    val loading: Boolean = false,
    /** The list could not be read. */
    val error: String? = null,
    /** The last switch did not stick. */
    val notice: String? = null,
    /** The page opened: read the list again. */
    val onOpen: () -> Unit = {},
    val onToggle: (McpConnector, Boolean) -> Unit = { _, _ -> },
    /** Sign in to a connector, in the browser. */
    val onConnect: (McpConnector) -> Unit = {},
    /** Where servers are added and configured: cursor.com. */
    val onManage: () -> Unit = {},
)

private enum class MenuPage { Root, Skills, McpServers }

/**
 * The menu behind the composer's "+" as it appears on cursor.com/agents: Multitask, then the pickers, Skills › and
 * MCP Servers ›. The web's flyout submenus become pages that slide in over the root; Multitask and skills toggle a
 * slash command at the front of the prompt, "Images and videos" opens the photo picker and "Files" the document
 * picker (Extended mode; the default mode has "Images" alone). The MCP page switches the account's connectors in
 * Extended mode ([ComposerMenuActions.connectors]); otherwise it manages the app's own servers, sent inline.
 */
@Composable
fun ComposerPlusMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    actions: ComposerMenuActions,
    /** What the Skills page lists: the composer's `/` catalog, the same one its popover completes from. */
    commands: SlashCatalog = SlashCatalog.BUILT_IN,
) {
    var page by remember(expanded) { mutableStateOf(MenuPage.Root) }
    // The editor is a form the user fills in by pasting from another app, which is exactly when the process is most
    // likely to be killed. Only the edited server's id is saved, not the server: it is re-read from the store below
    // so credentials never reach the instance-state bundle.
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    fun toggleCommand(name: String) {
        onPromptChange(SlashCommands.toggle(prompt, name))
        onDismiss()
    }

    // Anchored to the "+" disc, [CursorDimens.composerPadding] in from the composer's side: the menu's start edge
    // drops from the disc's, so its corners are concentric with the composer's (see [CursorDimens.menuRadius]).
    CursorMenu(expanded = expanded, onDismissRequest = onDismiss) {
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
                        onMedia = { onDismiss(); actions.onPickMedia() },
                        onFiles = actions.onPickFiles?.let { pick -> { onDismiss(); pick() } },
                        onSkills = { page = MenuPage.Skills },
                        onMcpServers = { page = MenuPage.McpServers },
                    )
                    MenuPage.Skills -> SkillsPage(
                        prompt = prompt,
                        catalog = commands,
                        recent = actions.recentSkills,
                        onBack = { page = MenuPage.Root },
                        onSelect = { skill ->
                            // A name the catalog does not list — typed, or picked before — is remembered for next time.
                            if (commands.byName(skill.name) == null) actions.onSkillUsed(skill.name)
                            toggleCommand(skill.name)
                        },
                    )
                    MenuPage.McpServers -> {
                        val connectors = actions.connectors
                        if (connectors != null) {
                            ConnectorsPage(connectors, onBack = { page = MenuPage.Root }, onManage = { onDismiss(); connectors.onManage() })
                        } else {
                            McpServersPage(
                                servers = actions.mcpServers,
                                onBack = { page = MenuPage.Root },
                                onToggle = actions.onToggleMcpServer,
                                onAdd = { onDismiss(); editingId = null; editorOpen = true },
                                onEdit = { onDismiss(); editingId = it.id; editorOpen = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val server = editingId?.let { id -> actions.mcpServers.firstOrNull { it.id == id } }
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

/** The media row's label in Extended mode, where the photo picker offers videos too. */
const val MEDIA_LABEL_IMAGES_AND_VIDEOS = "Images and videos"
/** The media row's label in the default mode, where the picker is filtered to images — all `prompt.images[]` takes. */
const val MEDIA_LABEL_IMAGES = "Images"
/** The document picker's row (Extended mode). */
const val FILES_LABEL = "Files"

@Composable
private fun RootPage(multitaskOn: Boolean, onMultitask: () -> Unit, onMedia: () -> Unit, onFiles: (() -> Unit)?, onSkills: () -> Unit, onMcpServers: () -> Unit) {
    val colors = CursorTheme.colors
    CursorMenuItem(
        "Multitask",
        CursorIcons.Multitask,
        subtitle = "Orchestrate multiple subagents in parallel",
        trailing = { if (multitaskOn) Icon(CursorIcons.Check, "On", tint = colors.accent, modifier = Modifier.size(CursorDimens.menuIcon)) },
        onClick = onMultitask,
    )
    CursorMenuSeparator()
    // Two pickers, each named for what it opens on: the gallery (images alone in the default mode, where the
    // documented request takes nothing else) and the document picker for every other kind of file (Extended mode).
    if (onFiles == null) {
        CursorMenuItem(MEDIA_LABEL_IMAGES, CursorIcons.Image, onClick = onMedia)
    } else {
        CursorMenuItem(MEDIA_LABEL_IMAGES_AND_VIDEOS, CursorIcons.Image, onClick = onMedia)
        CursorMenuItem(FILES_LABEL, CursorIcons.Paperclip, subtitle = "Any type, up to ${PromptFile.MAX_BYTES / (1024 * 1024)} MB each", onClick = onFiles)
    }
    CursorMenuItem("Skills", CursorIcons.Book, trailing = { Chevron() }, onClick = onSkills)
    CursorMenuItem("MCP Servers", CursorIcons.Plug, trailing = { Chevron() }, onClick = onMcpServers)
}

@Composable
private fun SkillsPage(prompt: String, catalog: SlashCatalog, recent: List<String>, onBack: () -> Unit, onSelect: (SlashCommand) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var query by remember { mutableStateOf("") }
    // The page is about skills; the commands (`/goal`, `/multitask`) have their own rows and the `/` popover.
    val results = remember(query, recent, catalog) { catalog.search(query, recent).filter { it.kind == SlashCommand.Kind.Skill } }

    PageHeader("Skills", onBack)
    MenuSearchField(query, onValueChange = { query = it }, placeholder = "Search or type a skill name")
    CursorMenuSeparator(Modifier.padding(top = 2.dp))
    Column(Modifier.heightIn(max = PageListMaxHeight).fadingVerticalScroll(surface = colors.elevated)) {
        if (results.isEmpty()) {
            Text("Skill names use lowercase letters, digits and hyphens.", style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(horizontal = CursorDimens.menuTextInset, vertical = 10.dp))
        }
        results.forEach { skill ->
            val on = SlashCommands.has(prompt, skill.name)
            CursorMenuItem(
                skill.command,
                icon = null,
                subtitle = skill.summary,
                trailing = { if (on) Icon(CursorIcons.Check, "On", tint = colors.accent, modifier = Modifier.size(CursorDimens.menuIcon)) },
            ) { onSelect(skill) }
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
    CursorMenuSeparator()
    if (servers.isEmpty()) {
        Text(
            "No MCP servers yet. Add an HTTP or stdio server here and every prompt from this app carries it inline.",
            style = type.small,
            color = colors.textQuaternary,
            modifier = Modifier.padding(horizontal = CursorDimens.menuTextInset, vertical = 8.dp),
        )
    } else {
        Column(Modifier.heightIn(max = PageListMaxHeight).fadingVerticalScroll(surface = colors.elevated)) {
            servers.forEach { server ->
                CursorMenuItem(
                    server.name,
                    CursorIcons.Plug,
                    subtitle = server.summary.ifBlank { server.transport.label },
                    trailing = { CursorToggle(server.enabled, onCheckedChange = { onToggle(server, it) }) },
                ) { onEdit(server) }
            }
        }
        Text(
            "Enabled servers are sent inline with each prompt. Tap a server to edit it.",
            style = type.small,
            color = colors.textQuaternary,
            modifier = Modifier.padding(horizontal = CursorDimens.menuTextInset, vertical = 6.dp),
        )
    }
    CursorMenuSeparator()
    CursorMenuItem("Add MCP server", CursorIcons.Plus, onClick = onAdd)
}

/** Where cursor.com adds and configures the account's MCP servers; the page's last row opens it. */
const val CONNECTORS_MANAGE_URL = "https://cursor.com/agents"

@Composable
private fun ConnectorsPage(menu: ConnectorMenu, onBack: () -> Unit, onManage: () -> Unit) {
    val colors = CursorTheme.colors
    LaunchedEffect(Unit) { menu.onOpen() }
    PageHeader("MCP Servers", onBack)
    CursorMenuSeparator()
    val rows = menu.connectors
    when {
        rows.isNotEmpty() -> Column(Modifier.heightIn(max = PageListMaxHeight).fadingVerticalScroll(surface = colors.elevated)) {
            rows.forEach { connector ->
                ConnectorRow(connector, onToggle = { menu.onToggle(connector, it) }, onConnect = { menu.onConnect(connector) })
            }
        }
        menu.error != null -> PageNote("Couldn't load your MCP servers. ${menu.error}", colors.red)
        menu.loading -> PageNote("Loading your MCP servers…")
        else -> PageNote("No MCP servers on your account yet. Add them on cursor.com/agents, or ask a team admin to share some.")
    }
    menu.notice?.let { PageNote(it, colors.red) }
    if (rows.isNotEmpty()) PageNote("Switches apply to every cloud agent on your account, wherever it starts.")
    CursorMenuSeparator()
    CursorMenuItem("Manage on cursor.com", CursorIcons.ExternalLink, onClick = onManage)
}

@Composable
private fun PageNote(text: String, color: Color = CursorTheme.colors.textQuaternary) {
    Text(text, style = CursorTheme.typography.small, color = color, modifier = Modifier.padding(horizontal = CursorDimens.menuTextInset, vertical = 6.dp))
}

/**
 * One connector: its logo (the brand's glyph until the account's logo has loaded, or instead of it), its name and
 * state, "Connect" when it waits for a sign-in, and the account-wide switch. A connector the team requires or has
 * switched off keeps its switch, dimmed, so the state still reads.
 */
@Composable
private fun ConnectorRow(connector: McpConnector, onToggle: (Boolean) -> Unit, onConnect: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val subtitleColor = when {
        connector.blockedByAdmin || !connector.enabled -> colors.textQuaternary
        connector.status == ConnectorStatus.Connected -> colors.green
        connector.status == ConnectorStatus.NeedsAuth -> colors.orange
        connector.status == ConnectorStatus.Error -> colors.red
        else -> colors.textTertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.menuInset)
            .heightIn(min = CursorDimens.menuRow)
            .padding(horizontal = CursorDimens.menuItemPadding, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectorLogo(connector)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(connector.name, style = type.base, color = if (connector.blockedByAdmin) colors.textQuaternary else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(connector.statusLabel, style = type.small, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (connector.canConnect) {
            Spacer(Modifier.width(6.dp))
            Text(
                "Connect",
                style = type.small,
                color = colors.accent,
                modifier = Modifier.pressable(onConnect, CursorTheme.shapes.base).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        CursorToggle(connector.enabled, onCheckedChange = onToggle, enabled = connector.canToggle)
    }
}

@Composable
private fun ConnectorLogo(connector: McpConnector) {
    val colors = CursorTheme.colors
    val loader = LocalMediaLoader.current
    val url = connector.logoUrl
    val px = with(LocalDensity.current) { ConnectorLogoSize.roundToPx() }
    val logo by produceState<ImageBitmap?>(initialValue = null, url, loader, px) {
        value = if (url == null || loader == null) null else runCatching { loader.image(MediaRef.Remote(url), px, px).asImageBitmap() }.getOrNull()
    }
    Box(Modifier.size(ConnectorLogoSize), contentAlignment = Alignment.Center) {
        val image = logo
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(ConnectorLogoSize).clip(CursorTheme.shapes.base))
        } else {
            val tint = if (connector.blockedByAdmin) colors.iconQuaternary else colors.iconSecondary
            Icon(ProjectIcons.vector(McpConnectors.glyph(connector)), null, tint = tint, modifier = Modifier.size(CursorDimens.menuIcon))
        }
    }
}

private val ConnectorLogoSize = 18.dp

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
    Icon(CursorIcons.ChevronRight, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(CursorDimens.menuIcon))
}

@Composable
private fun MenuSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CursorDimens.menuInset)
            .stylusWriting()
            .background(colors.fillFaint, CursorTheme.shapes.menuItem)
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
    // Saved, so tabbing away to a password manager mid-form does not come back to an empty sheet. Headers and env
    // deliberately are not: they carry bearer tokens and API keys, and the instance-state bundle is written to disk.
    var name by rememberSaveable(server?.id) { mutableStateOf(server?.name ?: "") }
    var transport by rememberSaveable(server?.id) { mutableStateOf(server?.transport ?: McpTransport.Http) }
    var url by rememberSaveable(server?.id) { mutableStateOf(server?.url ?: "") }
    var headers by remember(server?.id) { mutableStateOf(server?.let { McpServerForm.formatHeaders(it.headers) } ?: "") }
    var command by rememberSaveable(server?.id) { mutableStateOf(server?.command ?: "") }
    var args by rememberSaveable(server?.id) { mutableStateOf(server?.let { McpServerForm.formatArgs(it.args) } ?: "") }
    var env by remember(server?.id) { mutableStateOf(server?.let { McpServerForm.formatEnv(it.env) } ?: "") }
    var error by rememberSaveable(server?.id) { mutableStateOf<String?>(null) }

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
        Column(Modifier.fillMaxWidth().fadingVerticalScroll().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
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
            .stylusWriting()
            .background(colors.fillFaint, CursorTheme.shapes.base)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = style, color = colors.textQuaternary); inner() } },
    )
}
