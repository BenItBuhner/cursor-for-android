package com.cursorforandroid.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.data.update.UpdateManager
import com.cursorforandroid.domain.AppRelease
import com.cursorforandroid.domain.CredentialInfo
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.ProjectNotificationPrefs
import com.cursorforandroid.domain.ReleaseNotes
import com.cursorforandroid.domain.SignInMethod
import com.cursorforandroid.domain.UpdatePhase
import com.cursorforandroid.domain.UpdateState
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.ui.agents.Avatar
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/** The words the screen shows that more than one place (a test, a sheet) has to agree on. */
object SettingsCopy {
    const val GROUP_ACCOUNT = "Account"
    const val GROUP_APPEARANCE = "Appearance"
    const val GROUP_CHATS = "Chats"
    const val GROUP_NOTIFICATIONS = "Notifications"
    const val UNREAD_THIS_PHONE = "Unread only for chats from this phone"
    const val UNREAD_THIS_PHONE_DETAIL = "Chats from elsewhere show as read until opened here."
    const val GROUP_UPDATES = "Version and updates"
    const val SIGN_OUT = "Sign out"
    const val LEAVE_DEMO = "Leave demo"

    /** The one sentence the screen explains itself with; everything else about the build is behind the version row. */
    const val DISCLAIMER = "Unofficial client for Cursor Cloud Agents; not affiliated with Anysphere, Inc."

    /** The licenses of what the app bundles, in the debug sheet's Credits group. */
    const val CREDITS = "JetBrains Mono is bundled under the SIL Open Font License. Icons are derived from Lucide (ISC); the Project icon " +
        "catalog's brand and product marks come from Simple Icons (CC0 1.0) — each remains its owner's trademark, shown only to identify " +
        "that product on a Project named after it — and marks no open library carries are drawn as neutral stand-ins. " +
        "The remote desktop view embeds noVNC (MPL-2.0)."

    /** The long-press action on the version row, as TalkBack reads it. */
    const val DEBUG_ACTION = "Debug options"
}

/** Test tags for the rows that act rather than toggle, and the two sheets behind them. */
object SettingsTags {
    const val ACCOUNT_ROW = "settings_account"
    const val ACCOUNT_SHEET = "settings_account_sheet"
    const val SIGN_OUT = "settings_sign_out"
    const val UNREAD_THIS_PHONE = "settings_unread_this_phone"
    const val VERSION_ROW = "settings_version"
    const val WHATS_NEW_ROW = "settings_whats_new"
    const val DEBUG_SHEET = "settings_debug_sheet"
}

