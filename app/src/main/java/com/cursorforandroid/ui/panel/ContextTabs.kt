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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.domain.AgentStoreKind
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.ContextDocument
import com.cursorforandroid.domain.ContextEntry
import com.cursorforandroid.domain.MediaRef
import com.cursorforandroid.domain.RecentContextFile
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownAppearance
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.media.LocalMediaViewer
import com.cursorforandroid.ui.media.MediaEntry
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

/**
 * The Project tab, as cursor.com's right panel opens a Project: a header row with the Project's icon and name and,
 * at the end, the toggle between the two modes of the tab — the notes (`notes.md` at the root of its Agent Store,
 * rendered as markdown in the notes' own typography) and All Files (the Context stores as trees, the newest files
 * as Recents). The states around the reads are named: on their way, no notes written yet, a store the mode cannot
 * read, a read that failed.
 */
@Composable
internal fun ProjectTabContent(state: PanelState, actions: PanelActions, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    LaunchedEffect(state.agentId, state.capabilities) { actions.loadContext() }
    val root = state.projectRoot
    val name = root?.name ?: state.parentAgent?.name?.takeIf { state.agent?.parent != null } ?: state.agent?.name ?: "Project"
    val allFiles = state.context.allFiles
    BoxWithConstraints(modifier.fillMaxSize()) {
        val inset = contentInset(maxWidth)
        LazyColumn(Modifier.fillMaxSize().testTag(if (allFiles) "all-files-tab" else "project-notes-tab"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("header") {
                // The web's header: the icon 20px outside the content column, the name on its edge, the toggle at the end.
                Row(Modifier.fillMaxWidth().padding(start = (inset - 20.dp).coerceAtLeast(8.dp), end = (inset - 8.dp).coerceAtLeast(8.dp), top = 12.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(CursorIcons.project(root?.projectAppearance?.icon), null, tint = colors.projectTone(root?.projectAppearance?.colorId), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(name, style = PanelTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() }.testTag("project-notes-title"))
                    // The web's toggle beside the name: on its fill while the files are showing.
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(if (allFiles) colors.fillSoft else Color.Transparent, CursorTheme.shapes.base)
                            .pressable({ actions.openProject(allFiles = !allFiles) }, CursorTheme.shapes.base, role = Role.Switch)
                            .semantics { contentDescription = "All Files"; this.selected = allFiles }
                            .testTag("project-all-files-toggle"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CursorIcons.FileText, null, tint = if (allFiles) colors.iconPrimary else colors.iconSecondary, modifier = Modifier.size(15.dp))
                    }
                }
            }
            if (allFiles) allFilesItems(state, actions, inset) else notesItems(state, actions, inset)
        }
    }
}

/** The notes' body: the markdown under the header, in the notes' typography (see [MarkdownAppearance.Notes]). */
private fun LazyListScope.notesItems(state: PanelState, actions: PanelActions, inset: Dp) {
    item("notes") {
        when (val notes = state.context.notes) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading the Project's notes…")
            is RemoteLoad.Unsupported -> ContextUnavailable(notes.reason, state, actions)
            is RemoteLoad.Failed -> FailedRow(notes.message, onRetry = if (notes.retryable) ({ actions.loadContext(force = true) }) else null)
            is RemoteLoad.Loaded -> {
                val document = notes.value
                if (document == null) {
                    EmptyRow("No notes yet", "The coordinator writes the Project's notes to notes.md in its Context as the work moves.")
                } else {
                    Column(Modifier.fillMaxWidth().padding(start = inset, end = inset).testTag("project-notes-body")) {
                        MarkdownText(withoutLeadingTitle(document.text), style = NotesText, appearance = MarkdownAppearance.Notes)
                    }
                }
            }
        }
    }
}

/**
 * All Files, as the web draws it under the Project's header: the caption, the Context stores as trees — the
 * Project's under "Project", the user's under "User", each entry with when it was last written at the end edge —
 * and, under the trees, Recents: the newest files across the stores as thumbnails for the pictures and tiles for
 * the rest. A file opens as a document tab; a picture opens in the lightbox.
 */
