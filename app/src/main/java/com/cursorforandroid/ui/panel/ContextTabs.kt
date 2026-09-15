package com.cursorforandroid.ui.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * The "Project" tab: the Project's Context notes (`notes.md` at the root of its Agent Store) rendered as markdown
 * under the Project's icon and name, the way cursor.com's right panel opens a Project. The states around it are
 * named: the read on its way, no notes written yet, a store the mode cannot read, a read that failed.
 */
@Composable
internal fun ProjectNotesTab(state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    LaunchedEffect(state.agentId, state.capabilities) { actions.loadContext() }
    val root = state.projectRoot
    val name = root?.name ?: state.parentAgent?.name?.takeIf { state.agent?.parent != null } ?: state.agent?.name ?: "Project"
    LazyColumn(modifier.fillMaxSize().testTag("project-notes-tab"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(colors.projectTone(root?.projectAppearance?.colorId).copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(CursorIcons.project(root?.projectAppearance?.icon), null, tint = colors.projectTone(root?.projectAppearance?.colorId), modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(name, style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() }.testTag("project-notes-title"))
                (state.context.notes.valueOrNull)?.let { notes ->
                    Text(
                        "Open",
                        style = type.small,
                        color = colors.link,
                        modifier = Modifier.pressable({ actions.openDocument(notes.store, notes.path) }, CursorTheme.shapes.base).padding(horizontal = 6.dp, vertical = 2.dp).testTag("project-notes-open"),
                    )
                }
            }
        }
        item("body") {
            when (val notes = state.context.notes) {
                RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading the Project's notes…")
                is RemoteLoad.Unsupported -> ContextUnavailable(notes.reason, state, actions)
                is RemoteLoad.Failed -> FailedRow(notes.message, onRetry = if (notes.retryable) ({ actions.loadContext(force = true) }) else null)
                is RemoteLoad.Loaded -> {
                    val document = notes.value
                    if (document == null) {
                        EmptyRow("No notes yet", "The coordinator writes the Project's notes to notes.md in its Context as the work moves.")
                    } else {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).testTag("project-notes-body")) {
                            MarkdownText(withoutLeadingTitle(document.text), style = type.message)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "All Files" tab: the Context stores as trees — the Project's under its name, the user's under "User" — each
 * entry with when it was last written, folders opening in place; and, under the trees, "Recents": the newest files
 * across the stores as thumbnails for the pictures and tiles for the rest. A file opens as a document tab; a
 * picture opens in the lightbox.
 */
@Composable
internal fun AllFilesTab(state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    LaunchedEffect(state.agentId, state.capabilities) { actions.loadContext() }
    val context = state.context
    LazyColumn(modifier.fillMaxSize().testTag("all-files-tab"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("caption") { PanelCaption("All Files", Modifier.padding(top = 6.dp)) }
        when (val stores = context.stores) {
            RemoteLoad.Idle, RemoteLoad.Loading -> item("loading") { LoadingRow("Listing the stores…") }
            is RemoteLoad.Unsupported -> item("unsupported") { ContextUnavailable(stores.reason, state, actions) }
            is RemoteLoad.Failed -> item("failed") { FailedRow(stores.message, onRetry = if (stores.retryable) ({ actions.loadContext(force = true) }) else null) }
            is RemoteLoad.Loaded -> {
                val roots = stores.value
                if (roots.isEmpty) item("empty") { EmptyRow("No Context yet", "A Project's Context and your own files appear here once the account lists a store.") }
                roots.project?.let { store -> storeTree(store, state.projectRoot?.name?.let { "Project" } ?: "This chat", state, actions) }
                roots.user?.let { store -> storeTree(store, "User", state, actions) }
            }
        }
        item("recents-caption") { PanelCaption("Recents", Modifier.padding(top = 14.dp)) }
        item("recents") {
            when (val recents = context.recents) {
                RemoteLoad.Idle, RemoteLoad.Loading -> if (context.stores is RemoteLoad.Loaded) LoadingRow("Finding the newest files…") else Spacer(Modifier.height(4.dp))
                is RemoteLoad.Unsupported -> RecentsFallback(state, actions)
                is RemoteLoad.Failed -> FailedRow(recents.message, onRetry = if (recents.retryable) ({ actions.loadContext(force = true) }) else null)
                is RemoteLoad.Loaded -> if (recents.value.isEmpty()) PanelNote("Nothing written yet.") else RecentsRow(recents.value, actions)
            }
        }
    }
}

/** One store's tree: its root row, then its entries as the folders open. */
private fun androidx.compose.foundation.lazy.LazyListScope.storeTree(store: AgentStoreRef, label: String, state: PanelState, actions: PanelActions) {
    val context = state.context
    val rootExpanded = context.isExpanded(store, "")
    item("root:${store.storeId}") {
        TreeRow(
            name = label,
            icon = CursorIcons.Folder,
            iconTint = if (store.kind == AgentStoreKind.USER) CursorTheme.colors.accent else CursorTheme.colors.iconSecondary,
            depth = 0,
            expanded = rootExpanded,
            detail = null,
            onClick = { actions.toggleFolder(store, "") },
            modifier = Modifier.testTag("store-root"),
        )
    }
    if (rootExpanded) folderItems(store, "", 1, state, actions)
}

private fun androidx.compose.foundation.lazy.LazyListScope.folderItems(store: AgentStoreRef, path: String, depth: Int, state: PanelState, actions: PanelActions) {
    val context = state.context
    when (val listing = context.listing(store, path)) {
        RemoteLoad.Idle, RemoteLoad.Loading -> item("loading:${store.storeId}:$path") { LoadingRow("Listing…", Modifier.padding(start = (depth * TreeIndent.value).dp)) }
        is RemoteLoad.Unsupported -> item("unsupported:${store.storeId}:$path") { PanelNote(listing.reason) }
        is RemoteLoad.Failed -> item("failed:${store.storeId}:$path") { FailedRow(listing.message, onRetry = if (listing.retryable) ({ actions.toggleFolder(store, path); actions.toggleFolder(store, path) }) else null) }
        is RemoteLoad.Loaded -> {
            if (listing.value.isEmpty()) item("empty:${store.storeId}:$path") { PanelNote("Empty folder", Modifier.padding(start = (depth * TreeIndent.value).dp)) }
            listing.value.forEach { entry ->
                val key = "entry:${store.storeId}:${entry.relativePath}"
                if (entry.isDirectory) {
                    val expanded = context.isExpanded(store, entry.relativePath)
                    item(key) {
                        TreeRow(
                            name = entry.name,
                            icon = CursorIcons.Folder,
                            iconTint = CursorTheme.colors.iconSecondary,
                            depth = depth,
                            expanded = expanded,
                            detail = entry.updatedAtMillis?.let(::writtenAt),
                            onClick = { actions.toggleFolder(store, entry.relativePath) },
                            modifier = Modifier.testTag("tree-folder"),
                        )
                    }
                    if (expanded) folderItems(store, entry.relativePath, depth + 1, state, actions)
                } else {
                    item(key) {
                        TreeRow(
                            name = entry.name,
                            icon = iconForExtension(entry.name.substringAfterLast('.', "").lowercase()),
                            iconTint = CursorTheme.colors.iconTertiary,
                            depth = depth,
                            expanded = null,
                            detail = entry.updatedAtMillis?.let(::writtenAt),
                            onClick = { actions.openDocument(store, entry.relativePath) },
                            modifier = Modifier.testTag("tree-file"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of the tree: a chevron for a folder (down when open), the entry's glyph, its name, and at the end edge
 * when it was written — "Today at 2:37 AM", "Friday at 3:19 AM" — in the quaternary colour, as the web writes it.
 */
@Composable
private fun TreeRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    depth: Int,
    expanded: Boolean?,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick, CursorTheme.shapes.base, role = Role.Button)
            .heightIn(min = 30.dp)
            .padding(start = 8.dp + TreeIndent * depth, end = 12.dp, top = 3.dp, bottom = 3.dp)
            .semantics { contentDescription = if (expanded == null) "File $name" else if (expanded) "Folder $name, open" else "Folder $name" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded != null) {
            Icon(if (expanded) CursorIcons.ChevronDown else CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(12.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Spacer(Modifier.width(6.dp))
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = type.tiny, color = colors.textQuaternary, maxLines = 1)
        }
    }
}

/** The notes without a title line that would repeat the header above them: a `# Heading` the file opens with. */
internal fun withoutLeadingTitle(markdown: String): String {
    val trimmed = markdown.trimStart()
    if (!trimmed.startsWith("# ")) return markdown
    return trimmed.substringAfter('\n', "").trimStart('\n')
}

/** "Today at 2:37 AM", "Yesterday at 5:56 AM", "Friday at 3:19 AM", "Sep 2 at 11:04 PM": the web's file times. */
internal fun writtenAt(millis: Long): String = TimeFormat.dayAndTime(millis)

/** The Recents row: the newest files across the stores, a thumbnail each, sideways. */
@Composable
private fun RecentsRow(recents: List<RecentContextFile>, actions: PanelActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp).testTag("recents-row"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        recents.forEach { recent -> RecentTile(recent, actions) }
    }
}

/**
 * One recent file: the picture itself when the account presigned it, else a tile with the file's glyph; the name
 * under it, ellipsised the way the web's tiles ellipsise. A picture opens in the lightbox, anything else as a tab.
 */
@Composable
private fun RecentTile(recent: RecentContextFile, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    val media = LocalMarkdownMedia.current
    val url = recent.thumbnailUrl
    val density = LocalDensity.current
    val px = with(density) { RecentTileWidth.roundToPx() to RecentTileHeight.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url, media) {
        value = if (url == null || media == null) null else runCatching { media.loader.image(MediaRef.parse(url, media.agentId), px.first * 2, px.second * 2).asImageBitmap() }.getOrNull()
    }
    val open: () -> Unit = {
        val picture = bitmap
        if (url != null && picture != null) media?.lightbox?.open(url, recent.entry.name, picture) else actions.openDocument(recent.store, recent.entry.relativePath)
    }
    Column(Modifier.width(RecentTileWidth).testTag("recent-tile"), horizontalAlignment = Alignment.Start) {
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
                Image(picture, contentDescription = "Thumbnail of ${recent.entry.name}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
            } else {
                Icon(
                    if (recent.isImage) CursorIcons.Image else iconForExtension(recent.entry.name.substringAfterLast('.', "").lowercase()),
                    null,
                    tint = colors.iconTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(recent.entry.name, style = type.tiny, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Recents where the stores cannot be read (Extended mode off): what this chat produced — its artifacts and
 * generated images — stands in, since those the documented API does give.
 */
@Composable
private fun RecentsFallback(state: PanelState, actions: PanelActions) {
    val tiles = remember(state.content.media, state.artifacts, state.promptImages) { mediaTiles(state) }
    if (tiles.isEmpty()) {
        PanelNote("This chat's pictures and recordings would show here; it has none yet.")
        return
    }
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    val media = LocalMarkdownMedia.current
    val density = LocalDensity.current
    val px = with(density) { RecentTileWidth.roundToPx() to RecentTileHeight.roundToPx() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp).testTag("recents-row"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.filterIsInstance<MediaTile.Image>().take(8).forEach { tile ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, tile.src, media) {
                value = if (media == null) null else runCatching { media.loader.image(MediaRef.parse(tile.src, media.agentId), px.first * 2, px.second * 2).asImageBitmap() }.getOrNull()
            }
            Column(Modifier.width(RecentTileWidth).testTag("recent-tile")) {
                Box(
                    Modifier.size(RecentTileWidth, RecentTileHeight).cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
                        .pressable({ bitmap?.let { media?.lightbox?.open(tile.src, tile.caption, it) } ?: actions.openUrl(tile.src) }, shape, role = Role.Image),
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = bitmap
                    if (picture != null) Image(picture, contentDescription = tile.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
                    else Icon(CursorIcons.Image, tile.caption, tint = colors.iconTertiary, modifier = Modifier.size(22.dp))
                }
                Text(tile.caption, style = type.tiny, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The named state a Context tab shows when the stores cannot be read from here, with the way to them on cursor.com. */
@Composable
private fun ContextUnavailable(reason: String, state: PanelState, actions: PanelActions) {
    StateRow(
        icon = CursorIcons.Shield,
        title = "Context needs Extended mode",
        detail = reason,
        tint = CursorTheme.colors.orange,
        actionLabel = "Open on cursor.com",
        onAction = { actions.openUrl(state.agent?.url ?: com.cursorforandroid.data.api.CursorEndpoints.webUrl(state.agentId)) },
        modifier = Modifier.testTag("context-unavailable"),
    )
}

/**
 * A Context document as its own tab: the breadcrumb (the store's root, then each folder, then the file), the
 * `Preview` / `Source` toggle at the end, and the document — markdown rendered under Preview, the text with line
 * numbers under Source. A file that is not markdown opens on its source and has no preview.
 */
@Composable
internal fun DocumentTab(tab: PanelTab.Document, state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    LaunchedEffect(tab.key) { actions.loadDocument(tab) }
    val load = state.context.document(tab)
    val document = load.valueOrNull
    val markdown = document?.isMarkdown ?: (tab.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown"))
    val source = !markdown || state.context.showsSource(tab)
    val store = state.context.stores.valueOrNull?.all?.firstOrNull { it.storeId == tab.storeId }
    val rootLabel = when {
        store?.kind == AgentStoreKind.USER -> "User"
        state.projectRoot != null -> state.projectRoot?.name ?: "Project"
        else -> state.agent?.name ?: "Chat"
    }
    Column(modifier.fillMaxSize().testTag("document-tab")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val segments = tab.path.split('/').filter { it.isNotEmpty() }
                Text(rootLabel, style = type.small, color = colors.textTertiary, maxLines = 1)
                segments.forEachIndexed { index, segment ->
                    Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.padding(horizontal = 3.dp).size(11.dp))
                    Text(segment, style = type.small, color = if (index == segments.lastIndex) colors.textPrimary else colors.textTertiary, maxLines = 1)
                }
            }
            if (markdown) {
                Spacer(Modifier.width(8.dp))
                SegmentToggle(
                    options = listOf("Preview", "Source"),
                    selected = if (source) 1 else 0,
                    onSelect = { actions.setDocumentSource(tab, it == 1) },
                )
            }
        }
        HairlineDivider()
        when (load) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading ${tab.name}…")
            is RemoteLoad.Unsupported -> ContextUnavailable(load.reason, state, actions)
            is RemoteLoad.Failed -> FailedRow(load.message, onRetry = if (load.retryable) ({ actions.loadDocument(tab, force = true) }) else null)
            is RemoteLoad.Loaded -> DocumentBody(load.value, source)
        }
    }
}

@Composable
private fun DocumentBody(document: ContextDocument, source: Boolean) {
    if (source) {
        TextFile(document.text, truncated = false, modifier = Modifier.testTag("document-source"))
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("document-preview"), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            item { MarkdownText(document.text, style = CursorTheme.typography.message) }
        }
    }
}

/** Two or three words in one pill, the picked one on the panel's surface: the web's `Preview | Source`. */
@Composable
internal fun SegmentToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(modifier.background(colors.fillFaint, shape).padding(2.dp).testTag("segment-toggle"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, option ->
            val picked = index == selected
            Text(
                option,
                style = type.small,
                color = if (picked) colors.textPrimary else colors.textTertiary,
                modifier = Modifier
                    .background(if (picked) colors.fillSoft else Color.Transparent, shape)
                    .pressable({ onSelect(index) }, shape, role = Role.Tab)
                    .semantics { this.contentDescription = option }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("segment-$option"),
            )
        }
    }
}

private val TreeIndent = 14.dp
private val RecentTileWidth = 104.dp
private val RecentTileHeight = 84.dp

/** Whether a name is one of the pictures a store tile shows as a thumbnail. */
internal fun ContextEntry.isPicture(): Boolean = name.substringAfterLast('.', "").lowercase() in RecentContextFile.IMAGE_EXTENSIONS
