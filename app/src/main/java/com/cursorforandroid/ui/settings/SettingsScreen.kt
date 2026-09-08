package com.cursorforandroid.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.BuildConfig
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.data.api.GitHubEndpoints
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.data.update.GitHubReleasesClient
import com.cursorforandroid.data.update.UpdateManager
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.domain.UpdatePhase
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.ui.agents.Avatar
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.launch
import java.util.Locale

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
    val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
    val session by graph.session.state.collectAsStateWithLifecycle()
    val credential = (session as? SessionState.SignedIn)?.credential

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
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.displayName, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (isDemo) "Demo · local mock backend" else listOfNotNull(user.email, user.apiKeyName).joinToString(" · "),
                            style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (credential != null) {
                    HairlineDivider()
                    InfoRow(
                        "Signed in with",
                        when (credential.method) {
                            SignInMethod.Cursor -> "Cursor account"
                            SignInMethod.ApiKey -> "API key"
                        },
                    )
                    if (credential.expiresAtMs != null) {
                        HairlineDivider()
                        // The key this app minted lapses on its own; a fresh sign-in issues a new one.
                        InfoRow("Key expires", TimeFormat.date(credential.expiresAtMs))
                    }
                }
                HairlineDivider()
                LinkRow(if (credential?.method == SignInMethod.Cursor) "Manage this app's key" else "Manage API keys", CursorEndpoints.DASHBOARD_API_KEYS, uriHandler::openUri)
                HairlineDivider()
                LinkRow("Open cursor.com/agents", "https://cursor.com/agents", uriHandler::openUri)
            }

            Group("Appearance")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    Row(
                        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setThemeMode(mode) } }, CursorTheme.shapes.lg).height(CursorDimens.listRow).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            when (mode) { ThemeMode.System -> "Match system"; ThemeMode.Dark -> "Cursor Dark"; ThemeMode.Light -> "Cursor Light" },
                            style = type.base, color = colors.textPrimary,
                        )
                        if (themeMode == mode) Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    }
                    if (index != ThemeMode.entries.lastIndex) HairlineDivider(Modifier.padding(horizontal = 14.dp))
                }
                // OLED only does anything while dark is on — Cursor Dark, or Match system when the phone is dark.
                if (themeMode != ThemeMode.Light) {
                    HairlineDivider(Modifier.padding(horizontal = 14.dp))
                    Row(
                        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setOledBlack(!oledBlack) } }, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("OLED black", style = type.base, color = colors.textPrimary)
                            Text(
                                if (themeMode == ThemeMode.System) {
                                    "True-black surfaces when the system is in dark theme."
                                } else {
                                    "True-black surfaces instead of Cursor Dark's charcoal."
                                },
                                style = type.small, color = colors.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        CursorToggle(checked = oledBlack, onCheckedChange = { scope.launch { graph.prefs.setOledBlack(it) } })
                    }
                }
            }

            if (!isDemo) {
                Group("Chats")
                CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    PinSyncRows(graph)
                }
            }

            Group("Notifications")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                NotificationRows(graph)
            }

            Group("Updates")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                UpdateRows(graph, uriHandler::openUri)
            }

            Group("GitHub")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                GitHubRows(graph)
            }

            Group("About")
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                InfoRow("API", "Cloud Agents v1 · v0 transcript")
                HairlineDivider()
                LinkRow("API documentation", "https://cursor.com/docs/cloud-agent/api/endpoints", uriHandler::openUri)
                HairlineDivider()
                LinkRow("Source code and releases", GitHubReleasesClient.releasesPageUrl(BuildConfig.GITHUB_REPO), uriHandler::openUri)
            }
            Text(
                "Unofficial client for Cursor Cloud Agents; not affiliated with Anysphere, Inc. JetBrains Mono is bundled under the SIL Open Font License; icons are derived from Lucide (ISC).",
                style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            )

            Spacer(Modifier.height(24.dp))
            CursorButton(if (isDemo) "Leave demo" else "Sign out", onClick = { scope.launch { graph.signOut() } }, destructive = true, icon = CursorIcons.SignOut)
        }
    }
}

