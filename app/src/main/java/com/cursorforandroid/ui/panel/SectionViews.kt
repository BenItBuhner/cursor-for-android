package com.cursorforandroid.ui.panel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentUsage
import com.cursorforandroid.domain.Artifact
import com.cursorforandroid.domain.ChangedFile
import com.cursorforandroid.domain.ChangedFileStatus
import com.cursorforandroid.domain.CheckConclusion
import com.cursorforandroid.domain.CheckRun
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.PullRequestState
import com.cursorforandroid.domain.PullRequestView
import com.cursorforandroid.domain.RepoEntry
import com.cursorforandroid.domain.ReviewVerdict
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.ScmHost
import com.cursorforandroid.domain.TokenUsage
import com.cursorforandroid.domain.ToolNames
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptContent
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.ImageBlock
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.Pill
import com.cursorforandroid.ui.components.PullRequestPill
import com.cursorforandroid.ui.components.VideoBlock
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.conversation.DiffBlock
import com.cursorforandroid.ui.conversation.LineCounts
import com.cursorforandroid.ui.conversation.QuestionCard
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat

// -- Overview -------------------------------------------------------------------------------------------------------

/** What the run is doing, in a word, for the Overview and the header hint. */
internal fun statusLabel(state: PanelState): String {
    val agent = state.agent
    return when {
        agent?.isArchived == true -> "Archived"
        state.runStatus == RunStatus.CREATING -> "Starting"
        state.runStatus == RunStatus.RUNNING || state.isStreaming || agent?.isRunning == true -> "Working"
        state.runStatus == RunStatus.FINISHED -> "Finished"
        state.runStatus == RunStatus.ERROR -> "Failed"
        state.runStatus == RunStatus.CANCELLED -> "Cancelled"
        state.runStatus == RunStatus.EXPIRED -> "Expired"
        agent?.runStatus?.isTerminal == true -> agent.runStatus.name.lowercase().replaceFirstChar { it.uppercase() }
        agent != null -> "Idle"
        else -> "Loading"
    }
}

@Composable
internal fun OverviewSection(state: PanelState, actions: PanelActions) {
    val agent = state.agent
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (agent == null) {
            LoadingRow("Loading the chat…")
            return@Column
        }
        FactRow("Status", statusLabel(state))
        agent.repoSlug?.let { slug -> FactRow("Repository", slug, onClick = agent.repoUrl?.let { url -> { actions.openUrl(url) } }) }
        agent.branchName?.let { FactRow("Branch", it, onClick = { actions.copyText(it, "Branch name copied") }) }
        agent.startingRef?.takeIf { it != agent.branchName }?.let { FactRow("Base", it) }
        agent.modelName?.let { FactRow("Model", it) }
        FactRow("Environment", environmentLabel(agent))
        TimeFormat.duration(agent.durationMs)?.let { FactRow("Worked", it) }
        agent.createdAtMillis.takeIf { it > 0 }?.let { FactRow("Started", TimeFormat.date(it)) }
        val changes = state.content.changes
        if (changes.isNotEmpty()) {
            val stats = listOfNotNull(
                "${changes.size} ${if (changes.size == 1) "file" else "files"}",
                changes.mapNotNull { it.linesAdded }.takeIf { it.size == changes.size }?.sum()?.takeIf { it > 0 }?.let { "+$it" },
                changes.mapNotNull { it.linesRemoved }.takeIf { it.size == changes.size }?.sum()?.takeIf { it > 0 }?.let { "-$it" },
            )
            FactRow("Changed", stats.joinToString(" "))
        }
        agent.source?.let { FactRow("Started from", it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() }) }
    }
}

private fun environmentLabel(agent: Agent): String = when (agent.envType) {
    EnvType.CLOUD -> "Cloud"
    EnvType.POOL -> listOfNotNull("Team pool", agent.envName).joinToString(" · ")
    EnvType.MACHINE -> listOfNotNull("Remote Control", agent.envName).joinToString(" · ")
    EnvType.UNKNOWN -> agent.envName ?: "Unknown"
}

// -- Pending question -----------------------------------------------------------------------------------------------

@Composable
internal fun PendingQuestionSection(state: PanelState, actions: PanelActions) {
    val question = state.content.pendingQuestion
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (question != null) {
            QuestionCard(question, pending = true, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), onOpenInBrowser = { actions.openUrl(CursorEndpoints.webUrl(state.agentId)) })
        }
        RequiresExtendedRow(SectionAvailability.RequiresExtended(ANSWER_REASON), extendedOn = state.capabilities.anyExtended)
    }
}

