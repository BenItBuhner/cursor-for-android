package com.cursorforandroid.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cursorforandroid.domain.DesktopPage
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** What the noVNC page reports back about its connection. */
sealed interface DesktopConnection {
    data object Loading : DesktopConnection
    data object Connecting : DesktopConnection
    data class Connected(val desktopName: String? = null) : DesktopConnection
    /** The connection ended; [clean] when the far end closed it in order, else it dropped. */
    data class Disconnected(val clean: Boolean, val reason: String? = null) : DesktopConnection
    data class Failed(val reason: String) : DesktopConnection

    val isLive: Boolean get() = this is Connecting || this is Connected
}

/**
 * The agent's desktop, full screen in its own window: a bar with the chat's name, the connection state, a view-only /
 * control switch and a reconnect, then noVNC in a WebView underneath. Back and the X end the session. The screen
 * stays awake while the connection is live.
 */
@Composable
fun DesktopDialog(
    session: DesktopSession,
    agentName: String?,
    onViewOnlyChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        DesktopScreen(session, agentName, onViewOnlyChange, onReconnect, onClose, Modifier.fillMaxSize())
    }
}

@Composable
fun DesktopScreen(
    session: DesktopSession,
    agentName: String?,
    onViewOnlyChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var connection by remember(session.url) { mutableStateOf<DesktopConnection>(DesktopConnection.Loading) }
    val view = LocalView.current
    // Awake while the desktop is live: a screen that dims mid-session drops the VNC connection with it.
    DisposableEffect(connection.isLive) {
        if (connection.isLive) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(modifier.background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).testTag("desktop-screen")) {
        Row(Modifier.fillMaxWidth().background(colors.sidebar).heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatIconButton(CursorIcons.Close, "Close the desktop", onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName ?: "Desktop", style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(connectionLabel(connection, session), style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("desktop-connection"))
            }
            Text(if (session.viewOnly) "View only" else "In control", style = type.small, color = colors.textTertiary)
            Spacer(Modifier.width(8.dp))
            CursorToggle(checked = !session.viewOnly, onCheckedChange = { control -> onViewOnlyChange(!control) }, modifier = Modifier.testTag("desktop-control-toggle"))
            Spacer(Modifier.width(4.dp))
            FlatIconButton(CursorIcons.Refresh, "Reconnect", onClick = onReconnect)
        }
        HairlineDivider()
        Box(Modifier.fillMaxSize()) {
            if (!LocalInspectionMode.current) {
                DesktopWebView(session, onConnection = { connection = it }, modifier = Modifier.fillMaxSize())
            }
            when (val c = connection) {
                DesktopConnection.Loading, DesktopConnection.Connecting -> Row(Modifier.align(Alignment.Center).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpinnerRing(size = 14.dp, color = colors.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (c is DesktopConnection.Loading) "Loading the viewer…" else "Connecting to the desktop…", style = type.base, color = colors.textSecondary)
                }
                is DesktopConnection.Disconnected, is DesktopConnection.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (c is DesktopConnection.Failed) "The desktop refused the connection" else "The desktop connection ended", style = type.title, color = colors.textPrimary)
                    Text(connectionLabel(c, session), style = type.small, color = colors.textTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CursorButton("Reconnect", onReconnect, icon = CursorIcons.Refresh, height = 30.dp)
                        CursorButton("Close", onClose, height = 30.dp)
                    }
                }
                is DesktopConnection.Connected -> Unit
            }
        }
    }
}

private fun connectionLabel(connection: DesktopConnection, session: DesktopSession): String = when (connection) {
    DesktopConnection.Loading -> "Loading noVNC"
    DesktopConnection.Connecting -> listOfNotNull("Connecting", session.port?.let { "port $it" }).joinToString(" · ")
    is DesktopConnection.Connected -> listOfNotNull("Connected", connection.desktopName?.takeIf { it.isNotBlank() }, session.port?.let { "port $it" }).joinToString(" · ")
    is DesktopConnection.Disconnected -> when {
        connection.reason != null -> "Disconnected: ${connection.reason}"
        connection.clean -> "Disconnected by the desktop"
        else -> "The connection dropped: the VM may have stopped, or the ticket ran out"
    }
    is DesktopConnection.Failed -> connection.reason
}

