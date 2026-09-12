package com.cursorforandroid.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** How one websockify URL answered the probe. */
sealed interface ProbeResult {
    /** The WebSocket handshake completed: a VNC server is behind the URL. */
    data object Reachable : ProbeResult

    /** The handshake was refused with an HTTP status: the host answers, but not for this token or path. */
    data class Refused(val httpCode: Int, val message: String) : ProbeResult

    /** No answer: the name did not resolve, the connection was reset, or it timed out. */
    data class Unreachable(val message: String) : ProbeResult
}

/**
 * Opens a WebSocket to each candidate desktop URL until one accepts the handshake — the way to tell which of the
 * ports Cursor's bundle builds (spec §9, item 5) serves this pod's desktop before a WebView is pointed at it, and to
 * give a stopped or hibernated VM a named state rather than a black canvas. The socket is closed the moment it opens;
 * websockify tolerates that. The `Origin` the WebView will send goes along, so a refusal shows here first.
 */
open class DesktopProbe(
    /** Built on first use: the probe belongs to Extended mode, and a default-mode graph never needs its client. */
    private val client: () -> OkHttpClient,
    private val origin: String? = null,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** The first candidate that answered, or the failures of every one in order. */
    suspend fun firstReachable(urls: List<String>): Pair<String?, List<ProbeResult>> {
        val failures = ArrayList<ProbeResult>()
        for (url in urls) {
            when (val result = probe(url)) {
                ProbeResult.Reachable -> return url to failures
                else -> failures += result
            }
        }
        return null to failures
    }

    open suspend fun probe(url: String): ProbeResult {
        val request = Request.Builder()
            .url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))
            .apply { if (origin != null) header("Origin", origin) }
            .header("Sec-WebSocket-Protocol", "binary")
            .build()
        val probing = client().newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        return withTimeoutOrNull(timeoutMs + 1_000) {
            suspendCancellableCoroutine { continuation ->
                val socket = probing.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            // Torn down rather than closed in order: the answer is in, and a closing handshake would
                            // only hold a VNC slot open for nothing.
                            webSocket.cancel()
                            if (continuation.isActive) continuation.resume(ProbeResult.Reachable)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!continuation.isActive) return
                            val result = when {
                                response != null -> ProbeResult.Refused(response.code, "The desktop endpoint answered HTTP ${response.code}.")
                                t is IOException -> ProbeResult.Unreachable(t.message?.takeIf { it.isNotBlank() } ?: "No answer from the desktop endpoint.")
                                else -> ProbeResult.Unreachable(t.message ?: "The desktop endpoint could not be reached.")
                            }
                            continuation.resume(result)
                        }
                    },
                )
                continuation.invokeOnCancellation { socket.cancel() }
            }
        } ?: ProbeResult.Unreachable("The desktop endpoint did not answer in time.")
    }

    companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