internal const val ANSWER_REASON = "Answering the agent's question from here goes through SubmitInteractionResponseBackgroundComposer, an undocumented endpoint"

// -- Changes --------------------------------------------------------------------------------------------------------

@Composable
internal fun ChangesSection(state: PanelState, actions: PanelActions) {
    val pr = state.pullRequest
    val prFiles = pr.valueOrNull?.files.orEmpty()
    val local = state.content.changes
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        when {
            prFiles.isNotEmpty() -> {
                PanelCaption("From the pull request · ${prFiles.size} ${if (prFiles.size == 1) "file" else "files"}")
                prFiles.forEach { file -> ChangedFileRow(file) }
                if (local.isNotEmpty()) PanelNote("The edits streamed in this conversation are in the pull request above; open a file under Files › Touched to see each one.")
            }
            local.isNotEmpty() -> {
                PanelCaption("From this conversation · ${local.size} ${if (local.size == 1) "file" else "files"}")
                local.forEach { change -> FileChangeRow(change, onOpen = { actions.openChange(change) }) }
                when {
                    pr is RemoteLoad.Loading -> LoadingRow("Reading the pull request's files…")
                    pr is RemoteLoad.Failed -> PanelNote("The pull request's own files could not be read: ${pr.message}")
                    pr is RemoteLoad.Unsupported -> PanelNote(pr.reason)
                }
            }
            state.hasPullRequest -> when (pr) {
                RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading the pull request's files…")
                is RemoteLoad.Failed -> FailedRow(pr.message, onRetry = if (pr.retryable) ({ actions.loadPullRequest(force = true) }) else null, secondaryLabel = "Open in browser", onSecondary = { state.prUrl?.let(actions::openUrl) })
                is RemoteLoad.Unsupported -> UnsupportedRow(pr.reason, pr.url, actions::openUrl)
                is RemoteLoad.Loaded -> EmptyRow("The pull request changes no files")
            }
            else -> EmptyRow("No changes yet", "Edits arrive here as the agent makes them; a pull request's files once it opens one.")
        }
    }
}

/** A pull request's file: its status glyph, name and counts; opens onto its patch. */
@Composable
private fun ChangedFileRow(file: ChangedFile) {
    var expanded by rememberSaveable("pr-file-${file.path}") { mutableStateOf(false) }
    val colors = CursorTheme.colors
    val (icon, tint) = when (file.status) {
        ChangedFileStatus.Added, ChangedFileStatus.Copied -> CursorIcons.Plus to colors.gitAdded
        ChangedFileStatus.Removed -> CursorIcons.Trash to colors.gitRemoved
        ChangedFileStatus.Renamed -> CursorIcons.ArrowUp to colors.gitModified
        else -> CursorIcons.Pencil to colors.gitModified
    }
    Column {
        PanelRow(
            title = file.name,
            icon = icon,
            iconTint = tint,
            subtitle = listOfNotNull(file.previousPath?.let { "was $it" }, file.path.takeIf { it != file.name }).firstOrNull(),
            trailing = { LineCounts(file.additions, file.deletions) },
            onClick = if (file.patch != null) ({ expanded = !expanded }) else null,
            modifier = Modifier.testTag("changed-file"),
        )
        AnimatedVisibility(visible = expanded && file.patch != null) {
            DiffBlock(ToolPayload.FileDiff(file.path, file.patch.orEmpty(), file.additions, file.deletions), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), showHeader = false)
        }
        if (file.patch == null) PanelNote("${file.status.label}; ${if (file.status == ChangedFileStatus.Removed) "nothing to show" else "binary or too large to show inline"}.")
    }
}

