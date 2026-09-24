package com.cursorforandroid.ui.panel

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.StorePath
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FadingLazyColumn
import com.cursorforandroid.ui.components.LocalMarkdownMedia
import com.cursorforandroid.ui.components.MarkdownMediaContext
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.panelInsetPadding
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * The panel's contents, as cursor.com's right panel lays them out: a strip of tabs along the top — the Project in a
 * Project's chats, the chat's own sections (Details), then one tab for each thing opened from them: another chat, a
 * Context document, a file, a picture or a recording — and the selected tab's body under it. The panel opens on its
 * home tab, the Project where there is one; back walks the tabs back to it (see [PanelViewModel.back]), and from
 * there closes the panel. Each tab keeps its scroll and what it opened while another shows, and while the panel is put
 * away, as long as [tabStates] outlasts it.
 */
@Composable
fun ConversationPanel(
    state: PanelState,
    actions: PanelActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    registry: PanelRegistry = remember { PanelRegistry.default() },
    tabStates: PanelTabStates = rememberPanelTabStates(),
) {
    // Whether the chat has artifacts decides whether the Artifacts section is there at all, so the list is asked for
    // as the panel opens rather than when a section is; the view model asks once.
    LaunchedEffect(state.agentId) { actions.loadArtifacts() }
    val colors = CursorTheme.colors
    val strip = state.stripTabs
    val current = state.currentTab
    // Off its home tab, back is the panel's: to the tab the reader came from. At home it is the host's, and shuts the
    // panel. Pinned beside the chat the panel is a pane of the layout, like the rail, and back is the chat's.
    BackHandler(enabled = !LocalPanelPinned.current && current.key != state.homeTab.key, onBack = actions::back)
    val keys = strip.map { it.key }
    LaunchedEffect(keys) { tabStates.keepOnly(keys.toSet()) }
    val menu = remember(state.hasProjectTab, DefaultPanelSections.canStartSideChat(state.capabilities, state), actions) {
        buildList {
            if (state.hasProjectTab) add(StripAction("All files", CursorIcons.Folder) { actions.openProject(allFiles = true) })
            if (DefaultPanelSections.canStartSideChat(state.capabilities, state)) add(StripAction("New side chat", CursorIcons.Ask) { actions.startSideChat(null) })
        }
    }
    val edge = colors.strokeSubtle
    // The panel's one inset consumption (panelInsetPadding): the strip under the status bar, the body above the
    // navigation bar or the keyboard, whichever host — sheet or pane — this is composed in; the host's surface itself
    // runs edge to edge behind them, and so does the rule that parts the panel from the chat, as the web's does.
    Column(
        modifier
            .fillMaxSize()
            .drawBehind {
                val stroke = 1.dp.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) stroke / 2 else size.width - stroke / 2
                drawLine(edge, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            }
            .panelInsetPadding()
            .testTag("conversation-panel"),
    ) {
        PanelTabStrip(
            tabs = strip,
            selected = current,
            label = { tabLabel(it, state) },
            leading = { tab, selected -> TabGlyph(tab, selected, state) },
            onSelect = actions::selectTab,
            onClose = actions::closeTab,
            menu = menu,
            onClosePanel = onClose,
        )
        CompositionLocalProvider(LocalMarkdownMedia provides rememberPanelMedia(state.agentId, actions)) {
            Box(Modifier.fillMaxSize()) {
                tabStates.holder.SaveableStateProvider(current.key) {
                    when (current) {
                        PanelTab.Project -> ProjectTabContent(state, actions)
                        PanelTab.Details -> DetailsTab(state, actions, registry)
                        is PanelTab.Agent -> AgentTab(current, state, actions)
                        is PanelTab.Document -> DocumentTab(current, state, actions)
                        is PanelTab.File -> FileTab(current, state, actions)
                        is PanelTab.Media -> MediaTab(current, actions)
                    }
                }
            }
        }
    }
}

/**
 * What each of the panel's tabs keeps — its scroll, what it unfolded — for as long as whoever remembers this is
 * composed. The chat holds it rather than the panel, whose content is composed only while it can be seen: shut and
 * opened again, or put away as a foldable folds and brought back as it unfolds, a tab comes back as it was left. A tab
 * taken off the strip takes what it kept with it, and reopened starts afresh.
 */
@Stable
class PanelTabStates internal constructor(internal val holder: SaveableStateHolder) {
    private val kept = HashSet<String>()

    internal fun keepOnly(open: Set<String>) {
        kept.filterNot { it in open }.forEach(holder::removeState)
        kept.retainAll(open)
        kept.addAll(open)
    }
}

@Composable
fun rememberPanelTabStates(): PanelTabStates {
    val holder = rememberSaveableStateHolder()
    return remember(holder) { PanelTabStates(holder) }
}

