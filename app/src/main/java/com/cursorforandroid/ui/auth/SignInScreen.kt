package com.cursorforandroid.ui.auth

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.CursorEndpoints
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.pressable
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

    fun submit() {
        if (busy || key.isBlank()) return
        busy = true
        error = null
        scope.launch {
            graph.session.signIn(key).onFailure { error = it.message ?: "Couldn't sign in." }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(colors.canvas).systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(CursorIcons.Cube, null, tint = colors.textPrimary, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(20.dp))
            Text("Sign in to Cursor", style = type.display, color = colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste a user API key from the Cursor dashboard to manage your cloud agents from Android.",
                style = type.secondary,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .cursorSurface(colors.wash, if (error != null) colors.danger.copy(alpha = 0.6f) else colors.borderSubtle, CursorTheme.shapes.md)
                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = key,
                    onValueChange = { key = it.trim(); error = null },
                    singleLine = true,
                    textStyle = type.code.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.textPrimary),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box { if (key.isEmpty()) Text("key_…", style = type.code, color = colors.textPlaceholder); inner() }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (reveal) "Hide key" else "Show key",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(28.dp).pressable({ reveal = !reveal }, CursorTheme.shapes.sm).padding(5.dp),
                )
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, style = type.caption, color = colors.danger, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            CursorButton(
                text = if (busy) "Signing in…" else "Continue",
                onClick = ::submit,
                enabled = key.isNotBlank() && !busy,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                leading = if (busy) ({ SpinnerRing(size = 14.dp, color = colors.onAccent) }) else null,
            )
            Spacer(Modifier.height(10.dp))
            CursorButton(
                text = "Get an API key",
                onClick = { uriHandler.openUri(CursorEndpoints.DASHBOARD_API_KEYS) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "Try the demo",
                style = type.secondary,
                color = colors.accentBlue,
                modifier = Modifier.pressable({ scope.launch { graph.session.enterDemo() } }, CursorTheme.shapes.sm).padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Your key is stored encrypted on this device and only ever sent to api.cursor.com.",
                style = type.caption,
                color = colors.textPlaceholder,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Unofficial client. Not affiliated with Anysphere.",
                style = type.caption,
                color = colors.textPlaceholder,
                textAlign = TextAlign.Center,
            )
        }
    }
}