/** A file the agent changed in this conversation: the strongest word for what happened to it, and its counts. */
@Composable
private fun FileChangeRow(change: TranscriptContent.FileChange, onOpen: () -> Unit) {
    val colors = CursorTheme.colors
    val (icon, tint) = when (change.touch) {
        TranscriptContent.Touch.Created -> CursorIcons.Plus to colors.gitAdded
        TranscriptContent.Touch.Deleted -> CursorIcons.Trash to colors.gitRemoved
        else -> CursorIcons.Pencil to colors.gitModified
    }
    PanelRow(
        title = change.name,
        icon = icon,
        iconTint = tint,
        subtitle = change.path.takeIf { it != change.name },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (change.diffs.isEmpty()) Text(change.touch.name, style = CursorTheme.typography.small, color = colors.textQuaternary) else LineCounts(change.linesAdded, change.linesRemoved)
                if (change.diffs.isNotEmpty() || change.contentAfter != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(14.dp))
                }
            }
        },
        onClick = if (change.diffs.isNotEmpty() || change.contentAfter != null) onOpen else null,
        modifier = Modifier.testTag("file-change"),
    )
}

// -- Pull request ---------------------------------------------------------------------------------------------------

@Composable
internal fun PullRequestSection(state: PanelState, actions: PanelActions) {
    val url = state.prUrl
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (url == null) {
            EmptyRow("No pull request yet", "The agent opens one when it pushes with auto-create on, or when asked to.")
            return@Column
        }
        when (val pr = state.pullRequest) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading the pull request…")
            is RemoteLoad.Unsupported -> UnsupportedRow(pr.reason, pr.url ?: url, actions::openUrl)
            is RemoteLoad.Failed -> FailedRow(pr.message, onRetry = if (pr.retryable) ({ actions.loadPullRequest(force = true) }) else null, secondaryLabel = "Open in browser", onSecondary = { actions.openUrl(url) })
            is RemoteLoad.Loaded -> PullRequestBody(pr.value, actions)
        }
    }
}

