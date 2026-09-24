package com.cursorforandroid.ui.quick

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.DraftStore
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.share.ShareTarget
import com.cursorforandroid.ui.components.AttachmentCounts
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.ModePills
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.SelectorChip
import com.cursorforandroid.ui.components.SelectorRow
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.keyboardInsetPadding
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberFilePicker
import com.cursorforandroid.ui.components.rememberMediaPicker
import com.cursorforandroid.ui.compose.LaunchRefusedHaptic
import com.cursorforandroid.ui.compose.NewAgentUiState
import com.cursorforandroid.ui.compose.NewAgentViewModel
import com.cursorforandroid.ui.compose.rememberComposerMenuActions
import com.cursorforandroid.ui.home.BranchSheet
import com.cursorforandroid.ui.home.ComposerErrorLine
import com.cursorforandroid.ui.home.DeviceSheet
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.home.RepositorySheet
import com.cursorforandroid.ui.home.deviceIcon
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

/** Test tags for the quick composer's own controls. */
object QuickComposerTags {
    const val SHEET = "quick-composer"
    const val CANCEL = "quick-composer-cancel"
    const val CONTEXT = "quick-composer-context"
}

/**
 * The quick composer's screen: decides the session first — the sheet spins until the stored key or the demo has been
 * read, says so when there is no account, and is the composer otherwise — and puts the sheet over the scrim.
 */
@Composable
fun QuickComposerHost(
    graph: AppGraph,
    /** The composer's view model, once built, for the activity to keep the draft through its own lifecycle. */
    onComposer: (NewAgentViewModel) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    /** The server has created the chat; open the app on it. */
    onOpened: (agentId: String) -> Unit,
    /** Signed out: open the app, where signing in happens. */
    onOpenApp: () -> Unit,
) {
    val session by graph.session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        runCatching { graph.session.restoreIfNeeded() }
        // The list as last saved, for the branch picker and the newest chat's model; nothing is fetched for it here.
        if (graph.session.state.value is SessionState.SignedIn) runCatching { graph.agents.restoreFromCache() }
    }
    when (session) {
        SessionState.Loading -> SheetFrame(onDismiss) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { SpinnerRing(size = 16.dp, strokeWidth = 1.5.dp) }
        }
        SessionState.SignedOut -> SheetFrame(onDismiss) {
            SheetTitle(onCancel = onDismiss)
            Text("Sign in to Cursor to start a chat.", style = CursorTheme.typography.base, color = CursorTheme.colors.textSecondary, modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 14.dp))
            CursorButton("Open Cursor", onClick = onOpenApp, primary = true)
        }
        is SessionState.SignedIn -> QuickComposer(graph, onComposer, onDismiss, onCancel, onOpened)
    }
}

