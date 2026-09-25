package com.cursorforandroid.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FadingLazyColumn
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.time.format.TextStyle as DateTextStyle

/**
 * The Project tab, as cursor.com's right panel opens a Project: the Project's glyph and name, and at the end the
 * toggle between the tab's two faces — its notes (`notes.md` at the root of its store, set as the web sets them) and
 * All Files (the Context stores as trees, the newest files as Recents under them). Each read's state is named: on
 * its way, nothing written yet, a store this mode cannot read, a read that failed.
 */
@Composable
internal fun ProjectTabContent(state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    LaunchedEffect(state.agentId, state.capabilities) { actions.loadContext() }
    val root = state.projectRoot
    val name = root?.name ?: state.parentAgent?.name?.takeIf { state.agent?.parent != null } ?: state.agent?.name ?: "Project"
    val allFiles = state.context.allFiles
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inset = contentInset(maxWidth)
        // Each face keeps its own scroll: the notes and the files are different pages that share a header.
        key(allFiles) {
            FadingLazyColumn(Modifier.fillMaxSize().testTag(if (allFiles) "all-files-tab" else "project-notes-tab"), contentPadding = PaddingValues(bottom = 28.dp)) {
                item("header") { ProjectHeader(name, root, allFiles, inset, onToggle = { actions.openProject(allFiles = !allFiles) }) }
                if (allFiles) allFilesItems(state, actions, inset) else notesItems(state, actions, inset)
            }
        }
    }
}

/** The web's header: the Project's glyph on the content's edge, its name beside it, the All Files toggle at the end, on its fill while the files show. */
@Composable
private fun ProjectHeader(name: String, root: Agent?, allFiles: Boolean, inset: Dp, onToggle: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.base
    Row(
        Modifier.fillMaxWidth().padding(start = inset, end = (inset - 6.dp).coerceAtLeast(8.dp), top = 18.dp).height(38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.project(root?.projectAppearance?.icon), null, tint = colors.projectTone(root?.projectAppearance?.colorId), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = PanelType.title(),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() }.testTag("project-notes-title"),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(shape)
                .background(if (allFiles) colors.fillSoft else Color.Transparent)
                .pressable(onToggle, shape, role = Role.Switch)
                .semantics { contentDescription = "All Files"; selected = allFiles }
                .testTag("project-all-files-toggle"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanelIcons.Notebook, null, tint = if (allFiles) colors.iconPrimary else colors.iconTertiary, modifier = Modifier.size(15.dp))
        }
    }
}

/** The notes under the header, in the panel's markdown (see [PanelMarkdown]); a title line repeating the header is left out. */
private fun LazyListScope.notesItems(state: PanelState, actions: PanelActions, inset: Dp) {
    item("notes") {
        val edge = Modifier.padding(horizontal = inset - PanelGutter, vertical = 10.dp)
        when (val notes = state.context.notes) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading the Project's notes…", edge)
            is RemoteLoad.Unsupported -> ContextUnavailable(notes.reason, state, actions, edge)
            is RemoteLoad.Failed -> FailedRow(notes.message, onRetry = if (notes.retryable) ({ actions.loadContext(force = true) }) else null, modifier = edge)
            is RemoteLoad.Loaded -> {
                val document = notes.value
                if (document == null) {
                    EmptyRow("No notes yet", "The coordinator writes the Project's notes to notes.md in its Context as the work moves.", edge)
                } else {
                    PanelMarkdown(
                        withoutLeadingTitle(document.text),
                        base = StoreBase.of(document.store.storeId, document.path),
                        modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, top = 26.dp).testTag("project-notes-body"),
                    )
                }
            }
        }
    }
}

/**
 * All Files, as the web draws it under the header: its caption, the Context stores as trees — the Project's under
 * "Project", the user's under "User", each entry with when it was last written at the end — then Recents, the newest
 * files across the stores. A file opens as a document tab; a picture as a media tab.
 */
