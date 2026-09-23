package com.cursorforandroid.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.domain.ReleaseNotes
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.MarkdownText
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.launch

/** The words of the What's new page and the two surfaces that lead to it, shared with their tests. */
object WhatsNewCopy {
    const val HEADER = "What's new"
    const val VIEW_ON_GITHUB = "View on GitHub"
    const val DONE = "Done"

    /** "What's new in 0.3.37": the row in Settings, the card in the sidebar, the title of the page. */
    fun title(versionName: String): String = "What's new in $versionName"

    /** "Released Sep 18, 2026" — the header's detail line, from the release's own publish time. */
    fun released(publishedAtMs: Long): String = "Released ${TimeFormat.date(publishedAtMs)}"

    /** The page reached with no notes to show: the version was never released as such, or GitHub could not be read. */
    fun unavailable(versionName: String): String = "No release notes for $versionName are available on this device yet."
    const val UNAVAILABLE_HINT = "The notes are read from the release on GitHub when the app is opened; the release page has everything."
}

object WhatsNewTags {
    const val PAGE = "whats_new_page"
    const val NOTES = "whats_new_notes"
    const val DONE = "whats_new_done"
    const val GITHUB = "whats_new_github"
}

/**
 * The installed version's release notes: the version and its release date in the header, the curated notes as
 * markdown beneath (the lead line, the headed sections, code spans and bullets as the release page shows them), and a
 * footer with the release page on GitHub and Done, the notes dissolving into it while there is more of them below.
 * Opening the page is what reads it — the row in Settings and the
 * card in the sidebar go for this version from here on — and Done reads it again on its way out, for a page reached
 * before the notes arrived.
 */
@Composable
fun WhatsNewScreen(graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val notes by graph.whatsNew.notes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val version = notes?.versionName ?: graph.whatsNew.installedVersionName
    // Read on arrival, once the notes are what is on screen; a page shown without them has nothing to have read.
    LaunchedEffect(notes?.versionName) {
        if (notes != null) graph.whatsNew.markRead()
    }
    fun done() {
        scope.launch { graph.whatsNew.markRead() }
        onBack()
    }
    Column(modifier.fillMaxSize().background(colors.canvas).testTag(WhatsNewTags.PAGE)) {
        CursorHeader(
            title = WhatsNewCopy.title(version),
            subtitle = notes?.publishedAtMs?.takeIf { it > 0 }?.let(WhatsNewCopy::released),
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .fadingVerticalScroll(surface = colors.canvas)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val current = notes
            if (current != null) {
                Notes(current)
            } else {
                Unavailable(version)
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CursorButton(
                WhatsNewCopy.VIEW_ON_GITHUB,
                icon = CursorIcons.ExternalLink,
                onClick = { uriHandler.openUri(notes?.htmlUrl?.takeIf { it.isNotBlank() } ?: GitHubReleasesClient.releasesPageUrl(BuildConfig.GITHUB_REPO)) },
                modifier = Modifier.testTag(WhatsNewTags.GITHUB),
            )
            Spacer(Modifier.weight(1f))
            CursorButton(WhatsNewCopy.DONE, onClick = ::done, primary = true, modifier = Modifier.testTag(WhatsNewTags.DONE))
        }
    }
}

/** The curated notes, in the transcript's markdown: the same headings, bullets and code spans a reply gets. */
@Composable
private fun Notes(notes: ReleaseNotes) {
    MarkdownText(
        notes.markdown,
        style = CursorTheme.typography.message,
        color = CursorTheme.colors.textPrimary,
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).testTag(WhatsNewTags.NOTES),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Unavailable(versionName: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 8.dp)) {
        Text(WhatsNewCopy.unavailable(versionName), style = type.base, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(WhatsNewCopy.UNAVAILABLE_HINT, style = type.small, color = colors.textTertiary)
    }
}