/**
 * Settings in the desktop settings-page idiom: 12sp group labels, bordered cards of rows. The list is the essentials
 * and nothing else — the account and the way out of it, appearance, which chats may show as unread, notifications,
 * the Extended mode switch, the version with its updater and the crash report consent, one line of disclaimer. What
 * the account's key is and where to manage it sits behind a tap on the account row; the diagnostics exports, the
 * About links and the credits sit behind a long press on the version row ([SettingsDebugSheet]), where support can
 * ask for them.
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    user: CursorUser,
    isDemo: Boolean,
    onOpenSidebar: (() -> Unit)?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Opens the What's new page, from the row beneath the version while the installed version's notes are unread. */
    onOpenWhatsNew: () -> Unit = {},
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val themeMode by graph.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
    val oledBlack by graph.prefs.oledBlack.collectAsStateWithLifecycle(initialValue = false)
    val session by graph.session.state.collectAsStateWithLifecycle()
    val credential = (session as? SessionState.SignedIn)?.credential
    val keyStorage by graph.keyStore.availability.collectAsStateWithLifecycle()
    // Collected once for the screen: the Extended mode row and the debug sheet's API row read this one value, so the
    // sheet says what the switch says from its first frame rather than starting a collector of its own when it opens.
    // Collected on the main dispatcher (as the acknowledgment in ExtendedModeRows is): the value arrives from the
    // store's IO thread, and a state write made there can be lost to the recomposer while it is dispatching another
    // change — Compose 1.7's Recomposer.recordComposerModifications resets its pending set without holding the lock
    // for the whole round. On a device the composition runs on the main thread anyway; under the test harness's
    // unconfined dispatcher it does not, and a fresh collector's first value went missing once in a few dozen runs.
    val extendedMode by graph.extendedMode.enabled.collectAsStateWithLifecycle(initialValue = false, context = Dispatchers.Main.immediate)
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var debugOpen by rememberSaveable { mutableStateOf(false) }

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
            Group(SettingsCopy.GROUP_ACCOUNT)
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                // The demo has no key to describe; a real account's row opens onto its key and where to manage it.
                AccountRow(
                    user = user,
                    subtitle = if (isDemo) "Demo · local mock backend" else listOfNotNull(user.email, user.apiKeyName).joinToString(" · "),
                    onClick = if (isDemo) null else ({ accountOpen = true }),
                )
                HairlineDivider()
                SignOutRow(if (isDemo) SettingsCopy.LEAVE_DEMO else SettingsCopy.SIGN_OUT) { scope.launch { graph.signOut() } }
            }

            Group(SettingsCopy.GROUP_APPEARANCE)
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
                    ToggleRow(
                        title = "OLED black",
                        subtitle = if (themeMode == ThemeMode.System) "True-black surfaces when the system is in dark theme." else "True-black surfaces instead of Cursor Dark's charcoal.",
                        checked = oledBlack,
                        onCheckedChange = { scope.launch { graph.prefs.setOledBlack(it) } },
                    )
                }
            }

            // Every chat in the demo is this phone's own, so the switch would change nothing there.
            if (!isDemo) {
                Group(SettingsCopy.GROUP_CHATS)
                CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    UnreadThisPhoneRow(graph)
                }
            }

            Group(SettingsCopy.GROUP_NOTIFICATIONS)
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                NotificationRows(graph)
            }

            // The demo speaks to no account, so it has no mode to switch.
            if (!isDemo) {
                Group(ExtendedModeCopy.SETTING_TITLE)
                CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                    ExtendedModeRows(graph, enabled = extendedMode)
                }
            }

            Group(SettingsCopy.GROUP_UPDATES)
            CursorCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) {
                UpdateRows(graph, uriHandler::openUri, onDebug = { debugOpen = true }, onOpenWhatsNew = onOpenWhatsNew)
                HairlineDivider()
                CrashReportRows(graph)
            }

            Text(SettingsCopy.DISCLAIMER, style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
        }
    }

    if (accountOpen) {
        AccountDetailsSheet(credential = credential, keyStorage = keyStorage, open = uriHandler::openUri, onDismiss = { accountOpen = false })
    }
    if (debugOpen) {
        SettingsDebugSheet(graph = graph, isDemo = isDemo, extendedMode = extendedMode, open = uriHandler::openUri, onDismiss = { debugOpen = false })
    }
}

/** Who is signed in: the avatar, the name, the address (and the key's name). Tappable where there is more to show. */
@Composable
private fun AccountRow(user: CursorUser, subtitle: String, onClick: (() -> Unit)?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val gestures = if (onClick != null) Modifier.pressable(onClick, CursorTheme.shapes.lg) else Modifier
    Row(
        Modifier.fillMaxWidth().then(gestures).testTag(SettingsTags.ACCOUNT_ROW).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName, style = type.rowMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
        }
    }
}

/** The way out of the account (or the demo), in the account's own card: a destructive row, not a button at the end of the page. */
@Composable
private fun SignOutRow(label: String, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    Row(
        Modifier.fillMaxWidth().pressable(onClick, CursorTheme.shapes.lg).testTag(SettingsTags.SIGN_OUT).height(CursorDimens.listRow).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.SignOut, null, tint = colors.red, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = CursorTheme.typography.base, color = colors.red)
    }
}