/**
 * Live notification toggle plus the system pages it depends on: notification permission, and on Android 16 the
 * per-app Live Updates switch. The hints re-check when the screen resumes so a trip to Settings is reflected.
 */
@Composable
private fun NotificationRows(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val enabled by graph.prefs.liveNotifications.collectAsStateWithLifecycle(initialValue = true)
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }
    val notificationsAllowed = remember(resumeCount) { LiveNotifications.areEnabled(context) }
    val promotedAllowed = remember(resumeCount) { LiveNotifications.canPostPromoted(context) }
    val liveUpdatesIntent = remember(resumeCount) { LiveNotifications.liveUpdatesSettingsIntent(context) }

    Row(
        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setLiveNotifications(!enabled) } }, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Live notifications", style = type.base, color = colors.textPrimary)
            Text(
                "Follow running agents from the lock screen, like Live Activities on iOS. Live detail for up to eight at once; a card when one agent finishes.",
                style = type.small, color = colors.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = enabled, onCheckedChange = { scope.launch { graph.prefs.setLiveNotifications(it) } })
    }
    if (enabled && !notificationsAllowed) {
        HairlineDivider()
        HintRow(
            title = "Notifications are turned off for Cursor",
            subtitle = "Allow them in system settings to see running agents.",
            onClick = { runCatching { context.startActivity(LiveNotifications.appNotificationSettingsIntent(context)) } },
        )
    } else if (enabled && promotedAllowed == false && liveUpdatesIntent != null) {
        HairlineDivider()
        HintRow(
            title = "Allow Live Updates",
            subtitle = "Adds the status-bar chip and the lock-screen card on Android 16.",
            onClick = { runCatching { context.startActivity(liveUpdatesIntent) } },
        )
    }
}

/**
 * The in-app updater: the installed version with what the last check found and the one action that follows from it
 * (check, download, install, retry), the release page, the two preferences, and — until the user has allowed it —
 * the system page where installing from this app is permitted. The permission is re-read when the screen resumes.
 */
@Composable
private fun UpdateRows(graph: AppGraph, open: (String) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updates = graph.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val autoUpdate by updates.autoUpdate.collectAsStateWithLifecycle(initialValue = true)
    val includePreReleases by updates.includePreReleases.collectAsStateWithLifecycle(initialValue = false)
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCount++
        onPauseOrDispose { }
    }
    val canInstall = remember(resumeCount) { context.packageManager.canRequestPackageInstalls() }
    val allowInstalls = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Version ${BuildConfig.VERSION_NAME}", style = type.base, color = colors.textPrimary)
            Text(
                updateStatusLine(state),
                style = type.small,
                color = if (state is UpdateState.Failed) colors.red else colors.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        when (val s = state) {
            UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Installed ->
                CursorButton("Check for updates", onClick = updates::checkNow, height = 30.dp)
            UpdateState.Checking -> CursorButton("Checking…", onClick = {}, enabled = false, height = 30.dp)
            is UpdateState.Available ->
                if (s.signatureMismatch) {
                    CursorButton("Open release", onClick = { open(s.release.htmlUrl) }, height = 30.dp)
                } else {
                    CursorButton("Download", onClick = updates::downloadNow, primary = true, height = 30.dp)
                }
            is UpdateState.Downloading -> CursorButton("Cancel", onClick = updates::cancelDownload, height = 30.dp)
            is UpdateState.Downloaded ->
                CursorButton("Install", onClick = { if (canInstall) updates.installNow() else allowInstalls() }, primary = true, height = 30.dp)
            is UpdateState.Installing ->
                if (s.awaitingConfirmation) {
                    CursorButton("Confirm", onClick = updates::resumePendingInstall, primary = true, height = 30.dp)
                } else {
                    CursorButton("Installing…", onClick = {}, enabled = false, height = 30.dp)
                }
            is UpdateState.Failed ->
                CursorButton(if (s.phase == UpdatePhase.Check) "Check again" else "Retry", onClick = updates::retry, height = 30.dp)
        }
    }
    state.release?.takeIf { it.htmlUrl.isNotBlank() }?.let { release ->
        HairlineDivider()
        LinkRow("What's new in ${release.versionName}", release.htmlUrl, open)
    }
    HairlineDivider()
    ToggleRow(
        title = "Automatic updates",
        subtitle = if (Build.VERSION.SDK_INT >= UpdateManager.SILENT_SELF_UPDATE_SDK) {
            "Checks GitHub every 12 hours, downloads new releases over Wi-Fi and installs them while the app isn't in use."
        } else {
            "Checks GitHub every 12 hours, downloads new releases over Wi-Fi and lets you know when one is ready to install."
        },
        checked = autoUpdate,
        onCheckedChange = { scope.launch { updates.setAutoUpdate(it) } },
    )
    HairlineDivider()
    ToggleRow(
        title = "Include pre-releases",
        subtitle = "Also offer release candidates and betas (the vX.Y.Z-rc.N tags).",
        checked = includePreReleases,
        onCheckedChange = { scope.launch { updates.setIncludePreReleases(it) } },
    )
    if (!canInstall) {
        HairlineDivider()
        HintRow(
            title = "Allow installing updates",
            subtitle = "Android asks for your permission before Cursor may install the updates it downloads.",
            onClick = { allowInstalls() },
        )
    }
}

