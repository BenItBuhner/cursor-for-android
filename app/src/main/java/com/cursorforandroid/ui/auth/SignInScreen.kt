package com.cursorforandroid.ui.auth

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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(graph: AppGraph) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var key by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || key.isBlank()) return
        busy = true
        error = null
        scope.launch {
            graph.session.signIn(key).onFailure { error = it.message ?: "Couldn't sign in." }
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

    Box(Modifier.fillMaxSize().background(colors.canvas).systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(CursorIcons.Cube, null, tint = colors.iconPrimary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(22.dp))
            Text("Sign in", style = type.pageTitle, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text("Use a user API key from the Cursor dashboard.", style = type.base, color = colors.textSecondary)
            Spacer(Modifier.height(22.dp))

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(error.orEmpty(), style = type.small, color = colors.red)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CursorButton(if (busy) "Signing in…" else "Continue", onClick = ::submit, enabled = key.isNotBlank() && !busy, primary = true)
                CursorButton("Get an API key", onClick = { uriHandler.openUri(CursorEndpoints.DASHBOARD_API_KEYS) }, icon = CursorIcons.ExternalLink)
            }
            Spacer(Modifier.height(28.dp))
            HairlineDivider()
            Spacer(Modifier.height(20.dp))
            Text("No key handy?", style = type.small, color = colors.textTertiary)
            Spacer(Modifier.height(8.dp))
            CursorButton("Try the demo", onClick = { scope.launch { graph.session.enterDemo() } }, icon = CursorIcons.Sparkle)
            Spacer(Modifier.height(20.dp))
            Text("The key is stored encrypted on this device and only sent to api.cursor.com. Unofficial client, not affiliated with Anysphere.", style = type.small, color = colors.textQuaternary)
        }
    }
}