/**
 * The account's key, behind a tap on the account row: how the app was signed in, when the key it holds lapses, where
 * the key is kept when that is not the encrypted store, and the pages on cursor.com where the key and the agents live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailsSheet(credential: CredentialInfo?, keyStorage: SecureKeyStore.Availability, open: (String) -> Unit, onDismiss: () -> Unit) {
    CursorSheet(onDismiss = onDismiss) { _ ->
        SheetHeader(SettingsCopy.GROUP_ACCOUNT)
        Column(Modifier.testTag(SettingsTags.ACCOUNT_SHEET).padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
            CursorCard(Modifier.fillMaxWidth()) {
                if (credential != null) {
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
                    HairlineDivider()
                }
                if (keyStorage != SecureKeyStore.Availability.Encrypted) {
                    InfoRow(
                        "Key storage",
                        when (keyStorage) {
                            SecureKeyStore.Availability.Reset -> "Reset — sign in again"
                            else -> "Unavailable on this device"
                        },
                    )
                    HairlineDivider()
                }
                LinkRow(if (credential?.method == SignInMethod.Cursor) "Manage this app's key" else "Manage API keys", CursorEndpoints.DASHBOARD_API_KEYS, open)
                HairlineDivider()
                LinkRow("Open cursor.com/agents", "https://cursor.com/agents", open)
            }
        }
    }
}

/**
 * Whether a chat this phone never started or opened can show as unread (see `LocalAgentState.unreadOnlyTouchedHere`).
 * What this phone shows, nothing more: the switch marks nothing read or unread, here or on the account.
 */
@Composable
private fun UnreadThisPhoneRow(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    // On the main dispatcher for the reason the Extended mode switch is (see SettingsScreen).
    val enabled by graph.prefs.unreadOnlyTouchedHere.collectAsStateWithLifecycle(initialValue = true, context = Dispatchers.Main.immediate)
    ToggleRow(
        title = SettingsCopy.UNREAD_THIS_PHONE,
        subtitle = SettingsCopy.UNREAD_THIS_PHONE_DETAIL,
        checked = enabled,
        onCheckedChange = { scope.launch { graph.prefs.setUnreadOnlyTouchedHere(it) } },
        tag = SettingsTags.UNREAD_THIS_PHONE,
    )
}

/**
 * Live notification toggle plus the system pages it depends on: notification permission, and on Android 16 the
 * per-app Live Updates switch. The hints re-check when the screen resumes so a trip to Settings is reflected.
 */
@Composable
private fun NotificationRows(graph: AppGraph) {
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

    ToggleRow(
        title = "Live notifications",
        subtitle = "Follow running agents from the lock screen.",
        checked = enabled,
        onCheckedChange = { scope.launch { graph.prefs.setLiveNotifications(it) } },
    )
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
    // The Project half (see ProjectNotificationPrefs): the live count is every running agent by default, the
    // cards for a Project's chats off by default; each its own switch.
    val project by graph.prefs.projectNotifications.collectAsStateWithLifecycle(initialValue = ProjectNotificationPrefs.DEFAULT)
    HairlineDivider()
    ToggleRow(
        title = "Count Project agents in the live notification",
        checked = project.countProjectAgentsInLive,
        onCheckedChange = { scope.launch { graph.prefs.setCountProjectAgentsInLive(it) } },
        tag = "notif-count-project-agents",
    )
    HairlineDivider()
    ToggleRow(
        title = "Notify for Project coordinators",
        checked = project.notifyProjectCoordinators,
        onCheckedChange = { scope.launch { graph.prefs.setNotifyProjectCoordinators(it) } },
        tag = "notif-project-coordinators",
    )
    HairlineDivider()
    ToggleRow(
        title = "Notify for agents inside Projects",
        checked = project.notifyProjectMembers,
        onCheckedChange = { scope.launch { graph.prefs.setNotifyProjectMembers(it) } },
        tag = "notif-project-members",
    )
}

/**
 * One settings switch: the title, at most one line under it, the toggle at the trailing edge, the whole row
 * pressable. Without a subtitle the row is a standard list row; with one it grows to fit.
 */
