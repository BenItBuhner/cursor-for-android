package com.cursorforandroid.ui.panel

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FadingLazyColumn
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.panelInsetPadding
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * The panel's contents, one of two surfaces ([PanelSurface]). The Project panel is the one cursor.com keeps beside a
 * Project chat: a strip of tabs on top — the Project, one tab per Context document opened from it, one per side
 * chat opened beside the conversation, `+`, and the panel's expand and close controls at the end — and the tab's
 * body under it: the Project's notes or, toggled from the header beside the Project's name, its files (All Files);
 * a document with Preview and Source; a side chat itself ([sideChatContent] is the host's, since only the
 * conversation screen can compose a chat). The Chat surface is the chat's own sections ([PanelRegistry.shown]) as
 * collapsible groups, the panel every chat had before — or, while a file is open from Files or Changes, the file
 * viewer in their place, which back closes before it closes the panel.
 */
@Composable
fun ConversationPanel(
    state: PanelState,
    actions: PanelActions,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    registry: PanelRegistry = remember { PanelRegistry.default() },
    /** The panel's expand control: null leaves it out (a phone's sheet is already the width it can be). */
    onExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    sideChatContent: @Composable (agentId: String) -> Unit = { SideChatPlaceholder(it, state) },
) {
    // Whether the chat has artifacts decides whether the Artifacts section is there at all, so the list is asked for
    // as the panel opens rather than when a section is; the view model asks once.
    LaunchedEffect(state.agentId) { actions.loadArtifacts() }
    // The panel's one inset consumption (panelInsetPadding): the strip or the header under the status bar, the body
    // above the navigation bar or the keyboard, whichever host — sheet or pane — this is composed in; the host's
    // surface itself runs edge to edge behind them.
    Column(modifier.fillMaxSize().panelInsetPadding().testTag("conversation-panel")) {
        when (state.surface) {
            PanelSurface.Chat -> ChatSurface(state, actions, registry, onClose)
            PanelSurface.Project -> ProjectSurface(state, actions, onClose, onExpand, expanded, sideChatContent)
        }
    }
}

/** The Project panel: the tab strip and the selected tab's body. */
@Composable
private fun ProjectSurface(
    state: PanelState,
    actions: PanelActions,
    onClose: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    expanded: Boolean,
    sideChatContent: @Composable (agentId: String) -> Unit,
) {
    val canStartSideChat = DefaultPanelSections.canStartSideChat(state.capabilities, state)
    PanelTabStrip(
        tabs = state.tabs,
        labels = { tabLabel(it, state) },
        icons = ::tabIcon,
        onSelect = actions::selectTab,
        onClose = actions::closeTab,
        onOpenFile = if (state.hasProjectTab) ({ actions.openProject(allFiles = true) }) else null,
        onNewSideChat = if (canStartSideChat) ({ actions.startSideChat(null) }) else null,
        onExpand = onExpand,
        expanded = expanded,
        onClosePanel = onClose,
    )
    HairlineDivider()
    when (val tab = state.tabs.current) {
        PanelTab.Project -> ProjectTabContent(state, actions)
        is PanelTab.Document -> DocumentTab(tab, state, actions)
        is PanelTab.SideChat -> sideChatContent(tab.agentId)
        // No Project and nothing opened: the strip's `+` is the way in.
        null -> EmptyRow("Nothing open", "Open a side chat from the chat's sections, or a file from a Project's Context.")
    }
}

/** The tab's name on the strip: "Project", a document's file name, a side chat's name as the list has it. */
internal fun tabLabel(tab: PanelTab, state: PanelState): String = when (tab) {
    PanelTab.Project -> "Project"
    is PanelTab.Document -> tab.name
    is PanelTab.SideChat -> state.sideChats.firstOrNull { it.id == tab.agentId }?.name ?: "Side chat"
}