/** The composer itself, wired to the New Chat pane's view model and pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickComposer(
    graph: AppGraph,
    onComposer: (NewAgentViewModel) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onOpened: (agentId: String) -> Unit,
) {
    val viewModel: NewAgentViewModel = viewModel(factory = NewAgentViewModel.Factory(graph, origin = DraftStore.ORIGIN_QUICK_COMPOSER))
    LaunchedEffect(viewModel) { onComposer(viewModel) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    var repoSheet by rememberSaveable { mutableStateOf(false) }
    var branchSheet by rememberSaveable { mutableStateOf(false) }
    var deviceSheet by rememberSaveable { mutableStateOf(false) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    // The context line opens into the pane's three chips on a tap and stays open: whoever opened it is choosing.
    var contextExpanded by rememberSaveable { mutableStateOf(false) }

    val counts = AttachmentCounts.of(state.attachments, state.files)
    val pickMedia = rememberMediaPicker(
        extended = state.canAttachFiles,
        counts = counts,
        onPickedImages = viewModel::addAttachments,
        onPickedFiles = viewModel::addFiles,
        onError = viewModel::reportError,
    )
    val pickFiles = rememberFilePicker(counts = counts, onPickedFiles = viewModel::addFiles, onError = viewModel::reportError)
    val plusMenu = rememberComposerMenuActions(graph, onPickMedia = pickMedia, onPickFiles = if (state.canAttachFiles) pickFiles else null)
    // A share left for the New Chat pane is as much this composer's: it is the same draft.
    val share by graph.share.offer.collectAsStateWithLifecycle()
    LaunchedEffect(share?.generation, share?.target) {
        val draft = share ?: return@LaunchedEffect
        if (draft.target != ShareTarget.NewChat) return@LaunchedEffect
        viewModel.applyShare(draft.text, draft.attachments, draft.warning)
        graph.share.consume(draft.generation)
    }

    QuickComposerSheet(
        state = state,
        commands = commands,
        plusMenu = plusMenu,
        contextExpanded = contextExpanded,
        onExpandContext = { contextExpanded = true },
        onDismiss = onDismiss,
        onCancel = onCancel,
        onSend = { viewModel.launch(onOpen = onOpened, awaitServer = true) },
        onCancelSend = viewModel::cancelLaunch,
        onPrompt = viewModel::setPrompt,
        onAddAttachments = viewModel::addAttachments,
        onRemoveAttachment = viewModel::removeAttachment,
        onRemoveFile = viewModel::removeFile,
        onRetryFile = viewModel::retryFile,
        onAttachmentError = viewModel::reportError,
        onDismissError = viewModel::dismissError,
        onModePill = { pill -> viewModel.setPlanMode(pill == ModePills.Pill.Plan) },
        onRepo = { repoSheet = true },
        onBranch = { branchSheet = true },
        onDevice = { deviceSheet = true },
        onModel = { modelSheet = true },
    )

    if (repoSheet) {
        RepositorySheet(
            repos = state.repositories,
            recent = state.recentRepositories,
            selected = state.selectedRepo,
            noRepo = state.noRepo,
            loading = state.isLoadingRepos,
            unavailable = state.reposUnavailable,
            onSelect = viewModel::selectRepo,
            onRefresh = viewModel::refreshRepositories,
            onDismiss = { repoSheet = false },
            device = state.selectedDevice.takeUnless { it.isCloud },
            deviceRepo = state.deviceRepository,
        )
    }
    if (branchSheet) {
        BranchSheet(
            repo = state.selectedRepo,
            branches = state.branches,
            selected = state.ref,
            fromCheckout = state.startsFromCheckout,
            listedByAccount = state.branchesListedByAccount,
            onSelect = viewModel::setRef,
            onDismiss = { branchSheet = false },
        )
    }
    if (deviceSheet) {
        DeviceSheet(devices = state.devices, selected = state.selectedDevice, loading = state.isLoadingDevices, onSelect = viewModel::selectDevice, onRefresh = viewModel::refreshDevices, onDismiss = { deviceSheet = false })
    }
    if (modelSheet) {
        ModelSheet(
            models = state.models,
            selectedModel = state.selectedModel,
            selectedVariant = state.selectedVariant,
            planMode = state.planMode,
            autoCreatePr = state.autoCreatePr,
            loading = state.isLoadingModels,
            unavailable = state.modelsUnavailable,
            onPlanMode = viewModel::setPlanMode,
            onAutoCreatePr = viewModel::setAutoCreatePr,
            onRefresh = viewModel::refreshModels,
            onSelect = viewModel::selectModel,
            onDismiss = { modelSheet = false },
            pinnedIds = state.pinnedModelIds,
            onTogglePin = viewModel::togglePinnedModel,
        )
    }
}

/**
 * The sheet: the New Chat pane's composer docked at the bottom of a translucent window, over a scrim that dismisses
 * it. A title row — the cube, "New chat", and Cancel, which is the one way to throw the draft away — then the
 * pane's context row folded to one chip naming the source, branch and device it will use (the last launch's) until
 * tapped, then the composer box itself with everything the pane's has, and the server's words under it when a
 * launch is refused. The canvas is the pane's, so the box sits on the same ground as it does in the app.
 */