/**
 * The media context the panel's figures and links read: the chat's, with its links sent into the panel — a link to
 * another agent opens that agent's tab (its desktop, `#desktop`, on cursor.com), a store file a document or media tab —
 * and to wherever the chat would have sent them when no store of the account's holds the file.
 */
@Composable
private fun rememberPanelMedia(agentId: String, actions: PanelActions): MarkdownMediaContext? {
    val chat = LocalMarkdownMedia.current
    return remember(chat, agentId, actions) {
        chat?.let { parent ->
            MarkdownMediaContext(
                agentId = parent.agentId,
                loader = parent.loader,
                canReadStores = parent.canReadStores,
                onOpenStorePath = { path ->
                    actions.openStorePath(path, agentId) {
                        parent.onOpenStorePath?.invoke(path) ?: path.ownerId(agentId)?.let { actions.openUrl(StorePath.webUrl(it)) }
                    }
                },
                entries = parent.entries,
                onBeforeOpen = parent.onBeforeOpen,
                onOpenAgentLink = { link -> if (link.isDesktop) actions.openUrl(link.webUrl) else actions.openAgent(link.agentId) },
            )
        }
    }
}

/** The tab's name on the strip: "Project", "Details", another chat's name as the list has it, a file's name. */
internal fun tabLabel(tab: PanelTab, state: PanelState): String = when (tab) {
    PanelTab.Project -> "Project"
    PanelTab.Details -> "Details"
    is PanelTab.Agent -> state.tabAgents[tab.agentId]?.name ?: "Agent"
    is PanelTab.Document -> tab.name
    is PanelTab.File -> tab.name
    is PanelTab.Media -> tab.name
}

/** The glyph a tab wears on the strip, for all but another chat's: its dot. */
internal fun tabIcon(tab: PanelTab): androidx.compose.ui.graphics.vector.ImageVector? = when (tab) {
    PanelTab.Project -> PanelIcons.Kanban
    PanelTab.Details -> PanelIcons.Details
    is PanelTab.Agent -> null
    is PanelTab.Document -> if (isMarkdownName(tab.name)) PanelIcons.Markdown else iconForExtension(extensionOf(tab.name))
    is PanelTab.File -> iconForExtension(extensionOf(tab.name))
    is PanelTab.Media -> if (tab.isVideo) CursorIcons.Video else CursorIcons.Image
}

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

private fun isMarkdownName(name: String): Boolean = extensionOf(name) in setOf("md", "markdown")

@Composable
private fun TabGlyph(tab: PanelTab, selected: Boolean, state: PanelState) {
    val icon = tabIcon(tab)
    if (icon != null) TabIcon(icon, selected) else AgentDot((tab as? PanelTab.Agent)?.let { state.tabAgents[it.agentId] })
}

/** Another chat's mark on the strip, as the web's: a small dot — a spinner while it runs, red when its run failed. */
@Composable
private fun AgentDot(agent: Agent?) {
    val colors = CursorTheme.colors
    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        when {
            agent?.isRunning == true -> SpinnerRing(size = 10.dp)
            else -> Box(Modifier.size(6.dp).background(if (agent?.runStatus == RunStatus.ERROR) colors.red else colors.iconQuaternary, CircleShape))
        }
    }
}

/**
 * The chat's own sections ([PanelRegistry.shown]) under its name and repo · branch, as collapsible groups set apart by
 * their headers and air alone. A section whose read is under way or failed says so under its header; one with nothing
 * to show is not there.
 */
@Composable
private fun DetailsTab(state: PanelState, actions: PanelActions, registry: PanelRegistry) {
    val sections = registry.shown(state.capabilities, state)
    // A section whose rows are the list's own items (PanelSection.items) is composed beside the list and laid out
    // by it as its rows come on screen; the rest draw as one item each.
    val lazySections = sections.mapNotNull { section ->
        val items = section.items ?: return@mapNotNull null
        section.id to key(section.id) { lazySection(section, items, state, actions) }
    }.toMap()
    FadingLazyColumn(Modifier.fillMaxSize().testTag("panel-sections"), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "details-title") { DetailsTitle(state.agent) }
        for (section in sections) {
            val rows = lazySections[section.id]
            if (rows != null) rows() else item(key = section.id.name) { PanelSectionView(section, state, actions) }
        }
    }
}