@Composable
private fun PullRequestBody(view: PullRequestView, actions: PanelActions) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val details = view.details
    Column(Modifier.fillMaxWidth().testTag("pull-request")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(details.title, style = type.title, color = colors.textPrimary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PullRequestPill(details.state)
                if (view.checks.isNotEmpty()) {
                    val summary = view.checksSummary
                    val tint = when {
                        summary.failed > 0 -> colors.red
                        summary.pending > 0 -> colors.orange
                        else -> colors.green
                    }
                    Pill(summary.label, icon = if (summary.failed > 0) CursorIcons.Warning else CursorIcons.Check, tint = tint, fill = tint.copy(alpha = 0.12f))
                }
                view.reviewDecision?.let { verdict ->
                    val tint = when (verdict) {
                        ReviewVerdict.Approved -> colors.green
                        ReviewVerdict.ChangesRequested -> colors.red
                        else -> colors.textSecondary
                    }
                    Pill(verdict.label, tint = tint, fill = tint.copy(alpha = 0.12f))
                }
                details.labels.forEach { Pill(it) }
            }
            val meta = listOfNotNull(
                "#${details.number}",
                details.author?.let { "by $it" },
                listOfNotNull(details.headRef, details.baseRef).takeIf { it.size == 2 }?.joinToString(" → "),
                details.lineStats,
                details.changedFiles?.let { "$it ${if (it == 1) "file" else "files"}" },
                details.commits?.let { "$it ${if (it == 1) "commit" else "commits"}" },
            )
            Text(meta.joinToString(" · "), style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textQuaternary)
        }
        PullRequestDescription(details.body)
        if (view.checks.isNotEmpty()) {
            PanelCaption("Checks")
            view.checks.forEach { check -> CheckRow(check, onOpen = check.detailsUrl?.let { url -> { actions.openUrl(url) } }) }
        }
        if (view.reviews.isNotEmpty()) {
            PanelCaption("Reviews")
            view.reviews.forEach { review ->
                val tint = when (review.verdict) {
                    ReviewVerdict.Approved -> colors.green
                    ReviewVerdict.ChangesRequested -> colors.red
                    else -> colors.iconTertiary
                }
                PanelRow(
                    title = listOfNotNull(review.author, review.verdict.label.lowercase()).joinToString(" "),
                    icon = if (review.verdict == ReviewVerdict.Approved) CursorIcons.Check else CursorIcons.Eye,
                    iconTint = tint,
                    subtitle = review.body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
                    trailing = review.submittedAtMillis?.let { at -> { Text(TimeFormat.relativeShort(at), style = type.small, color = colors.textQuaternary) } },
                )
            }
        }
        if (view.threads.isNotEmpty()) {
            PanelCaption("Review threads · ${view.threads.size}")
            view.threads.forEach { thread ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).cursorSurface(colors.fillFaint, Color.Transparent, CursorTheme.shapes.lg).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val where = listOfNotNull(thread.name, thread.line?.let { "L$it" }).joinToString(":")
                    if (where.isNotEmpty()) Text(where, style = type.code, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    thread.comments.forEach { comment ->
                        Column {
                            Text(listOfNotNull(comment.author, comment.createdAtMillis?.let(TimeFormat::relativeShort)).joinToString(" · "), style = type.small, color = colors.textQuaternary)
                            MarkdownText(comment.body, style = type.base, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
        if (view.missing.isNotEmpty()) PanelNote("Could not read the ${view.missing.sorted().joinToString(", ")} from ${details.host.label}.")
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CursorButton("Open in browser", { actions.openUrl(details.url) }, icon = CursorIcons.ExternalLink, height = 30.dp)
            CursorButton("Refresh", { actions.loadPullRequest(force = true) }, icon = CursorIcons.Refresh, height = 30.dp)
        }
    }
}

/** The body as written, folded past a dozen lines behind "Show more" so a long description does not bury the checks. */
@Composable
private fun PullRequestDescription(body: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    if (body.isBlank()) {
        PanelNote("No description.")
        return
    }
    val lines = remember(body) { body.lines() }
    var showAll by rememberSaveable(body.length) { mutableStateOf(lines.size <= DESCRIPTION_FOLD_LINES) }
    val shown = if (showAll) body else lines.take(DESCRIPTION_FOLD_LINES).joinToString("\n")
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        MarkdownText(shown, style = type.base, color = colors.textSecondary, modifier = Modifier.testTag("pr-body"))
        if (!showAll) {
            Text(
                "Show more",
                style = type.small,
                color = colors.link,
                modifier = Modifier.pressable({ showAll = true }, CursorTheme.shapes.base).padding(vertical = 4.dp),
            )
        }
    }
}

private const val DESCRIPTION_FOLD_LINES = 12

@Composable
private fun CheckRow(check: CheckRun, onOpen: (() -> Unit)?) {
    val colors = CursorTheme.colors
    val (icon, tint) = when {
        check.isPending -> CursorIcons.Clock to colors.orange
        check.isFailure -> CursorIcons.Warning to colors.red
        check.conclusion == CheckConclusion.Success -> CursorIcons.Check to colors.green
        else -> CursorIcons.Check to colors.iconTertiary
    }
    PanelRow(
        title = check.name,
        icon = icon,
        iconTint = tint,
        subtitle = listOfNotNull(check.source, if (check.isPending) "Running" else check.conclusion?.label).joinToString(" · ").ifEmpty { null },
        trailing = if (onOpen != null) ({ Icon(CursorIcons.ExternalLink, null, tint = colors.iconQuaternary, modifier = Modifier.size(13.dp)) }) else null,
        onClick = onOpen,
        modifier = Modifier.testTag("check-run"),
    )
}

// -- Files ----------------------------------------------------------------------------------------------------------

internal enum class FilesTab(val label: String) { Touched("Touched"), Repository("Repository"), Workspace("Workspace") }

@Composable
internal fun FilesSection(state: PanelState, actions: PanelActions) {
    var tab by rememberSaveable { mutableStateOf(FilesTab.Touched) }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilesTab.entries.forEach { t -> TabChip(t.label, selected = tab == t, onClick = { tab = t }) }
        }
        when (tab) {
            FilesTab.Touched -> TouchedFiles(state, actions)
            FilesTab.Repository -> RepositoryBrowser(state, actions)
            FilesTab.Workspace -> RequiresExtendedRow(SectionAvailability.RequiresExtended("The agent's live workspace (ListWorkspaceFiles, ReadBinaryFile) is only reachable through undocumented endpoints"), extendedOn = state.capabilities.anyExtended)
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.full
    Text(
        label,
        style = CursorTheme.typography.small,
        color = if (selected) colors.textPrimary else colors.textTertiary,
        modifier = Modifier
            .cursorSurface(if (selected) colors.fillActive else colors.fillFaint, if (selected) colors.strokeStrong else Color.Transparent, shape)
            .pressable(onClick, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("files-tab-$label"),
    )
}

@Composable
private fun TouchedFiles(state: PanelState, actions: PanelActions) {
    val touched = state.content.touched
    if (touched.isEmpty()) {
        EmptyRow("Nothing touched yet", "Files the agent reads, writes, edits or deletes are listed here as it goes.")
        return
    }
    val colors = CursorTheme.colors
    touched.forEach { file ->
        PanelRow(
            title = file.name,
            icon = CursorIcons.File,
            iconTint = if (file.wasChanged) colors.gitModified else colors.iconTertiary,
            subtitle = file.path.takeIf { it != file.name },
            trailing = {
                Text(
                    file.kinds.joinToString(", ") { it.name },
                    style = CursorTheme.typography.small,
                    color = colors.textQuaternary,
                )
            },
            onClick = { actions.openTouched(file.path) },
            modifier = Modifier.testTag("touched-file"),
        )
    }
}

@Composable
private fun RepositoryBrowser(state: PanelState, actions: PanelActions) {
    val browser = state.browser
    val agent = state.agent
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val repoUrl = agent?.repoUrl
    if (repoUrl == null) {
        EmptyRow("No repository", "This chat runs without one.")
        return
    }
    if (state.isDemo) {
        EmptyRow("The demo has no repository to browse")
        return
    }
    val host = browser.host
    if (host == null) {
        RequiresExtendedRow(SectionAvailability.RequiresExtended("Browsing a repository on ${ScmHost.of(repoUrl).label} goes through Cursor's undocumented SCM endpoints"), extendedOn = state.capabilities.anyExtended)
        return
    }
    LaunchedEffect(browser.repoUrl, browser.ref) { if (browser.listing is RemoteLoad.Idle) actions.browse("") }
    // Breadcrumb: the repository's name, then each directory on the way down; the current one is not a link.
    val segments = browser.path.split('/').filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(agent?.repoShortName ?: "repository", style = type.small, color = if (segments.isEmpty()) colors.textSecondary else colors.link, modifier = Modifier.pressable({ actions.browse("") }, CursorTheme.shapes.sm, enabled = segments.isNotEmpty()).padding(2.dp))
        segments.forEachIndexed { index, segment ->
            Text(" / ", style = type.small, color = colors.textQuaternary)
            val last = index == segments.lastIndex
            Text(segment, style = type.small, color = if (last) colors.textSecondary else colors.link, modifier = Modifier.pressable({ actions.browse(segments.take(index + 1).joinToString("/")) }, CursorTheme.shapes.sm, enabled = !last).padding(2.dp))
        }
        browser.ref?.let {
            Spacer(Modifier.width(8.dp))
            Pill(it, icon = CursorIcons.GitBranch, tint = colors.textTertiary, fill = colors.fillFaint)
        }
    }
    when (val listing = browser.listing) {
        RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading ${host.label}…")
        is RemoteLoad.Failed -> FailedRow(listing.message, onRetry = if (listing.retryable) ({ actions.browse(browser.path, force = true) }) else null)
        is RemoteLoad.Unsupported -> UnsupportedRow(listing.reason, listing.url, actions::openUrl)
        is RemoteLoad.Loaded -> {
            if (!browser.isAtRoot) PanelRow(title = "..", icon = CursorIcons.Folder, onClick = actions::browseUp, modifier = Modifier.testTag("repo-up"))
            if (listing.value.entries.isEmpty()) EmptyRow("Empty directory")
            listing.value.entries.forEach { entry -> RepoEntryRow(entry, onClick = { if (entry.isDirectory) actions.browse(entry.path) else actions.openRepoFile(entry.path) }) }
        }
    }
}

@Composable
private fun RepoEntryRow(entry: RepoEntry, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    PanelRow(
        title = entry.name,
        icon = if (entry.isDirectory) CursorIcons.Folder else iconFor(entry.extension),
        iconTint = if (entry.isDirectory) colors.iconSecondary else colors.iconTertiary,
        trailing = { Text(if (entry.isDirectory) "" else formatBytes(entry.sizeBytes).orEmpty(), style = CursorTheme.typography.small, color = colors.textQuaternary) },
        onClick = onClick,
        modifier = Modifier.testTag(if (entry.isDirectory) "repo-dir" else "repo-file"),
    )
}

private fun iconFor(extension: String): ImageVector = when (extension) {
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> CursorIcons.Image
    "mp4", "webm", "mov" -> CursorIcons.Video
    "md", "markdown", "txt" -> CursorIcons.Book
    "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx", "go", "rs", "swift", "c", "cpp", "h", "rb", "sh" -> CursorIcons.Code
    else -> CursorIcons.File
}

// -- Images and media -----------------------------------------------------------------------------------------------

/** Everything visual the chat produced or was given, merged by path so an artifact that is also a recording shows once. */
internal fun mediaTiles(state: PanelState): List<MediaTile> {
    val tiles = ArrayList<MediaTile>()
    val seen = HashSet<String>()
    state.content.media.forEach { item ->
        when (item) {
            is TranscriptContent.MediaItem.Image -> item.image.src?.let { src -> if (seen.add(src)) tiles += MediaTile.Image(src, item.image.description ?: item.image.path?.let(ToolNames::basename) ?: "Generated image", "Generated") }
            is TranscriptContent.MediaItem.Recording -> if (seen.add(item.recording.path)) tiles += MediaTile.Video(item.recording.path, ToolNames.basename(item.recording.path), "Recording")
        }
    }
    state.artifacts.valueOrNull.orEmpty().forEach { artifact ->
        if (!seen.add(artifact.vmPath)) return@forEach
        when (artifact.kind) {
            Artifact.Kind.Image -> tiles += MediaTile.Image(artifact.vmPath, artifact.name, "Artifact")
            Artifact.Kind.Video -> tiles += MediaTile.Video(artifact.vmPath, artifact.name, "Artifact")
            else -> Unit
        }
    }
    state.promptImages.forEach { attachment ->
        val src = "file://${attachment.path}"
        if (seen.add(src)) tiles += MediaTile.Image(src, "Attached image", "Attached")
    }
    return tiles
}

/** How tall a gallery tile may be: a portrait screenshot is shrunk to it rather than taking the panel over. */
private val MediaTileHeight = 180.dp

internal sealed interface MediaTile {
    val src: String
    val caption: String
    val kind: String
    data class Image(override val src: String, override val caption: String, override val kind: String) : MediaTile
    data class Video(override val src: String, override val caption: String, override val kind: String) : MediaTile
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun MediaSection(state: PanelState, actions: PanelActions) {
    val tiles = remember(state.content.media, state.artifacts, state.promptImages) { mediaTiles(state) }
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (tiles.isEmpty()) {
            if (state.artifacts is RemoteLoad.Loading) LoadingRow("Looking for artifacts…") else EmptyRow("No images or recordings yet", "Generated images, screen recordings, published screenshots and the images attached to prompts gather here.")
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            val tileWidth = (maxWidth - 8.dp) / 2
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("media-grid")) {
                tiles.forEach { tile ->
                    Column(Modifier.width(tileWidth)) {
                        when (tile) {
                            is MediaTile.Image -> ImageBlock(tile.src, alt = tile.caption, heightCap = MediaTileHeight)
                            is MediaTile.Video -> VideoBlock(tile.src, poster = null, heightCap = MediaTileHeight)
                        }
                        Text(tile.caption, style = type.small, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(tile.kind, style = type.tiny, color = colors.textQuaternary, maxLines = 1)
                    }
                }
            }
        }
        if (state.artifacts is RemoteLoad.Loading) LoadingRow("Looking for more in the artifacts…")
    }
}

// -- Artifacts ------------------------------------------------------------------------------------------------------

@Composable
internal fun ArtifactsSection(state: PanelState, actions: PanelActions) {
    val colors = CursorTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        when (val artifacts = state.artifacts) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Listing artifacts…")
            is RemoteLoad.Failed -> FailedRow(artifacts.message, onRetry = { actions.loadArtifacts(force = true) })
            is RemoteLoad.Unsupported -> UnsupportedRow(artifacts.reason, artifacts.url, actions::openUrl)
            is RemoteLoad.Loaded -> {
                if (artifacts.value.isEmpty()) {
                    EmptyRow("Nothing published yet", "Files the agent saves under /opt/cursor/artifacts appear here.")
                } else {
                    artifacts.value.forEach { artifact ->
                        PanelRow(
                            title = artifact.name,
                            icon = when (artifact.kind) {
                                Artifact.Kind.Image -> CursorIcons.Image
                                Artifact.Kind.Video -> CursorIcons.Video
                                Artifact.Kind.Markdown, Artifact.Kind.Text -> CursorIcons.Book
                                Artifact.Kind.Other -> CursorIcons.File
                            },
                            subtitle = listOfNotNull(artifact.path.removePrefix("artifacts/").takeIf { it != artifact.name }, formatBytes(artifact.sizeBytes), artifact.updatedAtMillis?.let(TimeFormat::relativeShort)).joinToString(" · "),
                            trailing = { Icon(CursorIcons.ExternalLink, null, tint = colors.iconQuaternary, modifier = Modifier.size(13.dp)) },
                            onClick = { actions.openArtifact(artifact) },
                            modifier = Modifier.testTag("artifact"),
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    CursorButton("Refresh", { actions.loadArtifacts(force = true) }, icon = CursorIcons.Refresh, height = 28.dp)
                }
            }
        }
    }
}

// -- Usage ----------------------------------------------------------------------------------------------------------

@Composable
internal fun UsageSection(state: PanelState, actions: PanelActions) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        when (val usage = state.usage) {
            RemoteLoad.Idle, RemoteLoad.Loading -> LoadingRow("Reading usage…")
            is RemoteLoad.Failed -> FailedRow(usage.message, onRetry = { actions.loadUsage(force = true) })
            is RemoteLoad.Unsupported -> UnsupportedRow(usage.reason, usage.url, actions::openUrl)
            is RemoteLoad.Loaded -> UsageBody(usage.value)
        }
    }
}