private fun updateStatusLine(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet"
    UpdateState.Checking -> "Checking GitHub for a newer release…"
    is UpdateState.UpToDate -> "Up to date · ${checkedLabel(state.checkedAtMs)}"
    is UpdateState.Available ->
        if (state.signatureMismatch) "${state.release.versionName} is out, but signed with a different key" else "${state.release.versionName} is available"
    is UpdateState.Downloading -> state.fraction?.let { "Downloading ${state.release.versionName}… ${(it * 100).toInt()}%" }
        ?: "Downloading ${state.release.versionName}… ${"%.1f".format(Locale.US, state.bytesRead / 1_048_576.0)} MB"
    is UpdateState.Downloaded -> "${state.release.versionName} is ready · the app closes while it installs"
    is UpdateState.Installing -> if (state.awaitingConfirmation) "Waiting for you to confirm the installation" else "Installing ${state.release.versionName}…"
    is UpdateState.Failed -> state.message
    is UpdateState.Installed -> "Updated to ${state.versionName}"
}

/** "checked just now", "checked 4m ago", "checked Sep 4". */
private fun checkedLabel(checkedAtMs: Long): String = when (val age = TimeFormat.relativeShort(checkedAtMs)) {
    "now" -> "checked just now"
    else -> if (age.last().isLetter() && age.length <= 4) "checked $age ago" else "checked $age"
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable({ onCheckedChange(!checked) }, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary)
            Text(subtitle, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The GitHub token pull request states are read with. The Cloud Agents API names a chat's pull request but never
 * says whether it is open, a draft, merged or closed; GitHub does, and answers for a private repository only with a
 * token that may read it. Saving one re-asks at once about every pull request GitHub refused so far.
 */
@Composable
private fun GitHubRows(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val hasToken by graph.pullRequests.hasToken.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    fun apply(value: String?) {
        token = ""
        editing = false
        reveal = false
        scope.launch {
            graph.pullRequests.setToken(value)
            graph.pullRequests.refresh(graph.agents.state.value.agents.mapNotNull { it.prUrl })
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text("Pull request states", style = type.base, color = colors.textPrimary)
        Text(
            "Whether a chat's pull request is open, a draft, merged or closed is read from GitHub. Public repositories need no token; " +
                "private ones need a personal access token that can read their pull requests.",
            style = type.small, color = colors.textTertiary,
        )
    }
    HairlineDivider()
    if (hasToken && !editing) {
        Row(Modifier.fillMaxWidth().height(CursorDimens.listRow).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Token", style = type.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("Saved", style = type.base, color = colors.textTertiary)
            Spacer(Modifier.width(6.dp))
            TextAction("Replace", colors.link) { editing = true }
            TextAction("Remove", colors.red) { apply(null) }
        }
    } else {
        val fieldBorder by animateColorAsState(if (focused) colors.strokeStrong else colors.strokeSubtle, tween(160), label = "border")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .cursorSurface(colors.elevated, fieldBorder, CursorTheme.shapes.lg)
                .height(44.dp)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = token,
                onValueChange = { token = it.trim() },
                singleLine = true,
                textStyle = type.code.copy(color = colors.textPrimary, fontSize = type.base.fontSize),
                cursorBrush = SolidColor(colors.textPrimary),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (token.isNotBlank()) apply(token) }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner -> Box { if (token.isEmpty()) Text("github_pat_…", style = type.code.copy(fontSize = type.base.fontSize), color = colors.textQuaternary); inner() } },
            )
            FlatIconButton(if (reveal) CursorIcons.EyeOff else CursorIcons.Eye, if (reveal) "Hide token" else "Show token", onClick = { reveal = !reveal }, iconSize = 17.dp)
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CursorButton("Save token", onClick = { apply(token) }, enabled = token.isNotBlank(), primary = true)
            if (hasToken) CursorButton("Cancel", onClick = { editing = false; token = "" })
        }
    }
    HairlineDivider()
    LinkRow("Create a token on GitHub", GitHubEndpoints.NEW_TOKEN_URL, uriHandler::openUri)
}