@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, tag: String? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val tagged = if (tag != null) Modifier.testTag(tag) else Modifier
    val sized = if (subtitle == null) Modifier.height(CursorDimens.listRow).padding(horizontal = 14.dp) else Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
    Row(
        Modifier.fillMaxWidth().pressable({ onCheckedChange(!checked) }, CursorTheme.shapes.lg).then(tagged).then(sized),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = type.small, color = colors.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        CursorToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The in-app updater: the installed version with what the last check found and the one action that follows from it
 * (check, download, install, retry); beneath it, while they are unread, the installed version's release notes
 * ([onOpenWhatsNew]); the two preferences; and — until the user has allowed it — the system page where installing
 * from this app is permitted. The permission is re-read when the screen resumes. A long press on the version row is
 * the way into the debug sheet ([onDebug]); a tap does nothing, so the row is not announced as a button.
 */
@Composable
private fun UpdateRows(graph: AppGraph, open: (String) -> Unit, onDebug: () -> Unit, onOpenWhatsNew: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updates = graph.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val autoUpdate by updates.autoUpdate.collectAsStateWithLifecycle(initialValue = true)
    val includePreReleases by updates.includePreReleases.collectAsStateWithLifecycle(initialValue = false)
    // On the main dispatcher for the reason the Extended mode switch is (see SettingsScreen): the value comes from
    // the store's thread, and the row must not miss the write that would show it.
    val whatsNew by graph.whatsNew.unread.collectAsStateWithLifecycle(initialValue = null, context = Dispatchers.Main.immediate)
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

    Row(
        Modifier.fillMaxWidth().longPressable(SettingsCopy.DEBUG_ACTION, onDebug).testTag(SettingsTags.VERSION_ROW).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Version ${graph.appVersion}", style = type.base, color = colors.textPrimary)
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
                    // The status line says what is happening; this is the way out of a session that never finishes.
                    CursorButton("Cancel", onClick = updates::cancelInstall, height = 30.dp)
                }
            is UpdateState.Failed ->
                CursorButton(if (s.phase == UpdatePhase.Check) "Check again" else "Retry", onClick = updates::retry, height = 30.dp)
        }
    }
    // What this version brought, until it has been read: directly under the version it is about.
    whatsNew?.let { notes ->
        HairlineDivider()
        WhatsNewRow(notes, onClick = onOpenWhatsNew)
    }
    // A signer mismatch is the one outcome the user has to act on outside the app, so it gets a sentence rather than a
    // status line: Android cannot replace an install signed with a different key, whatever the updater does.
    (state as? UpdateState.Available)?.takeIf { it.signatureMismatch }?.let { mismatch ->
        HairlineDivider()
        HintRow(
            title = "Reinstall to move to ${mismatch.release.versionName}",
            subtitle = "This build and the new release are signed with different keys, so Android won't update it in place. " +
                "Download the APK from the release page, uninstall Cursor, then install it. You'll sign in again afterwards.",
            onClick = { open(mismatch.release.htmlUrl) },
        )
    }
    HairlineDivider()
    ToggleRow(
        title = "Automatic updates",
        subtitle = if (Build.VERSION.SDK_INT >= UpdateManager.SILENT_SELF_UPDATE_SDK) {
            "Downloads new releases over Wi-Fi and installs them while the app isn't in use."
        } else {
            "Downloads new releases over Wi-Fi and tells you when one is ready to install."
        },
        checked = autoUpdate,
        onCheckedChange = { scope.launch { updates.setAutoUpdate(it) } },
    )
    HairlineDivider()
    ToggleRow(
        title = "Include pre-releases",
        subtitle = "Also offer release candidates and betas.",
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

/**
 * A long press, and nothing else: the press ripple is not wanted (the row is not a control) and neither is a tap
 * announcement. TalkBack still gets the action, under [label], as a custom action on the row.
 */
@Composable
private fun Modifier.longPressable(label: String, onLongPress: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onLongPress)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                current()
            })
        }
        .semantics { onLongClick(label) { current(); true } }
}