private fun LazyListScope.allFilesItems(state: PanelState, actions: PanelActions, inset: Dp) {
    val context = state.context
    item("caption") { TreeCaption("All Files", inset, Modifier.padding(top = 10.dp)) }
    when (val stores = context.stores) {
        RemoteLoad.Idle, RemoteLoad.Loading -> item("loading") { LoadingRow("Listing the stores…") }
        is RemoteLoad.Unsupported -> item("unsupported") { ContextUnavailable(stores.reason, state, actions) }
        is RemoteLoad.Failed -> item("failed") { FailedRow(stores.message, onRetry = if (stores.retryable) ({ actions.loadContext(force = true) }) else null) }
        is RemoteLoad.Loaded -> {
            val roots = stores.value
            if (roots.isEmpty) item("empty") { EmptyRow("No Context yet", "A Project's Context and your own files appear here once the account lists a store.") }
            roots.project?.let { store -> storeTree(store, if (state.projectRoot != null) "Project" else "This chat", state, actions, inset) }
            roots.user?.let { store -> storeTree(store, "User", state, actions, inset) }
        }
    }
    item("recents-caption") { TreeCaption("Recents", inset, Modifier.padding(top = 20.dp)) }
    item("recents") {
        when (val recents = context.recents) {
            RemoteLoad.Idle, RemoteLoad.Loading -> if (context.stores is RemoteLoad.Loaded) LoadingRow("Finding the newest files…") else Spacer(Modifier.height(4.dp))
            is RemoteLoad.Unsupported -> RecentsFallback(state, actions, inset)
            is RemoteLoad.Failed -> FailedRow(recents.message, onRetry = if (recents.retryable) ({ actions.loadContext(force = true) }) else null)
            is RemoteLoad.Loaded -> if (recents.value.isEmpty()) PanelNote("Nothing written yet.") else RecentsRow(recents.value, actions, inset)
        }
    }
}

/** "All Files", "Recents": the web's small bold captions over the tree and the row. */
@Composable
private fun TreeCaption(text: String, inset: Dp, modifier: Modifier = Modifier) {
    Text(
        text,
        style = CursorTheme.typography.baseMedium,
        color = CursorTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(start = inset, end = inset, bottom = 6.dp).semantics { heading() },
    )
}

/**
 * The panel's content inset: the web pads its 800px Project panel by 50px; a phone's sheet cannot spare that, so the
 * inset follows the panel's width between 16dp and the web's 50dp (6.25% of the width, the web's ratio).
 */
internal fun contentInset(width: Dp): Dp = (width * 0.0625f).coerceIn(16.dp, 50.dp)

/** The notes' body text as the web sets it: 14px on a 21px line. */
private val NotesText = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)

/** The Project's name over its notes: 15px semibold, as measured. */
private val PanelTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

/** One store's tree: its root row — the open folder and the store's name, no time — then its entries as the folders open. */
private fun LazyListScope.storeTree(store: AgentStoreRef, label: String, state: PanelState, actions: PanelActions, inset: Dp) {
    val context = state.context
    val rootExpanded = context.isExpanded(store, "")
    item("root:${store.storeId}") {
        TreeRow(
            name = label,
            icon = CursorIcons.FolderOpen,
            iconTint = CursorTheme.colors.iconSecondary,
            depth = 0,
            detail = null,
            onClick = { actions.toggleFolder(store, "") },
            inset = inset,
            modifier = Modifier.testTag("store-root"),
        )
    }
    if (rootExpanded) folderItems(store, "", 1, state, actions, inset)
}

