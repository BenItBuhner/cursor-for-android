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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * The panel's contents: a header row, then every registered section as a collapsible group — or, while a file is
 * open from Files or Changes, the file viewer in their place. Sections that the mode or the chat cannot feed show
 * their named state under their header instead of disappearing (spec §7: "every section shows a named degraded
 * state").
 */
@Composable
fun ConversationPanel(
    state: PanelState,
    actions: PanelActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    registry: PanelRegistry = remember { PanelRegistry.default() },
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val file = state.browser.file
    Column(modifier.fillMaxSize().testTag("conversation-panel")) {
        if (file != null) {
            FileViewerScreen(file, onBack = actions::closeFile, onOpenUrl = actions::openUrl)
            return@Column
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state.agent?.name ?: "Chat", style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = state.agent?.let { a -> listOfNotNull(a.repoShortName, a.branchName).joinToString(" · ") }?.ifBlank { null }
                if (subtitle != null) Text(subtitle, style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlatIconButton(CursorIcons.Close, "Close panel", onClick = onClose)
        }
        HairlineDivider()
        LazyColumn(Modifier.fillMaxSize().testTag("panel-sections"), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(registry.sections, key = { it.id.name }) { section ->
                PanelSectionView(section, state, actions)
            }
        }
    }
}

@Composable
private fun PanelSectionView(section: PanelSection, state: PanelState, actions: PanelActions) {
    val availability = section.availability(state.capabilities, state)
    var expanded by rememberSaveable("panel-section-${section.id.name}") { mutableStateOf(section.expandedByDefault) }
    // What the section needs is asked for when it is opened, and again when its chat changes under it.
    LaunchedEffect(expanded, availability is SectionAvailability.Available, state.prUrl, state.agentId) {
        if (expanded && availability is SectionAvailability.Available) section.onOpen(actions)
    }
    val hint = when (availability) {
        is SectionAvailability.Available -> section.hint(state)
        is SectionAvailability.RequiresExtended -> if (state.capabilities.anyExtended) "Not yet" else "Extended mode"
        is SectionAvailability.NotForThisChat -> "—"
    }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(section, hint, expanded, onToggle = { expanded = !expanded })
        AnimatedVisibility(visible = expanded) {
            when (availability) {
                is SectionAvailability.Available -> section.content(state, actions)
                is SectionAvailability.RequiresExtended -> RequiresExtendedRow(availability, extendedOn = state.capabilities.anyExtended, modifier = Modifier.padding(bottom = 4.dp))
                is SectionAvailability.NotForThisChat -> EmptyRow(availability.reason, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 12.dp))
    }
}

/**
 * The [PanelActions] for a live panel: the view model's loads and the platform's clipboard, browser and share
 * sheet. [onToast] surfaces confirmations on the screen's own snackbar.
 */
@Composable
fun rememberPanelActions(viewModel: PanelViewModel, onToast: (String) -> Unit): PanelActions {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    return remember(viewModel) {
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
            override fun notify(message: String) {
                if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
