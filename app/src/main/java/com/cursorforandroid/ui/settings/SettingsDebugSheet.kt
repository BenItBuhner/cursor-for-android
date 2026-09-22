package com.cursorforandroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.theme.CursorTheme

/** The debug sheet's words, shared with its tests. */
object SettingsDebugCopy {
    const val TITLE = "Debug"
    const val GROUP_DIAGNOSTICS = "Diagnostics"
    const val GROUP_ABOUT = "About"
    const val GROUP_CREDITS = "Credits"
    const val API = "API"
    const val API_DOCS = "API documentation"
    const val SOURCE = "Source code and releases"
    const val API_DOCS_URL = "https://cursor.com/docs/cloud-agent/api/endpoints"
}

/**
 * Behind a long press on the version row: what support asks for and what the build is made of, kept off the
 * settings list itself. The two diagnostics exports (each one tap to the share sheet, see [ProjectDiagnosticsRow]
 * and [TranscriptDiagnosticsRow]) and the tap that writes both into the Cursor for Android Project's context store
 * ([SendDiagnosticsRow]), the About rows — which APIs this build speaks, the documentation, the source and its
 * releases, the notes of the release the updater last found — and the licenses of what the app bundles.
 *
 * [extendedMode] is the screen's reading of the mode (see [SettingsScreen]); the updater's state is a `StateFlow`,
 * read at once. So the sheet holds nothing that could still be on its way when it is first drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDebugSheet(graph: AppGraph, isDemo: Boolean, extendedMode: Boolean, open: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val update by graph.updates.state.collectAsStateWithLifecycle()
    CursorSheet(onDismiss = onDismiss) { _ ->
        SheetHeader(SettingsDebugCopy.TITLE)
        Column(Modifier.testTag(SettingsTags.DEBUG_SHEET).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            Group(SettingsDebugCopy.GROUP_DIAGNOSTICS)
            // The exports read what the app already holds and say which mode it held it under; neither needs the mode.
            // Sending them to the Project writes to the account's store, which only the mode does.
            SettingsCard {
                ProjectDiagnosticsRow(graph)
                HairlineDivider()
                TranscriptDiagnosticsRow(graph)
                HairlineDivider()
                SendDiagnosticsRow(graph, enabled = extendedMode && !isDemo)
                HairlineDivider()
                DeepRefreshRow(graph)
            }

            Group(SettingsDebugCopy.GROUP_ABOUT)
            SettingsCard {
                SettingsRow(
                    title = SettingsDebugCopy.API,
                    description = if (extendedMode && !isDemo) "Cloud Agents v1 · v0 transcript · Cursor account service" else "Cloud Agents v1 · v0 transcript",
                )
                HairlineDivider()
                LinkRow(SettingsDebugCopy.API_DOCS, SettingsDebugCopy.API_DOCS_URL, open)
                HairlineDivider()
                LinkRow(SettingsDebugCopy.SOURCE, GitHubReleasesClient.releasesPageUrl(BuildConfig.GITHUB_REPO), open)
                update.release?.takeIf { it.htmlUrl.isNotBlank() }?.let { release ->
                    HairlineDivider()
                    LinkRow("What's new in ${release.versionName}", release.htmlUrl, open)
                }
            }

            Group(SettingsDebugCopy.GROUP_CREDITS)
            Text(SettingsCopy.CREDITS, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 2.dp))
        }
    }
}