@Composable
fun QuickComposerSheet(
    state: NewAgentUiState,
    commands: SlashCatalog,
    plusMenu: ComposerMenuActions?,
    contextExpanded: Boolean,
    onExpandContext: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onCancelSend: () -> Unit,
    onPrompt: (String) -> Unit,
    onAddAttachments: (List<PendingAttachment>) -> Unit = {},
    onRemoveAttachment: (PendingAttachment) -> Unit = {},
    onRemoveFile: (PendingFile) -> Unit = {},
    onRetryFile: (PendingFile) -> Unit = {},
    onAttachmentError: (String) -> Unit = {},
    onDismissError: () -> Unit = {},
    onModePill: (ModePills.Pill?) -> Unit = {},
    onRepo: () -> Unit = {},
    onBranch: () -> Unit = {},
    onDevice: () -> Unit = {},
    onModel: () -> Unit = {},
) {
    SheetFrame(onDismiss) {
        SheetTitle(onCancel = onCancel)
        if (contextExpanded) {
            SelectorRow {
                SelectorChip(state.repoLabel, onClick = onRepo, icon = if (state.noRepo) CursorIcons.Cloud else CursorIcons.Repo, modifier = Modifier.weight(1f, fill = false))
                if (!state.noRepo) SelectorChip(state.branchLabel, onClick = onBranch, icon = CursorIcons.GitBranch)
                SelectorChip(state.deviceLabel, onClick = onDevice, icon = deviceIcon(state.selectedDevice))
            }
        } else {
            // One chip for the three: what the chat will run against, as the last launch left it, until it is opened.
            val summary = listOfNotNull(state.repoLabel, state.branchLabel.takeUnless { state.noRepo }, state.deviceLabel).joinToString(" · ")
            SelectorRow {
                SelectorChip(summary, onClick = onExpandContext, icon = if (state.noRepo) CursorIcons.Cloud else CursorIcons.Repo, modifier = Modifier.weight(1f, fill = false).testTag(QuickComposerTags.CONTEXT))
            }
        }
        LaunchRefusedHaptic(state)
        ComposerBox(
            value = state.prompt,
            onValueChange = onPrompt,
            placeholder = "Ask Cursor to build, fix bugs, explore",
            onSend = onSend,
            canSend = state.canLaunch,
            isSending = state.isLaunching,
            onCancelSend = onCancelSend,
            minLines = 2,
            plusMenu = plusMenu,
            commands = commands,
            attachments = state.attachments,
            onRemoveAttachment = onRemoveAttachment,
            onAddAttachments = onAddAttachments,
            onAttachmentError = onAttachmentError,
            files = state.files,
            onRemoveFile = onRemoveFile,
            fileUploads = state.fileUploads,
            onRetryFile = onRetryFile,
            sendHint = state.uploadHint,
            modelLabel = state.modelLabel,
            onModel = onModel,
            modePill = if (state.planMode) ModePills.Pill.Plan else null,
            onModePill = onModePill,
            focusOnOpen = true,
        )
        state.error?.let { ComposerErrorLine(it, state.errorAsked, onDismissError) }
    }
}

/** The sheet's first row: the cube where the sidebar has it, the title in the header voice, Cancel at the end. */
@Composable
private fun SheetTitle(onCancel: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CursorIcons.Cube, "Cursor", tint = colors.iconPrimary, modifier = Modifier.padding(start = 2.dp).size(CursorDimens.logo))
        Spacer(Modifier.width(10.dp))
        Text("New chat", style = type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(
            "Cancel",
            style = type.base,
            color = colors.textSecondary,
            modifier = Modifier
                .pressable(onCancel, CursorTheme.shapes.base)
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .testTag(QuickComposerTags.CANCEL),
        )
    }
}

/**
 * The window: a scrim the launcher shows through, which dismisses on a tap, and the sheet on the app's canvas at the
 * bottom with the app's sheet corners, resting on the keyboard while there is one and on the navigation bar
 * otherwise. The composer's gutter is the sheet's inner margin, and under the box the same gap the follow-up
 * composer keeps above the bar, so the box is placed as it is in a chat.
 */
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = CursorTheme.colors
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "Dismiss", onClick = onDismiss),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = CursorDimens.composerMaxWidth + CursorDimens.composerGutter * 2)
                .fillMaxWidth()
                .background(colors.canvas, CursorTheme.shapes.sheet)
                .keyboardInsetPadding()
                .padding(horizontal = CursorDimens.composerGutter)
                .padding(top = 10.dp, bottom = CursorDimens.composerBottomGap)
                .testTag(QuickComposerTags.SHEET),
            content = content,
        )
    }
}