@Composable
private fun DetailsTitle(agent: Agent?) {
    val colors = CursorTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = PanelGutter, end = PanelGutter, top = 18.dp, bottom = 10.dp).testTag("details-title")) {
        Text(agent?.name ?: "Chat", style = PanelType.title(), color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ") }?.ifBlank { null }?.let { caption ->
            Text(caption, style = CursorTheme.typography.small, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PanelSectionView(section: PanelSection, state: PanelState, actions: PanelActions) {
    val availability = section.availability(state.capabilities, state)
    var expanded by rememberExpanded(section, availability, state, actions)
    // Not drawn: the registry leaves such a section out (see PanelRegistry.shown).
    if (availability is SectionAvailability.RequiresExtended) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section, hintOf(section, availability, state), expanded, onToggle = { expanded = !expanded; actions.setSectionExpanded(section.id, expanded) })
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = SectionEndGap)) {
                when (availability) {
                    is SectionAvailability.Available -> section.content(state, actions)
                    is SectionAvailability.RequiresExtended -> Unit
                    is SectionAvailability.NotForThisChat -> EmptyRow(availability.reason, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

/**
 * A section whose rows are the panel list's own items ([PanelSection.items]): the header, rows and closing gap
 * [PanelSectionView] draws as one item, each an item of its own. Whether it is open, the read it asks for and what
 * [items] holds are composed here, beside the list, and the rows are composed by the list as they come on screen.
 */
@Composable
private fun lazySection(
    section: PanelSection,
    items: @Composable (PanelState, PanelActions) -> (LazyListScope.() -> Unit),
    state: PanelState,
    actions: PanelActions,
): LazyListScope.() -> Unit {
    val availability = section.availability(state.capabilities, state)
    var expanded by rememberExpanded(section, availability, state, actions)
    if (availability is SectionAvailability.RequiresExtended) return {}
    val hint = hintOf(section, availability, state)
    val rows = if (expanded && availability is SectionAvailability.Available) items(state, actions) else null
    return {
        item(key = section.id.name) {
            SectionHeader(section, hint, expanded, onToggle = { expanded = !expanded; actions.setSectionExpanded(section.id, expanded) })
        }
        if (rows != null) rows()
        if (expanded && availability is SectionAvailability.NotForThisChat) {
            sectionRow("${section.id.name}-unavailable") { EmptyRow(availability.reason, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        if (expanded) item(key = "${section.id.name}-end") { Spacer(Modifier.height(SectionEndGap)) }
    }
}

/** The air under an open section, before the next one's header: what the web parts its groups by in place of a rule. */
private val SectionEndGap = 8.dp

/**
 * Whether [section] is open. Opened or closed by the reader here, and remembered by the view model for as long as the
 * panel lives: the panel is composed only while it is open, so a reopened one starts from what the reader left rather
 * than the defaults. Where nothing remembers (previews, tests), the row's own state is all there is, and it still toggles.
 */
@Composable
private fun rememberExpanded(section: PanelSection, availability: SectionAvailability, state: PanelState, actions: PanelActions): MutableState<Boolean> {
    val expanded = rememberSaveable("panel-section-${section.id.name}") { mutableStateOf(state.expandedSections[section.id] ?: section.expandedByDefault) }
    // What the section needs is asked for when it is opened, and again when its chat — or the mode, which decides
    // which reads may be made — changes under it.
    LaunchedEffect(expanded.value, availability is SectionAvailability.Available, state.prUrl, state.agentId, state.capabilities) {
        if (expanded.value && availability is SectionAvailability.Available) section.onOpen(actions)
    }
    return expanded
}

private fun hintOf(section: PanelSection, availability: SectionAvailability, state: PanelState): String? = when (availability) {
    is SectionAvailability.Available -> section.hint(state)
    is SectionAvailability.RequiresExtended -> null
    is SectionAvailability.NotForThisChat -> "—"
}

/**
 * The graph the panel lives in, for what owns its own reads: the Project section's view model, an agent tab's
 * transcript. Null where the panel is rendered on its own, as in tests and previews; those then show a named state instead.
 */
val LocalPanelGraph = staticCompositionLocalOf<AppGraph?> { null }

/**
 * The [PanelActions] for a live panel: the view model's loads and tabs, and the platform's clipboard, browser and
 * share sheet. [onToast] surfaces confirmations on the screen's own snackbar; [onOpenAgent] is the host's navigation,
 * absent where the screen cannot navigate — another chat opens as a tab of the panel, and as the conversation itself
 * through that navigation.
 */
@Composable
fun rememberPanelActions(
    viewModel: PanelViewModel,
    onToast: (String) -> Unit,
    onOpenAgent: ((String) -> Unit)? = null,
    onAskToCopyFile: ((String) -> Unit)? = null,
): PanelActions {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, onOpenAgent, onAskToCopyFile) {
        object : PanelActions {
            override fun loadPullRequest(force: Boolean) = viewModel.loadPullRequest(force)
            override fun loadArtifacts(force: Boolean) = viewModel.loadArtifacts(force)
            override fun loadUsage(force: Boolean) = viewModel.loadUsage(force)
            override fun browse(path: String, force: Boolean) = viewModel.browse(path, force)
            override fun browseUp() = viewModel.browseUp()
            override fun openRepoFile(path: String) = viewModel.openRepoFile(path)
            override fun openTouched(path: String) = viewModel.openTouched(path)
            override fun openChange(change: TranscriptContent.FileChange) = viewModel.openChange(change)
            override fun closeFile() = viewModel.closeFile()
            override fun retryFile(wake: Boolean) = viewModel.retryFile(wake)
            override fun askToCopyFile(path: String) { onAskToCopyFile?.invoke(path) ?: onToast("Open the chat to ask the agent to copy it in.") }
            override fun openUrl(url: String) {
                runCatching { uriHandler.openUri(url) }.onFailure { onToast("Nothing on this device can open that link.") }
            }
            override fun copyText(text: String, confirmation: String) {
                clipboard.setText(AnnotatedString(text))
                // Android 13+ confirms clipboard writes itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onToast(confirmation)
            }
            override fun shareText(text: String) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { onToast("Nothing on this device can share that.") }
            }
            override fun openArtifact(artifact: Artifact) {
                scope.launch {
                    viewModel.artifactUrl(artifact).fold(
                        onSuccess = { openUrl(it) },
                        onFailure = { onToast("Couldn't get a download link for ${artifact.name}.") },
                    )
                }
            }
            override fun openAgent(agentId: String) = viewModel.openAgentTab(agentId)
            override fun openAgentAsChat(agentId: String) {
                onOpenAgent?.invoke(agentId) ?: onToast("This screen cannot open another chat.")
            }
            override fun notify(message: String) {
                if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            override fun setSectionExpanded(id: PanelSectionId, expanded: Boolean) = viewModel.setSectionExpanded(id, expanded)
            override fun refreshSideChats() = viewModel.refreshSideChats()
            override fun startSideChat(name: String?) = control { viewModel.startSideChat(name) }
            override fun loadDiff(force: Boolean) = viewModel.loadDiff(force)
            override fun openBranchDiffFile(file: AgentDiffFile) = viewModel.openBranchDiffFile(file)
            override fun loadWorkspace(force: Boolean) = viewModel.loadWorkspace(force)
            override fun browseWorkspace(path: String) = viewModel.browseWorkspace(path)
            override fun browseWorkspaceUp() = viewModel.browseWorkspaceUp()
            override fun openWorkspaceFile(path: String) = viewModel.openWorkspaceFile(path)
            override fun loadMachine(force: Boolean) = viewModel.loadMachine(force)
            override fun openDesktop(viewOnly: Boolean) = viewModel.openDesktop(viewOnly)
            override fun setDesktopViewOnly(viewOnly: Boolean) = viewModel.setDesktopViewOnly(viewOnly)
            override fun failDesktop(failure: DesktopFailure) = viewModel.failDesktop(failure)
            override fun closeDesktop() = viewModel.closeDesktop()
            override fun createPullRequest() = viewModel.createPullRequest()

            /** One account-service control: what it came back with goes on the screen's snackbar, as does the reason it did not happen. */
            private fun control(block: suspend () -> Result<String?>) {
                scope.launch {
                    block().fold(onSuccess = { message -> if (!message.isNullOrBlank()) onToast(message) }, onFailure = { onToast(it.userMessage()) })
                }
            }
            override fun answerQuestion(callId: String, answers: List<ToolPayload.Question.Answer>) = control { viewModel.answerQuestion(callId, answers) }
            override fun pauseRun() = control { viewModel.pauseRun() }
            override fun resumeRun() = control { viewModel.resumeRun() }
            override fun stopRun() = control { viewModel.stopRun() }
            override fun wake() = control { viewModel.wake() }

            override fun selectTab(tab: PanelTab) = viewModel.selectTab(tab)
            override fun closeTab(tab: PanelTab) = viewModel.closeTab(tab)
            override fun back() = viewModel.back()
            override fun openDocument(store: AgentStoreRef, path: String) = viewModel.openDocument(store, path)
            override fun openMedia(src: String, name: String, isVideo: Boolean) = viewModel.openMedia(src, name, isVideo)
            override fun openStorePath(path: StorePath, chatAgentId: String, otherwise: () -> Unit) = viewModel.openStorePath(path, chatAgentId, otherwise)
            override fun openProject(allFiles: Boolean) = viewModel.openProject(allFiles)
            override fun loadContext(force: Boolean) = viewModel.loadContext(force)
            override fun toggleFolder(store: AgentStoreRef, path: String) = viewModel.toggleFolder(store, path)
            override fun loadDocument(tab: PanelTab.Document, force: Boolean) = viewModel.loadDocument(tab, force)
            override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) = viewModel.setDocumentSource(tab, source)
        }
    }
}
