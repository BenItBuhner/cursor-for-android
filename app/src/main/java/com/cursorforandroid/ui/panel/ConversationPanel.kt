package com.cursorforandroid.ui.panel

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.domain.AgentDiffFile
import com.cursorforandroid.domain.AgentStoreRef
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * The panel's contents, the way cursor.com keeps them beside a Project chat: a strip of tabs on top ([PanelTab]) and
 * the tab's body under it. The Chat tab is the chat's sections ([PanelRegistry.shown]) as collapsible groups — or,
 * while a file is open from Files or Changes, the file viewer in their place; the Project tab renders the Project's
 * Context notes; All Files the Context stores as trees with Recents; a document tab one Context file with Preview
 * and Source; a side chat tab the side chat itself, beside the conversation ([sideChatContent] is the host's, since
 * only the conversation screen can compose a chat).
 */
@Composable
fun ConversationPanel(
    state: PanelState,
    actions: PanelActions,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    registry: PanelRegistry = remember { PanelRegistry.default() },
    sideChatContent: @Composable (agentId: String) -> Unit = { SideChatPlaceholder(it, state) },
) {
    // Whether the chat has artifacts decides whether the Artifacts section is there at all, so the list is asked for
    // as the panel opens rather than when a section is; the view model asks once.
    LaunchedEffect(state.agentId) { actions.loadArtifacts() }
    val canStartSideChat = DefaultPanelSections.canStartSideChat(state.capabilities, state)
    Column(modifier.fillMaxSize().testTag("conversation-panel")) {
        PanelTabStrip(
            tabs = state.tabs,
            labels = { tabLabel(it, state) },
            icons = ::tabIcon,
            onSelect = actions::selectTab,
            onClose = actions::closeTab,
            onOpenFile = actions::openAllFiles,
            onNewSideChat = if (canStartSideChat) ({ actions.startSideChat(null) }) else null,
            onClosePanel = onClose,
        )
        HairlineDivider()
        when (val tab = state.tabs.current) {
            PanelTab.Chat -> ChatTab(state, actions, registry)
            PanelTab.Project -> ProjectNotesTab(state, actions)
            PanelTab.AllFiles -> AllFilesTab(state, actions)
            is PanelTab.Document -> DocumentTab(tab, state, actions)
            is PanelTab.SideChat -> sideChatContent(tab.agentId)
        }
    }
}

/** The tab's name on the strip: the fixed tabs' words, a document's file name, a side chat's name as the list has it. */
internal fun tabLabel(tab: PanelTab, state: PanelState): String = when (tab) {
    PanelTab.Chat -> "Chat"
    PanelTab.Project -> "Project"
    PanelTab.AllFiles -> "All Files"
    is PanelTab.Document -> tab.name
    is PanelTab.SideChat -> state.sideChats.firstOrNull { it.id == tab.agentId }?.name ?: "Side chat"
}

internal fun tabIcon(tab: PanelTab): ImageVector = when (tab) {
    PanelTab.Chat -> CursorIcons.Layers
    PanelTab.Project -> CursorIcons.Book
    PanelTab.AllFiles -> CursorIcons.Folder
    is PanelTab.Document -> iconForExtension(tab.name.substringAfterLast('.', "").lowercase())
    is PanelTab.SideChat -> CursorIcons.Ask
}

/** The Chat tab: the chat's name and repo · branch, then its sections; the file viewer takes their place while a file is open. */
@Composable
private fun ChatTab(state: PanelState, actions: PanelActions, registry: PanelRegistry) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val file = state.browser.file
    if (file != null) {
        FileViewerScreen(file, onBack = actions::closeFile, onOpenUrl = actions::openUrl)
        return
    }
    val sections = registry.shown(state.capabilities, state)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.agent?.name ?: "Chat", style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = state.agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ") }?.ifBlank { null }
                if (subtitle != null) Text(subtitle, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("panel-sections"), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(sections, key = { it.id.name }) { section ->
                PanelSectionView(section, state, actions)
            }
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
    // Opened or closed by the reader here, and remembered by the view model for as long as the panel lives: the
    // panel is composed only while it is open, so a reopened one starts from what the reader left rather than the
    // defaults. Where nothing remembers (previews, tests), the row's own state is all there is, and it still toggles.
    var expanded by rememberSaveable("panel-section-${section.id.name}") { mutableStateOf(state.expandedSections[section.id] ?: section.expandedByDefault) }
    // A pill above the composer can ask for a section by name (see PanelActions.showSection); the view model's word
    // then overrides what the row remembered.
    val remembered = state.expandedSections[section.id]
    LaunchedEffect(remembered) { if (remembered != null) expanded = remembered }
    // What the section needs is asked for when it is opened, and again when its chat — or the mode, which decides
    // which reads may be made — changes under it.
    LaunchedEffect(expanded, availability is SectionAvailability.Available, state.prUrl, state.agentId, state.capabilities) {
        if (expanded && availability is SectionAvailability.Available) section.onOpen(actions)
    }
    val hint = when (availability) {
        is SectionAvailability.Available -> section.hint(state)
        // Not drawn: the registry leaves such a section out (see PanelRegistry.shown).
        is SectionAvailability.RequiresExtended -> return
        is SectionAvailability.NotForThisChat -> "—"
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section, hint, expanded, onToggle = { expanded = !expanded; actions.setSectionExpanded(section.id, expanded) })
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
): PanelActions {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return remember(viewModel, onOpenAgent) {
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
            override fun openSideChat(agentId: String) = viewModel.openSideChat(agentId)
            override fun openDocument(store: AgentStoreRef, path: String) = viewModel.openDocument(store, path)
            override fun openAllFiles() = viewModel.openAllFiles()
            override fun openProjectTab() = viewModel.openProjectTab()
            override fun showSection(section: PanelSectionId) = viewModel.showSection(section)
            override fun loadContext(force: Boolean) = viewModel.loadContext(force)
            override fun toggleFolder(store: AgentStoreRef, path: String) = viewModel.toggleFolder(store, path)
            override fun loadDocument(tab: PanelTab.Document, force: Boolean) = viewModel.loadDocument(tab, force)
            override fun setDocumentSource(tab: PanelTab.Document, source: Boolean) = viewModel.setDocumentSource(tab, source)
        }
    }
}