/** An inline text button at the end of a settings row, like the Customize sheet's "Reset". */
@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = CursorTheme.typography.baseMedium,
        color = color,
        modifier = Modifier.pressable(onClick, CursorTheme.shapes.base).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * The pin sync toggle and, under it, where the sync stands: the last time it went through, changes still waiting for
 * the server, or why it is not going through.
 */
@Composable
private fun PinSyncRows(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val enabled by graph.prefs.pinSyncEnabled.collectAsStateWithLifecycle(initialValue = true)
    val sync by graph.pins.state.collectAsStateWithLifecycle()

    Row(
        Modifier.fillMaxWidth().pressable({ scope.launch { graph.prefs.setPinSyncEnabled(!enabled) } }, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Sync pinned chats", style = type.base, color = colors.textPrimary)
            Text(
                "Pins follow your Cursor account, like the desktop Agents window and the iOS app. Off keeps them on this device.",
                style = type.small, color = colors.textTertiary,
            )
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = enabled, onCheckedChange = { scope.launch { graph.prefs.setPinSyncEnabled(it) } })
    }
    if (enabled && sync.error != null) {
        HairlineDivider()
        StatusRow(title = "Pins are staying on this device", subtitle = sync.error.orEmpty(), warning = true)
    } else if (enabled && sync.pendingCount > 0) {
        HairlineDivider()
        StatusRow(
            title = "Waiting to sync ${sync.pendingCount} ${if (sync.pendingCount == 1) "change" else "changes"}",
            subtitle = "They go up the next time Cursor is reachable.",
            warning = false,
        )
    } else if (enabled && sync.lastSyncedAtMillis != null) {
        HairlineDivider()
        InfoRow("Last synced", TimeFormat.relativeShort(sync.lastSyncedAtMillis ?: 0L, AppClock.now()))
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, warning: Boolean) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (warning) CursorIcons.Warning else CursorIcons.Cloud, null, tint = if (warning) colors.orange else colors.iconTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary)
            Text(subtitle, style = type.small, color = colors.textTertiary)
        }
    }
}

@Composable
private fun HintRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable(onClick, CursorTheme.shapes.lg).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Warning, null, tint = colors.orange, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary)
            Text(subtitle, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Group(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp))
}

@Composable
private fun LinkRow(label: String, url: String, open: (String) -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable({ open(url) }, CursorTheme.shapes.lg).height(CursorDimens.listRow).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Icon(CursorIcons.ExternalLink, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().height(CursorDimens.listRow).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CursorTheme.typography.base, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