private fun LazyListScope.allFilesItems(state: PanelState, actions: PanelActions, inset: Dp) {
    val context = state.context
    val edge = Modifier.padding(horizontal = inset - PanelGutter)
    item("caption") { TreeCaption("All Files", inset, top = 24.dp) }
    when (val stores = context.stores) {
        RemoteLoad.Idle, RemoteLoad.Loading -> item("loading") { LoadingRow("Listing the stores…", edge) }
        is RemoteLoad.Unsupported -> item("unsupported") { ContextUnavailable(stores.reason, state, actions, edge) }
        is RemoteLoad.Failed -> item("failed") { FailedRow(stores.message, onRetry = if (stores.retryable) ({ actions.loadContext(force = true) }) else null, modifier = edge) }
        is RemoteLoad.Loaded -> {
            val roots = stores.value
            if (roots.isEmpty) item("empty") { EmptyRow("No Context yet", "A Project's Context and your own files show here once the account lists a store.", edge) }
            roots.project?.let { store -> storeTree(store, if (state.projectRoot != null) "Project" else "This chat", state, actions, inset) }
            roots.user?.let { store -> storeTree(store, "User", state, actions, inset) }
        }
    }
    if (context.stores is RemoteLoad.Loaded) {
        item("recents-caption") { TreeCaption("Recents", inset, top = 34.dp) }
        item("recents") {
            when (val recents = context.recents) {
                RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Finding the newest files…", edge)
                is RemoteLoad.Unsupported -> PanelNote(recents.reason, edge)
                is RemoteLoad.Failed -> FailedRow(recents.message, onRetry = if (recents.retryable) ({ actions.loadContext(force = true) }) else null, modifier = edge)
                is RemoteLoad.Loaded -> if (recents.value.isEmpty()) PanelNote("Nothing written yet.", edge) else RecentsRow(recents.value, actions, inset)
            }
        }
    }
}

/** "All Files", "Recents": the web's small semibold captions over the tree and the row. */
@Composable
private fun TreeCaption(text: String, inset: Dp, top: Dp) {
    Text(
        text,
        style = CursorTheme.typography.small.copy(fontWeight = FontWeight.SemiBold),
        color = CursorTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(start = inset, end = inset, top = top, bottom = 16.dp).semantics { heading() },
    )
}

/**
 * The panel's content inset, as the web's is a share of its panel's width (32px of a 775px panel): 16dp on a phone's
 * sheet, the web's 32 on a panel as wide as its own.
 */
internal fun contentInset(width: Dp): Dp = (width * 0.042f).coerceIn(PanelGutter, 32.dp)

/** One store's tree: its root row — the open folder and the store's name — then its entries as its folders open. */
private fun LazyListScope.storeTree(store: AgentStoreRef, label: String, state: PanelState, actions: PanelActions, inset: Dp) {
    val expanded = state.context.isExpanded(store, "")
    item("root:${store.storeId}") {
        TreeRow(
            name = label,
            icon = if (expanded) PanelIcons.FolderOpen else CursorIcons.Folder,
            depth = 0,
            detail = null,
            onClick = { actions.toggleFolder(store, "") },
            inset = inset,
            description = if (expanded) "$label, open" else label,
            modifier = Modifier.testTag("store-root"),
        )
    }
    if (expanded) folderItems(store, "", 1, state, actions, inset)
}

