package com.cursorforandroid.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import com.cursorforandroid.domain.DesktopFailure
import com.cursorforandroid.domain.DesktopPage
import com.cursorforandroid.domain.DesktopSession
import com.cursorforandroid.domain.DesktopStep
import com.cursorforandroid.domain.DesktopTrace
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorToggle
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Where the viewer stands, step by step, the way the Agents Window's own desktop tab moves: the page, its script,
 * the socket handshake, the first frame — then live, ended, or failed at a named step.
 */
sealed interface ViewerPhase {
    data object LoadingPage : ViewerPhase
    data object StartingScript : ViewerPhase
    data object Connecting : ViewerPhase
    /** The RFB handshake is done; [firstFrame] once the display has flipped (or three seconds have passed, as the Agents Window allows). */
    data class Connected(val desktopName: String? = null, val firstFrame: Boolean = false) : ViewerPhase
    /** The connection ended; [clean] when the far end closed it in order, else it dropped. */
    data class Ended(val clean: Boolean, val reason: String? = null) : ViewerPhase

    val isLive: Boolean get() = this is Connecting || this is Connected
}

/**
 * The agent's desktop, full screen in its own window, for the whole of the way there: the steps while the machine
 * is found and probed, the failure — with the step that failed, what the server or socket said, a retry and the
 * diagnostics to share — and the viewer itself once a websockify URL has answered. Back and the X end it.
 */
@Composable
fun DesktopDialog(
    state: DesktopState,
    agentName: String?,
    onViewOnlyChange: (Boolean) -> Unit,
    onRetry: (viewOnly: Boolean) -> Unit,
    onFail: (DesktopFailure) -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
) {
    if (state is DesktopState.Idle) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        DesktopScreen(state, agentName, onViewOnlyChange, onRetry, onFail, onShare, onClose, Modifier.fillMaxSize())
    }
}