private fun updateStatusLine(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet"
    UpdateState.Checking -> "Checking GitHub for a newer release…"
    is UpdateState.UpToDate -> "Up to date · ${checkedLabel(state.checkedAtMs)}"
    is UpdateState.Available ->
        if (state.signatureMismatch) "${state.release.versionName} needs a manual reinstall" else UpdateCopy.available(state.release)
    is UpdateState.Downloading -> state.fraction?.let { "Downloading ${state.release.versionName}… ${(it * 100).toInt()}%" }
        ?: "Downloading ${state.release.versionName}… ${"%.1f".format(Locale.US, state.bytesRead / 1_048_576.0)} MB"
    is UpdateState.Downloaded -> "${state.release.versionName} is ready · the app closes while it installs"
    is UpdateState.Installing -> if (state.awaitingConfirmation) "Waiting for you to confirm the installation" else "Installing ${state.release.versionName}…"
    is UpdateState.Failed -> state.message
    is UpdateState.Installed -> "Updated to ${state.versionName}"
}

/** The updater's words that more than one surface shows: the Settings status line and the sidebar's hint row say the same thing. */
object UpdateCopy {
    /** "Update available: v0.3.9" — the version with the tag's `v`, so it reads as the release it names. */
    fun available(release: AppRelease): String = "Update available: v${release.versionName}"
}

/** "checked just now", "checked 4m ago", "checked Sep 4". */
private fun checkedLabel(checkedAtMs: Long): String = when (val age = TimeFormat.relativeShort(checkedAtMs)) {
    "now" -> "checked just now"
    else -> if (age.last().isLetter() && age.length <= 4) "checked $age ago" else "checked $age"
}

/**
 * The crash report consent, one row under the updater. Off until the user turns it on, and never assumed; a build
 * that carries no project to report to says so instead of offering a switch that would do nothing.
 */
@Composable
private fun CrashReportRows(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val enabled by graph.prefs.crashReports.collectAsStateWithLifecycle(initialValue = false)
    if (!graph.crashReporting.isAvailable) {
        StatusRow(title = CrashReportCopy.TITLE, subtitle = CrashReportCopy.UNAVAILABLE, warning = false)
        return
    }
    ToggleRow(
        title = CrashReportCopy.TITLE,
        subtitle = if (enabled) CrashReportCopy.ON else CrashReportCopy.OFF,
        checked = enabled,
        onCheckedChange = { scope.launch { graph.prefs.setCrashReports(it) } },
    )
}

/** Settings copy for the crash report consent, shared with its test. The consent says what leaves the device, in one line each. */
internal object CrashReportCopy {
    const val TITLE = "Send crash reports"
    const val OFF = "Off. Nothing is sent anywhere."
    const val ON = "Sends the stack trace, app version and device model to the project's Sentry — never your account, prompts, replies or keys."
    const val UNAVAILABLE = "Not available in this build: it was made without a project to report to."
}

/** A row that reports rather than acts: an icon, a title and a line under it. */
@Composable
private fun StatusRow(title: String, subtitle: String, warning: Boolean, icon: ImageVector = if (warning) CursorIcons.Warning else CursorIcons.Cloud) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (warning) colors.orange else colors.iconTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.base, color = colors.textPrimary)
            Text(subtitle, style = type.small, color = colors.textTertiary)
        }
    }
}

/**
 * "What's new in 0.3.37" with the notes' lead line under it (their release date when they have none), leading to the
 * What's new page. The accent glyph is the sidebar card's, so the two surfaces read as one thing.
 */
@Composable
private fun WhatsNewRow(notes: ReleaseNotes, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable(onClick, CursorTheme.shapes.lg).testTag(SettingsTags.WHATS_NEW_ROW).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Sparkle, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(WhatsNewCopy.title(notes.versionName), style = type.base, color = colors.textPrimary)
            val detail = notes.lead ?: notes.publishedAtMs.takeIf { it > 0 }?.let(WhatsNewCopy::released)
            if (detail != null) Text(detail, style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
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

/** A group label over a card: 12sp at 60 %, sentence case. Shared with the debug sheet, which groups the same way. */
@Composable
internal fun Group(text: String) {
    Text(text, style = CursorTheme.typography.small, color = CursorTheme.colors.textTertiary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 2.dp))
}

@Composable
internal fun LinkRow(label: String, url: String, open: (String) -> Unit) {
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
internal fun InfoRow(label: String, value: String) {
    val colors = CursorTheme.colors
    Row(Modifier.fillMaxWidth().height(CursorDimens.listRow).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CursorTheme.typography.base, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CursorTheme.typography.base, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
