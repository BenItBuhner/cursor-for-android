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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import com.cursorforandroid.ui.components.CursorIconButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SectionLabel
import com.cursorforandroid.ui.components.CursorTopBar
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    onOpenSidebar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)

    Column(modifier.fillMaxSize().background(colors.canvas)) {
        CursorTopBar(
            title = "Settings",
            leading = { if (onOpenSidebar != null) CursorIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = onOpenSidebar) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            SectionLabel("Account", Modifier.padding(horizontal = 0.dp))
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user, 40.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, style = type.bodyMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (isDemo) "Demo mode · local mock backend" else listOfNotNull(user.email, "Key: ${user.apiKeyName}").joinToString(" · "),
                            style = type.caption,
                            color = colors.textPlaceholder,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HairlineDivider()
                LinkRow("Manage API keys", CursorEndpoints.DASHBOARD_API_KEYS, uriHandler::openUri)
                HairlineDivider()
                LinkRow("Open cursor.com/agents", "https://cursor.com/agents", uriHandler::openUri)
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Appearance", Modifier.padding(horizontal = 0.dp))
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setThemeMode(mode) } }, CursorTheme.shapes.lg).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                when (mode) { ThemeMode.System -> "Match system"; ThemeMode.Dark -> "Cursor Dark"; ThemeMode.Light -> "Cursor Light" },
                                style = type.body, color = colors.textPrimary,
                            )
                            Text(
                                when (mode) { ThemeMode.System -> "Follow the device theme"; ThemeMode.Dark -> "Anysphere dark palette, #141414 canvas"; ThemeMode.Light -> "Anysphere light palette, #FCFCFC canvas" },
                                style = type.caption, color = colors.textPlaceholder,
                            )
                        }
                        if (themeMode == mode) Icon(Icons.Outlined.Check, null, tint = colors.accentBlue, modifier = Modifier.size(18.dp))
                    }
                    if (index != ThemeMode.entries.lastIndex) HairlineDivider(Modifier.padding(horizontal = 16.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("About", Modifier.padding(horizontal = 0.dp))
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                HairlineDivider()
                InfoRow("API", "api.cursor.com · Cloud Agents v1 (+ v0 transcript)")
                HairlineDivider()
                LinkRow("Cloud Agents API docs", "https://cursor.com/docs/cloud-agent/api/endpoints", uriHandler::openUri)
                HairlineDivider()
                Text(
                    "Unofficial community client for Cursor Cloud Agents. Not affiliated with Anysphere, Inc. JetBrains Mono is bundled under the SIL Open Font License.",
                    style = type.caption, color = colors.textPlaceholder, modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            CursorButton(
                text = if (isDemo) "Leave demo" else "Sign out",
                onClick = { scope.launch { graph.signOut() } },
                destructive = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable({ open(url) }, CursorTheme.shapes.lg).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = colors.textPlaceholder, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CursorTheme.typography.secondary, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
