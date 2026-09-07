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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.Avatar
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Settings in the desktop settings-page idiom: 12sp group labels, bordered cards of 38dp rows. */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    onOpenSidebar: (() -> Unit)?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorHeader(
            title = "Settings",
            leading = {
                when {
                    onBack != null -> FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack)
                    onOpenSidebar != null -> FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar)
                }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Group("Account")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, style = type.row, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (isDemo) "Demo · local mock backend" else listOfNotNull(user.email, user.apiKeyName).joinToString(" · "),
                            style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HairlineDivider()
                LinkRow("Manage API keys", CursorEndpoints.DASHBOARD_API_KEYS, uriHandler::openUri)
                HairlineDivider()
                LinkRow("Open cursor.com/agents", "https://cursor.com/agents", uriHandler::openUri)
            }

            Group("Appearance")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setThemeMode(mode) } }, CursorTheme.shapes.lg).height(38.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            when (mode) { ThemeMode.System -> "Match system"; ThemeMode.Dark -> "Cursor Dark"; ThemeMode.Light -> "Cursor Light" },
                            style = type.base, color = colors.textPrimary,
                        )
                        if (themeMode == mode) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    }
                    if (index != ThemeMode.entries.lastIndex) HairlineDivider(Modifier.padding(horizontal = 12.dp))
                }
            }

            Group("About")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                HairlineDivider()
                InfoRow("API", "Cloud Agents v1 · v0 transcript")
                HairlineDivider()
                LinkRow("API documentation", "https://cursor.com/docs/cloud-agent/api/endpoints", uriHandler::openUri)
            }
            Text(
                "Unofficial client for Cursor Cloud Agents; not affiliated with Anysphere, Inc. JetBrains Mono is bundled under the SIL Open Font License.",
                style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )

            Spacer(Modifier.height(20.dp))
            CursorButton(if (isDemo) "Leave demo" else "Sign out", onClick = { scope.launch { graph.signOut() } }, destructive = true)
        }
    }
}

@Composable
private fun Group(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 2.dp))
}

@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable({ open(url) }, CursorTheme.shapes.lg).height(38.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconQuaternary, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CursorTheme.typography.base, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