/**
 * noVNC in a WebView. The page and its modules are served from the app's assets under [DesktopPage.ORIGIN] by
 * intercepting every request to that host, so the ES modules load same-origin and nothing else is reachable from
 * the page; the websockify URL — with its ticket — is handed to the page by a script call once it has loaded, never
 * put in a URL the WebView would keep, and the page reports back through [Bridge]. Nothing is cached or stored.
 */
@Composable
private fun DesktopWebView(session: DesktopSession, onConnection: (DesktopConnection) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bridge = remember { Bridge() }
    bridge.onConnection = onConnection
    var pageReady by remember { mutableStateOf(false) }
    bridge.onReady = { pageReady = true }
    val webView = remember(context) { createDesktopWebView(context, bridge) }
    DisposableEffect(webView) {
        webView.loadUrl(DesktopPage.URL)
        onDispose {
            webView.evaluateJavascript("window.cursorDesktop && window.cursorDesktop.disconnect();", null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    // Connect once the page is ready, and again whenever the session's URL changes (a reconnect minted a new ticket).
    LaunchedEffect(pageReady, session.url) {
        if (pageReady) {
            onConnection(DesktopConnection.Connecting)
            webView.evaluateJavascript("window.cursorDesktop.connect(${jsString(session.url)}, ${session.viewOnly});", null)
        }
    }
    LaunchedEffect(pageReady, session.viewOnly) {
        if (pageReady) webView.evaluateJavascript("window.cursorDesktop.setViewOnly(${session.viewOnly});", null)
    }
    AndroidView(factory = { webView }, modifier = modifier.testTag("desktop-webview"))
}

/** What the page calls back into: [onState] with noVNC's events, [onReady] once its script is up. */
private class Bridge {
    var onConnection: (DesktopConnection) -> Unit = {}
    var onReady: () -> Unit = {}

    @JavascriptInterface
    fun onState(state: String, detail: String?) {
        val d = detail?.takeIf { it.isNotBlank() }
        val connection = when (state) {
            "ready" -> {
                onReady()
                return
            }
            "connecting" -> DesktopConnection.Connecting
            "connected" -> DesktopConnection.Connected()
            "name" -> DesktopConnection.Connected(d)
            "disconnected" -> DesktopConnection.Disconnected(clean = d == "clean")
            "failed" -> DesktopConnection.Failed(d ?: "The desktop refused the connection.")
            else -> return
        }
        onConnection(connection)
    }
}

/** JavaScript is the point: noVNC is a script. Nothing but the app's own assets can run in this view (see the client below). */
@SuppressLint("SetJavaScriptEnabled")
private fun createDesktopWebView(context: Context, bridge: Bridge): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    setBackgroundColor(android.graphics.Color.BLACK)
    isVerticalScrollBarEnabled = false
    isHorizontalScrollBarEnabled = false
    addJavascriptInterface(bridge, "AndroidDesktop")
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != DesktopPage.HOST) return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
            val path = url.path.orEmpty().trimStart('/').ifEmpty { "desktop.html" }
            return try {
                val stream = view.context.assets.open("${DesktopPage.ASSET_DIR}/$path")
                WebResourceResponse(mimeTypeOf(path), "UTF-8", stream)
            } catch (_: IOException) {
                WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != DesktopPage.HOST
    }
}

private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "")) {
    "html" -> "text/html"
    "js", "mjs" -> "text/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

/** A JavaScript string literal for [value], quoted and escaped the JSON way. */
internal fun jsString(value: String): String = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))