private fun LazyListScope.folderItems(store: AgentStoreRef, path: String, depth: Int, state: PanelState, actions: PanelActions, inset: Dp) {
    val context = state.context
    when (val listing = context.listing(store, path)) {
        RemoteLoad.Idle, RemoteLoad.Loading -> item("loading:${store.storeId}:$path") { LoadingRow("Listing…", Modifier.padding(start = inset + TreeIndent * depth - 12.dp)) }
        is RemoteLoad.Unsupported -> item("unsupported:${store.storeId}:$path") { PanelNote(listing.reason) }
        is RemoteLoad.Failed -> item("failed:${store.storeId}:$path") { FailedRow(listing.message, onRetry = if (listing.retryable) ({ actions.toggleFolder(store, path); actions.toggleFolder(store, path) }) else null) }
        is RemoteLoad.Loaded -> {
            if (listing.value.isEmpty()) item("empty:${store.storeId}:$path") { PanelNote("Empty folder", Modifier.padding(start = inset + TreeIndent * depth - 12.dp)) }
            listing.value.forEach { entry ->
                val key = "entry:${store.storeId}:${entry.relativePath}"
                if (entry.isDirectory) {
                    val expanded = context.isExpanded(store, entry.relativePath)
                    item(key) {
                        TreeRow(
                            name = entry.name,
                            icon = if (expanded) CursorIcons.FolderOpen else CursorIcons.Folder,
                            iconTint = CursorTheme.colors.iconSecondary,
                            depth = depth,
                            detail = entry.updatedAtMillis?.let(::writtenAt),
                            onClick = { actions.toggleFolder(store, entry.relativePath) },
                            inset = inset,
                            modifier = Modifier.testTag("tree-folder"),
                            description = if (expanded) "Folder ${entry.name}, open" else "Folder ${entry.name}",
                        )
                    }
                    if (expanded) folderItems(store, entry.relativePath, depth + 1, state, actions, inset)
                } else {
                    item(key) {
                        TreeRow(
                            name = entry.name,
                            icon = CursorIcons.FileText,
                            iconTint = CursorTheme.colors.iconTertiary,
                            depth = depth,
                            detail = entry.updatedAtMillis?.let(::writtenAt),
                            onClick = { actions.openDocument(store, entry.relativePath) },
                            inset = inset,
                            modifier = Modifier.testTag("tree-file"),
                            description = "File ${entry.name}",
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row of the tree as the web draws it: the entry's glyph, its name, and at the end edge when it was written —
 * "Today at 2:37 AM", "Friday at 3:19 AM" — in the quaternary colour; no chevrons, a folder opens on its row. Rows
 * are 34px, the web's pitch; each level steps 18px further in.
 */
@Composable
private fun TreeRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    depth: Int,
    detail: String?,
    onClick: () -> Unit,
    inset: Dp,
    modifier: Modifier = Modifier,
    description: String = name,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .pressable(onClick, CursorTheme.shapes.base, role = Role.Button)
            .height(34.dp)
            .padding(start = inset + TreeIndent * depth, end = inset)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(name, style = TreeText, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = type.base, color = colors.textQuaternary, maxLines = 1)
        }
    }
}

/** The tree's names as the web sets them: 14px. */
private val TreeText = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)

/** "Today at 2:37 AM", "Yesterday at 5:56 AM", "Friday at 3:19 AM", "Sep 2 at 11:04 PM": the web's file times. */
internal fun writtenAt(millis: Long): String = TimeFormat.dayAndTime(millis)

/** The notes without a title line that would repeat the header above them: a `# Heading` the file opens with. */
internal fun withoutLeadingTitle(markdown: String): String {
    val trimmed = markdown.trimStart()
    if (!trimmed.startsWith("# ")) return markdown
    return trimmed.substringAfter('\n', "").trimStart('\n')
}

/** The Recents row: the newest files across the stores, a thumbnail each, sideways. */
@Composable
private fun RecentsRow(recents: List<RecentContextFile>, actions: PanelActions, inset: Dp) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = inset, end = inset, top = 4.dp, bottom = 6.dp).testTag("recents-row"),
        horizontalArrangement = Arrangement.spacedBy(RecentTileGap),
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
    val viewer = LocalMediaViewer.current
    val open: () -> Unit = {
        val picture = bitmap
        // A picture opens in the media viewer on its own (the store's pictures are not among the chat's media); the rest as a tab.
        if (url != null && picture != null) {
            media?.onBeforeOpen?.invoke()
            viewer?.open(media?.agentId, emptyList(), url, seen = picture, fallback = MediaEntry(url, MediaEntry.Kind.Image, caption = recent.entry.name, fileName = recent.entry.name))
        } else {
            actions.openDocument(recent.store, recent.entry.relativePath)
        }
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
                // The top of the picture, as the web's tiles show the head of each screenshot.
                Image(picture, contentDescription = "Thumbnail of ${recent.entry.name}", contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize().clip(shape))
            } else {
                Icon(
                    if (recent.isImage) CursorIcons.Image else iconForExtension(recent.entry.name.substringAfterLast('.', "").lowercase()),
                    null,
                    tint = colors.iconTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(recent.entry.name, style = type.rowMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Recents where the stores cannot be read (Extended mode off): what this chat produced — its artifacts and
 * generated images — stands in, since those the documented API does give.
 */
@Composable
private fun RecentsFallback(state: PanelState, actions: PanelActions, inset: Dp) {
    val tiles = remember(state.content.media, state.artifacts, state.promptImages) { mediaTiles(state) }
    if (tiles.isEmpty()) {
        PanelNote("This chat's pictures and recordings would show here; it has none yet.")
        return
    }
    val viewer = LocalMediaViewer.current
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.lg
    val media = LocalMarkdownMedia.current
    val density = LocalDensity.current
    val px = with(density) { RecentTileWidth.roundToPx() to RecentTileHeight.roundToPx() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = inset, end = inset, top = 4.dp, bottom = 6.dp).testTag("recents-row"), horizontalArrangement = Arrangement.spacedBy(RecentTileGap)) {
        tiles.filterIsInstance<MediaTile.Image>().take(8).forEach { tile ->
            val bitmap by produceState<ImageBitmap?>(initialValue = null, tile.src, media) {
                value = if (media == null) null else runCatching { media.loader.image(MediaRef.parse(tile.src, media.agentId), px.first * 2, px.second * 2).asImageBitmap() }.getOrNull()
            }
            Column(Modifier.width(RecentTileWidth).testTag("recent-tile")) {
                Box(
                    Modifier.size(RecentTileWidth, RecentTileHeight).cursorSurface(colors.fillFaint, colors.strokeSubtle, shape)
                        .pressable(
                            {
                                bitmap?.let { picture ->
                                    media?.onBeforeOpen?.invoke()
                                    viewer?.open(media?.agentId, media?.entries?.invoke() ?: emptyList(), tile.src, seen = picture, fallback = MediaEntry(tile.src, MediaEntry.Kind.Image, caption = tile.caption))
                                } ?: actions.openUrl(tile.src)
                            },
                            shape,
                            role = Role.Image,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val picture = bitmap
                    if (picture != null) Image(picture, contentDescription = tile.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape))
                    else Icon(CursorIcons.Image, tile.caption, tint = colors.iconTertiary, modifier = Modifier.size(22.dp))
                }
                Text(tile.caption, style = type.rowMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
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
        // The web's second row: the breadcrumb from the store's root down to the file, `Preview` / `Source` at the end.
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val segments = tab.path.split('/').filter { it.isNotEmpty() }
                Text(rootLabel, style = type.base, color = colors.textTertiary, maxLines = 1)
                segments.forEachIndexed { index, segment ->
                    Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.padding(horizontal = 4.dp).size(11.dp))
                    Text(segment, style = type.base, color = if (index == segments.lastIndex) colors.textPrimary else colors.textTertiary, maxLines = 1)
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
        // A document runs to the web's 16px inset, unlike the notes under the Project's header.
        LazyColumn(Modifier.fillMaxSize().testTag("document-preview"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)) {
            item { MarkdownText(document.text, style = NotesText, appearance = MarkdownAppearance.Notes.copy(h1Scale = 2f)) }
        }
    }
}

/** Two or three words in one pill, the picked one on the panel's surface: the web's `Preview | Source`. */
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
                    .background(if (picked) colors.fillSoft else Color.Transparent, shape)
                    .pressable({ onSelect(index) }, shape, role = Role.Tab)
                    .semantics { this.contentDescription = option }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("segment-$option"),
            )
        }
    }
}

/** One level of the tree, the tile's size and the gap between tiles, as measured on cursor.com. */
private val TreeIndent = 18.dp
private val RecentTileWidth = 122.dp
private val RecentTileHeight = 146.dp
private val RecentTileGap = 12.dp