@Composable
private fun UsageBody(usage: AgentUsage) {
    if (usage.isEmpty) {
        EmptyRow("No usage reported yet", "Token counts arrive once a run has finished.")
        return
    }
    val total = usage.total
    Column(Modifier.testTag("usage")) {
        FactRow("Total", TokenUsage.format(total.total))
        FactRow("Input", TokenUsage.format(total.inputTokens))
        FactRow("Output", TokenUsage.format(total.outputTokens))
        if (total.cacheReadTokens > 0) FactRow("Cache read", TokenUsage.format(total.cacheReadTokens))
        if (total.cacheWriteTokens > 0) FactRow("Cache write", TokenUsage.format(total.cacheWriteTokens))
        if (usage.runs.size > 1) {
            PanelCaption("By run")
            usage.runs.forEachIndexed { index, run ->
                FactRow("Run ${usage.runs.size - index}", "${TokenUsage.format(run.usage.total)} · in ${TokenUsage.format(run.usage.inputTokens)} · out ${TokenUsage.format(run.usage.outputTokens)}")
            }
        }
    }
}

// -- Share ----------------------------------------------------------------------------------------------------------

@Composable
internal fun ShareSection(state: PanelState, actions: PanelActions) {
    val url = state.agent?.url?.takeIf { it.isNotBlank() } ?: CursorEndpoints.webUrl(state.agentId)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        PanelRow("Copy link", icon = CursorIcons.Copy, subtitle = url, onClick = { actions.copyText(url, "Link copied") }, modifier = Modifier.testTag("share-copy"))
        PanelRow("Open on cursor.com", icon = CursorIcons.ExternalLink, onClick = { actions.openUrl(url) })
        PanelRow("Share…", icon = CursorIcons.Link, subtitle = "Through Android's share sheet", onClick = { actions.shareText(url) })
        state.prUrl?.let { pr -> PanelRow("Copy pull request link", icon = CursorIcons.GitPullRequest, subtitle = pr, onClick = { actions.copyText(pr, "Pull request link copied") }) }
        HairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        RequiresExtendedRow(SectionAvailability.RequiresExtended("A public share link (CreateAgentShare) that opens without a Cursor account"), extendedOn = state.capabilities.anyExtended)
    }
}

// -- Placeholders ---------------------------------------------------------------------------------------------------

@Composable
internal fun PlaceholderSection(reason: String, state: PanelState) {
    RequiresExtendedRow(SectionAvailability.RequiresExtended(reason), extendedOn = state.capabilities.anyExtended, modifier = Modifier.padding(bottom = 4.dp))
}

/** The hint beside "Pull request" while the section is closed. */
internal fun pullRequestHint(state: PanelState): String? {
    if (!state.hasPullRequest) return null
    val view = state.pullRequest.valueOrNull ?: return "#${state.prUrl?.substringAfterLast('/')}"
    val summary = view.checksSummary
    return listOfNotNull(
        view.details.state.label,
        when {
            summary.total == 0 -> null
            summary.failed > 0 -> "checks failed"
            summary.pending > 0 -> "checks running"
            else -> "checks passed"
        },
    ).joinToString(" · ")
}

internal fun pullRequestStateOrNull(state: PanelState): PullRequestState? = state.pullRequest.valueOrNull?.details?.state
