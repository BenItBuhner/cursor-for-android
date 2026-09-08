package com.cursorforandroid.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.data.repo.LoginProgress
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

/**
 * Sign-in: the Cursor account is the default — one tap opens cursor.com to approve, and the app comes back signed
 * in — with a pasted API key folded away underneath for service accounts and teams that restrict user keys.
 */
@Composable
fun SignInScreen(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val context = LocalContext.current
    val login by graph.session.loginProgress.collectAsStateWithLifecycle()
    val signedOutReason by graph.session.signedOutReason.collectAsStateWithLifecycle()
    var noBrowser by remember { mutableStateOf(false) }

    fun openBrowser(url: String) {
        val opened = openLoginPage(context, url, colors.canvas)
        noBrowser = !opened
        if (!opened) graph.session.cancelCursorLogin()
    }

    fun continueWithCursor() {
        if (login is LoginProgress.WaitingForBrowser || login is LoginProgress.Finishing) return
        openBrowser(graph.session.startCursorLogin())
    }

    Box(Modifier.fillMaxSize().background(colors.canvas).systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(CursorIcons.Cube, null, tint = colors.iconPrimary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(22.dp))
            Text("Sign in", style = type.pageTitle, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text("Use your Cursor account to see and run your cloud agents.", style = type.base, color = colors.textSecondary)
            signedOutReason?.let {
                Spacer(Modifier.height(14.dp))
                ErrorLine(it)
            }
            Spacer(Modifier.height(22.dp))

            when (val progress = login) {
                is LoginProgress.WaitingForBrowser -> LoginInProgress(
                    title = "Waiting for cursor.com…",
                    detail = "Approve the sign-in in your browser, then come back here.",
                    onCancel = graph.session::cancelCursorLogin,
                    onReopen = { openBrowser(progress.loginUrl) },
                )
                LoginProgress.Finishing -> LoginInProgress(
                    title = "Setting up this device…",
                    detail = "Creating an API key for this app and checking it against Cursor.",
                    onCancel = graph.session::cancelCursorLogin,
                    onReopen = null,
                )
                LoginProgress.Idle, is LoginProgress.Failed -> {
                    CursorButton(
                        "Continue with Cursor",
                        onClick = ::continueWithCursor,
                        primary = true,
                        icon = CursorIcons.Cube,
                        height = 40.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val failure = (progress as? LoginProgress.Failed)?.message
                        ?: "Couldn't find a browser to sign in with. Paste an API key instead.".takeIf { noBrowser }
                    if (failure != null) {
                        Spacer(Modifier.height(10.dp))
                        ErrorLine(failure)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HairlineDivider()
            Spacer(Modifier.height(4.dp))
            ApiKeySection(graph)

            Spacer(Modifier.height(4.dp))
            HairlineDivider()
            Spacer(Modifier.height(20.dp))
            Text("No account handy?", style = type.small, color = colors.textTertiary)
            Spacer(Modifier.height(8.dp))
            val scope = rememberCoroutineScope()
            CursorButton("Try the demo", onClick = { scope.launch { graph.session.enterDemo() } }, icon = CursorIcons.Sparkle)
            Spacer(Modifier.height(20.dp))
            Text(
                "Signing in with Cursor creates an API key for this app, named after this phone, that you can revoke any time at " +
                    "cursor.com/dashboard/api. Keys are stored encrypted on this device and only sent to Cursor. " +
                    "Unofficial client, not affiliated with Anysphere.",
                style = type.small,
                color = colors.textQuaternary,
            )
        }
    }
}

/** The browser round-trip: what the app is doing, a way out, and a way to get the page back if it was swiped away. */
@Composable
private fun LoginInProgress(title: String, detail: String, onCancel: () -> Unit, onReopen: (() -> Unit)?) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    CursorCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SpinnerRing(size = 14.dp, color = colors.iconSecondary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = type.rowMedium, color = colors.textPrimary)
                Text(detail, style = type.small, color = colors.textTertiary)
            }
        }
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CursorButton("Cancel", onClick = onCancel)
            if (onReopen != null) CursorButton("Open browser again", onClick = onReopen, icon = CursorIcons.ExternalLink)
        }
    }
}

/** The pasted-key path, folded behind a disclosure so the account sign-in stays the obvious choice. */
@Composable
private fun ApiKeySection(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val login by graph.session.loginProgress.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Deliberately not saveable: saved instance state is an unencrypted Bundle that outlives the activity and is
    // marshalled for process restoration, so the key would sit in plaintext outside the key store. A recreation
    // clears the field instead, and re-reveals nothing.
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val browserBusy = login is LoginProgress.WaitingForBrowser || login is LoginProgress.Finishing

    fun submit() {
        val pasted = key
        if (busy || pasted.isBlank()) return
        busy = true
        error = null
        // The key store has it from here on; it does not stay on screen (or in a screenshot) while Cursor checks it.
        key = ""
        reveal = false
        scope.launch {
            graph.session.cancelCursorLogin()
            graph.session.signIn(pasted).onFailure { error = it.message ?: "Couldn't sign in." }
            busy = false
        }
    }

    val fieldBorder by animateColorAsState(
        when {
            error != null -> colors.red.copy(alpha = 0.6f)
            focused -> colors.strokeStrong
            else -> colors.strokeSubtle
        },
        tween(160),
        label = "border",
    )

    Row(
        Modifier.fillMaxWidth().pressable({ expanded = !expanded }, CursorTheme.shapes.base, role = Role.Button).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Use an API key instead", style = type.base, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) CursorIcons.ChevronDown else CursorIcons.ChevronRight,
            if (expanded) "Hide API key sign-in" else "Show API key sign-in",
            tint = colors.iconTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
    AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.padding(bottom = 10.dp)) {
            Text("A user API key from the Cursor dashboard, or a service account key.", style = type.small, color = colors.textTertiary)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .cursorSurface(colors.elevated, fieldBorder, CursorTheme.shapes.lg)
                    .height(44.dp)
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it.trim(); error = null },
                    singleLine = true,
                    textStyle = type.code.copy(color = colors.textPrimary, fontSize = type.base.fontSize),
                    cursorBrush = SolidColor(colors.textPrimary),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner -> Box { if (key.isEmpty()) Text("key_…", style = type.code.copy(fontSize = type.base.fontSize), color = colors.textQuaternary); inner() } },
                )
                FlatIconButton(if (reveal) CursorIcons.EyeOff else CursorIcons.Eye, if (reveal) "Hide key" else "Show key", onClick = { reveal = !reveal }, iconSize = 17.dp)
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                ErrorLine(error.orEmpty())
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CursorButton(if (busy) "Signing in…" else "Continue", onClick = ::submit, enabled = key.isNotBlank() && !busy && !browserBusy)
                CursorButton("Get an API key", onClick = { uriHandler.openUri(CursorEndpoints.DASHBOARD_API_KEYS) }, icon = CursorIcons.ExternalLink)
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    val colors = CursorTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = CursorTheme.typography.small, color = colors.red)
    }
}