private fun LazyListScope.folderItems(store: AgentStoreRef, path: String, depth: Int, state: PanelState, actions: PanelActions, inset: Dp) {
    val context = state.context
    val nested = Modifier.padding(start = inset - PanelGutter + TreeIndent * depth)
    when (val listing = context.listing(store, path)) {
        RemoteLoad.Idle, RemoteLoad.Loading -> item("loading:${store.storeId}:$path") { LoadingRow("Listing…", nested) }
        is RemoteLoad.Unsupported -> item("unsupported:${store.storeId}:$path") { PanelNote(listing.reason, nested) }
        is RemoteLoad.Failed -> item("failed:${store.storeId}:$path") {
            FailedRow(listing.message, onRetry = if (listing.retryable) ({ actions.toggleFolder(store, path); actions.toggleFolder(store, path) }) else null, modifier = nested)
        }
        is RemoteLoad.Loaded -> {
            if (listing.value.isEmpty()) item("empty:${store.storeId}:$path") { PanelNote("Empty folder", nested) }
            listing.value.forEach { entry ->
                val key = "entry:${store.storeId}:${entry.relativePath}"
                val written = entry.updatedAtMillis?.let { writtenAt(it) }
                if (entry.isDirectory) {
                    val open = context.isExpanded(store, entry.relativePath)
                    item(key) {
                        TreeRow(
                            name = entry.name,
                            icon = if (open) PanelIcons.FolderOpen else CursorIcons.Folder,
                            depth = depth,
                            detail = written,
                            onClick = { actions.toggleFolder(store, entry.relativePath) },
                            inset = inset,
                            description = if (open) "Folder ${entry.name}, open" else "Folder ${entry.name}",
                            modifier = Modifier.testTag("tree-folder"),
                        )
                    }
                    if (open) folderItems(store, entry.relativePath, depth + 1, state, actions, inset)
                } else {
                    item(key) {
                        val picture = RecentContextFile.isImageName(entry.name)
                        TreeRow(
                            name = entry.name,
                            icon = if (picture) CursorIcons.Image else CursorIcons.File,
                            depth = depth,
                            detail = written,
                            onClick = { actions.openDocument(store, entry.relativePath) },
                            inset = inset,
                            description = "File ${entry.name}",
                            modifier = Modifier.testTag("tree-file"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of the tree as the web draws it: the entry's glyph, its name, and at the end when it was written — "Today
 * at 2:37 AM", "Friday at 3:19 AM" — in the quaternary colour. No chevrons: a folder opens on its row. Rows are the
 * web's 34px apart; each level steps 14px in.
 */
@Composable
private fun TreeRow(
    name: String,
    icon: ImageVector,
    depth: Int,
    detail: String?,
    onClick: () -> Unit,
    inset: Dp,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .height(TreeRowHeight)
            .pressable(onClick, CursorTheme.shapes.base, role = Role.Button)
            .padding(start = inset + TreeIndent * depth, end = inset)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.iconSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(name, style = TreeName, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) {
            Spacer(Modifier.width(12.dp))
            Text(detail, style = type.base, color = colors.textQuaternary, maxLines = 1)
        }
    }
}

/** The tree's names as the web sets them: 14px on a 20px line. */
private val TreeName = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

/**
 * When a file was last written, as the web's tree puts it: "Today at 2:37 AM", "Yesterday at 5:56 AM", the weekday
 * within the week ("Friday at 3:19 AM"), else the date ("Sep 2 at 11:04 PM"), with its year when not this one.
 */
internal fun writtenAt(millis: Long, now: Long = AppClock.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val locale = Locale.getDefault()
    val then = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(then)
    val day = when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days in 2..6 -> then.dayOfWeek.getDisplayName(DateTextStyle.FULL, locale)
        then.year == today.year -> DateTimeFormatter.ofPattern("MMM d", locale).format(then)
        else -> DateTimeFormatter.ofPattern("MMM d, yyyy", locale).format(then)
    }
    return "$day at $time"
}

/** The notes without a title line that would repeat the header above them: a `# Heading` the file opens with. */
internal fun withoutLeadingTitle(markdown: String): String {
    val trimmed = markdown.trimStart()
    if (!trimmed.startsWith("# ")) return markdown
    return trimmed.substringAfter('\n', "").trimStart('\n')
}

/** The Recents row: the newest files across the stores, a tile each, sideways. */
@Composable
private fun RecentsRow(recents: List<RecentContextFile>, actions: PanelActions, inset: Dp) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = inset).testTag("recents-row"),
        horizontalArrangement = Arrangement.spacedBy(RecentTileGap),
    ) {
        recents.forEach { recent -> RecentTile(recent, actions) }
    }
}

/**
 * One recent file: the head of the picture when the account presigned one, else the file's glyph on the tile; its
 * name under it, cut short as the web's tiles cut theirs. A picture opens as a media tab, anything else as a document.
 */
@Composable
private fun RecentTile(recent: RecentContextFile, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    val media = LocalMarkdownMedia.current
    val url = recent.thumbnailUrl
    val px = with(LocalDensity.current) { RecentTileWidth.roundToPx() to RecentTileHeight.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url, media) {
        value = if (url == null || media == null) null else runCatching { media.loader.image(MediaRef.parse(url, media.agentId), px.first * 2, px.second * 2).asImageBitmap() }.getOrNull()
    }
    val open = remember(recent, url, actions) {
        {
            if (recent.isImage && url != null) actions.openMedia(url, recent.entry.name) else actions.openDocument(recent.store, recent.entry.relativePath)
        }
    }
    Column(Modifier.width(RecentTileWidth).testTag("recent-tile")) {
        Box(
            Modifier
                .size(RecentTileWidth, RecentTileHeight)
                .cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
                .pressable(open, shape, role = Role.Image)
                .semantics { contentDescription = recent.entry.name },
            contentAlignment = Alignment.Center,
        ) {
            val picture = bitmap
            if (picture != null) {
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().clip(shape).testTag("recent-thumbnail"))
            } else {
                Icon(if (recent.isImage) CursorIcons.Image else iconForExtension(recent.entry.name.substringAfterLast('.', "").lowercase()), null, tint = colors.iconTertiary, modifier = Modifier.size(22.dp))
            }
        }
        Text(recent.entry.name, style = type.base, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The named state a Context tab shows when the stores cannot be read from here, with the way to them on cursor.com. */
@Composable
private fun ContextUnavailable(reason: String, state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    StateRow(
        icon = CursorIcons.Shield,
        title = "Context needs Extended mode",
        detail = reason,
        tint = CursorTheme.colors.orange,
        actionLabel = "Open on cursor.com",
        onAction = { actions.openUrl(state.agent?.url ?: CursorEndpoints.webUrl(state.agentId)) },
        modifier = modifier.testTag("context-unavailable"),
    )
}

/**
 * A Context document as its own tab, as the web opens one: a row with back, the breadcrumb from the store down to the
 * file (the file itself in the text's colour) and `Preview` / `Source` at the end, then the document — markdown set as
 * the notes are under Preview, the text with line numbers under Source. A file that is not markdown shows its source.
 */
@Composable
internal fun DocumentTab(tab: PanelTab.Document, state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    LaunchedEffect(tab.key) { actions.loadDocument(tab) }
    val load = state.context.document(tab)
    val markdown = load.valueOrNull?.isMarkdown ?: (tab.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown"))
    val source = !markdown || state.context.showsSource(tab)
    val store = state.context.stores.valueOrNull?.all?.firstOrNull { it.storeId == tab.storeId }
    val rootLabel = when {
        store?.kind == AgentStoreKind.USER -> "User"
        state.projectRoot != null -> state.projectRoot?.name ?: "Project"
        else -> state.agent?.name ?: "Chat"
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("document-tab")) {
        val inset = contentInset(maxWidth)
        Column(Modifier.fillMaxSize()) {
            TabBar(
                onBack = actions::back,
                trailing = {
                    if (markdown) {
                        Spacer(Modifier.width(8.dp))
                        SegmentToggle(options = listOf("Preview", "Source"), selected = if (source) 1 else 0, onSelect = { actions.setDocumentSource(tab, it == 1) })
                    }
                },
            ) {
                Breadcrumb(rootLabel, pathSegments(tab.path), Modifier.weight(1f, fill = false))
            }
            val edge = Modifier.padding(horizontal = inset - PanelGutter, vertical = 10.dp)
            when (load) {
                RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading ${tab.name}…", edge)
                is RemoteLoad.Unsupported -> ContextUnavailable(load.reason, state, actions, edge)
                is RemoteLoad.Failed -> FailedRow(load.message, onRetry = if (load.retryable) ({ actions.loadDocument(tab, force = true) }) else null, modifier = edge)
                is RemoteLoad.Loaded -> DocumentBody(load.value, source, inset)
            }
        }
    }
}

@Composable
private fun DocumentBody(document: ContextDocument, source: Boolean, inset: Dp) {
    if (source) {
        TextFile(document.text, truncated = false, modifier = Modifier.testTag("document-source"))
    } else {
        FadingLazyColumn(Modifier.fillMaxSize().testTag("document-preview"), contentPadding = PaddingValues(start = inset, end = inset, top = 18.dp, bottom = 28.dp)) {
            item { PanelMarkdown(document.text, base = StoreBase.of(document.store.storeId, document.path)) }
        }
    }
}

/** The web's back arrow at the start of a tab's second row: to the tab the reader came from. */
@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlatIconButton(
        PanelIcons.ArrowLeft,
        "Back",
        onClick = onClick,
        size = 32.dp,
        iconSize = 15.dp,
        tint = CursorTheme.colors.iconSecondary,
        modifier = modifier.testTag("panel-back"),
    )
}

/** Two or three words in one row, the picked one on a soft fill: the web's `Preview` `Source`. */
@Composable
internal fun SegmentToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(modifier.testTag("segment-toggle"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            val picked = index == selected
            Text(
                option,
                style = type.base,
                color = if (picked) colors.textPrimary else colors.textTertiary,
                modifier = Modifier
                    .clip(shape)
                    .background(if (picked) colors.fillSoft else Color.Transparent)
                    .pressable({ onSelect(index) }, shape, role = Role.Tab)
                    .semantics { this.selected = picked }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("segment-$option"),
            )
        }
    }
}

/** A tree row's height, one level's step in, and the Recents tile, as measured on cursor.com. */
private val TreeRowHeight = 34.dp
private val TreeIndent = 14.dp
private val RecentTileWidth = 112.dp
private val RecentTileHeight = 140.dp
private val RecentTileGap = 16.dp