/** The tab's glyph: the web's page mark for the Project, the markdown mark for a `.md` document, the file's type otherwise, the side-chat glyph. */
internal fun tabIcon(tab: PanelTab): ImageVector = when (tab) {
    PanelTab.Project -> CursorIcons.FileText
    is PanelTab.Document -> if (tab.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")) CursorIcons.Markdown else iconForExtension(tab.name.substringAfterLast('.', "").lowercase())
    is PanelTab.SideChat -> CursorIcons.Ask
}

/** The Chat surface: the chat's name and repo · branch with the close control, then its sections; the file viewer takes their place while a file is open. */
@Composable
private fun ChatSurface(state: PanelState, actions: PanelActions, registry: PanelRegistry, onClose: (() -> Unit)?) {
    val file = state.browser.file
    if (file != null) {
        // A file opened from Files or Changes is a page within the panel: back returns to the sections it was
        // opened from before it closes the panel.
        BackHandler(onBack = actions::closeFile)
        FileViewerScreen(file, onBack = actions::closeFile, onOpenUrl = actions::openUrl, onRetry = actions::retryFile, onAskToCopy = actions::askToCopyFile)
        return
    }
    val sections = registry.shown(state.capabilities, state)
    // The same header every pane wears: it pads for the status bar on its own, and finds it already consumed at the root.
    CursorHeader(
        title = state.agent?.name ?: "Chat",
        subtitle = state.agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ") }?.ifBlank { null },
        trailing = {
            // A chat in a Project has the Project panel behind this one; the way back to it.
            if (state.hasProjectTab) FlatIconButton(CursorIcons.FileText, "Project panel", onClick = { actions.openProject(allFiles = false) }, modifier = Modifier.testTag("panel-to-project"))
            if (onClose != null) FlatIconButton(CursorIcons.Close, "Close panel", onClick = onClose)
        },
    )
    HairlineDivider()
    // A section whose rows are the list's own items (PanelSection.items) is composed beside the list and laid out
    // by it as its rows come on screen; the rest draw as one item each.
    val lazySections = sections.mapNotNull { section ->
        val items = section.items ?: return@mapNotNull null
        section.id to key(section.id) { lazySection(section, items, state, actions) }
    }.toMap()
    FadingLazyColumn(Modifier.fillMaxSize().testTag("panel-sections"), contentPadding = PaddingValues(vertical = 4.dp)) {
        for (section in sections) {
            val rows = lazySections[section.id]
            if (rows != null) rows() else item(key = section.id.name) { PanelSectionView(section, state, actions) }
        }
    }
}

/** Where a side chat tab has no host to compose the chat (tests, previews): the side chat's name and a way to open it as a chat. */
@Composable
private fun SideChatPlaceholder(agentId: String, state: PanelState) {
    val chat = state.sideChats.firstOrNull { it.id == agentId }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("side-chat-tab")) {
        Text(chat?.name ?: "Side chat", style = CursorTheme.typography.title, color = CursorTheme.colors.textPrimary)
        Text("The side chat opens here inside a running conversation.", style = CursorTheme.typography.small, color = CursorTheme.colors.textQuaternary)
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
            when (availability) {
                is SectionAvailability.Available -> section.content(state, actions)
                is SectionAvailability.RequiresExtended -> Unit
                is SectionAvailability.NotForThisChat -> EmptyRow(availability.reason, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 12.dp))
    }
}

/**
 * A section whose rows are the panel list's own items ([PanelSection.items]): the header, rows and divider
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
        item(key = "${section.id.name}-end") { HairlineDivider(Modifier.padding(horizontal = 12.dp)) }
    }
}

/**
 * Whether [section] is open. Opened or closed by the reader here, and remembered by the view model for as long as the
 * panel lives: the panel is composed only while it is open, so a reopened one starts from what the reader left rather
 * than the defaults. Where nothing remembers (previews, tests), the row's own state is all there is, and it still
 * toggles. A pill above the composer can ask for a section by name (see PanelActions.showSection); the view model's
 * word then overrides what the row remembered.
 */
@Composable
private fun rememberExpanded(section: PanelSection, availability: SectionAvailability, state: PanelState, actions: PanelActions): MutableState<Boolean> {
    val expanded = rememberSaveable("panel-section-${section.id.name}") { mutableStateOf(state.expandedSections[section.id] ?: section.expandedByDefault) }
    val remembered = state.expandedSections[section.id]
    LaunchedEffect(remembered) { if (remembered != null) expanded.value = remembered }
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
 * The graph the panel lives in, for sections that own their own view models (the Project section). Null where the
 * panel is rendered on its own, as in tests and previews; such a section then shows a named state instead.
 */
val LocalPanelGraph = staticCompositionLocalOf<AppGraph?> { null }

/**
 * The [PanelActions] for a live panel: the view model's loads and the platform's clipboard, browser and share
 * sheet. [onToast] surfaces confirmations on the screen's own snackbar; [onOpenAgent] is the host's navigation,
 * absent where the screen cannot navigate.
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
            override fun openAgent(agentId: String) {
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

            override fun showSurface(surface: PanelSurface) = viewModel.showSurface(surface)
            override fun selectTab(tab: PanelTab) = viewModel.selectTab(tab)
            override fun closeTab(tab: PanelTab) = viewModel.closeTab(tab)
            override fun openSideChat(agentId: String) = viewModel.openSideChat(agentId)
            override fun openDocument(store: AgentStoreRef, path: String) = viewModel.openDocument(store, path)
            override fun openProject(allFiles: Boolean) = viewModel.openProject(allFiles)
            override fun showSection(section: PanelSectionId) = viewModel.showSection(section)
            override fun loadContext(force: Boolean) = viewModel.loadContext(force)
            override fun toggleFolder(store: AgentStoreRef, path: String) = viewModel.toggleFolder(store, path)
            override fun loadDocument(tab: PanelTab.Document, force: Boolean) = viewModel.loadDocument(tab, force)
            override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) = viewModel.setDocumentSource(tab, source)
        }
    }
}