@Composable
fun DesktopScreen(
    state: DesktopState,
    agentName: String?,
    onViewOnlyChange: (Boolean) -> Unit,
    onRetry: (viewOnly: Boolean) -> Unit,
    onFail: (DesktopFailure) -> Unit,
    onShare: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val session = (state as? DesktopState.Open)?.session
    val viewer = remember(session?.url) { session?.let { DesktopViewer(it) } }
    val phase = viewer?.phase
    val view = LocalView.current
    // Awake while the desktop is live: a screen that dims mid-session drops the VNC connection with it.
    DisposableEffect(phase?.isLive) {
        if (phase?.isLive == true) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val viewOnly = when (state) {
        is DesktopState.Open -> state.session.viewOnly
        is DesktopState.Opening -> state.viewOnly
        is DesktopState.Failed -> state.viewOnly
        DesktopState.Idle -> true
    }
    Column(modifier.background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing).testTag("desktop-screen")) {
        Row(Modifier.fillMaxWidth().background(colors.sidebar).heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatIconButton(CursorIcons.Close, "Close the desktop", onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName ?: "Desktop", style = type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(state, phase), style = type.tiny, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("desktop-connection"))
            }
            if (session != null) {
                Text(if (viewOnly) "View only" else "In control", style = type.small, color = colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                CursorToggle(checked = !viewOnly, onCheckedChange = { control -> onViewOnlyChange(!control) }, modifier = Modifier.testTag("desktop-control-toggle"))
                Spacer(Modifier.width(4.dp))
            }
            FlatIconButton(CursorIcons.Refresh, "Reconnect", onClick = { onRetry(viewOnly) })
        }
        HairlineDivider()
        Box(Modifier.fillMaxSize()) {
            when (state) {
                DesktopState.Idle -> Unit
                is DesktopState.Opening -> ProgressView(state.trace, Modifier.align(Alignment.Center))
                is DesktopState.Failed -> FailureView(state.failure, onRetry = { onRetry(viewOnly) }, onShare = { onShare(state.failure.trace.report()) }, onClose = onClose, modifier = Modifier.align(Alignment.Center))
                is DesktopState.Open -> {
                    val current = viewer!!
                    if (!LocalInspectionMode.current) {
                        DesktopWebView(current, modifier = Modifier.fillMaxSize())
                    }
                    // Every step gets ten seconds (the Agents Window's own connect timeout); the one that runs out is
                    // the one the failure names, with what the page, the server or the socket said.
                    LaunchedEffect(current) {
                        current.watch(onFail)
                    }
                    when (val p = current.phase) {
                        ViewerPhase.LoadingPage, ViewerPhase.StartingScript, ViewerPhase.Connecting -> ProgressView(current.trace, Modifier.align(Alignment.Center))
                        is ViewerPhase.Connected -> if (!p.firstFrame) ProgressView(current.trace, Modifier.align(Alignment.Center))
                        is ViewerPhase.Ended -> FailureView(
                            DesktopFailure.Unreachable(endedMessage(p), current.trace),
                            title = if (p.clean) "The desktop closed the connection" else "The desktop connection dropped",
                            onRetry = { onRetry(viewOnly) },
                            onShare = { onShare(current.trace.report()) },
                            onClose = onClose,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

private fun statusLine(state: DesktopState, phase: ViewerPhase?): String = when (state) {
    DesktopState.Idle -> ""
    is DesktopState.Opening -> state.trace.current?.let { "${it.name}…" } ?: "Finding the machine…"
    is DesktopState.Failed -> state.failure.trace.lastFailed?.let { "Failed at ${it.name}" } ?: "Couldn't open the desktop"
    is DesktopState.Open -> {
        val port = state.session.port?.let { "port $it" }
        when (phase) {
            null, ViewerPhase.LoadingPage -> "Loading the viewer"
            ViewerPhase.StartingScript -> "Starting noVNC"
            ViewerPhase.Connecting -> listOfNotNull("Connecting", port).joinToString(" · ")
            is ViewerPhase.Connected -> listOfNotNull(if (phase.firstFrame) "Connected" else "Connected · waiting for the first frame", phase.desktopName?.takeIf { it.isNotBlank() }, port).joinToString(" · ")
            is ViewerPhase.Ended -> if (phase.clean) "Disconnected by the desktop" else "The connection dropped"
        }
    }
}

private fun endedMessage(ended: ViewerPhase.Ended): String = when {
    ended.reason != null -> "Disconnected: ${ended.reason}"
    ended.clean -> "The desktop closed the connection in order: the VM may have stopped, or the session was ended elsewhere."
    else -> "The connection dropped: the VM may have stopped, the network changed, or the desktop ticket ran out."
}

/** The steps so far, the running one with a spinner: what the reader waits on, by name. */
@Composable
private fun ProgressView(trace: DesktopTrace, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = trace.steps.takeLast(5)
        if (shown.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpinnerRing(size = 14.dp, color = colors.textSecondary)
                Spacer(Modifier.width(10.dp))
                Text("Finding the machine…", style = type.base, color = colors.textSecondary)
            }
        }
        shown.forEach { step -> StepRow(step) }
    }
}

@Composable
private fun StepRow(step: DesktopStep) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("desktop-step")) {
        when {
            step.isRunning -> SpinnerRing(size = 14.dp, color = colors.textSecondary)
            step.failed -> Icon(CursorIcons.Warning, null, tint = colors.red, modifier = Modifier.size(14.dp))
            else -> Icon(CursorIcons.Check, null, tint = colors.green, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(step.name.replaceFirstChar { it.uppercase() }, style = type.base, color = if (step.isRunning) colors.textPrimary else colors.textSecondary)
            val detail = listOfNotNull(step.outcome, step.durationMillis?.let { "$it ms" }).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = type.small, color = if (step.failed) colors.red else colors.textQuaternary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The failure, in the words the step left: which step, what the server or the socket said, every step before it,
 * then Retry (where asking again can help), the diagnostics to share — redacted, see [DesktopTrace.report] — and Close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureView(failure: DesktopFailure, onRetry: () -> Unit, onShare: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val failed = failure.trace.lastFailed
    Column(modifier.padding(24.dp).verticalScroll(rememberScrollState()).testTag("desktop-failure"), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title ?: failed?.let { "Failed at ${it.name}" } ?: "Couldn't open the desktop", style = type.title, color = colors.textPrimary)
        Text(failure.message, style = type.base, color = colors.textSecondary, modifier = Modifier.testTag("desktop-failure-message"))
        if (failure.trace.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                failure.trace.steps.takeLast(6).forEach { step -> StepRow(step) }
            }
        }
        failure.trace.host?.let { host -> Text("Endpoint: $host${failure.trace.port?.let { ":$it" } ?: ""}", style = type.small, color = colors.textQuaternary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (failure.retryable) CursorButton("Retry", onRetry, icon = CursorIcons.Refresh, height = 30.dp, modifier = Modifier.testTag("desktop-retry"))
            CursorButton("Share diagnostics", onShare, icon = CursorIcons.Link, height = 30.dp, modifier = Modifier.testTag("desktop-share-diagnostics"))
            CursorButton("Close", onClose, height = 30.dp)
        }
    }
}

/**
 * One viewer session's state and record: the phase, the trace it adds to the session's, and the page's own word
 * (the bridge and the state the Kotlin side reads back), kept outside the composition so a recomposition never
 * loses a step. Created per websockify URL.
 */
internal class DesktopViewer(val session: DesktopSession, private val now: () -> Long = AppClock::now) {
    var phase by mutableStateOf<ViewerPhase>(ViewerPhase.LoadingPage)
        private set
    var trace by mutableStateOf(session.trace.begin(DesktopTrace.PAGE, now()))
        private set

    /** The last thing the page reported through the bridge or its readable state: what the failure quotes. */
    @Volatile var lastPageDetail: String? = null
    @Volatile var pageLoaded: Boolean = false
    @Volatile var scriptReady: Boolean = false
    @Volatile var pageError: String? = null
    @Volatile var pageState: PageState? = null
    var webView: WebView? = null

    /** What the page's `cursorDesktop.state()` says, read back by script so a broken bridge cannot hide it. */
    data class PageState(val phase: String, val detail: String?, val frames: Int, val ready: Boolean, val error: String?, val name: String? = null, val log: String? = null) {
        /** What the page can say about a failure: its own word first, then noVNC's last logged error. */
        val reason: String? get() = listOfNotNull(error, log?.takeIf { it != error }).joinToString("; ").takeIf { it.isNotBlank() }
    }

    fun onPageFinished() {
        if (!pageLoaded) {
            pageLoaded = true
            trace = trace.end(DesktopTrace.PAGE, now(), "loaded").begin(DesktopTrace.SCRIPT, now())
            if (phase == ViewerPhase.LoadingPage) phase = ViewerPhase.StartingScript
        }
    }

    fun onPageFailed(detail: String) {
        pageError = detail
    }

    /** The bridge's `ready`, or the state read back: noVNC's modules are up. */
    fun onScriptReady() {
        if (!scriptReady) {
            scriptReady = true
            onPageFinished()
            trace = trace.end(DesktopTrace.SCRIPT, now(), "noVNC loaded")
        }
    }

    fun onConnecting() {
        if (phase == ViewerPhase.Connecting || phase is ViewerPhase.Connected) return
        trace = trace.begin(DesktopTrace.SOCKET, now())
        phase = ViewerPhase.Connecting
    }

    fun onConnected(name: String? = null) {
        val current = phase
        if (current is ViewerPhase.Connected) {
            if (name != null && current.desktopName == null) phase = current.copy(desktopName = name)
            return
        }
        trace = trace.end(DesktopTrace.SOCKET, now(), "connected").begin(DesktopTrace.FIRST_FRAME, now())
        phase = ViewerPhase.Connected(name)
    }

    fun onFirstFrame(observed: Boolean) {
        val current = phase as? ViewerPhase.Connected ?: return
        if (current.firstFrame) return
        trace = trace.end(DesktopTrace.FIRST_FRAME, now(), if (observed) "drawn" else "not observed within 3 s; showing anyway")
        phase = current.copy(firstFrame = true)
    }

    fun onEnded(clean: Boolean, detail: String?) {
        val step = trace.current?.name
        if (step != null) trace = trace.end(step, now(), detail ?: if (clean) "closed by the desktop" else "dropped", failed = !clean)
        phase = ViewerPhase.Ended(clean, detail)
    }

    /** The step under way ran out of time, or the page said it failed: the failure the viewer hands up, with its trace. */
    fun failure(step: String, message: String): DesktopFailure {
        trace = trace.end(step, now(), message, failed = true)
        return DesktopFailure.Unreachable(message, trace)
    }

    /**
     * Drives the steps and their timeouts on the composition's clock: the page (ten seconds), the script (ten), the
     * socket (ten), the first frame (three, then assumed). The page's own state is read back every quarter second
     * as well, so the steps move even if the JavaScript bridge never calls in.
     */
    suspend fun watch(onFail: (DesktopFailure) -> Unit) {
        if (!await(STEP_TIMEOUT_MS) { pageLoaded || pageError != null || pageState != null }) {
            onFail(failure(DesktopTrace.PAGE, "The viewer page did not load within ${STEP_TIMEOUT_MS / 1000} seconds${pageError?.let { ": $it" } ?: ""}."))
            return
        }
        pageError?.let { error ->
            onFail(failure(DesktopTrace.PAGE, "The viewer page failed to load: $error"))
            return
        }
        onPageFinished()
        if (!await(STEP_TIMEOUT_MS) { scriptReady || pageState?.ready == true }) {
            onFail(failure(DesktopTrace.SCRIPT, "noVNC did not start within ${STEP_TIMEOUT_MS / 1000} seconds${(pageState?.reason ?: pageError)?.let { ": $it" } ?: " (its modules did not load)"}."))
            return
        }
        onScriptReady()
        onConnecting()
        webView?.evaluateJavascript("window.cursorDesktop.connect(${jsString(session.url)}, ${session.viewOnly});", null)
        if (!await(STEP_TIMEOUT_MS) { phase !is ViewerPhase.Connecting }) {
            val detail = pageState?.reason ?: lastPageDetail
            onFail(failure(DesktopTrace.SOCKET, "The socket handshake did not complete within ${STEP_TIMEOUT_MS / 1000} seconds${detail?.let { ": $it" } ?: ""}. The VM may have stopped, or the ticket may be refused."))
            return
        }
        if (phase is ViewerPhase.Ended) return
        val drawn = await(FIRST_FRAME_MS) { (pageState?.frames ?: 0) > 0 || (phase as? ViewerPhase.Connected)?.firstFrame == true }
        onFirstFrame(observed = drawn)
    }

    private suspend fun await(timeoutMs: Long, done: () -> Boolean): Boolean = withTimeoutOrNull(timeoutMs) {
        while (!done()) {
            readBack()
            delay(POLL_MS)
        }
        true
    } ?: done()

    /** Asks the page for its state; applies whatever it says. Nothing happens under Robolectric, where scripts do not run. */
    private suspend fun readBack() {
        val view = webView ?: return
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            try {
                view.evaluateJavascript(READ_STATE_SCRIPT) { value -> if (continuation.isActive) continuation.resume(value) }
            } catch (_: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        } ?: return
        val state = parsePageState(raw) ?: return
        pageState = state
        if (state.ready) onScriptReady()
        when (state.phase) {
            "connecting" -> onConnecting()
            "connected" -> {
                onConnected(state.name)
                if (state.frames > 0) onFirstFrame(observed = true)
            }
            "disconnected" -> onEnded(clean = state.detail == "clean", detail = if (state.detail == "clean") null else state.reason)
            "failed" -> onEnded(clean = false, detail = state.reason ?: state.detail)
        }
    }

    companion object {
        const val STEP_TIMEOUT_MS = 10_000L
        const val FIRST_FRAME_MS = 3_000L
        const val POLL_MS = 250L
        /** The page's state as one JSON object, or the string `none` while its module has not run. */
        const val READ_STATE_SCRIPT = "(function(){try{return window.cursorDesktop?JSON.stringify(window.cursorDesktop.state()):(window.__cursorDesktopError?JSON.stringify({phase:'error',error:window.__cursorDesktopError}):'none')}catch(e){return JSON.stringify({phase:'error',error:String(e)})}})()"

        /** `evaluateJavascript` hands back a JSON-encoded value: a string holding the page's JSON, or `"none"`. */
        fun parsePageState(raw: String): PageState? {
            val outer = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
            val text = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (text == "none") return null
            val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            fun str(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return PageState(
                phase = str("phase") ?: "idle",
                detail = str("detail"),
                frames = (json["frames"] as? JsonPrimitive)?.intOrNull ?: 0,
                ready = (json["ready"] as? JsonPrimitive)?.booleanOrNull ?: (json["phase"] != null && str("phase") != "error"),
                error = str("error"),
                name = str("name"),
                log = str("log"),
            )
        }
    }
}

/**
 * noVNC in a WebView. The page and its modules are served from the app's assets under [DesktopPage.ORIGIN] by
 * intercepting every request to that host, so the ES modules load same-origin and nothing else is reachable from
 * the page; the websockify URL — with its ticket — is handed to the page by a script call once it has loaded, never
 * put in a URL the WebView would keep. The page reports back through [Bridge], and the [DesktopViewer] reads its
 * state back by script as well, so a bridge the build broke cannot leave the viewer loading forever. Nothing is
 * cached or stored.
 */
@Composable
private fun DesktopWebView(viewer: DesktopViewer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val webView = remember(viewer) { createDesktopWebView(context, viewer) }
    DisposableEffect(webView) {
        viewer.webView = webView
        webView.loadUrl(DesktopPage.URL)
        onDispose {
            viewer.webView = null
            webView.evaluateJavascript("window.cursorDesktop && window.cursorDesktop.disconnect();", null)
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(viewer.session.viewOnly, viewer.phase is ViewerPhase.Connected) {
        if (viewer.phase is ViewerPhase.Connected) webView.evaluateJavascript("window.cursorDesktop.setViewOnly(${viewer.session.viewOnly});", null)
    }
    AndroidView(factory = { webView }, modifier = modifier.testTag("desktop-webview"))
}

/** What the page calls back into: [onState] with noVNC's events, `ready` once its script is up. */
private class Bridge(private val viewer: DesktopViewer) {
    @JavascriptInterface
    fun onState(state: String, detail: String?) {
        val d = detail?.takeIf { it.isNotBlank() }
        viewer.lastPageDetail = d ?: viewer.lastPageDetail
        when (state) {
            "ready" -> viewer.onScriptReady()
            "connecting" -> viewer.onConnecting()
            "connected" -> viewer.onConnected()
            "name" -> viewer.onConnected(d)
            "frame" -> viewer.onFirstFrame(observed = true)
            "disconnected" -> viewer.onEnded(clean = d == "clean", detail = null)
            "failed" -> viewer.onEnded(clean = false, detail = d ?: "The desktop refused the connection.")
            "error" -> viewer.onPageFailed(d ?: "script error")
        }
    }
}

/** JavaScript is the point: noVNC is a script. Nothing but the app's own assets can run in this view (see the client below). */
@SuppressLint("SetJavaScriptEnabled")
private fun createDesktopWebView(context: Context, viewer: DesktopViewer): WebView = WebView(context).apply {
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
    addJavascriptInterface(Bridge(viewer), "AndroidDesktop")
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

        override fun onPageFinished(view: WebView, url: String?) {
            if (url == null || url.startsWith(DesktopPage.ORIGIN)) viewer.onPageFinished()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) viewer.onPageFailed("${error.description} (${error.errorCode}) for ${request.url.path}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            // A missing module is the page's failure as much as a missing page: name the path so the report says
            // which. A favicon the browser asks for on its own is not.
            val path = request.url.path.orEmpty()
            if (request.isForMainFrame || path.endsWith(".js") || path.endsWith(".html")) viewer.onPageFailed("HTTP ${errorResponse.statusCode} for $path")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            viewer.onEnded(clean = false, detail = if (detail.didCrash()) "the viewer's renderer crashed" else "the viewer's renderer was stopped by the system")
            return true
        }
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
